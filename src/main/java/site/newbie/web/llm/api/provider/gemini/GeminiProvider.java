package site.newbie.web.llm.api.provider.gemini;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import org.springframework.context.annotation.Lazy;
import site.newbie.web.llm.api.provider.command.Command;
import site.newbie.web.llm.api.provider.command.CommandParser;
import site.newbie.web.llm.api.provider.command.CommandHandler;
import site.newbie.web.llm.api.provider.gemini.command.AttachDriveCommand;
import site.newbie.web.llm.api.provider.gemini.command.AttachLocalFileCommand;
import site.newbie.web.llm.api.provider.gemini.model.GeminiModelConfig;
import site.newbie.web.llm.api.provider.gemini.model.GeminiModelConfig.GeminiContext;
import site.newbie.web.llm.api.util.ConversationIdUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gemini 统一提供者
 * 通过依赖 GeminiModelConfig 实现不同模型的差异化处理
 */
@Slf4j
@Component
public class GeminiProvider implements LLMProvider {

    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper;
    private final Map<String, GeminiModelConfig> modelConfigs;
    private final ProviderRegistry providerRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // 全局指令解析器，支持全局指令和 Gemini 特定指令
    private final CommandParser commandParser;

    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    // 存储 conversationId -> Page 的映射，用于指令执行后保留的 tab
    private final ConcurrentHashMap<String, Page> conversationPages = new ConcurrentHashMap<>();

    // Gemini 只支持 DOM 模式
    private static final String MONITOR_MODE = "dom";

    private final ModelConfig.ResponseHandler responseHandler = new ModelConfig.ResponseHandler() {
        @Override
        public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
            GeminiProvider.this.sendSseChunk(emitter, id, content, model);
        }

        @Override
        public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
            GeminiProvider.this.sendThinkingContent(emitter, id, content, model);
        }

        @Override
        public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
            GeminiProvider.this.sendUrlAndComplete(page, emitter, request);
        }

        @Override
        public String getSseData(Page page, String varName) {
            // Gemini 不支持 SSE，返回 null
            return null;
        }

        @Override
        public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
            // Gemini 不支持 SSE，返回空结果
            return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), lastActiveFragmentIndex);
        }

        @Override
        public String extractTextFromSse(String sseData) {
            // Gemini 不支持 SSE，返回 null
            return null;
        }
    };

    public GeminiProvider(BrowserManager browserManager, ObjectMapper objectMapper,
                          List<GeminiModelConfig> configs, @Lazy ProviderRegistry providerRegistry) {
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(GeminiModelConfig::getModelName, Function.identity()));
        
        // 创建指令解析器，支持全局指令和 Gemini 特定指令
        this.commandParser = new CommandParser(this::createProviderCommand);
        
        log.info("GeminiProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
    }
    
    @Override
    public CommandParser getCommandParser() {
        return commandParser;
    }
    
    /**
     * 创建 Gemini provider 特定指令
     */
    private Command createProviderCommand(String commandName, String param, String extra) {
        commandName = commandName.toLowerCase();
        
        // 如果 param 为空，尝试从 extra 中提取
        if (param == null && extra != null) {
            String[] parts = extra.split("\\s+", 2);
            if (parts.length > 0) {
                param = parts[0];
            }
        }
        
        switch (commandName) {
            case "attach-drive":
            case "attach-google-drive":
                if (param != null && !param.trim().isEmpty()) {
                    return new AttachDriveCommand(param.trim());
                }
                log.warn("attach-drive 指令缺少文件名参数");
                return null;
                
            case "attach-local":
            case "attach-file":
                if (param != null && !param.trim().isEmpty()) {
                    return new AttachLocalFileCommand(param.trim());
                }
                log.warn("attach-local 指令缺少文件路径参数");
                return null;
                
            default:
                return null;
        }
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.copyOf(modelConfigs.keySet());
    }

    @Override
    public boolean checkLoginStatus(Page page) {
        try {
            if (page == null || page.isClosed()) {
                log.warn("页面为空或已关闭，无法检查登录状态");
                return false;
            }

            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(1000);

            // 检查URL：如果URL包含登录相关路径，说明未登录
            String url = page.url();
            if (url.contains("/signin") || url.contains("/login") || url.contains("/auth")) {
                log.info("检测到登录页面URL: {}", url);
                return false;
            }

            // 优先检查登录按钮（未登录会有登录按钮）
            // 如果明确发现登录按钮，直接判断为未登录，优先级高于输入框检查
            // 根据实际 DOM 结构，登录按钮是 <a> 标签，带有 aria-label="登录" 和 href 包含 ServiceLogin
            Locator loginButton = page.locator("a[aria-label='登录']")
                    .or(page.locator("a[aria-label='Sign in']"))
                    .or(page.locator("a[href*='ServiceLogin']"))
                    .or(page.locator("button:has-text('登录')"))
                    .or(page.locator("button:has-text('Sign in')"))
                    .or(page.locator("a:has-text('登录')"))
                    .or(page.locator("a:has-text('Sign in')"))
                    .or(page.locator("a[href*='signin']"));

            if (loginButton.count() > 0 && loginButton.first().isVisible()) {
                log.info("检测到登录按钮，判断为未登录");
                return false;
            }

            // 检查是否存在输入框（已登录会有输入框）
            // 使用 div[role='textbox'] 作为主要选择器
            Locator inputBox = page.locator("div[role='textbox']")
                    .or(page.locator("textarea[placeholder*='输入']"))
                    .or(page.locator("textarea[placeholder*='Enter a prompt']"))
                    .or(page.locator("textarea[aria-label*='prompt']"));

            if (inputBox.count() > 0 && inputBox.first().isVisible()) {
                log.info("检测到输入框，判断为已登录");
                return true;
            }

            // 如果URL是聊天页面且没有登录按钮，认为已登录
            if (url.contains("gemini.google.com") || url.contains("ai.google.dev")) {
                log.info("在聊天页面且未检测到登录按钮，判断为已登录");
                return true;
            }

            // 默认返回false（保守策略）
            log.warn("无法确定登录状态，默认返回未登录");
            return false;
        } catch (Exception e) {
            log.error("检查登录状态时出错: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public LoginInfo getLoginInfo(Page page) {
        boolean loggedIn = checkLoginStatus(page);
        if (!loggedIn) {
            return LoginInfo.notLoggedIn();
        }

        return LoginInfo.loggedIn();
    }

    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        executor.submit(() -> {
            Page page = null;
            try {
                String model = request.getModel();
                GeminiModelConfig config = modelConfigs.get(model);

                if (config == null) {
                    throw new IllegalArgumentException("不支持的模型: " + model);
                }

                // 注意：指令检查已在 Controller 层统一处理，这里只处理普通聊天请求
                page = getOrCreatePage(request);

                // 检查是否找到了页面（对于非新对话，必须找到对应的 tab）
                if (page == null) {
                    String conversationId = getConversationId(request);
                    if (conversationId != null && !conversationId.isEmpty() && !isNewConversation(request)) {
                        // 找不到对应的 tab，返回系统错误
                        log.error("找不到对应的 tab: conversationId={}", conversationId);
                        sendSystemError(emitter, request.getModel(),
                                "系统错误：找不到对应的对话 Tab。该 Tab 可能已被关闭或丢失。请重新开始对话。");
                        return;
                    }
                    // 如果是新对话但 page 为 null，说明创建失败
                    throw new RuntimeException("无法创建或获取页面");
                }

                // 检查登录状态
                if (!checkLoginStatus(page)) {
                    log.warn("检测到未登录状态，发送手动登录提示");

                    String conversationId = getConversationId(request);
                    if (conversationId == null || conversationId.isEmpty()) {
                        conversationId = "login-" + UUID.randomUUID();
                    }

                    sendManualLoginPrompt(emitter, request.getModel(), getProviderName(), conversationId);
                    return;
                }

                if (isNewConversation(request)) {
                    clickNewChatButton(page);
                }

                config.configure(page);

                // 处理内置指令（如添加附件）- 这些指令会作为附件添加，但消息仍会发送
                processCommands(page, request);

                sendMessage(page, request);

                int messageCountBefore = countMessages(page);

                GeminiContext context = new GeminiContext(
                        page, emitter, request, messageCountBefore, MONITOR_MODE, responseHandler
                );
                config.monitorResponse(context);

            } catch (Exception e) {
                log.error("Chat Error", e);
                emitter.completeWithError(e);
                cleanupPageOnError(page, request.getModel());
            }
        });
    }

    // ==================== 页面管理 ====================

    @Override
    public Page getOrCreatePage(ChatCompletionRequest request) {
        String model = request.getModel();
        String conversationId = getConversationId(request);
        boolean isNew = isNewConversation(request);

        if (!isNew && conversationId != null) {
            // 优先从 conversationPages 中查找（指令执行后保留的 tab）
            Page page = conversationPages.get(conversationId);
            if (page != null && !page.isClosed()) {
                // 如果是临时 ID（command- 开头），检查页面是否已经有了真正的 conversationId
                if (conversationId.startsWith("command-")) {
                    String currentUrl = page.url();
                    String realConversationId = extractConversationIdFromUrl(currentUrl);
                    if (realConversationId != null && !realConversationId.isEmpty()) {
                        // 页面已经有了真正的 conversationId，更新映射关系
                        conversationPages.remove(conversationId); // 移除临时 ID
                        conversationPages.put(realConversationId, page); // 使用真正的 ID
                        pageUrls.put(model, currentUrl);
                        modelPages.put(model, page);
                        log.info("临时 ID 已更新为真正的 conversationId: tempId={}, realId={}, url={}", 
                            conversationId, realConversationId, currentUrl);
                        return page;
                    } else {
                        // 还是临时 ID，直接使用
                        log.info("找到已保留的 tab（临时 ID）: tempConversationId={}, url={}", conversationId, currentUrl);
                        modelPages.put(model, page);
                        pageUrls.put(model, currentUrl);
                        return page;
                    }
                } else {
                    // 真正的 conversationId，验证页面 URL 是否匹配
                    String expectedUrl = buildUrlFromConversationId(conversationId);
                    String currentUrl = page.url();
                    if (expectedUrl.equals(currentUrl) || currentUrl.contains(conversationId)) {
                        log.info("找到已保留的 tab: conversationId={}, url={}", conversationId, currentUrl);
                        modelPages.put(model, page);
                        pageUrls.put(model, currentUrl);
                        return page;
                    } else {
                        // URL 不匹配，尝试导航到正确的 URL
                        try {
                            page.navigate(expectedUrl);
                            page.waitForLoadState();
                            modelPages.put(model, page);
                            pageUrls.put(model, expectedUrl);
                            log.info("已导航到正确的 URL: conversationId={}, url={}", conversationId, expectedUrl);
                            return page;
                        } catch (Exception e) {
                            log.warn("导航到 URL 失败，移除无效的 tab 关联: conversationId={}, error={}", conversationId, e.getMessage());
                            conversationPages.remove(conversationId);
                        }
                    }
                }
            } else if (page != null && page.isClosed()) {
                // 页面已关闭，移除无效关联
                log.warn("已保留的 tab 已关闭，移除关联: conversationId={}", conversationId);
                conversationPages.remove(conversationId);
            }

            // 如果是临时 ID，找不到就直接返回 null（不尝试通过 URL 查找）
            if (conversationId.startsWith("command-")) {
                log.warn("找不到对应的 tab（临时 ID）: conversationId={}", conversationId);
                return null;
            }

            // 如果找不到已保留的 tab，尝试通过 URL 查找（但不创建新页面）
            String conversationUrl = buildUrlFromConversationId(conversationId);
            Page foundPage = findPageByUrl(conversationUrl);

            if (foundPage != null && !foundPage.isClosed()) {
                // 找到了页面，更新映射
                modelPages.put(model, foundPage);
                pageUrls.put(model, conversationUrl);
                conversationPages.put(conversationId, foundPage);
                log.info("通过 URL 找到已存在的 tab: conversationId={}, url={}", conversationId, conversationUrl);
                return foundPage;
            }

            // 找不到 tab，返回 null（会在 streamChat 中处理为系统错误）
            log.warn("找不到对应的 tab: conversationId={}, url={}", conversationId, conversationUrl);
            return null;
        } else {
            return createNewConversationPage(model);
        }
    }

    @Override
    public String getConversationId(ChatCompletionRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }

        if (request.getMessages() != null) {
            conversationId = ConversationIdUtils.extractConversationIdFromRequest(request, true);
        }
        return conversationId;
    }

    @Override
    public boolean isNewConversation(ChatCompletionRequest request) {
        String conversationId = getConversationId(request);
        // command- 开头的临时 ID 不是新对话（应该复用 tab）
        // login- 开头的需要登录，视为新对话
        return conversationId == null || conversationId.isEmpty() || conversationId.startsWith("login-");
    }

    private Page createNewConversationPage(String model) {
        Page oldPage = modelPages.remove(model);
        if (oldPage != null && !oldPage.isClosed()) {
            try {
                oldPage.close();
            } catch (Exception e) {
            }
        }

        Page page = browserManager.newPage(getProviderName());
        modelPages.put(model, page);
        // 使用 /app 路径
        page.navigate("https://gemini.google.com/app");
        page.waitForLoadState();
        // 等待页面加载完成（不等待输入框，因为可能未登录）
        page.waitForTimeout(2000);
        pageUrls.put(model, page.url());
        return page;
    }

    private Page findPageByUrl(String targetUrl) {
        if (targetUrl == null) return null;
        for (String model : modelPages.keySet()) {
            Page page = modelPages.get(model);
            if (page != null && !page.isClosed() && targetUrl.equals(pageUrls.get(model))) {
                return page;
            }
        }
        try {
            for (Page page : browserManager.getAllPages(getProviderName())) {
                if (page != null && !page.isClosed() && targetUrl.equals(page.url())) {
                    return page;
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private void clickNewChatButton(Page page) {
        try {
            page.waitForTimeout(1000);
            // 优先使用侧边栏的新聊天按钮
            Locator newChatButton = page.locator("bard-sidenav-container side-navigation-content mat-action-list.top-action-list button.mat-mdc-list-item")
                    .or(page.locator("button:has-text('新对话')"))
                    .or(page.locator("button:has-text('New chat')"))
                    .or(page.locator("a:has-text('新对话')"))
                    .or(page.locator("a:has-text('New chat')"))
                    .or(page.locator("[aria-label*='New chat']"))
                    .or(page.locator("[aria-label*='新对话']"));

            if (newChatButton.count() > 0) {
                newChatButton.first().click();
                // 等待输入框出现，确保新聊天页面加载完成
                try {
                    page.waitForSelector("div[role='textbox']", new Page.WaitForSelectorOptions().setTimeout(10000));
                } catch (Exception e) {
                    log.warn("等待新聊天页面输入框超时: {}", e.getMessage());
                }
                page.waitForTimeout(500);
                log.info("已点击新对话按钮");
            } else {
                log.warn("未找到新对话按钮，可能需要手动创建新对话");
            }
        } catch (Exception e) {
            log.warn("点击新对话按钮时出错", e);
        }
    }

    @Override
    public CommandHandler.CommandSuccessCallback getCommandSuccessCallback() {
        return this::handleCommandSuccess;
    }
    
    /**
     * 处理指令执行成功后的逻辑（保存 conversationId 等）
     * 这个方法会被 CommandHandler 调用
     */
    private boolean handleCommandSuccess(Page page, String model, SseEmitter emitter, String finalMessage, boolean allSuccess) {
        if (page == null || page.isClosed()) {
            return false; // 没有页面，使用默认处理
        }
        
        try {
            String url = page.url();
            if (url.contains("gemini.google.com") || url.contains("ai.google.dev")) {
                // 等待一下，看看 URL 是否会变化（可能页面还在加载中）
                page.waitForTimeout(1000);
                url = page.url(); // 重新获取 URL
                
                String conversationId = extractConversationIdFromUrl(url);
                if (conversationId != null && !conversationId.isEmpty()) {
                    // 保存 conversationId -> Page 的映射
                    conversationPages.put(conversationId, page);
                    pageUrls.put(model, url);
                    modelPages.put(model, page);
                    log.info("已保存指令执行后的 tab 关联: conversationId={}, url={}", conversationId, url);

                    // 发送 conversationId，让客户端知道这个标识
                    sendCommandResultWithConversationId(emitter, model, finalMessage, conversationId);
                    return true; // 已处理
                } else {
                    // 如果没有 conversationId，生成一个临时 ID（类似 login- 格式）
                    String tempConversationId = "command-" + UUID.randomUUID().toString();
                    conversationPages.put(tempConversationId, page);
                    pageUrls.put(model, url);
                    modelPages.put(model, page);
                    log.info("已保存指令执行后的 tab 关联（使用临时 ID）: tempConversationId={}, url={}", tempConversationId, url);
                    
                    // 发送临时 conversationId，让客户端知道这个标识
                    sendCommandResultWithConversationId(emitter, model, finalMessage, tempConversationId);
                    return true; // 已处理
                }
            }
        } catch (Exception e) {
            log.warn("保存 tab 关联时出错: {}", e.getMessage());
        }
        
        return false; // 未处理，使用默认处理
    }


    /**
     * 发送指令执行结果（包含 conversationId）
     */
    private void sendCommandResultWithConversationId(SseEmitter emitter, String model, String message, String conversationId) {
        try {
            String id = UUID.randomUUID().toString();
            // 先发送结果消息
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(message).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            emitter.send(SseEmitter.event().data(
                    objectMapper.writeValueAsString(response),
                    APPLICATION_JSON_UTF8));

            // 发送 conversationId
            sendConversationId(emitter, UUID.randomUUID().toString(), conversationId, model);

            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
            
            // 立即释放锁（不等待 onCompletion 回调，因为可能有时序问题）
            // 注意：这里需要从外部传入 providerName，但当前方法签名中没有
            // 暂时通过 providerRegistry 获取，或者延迟释放
            // 实际上，锁应该在 Controller 层的 onCompletion 中释放，这里只是确保完成
            log.debug("指令执行结果已发送，emitter 已完成");
        } catch (Exception e) {
            log.error("发送指令结果时出错", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 发送系统错误消息
     */
    private void sendSystemError(SseEmitter emitter, String model, String errorMessage) {
        try {
            String id = UUID.randomUUID().toString();
            String content = "```nwla-system-message\n" + errorMessage + "\n```";
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            emitter.send(SseEmitter.event().data(
                    objectMapper.writeValueAsString(response),
                    APPLICATION_JSON_UTF8));

            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送系统错误时出错", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 处理内置指令（如添加附件）- 用于普通对话中的指令
     *
     * @param page    页面对象
     * @param request 聊天请求
     */
    private void processCommands(Page page, ChatCompletionRequest request) {
        try {
            // 获取用户消息
            String userMessage = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)
                    .map(ChatCompletionRequest.Message::getContent)
                    .orElse(null);

            if (userMessage == null || userMessage.trim().isEmpty()) {
                return;
            }

            // 解析指令
            CommandParser.ParseResult parseResult = commandParser.parse(userMessage);

            if (!parseResult.hasCommands()) {
                return;
            }

            log.info("检测到 {} 个内置指令（作为附件添加）", parseResult.getCommands().size());

            // 执行所有指令（不发送进度）
            for (Command command : parseResult.getCommands()) {
                try {
                    log.info("执行指令: {}", command.getName());
                    boolean success = command.execute(page, null, GeminiProvider.this);
                    if (success) {
                        log.info("指令执行成功: {}", command.getName());
                        // 等待附件上传完成
                        page.waitForTimeout(1000);
                    } else {
                        log.warn("指令执行失败: {}", command.getName());
                    }
                } catch (Exception e) {
                    log.error("执行指令时出错: {}", command.getName(), e);
                }
            }

            // 更新请求消息为清理后的消息（移除指令部分）
            if (!parseResult.getCleanedMessage().equals(userMessage)) {
                // 更新最后一个用户消息的内容
                request.getMessages().stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .reduce((first, second) -> second)
                        .ifPresent(m -> m.setContent(parseResult.getCleanedMessage()));

                log.info("已更新消息内容（移除指令）: 原始长度={}, 新长度={}",
                        userMessage.length(), parseResult.getCleanedMessage().length());
            }

        } catch (Exception e) {
            log.error("处理指令时出错: {}", e.getMessage(), e);
            // 不抛出异常，继续执行消息发送
        }
    }

    private void sendMessage(Page page, ChatCompletionRequest request) {
        try {
            page.waitForTimeout(1000);
            // 优先使用 div[role='textbox'] 作为输入框选择器
            Locator inputBox = page.locator("div[role='textbox']")
                    .or(page.locator("textarea[placeholder*='输入']"))
                    .or(page.locator("textarea[placeholder*='Enter a prompt']"))
                    .or(page.locator("textarea[aria-label*='prompt']"));

            inputBox.waitFor();

            String message = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)
                    .map(ChatCompletionRequest.Message::getContent)
                    .orElse("Hello");

            // 如果消息为空，使用默认消息
            if (message == null || message.trim().isEmpty()) {
                message = "Hello";
            }

            log.info("发送消息: {}", message);
            // 直接 fill 然后按 Enter
            if (inputBox.count() > 0) {
                inputBox.first().fill(message);
                page.waitForTimeout(200);
                inputBox.first().press("Enter");
                log.info("已发送消息（使用 Enter 键）");
            } else {
                throw new RuntimeException("未找到输入框");
            }
            page.waitForTimeout(500);
        } catch (Exception e) {
            log.error("发送消息时出错: {}", e.getMessage(), e);
            throw new RuntimeException("发送消息失败", e);
        }
    }

    private int countMessages(Page page) {
        // 使用 model-response message-content 作为响应选择器
        Locator locators = page.locator("model-response message-content")
                .or(page.locator("[data-message-author-role='model']"))
                .or(page.locator("[data-author='model']"));
        return locators.count();
    }

    private void cleanupPageOnError(Page page, String model) {
        if (page != null) {
            modelPages.remove(model, page);
            // 从 conversationPages 中移除（如果存在）
            conversationPages.entrySet().removeIf(entry -> entry.getValue() == page);
            try {
                if (!page.isClosed()) page.close();
            } catch (Exception e) {
            }
        }
    }

    // ==================== SSE 发送 ====================

    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private void sendSseChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }

    private void sendThinkingContent(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().reasoningContent(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }

    private void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
        try {
            if (!page.isClosed()) {
                String url = page.url();
                if (url.contains("gemini.google.com") || url.contains("ai.google.dev")) {
                    pageUrls.put(request.getModel(), url);
                    String conversationId = extractConversationIdFromUrl(url);
                    if (conversationId != null && !conversationId.isEmpty()) {
                        sendConversationId(emitter, UUID.randomUUID().toString(), conversationId, request.getModel());
                        log.info("已发送对话 ID: {} (从 URL: {})", conversationId, url);
                    } else {
                        log.warn("无法从 URL 中提取对话 ID: {}", url);
                    }
                }
            }
        } catch (Exception e) {
            log.error("发送对话 ID 时出错: {}", e.getMessage());
        }
        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
        emitter.complete();
    }

    private void sendConversationId(SseEmitter emitter, String id, String conversationId, String model) throws IOException {
        String content = "\n\n```nwla-conversation-id\n" + conversationId + "\n```\n\n";
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }

    // ==================== 对话 ID 提取 ====================

    private String extractConversationIdFromUrl(String url) {
        if (url == null || (!url.contains("gemini.google.com") && !url.contains("ai.google.dev"))) {
            return null;
        }

        try {
            // Gemini URL 格式: https://gemini.google.com/app/{conversationId}
            int appIdx = url.indexOf("/app/");
            if (appIdx >= 0) {
                String afterApp = url.substring(appIdx + "/app/".length());
                int queryIdx = afterApp.indexOf('?');
                int fragmentIdx = afterApp.indexOf('#');
                int endIdx = afterApp.length();
                if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);

                String id = afterApp.substring(0, endIdx).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }

            // 兼容旧格式: https://gemini.google.com/chat/{id}
            int chatIdx = url.indexOf("/chat/");
            if (chatIdx >= 0) {
                String afterChat = url.substring(chatIdx + "/chat/".length());
                int queryIdx = afterChat.indexOf('?');
                int fragmentIdx = afterChat.indexOf('#');
                int endIdx = afterChat.length();
                if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);

                String id = afterChat.substring(0, endIdx).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.warn("从 URL 提取对话 ID 失败: url={}, error={}", url, e.getMessage());
        }

        return null;
    }

    private String buildUrlFromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        // 使用 /app/ 路径格式
        return "https://gemini.google.com/app/" + conversationId;
    }

    /**
     * 发送手动登录提示
     * 引导用户在浏览器中手动完成登录
     */
    private void sendManualLoginPrompt(SseEmitter emitter, String model, String providerName, String conversationId) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("```nwla-system-message\n");
            message.append("当前未登录，请在浏览器中手动完成登录。\n\n");
            message.append("浏览器窗口已打开，请按照以下步骤操作：\n");
            message.append("1. 在浏览器中找到登录按钮并点击\n");
            message.append("2. 完成登录流程（可以使用 Google 账号登录）\n");
            message.append("3. 登录完成后，请重新发送消息\n\n");
            message.append("注意：登录需要在浏览器界面中手动完成，系统无法自动登录。");
            message.append("\n```");

            if (conversationId != null && !conversationId.isEmpty()) {
                message.append("\n\n```nwla-conversation-id\n");
                message.append(conversationId);
                message.append("\n```");
            }

            String id = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(message.toString()).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();

            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();

            // 释放锁
            if (providerName != null) {
                new Thread(() -> {
                    try {
                        Thread.sleep(100);
                        providerRegistry.releaseLock(providerName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        } catch (Exception e) {
            log.error("发送手动登录提示时出错", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                // 忽略
            }
            if (providerName != null) {
                providerRegistry.releaseLock(providerName);
            }
        }
    }


    @PreDestroy
    public void cleanup() {
        log.info("清理页面，modelPages: {}, conversationPages: {}", 
            modelPages.size(), conversationPages.size());
        modelPages.values().forEach(page -> {
            try {
                if (page != null && !page.isClosed()) page.close();
            } catch (Exception e) {
            }
        });
        conversationPages.values().forEach(page -> {
            try {
                if (page != null && !page.isClosed()) page.close();
            } catch (Exception e) {
            }
        });
        modelPages.clear();
        conversationPages.clear();
    }
    
    /**
     * 同步生成图片（用于 OpenAI 兼容的图片生成 API）
     * @param prompt 图片描述提示词
     * @param n 生成图片数量
     * @return 图片文件名列表（不是 URL）
     */
    public List<String> generateImageSync(String prompt, int n) {
        List<String> imageUrls = new java.util.ArrayList<>();
        // 创建一个自定义的 ResponseHandler 来收集图片文件名
        final List<String> collectedFilenames = new java.util.ArrayList<>();
        
        // 创建请求
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gemini-web-imagegen")
                .messages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()
                ))
                .stream(false)
                .newConversation(true)
                .build();
        
        // 使用 CountDownLatch 等待结果
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<List<String>> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
        
        // 创建临时 SSE Emitter 来收集结果
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        
        emitter.onCompletion(() -> {
            log.info("SSE emitter 完成回调被触发");
            if (latch.getCount() > 0) {
                latch.countDown();
            }
        });
        
        emitter.onError((ex) -> {
            log.error("SSE emitter 错误回调被触发: {}", ex.getMessage());
            if (latch.getCount() > 0) {
                latch.countDown();
            }
        });
        
        // 创建一个自定义的 ResponseHandler 来收集图片文件名
        ModelConfig.ResponseHandler imageHandler = new ModelConfig.ResponseHandler() {
            @Override
            public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
                // 从 Markdown 格式中提取图片 URL，然后提取文件名
                // 格式: ![生成的图片](http://localhost:24753/api/images/xxx.jpg)
                if (content != null && content.contains("![生成的图片](")) {
                    int start = content.indexOf("![生成的图片](") + "![生成的图片](".length();
                    int end = content.indexOf(")", start);
                    if (end > start) {
                        String imageUrl = content.substring(start, end);
                        // 从 URL 中提取文件名
                        // URL 格式: http://localhost:24753/api/images/gemini_xxx.jpg
                        int lastSlash = imageUrl.lastIndexOf('/');
                        if (lastSlash >= 0 && lastSlash < imageUrl.length() - 1) {
                            String filename = imageUrl.substring(lastSlash + 1);
                            collectedFilenames.add(filename);
                            log.info("收集到图片文件名: {}", filename);
                        } else {
                            // 如果无法解析，尝试直接使用 URL（向后兼容）
                            imageUrls.add(imageUrl);
                            log.warn("无法从 URL 提取文件名，使用完整 URL: {}", imageUrl);
                        }
                    }
                }
            }
            
            @Override
            public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
                // 忽略思考内容
            }
            
            @Override
            public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
                // 完成时关闭 emitter
                emitter.complete();
            }
            
            @Override
            public String getSseData(Page page, String varName) {
                return null;
            }
            
            @Override
            public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
                return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), lastActiveFragmentIndex);
            }
            
            @Override
            public String extractTextFromSse(String sseData) {
                return null;
            }
        };
        
        // 执行图片生成
        executor.submit(() -> {
            Page page = null;
            try {
                GeminiModelConfig config = modelConfigs.get("gemini-web-imagegen");
                if (config == null) {
                    throw new IllegalArgumentException("图片生成模型不可用");
                }
                
                page = createNewConversationPage("gemini-web-imagegen");
                if (page == null) {
                    throw new RuntimeException("无法创建页面");
                }
                
                // 检查登录状态
                if (!checkLoginStatus(page)) {
                    throw new RuntimeException("未登录，请先登录 Gemini");
                }
                
                // 配置模型
                config.configure(page);
                
                // 发送消息
                sendMessage(page, request);
                
                int messageCountBefore = countMessages(page);
                
                // 使用自定义 handler 监控响应
                GeminiContext context = new GeminiContext(
                        page, emitter, request, messageCountBefore, MONITOR_MODE, imageHandler
                );
                
                // monitorResponse 会调用 sendUrlAndComplete，这会完成 emitter
                // 所以这里不需要再次调用 emitter.complete()
                config.monitorResponse(context);
                
                log.debug("monitorResponse 已完成，已收集到 {} 个文件名", collectedFilenames.size());
                
            } catch (Exception e) {
                log.error("图片生成失败", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // emitter 可能已经被完成，忽略
                    log.debug("完成 emitter 时出错: {}", ex.getMessage());
                }
            } finally {
                // 确保 latch 被触发（即使出错或 emitter 已完成）
                // 因为 onCompletion 回调可能没有被触发，或者已经触发过了
                if (latch.getCount() > 0) {
                    log.info("在 finally 块中手动触发 latch（当前计数: {}，已收集文件名: {}）", latch.getCount(), collectedFilenames.size());
                    latch.countDown();
                } else {
                    log.debug("latch 已经被触发");
                }
            }
        });
        
        // 等待完成
        log.info("等待图片生成完成（最多 300 秒），当前已收集文件名: {}", collectedFilenames.size());
        try {
            boolean completed = latch.await(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                log.warn("图片生成等待超时，但可能已经收集到文件名: {}", collectedFilenames.size());
            } else {
                log.info("图片生成等待完成，已收集文件名: {}", collectedFilenames.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待图片生成时被中断", e);
        }
        
        // 优先使用收集到的文件名
        List<String> result = collectedFilenames.isEmpty() ? imageUrls : collectedFilenames;
        log.info("图片生成完成，返回 {} 个文件名: {}", result.size(), result);
        
        // 如果请求生成多张图片，但目前 Gemini 只支持一次生成一张
        // 所以如果 n > 1，我们需要重复第一张图片
        if (n > 1 && !result.isEmpty()) {
            String first = result.get(0);
            result.clear();
            for (int i = 0; i < n; i++) {
                result.add(first);
            }
        }
        
        return result;
    }
}


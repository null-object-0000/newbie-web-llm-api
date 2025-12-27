package site.newbie.web.llm.api.provider.openai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import site.newbie.web.llm.api.provider.command.Command;
import site.newbie.web.llm.api.provider.command.CommandHandler;
import site.newbie.web.llm.api.provider.command.CommandParser;
import org.springframework.context.annotation.Lazy;
import site.newbie.web.llm.api.provider.openai.model.OpenAIModelConfig;
import site.newbie.web.llm.api.provider.openai.model.OpenAIModelConfig.OpenAIContext;
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
 * OpenAI 统一提供者
 * 通过依赖 OpenAIModelConfig 实现不同模型的差异化处理
 */
@Slf4j
@Component
public class OpenAIProvider implements LLMProvider {

    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper;
    private final Map<String, OpenAIModelConfig> modelConfigs;
    private final ProviderRegistry providerRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();

    // 全局指令解析器，支持全局指令
    private final CommandParser commandParser;

    @Value("${openai.monitor.mode:sse}")
    private String monitorMode;

    private static final String SSE_DATA_VAR = "__openaiSseData";
    private static final String SSE_INTERCEPTOR_VAR = "__openaiSseInterceptorSet";
    private static final String[] SSE_URL_PATTERNS = {"/api/conversation", "/backend-api"};
    
    private final ModelConfig.ResponseHandler responseHandler = new ModelConfig.ResponseHandler() {
        @Override
        public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
            OpenAIProvider.this.sendSseChunk(emitter, id, content, model);
        }

        @Override
        public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
            OpenAIProvider.this.sendThinkingContent(emitter, id, content, model);
        }

        @Override
        public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
            OpenAIProvider.this.sendUrlAndComplete(page, emitter, request);
        }

        @Override
        public String getSseData(Page page, String varName) {
            return OpenAIProvider.this.getSseDataFromPage(page, varName);
        }

        @Override
        public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
            // OpenAI 使用简化的解析
            return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), null);
        }

        @Override
        public String extractTextFromSse(String sseData) {
            return OpenAIProvider.this.extractTextFromSse(sseData);
        }
    };

    public OpenAIProvider(BrowserManager browserManager, ObjectMapper objectMapper,
                          List<OpenAIModelConfig> configs, @Lazy ProviderRegistry providerRegistry) {
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(OpenAIModelConfig::getModelName, Function.identity()));
        // OpenAI 目前没有 provider 特定的命令，只支持全局命令
        this.commandParser = new CommandParser();
        log.info("OpenAIProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
    }
    
    @Override
    public CommandParser getCommandParser() {
        return commandParser;
    }

    @Override
    public String getProviderName() {
        return "openai";
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
            if (url.contains("/login") || url.contains("/signin") || url.contains("/auth") || url.contains("/u/login")) {
                log.info("检测到登录页面URL: {}", url);
                return false;
            }
            
            // 优先检查登录按钮（未登录会有登录按钮）
            Locator loginButton = page.locator("button:has-text('登录')")
                    .or(page.locator("button:has-text('Log in')"))
                    .or(page.locator("a:has-text('登录')"))
                    .or(page.locator("a:has-text('Log in')"))
                    .or(page.locator("a[href*='login']"));
            if (loginButton.count() > 0 && loginButton.first().isVisible()) {
                log.info("检测到登录按钮，判断为未登录");
                return false;
            }
            
            // 检查是否存在输入框（已登录会有输入框）
            Locator inputBox = page.locator("div.ProseMirror[id='prompt-textarea']")
                    .or(page.locator("div[contenteditable='true'][id='prompt-textarea']"));
            if (inputBox.count() > 0 && inputBox.first().isVisible()) {
                log.info("检测到输入框，判断为已登录");
                return true;
            }
            
            // 如果URL是聊天页面且没有登录按钮，认为已登录
            if (url.contains("chatgpt.com") || url.contains("chat.openai.com")) {
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
                OpenAIModelConfig config = modelConfigs.get(model);
                
                if (config == null) {
                    throw new IllegalArgumentException("不支持的模型: " + model);
                }

                // 注意：指令检查已在 Controller 层统一处理，这里只处理普通聊天请求
                page = getOrCreatePage(request);
                
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
                
                setupSseInterceptor(page);
                
                if (isNewConversation(request)) {
                    clickNewChatButton(page);
                }
                
                config.configure(page);
                sendMessage(page, request);
                
                int messageCountBefore = countMessages(page);
                verifySseInterceptor(page);
                
                OpenAIContext context = new OpenAIContext(
                        page, emitter, request, messageCountBefore, monitorMode, responseHandler
                );
                config.monitorResponse(context);

            } catch (Exception e) {
                log.error("Chat Error", e);
                emitter.completeWithError(e);
                cleanupPageOnError(page, request.getModel());
            }
        });
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
            if (url.contains("chatgpt.com") || url.contains("chat.openai.com")) {
                // 等待一下，看看 URL 是否会变化（可能页面还在加载中）
                page.waitForTimeout(1000);
                url = page.url(); // 重新获取 URL
                
                String conversationId = extractConversationIdFromUrl(url);
                if (conversationId != null && !conversationId.isEmpty()) {
                    // 保存 conversationId -> Page 的映射
                    pageUrls.put(model, url);
                    modelPages.put(model, page);
                    log.info("已保存指令执行后的 tab 关联: conversationId={}, url={}", conversationId, url);

                    // 发送 conversationId，让客户端知道这个标识
                    sendCommandResultWithConversationId(emitter, model, finalMessage, conversationId);
                    return true; // 已处理
                }
            }
        } catch (Exception e) {
            log.warn("保存 tab 关联时出错: {}", e.getMessage());
        }
        
        return false; // 未处理，使用默认处理
    }

    // ==================== 页面管理 ====================
    
    @Override
    public Page getOrCreatePage(ChatCompletionRequest request) {
        String model = request.getModel();
        String conversationId = getConversationId(request);
        boolean isNew = isNewConversation(request);
        
        if (!isNew && conversationId != null) {
            String conversationUrl = buildUrlFromConversationId(conversationId);
            return findOrCreatePageForUrl(conversationUrl, model);
        } else {
            return createNewConversationPage(model);
        }
    }
    
    @Override
    public String getConversationId(ChatCompletionRequest request) {
        // 首先尝试从请求中获取
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        
        // 从历史消息中提取
        if (request.getMessages() != null) {
            conversationId = ConversationIdUtils.extractConversationIdFromRequest(request, true);
        }
        return conversationId;
    }
    
    @Override
    public boolean isNewConversation(ChatCompletionRequest request) {
        String conversationId = getConversationId(request);
        return conversationId == null || conversationId.isEmpty() || conversationId.startsWith("login-");
    }
    
    private Page findOrCreatePageForUrl(String url, String model) {
        Page page = findPageByUrl(url);
            if (page != null && !page.isClosed()) {
                if (!page.url().equals(url)) {
                    page.navigate(url);
                    page.waitForLoadState();
                }
                modelPages.put(model, page);
                pageUrls.put(model, url);
                return page;
            }
            
            page = browserManager.newPage(getProviderName());
            modelPages.put(model, page);
            page.navigate(url);
            page.waitForLoadState();
            pageUrls.put(model, url);
            return page;
    }
    
    private Page createNewConversationPage(String model) {
        Page oldPage = modelPages.remove(model);
        if (oldPage != null && !oldPage.isClosed()) {
            try { oldPage.close(); } catch (Exception e) { }
        }
        
        Page page = browserManager.newPage(getProviderName());
        modelPages.put(model, page);
        page.navigate("https://chatgpt.com/");
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
        } catch (Exception e) { }
        return null;
    }
    
    private void clickNewChatButton(Page page) {
        try {
            page.waitForTimeout(1000);
            Boolean result = (Boolean) page.evaluate("""
                () => {
                    let button = document.querySelector('a[data-testid="create-new-chat-button"]');
                    if (!button) {
                        const links = document.querySelectorAll('a[href="/"]');
                        for (const link of links) {
                            if (link.textContent.includes('新聊天') || link.textContent.includes('New chat')) {
                                button = link;
                                break;
                            }
                        }
                    }
                    if (button) {
                        button.click();
                        return true;
                    }
                    return false;
                }
            """);
            if (Boolean.TRUE.equals(result)) {
                page.waitForTimeout(500);
                log.info("已点击新聊天按钮");
            }
        } catch (Exception e) {
            log.warn("点击新聊天按钮时出错", e);
        }
    }
    
    private void sendMessage(Page page, ChatCompletionRequest request) {
        page.waitForTimeout(1000);
        Locator inputBox = page.locator("div.ProseMirror[id='prompt-textarea']");
        try {
            inputBox.waitFor();
        } catch (Exception e) {
            inputBox = page.locator("div[contenteditable='true'][id='prompt-textarea']");
            inputBox.waitFor();
        }
        
        String message = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(ChatCompletionRequest.Message::getContent)
                .orElse("Hello");
        
        log.info("发送消息: {}", message);
        inputBox.click();
        page.waitForTimeout(200);
        
        page.evaluate("""
            (text) => {
                const input = document.getElementById('prompt-textarea');
                if (input) {
                    input.innerHTML = '';
                    input.appendChild(document.createTextNode(text));
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }
        """, message);
        
        page.waitForTimeout(300);
        page.keyboard().press("Enter");
        page.waitForTimeout(500);
    }
    
    private int countMessages(Page page) {
        Locator locators = page.locator("[data-message-author-role='assistant']")
                .or(page.locator(".markdown"));
        return locators.count();
    }
    
    private void cleanupPageOnError(Page page, String model) {
        if (page != null) {
            modelPages.remove(model, page);
            try { if (!page.isClosed()) page.close(); } catch (Exception e) { }
        }
    }
    
    // ==================== SSE 拦截器 ====================
    
    private void setupSseInterceptor(Page page) {
        // 清空旧的 SSE 数据和索引（避免复用页面时读取到旧数据）
        try {
            page.evaluate(String.format("() => { window.%s = []; window.%sIndex = 0; }", SSE_DATA_VAR, SSE_DATA_VAR));
        } catch (Exception e) { }

        StringBuilder urlCondition = new StringBuilder();
        for (int i = 0; i < SSE_URL_PATTERNS.length; i++) {
            if (i > 0) urlCondition.append(" || ");
            urlCondition.append("url.includes('").append(SSE_URL_PATTERNS[i]).append("')");
        }

        String jsCode = String.format("""
            (function() {
                if (window.%s) return;
                window.%s = true;
                const originalFetch = window.fetch;
                window.fetch = function(...args) {
                    const url = args[0];
                    if (typeof url === 'string' && (%s)) {
                        return originalFetch.apply(this, args).then(response => {
                            const contentType = response.headers.get('content-type');
                            if (contentType && contentType.includes('text/event-stream')) {
                                const clonedResponse = response.clone();
                                const reader = clonedResponse.body.getReader();
                                const decoder = new TextDecoder();
                                window.%s = window.%s || [];
                                function readStream() {
                                    reader.read().then(({ done, value }) => {
                                        if (done) return;
                                        window.%s.push(decoder.decode(value, { stream: true }));
                                        readStream();
                                    }).catch(err => {});
                                }
                                readStream();
                            }
                            return response;
                        });
                    }
                    return originalFetch.apply(this, args);
                };
            })();
            """, SSE_INTERCEPTOR_VAR, SSE_INTERCEPTOR_VAR, urlCondition, 
            SSE_DATA_VAR, SSE_DATA_VAR, SSE_DATA_VAR);
        
        try {
            page.evaluate(jsCode);
        } catch (Exception e) {
            log.error("设置 SSE 拦截器失败: {}", e.getMessage());
        }
    }
    
    private void verifySseInterceptor(Page page) {
        try {
            Object status = page.evaluate("() => window." + SSE_INTERCEPTOR_VAR + " || false");
            if (!Boolean.TRUE.equals(status)) {
                setupSseInterceptor(page);
            }
        } catch (Exception e) { }
    }
    
    private String getSseDataFromPage(Page page, String varName) {
        try {
            if (page.isClosed()) return null;
            // 使用索引跟踪已读取的数据，避免清空数组导致数据丢失
            Object result = page.evaluate(String.format("""
                () => {
                    if (!window.%s) return null;
                    if (!window.%sIndex) window.%sIndex = 0;
                    if (window.%s.length > window.%sIndex) {
                        const newData = window.%s.slice(window.%sIndex);
                        window.%sIndex = window.%s.length;
                        return newData.join('\\n');
                    }
                    return null;
                }
                """, varName, varName, varName, varName, varName, varName, varName, varName, varName));
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== SSE 发送 ====================
    
    // UTF-8 编码的 MediaType，确保中文和 emoji 正确传输
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

    private void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
        try {
            if (!page.isClosed()) {
                String url = page.url();
                if (url.contains("chatgpt.com") || url.contains("chat.openai.com")) {
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
            log.warn("发送对话 URL 时出错: {}", e.getMessage());
        }
        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
        emitter.complete();
    }
    
    private String extractTextFromSse(String sseData) {
        if (sseData == null) return null;
        StringBuilder text = new StringBuilder();
        
        String[] lines = sseData.split("\n");
        int processedCount = 0;
        int skippedCount = 0;

        for (String s : lines) {
            String line = s.trim();

            // 跳过 event 行（不再需要跟踪状态，因为简单格式不依赖 event）
            if (line.startsWith("event: ")) {
                continue;
            }

            // 只处理 data 行
            if (!line.startsWith("data: ")) {
                continue;
            }

            // 跳过完成标记（但先处理完当前批次的其他数据）
            if (line.contains("[DONE]")) {
                // 注意：不立即 continue，让循环继续处理其他行
                // 因为 [DONE] 可能在数据中间
                continue;
            }

            String jsonStr = line.substring(6).trim();
            if (jsonStr.isEmpty() || jsonStr.equals("{}")) {
                continue;
            }

            String liteJSONStr = jsonStr.length() > 100 ? jsonStr.substring(0, 100) + "..." : jsonStr;
            try {
                var json = objectMapper.readTree(jsonStr);

                // 处理纯字符串的情况（可能是版本号等元数据，但先记录）
                if (json.isString()) {
                    skippedCount++;
                    log.debug("跳过纯字符串数据: {}", liteJSONStr);
                    continue;
                }

                // 必须是对象节点
                if (!json.isObject()) {
                    skippedCount++;
                    log.debug("跳过非对象数据: {}", liteJSONStr);
                    continue;
                }

                // 跳过有 type 字段的元数据（如 resume_conversation_token, input_message 等）
                if (json.has("type")) {
                    skippedCount++;
                    String type = json.get("type").asString();
                    // 记录重要的元数据类型，帮助调试
                    if (!"message_stream_complete".equals(type) && !"conversation_detail_metadata".equals(type)) {
                        log.info("跳过元数据: {}", type);
                    }
                    continue;
                }

                // 格式1: {"p": "/message/content/parts/0", "o": "append", "v": "内容"}
                // 注意：只处理 append 操作，patch 操作由格式3处理
                if (json.has("p") && json.has("o") && json.has("v")) {
                    String path = json.get("p").asString();
                    String operation = json.get("o").asString();

                    // 处理内容路径的 append 操作
                    // 支持多种可能的内容路径格式
                    if (path != null && "append".equals(operation)) {
                        boolean isContentPath = path.contains("/message/content/parts/") ||
                                path.contains("/content/parts/") ||
                                path.contains("/content") ||
                                path.endsWith("/content") ||
                                path.contains("content");

                        if (isContentPath && json.get("v").isString()) {
                            String content = json.get("v").asString();
                            if (content != null && !content.isEmpty()) {
                                text.append(content);
                                processedCount++;
                                log.info("提取内容 (路径格式, path={}): {}", path, content);
                            }
                        } else if (isContentPath) {
                            log.info("内容路径的值不是文本类型: path={}, op={}, v类型={}", path, operation, json.get("v").getNodeType());
                        } else {
                            log.trace("跳过非内容路径: path={}, op={}", path, operation);
                        }
                        continue; // 只有 append 操作处理完才跳过
                    }
                    // 注意：如果 operation 不是 "append"（如 "patch", "add", "replace"），不要 continue，让后续逻辑处理
                }

                // 格式2: {"v": "内容"} - 简单格式，直接追加（不依赖 event，因为可能跨批次）
                // 注意：只处理纯文本内容，跳过对象和数组
                if (json.has("v") && !json.has("p") && !json.has("o")) {
                    var vNode = json.get("v");
                    if (vNode.isString()) {
                        String content = vNode.asString();
                        if (content != null && !content.isEmpty()) {
                            text.append(content);
                            processedCount++;
                            log.info("提取内容 (简单格式): {}", content);
                        }
                    }
                    continue;
                }

                // 格式3: {"p": "", "o": "patch", "v": [...]} - 批量更新
                if (json.has("p") && json.has("o") && "patch".equals(json.get("o").asString())) {
                    if (json.has("v") && json.get("v").isArray()) {
                        var patchArray = json.get("v");
                        int patchItemCount = 0;
                        int totalPatchItems = patchArray.size();
                        log.info("处理 patch 格式，包含 {} 个操作项", totalPatchItems);
                        for (var patchItem : patchArray) {
                            if (patchItem.has("p") && patchItem.has("o") && patchItem.has("v")) {
                                String itemPath = patchItem.get("p").asString();
                                String itemOp = patchItem.get("o").asString();

                                // 处理内容路径的 append 操作，支持更多路径格式
                                if (itemPath != null && "append".equals(itemOp)) {
                                    boolean isContentPath = itemPath.contains("/message/content/parts/") ||
                                            itemPath.contains("/content/parts/") ||
                                            itemPath.contains("/content") ||
                                            itemPath.endsWith("/content") ||
                                            itemPath.contains("content");

                                    if (isContentPath && patchItem.get("v").isString()) {
                                        String content = patchItem.get("v").asString();
                                        if (content != null && !content.isEmpty()) {
                                            text.append(content);
                                            patchItemCount++;
                                            log.info("提取内容 (patch格式, path={}): {}", itemPath, content);
                                        }
                                    } else if (isContentPath) {
                                        log.info("patch 项的值不是文本类型: path={}, op={}, v类型={}", itemPath, itemOp, patchItem.get("v").getNodeType());
                                    } else {
                                        log.trace("跳过 patch 项（非内容路径）: path={}, op={}", itemPath, itemOp);
                                    }
                                } else {
                                    log.trace("跳过 patch 项（非 append 操作）: path={}, op={}", itemPath, itemOp);
                                }
                            } else {
                                log.info("patch 项格式不完整: {}", patchItem);
                            }
                        }
                        if (patchItemCount > 0) {
                            processedCount++;
                            log.info("patch 格式处理完成，提取了 {} 个内容项，共 {} 个操作项", patchItemCount, totalPatchItems);
                        } else if (totalPatchItems > 0) {
                            log.info("patch 格式未提取到任何内容，共 {} 个操作项", totalPatchItems);
                        }
                    } else {
                        log.warn("patch 格式的 v 字段不是数组: {}", jsonStr.length() > 200 ? jsonStr.substring(0, 200) + "..." : jsonStr);
                    }
                    continue;
                }

                // 如果都没有匹配，记录未处理的格式
                skippedCount++;
                // 检查是否是消息对象（可能包含内容）
                if (json.has("v") && json.get("v").isObject()) {
                    var vObj = json.get("v");
                    if (vObj.has("message")) {
                        log.debug("跳过消息对象（可能是初始化消息）");
                    } else {
                        // 尝试从消息对象中提取内容
                        if (vObj.has("content") && vObj.get("content").isString()) {
                            String content = vObj.get("content").asString();
                            if (content != null && !content.isEmpty()) {
                                text.append(content);
                                processedCount++;
                                log.info("从消息对象中提取内容: {}", content.length() > 50 ? content.substring(0, 50) + "..." : content);
                                continue;
                            }
                        }
                        log.warn("未处理的 JSON 格式（包含 v 对象）: {}", liteJSONStr);
                    }
                } else {
                    log.warn("未处理的 JSON 格式: {}", liteJSONStr);
                }
            } catch (Exception e) {
                skippedCount++;
                log.error("解析 SSE 数据行失败: {} - {}", e.getMessage(), liteJSONStr);
            }
        }
        
        if (text.length() > 0) {
            log.info("从 SSE 提取到 {} 字符，处理了 {} 条数据，跳过了 {} 条", text.length(), processedCount, skippedCount);
            if (log.isDebugEnabled() && text.length() < 100) {
                log.debug("提取的内容预览: {}", text);
            }
            return text.toString();
        } else if (processedCount > 0 || skippedCount > 0) {
            log.info("SSE 数据已处理但无文本内容，处理了 {} 条，跳过了 {} 条", processedCount, skippedCount);
            if (log.isDebugEnabled() && sseData.length() < 500) {
                log.debug("SSE 数据内容: {}", sseData);
            }
        }
        return null;
    }
    
    /**
     * 从 URL 中提取对话 ID
     * 支持格式：
     * - https://chatgpt.com/c/{id}
     * - https://chat.openai.com/c/{id}
     */
    private String extractConversationIdFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        
        boolean isChatGpt = url.contains("chatgpt.com");
        boolean isChatOpenai = url.contains("chat.openai.com");
        if (!isChatGpt && !isChatOpenai) {
            return null;
        }
        
        try {
            // 尝试从路径中提取 ID
            // 格式: https://chatgpt.com/c/{id} 或 https://chat.openai.com/c/{id}
            int cIdx = url.indexOf("/c/");
            if (cIdx >= 0) {
                String afterC = url.substring(cIdx + "/c/".length());
                // 移除查询参数和片段
                int queryIdx = afterC.indexOf('?');
                int fragmentIdx = afterC.indexOf('#');
                int endIdx = afterC.length();
                if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);
                
                String id = afterC.substring(0, endIdx).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.warn("从 URL 提取对话 ID 失败: url={}, error={}", url, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 从对话 ID 构建 URL
     */
    private String buildUrlFromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        // 默认使用 chatgpt.com，如果需要可以扩展为支持 chat.openai.com
        return "https://chatgpt.com/c/" + conversationId;
    }
    
    /**
     * 发送手动登录提示
     * 引导用户在浏览器中手动完成登录
     */
    /**
     * 指令进度回调实现
     * 累积进度消息，在开始和结束时发送标记
     */
    private static class CommandProgressCallback implements Command.ProgressCallback {
        private final SseEmitter emitter;
        private final String model;
        private final ObjectMapper objectMapper;
        private final StringBuilder accumulatedContent = new StringBuilder();
        private boolean startMarkerSent = false;
        private int lastSentLength = 0;
        private int startMarkerLength = 0;

        public CommandProgressCallback(SseEmitter emitter, String model, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.model = model;
            this.objectMapper = objectMapper;
        }

        /**
         * 发送开始标记
         */
        public void sendStartMarker() {
            try {
                String startMarker = "```nwla-system-message\n";
                sendReasoningContent(startMarker);
                accumulatedContent.append(startMarker);
                startMarkerLength = startMarker.length();
                lastSentLength = accumulatedContent.length();
                startMarkerSent = true;
            } catch (Exception e) {
                log.warn("发送开始标记时出错: {}", e.getMessage());
            }
        }

        /**
         * 发送结束标记
         */
        public void sendEndMarker() {
            try {
                // 先发送换行（如果最后一条消息后面没有换行）
                String newline = "\n";
                sendReasoningContent(newline);
                // 然后发送结束标记
                String endMarker = "```\n";
                sendReasoningContent(endMarker);
            } catch (Exception e) {
                log.warn("发送结束标记时出错: {}", e.getMessage());
            }
        }

        /**
         * 添加进度消息（累积并实时发送增量）
         */
        public void addProgress(String message) {
            if (startMarkerSent) {
                // 添加换行（如果已有内容，即除了开始标记外还有其他内容）
                if (accumulatedContent.length() > startMarkerLength) {
                    accumulatedContent.append("\n");
                }
                accumulatedContent.append(message);
                // 实时发送增量内容
                String newContent = accumulatedContent.substring(lastSentLength);
                sendReasoningContent(newContent);
                lastSentLength = accumulatedContent.length();
            }
        }

        @Override
        public void onProgress(String message) {
            if (startMarkerSent) {
                // 添加换行（如果已有内容，即除了开始标记外还有其他内容）
                if (accumulatedContent.length() > startMarkerLength) {
                    accumulatedContent.append("\n");
                }
                accumulatedContent.append(message);
                // 实时发送增量内容
                String newContent = accumulatedContent.substring(lastSentLength);
                sendReasoningContent(newContent);
                lastSentLength = accumulatedContent.length();
            }
        }

        /**
         * 发送思考内容（增量）
         */
        private void sendReasoningContent(String content) {
            try {
                String thinkingId = UUID.randomUUID().toString();
                ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                        .delta(ChatCompletionResponse.Delta.builder()
                                .reasoningContent(content)
                                .build())
                        .index(0).build();
                ChatCompletionResponse response = ChatCompletionResponse.builder()
                        .id(thinkingId).object("chat.completion.chunk")
                        .created(System.currentTimeMillis() / 1000)
                        .model(model).choices(List.of(choice)).build();
                MediaType jsonUtf8 = new MediaType("application", "json", StandardCharsets.UTF_8);
                emitter.send(SseEmitter.event().data(
                        objectMapper.writeValueAsString(response),
                        jsonUtf8));
            } catch (Exception e) {
                log.warn("发送思考内容时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 发送指令执行结果
     */
    private void sendCommandResult(SseEmitter emitter, String model, String message, boolean success) {
        try {
            String id = UUID.randomUUID().toString();
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

            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送指令结果时出错", e);
            emitter.completeWithError(e);
        }
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
        } catch (Exception e) {
            log.error("发送指令结果时出错", e);
            emitter.completeWithError(e);
        }
    }

    private void sendManualLoginPrompt(SseEmitter emitter, String model, String providerName, String conversationId) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("```nwla-system-message\n");
            message.append("当前未登录，请在浏览器中手动完成登录。\n\n");
            message.append("浏览器窗口已打开，请按照以下步骤操作：\n");
            message.append("1. 在浏览器中找到登录按钮并点击\n");
            message.append("2. 完成登录流程（可以使用 OpenAI 账号登录）\n");
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
        log.info("清理页面，共 {} 个", modelPages.size());
        modelPages.values().forEach(page -> {
            try { if (page != null && !page.isClosed()) page.close(); } catch (Exception e) { }
        });
        modelPages.clear();
    }
}


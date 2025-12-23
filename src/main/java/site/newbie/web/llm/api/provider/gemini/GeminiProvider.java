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
    
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
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
        log.info("GeminiProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
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
                
                // Gemini 只支持 DOM 模式，不需要 SSE 拦截器
                
                if (isNewConversation(request)) {
                    clickNewChatButton(page);
                }
                
                config.configure(page);
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
    
    private Page getOrCreatePage(ChatCompletionRequest request) {
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
    
    private String getConversationId(ChatCompletionRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        
        if (request.getMessages() != null) {
            conversationId = ConversationIdUtils.extractConversationIdFromRequest(request, true);
        }
        return conversationId;
    }
    
    private boolean isNewConversation(ChatCompletionRequest request) {
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
        } catch (Exception e) { }
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
            try { if (!page.isClosed()) page.close(); } catch (Exception e) { }
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
        log.info("清理页面，共 {} 个", modelPages.size());
        modelPages.values().forEach(page -> {
            try { if (page != null && !page.isClosed()) page.close(); } catch (Exception e) { }
        });
        modelPages.clear();
    }
}


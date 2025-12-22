package site.newbie.web.llm.api.provider.openai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
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
import org.springframework.context.annotation.Lazy;
import site.newbie.web.llm.api.manager.LoginSessionManager;
import site.newbie.web.llm.api.provider.openai.model.OpenAIModelConfig;
import site.newbie.web.llm.api.provider.openai.model.OpenAIModelConfig.OpenAIContext;
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
    private final LoginSessionManager loginSessionManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
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
        public void sendReplace(SseEmitter emitter, String id, String content, String model) throws IOException {
            OpenAIProvider.this.sendSseReplace(emitter, id, content, model);
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
                         List<OpenAIModelConfig> configs, @Lazy ProviderRegistry providerRegistry,
                         LoginSessionManager loginSessionManager) {
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.loginSessionManager = loginSessionManager;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(OpenAIModelConfig::getModelName, Function.identity()));
        log.info("OpenAIProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
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
            
            // 检查是否存在输入框（已登录会有输入框）
            Locator inputBox = page.locator("div.ProseMirror[id='prompt-textarea']")
                    .or(page.locator("div[contenteditable='true'][id='prompt-textarea']"));
            if (inputBox.count() > 0) {
                log.info("检测到输入框，判断为已登录");
                return true;
            }
            
            // 检查是否存在登录按钮（未登录会有登录按钮）
            Locator loginButton = page.locator("button:has-text('登录')")
                    .or(page.locator("button:has-text('Log in')"))
                    .or(page.locator("a:has-text('登录')"))
                    .or(page.locator("a:has-text('Log in')"))
                    .or(page.locator("a[href*='login']"));
            if (loginButton.count() > 0) {
                log.info("检测到登录按钮，判断为未登录");
                return false;
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

                page = getOrCreatePage(request);
                
                // 检查登录状态（在创建页面后再次检查，因为页面创建时可能检测到登录状态丢失）
                if (!checkLoginStatus(page)) {
                    log.warn("检测到未登录状态，发送登录提示");
                    // 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
                    // 状态更新由 Controller 层处理
                    
                    // 获取或生成对话ID
                    String conversationId = getConversationId(request);
                    if (conversationId == null || conversationId.isEmpty()) {
                        conversationId = "login-" + UUID.randomUUID();
                    }
                    
                    // 创建登录会话
                    LoginSessionManager.LoginSession session = loginSessionManager.getOrCreateSession(getProviderName(), conversationId);
                    session.setConversationId(conversationId);
                    session.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
                    // 保存会话（确保状态被持久化）
                    loginSessionManager.saveSession(getProviderName(), conversationId, session);
                    
                    // 发送登录方式选择提示
                    sendLoginMethodSelection(emitter, request.getModel(), getProviderName(), conversationId);
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
        // 首先尝试从请求中获取
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        
        // 从历史消息中提取
        if (request.getMessages() != null) {
            conversationId = extractConversationIdFromHistory(request);
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
                // 检测登录状态是否丢失
                checkLoginStatusLost(page);
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
        // 检测登录状态是否丢失
        checkLoginStatusLost(page);
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
        pageUrls.put(model, page.url());
        // 检测登录状态是否丢失
        checkLoginStatusLost(page);
        return page;
    }
    
    /**
     * 检测登录状态是否丢失（通过检查是否有登录按钮）
     * 如果检测到登录按钮，说明登录状态丢失
     * 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
     */
    private void checkLoginStatusLost(Page page) {
        try {
            if (page == null || page.isClosed()) {
                return;
            }
            
            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 检查是否有登录按钮（登录状态丢失时会出现登录按钮）
            Locator loginButton = page.locator("button:has-text('登录')")
                    .or(page.locator("button:has-text('Login')"))
                    .or(page.locator("button:has-text('Log in')"))
                    .or(page.locator("button:has-text('Sign in')"));
            
            if (loginButton.count() > 0 && loginButton.first().isVisible()) {
                log.warn("检测到登录按钮，说明登录状态已丢失");
                // 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
                // 状态更新由 Controller 层处理
            }
        } catch (Exception e) {
            log.warn("检测登录状态丢失时出错: {}", e.getMessage());
        }
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
    
    private void sendSseReplace(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content("__REPLACE__" + content).build())
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
                        String content = "\n\n```nwla-conversation-id\n" + conversationId + "\n```\n\n";
                        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                                .index(0).build();
                        ChatCompletionResponse response = ChatCompletionResponse.builder()
                                .id(UUID.randomUUID().toString()).object("chat.completion.chunk")
                                .created(System.currentTimeMillis() / 1000)
                                .model(request.getModel()).choices(List.of(choice)).build();
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
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
    
    /**
     * 发送登录方式选择提示
     */
    private void sendLoginMethodSelection(SseEmitter emitter, String model, String providerName, String conversationId) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("```nwla-system-message\n");
            message.append("当前未登录，请选择登录方式：\n\n");
            message.append("1. 手机号+验证码登录\n");
            message.append("2. 账号+密码登录\n");
            message.append("3. 微信扫码登录\n\n");
            message.append("请输入对应的数字（1、2、3）来选择登录方式。");
            message.append("\n```");
            
            // 如果提供了对话ID，在消息中包含对话ID信息
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
            log.error("发送登录方式选择提示时出错", e);
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
     * 从历史消息中提取对话 ID
     */
    private String extractConversationIdFromHistory(ChatCompletionRequest request) {
        if (request.getMessages() == null) return null;
        
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                
                // 检查标记格式：```nwla-conversation-id\n{id}\n```
                String marker = "```nwla-conversation-id";
                int startIdx = content.indexOf(marker);
                if (startIdx != -1) {
                    // 找到开始标记，查找结束标记 ```
                    int afterMarker = startIdx + marker.length();
                    // 跳过可能的换行
                    while (afterMarker < content.length() && 
                           (content.charAt(afterMarker) == '\n' || content.charAt(afterMarker) == '\r')) {
                        afterMarker++;
                    }
                    // 查找结束的 ```
                    int endIdx = content.indexOf("```", afterMarker);
                    if (endIdx != -1 && endIdx > afterMarker) {
                        String extractedId = content.substring(afterMarker, endIdx).trim();
                        // 提取第一行非空内容
                        extractedId = extractedId.lines()
                            .filter(line -> !line.trim().isEmpty() && !line.contains("```") && !line.contains("nwla-conversation-id"))
                            .findFirst()
                            .orElse("")
                            .trim();
                        if (!extractedId.isEmpty() && !extractedId.startsWith("login-")) {
                            // 不是登录对话 ID，返回提取的 ID
                            return extractedId;
                        }
                    }
                }
            }
        }
        return null;
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


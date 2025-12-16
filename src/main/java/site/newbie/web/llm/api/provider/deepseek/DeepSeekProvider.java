package site.newbie.web.llm.api.provider.deepseek;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.deepseek.model.DeepSeekModelConfig;
import site.newbie.web.llm.api.provider.deepseek.model.DeepSeekModelConfig.*;
import tools.jackson.databind.JsonNode;
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
 * DeepSeek 统一提供者
 * 通过依赖 DeepSeekModelConfig 实现不同模型的差异化处理
 */
@Slf4j
@Component
public class DeepSeekProvider implements LLMProvider {

    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper;
    private final Map<String, DeepSeekModelConfig> modelConfigs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // 页面管理
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
    @Value("${deepseek.monitor.mode:sse}")
    private String monitorMode;
    
    // SSE 拦截器配置
    private static final String SSE_DATA_VAR = "__deepseekSseData";
    private static final String SSE_INTERCEPTOR_VAR = "__deepseekSseInterceptorSet";
    private static final String[] SSE_URL_PATTERNS = {"/api/v0/chat/completion"};
    
    /**
     * 响应处理器实现，提供给 ModelConfig 使用
     */
    private final ModelConfig.ResponseHandler responseHandler = new ModelConfig.ResponseHandler() {
        @Override
        public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
            DeepSeekProvider.this.sendSseChunk(emitter, id, content, model);
        }

        @Override
        public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
            DeepSeekProvider.this.sendThinkingContent(emitter, id, content, model);
        }

        @Override
        public void sendReplace(SseEmitter emitter, String id, String content, String model) throws IOException {
            DeepSeekProvider.this.sendSseReplace(emitter, id, content, model);
        }

        @Override
        public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
            DeepSeekProvider.this.sendUrlAndComplete(page, emitter, request);
        }

        @Override
        public String getSseData(Page page, String varName) {
            return DeepSeekProvider.this.getSseDataFromPage(page, varName);
        }

        @Override
        public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
            return DeepSeekProvider.this.parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
        }

        @Override
        public String extractTextFromSse(String sseData) {
            return DeepSeekProvider.this.extractTextFromSse(sseData);
        }
    };

    public DeepSeekProvider(BrowserManager browserManager, ObjectMapper objectMapper, 
                           List<DeepSeekModelConfig> configs) {
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(DeepSeekModelConfig::getModelName, Function.identity()));
        log.info("DeepSeekProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.copyOf(modelConfigs.keySet());
    }

    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        executor.submit(() -> {
            Page page = null;
            try {
                String model = request.getModel();
                DeepSeekModelConfig config = modelConfigs.get(model);
                
                if (config == null) {
                    throw new IllegalArgumentException("不支持的模型: " + model);
                }

                // 1. 获取或创建页面
                page = getOrCreatePage(request);
                
                // 2. 设置 SSE 拦截器
                setupSseInterceptor(page);
                
                // 3. 如果是新对话，点击"新对话"按钮
                if (isNewConversation(request)) {
                    clickNewChatButton(page);
                }
                
                // 4. 配置模型（由 ModelConfig 实现）
                config.configure(page);
                
                // 5. 处理联网搜索
                handleWebSearchToggle(page, request.isWebSearch());
                
                // 6. 发送消息
                sendMessage(page, request);
                
                // 7. 记录发送前消息数量
                int messageCountBefore = page.locator(".ds-markdown").count();
                log.info("发送前消息数量: {}", messageCountBefore);
                
                // 8. 验证 SSE 拦截器
                verifySseInterceptor(page);
                
                // 9. 监听响应（由 ModelConfig 实现）
                DeepSeekContext context = new DeepSeekContext(
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
        String conversationUrl = getConversationUrl(request);
        boolean isNewConversation = isNewConversation(request);
        
        Page page;
        if (!isNewConversation && conversationUrl != null) {
            page = findOrCreatePageForUrl(conversationUrl, model);
        } else {
            page = createNewConversationPage(model);
        }
        
        return page;
    }
    
    private String getConversationUrl(ChatCompletionRequest request) {
        String url = request.getConversationUrl();
        if ((url == null || url.isEmpty()) && request.getMessages() != null) {
            url = extractUrlFromHistory(request);
        }
        return url;
    }
    
    private boolean isNewConversation(ChatCompletionRequest request) {
        String url = getConversationUrl(request);
        return url == null || url.isEmpty() || !url.contains("chat.deepseek.com");
    }
    
    private Page findOrCreatePageForUrl(String url, String model) {
        log.info("检测到对话 URL，尝试复用: {}", url);
        
        // 查找已有页面
        Page page = findPageByUrl(url);
        
        if (page != null && !page.isClosed()) {
            String currentUrl = page.url();
            if (!currentUrl.equals(url)) {
                page.navigate(url);
                page.waitForLoadState();
            }
            modelPages.put(model, page);
            pageUrls.put(model, url);
            return page;
        }
        
        // 创建新页面
        page = browserManager.newPage();
        modelPages.put(model, page);
        page.navigate(url);
        page.waitForLoadState();
        pageUrls.put(model, url);
        log.info("已导航到对话 URL: {}", url);
        return page;
    }
    
    private Page createNewConversationPage(String model) {
        log.info("开启新对话");
        
        // 关闭旧页面
        Page oldPage = modelPages.remove(model);
        if (oldPage != null && !oldPage.isClosed()) {
            try { oldPage.close(); } catch (Exception e) { log.warn("关闭旧页面时出错", e); }
        }
        
        // 创建新页面
        Page page = browserManager.newPage();
        modelPages.put(model, page);
        page.navigate("https://chat.deepseek.com/");
        page.waitForLoadState();
        pageUrls.put(model, page.url());
        return page;
    }
    
    private Page findPageByUrl(String targetUrl) {
        if (targetUrl == null) return null;
        
        // 检查 modelPages
        for (String model : modelPages.keySet()) {
            Page page = modelPages.get(model);
            if (page != null && !page.isClosed()) {
                String savedUrl = pageUrls.get(model);
                if (targetUrl.equals(savedUrl)) {
                    return page;
                }
            }
        }
        
        // 检查所有 tab
        try {
            for (Page page : browserManager.getAllPages()) {
                if (page != null && !page.isClosed() && targetUrl.equals(page.url())) {
                    return page;
                }
            }
        } catch (Exception e) {
            log.warn("检查 tab 时出错: {}", e.getMessage());
        }
        
        return null;
    }
    
    private void clickNewChatButton(Page page) {
        try {
            page.waitForTimeout(1000);
            Locator newChatButton = page.locator("button:has-text('新对话')")
                    .or(page.locator("button:has-text('New Chat')"))
                    .or(page.locator("[aria-label*='New'], [aria-label*='new']"))
                    .first();
            if (newChatButton.count() > 0) {
                newChatButton.click();
                page.waitForTimeout(500);
                log.info("已点击新对话按钮");
            }
        } catch (Exception e) {
            log.warn("点击新对话按钮时出错", e);
        }
    }
    
    private void sendMessage(Page page, ChatCompletionRequest request) {
        Locator inputBox = page.locator("textarea");
        inputBox.waitFor();
        
        String message = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(ChatCompletionRequest.Message::getContent)
                .orElse("Hello");
        
        log.info("发送消息: {}", message);
        inputBox.fill(message);
        page.keyboard().press("Enter");
        page.waitForTimeout(500);
    }
    
    private void cleanupPageOnError(Page page, String model) {
        if (page != null) {
            modelPages.remove(model, page);
            try { if (!page.isClosed()) page.close(); } catch (Exception e) { }
        }
    }
    
    // ==================== SSE 拦截器 ====================
    
    private void setupSseInterceptor(Page page) {
        // 清空旧的 SSE 数据（避免复用页面时读取到旧数据）
        try {
            page.evaluate(String.format("() => { window.%s = []; }", SSE_DATA_VAR));
        } catch (Exception e) {
            log.debug("初始化 SSE 数据存储失败: {}", e.getMessage());
        }

        StringBuilder urlCondition = new StringBuilder();
        for (int i = 0; i < SSE_URL_PATTERNS.length; i++) {
            if (i > 0) urlCondition.append(" || ");
            urlCondition.append("url.includes('").append(SSE_URL_PATTERNS[i]).append("')");
        }

        String jsCode = buildSseInterceptorScript(urlCondition.toString());
        
        try {
            page.evaluate(jsCode);
            log.info("已设置 SSE 拦截器");
        } catch (Exception e) {
            log.error("设置 SSE 拦截器失败: {}", e.getMessage());
        }
    }
    
    private String buildSseInterceptorScript(String urlCondition) {
        return String.format("""
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
                                        const chunk = decoder.decode(value, { stream: true });
                                        window.%s.push(chunk);
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
                
                const originalXHROpen = XMLHttpRequest.prototype.open;
                const originalXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                    this._interceptedUrl = url;
                    return originalXHROpen.apply(this, [method, url, ...rest]);
                };
                XMLHttpRequest.prototype.send = function(...args) {
                    if (this._interceptedUrl && (%s)) {
                        this.onreadystatechange = function() {
                            if (this.readyState === 3 || this.readyState === 4) {
                                const responseText = this.responseText;
                                if (responseText) {
                                    const contentType = this.getResponseHeader('content-type');
                                    if (contentType && contentType.includes('text/event-stream')) {
                                        window.%s = window.%s || [];
                                        if (this._lastResponseLength === undefined) this._lastResponseLength = 0;
                                        if (responseText.length > this._lastResponseLength) {
                                            window.%s.push(responseText.substring(this._lastResponseLength));
                                            this._lastResponseLength = responseText.length;
                                        }
                                    }
                                }
                            }
                        };
                    }
                    return originalXHRSend.apply(this, args);
                };
            })();
            """, SSE_INTERCEPTOR_VAR, SSE_INTERCEPTOR_VAR, urlCondition, 
            SSE_DATA_VAR, SSE_DATA_VAR, SSE_DATA_VAR,
            urlCondition.replace("url", "this._interceptedUrl"), 
            SSE_DATA_VAR, SSE_DATA_VAR, SSE_DATA_VAR);
    }
    
    private void verifySseInterceptor(Page page) {
        try {
            Object status = page.evaluate("() => window." + SSE_INTERCEPTOR_VAR + " || false");
            if (!Boolean.TRUE.equals(status)) {
                log.warn("SSE 拦截器未设置，重新设置...");
                setupSseInterceptor(page);
            }
        } catch (Exception e) {
            log.warn("检查 SSE 拦截器状态失败: {}", e.getMessage());
        }
    }
    
    private String getSseDataFromPage(Page page, String varName) {
        try {
            if (page.isClosed()) return null;
            Object result = page.evaluate(String.format("""
                () => {
                    if (window.%s && window.%s.length > 0) {
                        const data = window.%s.join('\\n');
                        window.%s = [];
                        return data;
                    }
                    return null;
                }
                """, varName, varName, varName, varName));
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== 联网搜索 ====================
    
    private void handleWebSearchToggle(Page page, boolean enable) {
        try {
            Locator toggle = page.locator("button:has-text('联网搜索')")
                    .or(page.locator("button:has-text('联网')"))
                    .first();

            if (toggle.count() > 0) {
                String className = toggle.getAttribute("class");
                boolean isActive = className != null && 
                        (className.contains("active") || className.contains("selected") ||
                         className.contains("ds-toggle-button--active"));

                if (enable && !isActive) {
                    toggle.click();
                    page.waitForTimeout(500);
                    log.info("已启用联网搜索");
                } else if (!enable && isActive) {
                    toggle.click();
                    page.waitForTimeout(300);
                    log.info("已关闭联网搜索");
                }
            }
        } catch (Exception e) {
            log.debug("处理联网搜索时出错: {}", e.getMessage());
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
        String markedContent = "__REPLACE__" + content;
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(markedContent).build())
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
                if (url.contains("chat.deepseek.com")) {
                    pageUrls.put(request.getModel(), url);
                    sendConversationUrl(emitter, UUID.randomUUID().toString(), url, request.getModel());
                    log.info("已发送对话 URL: {}", url);
                }
            }
        } catch (Exception e) {
            log.warn("发送对话 URL 时出错: {}", e.getMessage());
        }
        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
        emitter.complete();
    }
    
    private void sendConversationUrl(SseEmitter emitter, String id, String url, String model) throws IOException {
        String content = "\n\n__CONVERSATION_URL_START__\n" + url + "\n__CONVERSATION_URL_END__\n\n";
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }
    
    // ==================== SSE 解析 ====================
    
    private String extractTextFromSse(String sseData) {
        if (sseData == null || sseData.isEmpty()) return null;
        
        StringBuilder text = new StringBuilder();
        for (String line : sseData.split("\n")) {
            line = line.trim();
            if (line.startsWith("data: ")) {
                String jsonStr = line.substring(6).trim();
                if (jsonStr.isEmpty() || jsonStr.equals("{}")) continue;
                try {
                    JsonNode json = objectMapper.readTree(jsonStr);
                    if (json.has("v") && json.get("v").isString()) {
                        text.append(json.get("v").asString());
                    }
                } catch (Exception e) { }
            }
        }
        return !text.isEmpty() ? text.toString() : null;
    }
    
    private ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, 
                                                     Integer lastActiveFragmentIndex) {
        if (sseData == null || sseData.isEmpty()) {
            return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), lastActiveFragmentIndex);
        }
        
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder responseText = new StringBuilder();
        boolean finished = false;
        Integer currentActiveIndex = lastActiveFragmentIndex;
        
        for (String line : sseData.split("\n")) {
            line = line.trim();
            
            if (line.startsWith("event: ")) {
                String event = line.substring(7).trim();
                if ("finish".equals(event) || "close".equals(event)) {
                    finished = true;
                }
                continue;
            }
            
            if (!line.startsWith("data: ")) continue;
            String jsonStr = line.substring(6).trim();
            if (jsonStr.isEmpty() || jsonStr.equals("{}")) continue;
            
            try {
                JsonNode json = objectMapper.readTree(jsonStr);
                String path = json.has("p") ? json.get("p").asString() : null;
                String operation = json.has("o") ? json.get("o").asString() : null;
                
                // Fragment 创建（处理多种可能的路径格式）
                if ((path != null && (path.equals("fragments") || path.equals("response/fragments") || path.endsWith("/fragments")))
                        && "APPEND".equals(operation) && json.has("v") && json.get("v").isArray()) {
                    int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                            fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                    for (JsonNode fragment : json.get("v")) {
                        if (fragment.has("type")) {
                            String type = fragment.get("type").asString();
                            fragmentTypeMap.put(nextIndex, type);
                            log.info("创建 fragment {}: type={}", nextIndex, type);
                            if (fragment.has("content")) {
                                String content = fragment.get("content").asString();
                                if (content != null && !content.isEmpty()) {
                                    log.debug("Fragment {} 初始内容: {}", nextIndex, content.length() > 50 ? content.substring(0, 50) + "..." : content);
                                    if ("THINK".equals(type)) thinkingText.append(content);
                                    else if ("RESPONSE".equals(type)) responseText.append(content);
                                }
                            }
                            nextIndex++;
                        }
                    }
                    continue;
                }
                
                // 内容更新 - 处理完整路径
                if (path != null && path.contains("fragments/") && path.endsWith("/content")) {
                    Integer idx = extractFragmentIndex(path);
                    if (idx != null) {
                        currentActiveIndex = idx;
                        String type = fragmentTypeMap.get(idx);
                        if (json.has("v") && json.get("v").isString()) {
                            String content = json.get("v").asString();
                            if (content != null && !content.isEmpty()) {
                                log.trace("Fragment {} 内容更新 (type={}): {}", idx, type, content);
                                if ("THINK".equals(type)) thinkingText.append(content);
                                else responseText.append(content);
                            }
                        }
                    } else {
                        log.debug("无法从路径提取索引: {}", path);
                    }
                }
                // 简单格式 - 只有 v 字段，没有 p 字段
                else if (json.has("v") && !json.has("p") && json.get("v").isString()) {
                    String content = json.get("v").asString();
                    if (content != null && !content.isEmpty()) {
                        // 根据当前活动的 fragment 类型决定是思考还是回复
                        boolean isThinking = currentActiveIndex != null && 
                                "THINK".equals(fragmentTypeMap.get(currentActiveIndex));
                        log.trace("简单格式内容 (activeIdx={}, isThinking={}): {}", currentActiveIndex, isThinking, content);
                        if (isThinking) {
                            thinkingText.append(content);
                        } else {
                            responseText.append(content);
                        }
                    }
                }
                // BATCH 操作
                else if (path != null && "BATCH".equals(operation) && json.has("v") && json.get("v").isArray()) {
                    for (JsonNode item : json.get("v")) {
                        if (!item.has("p") || !item.has("v")) continue;
                        String itemPath = item.get("p").asString();
                        
                        // 处理 fragment 创建
                        if (itemPath != null && (itemPath.equals("fragments") || itemPath.endsWith("/fragments"))) {
                            if (item.has("o") && "APPEND".equals(item.get("o").asString()) && item.get("v").isArray()) {
                                int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                                        fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                                for (JsonNode fragment : item.get("v")) {
                                    if (fragment.has("type")) {
                                        String type = fragment.get("type").asString();
                                        fragmentTypeMap.put(nextIndex, type);
                                        log.info("BATCH 创建 fragment {}: type={}", nextIndex, type);
                                        if (fragment.has("content")) {
                                            String content = fragment.get("content").asString();
                                            if (content != null && !content.isEmpty()) {
                                                if ("THINK".equals(type)) thinkingText.append(content);
                                                else if ("RESPONSE".equals(type)) responseText.append(content);
                                            }
                                        }
                                        nextIndex++;
                                    }
                                }
                            }
                            continue;
                        }
                        
                        // 处理内容更新
                        if (itemPath != null && itemPath.contains("fragments/") && itemPath.endsWith("/content")) {
                            Integer idx = extractFragmentIndex(itemPath);
                            if (idx != null) {
                                currentActiveIndex = idx;
                                String type = fragmentTypeMap.get(idx);
                                if (item.get("v").isString()) {
                                    String content = item.get("v").asString();
                                    if (content != null && !content.isEmpty()) {
                                        if ("THINK".equals(type)) thinkingText.append(content);
                                        else if ("RESPONSE".equals(type)) responseText.append(content);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("解析 SSE 数据行时出错: {}, line: {}", e.getMessage(), line.length() > 100 ? line.substring(0, 100) + "..." : line);
            }
        }
        
        if (log.isDebugEnabled() && (thinkingText.length() > 0 || responseText.length() > 0)) {
            log.debug("SSE 解析结果: thinking={} chars, response={} chars, finished={}", 
                    thinkingText.length(), responseText.length(), finished);
        }
        
        ModelConfig.SseParseResult result = new ModelConfig.SseParseResult(
                thinkingText.length() > 0 ? thinkingText.toString() : null,
                responseText.length() > 0 ? responseText.toString() : null,
                finished
        );
        return new ModelConfig.ParseResultWithIndex(result, currentActiveIndex);
    }
    
    /**
     * 从路径中提取 fragment 索引
     * 支持格式：response/fragments/0/content, fragments/0/content
     */
    private Integer extractFragmentIndex(String path) {
        if (path == null) return null;
        try {
            // 查找 "fragments/" 后面的数字
            int fragmentsIdx = path.indexOf("fragments/");
            if (fragmentsIdx >= 0) {
                String afterFragments = path.substring(fragmentsIdx + "fragments/".length());
                int slashIdx = afterFragments.indexOf('/');
                String indexStr = slashIdx > 0 ? afterFragments.substring(0, slashIdx) : afterFragments;
                return Integer.parseInt(indexStr);
            }
        } catch (Exception e) {
            log.debug("提取 fragment 索引失败: path={}", path);
        }
        return null;
    }
    
    // ==================== URL 提取 ====================
    
    private String extractUrlFromHistory(ChatCompletionRequest request) {
        if (request.getMessages() == null) return null;
        
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                int start = content.indexOf("__CONVERSATION_URL_START__");
                int end = content.indexOf("__CONVERSATION_URL_END__");
                if (start != -1 && end != -1 && end > start) {
                    String url = content.substring(start + "__CONVERSATION_URL_START__".length(), end).trim();
                    url = url.lines().filter(l -> !l.isEmpty()).findFirst().orElse("").trim();
                    if (url.contains("chat.deepseek.com")) return url;
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


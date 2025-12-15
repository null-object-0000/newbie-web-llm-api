package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class DeepSeekProvider extends BaseProvider {

    private final BrowserManager browserManager;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // 保存每个模型的页面引用（key: model, value: page）
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    // 记录每个页面的 URL（key: model, value: url）
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
    /**
     * 根据 URL 查找对应的页面
     */
    private Page findPageByUrl(String model, String targetUrl) {
        Page page = modelPages.get(model);
        if (page != null && !page.isClosed()) {
            String savedUrl = pageUrls.get(model);
            if (savedUrl != null && savedUrl.equals(targetUrl)) {
                return page;
            }
        }
        return null;
    }

    public DeepSeekProvider(BrowserManager browserManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.browserManager = browserManager;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public List<String> getSupportedModels() {
        return Arrays.asList("deepseek-web");
    }

    /**
     * 处理流式聊天请求
     */
    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        // 在新线程中执行，避免阻塞 Controller 主线程
        executor.submit(() -> {
            Page page = null;
            boolean shouldClosePage = false;
            try {
                String model = request.getModel();

                // 1. 获取或创建页面
                if (request.isNewConversation()) {
                    // 新对话：关闭旧页面（如果存在），创建新页面
                    Page oldPage = modelPages.remove(model);
                    if (oldPage != null && !oldPage.isClosed()) {
                        try {
                            oldPage.close();
                            log.info("已关闭旧页面，准备创建新对话");
                        } catch (Exception e) {
                            log.warn("关闭旧页面时出错", e);
                        }
                    }
                    page = browserManager.newPage();
                    modelPages.put(model, page);
                    shouldClosePage = false; // 不关闭，保存复用

                    // 2. 导航到 DeepSeek
                    page.navigate("https://chat.deepseek.com/");
                    page.waitForLoadState();
                    
                    // 记录页面 URL
                    String initialUrl = page.url();
                    pageUrls.put(model, initialUrl);
                    log.info("创建新对话页面，模型: {}, URL: {}", model, initialUrl);
                } else {
                    // 继续对话：根据 conversationUrl 决定使用哪个页面
                    String conversationUrl = request.getConversationUrl();
                    
                    if (conversationUrl != null && !conversationUrl.isEmpty() && 
                        conversationUrl.contains("chat.deepseek.com")) {
                        // 如果有指定的对话 URL，尝试导航到该 URL
                        log.info("继续指定对话，URL: {}", conversationUrl);
                        
                        // 检查是否有对应 URL 的页面
                        page = findPageByUrl(model, conversationUrl);
                        
                        if (page == null || page.isClosed()) {
                            // 创建新页面并导航到指定 URL
                            if (page != null && page.isClosed()) {
                                modelPages.remove(model, page);
                            }
                            page = browserManager.newPage();
                            modelPages.put(model, page);
                            
                            page.navigate(conversationUrl);
                            page.waitForLoadState();
                            pageUrls.put(model, conversationUrl); // 记录 URL
                            log.info("已导航到指定对话 URL: {}", conversationUrl);
                        } else {
                            // 复用现有页面，但确保 URL 正确
                            String currentUrl = page.url();
                            if (!currentUrl.equals(conversationUrl)) {
                                log.info("页面 URL 不匹配，导航到指定 URL。当前: {}, 目标: {}", currentUrl, conversationUrl);
                                page.navigate(conversationUrl);
                                page.waitForLoadState();
                                pageUrls.put(model, conversationUrl); // 更新记录的 URL
                            } else {
                                log.info("复用现有页面，URL: {}", currentUrl);
                            }
                        }
                    } else {
                        // 没有指定 URL，复用现有页面（兼容旧逻辑）
                        page = modelPages.get(model);
                        if (page == null || page.isClosed()) {
                            // 如果页面不存在或已关闭，创建新页面
                            log.info("页面不存在或已关闭，创建新页面，模型: {}", model);
                            if (page != null && page.isClosed()) {
                                // 从缓存中移除已关闭的页面
                                modelPages.remove(model, page);
                            }
                            page = browserManager.newPage();
                            modelPages.put(model, page);

                            page.navigate("https://chat.deepseek.com/");
                            page.waitForLoadState();
                        } else {
                            log.info("复用现有页面，模型: {}, URL: {}", model, page.url());

                            // 确保页面仍然在 DeepSeek 网站
                            String currentUrl = page.url();
                            if (!currentUrl.contains("chat.deepseek.com")) {
                                log.warn("页面不在 DeepSeek 网站，重新导航");
                                page.navigate("https://chat.deepseek.com/");
                                page.waitForLoadState();
                            }
                        }
                    }
                    shouldClosePage = false; // 不关闭，保存复用
                }

                // 为当前请求创建 SSE 内容队列（每次请求都需要新的队列）
                // 在页面创建后设置 SSE 拦截器
                BlockingQueue<String> sseContentQueue = new LinkedBlockingQueue<>();
                setupSseInterceptor(page, sseContentQueue);

                // 3. 如果是新对话，点击"新对话"按钮
                if (request.isNewConversation()) {
                    try {
                        // 尝试点击新对话按钮（根据实际页面调整选择器）
                        // 常见的选择器：button[aria-label*="New"], .new-chat-button, [data-testid="new-chat"]
                        page.waitForTimeout(1000); // 等待页面加载

                        // 尝试多种方式找到新对话按钮
                        Locator newChatButton = page.locator("button:has-text('新对话')")
                                .or(page.locator("button:has-text('New Chat')"))
                                .or(page.locator("[aria-label*='New'], [aria-label*='new']"))
                                .first();

                        if (newChatButton.count() > 0) {
                            newChatButton.click();
                            page.waitForTimeout(500); // 等待新对话界面加载
                            log.info("已点击新对话按钮");
                        } else {
                            log.warn("未找到新对话按钮，将使用当前对话");
                        }
                    } catch (Exception e) {
                        log.warn("点击新对话按钮时出错，继续使用当前对话", e);
                    }
                }

                // 5. 处理深度思考模式开关
                if (request.isThinking()) {
                    try {
                        log.info("尝试启用深度思考模式");
                        // 根据实际 DOM 结构查找深度思考按钮
                        // 按钮包含文本"深度思考"，类名包含 ds-toggle-button
                        Locator thinkingToggle = page.locator("button.ds-toggle-button:has-text('深度思考')")
                                .or(page.locator("button:has-text('深度思考')"))
                                .or(page.locator("button[aria-label*='思考'], button[aria-label*='Think']"))
                                .or(page.locator(".ds-toggle-button:has-text('Thinking')"))
                                .first();
                        
                        if (thinkingToggle.count() > 0) {
                            // 检查按钮是否已激活（toggle button 可能有 active 状态）
                            try {
                                String className = thinkingToggle.getAttribute("class");
                                boolean isActive = className != null && 
                                    (className.contains("active") || className.contains("selected") || 
                                     className.contains("ds-toggle-button--active"));
                                
                                if (!isActive) {
                                    thinkingToggle.click();
                                    page.waitForTimeout(500); // 等待开关切换
                                    log.info("已启用深度思考模式");
                                } else {
                                    log.info("深度思考模式已启用");
                                }
                            } catch (Exception e) {
                                // 如果检查失败，直接点击
                                thinkingToggle.click();
                                page.waitForTimeout(500);
                                log.info("已点击深度思考按钮");
                            }
                        } else {
                            // 如果找不到按钮，尝试通过 JavaScript 直接设置
                            try {
                                Boolean result = (Boolean) page.evaluate("""
                                    () => {
                                        // 查找包含"深度思考"文本的按钮
                                        const buttons = document.querySelectorAll('button');
                                        for (const btn of buttons) {
                                            const text = btn.textContent || '';
                                            if (text.includes('深度思考') || text.includes('Thinking')) {
                                                // 检查是否是 toggle button 且未激活
                                                const isActive = btn.classList.contains('active') || 
                                                               btn.classList.contains('selected') ||
                                                               btn.classList.contains('ds-toggle-button--active');
                                                if (!isActive) {
                                                    btn.click();
                                                    return true;
                                                }
                                                return false;
                                            }
                                        }
                                        return false;
                                    }
                                """);
                                if (Boolean.TRUE.equals(result)) {
                                    log.info("通过 JavaScript 已启用深度思考模式");
                                } else {
                                    log.warn("未找到深度思考按钮或已启用");
                                }
                            } catch (Exception e) {
                                log.warn("无法启用深度思考模式: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("启用深度思考模式时出错，继续执行: {}", e.getMessage());
                    }
                } else {
                    // 如果不需要深度思考模式，确保它是关闭的
                    try {
                        // 查找深度思考按钮，如果已激活则点击关闭
                        Locator thinkingToggle = page.locator("button.ds-toggle-button:has-text('深度思考')")
                                .or(page.locator("button:has-text('深度思考')"))
                                .first();
                        
                        if (thinkingToggle.count() > 0) {
                            try {
                                String className = thinkingToggle.getAttribute("class");
                                boolean isActive = className != null && 
                                    (className.contains("active") || className.contains("selected") || 
                                     className.contains("ds-toggle-button--active"));
                                
                                if (isActive) {
                                    thinkingToggle.click();
                                    page.waitForTimeout(300);
                                    log.info("已关闭深度思考模式");
                                }
                            } catch (Exception e) {
                                // 如果检查失败，尝试通过 JavaScript 关闭
                                page.evaluate("""
                                    () => {
                                        const buttons = document.querySelectorAll('button');
                                        for (const btn of buttons) {
                                            const text = btn.textContent || '';
                                            if (text.includes('深度思考') || text.includes('Thinking')) {
                                                const isActive = btn.classList.contains('active') || 
                                                               btn.classList.contains('selected') ||
                                                               btn.classList.contains('ds-toggle-button--active');
                                                if (isActive) {
                                                    btn.click();
                                                    return true;
                                                }
                                            }
                                        }
                                        return false;
                                    }
                                """);
                            }
                        }
                    } catch (Exception e) {
                        // 忽略错误
                    }
                }

                // 6. 定位输入框并填入内容
                // 提示：DeepSeek 的输入框通常就是页面上唯一的 textarea
                Locator inputBox = page.locator("textarea");
                inputBox.waitFor(); // 等待出现

                // 7. 处理消息：无论是新对话还是继续对话，都只发送最后一条用户消息
                // 因为继续对话时，页面已经保存了之前的对话上下文，只需要发送新的用户输入即可
                String messageToSend = request.getMessages().stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .reduce((first, second) -> second) // 取最后一个用户消息
                        .map(ChatCompletionRequest.Message::getContent)
                        .orElse("Hello");

                if (request.isNewConversation()) {
                    log.info("新对话，发送消息: {} (深度思考: {})", messageToSend, request.isThinking());
                } else {
                    log.info("继续对话，只发送最后一条用户消息: {} (页面已保存历史上下文, 深度思考: {})",
                            messageToSend, request.isThinking());
                }

                // 8. 记录发送前的消息数量（用于只获取新增的回复）
                Locator responseLocatorsBefore = page.locator(".ds-markdown");
                int messageCountBefore = responseLocatorsBefore.count();
                log.info("发送前消息数量: {}", messageCountBefore);

                // 7. 验证 SSE 拦截器是否已设置
                try {
                    Object interceptorStatus = page.evaluate("() => window.__deepseekSseInterceptorSet || false");
                    log.info("SSE 拦截器状态: {}", interceptorStatus);

                    // 如果未设置，重新设置
                    if (!Boolean.TRUE.equals(interceptorStatus)) {
                        log.warn("SSE 拦截器未设置，重新设置...");
                        setupSseInterceptor(page, sseContentQueue);
                    }
                } catch (Exception e) {
                    log.warn("检查 SSE 拦截器状态失败: {}", e.getMessage());
                }

                // 8. 填入消息并发送
                inputBox.fill(messageToSend);
                page.keyboard().press("Enter");

                // 9. 等待一小段时间让请求发出
                page.waitForTimeout(500);

                // 10. 检查是否有 SSE 数据
                String initialSseData = getSseDataFromPage(page);
                if (initialSseData != null && !initialSseData.isEmpty()) {
                    log.info("检测到初始 SSE 数据，长度: {}", initialSseData.length());
                }

                // 11. 使用混合方式监听回复：DOM 实时流式 + SSE 最终修正
                log.info("开始监听 AI 回复（DOM 实时 + SSE 修正）...");
                monitorResponseHybrid(page, emitter, request, messageCountBefore, sseContentQueue);

            } catch (Exception e) {
                log.error("Chat Error", e);
                emitter.completeWithError(e);
                // 如果出错，从缓存中移除页面
                if (page != null) {
                    modelPages.remove(request.getModel(), page);
                    try {
                        if (!page.isClosed()) {
                            page.close();
                        }
                    } catch (Exception closeEx) {
                        log.warn("关闭页面时出错", closeEx);
                    }
                }
            } finally {
                // 只有在明确需要关闭时才关闭（新对话时已经处理，继续对话时不关闭）
                if (shouldClosePage && page != null && !page.isClosed()) {
                    try {
                        page.close();
                        modelPages.remove(request.getModel(), page);
                    } catch (Exception e) {
                        log.warn("关闭页面时出错", e);
                    }
                }
            }
        });
    }

    /**
     * 设置 SSE 拦截器（通过 JavaScript 注入拦截 EventSource 和 fetch）
     */
    private void setupSseInterceptor(Page page, BlockingQueue<String> sseContentQueue) {
        // 初始化全局变量
        try {
            page.evaluate("""
                        () => {
                            if (!window.__deepseekSseData) {
                                window.__deepseekSseData = [];
                            }
                        }
                    """);
        } catch (Exception e) {
            log.debug("初始化 SSE 数据存储失败: {}", e.getMessage());
        }

        // 注入 JavaScript 来拦截 EventSource 和 fetch 的 SSE 响应
        // 使用 evaluate 而不是 addInitScript，确保在页面加载后也能执行
        try {
            page.evaluate("""
                        (function() {
                            // 如果已经设置过，不再重复设置
                            if (window.__deepseekSseInterceptorSet) {
                                return;
                            }
                            window.__deepseekSseInterceptorSet = true;
                    
                            // 拦截 EventSource
                            const originalEventSource = window.EventSource;
                            window.EventSource = function(url, eventSourceInitDict) {
                                console.log('[SSE Interceptor] EventSource created:', url);
                                const es = new originalEventSource(url, eventSourceInitDict);
                    
                                // 只拦截 chat/completion 相关的 EventSource
                                if (url.includes('/api/v0/chat/completion')) {
                                    console.log('[SSE Interceptor] Intercepting EventSource:', url);
                    
                                    es.addEventListener('message', function(event) {
                                        console.log('[SSE Interceptor] EventSource message:', event.data);
                                        window.__deepseekSseData = window.__deepseekSseData || [];
                                        window.__deepseekSseData.push(event.data);
                                    });
                    
                                    es.addEventListener('error', function(event) {
                                        console.log('[SSE Interceptor] EventSource error:', event);
                                    });
                                }
                    
                                return es;
                            };
                    
                            // 拦截 fetch 的 SSE 响应
                            const originalFetch = window.fetch;
                            window.fetch = function(...args) {
                                const url = args[0];
                                if (typeof url === 'string' && url.includes('/api/v0/chat/completion')) {
                                    console.log('[SSE Interceptor] Intercepting fetch:', url);
                    
                                    return originalFetch.apply(this, args).then(response => {
                                        const contentType = response.headers.get('content-type');
                                        console.log('[SSE Interceptor] Response content-type:', contentType);
                    
                                        if (contentType && contentType.includes('text/event-stream')) {
                                            console.log('[SSE Interceptor] Detected SSE response');
                    
                                            // 克隆响应，这样原始响应仍然可用
                                            const clonedResponse = response.clone();
                                            const reader = clonedResponse.body.getReader();
                                            const decoder = new TextDecoder();
                    
                                            window.__deepseekSseData = window.__deepseekSseData || [];
                    
                                            function readStream() {
                                                reader.read().then(({ done, value }) => {
                                                    if (done) {
                                                        console.log('[SSE Interceptor] Stream finished');
                                                        return;
                                                    }
                    
                                                    const chunk = decoder.decode(value, { stream: true });
                                                    console.log('[SSE Interceptor] Received chunk:', chunk.substring(0, 100));
                                                    window.__deepseekSseData.push(chunk);
                    
                                                    readStream();
                                                }).catch(err => {
                                                    console.error('[SSE Interceptor] Read error:', err);
                                                });
                                            }
                    
                                            readStream();
                                        }
                    
                                        return response;
                                    });
                                }
                                return originalFetch.apply(this, args);
                            };
                    
                            // 拦截 XMLHttpRequest 的 SSE 响应
                            const originalXHROpen = XMLHttpRequest.prototype.open;
                            const originalXHRSend = XMLHttpRequest.prototype.send;
                    
                            XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                                this._interceptedUrl = url;
                                return originalXHROpen.apply(this, [method, url, ...rest]);
                            };
                    
                            XMLHttpRequest.prototype.send = function(...args) {
                                if (this._interceptedUrl && this._interceptedUrl.includes('/api/v0/chat/completion')) {
                                    console.log('[SSE Interceptor] Intercepting XMLHttpRequest:', this._interceptedUrl);
                    
                                    const originalOnReadyStateChange = this.onreadystatechange;
                                    this.onreadystatechange = function() {
                                        if (this.readyState === 3 || this.readyState === 4) { // LOADING or DONE
                                            const responseText = this.responseText;
                                            if (responseText) {
                                                const contentType = this.getResponseHeader('content-type');
                                                if (contentType && contentType.includes('text/event-stream')) {
                                                    console.log('[SSE Interceptor] XMLHttpRequest SSE data:', responseText.substring(0, 100));
                                                    window.__deepseekSseData = window.__deepseekSseData || [];
                                                    // 只保存新增的部分
                                                    if (this._lastResponseLength === undefined) {
                                                        this._lastResponseLength = 0;
                                                    }
                                                    if (responseText.length > this._lastResponseLength) {
                                                        const newData = responseText.substring(this._lastResponseLength);
                                                        window.__deepseekSseData.push(newData);
                                                        this._lastResponseLength = responseText.length;
                                                    }
                                                }
                                            }
                                        }
                                        if (originalOnReadyStateChange) {
                                            originalOnReadyStateChange.apply(this, arguments);
                                        }
                                    };
                                }
                                return originalXHRSend.apply(this, args);
                            };
                    
                            console.log('[SSE Interceptor] Interceptor setup complete');
                        })();
                    """);
            log.info("已设置 SSE 拦截器（通过 JavaScript 注入）");
        } catch (Exception e) {
            log.error("设置 SSE 拦截器失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从 JavaScript 中获取 SSE 数据
     */
    private String getSseDataFromPage(Page page) {
        try {
            if (page.isClosed()) {
                return null;
            }

            Object result = page.evaluate("""
                        () => {
                            if (window.__deepseekSseData && window.__deepseekSseData.length > 0) {
                                const data = window.__deepseekSseData.join('\\n');
                                window.__deepseekSseData = []; // 清空已读取的数据
                                console.log('[SSE Interceptor] Returning data, length:', data.length);
                                return data;
                            }
                            return null;
                        }
                    """);

            if (result != null) {
                String data = result.toString();
                if (!data.isEmpty()) {
                    log.debug("从页面获取到 SSE 数据，长度: {}", data.length());
                }
                return data;
            }
            return null;
        } catch (Exception e) {
            // 如果是页面关闭错误，不记录日志
            if (!e.getMessage().contains("Target closed") && !e.getMessage().contains("Session closed")) {
                log.debug("获取 SSE 数据失败: {}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * 解析 SSE 数据，提取最终回复文本内容（不包括思考内容）
     * 注意：SSE 数据中可能包含思考内容和最终回复，我们需要从 DOM 中区分
     * 这个方法主要用于向后兼容，实际区分应该在 DOM 解析时完成
     */
    private String extractTextFromSse(String sseData) {
        if (sseData == null || sseData.isEmpty()) {
            return null;
        }
        
        StringBuilder text = new StringBuilder();
        String[] lines = sseData.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("data: ")) {
                String jsonStr = line.substring(6).trim();
                if (jsonStr.isEmpty() || jsonStr.equals("{}")) {
                    continue;
                }
                
                try {
                    JsonNode json = objectMapper.readTree(jsonStr);
                    
                    // 提取 "v" 字段（文本内容）
                    if (json.has("v") && json.get("v").isTextual()) {
                        String content = json.get("v").asText();
                        if (content != null && !content.isEmpty()) {
                            text.append(content);
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
        }
        
        return text.length() > 0 ? text.toString() : null;
    }

    /**
     * 混合方式监听回复：DOM 实时流式 + SSE 最终修正
     * 支持区分思考内容和最终回复（深度思考模式）
     */
    private void monitorResponseHybrid(Page page, SseEmitter emitter, ChatCompletionRequest request,
                                      int messageCountBefore, BlockingQueue<String> sseContentQueue)
            throws IOException, InterruptedException {
        // 思考内容选择器：在 .ds-think-content 内的 .ds-markdown
        String thinkingSelector = ".ds-think-content .ds-markdown";
        // 所有 markdown 选择器（用于查找最终回复）
        String allMarkdownSelector = ".ds-markdown";
        
        // 等待新的回复框出现
        Locator allResponseLocators = page.locator(allMarkdownSelector);
        int maxWaitTime = 30000;
        long waitStartTime = System.currentTimeMillis();
        while (allResponseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始混合监听（DOM 实时 + SSE 修正），支持区分思考内容");
        
        StringBuilder collectedText = new StringBuilder();
        StringBuilder collectedThinkingText = new StringBuilder(); // 思考内容
        StringBuilder sseCollectedText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String lastFullText = "";
        int noChangeCount = 0;
        boolean sseFinished = false;

        while (true) {
            try {
                // 检查页面状态
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                // 1. DOM 解析（实时流式）
                try {
                    // 1.1 检查思考内容（深度思考模式）
                    try {
                        Locator thinkingLocators = page.locator(thinkingSelector);
                        int thinkingCount = thinkingLocators.count();
                        
                        if (thinkingCount > 0) {
                            // 获取最后一个思考内容
                            String thinkingText = thinkingLocators.nth(thinkingCount - 1).innerText();
                            
                            if (thinkingText.length() > collectedThinkingText.length()) {
                                String thinkingDelta = thinkingText.substring(collectedThinkingText.length());
                                collectedThinkingText.append(thinkingDelta);
                                
                                // 发送思考内容（使用特殊标记）
                                log.debug("发送思考内容增量，长度: {}", thinkingDelta.length());
                                sendThinkingContent(emitter, id, thinkingDelta, request.getModel());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("读取思考内容时出错: {}", e.getMessage());
                    }
                    
                    // 1.2 检查最终回复内容（排除思考区域内的）
                    Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                    int currentCount = allMarkdownLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        try {
                            // 查找最后一个不在思考区域内的 markdown 元素
                            String finalText = null;
                            for (int i = currentCount - 1; i >= messageCountBefore; i--) {
                                try {
                                    Locator locator = allMarkdownLocators.nth(i);
                                    // 检查是否在思考区域内
                                    Boolean isInThinking = (Boolean) locator.evaluate("""
                                        el => {
                                            const thinkContent = el.closest('.ds-think-content');
                                            return thinkContent !== null;
                                        }
                                    """);
                                    
                                    if (isInThinking == null || !isInThinking) {
                                        // 不在思考区域内，这是最终回复
                                        finalText = locator.innerText();
                                        break;
                                    }
                                } catch (Exception e) {
                                    // 继续查找下一个
                                }
                            }
                            
                            if (finalText != null) {
                                if (finalText.length() > collectedText.length()) {
                                    String delta = finalText.substring(collectedText.length());
                                    collectedText.append(delta);
                                    noChangeCount = 0;
                                    
                                    // 发送增量（实时）
                                    log.debug("发送最终回复增量，长度: {}", delta.length());
                                    sendSseChunk(emitter, id, delta, request.getModel());
                                } else if (finalText.equals(lastFullText) && finalText.length() > 0) {
                                    noChangeCount++;
                                }
                                
                                lastFullText = finalText;
                            }
                        } catch (Exception e) {
                            log.debug("读取 DOM 文本时出错: {}", e.getMessage());
                            // 继续尝试，不中断
                        }
                    }
                } catch (Exception e) {
                    log.debug("DOM 查询时出错: {}", e.getMessage());
                    // 如果 DOM 查询失败，可能是页面正在更新，继续尝试
                }

                // 2. SSE 解析（用于最终修正）
                if (!sseFinished) {
                    try {
                        String sseData = getSseDataFromPage(page);
                        if (sseData != null && !sseData.isEmpty()) {
                            log.debug("获取到 SSE 数据，长度: {}, 前200字符: {}",
                                    sseData.length(),
                                    sseData.length() > 200 ? sseData.substring(0, 200) : sseData);

                            // 检查是否完成
                            if (sseData.contains("event: finish") || sseData.contains("event: close")) {
                                sseFinished = true;
                                log.info("SSE 流已完成");
                            }

                            // 提取文本
                            String sseText = extractTextFromSse(sseData);
                            if (sseText != null && !sseText.isEmpty()) {
                                log.debug("从 SSE 提取到文本，长度: {}, 内容: {}",
                                        sseText.length(),
                                        sseText.length() > 100 ? sseText.substring(0, 100) : sseText);
                                sseCollectedText.append(sseText);
                            } else {
                                log.debug("未能从 SSE 数据中提取文本");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("获取 SSE 数据时出错: {}", e.getMessage());
                        // 继续尝试，不中断
                    }
                }

                // 3. 如果 SSE 已完成，从 DOM 获取最终回复并做修正（整体替换，不包括思考内容）
                if (sseFinished) {
                    // 从 DOM 获取最终的回复内容（不包括思考内容）
                    try {
                        Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                        int currentCount = allMarkdownLocators.count();
                        
                        String domFinalText = collectedText.toString();
                        
                        // 查找最后一个不在思考区域内的 markdown 元素
                        for (int i = currentCount - 1; i >= messageCountBefore; i--) {
                            try {
                                Locator locator = allMarkdownLocators.nth(i);
                                Boolean isInThinking = (Boolean) locator.evaluate("""
                                    el => {
                                        const thinkContent = el.closest('.ds-think-content');
                                        return thinkContent !== null;
                                    }
                                """);
                                
                                if (isInThinking == null || !isInThinking) {
                                    // 不在思考区域内，这是最终回复
                                    String finalText = locator.innerText();
                                    
                                    if (!finalText.equals(domFinalText)) {
                                        log.info("检测到最终回复内容差异，使用 DOM 内容整体替换");
                                        log.debug("当前内容长度: {}, DOM最终回复长度: {}", 
                                                domFinalText.length(), finalText.length());
                                        
                                        try {
                                            // 发送整体替换消息（只替换最终回复，不包括思考内容）
                                            sendSseReplace(emitter, id, finalText, request.getModel());
                                            collectedText = new StringBuilder(finalText);
                                            log.info("整体替换最终回复已发送，长度: {}", finalText.length());
                                        } catch (Exception e) {
                                            log.error("发送整体替换内容失败: {}", e.getMessage(), e);
                                        }
                                    } else {
                                        log.info("最终回复内容一致，无需替换");
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                // 继续查找下一个
                            }
                        }
                    } catch (Exception e) {
                        log.warn("获取最终回复时出错: {}", e.getMessage());
                    }
                    break;
                }

                // 4. 结束条件检查
                if (noChangeCount >= 20 && sseFinished) {
                    log.info("DOM 和 SSE 都已完成，生成完成");
                    break;
                }

                if (System.currentTimeMillis() - startTime > 120000) {
                    log.warn("达到超时时间，结束监听");
                    break;
                }

                Thread.sleep(100);
            } catch (Exception e) {
                // 检查是否是页面关闭或导航导致的错误
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                // 如果是 Playwright 连接错误，可能是页面状态变化，尝试继续
                if (e.getMessage() != null &&
                        (e.getMessage().contains("Cannot find command") ||
                                e.getMessage().contains("Target closed") ||
                                e.getMessage().contains("Session closed"))) {
                    log.warn("检测到页面状态变化，尝试继续监听: {}", e.getMessage());
                    Thread.sleep(500); // 等待页面稳定
                    continue;
                }

                log.error("监听响应时出错", e);
                if (collectedText.length() > 0) {
                    log.info("已收集部分内容，长度: {}，结束监听", collectedText.length());
                    break;
                }
                // 如果还没有收集到内容，抛出异常
                throw e;
            }
        }

        // 在发送完成标记之前，发送当前页面的 URL 给前端
        try {
            if (page != null && !page.isClosed()) {
                String currentUrl = page.url();
                if (currentUrl.contains("chat.deepseek.com")) {
                    // 更新记录的 URL
                    String model = request.getModel();
                    pageUrls.put(model, currentUrl);
                    // 发送 URL 给前端（使用一个临时 ID）
                    String urlId = UUID.randomUUID().toString();
                    sendConversationUrl(emitter, urlId, currentUrl, model);
                    log.info("已发送对话 URL: {}", currentUrl);
                }
            }
        } catch (Exception e) {
            log.warn("发送对话 URL 时出错: {}", e.getMessage());
        }

        sendDone(emitter);
    }

    /**
     * 从 DOM 解析监听 AI 回复（回退方法）
     */
    private void monitorResponse(Page page, SseEmitter emitter, ChatCompletionRequest request, int messageCountBefore) throws IOException, InterruptedException {
        // --- 定义选择器 (这里最容易失效，需要根据实际 F12 调整) ---
        // 策略：找到所有的消息气泡，只监听新增的消息（索引 >= messageCountBefore）
        // 假设 DeepSeek 的回复都包含在某个特定的 div class 里，比如 "ds-markdown" 或 "message-content"
        String responseSelector = ".ds-markdown";

        // 等待新的回复框出现（消息数量增加）
        Locator allResponseLocators = page.locator(responseSelector);
        int maxWaitTime = 30000; // 最多等待30秒
        long waitStartTime = System.currentTimeMillis();
        while (allResponseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }

        log.info("检测到新消息，当前消息数量: {} (之前: {})", allResponseLocators.count(), messageCountBefore);

        // 只监听新增的消息（最后一个）
        // 这是一个简单的轮询逻辑
        // 生产环境建议使用 mutation observer (JS注入) 以获得更低延迟
        StringBuilder collectedText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();

        String lastFullText = "";
        int noChangeCount = 0;

        while (true) {
            try {
                // 获取最后一个回复框的文本（只获取新增的消息）
                Locator currentResponseLocators = page.locator(responseSelector);
                int currentCount = currentResponseLocators.count();

                if (currentCount <= messageCountBefore) {
                    // 如果消息数量没有增加，说明还没有新消息
                    Thread.sleep(100);
                    continue;
                }

                // 获取最后一个消息的文本（这是新增的消息）
                String fullText = currentResponseLocators.nth(currentCount - 1).innerText();

                if (fullText.length() > collectedText.length()) {
                    // 计算增量 (Delta)
                    String delta = fullText.substring(collectedText.length());
                    collectedText.append(delta);
                    noChangeCount = 0; // 重置无变化计数

                    // 发送 SSE 消息 (OpenAI 格式)
                    log.debug("发送增量: {}", delta);
                    sendSseChunk(emitter, id, delta, request.getModel());
                } else if (fullText.equals(lastFullText) && fullText.length() > 0) {
                    // 文本没有变化
                    noChangeCount++;
                    // 如果连续 20 次（2秒）没有变化，且文本不为空，认为生成完成
                    if (noChangeCount >= 20) {
                        log.info("检测到文本不再变化，生成完成。总长度: {}", fullText.length());
                        break;
                    }
                }

                lastFullText = fullText;

                // 判断结束条件：
                // 1. 出现了 "Regenerate" (重新生成) 按钮，说明生成结束了
                // 2. 或者 "Stop generating" 按钮消失了
                // 这里用一个简单的超时兜底，防止死循环
                if (System.currentTimeMillis() - startTime > 120000) { // 2分钟超时
                    log.warn("达到超时时间，结束监听");
                    break;
                }

                Thread.sleep(100); // 稍微休眠，避免 CPU 100%
            } catch (Exception e) {
                log.error("监听响应时出错", e);
                // 如果出错，尝试发送已收集的文本
                if (collectedText.length() > 0) {
                    break; // 跳出循环，发送完成标记
                }
                throw e;
            }
        }

        // 发送 [DONE]
        sendDone(emitter);
    }

    /**
     * 清理所有页面（应用关闭时调用）
     */
    @PreDestroy
    public void cleanup() {
        log.info("清理所有页面，共 {} 个", modelPages.size());
        for (Page page : modelPages.values()) {
            try {
                if (page != null && !page.isClosed()) {
                    page.close();
                }
            } catch (Exception e) {
                log.warn("关闭页面时出错", e);
            }
        }
        modelPages.clear();
    }
}


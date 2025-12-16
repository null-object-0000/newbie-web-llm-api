package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * 监听模式：true 使用 SSE 拦截数据实时流，false 使用 DOM 解析
     * 可通过 application.properties 中的 deepseek.monitor.mode 配置来切换
     * 可选值：sse（SSE 实时流模式）或 dom（DOM 解析模式，默认）
     */
    @Value("${deepseek.monitor.mode:dom}")
    private String monitorMode;
    
    // SSE 拦截器配置
    private static final SseInterceptorConfig SSE_CONFIG = new SseInterceptorConfig(
            "__deepseekSseData",
            "__deepseekSseInterceptorSet",
            new String[]{"/api/v0/chat/completion"},
            true  // DeepSeek 需要拦截 XMLHttpRequest
    );
    
    /**
     * 根据 URL 查找对应的页面（检查所有打开的 tab）
     */
    private Page findPageByUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) {
            return null;
        }
        
        // 首先检查 modelPages 中是否有匹配的页面
        for (String model : modelPages.keySet()) {
            Page page = modelPages.get(model);
            if (page != null && !page.isClosed()) {
                String savedUrl = pageUrls.get(model);
                if (savedUrl != null && savedUrl.equals(targetUrl)) {
                    log.info("在 modelPages 中找到匹配的页面，模型: {}, URL: {}", model, targetUrl);
                    return page;
                }
            }
        }
        
        // 检查所有打开的 tab 中是否已经打开过此链接
        try {
            List<Page> allPages = browserManager.getAllPages();
            for (Page page : allPages) {
                if (page != null && !page.isClosed()) {
                    try {
                        String currentUrl = page.url();
                        if (currentUrl.equals(targetUrl)) {
                            log.info("在所有打开的 tab 中找到匹配的页面，URL: {}", targetUrl);
                            return page;
                        }
                    } catch (Exception e) {
                        // 忽略获取 URL 时的错误（可能是页面正在关闭）
                        log.debug("获取页面 URL 时出错: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检查所有打开的 tab 时出错: {}", e.getMessage());
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
        return List.of("deepseek-web-chat", "deepseek-web-reasoner");
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
                
                // 根据模型名称自动判断是否需要深度思考模式（不再依赖外部参数）
                boolean shouldEnableThinking = "deepseek-web-reasoner".equals(model);
                if (shouldEnableThinking) {
                    log.info("检测到 deepseek-web-reasoner 模型，自动启用深度思考模式");
                } else {
                    log.info("检测到 {} 模型，自动禁用深度思考模式", model);
                }

                // 1. 获取或创建页面
                // 修改逻辑：只有传递了 conversationUrl 才复用，否则开启新对话
                String conversationUrl = request.getConversationUrl();
                
                // 如果请求中没有传递 conversationUrl，尝试从历史对话中提取（使用基类的公共能力）
                if ((conversationUrl == null || conversationUrl.isEmpty()) && request.getMessages() != null && !request.getMessages().isEmpty()) {
                    String extractedUrl = extractUrlFromHistory(request, "chat.deepseek.com");
                    if (extractedUrl != null && !extractedUrl.isEmpty()) {
                        conversationUrl = extractedUrl;
                        log.info("从历史对话中提取到 URL，将用于复用对话: {}", conversationUrl);
                    }
                }
                
                // 判断是否为新对话：完全根据是否有 conversationUrl 来判断
                boolean isNewConversation = (conversationUrl == null || conversationUrl.isEmpty() || !conversationUrl.contains("chat.deepseek.com"));
                
                if (!isNewConversation) {
                    // 有对话 URL：检查当前打开的 tab 中是否已经打开过此链接
                    log.info("检测到对话 URL，尝试复用: {}", conversationUrl);
                    
                    // 检查所有打开的 tab 中是否已经打开过此链接
                    page = findPageByUrl(conversationUrl);
                    
                    if (page != null && !page.isClosed()) {
                        // 找到已打开的页面，直接复用
                        String currentUrl = page.url();
                        if (currentUrl.equals(conversationUrl)) {
                            log.info("复用已打开的页面，URL: {}", currentUrl);
                            // 确保该页面在 modelPages 中（如果不在，添加进去）
                            if (!modelPages.containsValue(page)) {
                                // 找到对应的 model（如果有），否则使用当前 model
                                modelPages.put(model, page);
                                pageUrls.put(model, conversationUrl);
                            }
                        } else {
                            // URL 不匹配，导航到指定 URL
                            log.info("页面 URL 不匹配，导航到指定 URL。当前: {}, 目标: {}", currentUrl, conversationUrl);
                            page.navigate(conversationUrl);
                            page.waitForLoadState();
                            // 更新记录
                            modelPages.put(model, page);
                            pageUrls.put(model, conversationUrl);
                        }
                    } else {
                        // 没有找到已打开的页面，创建新页面并导航到指定 URL
                        log.info("未找到已打开的页面，创建新页面并导航到: {}", conversationUrl);
                        page = browserManager.newPage();
                        modelPages.put(model, page);
                        
                        page.navigate(conversationUrl);
                        page.waitForLoadState();
                        pageUrls.put(model, conversationUrl);
                        log.info("已导航到指定对话 URL: {}", conversationUrl);
                    }
                    shouldClosePage = false; // 不关闭，保存复用
                } else {
                    // 没有对话 URL：开启新对话
                    log.info("未传递对话 URL，开启新对话");
                    
                    // 关闭旧页面（如果存在）
                    Page oldPage = modelPages.remove(model);
                    if (oldPage != null && !oldPage.isClosed()) {
                        try {
                            oldPage.close();
                            log.info("已关闭旧页面，准备创建新对话");
                        } catch (Exception e) {
                            log.warn("关闭旧页面时出错", e);
                        }
                    }
                    
                    // 创建新页面
                    page = browserManager.newPage();
                    modelPages.put(model, page);
                    shouldClosePage = false; // 不关闭，保存复用

                    // 导航到 DeepSeek
                    page.navigate("https://chat.deepseek.com/");
                    page.waitForLoadState();
                    
                    // 记录页面 URL
                    String initialUrl = page.url();
                    pageUrls.put(model, initialUrl);
                    log.info("创建新对话页面，模型: {}, URL: {}", model, initialUrl);
                }

                // 在页面创建后设置 SSE 拦截器
                setupSseInterceptor(page, SSE_CONFIG);

                // 3. 如果是新对话，点击"新对话"按钮
                if (isNewConversation) {
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

                // 5. 处理深度思考模式开关（根据模型名称自动判断）
                if (shouldEnableThinking) {
                    try {
                        log.info("尝试启用深度思考模式");
                        // 等待页面完全加载，确保按钮已渲染
                        // 先等待输入框出现，说明页面基本加载完成
                        try {
                            page.locator("textarea").waitFor();
                        } catch (Exception e) {
                            log.debug("等待输入框超时: {}", e.getMessage());
                        }
                        page.waitForTimeout(1500); // 额外等待，确保所有按钮都已渲染
                        
                        // 尝试多种方式查找深度思考按钮，最多重试3次
                        boolean thinkingEnabled = false;
                        int maxRetries = 3;
                        
                        for (int retry = 0; retry < maxRetries && !thinkingEnabled; retry++) {
                            if (retry > 0) {
                                log.info("重试查找深度思考按钮 (第 {} 次)", retry + 1);
                                page.waitForTimeout(1000); // 重试前等待
                            }
                            
                            // 方法1: 使用 Playwright Locator
                            try {
                                Locator thinkingToggle = page.locator("div[role='button'].ds-toggle-button:has-text('深度思考')")
                                        .or(page.locator("div[role='button']:has-text('深度思考')"))
                                        .or(page.locator("button.ds-toggle-button:has-text('深度思考')"))
                                        .or(page.locator("button:has-text('深度思考')"))
                                        .or(page.locator("div[role='button'][aria-label*='思考'], div[role='button'][aria-label*='Think']"))
                                        .or(page.locator("button[aria-label*='思考'], button[aria-label*='Think']"))
                                        .or(page.locator(".ds-toggle-button:has-text('Thinking')"))
                                        .or(page.locator("button:has-text('Thinking')"))
                                        .first();
                                
                                if (thinkingToggle.count() > 0) {
                                    thinkingToggle.waitFor();
                                    // 检查按钮是否已激活
                                    try {
                                        String className = thinkingToggle.getAttribute("class");
                                        boolean isActive = className != null && 
                                            (className.contains("active") || className.contains("selected") || 
                                             className.contains("ds-toggle-button--active") ||
                                             className.contains("ds-toggle-button-active"));
                                        
                                        if (!isActive) {
                                            thinkingToggle.click();
                                            page.waitForTimeout(800); // 等待开关切换
                                            log.info("已通过 Locator 启用深度思考模式");
                                            thinkingEnabled = true;
                                        } else {
                                            log.info("深度思考模式已启用（通过 Locator 检查）");
                                            thinkingEnabled = true;
                                        }
                                    } catch (Exception e) {
                                        // 如果检查失败，直接点击
                                        thinkingToggle.click();
                                        page.waitForTimeout(800);
                                        log.info("已通过 Locator 点击深度思考按钮");
                                        thinkingEnabled = true;
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Locator 方法失败 (重试 {}): {}", retry + 1, e.getMessage());
                            }
                            
                            // 如果 Locator 失败，立即尝试 JavaScript 方法
                            if (!thinkingEnabled) {
                        
                                // 方法2: 使用 JavaScript 查找并点击
                                try {
                                String result = (String) page.evaluate("""
                                    () => {
                                        // 查找包含"深度思考"或"Thinking"文本的按钮
                                        // 包括 button 元素和 div[role="button"] 元素
                                        const buttons = document.querySelectorAll('button, div[role="button"]');
                                        let foundButton = null;
                                        let buttonText = '';
                                        
                                        for (const btn of buttons) {
                                            const text = (btn.textContent || '').trim();
                                            const ariaLabel = (btn.getAttribute('aria-label') || '').trim();
                                            
                                            // 检查文本或 aria-label
                                            if (text.includes('深度思考') || text.includes('Thinking') ||
                                                ariaLabel.includes('思考') || ariaLabel.includes('Think')) {
                                                foundButton = btn;
                                                buttonText = text || ariaLabel;
                                                
                                                // 检查是否是 toggle button 且未激活
                                                // 对于 div[role="button"]，检查类名和 aria-pressed
                                                const isActive = btn.classList.contains('active') ||
                                                               btn.classList.contains('selected') ||
                                                               btn.classList.contains('ds-toggle-button--active') ||
                                                               btn.classList.contains('ds-toggle-button-active') ||
                                                               btn.getAttribute('aria-pressed') === 'true';
                                                
                                                if (!isActive) {
                                                    btn.click();
                                                    return 'clicked: ' + buttonText;
                                                } else {
                                                    return 'already-active: ' + buttonText;
                                                }
                                            }
                                        }
                                        
                                        // 如果没找到，尝试查找所有可能的按钮并返回信息用于调试
                                        const allButtons = Array.from(buttons).map(btn => ({
                                            text: (btn.textContent || '').trim(),
                                            ariaLabel: (btn.getAttribute('aria-label') || '').trim(),
                                            classes: btn.className,
                                            role: btn.getAttribute('role') || 'button'
                                        })).filter(btn => btn.text.length > 0 || btn.ariaLabel.length > 0);
                                        
                                        return 'not-found. total-buttons: ' + allButtons.length;
                                    }
                                """);
                                
                                if (result != null) {
                                    if (result.startsWith("clicked:")) {
                                        page.waitForTimeout(800);
                                        log.info("通过 JavaScript 已启用深度思考模式: {}", result);
                                        thinkingEnabled = true;
                                    } else if (result.startsWith("already-active:")) {
                                        log.info("深度思考模式已启用（通过 JavaScript 检查）: {}", result);
                                        thinkingEnabled = true;
                                    } else {
                                        if (retry == maxRetries - 1) {
                                            log.warn("未找到深度思考按钮: {}", result);
                                        }
                                    }
                                }
                                } catch (Exception e) {
                                    if (retry == maxRetries - 1) {
                                        log.warn("JavaScript 方法失败: {}", e.getMessage());
                                    }
                                }
                            }
                        }
                        
                        if (!thinkingEnabled) {
                            log.warn("无法启用深度思考模式，可能按钮尚未加载或页面结构已变化");
                        }
                    } catch (Exception e) {
                        log.warn("启用深度思考模式时出错，继续执行: {}", e.getMessage(), e);
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

                // 5.1 处理联网搜索开关（仅在 DeepSeek 页面存在对应按钮时生效）
                try {
                    if (request.isWebSearch()) {
                        log.info("尝试启用联网搜索模式");
                        // 尝试多种方式查找“联网搜索”按钮
                        Locator webSearchToggle = page.locator("button:has-text('联网搜索')")
                                .or(page.locator("button:has-text('联网')"))
                                .or(page.locator("button[aria-label*='联网'], button[aria-label*='搜索'], button[aria-label*='Search']"))
                                .first();

                        if (webSearchToggle.count() > 0) {
                            try {
                                String className = webSearchToggle.getAttribute("class");
                                boolean isActive = className != null &&
                                        (className.contains("active") || className.contains("selected") ||
                                                className.contains("ds-toggle-button--active"));

                                if (!isActive) {
                                    webSearchToggle.click();
                                    page.waitForTimeout(500);
                                    log.info("已启用联网搜索模式");
                                } else {
                                    log.info("联网搜索模式已启用");
                                }
                            } catch (Exception e) {
                                // 检查失败则直接点击
                                webSearchToggle.click();
                                page.waitForTimeout(500);
                                log.info("已点击联网搜索按钮（未检查状态）");
                            }
                        } else {
                            // 如果按钮选择器失效，尝试用 JS 兜底（根据按钮文本模糊查找）
                            try {
                                Boolean result = (Boolean) page.evaluate("""
                                    () => {
                                        const buttons = document.querySelectorAll('button');
                                        for (const btn of buttons) {
                                            const text = (btn.textContent || '').trim();
                                            if (text.includes('联网搜索') || text.includes('联网') || text.includes('Web Search')) {
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
                                    log.info("通过 JavaScript 已启用联网搜索模式");
                                } else {
                                    log.warn("未找到联网搜索按钮或已启用");
                                }
                            } catch (Exception e) {
                                log.warn("无法启用联网搜索模式: {}", e.getMessage());
                            }
                        }
                    } else {
                        // 未开启联网搜索时，尽量保证按钮处于关闭状态
                        try {
                            Locator webSearchToggle = page.locator("button:has-text('联网搜索')")
                                    .or(page.locator("button:has-text('联网')"))
                                    .first();

                            if (webSearchToggle.count() > 0) {
                                try {
                                    String className = webSearchToggle.getAttribute("class");
                                    boolean isActive = className != null &&
                                            (className.contains("active") || className.contains("selected") ||
                                                    className.contains("ds-toggle-button--active"));

                                    if (isActive) {
                                        webSearchToggle.click();
                                        page.waitForTimeout(300);
                                        log.info("已关闭联网搜索模式");
                                    }
                                } catch (Exception e) {
                                    // 检查失败则尝试用 JS 关闭
                                    page.evaluate("""
                                        () => {
                                            const buttons = document.querySelectorAll('button');
                                            for (const btn of buttons) {
                                                const text = (btn.textContent || '').trim();
                                                if (text.includes('联网搜索') || text.includes('联网') || text.includes('Web Search')) {
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
                            // 忽略关闭失败的异常，避免影响主流程
                            log.debug("关闭联网搜索模式时出错: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("处理联网搜索模式时出错，继续执行: {}", e.getMessage());
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

                if (isNewConversation) {
                    log.info("新对话，发送消息: {} (深度思考: {})", messageToSend, shouldEnableThinking);
                } else {
                    log.info("继续对话，只发送最后一条用户消息: {} (页面已保存历史上下文, 深度思考: {})",
                            messageToSend, shouldEnableThinking);
                }

                // 8. 记录发送前的消息数量（用于只获取新增的回复）
                Locator responseLocatorsBefore = page.locator(".ds-markdown");
                int messageCountBefore = responseLocatorsBefore.count();
                log.info("发送前消息数量: {}", messageCountBefore);

                // 7. 验证 SSE 拦截器是否已设置
                try {
                    Object interceptorStatus = page.evaluate("() => window." + SSE_CONFIG.getInterceptorVarName() + " || false");
                    log.info("SSE 拦截器状态: {}", interceptorStatus);

                    // 如果未设置，重新设置
                    if (!Boolean.TRUE.equals(interceptorStatus)) {
                        log.warn("SSE 拦截器未设置，重新设置...");
                        setupSseInterceptor(page, SSE_CONFIG);
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
                String initialSseData = getSseDataFromPage(page, SSE_CONFIG.getDataVarName());
                if (initialSseData != null && !initialSseData.isEmpty()) {
                    log.info("检测到初始 SSE 数据，长度: {}", initialSseData.length());
                }

                // 11. 根据配置选择监听方式
                if ("sse".equalsIgnoreCase(monitorMode)) {
                    log.info("开始监听 AI 回复（SSE 拦截数据实时流模式）...");
                    monitorResponseSse(page, emitter, request, messageCountBefore, shouldEnableThinking);
                } else {
                    log.info("开始监听 AI 回复（DOM 实时 + SSE 修正模式）...");
                    monitorResponseHybrid(page, emitter, request, messageCountBefore, shouldEnableThinking);
                }

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
                    if (json.has("v") && json.get("v").isString()) {
                        String content = json.get("v").asString();
                        if (content != null && !content.isEmpty()) {
                            text.append(content);
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
        }
        
        return !text.isEmpty() ? text.toString() : null;
    }

    /**
     * SSE 数据解析结果
     */
    private static class SseParseResult {
        String thinkingContent;  // 思考内容增量
        String responseContent;  // 最终回复增量
        boolean finished;         // 是否完成
        
        SseParseResult(String thinkingContent, String responseContent, boolean finished) {
            this.thinkingContent = thinkingContent;
            this.responseContent = responseContent;
            this.finished = finished;
        }
    }

    /**
     * 增量解析 SSE 数据，区分思考内容和最终回复
     * 根据实际 DeepSeek SSE 数据格式解析：
     * - fragment 创建：{"v": [{"id": 1, "type": "THINK", ...}], "p": "fragments", "o": "APPEND"}
     * - 内容更新：{"v": "xxx", "p": "response/fragments/0/content", "o": "APPEND"}
     * - 简单格式：{"v": "xxx"} (需要根据上下文判断)
     * 
     * @param sseData 新的 SSE 数据（增量）
     * @param fragmentTypeMap fragment 数组索引到类型的映射（用于根据 path 判断类型）
     * @param lastActiveFragmentIndex 上次活跃的 fragment 索引（用于判断简单格式的类型）
     * @return 解析结果，包含思考内容和回复内容的增量，以及当前活跃的 fragment 索引
     */
    private static class ParseResultWithIndex {
        SseParseResult result;
        Integer lastActiveFragmentIndex;
        
        ParseResultWithIndex(SseParseResult result, Integer lastActiveFragmentIndex) {
            this.result = result;
            this.lastActiveFragmentIndex = lastActiveFragmentIndex;
        }
    }
    
    private ParseResultWithIndex parseSseIncremental(String sseData, java.util.Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
        if (sseData == null || sseData.isEmpty()) {
            return new ParseResultWithIndex(new SseParseResult(null, null, false), lastActiveFragmentIndex);
        }
        
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder responseText = new StringBuilder();
        boolean finished = false;
        String currentEvent = null;
        Integer currentActiveIndex = lastActiveFragmentIndex;
        
        String[] lines = sseData.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            // 检查完成标记
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7).trim();
                if ("finish".equals(currentEvent) || "close".equals(currentEvent)) {
                    finished = true;
                }
                continue;
            }
            
            if (line.startsWith("data: ")) {
                String jsonStr = line.substring(6).trim();
                if (jsonStr.isEmpty() || jsonStr.equals("{}")) {
                    continue;
                }
                
                try {
                    JsonNode json = objectMapper.readTree(jsonStr);
                    
                    // 提取 path 和 operation
                    String path = null;
                    String operation = null;
                    if (json.has("p") && json.get("p").isString()) {
                        path = json.get("p").asString();
                    }
                    if (json.has("o") && json.get("o").isString()) {
                        operation = json.get("o").asString();
                    }
                    
                    boolean isThinking = false;
                    String content = null;
                    
                    // 情况1: fragment 创建 - {"v": [{"id": 1, "type": "THINK", ...}], "p": "fragments", "o": "APPEND"}
                    // 或者 {"v": [{"id": 2, "type": "RESPONSE", ...}], "p": "response/fragments", "o": "APPEND"}
                    // fragment 按创建顺序分配数组索引（第一个是 0，第二个是 1，以此类推）
                    if (("fragments".equals(path) || "response/fragments".equals(path)) && "APPEND".equals(operation) && json.has("v") && json.get("v").isArray()) {
                        JsonNode fragments = json.get("v");
                        // 找到当前最大的索引，新 fragment 从下一个索引开始
                        int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                                       fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                        
                        for (JsonNode fragment : fragments) {
                            if (fragment.has("type") && fragment.get("type").isString()) {
                                String type = fragment.get("type").asString();
                                // 使用数组索引（不是 fragment id）作为 key
                                fragmentTypeMap.put(nextIndex, type);
                                
                                // 如果 fragment 创建时就有初始内容，必须提取并返回
                                if (fragment.has("content") && fragment.get("content").isString()) {
                                    String fragmentContent = fragment.get("content").asString();
                                    if (!fragmentContent.isEmpty()) {
                                        if ("THINK".equals(type)) {
                                            thinkingText.append(fragmentContent);
                                            log.info("从 fragment 创建提取思考内容 (索引 {}): {}", nextIndex, fragmentContent);
                                        } else if ("RESPONSE".equals(type)) {
                                            responseText.append(fragmentContent);
                                            log.info("从 fragment 创建提取回复内容 (索引 {}): {}", nextIndex, fragmentContent);
                                        }
                                    } else {
                                        log.warn("Fragment 创建时 content 为空，类型: {}, 索引: {}", type, nextIndex);
                                    }
                                } else {
                                    log.debug("Fragment 创建时没有 content 字段，类型: {}, 索引: {}", type, nextIndex);
                                }
                                
                                nextIndex++;
                            }
                        }
                        // 注意：这里不 continue，让后续逻辑也能处理，但实际上 fragment 创建后应该继续处理其他数据
                        // 但为了确保初始内容被返回，我们需要继续处理
                        continue;
                    }
                    
                    // 情况2: 内容更新 - {"v": "xxx", "p": "response/fragments/0/content", "o": "APPEND"}
                    if (path != null && path.startsWith("response/fragments/") && path.endsWith("/content")) {
                        // 提取 fragment 索引：response/fragments/0/content -> 0
                        try {
                            String[] pathParts = path.split("/");
                            if (pathParts.length >= 3) {
                                int fragmentIndex = Integer.parseInt(pathParts[2]);
                                currentActiveIndex = fragmentIndex; // 更新当前活跃的 fragment 索引
                                
                                String fragmentType = fragmentTypeMap.get(fragmentIndex);
                                
                                if (fragmentType != null) {
                                    if ("THINK".equals(fragmentType)) {
                                        isThinking = true;
                                    } else if ("RESPONSE".equals(fragmentType)) {
                                        isThinking = false;
                                    }
                                    log.debug("内容更新 - fragment 索引: {}, 类型: {}", fragmentIndex, fragmentType);
                                } else {
                                    // fragment 类型未知，根据索引推断（通常索引 0 是思考，索引 1 是回复）
                                    log.warn("Fragment 类型未知，索引: {}，尝试推断", fragmentIndex);
                                    if (fragmentIndex == 0) {
                                        isThinking = true; // 通常第一个 fragment 是思考
                                    } else {
                                        isThinking = false; // 其他是回复
                                    }
                                }
                            }
                        } catch (NumberFormatException e) {
                            log.debug("解析 fragment 索引失败: {}", path);
                        }
                        
                        // 提取内容
                        if (json.has("v")) {
                            JsonNode vNode = json.get("v");
                            if (vNode.isString()) {
                                content = vNode.asString();
                                log.debug("提取内容更新: {} (思考: {})", content, isThinking);
                            }
                        }
                    }
                    // 情况3: 简单格式 - {"v": "xxx"} (根据当前活跃的 fragment 类型判断)
                    // 从实际数据看，简单格式通常出现在 fragment 内容更新之后，是增量内容
                    // 我们根据最近更新的 fragment 索引来判断
                    else if (json.has("v") && !json.has("p")) {
                        JsonNode vNode = json.get("v");
                        if (vNode.isString()) {
                            content = vNode.asString();
                            
                            // 优先根据当前活跃的 fragment 索引判断
                            if (currentActiveIndex != null) {
                                String fragmentType = fragmentTypeMap.get(currentActiveIndex);
                                if (fragmentType != null) {
                                    isThinking = "THINK".equals(fragmentType);
                                    log.debug("简单格式 - 根据活跃索引 {} (类型: {}) 判断为: {}", 
                                            currentActiveIndex, fragmentType, isThinking ? "思考" : "回复");
                                } else {
                                    // 类型未知，根据索引推断
                                    isThinking = (currentActiveIndex == 0);
                                    log.debug("简单格式 - 根据活跃索引 {} (类型未知) 推断为: {}", 
                                            currentActiveIndex, isThinking ? "思考" : "回复");
                                }
                            } else {
                                // 没有活跃索引，根据 fragmentTypeMap 判断
                                boolean hasThink = fragmentTypeMap.containsValue("THINK");
                                boolean hasResponse = fragmentTypeMap.containsValue("RESPONSE");
                                
                                if (hasThink && !hasResponse) {
                                    // 只有思考 fragment，认为是思考内容
                                    isThinking = true;
                                } else if (hasResponse) {
                                    // 有回复 fragment，认为是回复内容
                                    isThinking = false;
                                } else if (hasThink) {
                                    // 有思考也有回复，根据最近更新的类型判断（简化：优先认为是回复）
                                    isThinking = false;
                                }
                                log.debug("简单格式 - 根据 fragmentTypeMap 判断为: {}", isThinking ? "思考" : "回复");
                            }
                        }
                    }
                    // 情况4: BATCH 操作 - {"v": [...], "p": "response", "o": "BATCH"}
                    // BATCH 操作可能包含 fragment 创建和内容更新
                    else if ("response".equals(path) && "BATCH".equals(operation) && json.has("v") && json.get("v").isArray()) {
                        JsonNode batchData = json.get("v");
                        // 找到当前最大的索引，新 fragment 从下一个索引开始
                        int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                                       fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                        
                        for (JsonNode item : batchData) {
                            if (item.has("p") && item.has("v")) {
                                String itemPath = item.get("p").asString();
                                String itemOperation = item.has("o") && item.get("o").isString() ? item.get("o").asString() : null;
                                
                                // 处理 fragment 创建（在 BATCH 中）
                                // 支持 "fragments" 和 "response/fragments" 两种路径
                                if (("fragments".equals(itemPath) || "response/fragments".equals(itemPath)) && "APPEND".equals(itemOperation) && item.get("v").isArray()) {
                                    JsonNode fragments = item.get("v");
                                    for (JsonNode fragment : fragments) {
                                        if (fragment.has("type") && fragment.get("type").isString()) {
                                            String type = fragment.get("type").asString();
                                            fragmentTypeMap.put(nextIndex, type);
                                            
                                            // 提取 fragment 创建时的初始内容
                                            if (fragment.has("content") && fragment.get("content").isString()) {
                                                String fragmentContent = fragment.get("content").asString();
                                                if (!fragmentContent.isEmpty()) {
                                                    if ("THINK".equals(type)) {
                                                        thinkingText.append(fragmentContent);
                                                        log.debug("从 BATCH 中 fragment 创建提取思考内容 (索引 {}): {}", nextIndex, fragmentContent);
                                                    } else if ("RESPONSE".equals(type)) {
                                                        responseText.append(fragmentContent);
                                                        log.debug("从 BATCH 中 fragment 创建提取回复内容 (索引 {}): {}", nextIndex, fragmentContent);
                                                    }
                                                }
                                            }
                                            
                                            nextIndex++;
                                        }
                                    }
                                }
                                // 处理内容更新（在 BATCH 中）
                                else if (itemPath.startsWith("response/fragments/") && itemPath.endsWith("/content")) {
                                    try {
                                        String[] pathParts = itemPath.split("/");
                                        if (pathParts.length >= 3) {
                                            int fragmentIndex = Integer.parseInt(pathParts[2]);
                                            currentActiveIndex = fragmentIndex; // 更新当前活跃的 fragment 索引
                                            
                                            String fragmentType = fragmentTypeMap.get(fragmentIndex);
                                            
                                            JsonNode itemValue = item.get("v");
                                            String itemContent = null;
                                            if (itemValue.isString()) {
                                                itemContent = itemValue.asString();
                                            }
                                            
                                            if (itemContent != null && !itemContent.isEmpty()) {
                                                if ("THINK".equals(fragmentType)) {
                                                    thinkingText.append(itemContent);
                                                } else if ("RESPONSE".equals(fragmentType)) {
                                                    responseText.append(itemContent);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.debug("解析 BATCH 中的内容更新失败: {}", e.getMessage());
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    
                    // 输出内容
                    if (content != null && !content.isEmpty()) {
                        if (isThinking) {
                            thinkingText.append(content);
                        } else {
                            responseText.append(content);
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析 SSE JSON 时出错: {}", e.getMessage());
                }
            }
        }
        
        String thinking = thinkingText.length() > 0 ? thinkingText.toString() : null;
        String response = responseText.length() > 0 ? responseText.toString() : null;
        
        // 记录解析结果，确保初始内容被包含
        if (thinking != null && !thinking.isEmpty()) {
            log.debug("解析结果 - 思考内容长度: {}, 内容: {}", thinking.length(),
                     thinking.length() > 50 ? thinking.substring(0, 50) + "..." : thinking);
        }
        if (response != null && !response.isEmpty()) {
            log.debug("解析结果 - 回复内容长度: {}, 内容: {}", response.length(),
                     response.length() > 50 ? response.substring(0, 50) + "..." : response);
        }
        
        SseParseResult result = new SseParseResult(thinking, response, finished);
        return new ParseResultWithIndex(result, currentActiveIndex);
    }

    /**
     * 基于 SSE 拦截数据的实时流式监听
     * 直接从网络拦截的 SSE 数据中提取内容，实时流式输出
     * 根据实际 DeepSeek SSE 数据格式解析思考内容和回复内容
     */
    private void monitorResponseSse(Page page, SseEmitter emitter, ChatCompletionRequest request,
                                   int messageCountBefore, boolean enableThinking)
            throws IOException, InterruptedException {
        String id = UUID.randomUUID().toString();
        StringBuilder collectedThinkingText = new StringBuilder();
        StringBuilder collectedResponseText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        boolean finished = false;
        int noDataCount = 0;
        
        // 维护 fragment 索引到类型的映射（用于根据 path 判断是思考还是回复）
        java.util.Map<Integer, String> fragmentTypeMap = new java.util.concurrent.ConcurrentHashMap<>();
        // 维护当前活跃的 fragment 索引（用于判断简单格式的类型）
        Integer lastActiveFragmentIndex = null;

        log.info("开始 SSE 实时流式监听（思考模式: {}）", enableThinking);

        while (!finished) {
            try {
                // 检查页面状态
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                // 从 SSE 数据获取内容
                String sseData = getSseDataFromPage(page, SSE_CONFIG.getDataVarName());
                
                if (sseData != null && !sseData.isEmpty()) {
                    // 有新的数据
                    noDataCount = 0;
                    
                    // 解析新增的 SSE 数据（传入 fragmentTypeMap 和 lastActiveFragmentIndex）
                    ParseResultWithIndex parseResult = parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
                    SseParseResult result = parseResult.result;
                    lastActiveFragmentIndex = parseResult.lastActiveFragmentIndex; // 更新活跃索引
                    
                    // 发送思考内容增量（从 SSE 数据中提取）
                    if (enableThinking && result.thinkingContent != null && 
                        !result.thinkingContent.isEmpty()) {
                        collectedThinkingText.append(result.thinkingContent);
                        log.debug("从 SSE 发送思考内容增量，长度: {}, 内容: {}",
                                result.thinkingContent.length(),
                                result.thinkingContent.length() > 20 ? 
                                result.thinkingContent.substring(0, 20) + "..." : result.thinkingContent);
                        sendThinkingContent(emitter, id, result.thinkingContent, request.getModel());
                    }
                    
                    // 发送回复内容增量
                    if (result.responseContent != null && !result.responseContent.isEmpty()) {
                        collectedResponseText.append(result.responseContent);
                        log.debug("从 SSE 发送回复内容增量，长度: {}, 内容: {}",
                                result.responseContent.length(),
                                result.responseContent.length() > 20 ? 
                                result.responseContent.substring(0, 20) + "..." : result.responseContent);
                        sendSseChunk(emitter, id, result.responseContent, request.getModel());
                    }
                    
                    // 检查是否完成
                    if (result.finished) {
                        finished = true;
                        log.info("SSE 流已完成");
                    }
                } else {
                    // 没有新数据
                    noDataCount++;
                    
                    // 如果长时间没有新数据，检查是否完成
                    if (noDataCount > 50) { // 5秒没有新数据
                        // 尝试从页面获取完整的 SSE 数据检查完成标记
                        try {
                            Object fullData = page.evaluate("""
                                () => {
                                    if (window.__deepseekSseData && window.__deepseekSseData.length > 0) {
                                        return window.__deepseekSseData.join('\\n');
                                    }
                                    return null;
                                }
                            """);
                            
                            if (fullData != null) {
                                String fullSseData = fullData.toString();
                                if (fullSseData.contains("event: finish") || 
                                    fullSseData.contains("event: close")) {
                                    finished = true;
                                    log.info("检测到完成标记，结束监听");
                                }
                            }
                        } catch (Exception e) {
                            log.debug("检查完成标记时出错: {}", e.getMessage());
                        }
                        
                        if (!finished && noDataCount > 200) { // 20秒没有新数据，超时
                            log.warn("长时间没有新数据，结束监听");
                            finished = true;
                        }
                    }
                }

                // 超时检查
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
                    Thread.sleep(500);
                    continue;
                }

                log.error("SSE 监听时出错", e);
                if (!collectedResponseText.isEmpty()) {
                    log.info("已收集部分内容，长度: {}，结束监听", collectedResponseText.length());
                    break;
                }
                throw e;
            }
        }

        // 发送对话 URL
        try {
            if (!page.isClosed()) {
                String currentUrl = page.url();
                if (currentUrl.contains("chat.deepseek.com")) {
                    String model = request.getModel();
                    pageUrls.put(model, currentUrl);
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
     * 混合方式监听回复：DOM 实时流式 + SSE 最终修正
     * 支持区分思考内容和最终回复（深度思考模式）
     */
    private void monitorResponseHybrid(Page page, SseEmitter emitter, ChatCompletionRequest request,
                                      int messageCountBefore, boolean enableThinking)
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
                    // 注意：只有当当前请求显式开启了深度思考时才会推送思考内容，
                    // 避免上一轮开启深度思考后，本轮已关闭但仍重复输出旧思考内容。
                    if (enableThinking) {
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
                                } else if (finalText.equals(lastFullText) && !finalText.isEmpty()) {
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
                        String sseData = getSseDataFromPage(page, SSE_CONFIG.getDataVarName());
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
                if (!collectedText.isEmpty()) {
                    log.info("已收集部分内容，长度: {}，结束监听", collectedText.length());
                    break;
                }
                // 如果还没有收集到内容，抛出异常
                throw e;
            }
        }

        // 在发送完成标记之前，发送当前页面的 URL 给前端
        try {
            if (!page.isClosed()) {
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


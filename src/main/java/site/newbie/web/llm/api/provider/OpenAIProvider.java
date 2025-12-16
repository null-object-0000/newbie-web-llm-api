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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class OpenAIProvider extends BaseProvider {

    private final BrowserManager browserManager;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // 保存每个模型的页面引用（key: model, value: page）
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    // 记录每个页面的 URL（key: model, value: url）
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
    /**
     * 监听模式：true 使用 SSE 拦截数据实时流，false 使用 DOM 解析
     * 可通过 application.properties 中的 openai.monitor.mode 配置来切换
     * 可选值：sse（SSE 实时流模式）或 dom（DOM 解析模式，默认）
     */
    @Value("${openai.monitor.mode:dom}")
    private String monitorMode;
    
    // SSE 拦截器配置
    private static final SseInterceptorConfig SSE_CONFIG = new SseInterceptorConfig(
            "__openaiSseData",
            "__openaiSseInterceptorSet",
            new String[]{"/api/conversation", "/backend-api"},
            false  // OpenAI 不需要拦截 XMLHttpRequest
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

    public OpenAIProvider(BrowserManager browserManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.browserManager = browserManager;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of("gpt-web-chat", "gpt-web-reasoner");
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
                boolean shouldEnableThinking = "gpt-web-reasoner".equals(model);
                if (shouldEnableThinking) {
                    log.info("检测到 gpt-web-reasoner 模型，自动启用深度思考模式");
                } else {
                    log.info("检测到 {} 模型，自动禁用深度思考模式", model);
                }

                // 1. 获取或创建页面
                String conversationUrl = request.getConversationUrl();
                
                // 如果请求中没有传递 conversationUrl，尝试从历史对话中提取
                if ((conversationUrl == null || conversationUrl.isEmpty()) && request.getMessages() != null && !request.getMessages().isEmpty()) {
                    // 尝试提取 chatgpt.com 或 chat.openai.com 的 URL
                    String extractedUrl = extractUrlFromHistory(request, "chatgpt.com");
                    if (extractedUrl == null || extractedUrl.isEmpty()) {
                        extractedUrl = extractUrlFromHistory(request, "chat.openai.com");
                    }
                    if (extractedUrl != null && !extractedUrl.isEmpty()) {
                        conversationUrl = extractedUrl;
                        log.info("从历史对话中提取到 URL，将用于复用对话: {}", conversationUrl);
                    }
                }
                
                // 判断是否为新对话（支持 chatgpt.com 和 chat.openai.com）
                boolean isNewConversation = (conversationUrl == null || conversationUrl.isEmpty() || 
                        (!conversationUrl.contains("chatgpt.com") && !conversationUrl.contains("chat.openai.com")));
                
                if (!isNewConversation) {
                    // 有对话 URL：检查当前打开的 tab 中是否已经打开过此链接
                    log.info("检测到对话 URL，尝试复用: {}", conversationUrl);
                    
                    page = findPageByUrl(conversationUrl);
                    
                    if (page != null && !page.isClosed()) {
                        // 找到已打开的页面，直接复用
                        String currentUrl = page.url();
                        if (currentUrl.equals(conversationUrl)) {
                            log.info("复用已打开的页面，URL: {}", currentUrl);
                            if (!modelPages.containsValue(page)) {
                                modelPages.put(model, page);
                                pageUrls.put(model, conversationUrl);
                            }
                        } else {
                            // URL 不匹配，导航到指定 URL
                            log.info("页面 URL 不匹配，导航到指定 URL。当前: {}, 目标: {}", currentUrl, conversationUrl);
                            page.navigate(conversationUrl);
                            page.waitForLoadState();
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

                    // 导航到 ChatGPT（使用 chatgpt.com，因为 chat.openai.com 会重定向到这里）
                    page.navigate("https://chatgpt.com/");
                    page.waitForLoadState();
                    
                    // 记录页面 URL
                    String initialUrl = page.url();
                    pageUrls.put(model, initialUrl);
                    log.info("创建新对话页面，模型: {}, URL: {}", model, initialUrl);
                }

                // 在页面创建后设置 SSE 拦截器
                setupSseInterceptor(page, SSE_CONFIG);

                // 如果是新对话，可能需要点击"新聊天"按钮
                if (isNewConversation) {
                    try {
                        page.waitForTimeout(1000); // 等待页面加载

                        // 直接使用 JavaScript 点击，避免元素拦截问题
                        // 根据实际 DOM 结构，按钮是 <a data-testid="create-new-chat-button" href="/">，文本是"新聊天"
                        Boolean jsResult = (Boolean) page.evaluate("""
                            () => {
                                // 优先使用 data-testid 查找
                                let button = document.querySelector('a[data-testid="create-new-chat-button"]');
                                
                                if (!button) {
                                    // 备选：通过文本内容查找
                                    const allLinks = document.querySelectorAll('a[href="/"]');
                                    for (const link of allLinks) {
                                        const text = link.textContent || '';
                                        if (text.includes('新聊天') || text.includes('New chat') || text.includes('新对话')) {
                                            button = link;
                                            break;
                                        }
                                    }
                                }
                                
                                if (button) {
                                    // 先尝试直接点击（如果元素可见且可点击）
                                    try {
                                        button.click();
                                        return true;
                                    } catch (e) {
                                        // 如果直接点击失败，使用 dispatchEvent
                                        const clickEvent = new MouseEvent('click', {
                                            bubbles: true,
                                            cancelable: true,
                                            view: window
                                        });
                                        button.dispatchEvent(clickEvent);
                                        
                                        // 也触发 navigation（因为这是 <a> 标签）
                                        if (button.href) {
                                            // 如果 dispatchEvent 没有触发导航，尝试直接导航
                                            setTimeout(() => {
                                                if (window.location.pathname !== button.getAttribute('href')) {
                                                    window.location.href = button.href;
                                                }
                                            }, 100);
                                        }
                                        return true;
                                    }
                                }
                                return false;
                            }
                        """);
                        
                        if (Boolean.TRUE.equals(jsResult)) {
                            page.waitForTimeout(500); // 等待新对话界面加载
                            log.info("已通过 JavaScript 点击新聊天按钮");
                        } else {
                            // JavaScript 方法失败，尝试使用 Locator（作为备选）
                            log.debug("JavaScript 点击失败，尝试使用 Locator 方法");
                            try {
                                Locator newChatButton = page.locator("a[data-testid='create-new-chat-button']")
                                        .or(page.locator("a:has-text('新聊天')"))
                                        .or(page.locator("a:has-text('New chat')"))
                                        .or(page.locator("a[href='/']"))
                                        .first();

                                if (newChatButton.count() > 0) {
                                    // 使用 force 选项强制点击（如果元素被遮挡）
                                    try {
                                        newChatButton.click();
                                        page.waitForTimeout(500);
                                        log.info("已通过 Locator 点击新聊天按钮");
                                    } catch (Exception clickEx) {
                                        log.warn("Locator 点击也失败: {}", clickEx.getMessage());
                                    }
                                } else {
                                    log.warn("未找到新聊天按钮，将使用当前对话");
                                }
                            } catch (Exception e) {
                                log.warn("使用 Locator 查找新聊天按钮时出错: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("点击新聊天按钮时出错，继续使用当前对话: {}", e.getMessage());
                    }
                }

                // 处理深度思考模式开关（根据模型名称自动判断）
                // OpenAI 的深度思考模式需要先点击加号按钮打开菜单，然后选择"思考"选项
                if (shouldEnableThinking) {
                    try {
                        log.info("尝试启用深度思考模式");
                        // 等待页面完全加载，确保按钮已渲染
                        try {
                            page.locator("div.ProseMirror[id='prompt-textarea']").waitFor();
                        } catch (Exception e) {
                            log.debug("等待输入框超时: {}", e.getMessage());
                        }
                        page.waitForTimeout(1500); // 额外等待，确保所有按钮都已渲染
                        
                        // 首先检查是否已经激活了思考模式
                        boolean thinkingEnabled = false;
                        try {
                            // 检查 footer 区域是否有思考按钮（已激活状态）
                            Locator thinkingPill = page.locator("button.__composer-pill[aria-label*='思考']")
                                    .or(page.locator("div[data-testid='composer-footer-actions'] button:has-text('思考')"))
                                    .or(page.locator("button[aria-label*='思考，点击以重试']"));
                            
                            if (thinkingPill.count() > 0) {
                                log.info("深度思考模式已启用（通过检查 footer 区域）");
                                thinkingEnabled = true;
                            }
                        } catch (Exception e) {
                            log.debug("检查思考模式状态时出错: {}", e.getMessage());
                        }
                        
                        // 如果未激活，则执行激活流程
                        if (!thinkingEnabled) {
                            int maxRetries = 3;
                            for (int retry = 0; retry < maxRetries && !thinkingEnabled; retry++) {
                                if (retry > 0) {
                                    log.info("重试启用深度思考模式 (第 {} 次)", retry + 1);
                                    page.waitForTimeout(1000); // 重试前等待
                                }
                                
                                try {
                                    // 步骤1: 点击加号按钮打开菜单
                                    Locator plusButton = page.locator("button[data-testid='composer-plus-btn']")
                                            .or(page.locator("button.composer-btn[aria-label*='添加']"))
                                            .or(page.locator("button[aria-label*='添加文件等']"));
                                    
                                    if (plusButton.count() > 0) {
                                        log.info("找到加号按钮，准备点击");
                                        plusButton.click();
                                        page.waitForTimeout(500); // 等待菜单打开
                                        
                                        // 步骤2: 在菜单中选择"思考"选项
                                        Locator thinkingMenuItem = page.locator("div[role='menuitemradio']:has-text('思考')")
                                                .or(page.locator("div[role='menuitemradio'] .truncate:has-text('思考')"))
                                                .or(page.locator("div.__menu-item:has-text('思考')"));
                                        
                                        if (thinkingMenuItem.count() > 0) {
                                            log.info("找到思考菜单项，准备点击");
                                            thinkingMenuItem.click();
                                            page.waitForTimeout(800); // 等待菜单关闭和状态更新
                                            
                                            // 步骤3: 验证是否成功激活
                                            try {
                                                Locator thinkingPill = page.locator("button.__composer-pill[aria-label*='思考']")
                                                        .or(page.locator("div[data-testid='composer-footer-actions'] button:has-text('思考')"));
                                                
                                                if (thinkingPill.count() > 0) {
                                                    log.info("已成功启用深度思考模式");
                                                    thinkingEnabled = true;
                                                } else {
                                                    log.warn("点击思考菜单项后，未在 footer 区域找到思考按钮");
                                                }
                                            } catch (Exception e) {
                                                log.debug("验证思考模式状态时出错: {}", e.getMessage());
                                                // 即使验证失败，也认为可能已启用
                                                thinkingEnabled = true;
                                            }
                                        } else {
                                            log.warn("未找到思考菜单项");
                                        }
                                    } else {
                                        log.warn("未找到加号按钮");
                                    }
                                } catch (Exception e) {
                                    log.debug("启用深度思考模式时出错 (重试 {}): {}", retry + 1, e.getMessage());
                                }
                                
                                // 如果 Locator 方法失败，尝试 JavaScript 方法
                                if (!thinkingEnabled) {
                                    try {
                                        // 步骤1: 检查是否已经激活
                                        String checkResult = (String) page.evaluate("""
                                            () => {
                                                const footerActions = document.querySelector('[data-testid="composer-footer-actions"]');
                                                if (footerActions) {
                                                    const thinkingPill = footerActions.querySelector('button[aria-label*="思考"]');
                                                    if (thinkingPill) {
                                                        return 'already-active';
                                                    }
                                                }
                                                return 'not-active';
                                            }
                                        """);
                                        
                                        if ("already-active".equals(checkResult)) {
                                            log.info("深度思考模式已启用（通过 JavaScript 检查）");
                                            thinkingEnabled = true;
                                            continue;
                                        }
                                        
                                        // 步骤2: 点击加号按钮
                                        String plusResult = (String) page.evaluate("""
                                            () => {
                                                const plusBtn = document.querySelector('button[data-testid="composer-plus-btn"]') ||
                                                               document.querySelector('button.composer-btn[aria-label*="添加"]');
                                                
                                                if (!plusBtn) {
                                                    return 'plus-button-not-found';
                                                }
                                                
                                                plusBtn.click();
                                                return 'clicked';
                                            }
                                        """);
                                        
                                        if (!"clicked".equals(plusResult)) {
                                            if (retry == maxRetries - 1) {
                                                log.warn("未找到加号按钮: {}", plusResult);
                                            }
                                            continue;
                                        }
                                        
                                        // 等待菜单打开
                                        page.waitForTimeout(500);
                                        
                                        // 步骤3: 点击思考菜单项
                                        String menuResult = (String) page.evaluate("""
                                            () => {
                                                const menuItems = document.querySelectorAll('div[role="menuitemradio"]');
                                                for (const item of menuItems) {
                                                    const text = item.textContent || '';
                                                    if (text.includes('思考') && !text.includes('深度研究') && !text.includes('智能购物')) {
                                                        item.click();
                                                        return 'clicked';
                                                    }
                                                }
                                                return 'thinking-menu-item-not-found';
                                            }
                                        """);
                                        
                                        if (!"clicked".equals(menuResult)) {
                                            if (retry == maxRetries - 1) {
                                                log.warn("未找到思考菜单项: {}", menuResult);
                                            }
                                            continue;
                                        }
                                        
                                        // 等待状态更新
                                        page.waitForTimeout(800);
                                        
                                        // 步骤4: 验证是否激活
                                        String verifyResult = (String) page.evaluate("""
                                            () => {
                                                const footerActions = document.querySelector('[data-testid="composer-footer-actions"]');
                                                if (footerActions) {
                                                    const thinkingPill = footerActions.querySelector('button[aria-label*="思考"]');
                                                    if (thinkingPill) {
                                                        return 'enabled';
                                                    }
                                                }
                                                return 'not-verified';
                                            }
                                        """);
                                        
                                        if ("enabled".equals(verifyResult)) {
                                            log.info("通过 JavaScript 已启用深度思考模式");
                                            thinkingEnabled = true;
                                        } else {
                                            log.info("已点击思考菜单项，但无法验证状态，假设已启用");
                                            thinkingEnabled = true; // 假设已启用
                                        }
                                    } catch (Exception e) {
                                        if (retry == maxRetries - 1) {
                                            log.warn("JavaScript 方法失败: {}", e.getMessage());
                                        }
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
                        // 查找 footer 区域的思考按钮，如果已激活则点击关闭
                        Locator thinkingPill = page.locator("button.__composer-pill[aria-label*='思考']")
                                .or(page.locator("div[data-testid='composer-footer-actions'] button:has-text('思考')"));
                        
                        if (thinkingPill.count() > 0) {
                            // 点击思考按钮上的关闭图标（X 按钮）
                            Locator removeButton = thinkingPill.locator("div.__composer-pill-remove")
                                    .or(thinkingPill.locator("svg[viewBox='0 0 16 16']").locator(".."));
                            
                            if (removeButton.count() > 0) {
                                removeButton.click();
                                page.waitForTimeout(300);
                                log.info("已关闭深度思考模式");
                            } else {
                                // 如果没有找到关闭按钮，直接点击思考按钮本身
                                thinkingPill.click();
                                page.waitForTimeout(300);
                                log.info("已点击思考按钮关闭深度思考模式");
                            }
                        }
                    } catch (Exception e) {
                        // 忽略错误
                        log.debug("关闭深度思考模式时出错: {}", e.getMessage());
                    }
                }

                // 定位输入框并填入内容
                // ChatGPT 的输入框是 contenteditable div，id 为 "prompt-textarea"
                // 注意：有一个隐藏的 textarea（class="wcDTda_fallbackTextarea"），我们需要选择可见的 contenteditable div
                // 等待页面加载完成
                page.waitForTimeout(1000);
                
                // 直接选择可见的 contenteditable div（真正的输入框）
                // 使用更精确的选择器，排除隐藏的 textarea
                Locator inputBox = page.locator("div.ProseMirror[id='prompt-textarea']");
                
                // 等待输入框可见（最多等待 30 秒）
                try {
                    inputBox.waitFor();
                    log.info("找到 ProseMirror contenteditable 输入框");
                } catch (Exception e) {
                    log.warn("ProseMirror 选择器等待超时，尝试备选选择器: {}", e.getMessage());
                    // 备选：使用 contenteditable 属性选择器
                    inputBox = page.locator("div[contenteditable='true'][id='prompt-textarea']");
                    inputBox.waitFor();
                    log.info("找到 contenteditable 输入框（备用方法）");
                }

                // 处理消息：只发送最后一条用户消息
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

                // 记录发送前的消息数量（用于只获取新增的回复）
                Locator responseLocatorsBefore = page.locator("[data-message-author-role='assistant']")
                        .or(page.locator(".markdown"))
                        .or(page.locator("[class*='markdown']"));
                int messageCountBefore = responseLocatorsBefore.count();
                log.info("发送前消息数量: {}", messageCountBefore);

                // 验证 SSE 拦截器是否已设置
                try {
                    Object interceptorStatus = page.evaluate("() => window." + SSE_CONFIG.getInterceptorVarName() + " || false");
                    log.info("SSE 拦截器状态: {}", interceptorStatus);

                    if (!Boolean.TRUE.equals(interceptorStatus)) {
                        log.warn("SSE 拦截器未设置，重新设置...");
                        setupSseInterceptor(page, SSE_CONFIG);
                    }
                } catch (Exception e) {
                    log.warn("检查 SSE 拦截器状态失败: {}", e.getMessage());
                }

                // 填入消息并发送
                // 对于 contenteditable div，使用 JavaScript 设置内容是最可靠的方法
                try {
                    inputBox.click(); // 点击聚焦
                    page.waitForTimeout(200); // 等待聚焦完成
                    
                    // 使用 JavaScript 设置内容（适用于 contenteditable）
                    Boolean result = (Boolean) page.evaluate("""
                        (text) => {
                            const input = document.getElementById('prompt-textarea');
                            if (input) {
                                // 清空现有内容
                                input.innerHTML = '';
                                // 创建文本节点
                                const textNode = document.createTextNode(text);
                                input.appendChild(textNode);
                                // 触发 input 事件，确保 ChatGPT 检测到内容变化
                                const inputEvent = new Event('input', { bubbles: true });
                                input.dispatchEvent(inputEvent);
                                // 也触发 change 事件
                                const changeEvent = new Event('change', { bubbles: true });
                                input.dispatchEvent(changeEvent);
                                return true;
                            }
                            return false;
                        }
                    """, messageToSend);
                    
                    if (Boolean.TRUE.equals(result)) {
                        log.info("使用 JavaScript 设置输入框内容成功");
                    } else {
                        log.warn("JavaScript 设置内容返回 false，输入框可能未找到");
                    }
                } catch (Exception e) {
                    log.error("设置输入框内容失败: {}", e.getMessage(), e);
                    throw new RuntimeException("无法设置输入框内容", e);
                }
                
                // 等待内容设置完成
                page.waitForTimeout(300);
                
                // 发送消息（按 Enter）
                page.keyboard().press("Enter");

                // 等待一小段时间让请求发出
                page.waitForTimeout(500);

                // 检查是否有 SSE 数据
                String initialSseData = getSseDataFromPage(page, SSE_CONFIG.getDataVarName());
                if (initialSseData != null && !initialSseData.isEmpty()) {
                    log.info("检测到初始 SSE 数据，长度: {}", initialSseData.length());
                }

                // 根据配置选择监听方式
                if ("sse".equalsIgnoreCase(monitorMode)) {
                    log.info("开始监听 AI 回复（SSE 拦截数据实时流模式）...");
                    monitorResponseSse(page, emitter, request, messageCountBefore, shouldEnableThinking);
                } else {
                    log.info("开始监听 AI 回复（DOM 模式）...");
                    monitorResponseDom(page, emitter, request, messageCountBefore, shouldEnableThinking);
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
                // 只有在明确需要关闭时才关闭
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
     * 基于 SSE 拦截数据的实时流式监听
     * 支持深度思考模式，提取思考内容和最终回复
     */
    private void monitorResponseSse(Page page, SseEmitter emitter, ChatCompletionRequest request,
                                   int messageCountBefore, boolean enableThinking)
            throws IOException, InterruptedException {
        String id = UUID.randomUUID().toString();
        StringBuilder collectedText = new StringBuilder();
        StringBuilder collectedThinkingText = new StringBuilder(); // 思考内容
        long startTime = System.currentTimeMillis();
        boolean finished = false;
        int noDataCount = 0;

        log.info("开始 SSE 实时流式监听（思考模式: {}）", enableThinking);

        while (!finished) {
            try {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                String sseData = getSseDataFromPage(page, SSE_CONFIG.getDataVarName());
                
                if (sseData != null && !sseData.isEmpty()) {
                    noDataCount = 0;
                    
                    // 解析 SSE 数据并提取内容（支持思考内容和最终回复）
                    SseParseResult result = extractContentFromSse(sseData, enableThinking);
                    
                    // 发送思考内容增量
                    if (enableThinking && result.thinkingContent != null && 
                        !result.thinkingContent.isEmpty()) {
                        collectedThinkingText.append(result.thinkingContent);
                        log.debug("从 SSE 发送思考内容增量，长度: {}",
                                result.thinkingContent.length());
                        sendThinkingContent(emitter, id, result.thinkingContent, request.getModel());
                    }
                    
                    // 发送回复内容增量
                    if (result.responseContent != null && !result.responseContent.isEmpty()) {
                        collectedText.append(result.responseContent);
                        log.debug("从 SSE 发送回复内容增量，长度: {}",
                                result.responseContent.length());
                        sendSseChunk(emitter, id, result.responseContent, request.getModel());
                    }
                    
                    // 检查是否完成
                    if (result.finished || sseData.contains("event: done") || sseData.contains("[DONE]")) {
                        finished = true;
                        log.info("SSE 流已完成");
                    }
                } else {
                    noDataCount++;
                    
                    if (noDataCount > 50) { // 5秒没有新数据
                        if (noDataCount > 200) { // 20秒没有新数据，超时
                            log.warn("长时间没有新数据，结束监听");
                            finished = true;
                        }
                    }
                }

                if (System.currentTimeMillis() - startTime > 120000) {
                    log.warn("达到超时时间，结束监听");
                    break;
                }

                Thread.sleep(100);
            } catch (Exception e) {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                if (e.getMessage() != null &&
                        (e.getMessage().contains("Cannot find command") ||
                                e.getMessage().contains("Target closed") ||
                                e.getMessage().contains("Session closed"))) {
                    log.warn("检测到页面状态变化，尝试继续监听: {}", e.getMessage());
                    Thread.sleep(500);
                    continue;
                }

                log.error("SSE 监听时出错", e);
                if (!collectedText.isEmpty()) {
                    log.info("已收集部分内容，长度: {}，结束监听", collectedText.length());
                    break;
                }
                throw e;
            }
        }

        // 发送对话 URL
        try {
            if (!page.isClosed()) {
                String currentUrl = page.url();
                if (currentUrl.contains("chatgpt.com") || currentUrl.contains("chat.openai.com")) {
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
     * DOM 模式监听回复：纯 DOM 解析，实时流式输出
     * 支持深度思考模式，区分思考内容和最终回复
     */
    private void monitorResponseDom(Page page, SseEmitter emitter, ChatCompletionRequest request,
                                   int messageCountBefore, boolean enableThinking)
            throws IOException, InterruptedException {
        // ChatGPT 的回复选择器
        String responseSelector = "[data-message-author-role='assistant'] .markdown, [data-message-author-role='assistant'] [class*='markdown']";
        // OpenAI 的思考内容选择器（根据实际页面结构调整）
        String thinkingSelector = "[data-message-author-role='assistant'] [class*='reasoning'], [data-message-author-role='assistant'] [class*='thinking']";
        
        // 等待新的回复框出现
        Locator responseLocators = page.locator(responseSelector);
        int maxWaitTime = 30000;
        long waitStartTime = System.currentTimeMillis();
        while (responseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始 DOM 监听（纯 DOM 模式，思考模式: {}）", enableThinking);
        
        StringBuilder collectedText = new StringBuilder();
        StringBuilder collectedThinkingText = new StringBuilder(); // 思考内容
        long startTime = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String lastFullText = "";
        int noChangeCount = 0;

        while (true) {
            try {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                // DOM 解析（实时流式）
                try {
                    // 1.1 检查思考内容（深度思考模式）
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
                    Locator allResponseLocators = page.locator(responseSelector);
                    int currentCount = allResponseLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        try {
                            // 查找最后一个不在思考区域内的回复元素
                            String currentText = null;
                            for (int i = currentCount - 1; i >= messageCountBefore; i--) {
                                try {
                                    Locator locator = allResponseLocators.nth(i);
                                    // 检查是否在思考区域内
                                    Boolean isInThinking = (Boolean) locator.evaluate("""
                                        el => {
                                            const reasoningContent = el.closest('[class*="reasoning"], [class*="thinking"]');
                                            return reasoningContent !== null;
                                        }
                                    """);
                                    
                                    if (isInThinking == null || !isInThinking) {
                                        // 不在思考区域内，这是最终回复
                                        currentText = locator.innerText();
                                        break;
                                    }
                                } catch (Exception e) {
                                    // 继续查找下一个
                                }
                            }
                            
                            if (currentText != null) {
                                if (currentText.length() > collectedText.length()) {
                                    String delta = currentText.substring(collectedText.length());
                                    collectedText.append(delta);
                                    noChangeCount = 0;
                                    
                                    // 发送增量（实时）
                                    log.debug("发送回复增量，长度: {}", delta.length());
                                    sendSseChunk(emitter, id, delta, request.getModel());
                                } else if (currentText.equals(lastFullText) && !currentText.isEmpty()) {
                                    noChangeCount++;
                                }
                                
                                lastFullText = currentText;
                            }
                        } catch (Exception e) {
                            log.debug("读取 DOM 文本时出错: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("DOM 查询时出错: {}", e.getMessage());
                }

                // 结束条件检查：内容不再变化
                if (noChangeCount >= 20) {
                    log.info("DOM 内容不再变化，生成完成");
                    break;
                }

                if (System.currentTimeMillis() - startTime > 120000) {
                    log.warn("达到超时时间，结束监听");
                    break;
                }

                Thread.sleep(100);
            } catch (Exception e) {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                if (e.getMessage() != null &&
                        (e.getMessage().contains("Cannot find command") ||
                                e.getMessage().contains("Target closed") ||
                                e.getMessage().contains("Session closed"))) {
                    log.warn("检测到页面状态变化，尝试继续监听: {}", e.getMessage());
                    Thread.sleep(500);
                    continue;
                }

                log.error("监听响应时出错", e);
                if (!collectedText.isEmpty()) {
                    log.info("已收集部分内容，长度: {}，结束监听", collectedText.length());
                    break;
                }
                throw e;
            }
        }

        // 发送对话 URL
        try {
            if (!page.isClosed()) {
                String currentUrl = page.url();
                if (currentUrl.contains("chatgpt.com") || currentUrl.contains("chat.openai.com")) {
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
     * 从 SSE 数据中提取内容（支持思考内容和最终回复）
     * ChatGPT 的 SSE 格式可能不同，需要根据实际情况调整
     * OpenAI 的深度思考模型（o1）可能在 SSE 数据中包含 reasoning_content 字段
     */
    private SseParseResult extractContentFromSse(String sseData, boolean enableThinking) {
        if (sseData == null || sseData.isEmpty()) {
            return new SseParseResult(null, null, false);
        }
        
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder responseText = new StringBuilder();
        boolean finished = false;
        String[] lines = sseData.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            // 检查完成标记
            if (line.startsWith("event: ")) {
                String event = line.substring(7).trim();
                if ("done".equals(event) || "finish".equals(event) || "close".equals(event)) {
                    finished = true;
                }
                continue;
            }
            
            if (line.startsWith("data: ")) {
                String jsonStr = line.substring(6).trim();
                if (jsonStr.isEmpty() || jsonStr.equals("{}") || jsonStr.equals("[DONE]")) {
                    continue;
                }
                
                try {
                    // ChatGPT 的 SSE 数据格式可能是 JSON，需要解析
                    // OpenAI 的深度思考模型可能在 delta 中包含 reasoning_content 字段
                    tools.jackson.databind.JsonNode json = objectMapper.readTree(jsonStr);
                    
                    // 检查是否有 reasoning_content（思考内容）
                    if (enableThinking && json.has("delta") && json.get("delta").has("reasoning_content")) {
                        tools.jackson.databind.JsonNode reasoningNode = json.get("delta").get("reasoning_content");
                        if (reasoningNode.isString()) {
                            String reasoning = reasoningNode.asString();
                            if (reasoning != null && !reasoning.isEmpty()) {
                                thinkingText.append(reasoning);
                            }
                        }
                    }
                    
                    // 提取最终回复内容
                    // 尝试多种可能的格式
                    String content = null;
                    if (json.has("delta") && json.get("delta").has("content")) {
                        tools.jackson.databind.JsonNode contentNode = json.get("delta").get("content");
                        if (contentNode.isString()) {
                            content = contentNode.asString();
                        }
                    } else if (json.has("message") && json.get("message").has("content")) {
                        tools.jackson.databind.JsonNode contentNode = json.get("message").get("content");
                        if (contentNode.has("parts") && contentNode.get("parts").isArray() && contentNode.get("parts").size() > 0) {
                            tools.jackson.databind.JsonNode partNode = contentNode.get("parts").get(0);
                            if (partNode.isString()) {
                                content = partNode.asString();
                            }
                        }
                    } else if (json.has("content")) {
                        tools.jackson.databind.JsonNode contentNode = json.get("content");
                        if (contentNode.isString()) {
                            content = contentNode.asString();
                        }
                    }
                    
                    if (content != null && !content.isEmpty()) {
                        responseText.append(content);
                    }
                } catch (Exception e) {
                    // 如果解析失败，可能是纯文本格式，作为回复内容
                    if (jsonStr.length() > 0 && !jsonStr.equals("[DONE]")) {
                        responseText.append(jsonStr);
                    }
                }
            }
        }
        
        String thinking = thinkingText.length() > 0 ? thinkingText.toString() : null;
        String response = responseText.length() > 0 ? responseText.toString() : null;
        
        return new SseParseResult(thinking, response, finished);
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


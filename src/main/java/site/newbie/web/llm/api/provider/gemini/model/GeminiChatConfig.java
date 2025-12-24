package site.newbie.web.llm.api.provider.gemini.model;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.SseDataLogger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini Chat 模型配置
 * 普通对话模式
 */
@Slf4j
@Component
public class GeminiChatConfig implements GeminiModelConfig {
    
    public static final String MODEL_NAME = "gemini-web-chat";
    
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
    
    @Override
    public void configure(Page page) {
        log.info("配置 {} 模型", MODEL_NAME);
        // Gemini 目前不需要特殊配置
    }

    @Override
    public void monitorResponse(GeminiContext context) throws IOException, InterruptedException {
        if ("sse".equalsIgnoreCase(context.monitorMode())) {
            log.info("开始监听 AI 回复（SSE 模式）...");
            monitorResponseSse(context);
        } else {
            log.info("开始监听 AI 回复（DOM 模式）...");
            monitorResponseDom(context);
        }
    }
    
    private void monitorResponseSse(GeminiContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String id = UUID.randomUUID().toString();
        StringBuilder collectedResponseText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        boolean finished = false;
        int noDataCount = 0;
        
        java.util.Map<Integer, String> fragmentTypeMap = new ConcurrentHashMap<>();
        Integer lastActiveFragmentIndex = null;
        
        SseDataLogger sseLogger = new SseDataLogger(request.getModel(), request);

        while (!finished) {
            try {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                String sseData = handler.getSseData(page, "__geminiSseData");
                
                if (sseData != null && !sseData.isEmpty()) {
                    noDataCount = 0;
                    sseLogger.logSseChunk(sseData);
                    
                    ModelConfig.ParseResultWithIndex parseResult = handler.parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
                    ModelConfig.SseParseResult result = parseResult.result();
                    lastActiveFragmentIndex = parseResult.lastActiveFragmentIndex();
                    
                    if (result.responseContent() != null && !result.responseContent().isEmpty()) {
                        collectedResponseText.append(result.responseContent());
                        handler.sendChunk(emitter, id, result.responseContent(), request.getModel());
                    }
                    
                    if (result.finished()) {
                        finished = true;
                        log.info("SSE 流已完成");
                    }
                } else {
                    noDataCount++;
                    
                    // 当没有新数据时，检查页面状态确认是否完成
                    if (noDataCount > 50) {
                        // 检查动画元素：如果存在未完成的动画，说明还在生成中
                        boolean animationActive = false;
                        try {
                            Locator animationLocator = page.locator("div.avatar div.avatar_primary_animation:not([data-test-lottie-animation-status='completed'])");
                            animationActive = animationLocator.count() > 0;
                        } catch (Exception e) {
                            log.debug("检查动画状态时出错: {}", e.getMessage());
                        }
                        
                        // 如果动画还在进行，说明还在生成，继续等待
                        if (animationActive) {
                            noDataCount = 0; // 重置无数据计数
                            continue;
                        }
                        
                        // 参考 Frame Kitchen 项目，检查思考状态和发送按钮
                        try {
                            String thinkingSelector = ".send-button.stop, .bard-avatar.thinking";
                            Locator thinkingLocator = page.locator(thinkingSelector);
                            boolean thinkingVisible = thinkingLocator.all().stream().anyMatch(Locator::isVisible);
                            
                            // 检查发送按钮是否出现
                            Locator sendButton = page.locator("input-container button.send-button:not(.stop)")
                                    .or(page.locator("button[aria-label*='发送']:not(.stop)"))
                                    .or(page.locator("button[aria-label*='Send']:not(.stop)"));
                            
                            boolean sendButtonVisible = sendButton.count() > 0 && sendButton.first().isVisible();
                            
                            // 如果动画已完成，思考元素不可见，且发送按钮可见，说明对话已完成
                            if (!animationActive && !thinkingVisible && sendButtonVisible) {
                                finished = true;
                                log.info("检测到响应完成（动画已完成，思考状态结束，发送按钮已出现）");
                            }
                        } catch (Exception e) {
                            log.debug("检查页面状态时出错: {}", e.getMessage());
                        }
                        
                        // 如果还没完成，检查 SSE 数据中的完成标记
                        if (!finished) {
                            try {
                                Object fullData = page.evaluate("""
                                    () => {
                                        if (window.__geminiSseData && window.__geminiSseData.length > 0) {
                                            return window.__geminiSseData.join('\\n');
                                        }
                                        return null;
                                    }
                                """);
                                
                                if (fullData != null) {
                                    String fullSseData = fullData.toString();
                                    if (fullSseData.contains("event: finish") || fullSseData.contains("event: close")) {
                                        finished = true;
                                        log.info("检测到完成标记，结束监听");
                                    }
                                }
                            } catch (Exception e) {
                                log.error("检查完成标记时出错: {}", e.getMessage());
                            }
                        }
                        
                        if (!finished && noDataCount > 200) {
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
                if (page.isClosed()) break;
                if (isRecoverableError(e)) {
                    Thread.sleep(500);
                    continue;
                }
                log.error("SSE 监听时出错", e);
                if (!collectedResponseText.isEmpty()) break;
                throw e;
            }
        }

        sseLogger.logSummary(collectedResponseText.length());
        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private void monitorResponseDom(GeminiContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        int messageCountBefore = context.messageCountBefore();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        // 参考 Frame Kitchen 项目，使用 model-response message-content 作为响应选择器
        String allMarkdownSelector = "model-response message-content";
        
        Locator allResponseLocators = page.locator(allMarkdownSelector);
        int maxWaitTime = 30000;
        long waitStartTime = System.currentTimeMillis();
        while (allResponseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始 DOM 监听（思考过程）");
        
        // 尝试点击思考按钮以展开思考内容
        clickThoughtsButtonIfExists(page);
        
        StringBuilder collectedThinkingText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String thinkingId = UUID.randomUUID().toString();
        String lastFullText = "";
        String lastThinkingText = "";
        int noChangeCount = 0;
        boolean responseFinished = false;
        boolean thoughtsButtonClicked = false;

        // 第一阶段：监听思考过程，将所有内容作为思考过程发送
        while (true) {
            try {
                if (page.isClosed()) break;

                try {
                    // 尝试点击思考按钮（如果还未点击）
                    if (!thoughtsButtonClicked) {
                        if (clickThoughtsButtonIfExists(page)) {
                            thoughtsButtonClicked = true;
                            // 等待思考内容展开
                            Thread.sleep(500);
                        }
                    }
                    
                    // 提取思考内容（如果存在）
                    String thinkingText = extractThinkingContent(page, messageCountBefore);
                    if (thinkingText != null && !thinkingText.isEmpty()) {
                        if (thinkingText.length() > lastThinkingText.length()) {
                            String delta = thinkingText.substring(lastThinkingText.length());
                            collectedThinkingText.append(delta);
                            noChangeCount = 0;
                            // 作为思考内容发送
                            handler.sendThinking(emitter, thinkingId, delta, request.getModel());
                        } else if (thinkingText.equals(lastThinkingText) && !thinkingText.isEmpty()) {
                            noChangeCount++;
                        }
                        lastThinkingText = thinkingText;
                    }
                    
                    Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                    int currentCount = allMarkdownLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        String currentText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
                        
                        if (currentText != null) {
                            // 将新增的内容作为思考过程发送（如果还没有思考内容）
                            if (thinkingText == null || thinkingText.isEmpty()) {
                                if (currentText.length() > collectedThinkingText.length()) {
                                    String delta = currentText.substring(collectedThinkingText.length());
                                    collectedThinkingText.append(delta);
                                    noChangeCount = 0;
                                    // 作为思考内容发送
                                    handler.sendThinking(emitter, thinkingId, delta, request.getModel());
                                } else if (currentText.equals(lastFullText) && !currentText.isEmpty()) {
                                    noChangeCount++;
                                }
                            }
                            lastFullText = currentText;
                        }
                    }
                } catch (Exception e) {
                    log.error("DOM 查询时出错: {}", e.getMessage());
                }

                    // 检测响应是否完成
                    if (!responseFinished) {
                        // 检查动画元素：如果存在未完成的动画，说明还在生成中
                        boolean animationActive = false;
                        try {
                            Locator animationLocator = page.locator("div.avatar div.avatar_primary_animation:not([data-test-lottie-animation-status='completed'])");
                            animationActive = animationLocator.count() > 0;
                        } catch (Exception e) {
                            log.debug("检查动画状态时出错: {}", e.getMessage());
                        }
                    
                    // 如果动画还在进行，说明还在生成，继续等待
                    if (animationActive) {
                        noChangeCount = 0; // 重置无变化计数
                        continue;
                    }
                    
                    // 参考 Frame Kitchen 项目，使用 .send-button.stop, .bard-avatar.thinking 检测思考状态
                    String thinkingSelector = ".send-button.stop, .bard-avatar.thinking";
                    boolean thinkingVisible = false;
                    try {
                        Locator thinkingLocator = page.locator(thinkingSelector);
                        // 检查是否有思考元素可见
                        thinkingVisible = thinkingLocator.all().stream().anyMatch(Locator::isVisible);
                    } catch (Exception e) {
                        // 忽略错误，继续检查
                    }
                    
                    // 检查发送按钮是否出现（对话完成后，发送按钮会重新出现）
                    boolean sendButtonVisible = false;
                    try {
                        // 查找发送按钮（不是停止按钮）
                        Locator sendButton = page.locator("input-container button.send-button:not(.stop)")
                                .or(page.locator("button[aria-label*='发送']:not(.stop)"))
                                .or(page.locator("button[aria-label*='Send']:not(.stop)"))
                                .or(page.locator("button.send-button:not(.stop):not([disabled])"));
                        
                        if (sendButton.count() > 0) {
                            sendButtonVisible = sendButton.first().isVisible();
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续检查
                    }
                    
                    // 如果动画已完成，思考元素不可见，且发送按钮可见，说明对话已完成
                    if (!animationActive && !thinkingVisible && sendButtonVisible) {
                        // 再等待一小段时间，确保状态稳定
                        Thread.sleep(500);
                        // 再次确认动画状态和发送按钮
                        try {
                            Locator animationLocator2 = page.locator("div.avatar div.avatar_primary_animation:not([data-test-lottie-animation-status='completed'])");
                            boolean animationActive2 = animationLocator2.count() > 0;
                            
                            thinkingVisible = page.locator(thinkingSelector).all().stream().anyMatch(Locator::isVisible);
                            sendButtonVisible = page.locator("input-container button.send-button:not(.stop)").count() > 0 
                                    && page.locator("input-container button.send-button:not(.stop)").first().isVisible();
                            
                            if (!animationActive2 && !thinkingVisible && sendButtonVisible) {
                                responseFinished = true;
                                log.info("检测到响应完成（动画已完成，思考状态结束，发送按钮已出现）");
                            }
                        } catch (Exception e) {
                            // 如果再次检查失败，但第一次检查通过，也认为完成
                            responseFinished = true;
                            log.info("检测到响应完成（动画已完成，思考状态结束，发送按钮已出现，二次确认时出错但继续）");
                        }
                    } else if (!animationActive && !thinkingVisible) {
                        // 如果动画已完成，思考元素不可见，但发送按钮还没出现，可能是短暂状态，继续等待
                        // 但如果长时间（比如 2 秒）都是这样，也认为完成
                        if (noChangeCount >= 20) {
                            responseFinished = true;
                            log.info("检测到响应完成（动画已完成，思考状态结束，内容稳定）");
                        }
                    }
                    
                    // 如果内容长时间不变，也认为已完成（但优先级低于动画和发送按钮检查）
                    if (!responseFinished && noChangeCount >= 30) {
                        responseFinished = true;
                        log.info("检测到响应完成（内容长时间稳定）");
                    }
                }

                if (responseFinished) {
                    break;
                }

                if (System.currentTimeMillis() - startTime > 120000) {
                    log.warn("达到超时时间，结束思考过程监听");
                    break;
                }

                Thread.sleep(100);
            } catch (Exception e) {
                if (page.isClosed()) break;
                if (isRecoverableError(e)) {
                    Thread.sleep(500);
                    continue;
                }
                log.error("监听思考过程时出错", e);
                break;
            }
        }

        // 第二阶段：响应完成后，重新获取 DOM 内容作为正式回复
        log.info("思考过程完成，获取最终回复内容");
        try {
            // 等待一小段时间，确保 DOM 内容完全稳定
            Thread.sleep(500);
            
            Locator allMarkdownLocators = page.locator(allMarkdownSelector);
            int currentCount = allMarkdownLocators.count();
            
            if (currentCount > messageCountBefore) {
                String finalResponseText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
                
                if (finalResponseText != null && !finalResponseText.isEmpty()) {
                    log.info("发送最终回复内容，长度: {}", finalResponseText.length());
                    // 将最终内容作为正式回复发送
                    String responseId = UUID.randomUUID().toString();
                    handler.sendChunk(emitter, responseId, finalResponseText, request.getModel());
                } else {
                    log.warn("无法获取最终回复内容");
                }
            } else {
                log.warn("未找到新的响应内容");
            }
        } catch (Exception e) {
            log.error("获取最终回复内容时出错: {}", e.getMessage(), e);
        }

        // 第三阶段：对话完成后，折叠思考内容
        try {
            collapseThoughts(page);
        } catch (Exception e) {
            log.warn("折叠思考内容时出错: {}", e.getMessage());
        }

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private String findFinalResponseText(Locator allMarkdownLocators, int messageCountBefore, int currentCount) {
        for (int i = currentCount - 1; i >= messageCountBefore; i--) {
            try {
                Locator locator = allMarkdownLocators.nth(i);
                // 使用 JavaScript 将 HTML 内容转换为 Markdown 格式
                String markdown = (String) locator.evaluate("""
                    (element) => {
                        if (!element) return '';
                        
                        function htmlToMarkdown(node) {
                            if (node.nodeType === Node.TEXT_NODE) {
                                return node.textContent || '';
                            }
                            
                            if (node.nodeType !== Node.ELEMENT_NODE) {
                                return '';
                            }
                            
                            const tagName = node.tagName?.toLowerCase();
                            const children = Array.from(node.childNodes);
                            let content = children.map(htmlToMarkdown).join('');
                            
                            switch (tagName) {
                                case 'strong':
                                case 'b':
                                    return '**' + content + '**';
                                case 'em':
                                case 'i':
                                    return '*' + content + '*';
                                case 'code':
                                    // 检查是否在 pre 标签内
                                    if (node.parentElement?.tagName?.toLowerCase() === 'pre') {
                                        return content;
                                    }
                                    return '`' + content + '`';
                                case 'pre':
                                    const codeElement = node.querySelector('code');
                                    const language = codeElement?.className?.match(/language-(\\w+)/)?.[1] || '';
                                    const codeContent = codeElement ? codeElement.textContent : content;
                                    return '\\n```' + language + '\\n' + codeContent + '\\n```\\n';
                                case 'a':
                                    const href = node.getAttribute('href') || '';
                                    return '[' + content + '](' + href + ')';
                                case 'h1':
                                    return '\\n# ' + content + '\\n';
                                case 'h2':
                                    return '\\n## ' + content + '\\n';
                                case 'h3':
                                    return '\\n### ' + content + '\\n';
                                case 'h4':
                                    return '\\n#### ' + content + '\\n';
                                case 'h5':
                                    return '\\n##### ' + content + '\\n';
                                case 'h6':
                                    return '\\n###### ' + content + '\\n';
                                case 'p':
                                    return content + '\\n\\n';
                                case 'br':
                                    return '\\n';
                                case 'ul':
                                case 'ol':
                                    return '\\n' + content + '\\n';
                                case 'li':
                                    const parent = node.parentElement;
                                    const isOrdered = parent?.tagName?.toLowerCase() === 'ol';
                                    const index = Array.from(parent?.children || []).indexOf(node) + 1;
                                    const prefix = isOrdered ? index + '. ' : '- ';
                                    return prefix + content.trim() + '\\n';
                                case 'blockquote':
                                    return '\\n> ' + content.trim().replace(/\\n/g, '\\n> ') + '\\n';
                                case 'hr':
                                    return '\\n---\\n';
                                default:
                                    return content;
                            }
                        }
                        
                        return htmlToMarkdown(element).trim();
                    }
                """);
                return markdown;
            } catch (Exception e) {
                log.warn("获取响应文本时出错，尝试使用纯文本: {}", e.getMessage());
                try {
                    // 如果转换失败，回退到纯文本
                    Locator locator = allMarkdownLocators.nth(i);
                    return locator.innerText();
                } catch (Exception e2) {
                    // 继续查找
                }
            }
        }
        return null;
    }
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
    
    /**
     * 检查最新的思考内容是否已经展开
     * 通过检查最新的 model-thoughts structured-content-container 是否存在来判断
     * @param page 页面对象
     * @return 是否已经展开
     */
    private boolean isThoughtsExpanded(Page page) {
        try {
            // 使用 Playwright locator 检查最新的 model-thoughts structured-content-container 是否可见
            Locator thoughtsElements = page.locator("model-thoughts structured-content-container");
            int count = thoughtsElements.count();
            if (count == 0) {
                return false;
            }
            // 检查最后一个元素（最新的思考内容）是否可见
            return thoughtsElements.nth(count - 1).isVisible();
        } catch (Exception e) {
            log.debug("检查思考内容是否展开时出错: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 点击思考按钮以展开思考内容
     * @param page 页面对象
     * @return 是否成功点击了按钮（或已经展开）
     */
    private boolean clickThoughtsButtonIfExists(Page page) {
        try {
            // 先检查是否已经展开
            if (isThoughtsExpanded(page)) {
                log.debug("思考内容已经展开，无需点击按钮");
                return true;
            }
            
            // 查找思考按钮：class="thoughts-header-button-content"
            Locator thoughtsButton = page.locator(".mdc-button__label .thoughts-header-button-content");
            
            // 等待按钮出现（最多等待 3 秒）
            int maxWaitAttempts = 30;
            int attempts = 0;
            while (attempts < maxWaitAttempts && thoughtsButton.count() == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待思考按钮时被中断");
                    return false;
                }
                attempts++;
                // 在等待过程中也检查是否已经展开
                if (isThoughtsExpanded(page)) {
                    log.debug("在等待按钮时检测到思考内容已展开");
                    return true;
                }
            }
            
            if (thoughtsButton.count() > 0) {
                // 点击最新的思考按钮（最后一个，对应最新的回复）
                int buttonCount = thoughtsButton.count();
                Locator latestButton = thoughtsButton.nth(buttonCount - 1);
                
                try {
                    if (latestButton.isVisible()) {
                        latestButton.click();
                        log.info("已点击最新的思考按钮（共 {} 个），等待思考内容展开", buttonCount);
                        
                        // 等待思考内容展开（最多等待 2 秒）
                        int waitAttempts = 0;
                        while (waitAttempts < 20 && !isThoughtsExpanded(page)) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("等待思考内容展开时被中断");
                                break;
                            }
                            waitAttempts++;
                        }
                        
                        if (isThoughtsExpanded(page)) {
                            log.info("思考内容已成功展开");
                            return true;
                        } else {
                            log.warn("点击按钮后思考内容未展开");
                            return false;
                        }
                    } else {
                        log.debug("最新的思考按钮不可见");
                        return false;
                    }
                } catch (Exception e) {
                    log.debug("点击最新的思考按钮失败: {}", e.getMessage());
                    return false;
                }
            }
        } catch (Exception e) {
            log.debug("未找到思考按钮或点击失败: {}", e.getMessage());
        }
        return false;
    }
    
    /**
     * 从 model-thoughts structured-content-container 元素中提取思考内容
     * @param page 页面对象
     * @param messageCountBefore 消息计数（用于定位最新的思考内容）
     * @return 思考内容文本，如果不存在则返回 null
     */
    private String extractThinkingContent(Page page, int messageCountBefore) {
        try {
            // 先检查是否已经展开
            if (!isThoughtsExpanded(page)) {
                log.debug("思考内容未展开，无法提取");
                return null;
            }
            
            // 使用 Playwright Locator 直接提取文本内容
            Locator thoughtsElements = page.locator("model-thoughts structured-content-container");
            int count = thoughtsElements.count();
            if (count > 0) {
                Locator latestThoughts = thoughtsElements.nth(count - 1);
                // 使用 innerText() 自动过滤隐藏元素和脚本标签
                String innerText = latestThoughts.innerText();
                if (innerText != null && !innerText.trim().isEmpty()) {
                    // 移除多余的空白字符
                    return innerText.trim().replaceAll("\\s+", " ");
                }
            }
        } catch (Exception e) {
            log.debug("查找思考内容时出错: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 折叠思考内容
     * 在对话完成后调用，将思考内容折叠起来
     * @param page 页面对象
     */
    private void collapseThoughts(Page page) {
        try {
            // 检查是否已经展开，如果未展开则无需折叠
            if (!isThoughtsExpanded(page)) {
                log.debug("思考内容未展开，无需折叠");
                return;
            }
            
            // 查找最新的思考按钮（最后一个）
            Locator thoughtsButton = page.locator(".mdc-button__label .thoughts-header-button-content");
            
            if (thoughtsButton.count() > 0) {
                // 点击最新的思考按钮（最后一个）来折叠
                int buttonCount = thoughtsButton.count();
                Locator latestButton = thoughtsButton.nth(buttonCount - 1);
                
                try {
                    if (latestButton.isVisible()) {
                        latestButton.click();
                        log.info("已点击最新的思考按钮（共 {} 个）以折叠思考内容", buttonCount);
                        
                        // 等待思考内容折叠（最多等待 1 秒）
                        int waitAttempts = 0;
                        while (waitAttempts < 10 && isThoughtsExpanded(page)) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("等待思考内容折叠时被中断");
                                break;
                            }
                            waitAttempts++;
                        }
                        
                        if (!isThoughtsExpanded(page)) {
                            log.info("思考内容已成功折叠");
                        } else {
                            log.warn("点击按钮后思考内容未折叠");
                        }
                    } else {
                        log.debug("最新的思考按钮不可见，无法折叠");
                    }
                } catch (Exception e) {
                    log.debug("点击思考按钮折叠时出错: {}", e.getMessage());
                }
            } else {
                log.debug("未找到思考按钮，无法折叠");
            }
        } catch (Exception e) {
            log.debug("折叠思考内容时出错: {}", e.getMessage());
        }
    }
}


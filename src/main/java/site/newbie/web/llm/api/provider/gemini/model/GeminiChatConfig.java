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
                    
                    if (noDataCount > 50) {
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
        
        StringBuilder collectedThinkingText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String thinkingId = UUID.randomUUID().toString();
        String lastFullText = "";
        int noChangeCount = 0;
        boolean responseFinished = false;

        // 第一阶段：监听思考过程，将所有内容作为思考过程发送
        while (true) {
            try {
                if (page.isClosed()) break;

                try {
                    Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                    int currentCount = allMarkdownLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        String currentText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
                        
                        if (currentText != null) {
                            // 将新增的内容作为思考过程发送
                            if (currentText.length() > collectedThinkingText.length()) {
                                String delta = currentText.substring(collectedThinkingText.length());
                                collectedThinkingText.append(delta);
                                noChangeCount = 0;
                                // 作为思考内容发送
                                handler.sendThinking(emitter, thinkingId, delta, request.getModel());
                            } else if (currentText.equals(lastFullText) && !currentText.isEmpty()) {
                                noChangeCount++;
                            }
                            lastFullText = currentText;
                        }
                    }
                } catch (Exception e) {
                    log.error("DOM 查询时出错: {}", e.getMessage());
                }

                // 检测响应是否完成
                if (!responseFinished) {
                    // 参考 Frame Kitchen 项目，使用 .send-button.stop, .bard-avatar.thinking 检测思考状态
                    String thinkingSelector = ".send-button.stop, .bard-avatar.thinking";
                    try {
                        Locator thinkingLocator = page.locator(thinkingSelector);
                        // 如果所有思考元素都不可见，说明生成结束
                        if (thinkingLocator.all().stream().noneMatch(Locator::isVisible)) {
                            responseFinished = true;
                            log.info("检测到响应完成（思考状态结束）");
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续检查
                    }
                    
                    // 如果内容长时间不变，也认为已完成
                    if (noChangeCount >= 20) {
                        responseFinished = true;
                        log.info("检测到响应完成（内容稳定）");
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
}


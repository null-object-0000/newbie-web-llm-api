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
        
        log.info("检测到新消息，开始 DOM 监听");
        
        StringBuilder collectedText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String lastFullText = "";
        int noChangeCount = 0;
        boolean sseFinished = false;

        while (true) {
            try {
                if (page.isClosed()) break;

                try {
                    Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                    int currentCount = allMarkdownLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        String finalText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
                        
                        if (finalText != null) {
                            if (finalText.length() > collectedText.length()) {
                                String delta = finalText.substring(collectedText.length());
                                collectedText.append(delta);
                                noChangeCount = 0;
                                handler.sendChunk(emitter, id, delta, request.getModel());
                            } else if (finalText.equals(lastFullText) && !finalText.isEmpty()) {
                                noChangeCount++;
                            }
                            lastFullText = finalText;
                        }
                    }
                } catch (Exception e) {
                    log.error("DOM 查询时出错: {}", e.getMessage());
                }

                if (!sseFinished) {
                    // 参考 Frame Kitchen 项目，使用 .send-button.stop, .bard-avatar.thinking 检测思考状态
                    String thinkingSelector = ".send-button.stop, .bard-avatar.thinking";
                    try {
                        Locator thinkingLocator = page.locator(thinkingSelector);
                        // 如果所有思考元素都不可见，说明生成结束
                        if (thinkingLocator.all().stream().noneMatch(Locator::isVisible)) {
                            sseFinished = true;
                            log.info("检测到响应完成（思考状态结束）");
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续检查 SSE
                    }
                    
                    String sseData = handler.getSseData(page, "__geminiSseData");
                    if (sseData != null && (sseData.contains("event: finish") || sseData.contains("event: close"))) {
                        sseFinished = true;
                        log.info("SSE 流已完成");
                    }
                }

                if (sseFinished) {
                    performFinalCorrection(page, allMarkdownSelector, messageCountBefore, 
                            collectedText.toString(), emitter, id, request.getModel(), handler);
                    break;
                }

                if (noChangeCount >= 20 && sseFinished) break;
                if (System.currentTimeMillis() - startTime > 120000) break;

                Thread.sleep(100);
            } catch (Exception e) {
                if (page.isClosed()) break;
                if (isRecoverableError(e)) {
                    Thread.sleep(500);
                    continue;
                }
                log.error("监听响应时出错", e);
                if (!collectedText.isEmpty()) break;
                throw e;
            }
        }

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private String findFinalResponseText(Locator allMarkdownLocators, int messageCountBefore, int currentCount) {
        for (int i = currentCount - 1; i >= messageCountBefore; i--) {
            try {
                Locator locator = allMarkdownLocators.nth(i);
                return locator.innerText();
            } catch (Exception e) {
                // 继续查找
            }
        }
        return null;
    }
    
    private void performFinalCorrection(Page page, String selector, int messageCountBefore,
                                        String currentText, SseEmitter emitter, String id, 
                                        String model, ModelConfig.ResponseHandler handler) throws IOException {
        try {
            Locator allMarkdownLocators = page.locator(selector);
            int currentCount = allMarkdownLocators.count();
            
            String finalText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
            
            if (finalText != null && !finalText.equals(currentText)) {
                log.info("检测到内容差异，整体替换");
                handler.sendReplace(emitter, id, finalText, model);
            }
        } catch (Exception e) {
            log.error("获取最终回复时出错: {}", e.getMessage());
        }
    }
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
}


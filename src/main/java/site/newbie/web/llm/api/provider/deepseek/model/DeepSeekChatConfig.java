package site.newbie.web.llm.api.provider.deepseek.model;

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
 * DeepSeek Chat 模型配置
 * 普通对话模式，不启用深度思考
 */
@Slf4j
@Component
public class DeepSeekChatConfig implements DeepSeekModelConfig {
    
    public static final String MODEL_NAME = "deepseek-web-chat";
    
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
    
    @Override
    public void configure(Page page) {
        log.info("配置 {} 模型：确保深度思考关闭", MODEL_NAME);
        disableThinkingMode(page);
    }
    
    private void disableThinkingMode(Page page) {
        try {
            Locator thinkingToggle = page.locator("div[role='button'].ds-toggle-button:has-text('深度思考')")
                    .or(page.locator("div[role='button']:has-text('深度思考')"))
                    .or(page.locator("button.ds-toggle-button:has-text('深度思考')"))
                    .or(page.locator("button:has-text('深度思考')"))
                    .first();
            
            if (thinkingToggle.count() > 0) {
                try {
                    String className = thinkingToggle.getAttribute("class");
                    boolean isActive = className != null && 
                        (className.contains("active") || className.contains("selected") || 
                         className.contains("ds-toggle-button--active") ||
                         className.contains("ds-toggle-button-active"));
                    
                    if (isActive) {
                        thinkingToggle.click();
                        page.waitForTimeout(300);
                        log.info("已关闭深度思考模式");
                    } else {
                        log.info("深度思考模式已关闭");
                    }
                } catch (Exception e) {
                    page.evaluate("""
                        () => {
                            const buttons = document.querySelectorAll('button, div[role="button"]');
                            for (const btn of buttons) {
                                const text = btn.textContent || '';
                                if (text.includes('深度思考') || text.includes('Thinking')) {
                                    const isActive = btn.classList.contains('active') ||
                                                   btn.classList.contains('selected') ||
                                                   btn.classList.contains('ds-toggle-button--active') ||
                                                   btn.classList.contains('ds-toggle-button-active') ||
                                                   btn.getAttribute('aria-pressed') === 'true';
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
            log.error("关闭深度思考模式时出错: {}", e.getMessage());
        }
    }

    @Override
    public void monitorResponse(DeepSeekContext context) throws IOException, InterruptedException {
        if ("sse".equalsIgnoreCase(context.monitorMode())) {
            log.info("开始监听 AI 回复（SSE 模式，普通对话）...");
            monitorResponseSse(context);
        } else {
            log.info("开始监听 AI 回复（DOM 模式，普通对话）...");
            monitorResponseDom(context);
        }
    }
    
    private void monitorResponseSse(DeepSeekContext context) throws IOException, InterruptedException {
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
        
        // 用于调试：记录所有接收到的原始 SSE 数据
        SseDataLogger sseLogger = new SseDataLogger(request.getModel(), request);

        while (!finished) {
            try {
                if (page.isClosed()) {
                    log.warn("页面已关闭，结束监听");
                    break;
                }

                String sseData = handler.getSseData(page, "__deepseekSseData");
                
                if (sseData != null && !sseData.isEmpty()) {
                    noDataCount = 0;
                    
                    // 记录完整的原始 SSE 响应数据（用于调试）
                    sseLogger.logSseChunk(sseData);
                    
                    ModelConfig.ParseResultWithIndex parseResult = handler.parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
                    ModelConfig.SseParseResult result = parseResult.result();
                    lastActiveFragmentIndex = parseResult.lastActiveFragmentIndex();
                    
                    // 只发送回复内容，忽略思考内容
                    if (result.responseContent() != null && !result.responseContent().isEmpty()) {
                        collectedResponseText.append(result.responseContent());
                        log.info("发送回复内容增量，长度: {}", result.responseContent().length());
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
                                    if (window.__deepseekSseData && window.__deepseekSseData.length > 0) {
                                        return window.__deepseekSseData.join('\\n');
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

        // 记录完整的原始 SSE 响应数据汇总（用于调试）
        sseLogger.logSummary(collectedResponseText.length());

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private void monitorResponseDom(DeepSeekContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        int messageCountBefore = context.messageCountBefore();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String allMarkdownSelector = ".ds-markdown";
        
        Locator allResponseLocators = page.locator(allMarkdownSelector);
        int maxWaitTime = 30000;
        long waitStartTime = System.currentTimeMillis();
        while (allResponseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始 DOM 监听（普通对话模式）");
        
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
                    String sseData = handler.getSseData(page, "__deepseekSseData");
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
                Boolean isInThinking = (Boolean) locator.evaluate("""
                    el => {
                        const thinkContent = el.closest('.ds-think-content');
                        return thinkContent !== null;
                    }
                """);
                
                if (isInThinking == null || !isInThinking) {
                    return locator.innerText();
                }
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

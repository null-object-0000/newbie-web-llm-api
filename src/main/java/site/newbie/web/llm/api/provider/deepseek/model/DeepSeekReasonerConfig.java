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
 * DeepSeek Reasoner 模型配置
 * 深度思考模式，启用深度思考并处理思考内容
 */
@Slf4j
@Component
public class DeepSeekReasonerConfig implements DeepSeekModelConfig {
    
    public static final String MODEL_NAME = "deepseek-web-reasoner";
    
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
    
    @Override
    public void configure(Page page) {
        log.info("配置 {} 模型：启用深度思考", MODEL_NAME);
        enableThinkingMode(page);
    }
    
    private void enableThinkingMode(Page page) {
        try {
            try {
                page.locator("textarea").waitFor();
            } catch (Exception e) {
                log.error("等待输入框超时: {}", e.getMessage());
            }
            page.waitForTimeout(1500);
            
            boolean thinkingEnabled = false;
            int maxRetries = 3;
            
            for (int retry = 0; retry < maxRetries && !thinkingEnabled; retry++) {
                if (retry > 0) {
                    log.info("重试查找深度思考按钮 (第 {} 次)", retry + 1);
                    page.waitForTimeout(1000);
                }
                
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
                        String className = thinkingToggle.getAttribute("class");
                        boolean isActive = className != null && 
                            (className.contains("active") || className.contains("selected") || 
                             className.contains("ds-toggle-button--active") ||
                             className.contains("ds-toggle-button-active"));
                        
                        if (!isActive) {
                            thinkingToggle.click();
                            page.waitForTimeout(800);
                            log.info("已启用深度思考模式");
                        } else {
                            log.info("深度思考模式已启用");
                        }
                        thinkingEnabled = true;
                    }
                } catch (Exception e) {
                    log.error("Locator 方法失败: {}", e.getMessage());
                }
                
                if (!thinkingEnabled) {
                    try {
                        String result = (String) page.evaluate("""
                            () => {
                                const buttons = document.querySelectorAll('button, div[role="button"]');
                                for (const btn of buttons) {
                                    const text = (btn.textContent || '').trim();
                                    const ariaLabel = (btn.getAttribute('aria-label') || '').trim();
                                    
                                    if (text.includes('深度思考') || text.includes('Thinking') ||
                                        ariaLabel.includes('思考') || ariaLabel.includes('Think')) {
                                        const isActive = btn.classList.contains('active') ||
                                                       btn.classList.contains('selected') ||
                                                       btn.classList.contains('ds-toggle-button--active') ||
                                                       btn.classList.contains('ds-toggle-button-active') ||
                                                       btn.getAttribute('aria-pressed') === 'true';
                                        
                                        if (!isActive) {
                                            btn.click();
                                            return 'clicked';
                                        }
                                        return 'already-active';
                                    }
                                }
                                return 'not-found';
                            }
                        """);
                        
                        if ("clicked".equals(result) || "already-active".equals(result)) {
                            thinkingEnabled = true;
                            log.info("通过 JavaScript 处理深度思考模式: {}", result);
                        }
                    } catch (Exception e) {
                        log.error("JavaScript 方法失败: {}", e.getMessage());
                    }
                }
            }
            
            if (!thinkingEnabled) {
                log.warn("无法启用深度思考模式");
            }
        } catch (Exception e) {
            log.error("启用深度思考模式时出错: {}", e.getMessage());
        }
    }

    @Override
    public void monitorResponse(DeepSeekContext context) throws IOException, InterruptedException {
        log.info("开始监听 AI 回复（SSE 模式，深度思考）...");
        monitorResponseSse(context);
    }
    
    private void monitorResponseSse(DeepSeekContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String id = UUID.randomUUID().toString();
        StringBuilder collectedThinkingText = new StringBuilder();
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
                if (page.isClosed()) break;

                String sseData = handler.getSseData(page, "__deepseekSseData");
                
                if (sseData != null && !sseData.isEmpty()) {
                    noDataCount = 0;
                    
                    // 记录完整的原始 SSE 响应数据（用于调试）
                    sseLogger.logSseChunk(sseData);
                    
                    ModelConfig.ParseResultWithIndex parseResult = handler.parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
                    ModelConfig.SseParseResult result = parseResult.result();
                    lastActiveFragmentIndex = parseResult.lastActiveFragmentIndex();
                    
                    // 发送思考内容
                    if (result.thinkingContent() != null && !result.thinkingContent().isEmpty()) {
                        collectedThinkingText.append(result.thinkingContent());
                        handler.sendThinking(emitter, id, result.thinkingContent(), request.getModel());
                    }
                    
                    // 发送回复内容
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
                                }
                            }
                        } catch (Exception e) {
                            log.error("检查完成标记时出错: {}", e.getMessage());
                        }
                        
                        if (!finished && noDataCount > 200) {
                            finished = true;
                        }
                    }
                }

                if (System.currentTimeMillis() - startTime > 120000) break;
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
        sseLogger.logSummary(collectedResponseText.length() + collectedThinkingText.length());

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
}

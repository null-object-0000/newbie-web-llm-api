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
                    // Fallback: 尝试使用更宽泛的选择器查找按钮
                    try {
                        Locator fallbackButtons = page.locator("button:has-text('深度思考')")
                                .or(page.locator("button:has-text('Thinking')"))
                                .or(page.locator("div[role='button']:has-text('深度思考')"))
                                .or(page.locator("div[role='button']:has-text('Thinking')"));
                        
                        if (fallbackButtons.count() > 0) {
                            Locator button = fallbackButtons.first();
                            String className = button.getAttribute("class");
                            String ariaPressed = button.getAttribute("aria-pressed");
                            boolean isActive = (className != null && 
                                    (className.contains("active") || className.contains("selected") || 
                                     className.contains("ds-toggle-button--active") ||
                                     className.contains("ds-toggle-button-active"))) ||
                                    "true".equals(ariaPressed);
                            
                            if (isActive) {
                                button.click();
                                page.waitForTimeout(300);
                                log.info("已通过 fallback 方法关闭深度思考模式");
                            }
                        }
                    } catch (Exception e2) {
                        log.debug("Fallback 方法也失败: {}", e2.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("关闭深度思考模式时出错: {}", e.getMessage());
        }
    }

    @Override
    public void monitorResponse(DeepSeekContext context) throws IOException, InterruptedException {
        log.info("开始监听 AI 回复（SSE 模式，普通对话）...");
        monitorResponseSse(context);
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
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
}

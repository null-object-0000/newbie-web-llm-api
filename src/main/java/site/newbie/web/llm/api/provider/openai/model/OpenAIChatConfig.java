package site.newbie.web.llm.api.provider.openai.model;

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

/**
 * OpenAI Chat 模型配置
 * 普通对话模式，不启用深度思考
 */
@Slf4j
@Component
public class OpenAIChatConfig implements OpenAIModelConfig {
    
    public static final String MODEL_NAME = "gpt-web-chat";
    
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
            // 查找 footer 区域的思考按钮，如果已激活则点击关闭
            Locator thinkingPill = page.locator("button.__composer-pill[aria-label*='思考']")
                    .or(page.locator("div[data-testid='composer-footer-actions'] button:has-text('思考')"));
            
            if (thinkingPill.count() > 0) {
                Locator removeButton = thinkingPill.locator("div.__composer-pill-remove")
                        .or(thinkingPill.locator("svg[viewBox='0 0 16 16']").locator(".."));
                
                if (removeButton.count() > 0) {
                    removeButton.click();
                    page.waitForTimeout(300);
                    log.info("已关闭深度思考模式");
                } else {
                    thinkingPill.click();
                    page.waitForTimeout(300);
                    log.info("已点击思考按钮关闭深度思考模式");
                }
            }
        } catch (Exception e) {
            log.error("关闭深度思考模式时出错: {}", e.getMessage());
        }
    }

    @Override
    public void monitorResponse(OpenAIContext context) throws IOException, InterruptedException {
        if ("sse".equalsIgnoreCase(context.monitorMode())) {
            log.info("开始监听 AI 回复（SSE 模式，普通对话）...");
            monitorResponseSse(context);
        } else {
            log.info("开始监听 AI 回复（DOM 模式，普通对话）...");
            monitorResponseDom(context);
        }
    }
    
    private void monitorResponseSse(OpenAIContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String id = UUID.randomUUID().toString();
        StringBuilder collectedText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        boolean finished = false;
        int noDataCount = 0;

        boolean doneDetected = false;
        int doneWaitCount = 0;
        
        // 用于调试：记录所有接收到的原始 SSE 数据
        SseDataLogger sseLogger = new SseDataLogger(request.getModel(), request);
        
        while (!finished) {
            try {
                if (page.isClosed()) break;

                String sseData = handler.getSseData(page, "__openaiSseData");
                
                if (sseData != null && !sseData.isEmpty()) {
                    // 重置等待计数，因为还有数据
                    noDataCount = 0;
                    if (doneDetected) {
                        doneWaitCount = 0; // 重置完成等待计数，因为还有新数据
                    }
                    
                    // 记录完整的原始 SSE 响应数据（用于调试）
                    sseLogger.logSseChunk(sseData);
                    
                    // 简化的 SSE 解析，只提取回复内容
                    log.info("读取到 SSE 数据，长度: {}", sseData.length());
                    String content = handler.extractTextFromSse(sseData);
                    if (content != null && !content.isEmpty()) {
                        collectedText.append(content);
                        handler.sendChunk(emitter, id, content, request.getModel());
                        log.info("发送内容块，长度: {}, 累计长度: {}", content.length(), collectedText.length());
                    } else {
                        log.info("本次读取未提取到内容");
                    }
                    
                    // 检测完成标记，但不立即结束，继续读取剩余数据
                    // 注意：即使检测到 [DONE]，也要继续读取，因为可能还有数据在缓冲区
                    if (sseData.contains("event: done") || sseData.contains("[DONE]")) {
                        if (!doneDetected) {
                            doneDetected = true;
                            log.info("检测到完成标记，继续读取剩余数据...");
                            log.info("检测到完成标记时的 SSE 数据长度: {}, 累计文本长度: {}", sseData.length(), collectedText.length());
                            // 检测到完成标记后，立即再尝试读取一次，确保当前批次的所有数据都被处理
                            String finalData = handler.getSseData(page, "__openaiSseData");
                            if (finalData != null && !finalData.isEmpty()) {
                                log.info("检测到完成标记后立即读取，数据长度: {}", finalData.length());
                                // 记录完成标记后的原始 SSE 数据
                                sseLogger.logSseChunk(finalData, "完成标记后立即读取");
                                String finalContent = handler.extractTextFromSse(finalData);
                                if (finalContent != null && !finalContent.isEmpty()) {
                                    collectedText.append(finalContent);
                                    handler.sendChunk(emitter, id, finalContent, request.getModel());
                                    log.info("检测到完成标记后立即提取到内容，长度: {}, 累计长度: {}", finalContent.length(), collectedText.length());
                                }
                            }
                        }
                        // 检测到 [DONE] 后，再读取一次确保没有遗漏
                        // 不立即结束，让循环继续处理
                    }
                } else {
                    noDataCount++;
                    
                    // 如果已检测到完成标记，检查是否还有数据在缓冲区
                    if (doneDetected) {
                        // 再次尝试读取，确保没有遗漏的数据
                        String remainingData = handler.getSseData(page, "__openaiSseData");
                        if (remainingData != null && !remainingData.isEmpty()) {
                            log.info("检测到完成标记后，读取到剩余数据，长度: {}", remainingData.length());
                            // 记录剩余数据的原始 SSE 响应
                            sseLogger.logSseChunk(remainingData, "剩余数据");
                            String content = handler.extractTextFromSse(remainingData);
                            if (content != null && !content.isEmpty()) {
                                collectedText.append(content);
                                handler.sendChunk(emitter, id, content, request.getModel());
                                log.info("检测到完成标记后，读取到额外内容，长度: {}, 累计长度: {}", content.length(), collectedText.length());
                                noDataCount = 0; // 重置计数
                                doneWaitCount = 0; // 重置等待计数
                                continue; // 继续循环
                            } else {
                                log.info("检测到完成标记后，剩余数据未提取到内容");
                            }
                        }
                        
                        doneWaitCount++;
                        // 等待最多 1 秒（10 次循环）确保所有数据都被读取
                        // 增加等待时间，因为网络延迟可能导致数据分批到达
                        if (doneWaitCount >= 10) {
                            finished = true;
                            log.info("SSE 流已完成（等待 {} 次后结束，共 {}ms），最终累计长度: {}", doneWaitCount, doneWaitCount * 100, collectedText.length());
                        }
                    } else if (noDataCount > 200) {
                        finished = true;
                        log.info("SSE 流已完成（无数据超时），最终累计长度: {}", collectedText.length());
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
                if (!collectedText.isEmpty()) break;
                throw e;
            }
        }

        // 记录完整的原始 SSE 响应数据汇总（用于调试）
        sseLogger.logSummary(collectedText.length());

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    private void monitorResponseDom(OpenAIContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        int messageCountBefore = context.messageCountBefore();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String responseSelector = "[data-message-author-role='assistant'] .markdown, [data-message-author-role='assistant'] [class*='markdown']";
        
        Locator responseLocators = page.locator(responseSelector);
        long waitStartTime = System.currentTimeMillis();
        while (responseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > 30000) {
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

        while (true) {
            try {
                if (page.isClosed()) break;

                try {
                    Locator allResponseLocators = page.locator(responseSelector);
                    int currentCount = allResponseLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        String currentText = findFinalResponseText(allResponseLocators, messageCountBefore, currentCount);
                        
                        if (currentText != null) {
                            if (currentText.length() > collectedText.length()) {
                                String delta = currentText.substring(collectedText.length());
                                collectedText.append(delta);
                                noChangeCount = 0;
                                handler.sendChunk(emitter, id, delta, request.getModel());
                            } else if (currentText.equals(lastFullText) && !currentText.isEmpty()) {
                                noChangeCount++;
                            }
                            lastFullText = currentText;
                        }
                    }
                } catch (Exception e) {
                    log.error("DOM 查询时出错: {}", e.getMessage());
                }

                if (noChangeCount >= 20) break;
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
    
    private String findFinalResponseText(Locator allResponseLocators, int messageCountBefore, int currentCount) {
        for (int i = currentCount - 1; i >= messageCountBefore; i--) {
            try {
                Locator locator = allResponseLocators.nth(i);
                // 使用 Playwright locator 检查父元素是否包含 reasoning 或 thinking 类
                // 如果找不到包含这些类的父元素，说明不是思考内容
                Locator thinkingParent = locator.locator("xpath=ancestor::*[contains(@class, 'reasoning') or contains(@class, 'thinking')]");
                boolean isInThinking = thinkingParent.count() > 0;
                
                if (!isInThinking) {
                    return locator.innerText();
                }
            } catch (Exception e) {
                // 继续查找
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


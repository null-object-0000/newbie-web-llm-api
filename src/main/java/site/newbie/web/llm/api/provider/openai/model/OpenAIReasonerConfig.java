package site.newbie.web.llm.api.provider.openai.model;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * OpenAI Reasoner 模型配置
 * 深度思考模式，启用深度思考并处理思考内容
 */
@Slf4j
@Component
public class OpenAIReasonerConfig implements OpenAIModelConfig {
    
    public static final String MODEL_NAME = "gpt-web-reasoner";
    
    private final ObjectMapper objectMapper;
    
    public OpenAIReasonerConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
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
            // 等待页面加载
            try {
                page.locator("div.ProseMirror[id='prompt-textarea']").waitFor();
            } catch (Exception e) {
                log.debug("等待输入框超时: {}", e.getMessage());
            }
            page.waitForTimeout(1500);
            
            // 检查是否已经激活
            boolean thinkingEnabled = false;
            try {
                Locator thinkingPill = page.locator("button.__composer-pill[aria-label*='思考']")
                        .or(page.locator("div[data-testid='composer-footer-actions'] button:has-text('思考')"));
                
                if (thinkingPill.count() > 0) {
                    log.info("深度思考模式已启用");
                    thinkingEnabled = true;
                }
            } catch (Exception e) {
                log.debug("检查思考模式状态时出错: {}", e.getMessage());
            }
            
            if (!thinkingEnabled) {
                int maxRetries = 3;
                for (int retry = 0; retry < maxRetries && !thinkingEnabled; retry++) {
                    if (retry > 0) {
                        log.info("重试启用深度思考模式 (第 {} 次)", retry + 1);
                        page.waitForTimeout(1000);
                    }
                    
                    try {
                        // 点击加号按钮
                        Locator plusButton = page.locator("button[data-testid='composer-plus-btn']")
                                .or(page.locator("button.composer-btn[aria-label*='添加']"));
                        
                        if (plusButton.count() > 0) {
                            plusButton.click();
                            page.waitForTimeout(500);
                            
                            // 选择"思考"选项
                            Locator thinkingMenuItem = page.locator("div[role='menuitemradio']:has-text('思考')")
                                    .or(page.locator("div[role='menuitemradio'] .truncate:has-text('思考')"));
                            
                            if (thinkingMenuItem.count() > 0) {
                                thinkingMenuItem.click();
                                page.waitForTimeout(800);
                                thinkingEnabled = true;
                                log.info("已启用深度思考模式");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Locator 方法失败: {}", e.getMessage());
                    }
                    
                    // JavaScript 备选方案
                    if (!thinkingEnabled) {
                        try {
                            String result = (String) page.evaluate("""
                                () => {
                                    // 检查是否已激活
                                    const footerActions = document.querySelector('[data-testid="composer-footer-actions"]');
                                    if (footerActions && footerActions.querySelector('button[aria-label*="思考"]')) {
                                        return 'already-active';
                                    }
                                    
                                    // 点击加号按钮
                                    const plusBtn = document.querySelector('button[data-testid="composer-plus-btn"]');
                                    if (!plusBtn) return 'plus-not-found';
                                    plusBtn.click();
                                    return 'clicked-plus';
                                }
                            """);
                            
                            if ("already-active".equals(result)) {
                                thinkingEnabled = true;
                            } else if ("clicked-plus".equals(result)) {
                                page.waitForTimeout(500);
                                
                                String menuResult = (String) page.evaluate("""
                                    () => {
                                        const menuItems = document.querySelectorAll('div[role="menuitemradio"]');
                                        for (const item of menuItems) {
                                            if (item.textContent.includes('思考') && 
                                                !item.textContent.includes('深度研究')) {
                                                item.click();
                                                return 'clicked';
                                            }
                                        }
                                        return 'not-found';
                                    }
                                """);
                                
                                if ("clicked".equals(menuResult)) {
                                    page.waitForTimeout(800);
                                    thinkingEnabled = true;
                                    log.info("通过 JavaScript 启用深度思考模式");
                                }
                            }
                        } catch (Exception e) {
                            log.debug("JavaScript 方法失败: {}", e.getMessage());
                        }
                    }
                }
            }
            
            if (!thinkingEnabled) {
                log.warn("无法启用深度思考模式");
            }
        } catch (Exception e) {
            log.warn("启用深度思考模式时出错: {}", e.getMessage());
        }
    }

    @Override
    public void monitorResponse(OpenAIContext context) throws IOException, InterruptedException {
        if ("sse".equalsIgnoreCase(context.monitorMode())) {
            log.info("开始监听 AI 回复（SSE 模式，深度思考）...");
            monitorResponseSse(context);
        } else {
            log.info("开始监听 AI 回复（DOM 模式，深度思考）...");
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
        StringBuilder collectedThinkingText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        boolean finished = false;
        int noDataCount = 0;

        boolean doneDetected = false;
        int doneWaitCount = 0;
        int maxDoneWaitCount = 15; // 检测到完成标记后，最多等待 3 秒（30 * 100ms）
        
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
                    
                    // 解析包含思考内容的 SSE
                    ModelConfig.SseParseResult result = extractContentFromSse(sseData);
                    
                    if (result.thinkingContent() != null && !result.thinkingContent().isEmpty()) {
                        collectedThinkingText.append(result.thinkingContent());
                        handler.sendThinking(emitter, id, result.thinkingContent(), request.getModel());
                    }
                    
                    if (result.responseContent() != null && !result.responseContent().isEmpty()) {
                        collectedText.append(result.responseContent());
                        handler.sendChunk(emitter, id, result.responseContent(), request.getModel());
                        log.trace("发送回复内容块，长度: {}, 累计长度: {}", result.responseContent().length(), collectedText.length());
                    }
                    
                    if (result.thinkingContent() != null && !result.thinkingContent().isEmpty()) {
                        log.trace("发送思考内容块，长度: {}, 累计长度: {}", result.thinkingContent().length(), collectedThinkingText.length());
                    }
                    
                    // 检测完成标记，但不立即结束，继续读取剩余数据
                    if (result.finished() || sseData.contains("event: done") || sseData.contains("[DONE]")) {
                        if (!doneDetected) {
                            doneDetected = true;
                            log.info("检测到完成标记，继续读取剩余数据...");
                        }
                    }
                } else {
                    noDataCount++;
                    
                    // 如果已检测到完成标记，等待一段时间确保所有数据都被读取
                    if (doneDetected) {
                        doneWaitCount++;
                        // 等待最多 3 秒（30 次循环）确保所有数据都被读取
                        if (doneWaitCount >= maxDoneWaitCount) {
                            finished = true;
                            log.info("SSE 流已完成（等待 {} 次后结束，共 {}ms）", maxDoneWaitCount, maxDoneWaitCount * 100);
                        }
                    } else if (noDataCount > 200) {
                        finished = true;
                        log.info("SSE 流已完成（无数据超时）");
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

        handler.sendUrlAndComplete(page, emitter, request);
    }

    private void monitorResponseDom(OpenAIContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        int messageCountBefore = context.messageCountBefore();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        String responseSelector = "[data-message-author-role='assistant'] .markdown, [data-message-author-role='assistant'] [class*='markdown']";
        String thinkingSelector = "[data-message-author-role='assistant'] [class*='reasoning'], [data-message-author-role='assistant'] [class*='thinking']";
        
        Locator responseLocators = page.locator(responseSelector);
        long waitStartTime = System.currentTimeMillis();
        while (responseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > 30000) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始 DOM 监听（深度思考模式）");
        
        StringBuilder collectedText = new StringBuilder();
        StringBuilder collectedThinkingText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String lastFullText = "";
        int noChangeCount = 0;

        while (true) {
            try {
                if (page.isClosed()) break;

                // 检查思考内容
                try {
                    Locator thinkingLocators = page.locator(thinkingSelector);
                    int thinkingCount = thinkingLocators.count();

                    if (thinkingCount > 0) {
                        String thinkingText = thinkingLocators.nth(thinkingCount - 1).innerText();
                        if (thinkingText.length() > collectedThinkingText.length()) {
                            String delta = thinkingText.substring(collectedThinkingText.length());
                            collectedThinkingText.append(delta);
                            handler.sendThinking(emitter, id, delta, request.getModel());
                        }
                    }
                } catch (Exception e) {
                    log.debug("读取思考内容时出错: {}", e.getMessage());
                }
                
                // 检查回复内容
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
                    log.debug("DOM 查询时出错: {}", e.getMessage());
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
                Boolean isInThinking = (Boolean) locator.evaluate("""
                    el => {
                        const reasoningContent = el.closest('[class*="reasoning"], [class*="thinking"]');
                        return reasoningContent !== null;
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
    
    private ModelConfig.SseParseResult extractContentFromSse(String sseData) {
        if (sseData == null) {
            return new ModelConfig.SseParseResult(null, null, false);
        }
        
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder responseText = new StringBuilder();
        boolean finished = false;
        
        String[] lines = sseData.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // 检测 event: done
            if (line.startsWith("event: ")) {
                String event = line.substring(7).trim();
                if ("done".equals(event)) {
                    finished = true;
                }
                continue;
            }
            
            // 只处理 data 行
            if (!line.startsWith("data: ")) {
                continue;
            }
            
            // 跳过完成标记
            if (line.contains("[DONE]")) {
                finished = true;
                continue;
            }
            
            String jsonStr = line.substring(6).trim();
            if (jsonStr.isEmpty() || jsonStr.equals("{}")) {
                continue;
            }
            
            try {
                var json = objectMapper.readTree(jsonStr);
                
                // 跳过有 type 字段的元数据
                if (json.has("type")) {
                    continue;
                }
                
                // 格式1: {"p": "/message/reasoning_content", "o": "append", "v": "思考内容"}
                // 或 {"p": "/message/content/parts/0", "o": "append", "v": "回复内容"}
                // 或 {"p": "/message/content/thoughts/0/content", "o": "append", "v": "思考内容"}
                // 或 {"p": "/message/content/thoughts", "o": "append", "v": [{"summary": "...", "content": "...", ...}]}
                if (json.has("p") && json.has("o") && json.has("v")) {
                    String path = json.get("p").asText();
                    String operation = json.get("o").asText();
                    
                    if (path != null && "append".equals(operation)) {
                        // 处理文本内容
                        if (json.get("v").isTextual()) {
                            String content = json.get("v").asText();
                            if (content != null && !content.isEmpty()) {
                                // 判断是思考内容还是回复内容
                                if (path.contains("/reasoning_content") || 
                                    path.contains("/reasoning") ||
                                    (path.contains("/thoughts/") && path.contains("/content"))) {
                                    thinkingText.append(content);
                                    log.trace("提取思考内容 (路径格式): {}", content);
                                } else if (path.contains("/message/content/parts/")) {
                                    responseText.append(content);
                                    log.trace("提取回复内容 (路径格式): {}", content);
                                }
                            }
                        }
                        // 处理思考内容数组初始化: {"p": "/message/content/thoughts", "o": "append", "v": [{"summary": "...", "content": "...", ...}]}
                        else if (json.get("v").isArray() && path.contains("/thoughts") && !path.contains("/content")) {
                            var thoughtsArray = json.get("v");
                            for (var thought : thoughtsArray) {
                                if (thought.has("content") && thought.get("content").isTextual()) {
                                    String thoughtContent = thought.get("content").asText();
                                    if (thoughtContent != null && !thoughtContent.isEmpty()) {
                                        thinkingText.append(thoughtContent);
                                        log.trace("提取思考内容 (thoughts数组初始化): {}", thoughtContent);
                                    }
                                }
                            }
                        }
                    }
                    continue;
                }
                
                // 格式2: {"v": "内容"} 或 {"v": [...]} 或 {"v": {对象}} - 不依赖 event（可能跨批次）
                if (json.has("v") && !json.has("p") && !json.has("o")) {
                    var vNode = json.get("v");
                    
                    // 如果是文本，通常是回复内容的增量更新
                    if (vNode.isTextual()) {
                        String content = vNode.asText();
                        if (content != null && !content.isEmpty()) {
                            // 简单格式的文本通常是回复内容
                            responseText.append(content);
                            log.trace("提取回复内容 (简单格式): {}", content);
                        }
                    }
                    // 如果是数组，可能是批量更新: {"v": [{"p": "/message/content/thoughts/0/content", "o": "append", "v": "说"}, ...]}
                    else if (vNode.isArray()) {
                        for (var item : vNode) {
                            if (item.has("p") && item.has("o") && item.has("v")) {
                                String itemPath = item.get("p").asText();
                                String itemOp = item.get("o").asText();
                                
                                if (itemPath != null && "append".equals(itemOp)) {
                                    if (item.get("v").isTextual()) {
                                        String content = item.get("v").asText();
                                        if (content != null && !content.isEmpty()) {
                                            // 判断是思考内容还是回复内容
                                            if (itemPath.contains("/thoughts/") && itemPath.contains("/content")) {
                                                thinkingText.append(content);
                                                log.trace("提取思考内容 (批量数组格式): {}", content);
                                            } else if (itemPath.contains("/message/content/parts/")) {
                                                responseText.append(content);
                                                log.trace("提取回复内容 (批量数组格式): {}", content);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 如果是对象，可能是消息对象
                    else if (vNode.isObject()) {
                        // 检查是否是思考消息: {"v": {"message": {"content": {"content_type": "thoughts", "thoughts": [...]}}}}
                        if (vNode.has("message")) {
                            var message = vNode.get("message");
                            if (message.has("content")) {
                                var content = message.get("content");
                                
                                // 处理思考内容: content_type = "thoughts"
                                if (content.has("content_type") && "thoughts".equals(content.get("content_type").asText())) {
                                    if (content.has("thoughts") && content.get("thoughts").isArray()) {
                                        var thoughtsArray = content.get("thoughts");
                                        for (var thought : thoughtsArray) {
                                            if (thought.has("content") && thought.get("content").isTextual()) {
                                                String thoughtContent = thought.get("content").asText();
                                                if (thoughtContent != null && !thoughtContent.isEmpty()) {
                                                    thinkingText.append(thoughtContent);
                                                    log.trace("提取思考内容 (thoughts数组): {}", thoughtContent);
                                                }
                                            }
                                        }
                                    } else {
                                        // thoughts 数组为空，这是正常的，表示没有思考内容
                                        log.trace("检测到思考消息，但 thoughts 数组为空");
                                    }
                                }
                                // 处理思考总结: content_type = "reasoning_recap" (可以忽略，只是状态信息)
                                else if (content.has("content_type") && "reasoning_recap".equals(content.get("content_type").asText())) {
                                    // 这是思考总结，通常显示"已思考若干秒"，不需要发送
                                    log.trace("检测到思考总结: {}", content.has("content") ? content.get("content").asText() : "");
                                }
                            }
                        }
                    }
                    continue;
                }
                
                // 格式3: {"p": "", "o": "patch", "v": [...]} - 批量更新
                if (json.has("p") && json.has("o") && "patch".equals(json.get("o").asText())) {
                    if (json.has("v") && json.get("v").isArray()) {
                        var patchArray = json.get("v");
                        for (var patchItem : patchArray) {
                            if (patchItem.has("p") && patchItem.has("o") && patchItem.has("v")) {
                                String itemPath = patchItem.get("p").asText();
                                String itemOp = patchItem.get("o").asText();
                                
                                if (itemPath != null && "append".equals(itemOp)) {
                                    if (patchItem.get("v").isTextual()) {
                                        String content = patchItem.get("v").asText();
                                        if (content != null && !content.isEmpty()) {
                                            if (itemPath.contains("/reasoning_content") || 
                                                itemPath.contains("/reasoning") ||
                                                (itemPath.contains("/thoughts/") && itemPath.contains("/content"))) {
                                                thinkingText.append(content);
                                                log.trace("提取思考内容 (patch格式): {}", content);
                                            } else if (itemPath.contains("/message/content/parts/")) {
                                                responseText.append(content);
                                                log.trace("提取回复内容 (patch格式): {}", content);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    continue;
                }
            } catch (Exception e) {
                log.debug("解析 SSE 数据行失败: {}", e.getMessage());
            }
        }
        
        if (log.isDebugEnabled() && (thinkingText.length() > 0 || responseText.length() > 0)) {
            log.debug("SSE 解析结果: thinking={} chars, response={} chars, finished={}", 
                    thinkingText.length(), responseText.length(), finished);
        }
        
        return new ModelConfig.SseParseResult(
                thinkingText.length() > 0 ? thinkingText.toString() : null,
                responseText.length() > 0 ? responseText.toString() : null,
                finished
        );
    }
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
}


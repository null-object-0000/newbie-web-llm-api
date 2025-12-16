package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 提供者基类，提供通用的 SSE 发送方法和 SSE 监听能力
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseProvider implements LLMProvider {
    
    protected final ObjectMapper objectMapper;
    
    /**
     * 发送 SSE 数据块
     */
    protected void sendSseChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder()
                        .content(content)
                        .build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送整体替换消息（用于修正完整内容）
     * 使用特殊标记 __REPLACE__ 来标识这是整体替换消息
     */
    protected void sendSseReplace(SseEmitter emitter, String id, String fullContent, String model) throws IOException {
        // 在内容前添加特殊标记，前端识别后会替换整个内容
        String markedContent = "__REPLACE__" + fullContent;
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder()
                        .content(markedContent)
                        .build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送思考内容（用于深度思考模式）
     * 使用 OpenAI API 兼容格式：通过 delta.reasoning_content 字段返回思考内容
     */
    protected void sendThinkingContent(SseEmitter emitter, String id, String thinkingContent, String model) throws IOException {
        ChatCompletionResponse.Delta delta = ChatCompletionResponse.Delta.builder()
                .reasoningContent(thinkingContent)
                .build();
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(delta)
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送完成标记
     */
    protected void sendDone(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }
    
    /**
     * 发送对话 URL（用于前端保存）
     * 使用特殊标记 __CONVERSATION_URL_START__ 和 __CONVERSATION_URL_END__ 来标识这是 URL 信息
     * 新起两行输出，避免与普通内容冲突
     */
    protected void sendConversationUrl(SseEmitter emitter, String id, String url, String model) throws IOException {
        // 使用更特殊的标记，新起两行输出
        // 第一行：开始标记
        String startMarker = "\n\n__CONVERSATION_URL_START__\n";
        // 第二行：URL 内容
        String urlContent = url + "\n";
        // 第三行：结束标记
        String endMarker = "__CONVERSATION_URL_END__\n\n";
        
        // 分三次发送，确保格式清晰
        // 发送开始标记
        ChatCompletionResponse.Choice choice1 = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder()
                        .content(startMarker)
                        .build())
                .index(0)
                .build();
        ChatCompletionResponse response1 = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice1))
                .build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response1)));
        
        // 发送 URL 内容
        ChatCompletionResponse.Choice choice2 = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder()
                        .content(urlContent)
                        .build())
                .index(0)
                .build();
        ChatCompletionResponse response2 = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice2))
                .build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response2)));
        
        // 发送结束标记
        ChatCompletionResponse.Choice choice3 = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder()
                        .content(endMarker)
                        .build())
                .index(0)
                .build();
        ChatCompletionResponse response3 = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice3))
                .build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response3)));
    }
    
    /**
     * 从历史对话中提取 URL（检查最后一条 assistant 消息是否包含 URL 标记）
     * 这是一个公共能力，所有提供者都可以使用
     * 
     * @param request 聊天请求，包含历史消息
     * @param domainPattern 域名模式，用于验证 URL（例如 "chat.deepseek.com"），如果为 null 则不验证域名
     * @return 提取到的 URL，如果没有找到则返回 null
     */
    protected String extractUrlFromHistory(ChatCompletionRequest request, String domainPattern) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return null;
        }
        
        // 从后往前查找最后一条 assistant 消息
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                
                // 检查新的 URL 标记格式：__CONVERSATION_URL_START__ 和 __CONVERSATION_URL_END__
                int startIdx = content.indexOf("__CONVERSATION_URL_START__");
                int endIdx = content.indexOf("__CONVERSATION_URL_END__");
                if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                    // 提取开始标记和结束标记之间的内容
                    String urlContent = content.substring(startIdx + "__CONVERSATION_URL_START__".length(), endIdx);
                    // 按行分割，过滤掉空行和包含标记的行，取第一行作为 URL
                    String url = urlContent.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.contains("__CONVERSATION_URL"))
                            .findFirst()
                            .orElse("")
                            .trim();
                    if (!url.isEmpty()) {
                        // 如果指定了域名模式，验证 URL 是否包含该域名
                        if (domainPattern == null || url.contains(domainPattern)) {
                            return url;
                        }
                    }
                }
                
                // 向后兼容：检查旧的 __URL__ 标记格式
                if (content.contains("__URL__")) {
                    int urlStartIdx = content.indexOf("__URL__");
                    if (urlStartIdx != -1) {
                        // 提取 __URL__ 后面的内容，取第一行作为 URL
                        String urlContent = content.substring(urlStartIdx + "__URL__".length());
                        String url = urlContent.lines()
                                .map(String::trim)
                                .filter(line -> !line.isEmpty())
                                .findFirst()
                                .orElse("")
                                .trim();
                        if (!url.isEmpty()) {
                            // 如果指定了域名模式，验证 URL 是否包含该域名
                            if (domainPattern == null || url.contains(domainPattern)) {
                                return url;
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * SSE 拦截器配置
     */
    protected static class SseInterceptorConfig {
        private final String dataVarName;        // 数据存储变量名（如 "__deepseekSseData"）
        private final String interceptorVarName; // 拦截器标记变量名（如 "__deepseekSseInterceptorSet"）
        private final String[] urlPatterns;      // URL 匹配模式数组
        private final boolean interceptXHR;      // 是否拦截 XMLHttpRequest
        
        public SseInterceptorConfig(String dataVarName, String interceptorVarName, String[] urlPatterns, boolean interceptXHR) {
            this.dataVarName = dataVarName;
            this.interceptorVarName = interceptorVarName;
            this.urlPatterns = urlPatterns;
            this.interceptXHR = interceptXHR;
        }
        
        public String getDataVarName() {
            return dataVarName;
        }
        
        public String getInterceptorVarName() {
            return interceptorVarName;
        }
        
        public String[] getUrlPatterns() {
            return urlPatterns;
        }
        
        public boolean isInterceptXHR() {
            return interceptXHR;
        }
    }
    
    /**
     * 设置 SSE 拦截器（通用方法）
     * 
     * @param page 页面对象
     * @param config SSE 拦截器配置
     */
    protected void setupSseInterceptor(Page page, SseInterceptorConfig config) {
        // 初始化全局变量
        try {
            page.evaluate(String.format("""
                        () => {
                            if (!window.%s) {
                                window.%s = [];
                            }
                        }
                    """, config.getDataVarName(), config.getDataVarName()));
        } catch (Exception e) {
            log.debug("初始化 SSE 数据存储失败: {}", e.getMessage());
        }

        // 构建 URL 匹配条件
        StringBuilder urlCondition = new StringBuilder();
        for (int i = 0; i < config.getUrlPatterns().length; i++) {
            if (i > 0) {
                urlCondition.append(" || ");
            }
            urlCondition.append("url.includes('").append(config.getUrlPatterns()[i]).append("')");
        }
        String urlConditionStr = urlCondition.toString();
        
        // 构建拦截器 JavaScript 代码
        StringBuilder jsCode = new StringBuilder();
        jsCode.append("(function() {");
        jsCode.append("    if (window.").append(config.getInterceptorVarName()).append(") { return; }");
        jsCode.append("    window.").append(config.getInterceptorVarName()).append(" = true;");
        jsCode.append("    const originalEventSource = window.EventSource;");
        jsCode.append("    window.EventSource = function(url, eventSourceInitDict) {");
        jsCode.append("        console.log('[SSE Interceptor] EventSource created:', url);");
        jsCode.append("        const es = new originalEventSource(url, eventSourceInitDict);");
        jsCode.append("        if (").append(urlConditionStr).append(") {");
        jsCode.append("            console.log('[SSE Interceptor] Intercepting EventSource:', url);");
        jsCode.append("            es.addEventListener('message', function(event) {");
        jsCode.append("                console.log('[SSE Interceptor] EventSource message:', event.data);");
        jsCode.append("                window.").append(config.getDataVarName()).append(" = window.").append(config.getDataVarName()).append(" || [];");
        jsCode.append("                window.").append(config.getDataVarName()).append(".push(event.data);");
        jsCode.append("            });");
        jsCode.append("            es.addEventListener('error', function(event) {");
        jsCode.append("                console.log('[SSE Interceptor] EventSource error:', event);");
        jsCode.append("            });");
        jsCode.append("        }");
        jsCode.append("        return es;");
        jsCode.append("    };");
        jsCode.append("    const originalFetch = window.fetch;");
        jsCode.append("    window.fetch = function(...args) {");
        jsCode.append("        const url = args[0];");
        jsCode.append("        if (typeof url === 'string' && (").append(urlConditionStr).append(")) {");
        jsCode.append("            console.log('[SSE Interceptor] Intercepting fetch:', url);");
        jsCode.append("            return originalFetch.apply(this, args).then(response => {");
        jsCode.append("                const contentType = response.headers.get('content-type');");
        jsCode.append("                if (contentType && contentType.includes('text/event-stream')) {");
        jsCode.append("                    console.log('[SSE Interceptor] Detected SSE response');");
        jsCode.append("                    const clonedResponse = response.clone();");
        jsCode.append("                    const reader = clonedResponse.body.getReader();");
        jsCode.append("                    const decoder = new TextDecoder();");
        jsCode.append("                    window.").append(config.getDataVarName()).append(" = window.").append(config.getDataVarName()).append(" || [];");
        jsCode.append("                    function readStream() {");
        jsCode.append("                        reader.read().then(({ done, value }) => {");
        jsCode.append("                            if (done) { return; }");
        jsCode.append("                            const chunk = decoder.decode(value, { stream: true });");
        jsCode.append("                            window.").append(config.getDataVarName()).append(".push(chunk);");
        jsCode.append("                            readStream();");
        jsCode.append("                        }).catch(err => {");
        jsCode.append("                            console.error('[SSE Interceptor] Read error:', err);");
        jsCode.append("                        });");
        jsCode.append("                    }");
        jsCode.append("                    readStream();");
        jsCode.append("                }");
        jsCode.append("                return response;");
        jsCode.append("            });");
        jsCode.append("        }");
        jsCode.append("        return originalFetch.apply(this, args);");
        jsCode.append("    };");
        
        // 如果需要拦截 XMLHttpRequest
        if (config.isInterceptXHR()) {
            jsCode.append("    const originalXHROpen = XMLHttpRequest.prototype.open;");
            jsCode.append("    const originalXHRSend = XMLHttpRequest.prototype.send;");
            jsCode.append("    XMLHttpRequest.prototype.open = function(method, url, ...rest) {");
            jsCode.append("        this._interceptedUrl = url;");
            jsCode.append("        return originalXHROpen.apply(this, [method, url, ...rest]);");
            jsCode.append("    };");
            jsCode.append("    XMLHttpRequest.prototype.send = function(...args) {");
            jsCode.append("        if (this._interceptedUrl && (").append(urlConditionStr.replace("url", "this._interceptedUrl")).append(")) {");
            jsCode.append("            console.log('[SSE Interceptor] Intercepting XMLHttpRequest:', this._interceptedUrl);");
            jsCode.append("            const originalOnReadyStateChange = this.onreadystatechange;");
            jsCode.append("            this.onreadystatechange = function() {");
            jsCode.append("                if (this.readyState === 3 || this.readyState === 4) {");
            jsCode.append("                    const responseText = this.responseText;");
            jsCode.append("                    if (responseText) {");
            jsCode.append("                        const contentType = this.getResponseHeader('content-type');");
            jsCode.append("                        if (contentType && contentType.includes('text/event-stream')) {");
            jsCode.append("                            window.").append(config.getDataVarName()).append(" = window.").append(config.getDataVarName()).append(" || [];");
            jsCode.append("                            if (this._lastResponseLength === undefined) {");
            jsCode.append("                                this._lastResponseLength = 0;");
            jsCode.append("                            }");
            jsCode.append("                            if (responseText.length > this._lastResponseLength) {");
            jsCode.append("                                const newData = responseText.substring(this._lastResponseLength);");
            jsCode.append("                                window.").append(config.getDataVarName()).append(".push(newData);");
            jsCode.append("                                this._lastResponseLength = responseText.length;");
            jsCode.append("                            }");
            jsCode.append("                        }");
            jsCode.append("                    }");
            jsCode.append("                }");
            jsCode.append("                if (originalOnReadyStateChange) {");
            jsCode.append("                    originalOnReadyStateChange.apply(this, arguments);");
            jsCode.append("                }");
            jsCode.append("            };");
            jsCode.append("        }");
            jsCode.append("        return originalXHRSend.apply(this, args);");
            jsCode.append("    };");
        }
        
        jsCode.append("    console.log('[SSE Interceptor] Interceptor setup complete');");
        jsCode.append("})();");

        // 注入 JavaScript 来拦截 EventSource 和 fetch 的 SSE 响应
        try {
            page.evaluate(jsCode.toString());
            log.info("已设置 SSE 拦截器（通过 JavaScript 注入）");
        } catch (Exception e) {
            log.error("设置 SSE 拦截器失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从页面获取 SSE 数据（通用方法）
     * 
     * @param page 页面对象
     * @param dataVarName 数据存储变量名（如 "__deepseekSseData"）
     * @return SSE 数据字符串，如果没有数据则返回 null
     */
    protected String getSseDataFromPage(Page page, String dataVarName) {
        try {
            if (page.isClosed()) {
                return null;
            }

            Object result = page.evaluate(String.format("""
                        () => {
                            if (window.%s && window.%s.length > 0) {
                                const data = window.%s.join('\\n');
                                window.%s = []; // 清空已读取的数据
                                console.log('[SSE Interceptor] Returning data, length:', data.length);
                                return data;
                            }
                            return null;
                        }
                    """, dataVarName, dataVarName, dataVarName, dataVarName));

            if (result != null) {
                String data = result.toString();
                if (!data.isEmpty()) {
                    log.debug("从页面获取到 SSE 数据，长度: {}", data.length());
                }
                return data;
            }
            return null;
        } catch (Exception e) {
            // 如果是页面关闭错误，不记录日志
            if (e.getMessage() == null || 
                (!e.getMessage().contains("Target closed") && !e.getMessage().contains("Session closed"))) {
                log.debug("获取 SSE 数据失败: {}", e.getMessage());
            }
            return null;
        }
    }
}


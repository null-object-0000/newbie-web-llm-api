package site.newbie.web.llm.api.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ModelResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.ProviderRegistry;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "*")
public class OpenAiController {

    private final ProviderRegistry providerRegistry;

    public OpenAiController(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @PostMapping(value = "/chat/completions", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object chat(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Web-Search", required = false) String webSearchHeader,
            @RequestHeader(value = "X-Conversation-URL", required = false) String conversationUrlHeader) {
        try {
            // 1. 简单的参数校验
            if (request == null) {
                System.err.println("请求体为 null");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Request body is required", "type", "invalid_request_error")));
            }
            
            if (request.getMessages() == null || request.getMessages().isEmpty()) {
                System.err.println("消息列表为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Message list cannot be empty", "type", "invalid_request_error")));
            }

            if (request.getModel() == null || request.getModel().isEmpty()) {
                System.err.println("模型名称为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Model is required", "type", "invalid_request_error")));
            }

            // 2. 从 Header 读取 webSearch（如果请求体中没有设置）
            // 注意：是否新对话现在完全根据是否有 conversationUrl 来判断，不再接收外部参数
            if (webSearchHeader != null && !webSearchHeader.isEmpty()) {
                request.setWebSearch(Boolean.parseBoolean(webSearchHeader));
            }
            
            // 3. 从 Header 读取 conversationUrl（如果请求体中没有设置）
            if (conversationUrlHeader != null && !conversationUrlHeader.isEmpty()) {
                request.setConversationUrl(conversationUrlHeader);
            }

            // 注意：
            // - 深度思考模式现在完全根据模型名称自动判断，不再接收外部参数
            // - 是否新对话现在完全根据是否有 conversationUrl 来判断，不再接收外部参数
            boolean isNewConversation = (request.getConversationUrl() == null || request.getConversationUrl().isEmpty());
            log.info("收到请求: 模型=" + request.getModel() + ", 新对话=" + isNewConversation +
                    ", 联网搜索=" + request.isWebSearch() +
                    ", 对话URL=" + request.getConversationUrl() + ", 消息数=" + request.getMessages().size());
        } catch (Exception e) {
            System.err.println("解析请求时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of("message", "Failed to parse request: " + e.getMessage(), "type", "invalid_request_error")));
        }

        // 3. 根据模型名称获取对应的提供者
        LLMProvider provider = providerRegistry.getProviderByModel(request.getModel());
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of("message", "Unsupported model: " + request.getModel(), "type", "invalid_request_error")));
        }

        // 4. 判断是流式 (Stream) 还是 普通请求
        if (request.isStream()) {
            return handleStreamRequest(request, provider);
        } else {
            return handleNormalRequest(request);
        }
    }

    // 处理流式请求 (SSE)
    private Object handleStreamRequest(ChatCompletionRequest request, LLMProvider provider) {
        String providerName = provider.getProviderName();
        
        // 尝试获取锁
        if (!providerRegistry.tryAcquireLock(providerName)) {
            log.warn("提供器 {} 正忙，拒绝请求", providerName);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", Map.of(
                            "message", providerName + " 提供器正忙，请等待当前对话完成后再试",
                            "type", "provider_busy_error"
                    )));
        }
        
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        
        // 设置响应头
        emitter.onError((ex) -> {
            System.err.println("SSE Error: " + ex.getMessage());
            ex.printStackTrace();
            providerRegistry.releaseLock(providerName);
        });
        
        emitter.onTimeout(() -> {
            System.err.println("SSE Timeout");
            emitter.complete();
            providerRegistry.releaseLock(providerName);
        });
        
        emitter.onCompletion(() -> {
            System.out.println("SSE Completed");
            providerRegistry.releaseLock(providerName);
        });
        
        // 调用提供者处理请求
        provider.streamChat(request, emitter);
        
        return emitter;
    }
    
    /**
     * 获取所有可用的提供者和模型
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(providerRegistry.getAllProviders());
    }
    
    /**
     * 获取所有可用的模型列表（OpenAI 兼容格式）
     * 前端可以使用 OpenAI SDK: openai.models.list()
     */
    @GetMapping("/models")
    public ResponseEntity<ModelResponse> getModels() {
        return ResponseEntity.ok(providerRegistry.getOpenAIModels());
    }

    // 处理普通请求
    private Map<String, Object> handleNormalRequest(ChatCompletionRequest request) {
        // TODO: 暂时返回一个 Mock 数据测试接口通不通
        return Map.of(
                "id", "mock-id",
                "choices", java.util.List.of(
                        Map.of("message", Map.of("role", "assistant", "content", "这是测试回复，接口已打通！"))
                )
        );
    }
}
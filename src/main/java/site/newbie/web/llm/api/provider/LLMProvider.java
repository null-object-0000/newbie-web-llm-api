package site.newbie.web.llm.api.provider;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;

import java.util.List;

/**
 * LLM 提供者接口
 * 所有 LLM 提供者都需要实现此接口
 */
public interface LLMProvider {
    
    /**
     * 获取提供者名称
     */
    String getProviderName();
    
    /**
     * 获取该提供者支持的所有模型列表
     */
    List<String> getSupportedModels();
    
    /**
     * 检查是否支持指定的模型
     */
    default boolean supportsModel(String model) {
        return getSupportedModels().contains(model);
    }
    
    /**
     * 处理流式聊天请求
     * @param request 聊天请求
     * @param emitter SSE 发射器，用于发送流式数据
     */
    void streamChat(ChatCompletionRequest request, SseEmitter emitter);
}


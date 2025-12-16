package site.newbie.web.llm.api.provider.openai.model;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;

/**
 * OpenAI 模型配置接口
 * 继承通用 ModelConfig，定义 OpenAI 特定的上下文类型
 */
public interface OpenAIModelConfig extends ModelConfig<OpenAIModelConfig.OpenAIContext> {
    
    /**
     * OpenAI 监听上下文
     */
    record OpenAIContext(
            Page page,
            SseEmitter emitter,
            ChatCompletionRequest request,
            int messageCountBefore,
            String monitorMode,
            ResponseHandler responseHandler
    ) {}
}


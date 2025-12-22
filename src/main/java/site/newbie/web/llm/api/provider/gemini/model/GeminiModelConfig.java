package site.newbie.web.llm.api.provider.gemini.model;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;

/**
 * Gemini 模型配置接口
 * 继承通用 ModelConfig，定义 Gemini 特定的上下文类型
 */
public interface GeminiModelConfig extends ModelConfig<GeminiModelConfig.GeminiContext> {
    
    /**
     * Gemini 监听上下文
     */
    record GeminiContext(
            Page page,
            SseEmitter emitter,
            ChatCompletionRequest request,
            int messageCountBefore,
            String monitorMode,
            ModelConfig.ResponseHandler responseHandler
    ) {}
}


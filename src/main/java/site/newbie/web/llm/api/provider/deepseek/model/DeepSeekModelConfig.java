package site.newbie.web.llm.api.provider.deepseek.model;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;

/**
 * DeepSeek 模型配置接口
 * 继承通用 ModelConfig，定义 DeepSeek 特定的上下文类型
 */
public interface DeepSeekModelConfig extends ModelConfig<DeepSeekModelConfig.DeepSeekContext> {
    
    /**
     * DeepSeek 监听上下文
     */
    record DeepSeekContext(
            Page page,
            SseEmitter emitter,
            ChatCompletionRequest request,
            int messageCountBefore,
            String monitorMode,
            ModelConfig.ResponseHandler responseHandler
    ) {}
}

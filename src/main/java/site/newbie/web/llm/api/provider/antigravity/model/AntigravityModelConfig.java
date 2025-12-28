package site.newbie.web.llm.api.provider.antigravity.model;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.antigravity.core.ProjectResolver;
import site.newbie.web.llm.api.provider.antigravity.core.RequestMapper;
import site.newbie.web.llm.api.provider.antigravity.core.ResponseMapper;
import site.newbie.web.llm.api.provider.antigravity.core.TokenManager;
import site.newbie.web.llm.api.provider.antigravity.core.UpstreamClient;

/**
 * Antigravity 模型配置接口
 * 继承通用 ModelConfig，定义 Antigravity 特定的上下文类型
 */
public interface AntigravityModelConfig extends ModelConfig<AntigravityModelConfig.AntigravityContext> {
    
    /**
     * Antigravity 监听上下文
     */
    record AntigravityContext(
            SseEmitter emitter,
            ChatCompletionRequest request,
            TokenManager tokenManager,
            ProjectResolver projectResolver,
            UpstreamClient upstreamClient,
            RequestMapper requestMapper,
            ResponseMapper responseMapper,
            ModelConfig.ResponseHandler responseHandler
    ) {}
}


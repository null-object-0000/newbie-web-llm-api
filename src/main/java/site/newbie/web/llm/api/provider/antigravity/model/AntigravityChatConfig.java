package site.newbie.web.llm.api.provider.antigravity.model;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.antigravity.core.ProjectResolver;
import site.newbie.web.llm.api.provider.antigravity.core.RequestMapper;
import site.newbie.web.llm.api.provider.antigravity.core.ResponseMapper;
import site.newbie.web.llm.api.provider.antigravity.core.TokenManager;
import site.newbie.web.llm.api.provider.antigravity.core.UpstreamClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Antigravity Chat 模型配置
 * 直接调用 Google v1internal API
 */
@Slf4j
@Component
public class AntigravityChatConfig implements AntigravityModelConfig {
    
    public static final String MODEL_NAME = "antigravity-chat";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
    
    @Override
    public void configure(Page page) {
        // Antigravity 不需要页面配置
        log.info("配置 {} 模型：无需页面配置", MODEL_NAME);
    }

    @Override
    public void monitorResponse(AntigravityContext context) throws IOException, InterruptedException {
        log.info("开始调用 Google v1internal API...");
        
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        TokenManager tokenManager = context.tokenManager();
        ProjectResolver projectResolver = context.projectResolver();
        UpstreamClient upstreamClient = context.upstreamClient();
        RequestMapper requestMapper = context.requestMapper();
        ResponseMapper responseMapper = context.responseMapper();
        
        try {
            // 1. 获取 Token 和 Project ID
            TokenManager.TokenResult tokenResult = tokenManager.getToken("agent", false);
            String accessToken = tokenResult.getAccessToken();
            String projectId = tokenResult.getProjectId();
            String email = tokenResult.getEmail();
            
            // 如果 project_id 为空，尝试获取
            if (projectId == null || projectId.isEmpty()) {
                log.info("账号 {} 缺少 project_id，尝试获取...", email);
                projectId = projectResolver.fetchProjectId(accessToken);
                // TODO: 保存 project_id 到账号文件
            }
            
            log.info("使用账号: {} (project_id: {})", email, projectId);
            
            // 2. 映射模型名称
            String mappedModel = mapModelName(request.getModel());
            
            // 3. 转换请求格式
            JsonNode geminiBody = requestMapper.transformOpenAIRequest(request, projectId, mappedModel);
            log.debug("转换后的请求体: {}", objectMapper.writeValueAsString(geminiBody));
            
            // 4. 调用 v1internal API
            String method = "streamGenerateContent";
            String queryString = "alt=sse";
            
            var response = upstreamClient.callV1Internal(method, accessToken, geminiBody, queryString);
            
            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("v1internal API 返回错误: status={}, body={}", response.statusCode(), errorBody);
                throw new RuntimeException("v1internal API 错误: " + response.statusCode() + " - " + errorBody);
            }
            
            // 5. 处理流式响应
            String id = UUID.randomUUID().toString();
            ResponseMapper.ResponseHandler responseHandler = new ResponseMapper.ResponseHandler() {
                @Override
                public void onChunk(String chunkId, String content, String model) {
                    try {
                        handler.sendChunk(emitter, chunkId, content, model);
                    } catch (IOException e) {
                        log.error("发送 SSE chunk 时出错", e);
                    }
                }
                
                @Override
                public void onFinish(String finishReason) {
                    // 完成标记会在 ResponseMapper 中处理
                }
            };
            
            try {
                responseMapper.processStreamResponse(response.body(), request.getModel(), responseHandler);
            } catch (IOException | InterruptedException e) {
                // 重新抛出已声明的异常
                throw e;
            } catch (Exception e) {
                // 将其他异常包装为 IOException
                throw new IOException("处理流式响应时出错", e);
            }
            
            log.info("流式响应处理完成");
            
        } catch (IOException | InterruptedException e) {
            log.error("调用 v1internal API 时出错", e);
            throw e;
        } catch (Exception e) {
            log.error("调用 v1internal API 时出错", e);
            throw new IOException("调用 v1internal API 时出错", e);
        }
        
        // 完成响应
        handler.sendUrlAndComplete(null, emitter, request);
    }
    
    /**
     * 映射模型名称
     */
    private String mapModelName(String model) {
        if (model != null && (model.startsWith("gemini-") || model.startsWith("claude-"))) {
            return model;
        }
        
        if (MODEL_NAME.equals(model)) {
            return "gemini-3-flash";
        }
        
        return model != null ? model : "gemini-3-flash";
    }
}


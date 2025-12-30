package site.newbie.web.llm.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import site.newbie.web.llm.api.manager.ApiKeyManager;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * API Key 验证拦截器
 * 为所有 /v1/** 接口添加 API key 验证
 */
@Slf4j
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    @Autowired
    private ApiKeyManager apiKeyManager;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 确保清理之前的上下文（防止线程复用导致的问题）
        ApiKeyScopedValue.clear();
        
        String authorizationHeader = request.getHeader("Authorization");
        String apiKey = extractApiKey(authorizationHeader);
        
        if (apiKey == null) {
            log.warn("API key 缺失: {}", request.getRequestURI());
            sendUnauthorizedResponse(response, "Invalid API Key");
            return false;
        }
        
        // 验证 API key 并获取支持的 providers
        Set<String> supportedProviders = getSupportedProviders(apiKey);
        if (supportedProviders.isEmpty()) {
            log.warn("无效的 API key: {}", request.getRequestURI());
            sendUnauthorizedResponse(response, "Invalid API Key");
            return false;
        }
        
        // 将 API key 信息存储到 ScopedValue，供后续业务流程使用
        ApiKeyContext context = new ApiKeyContext(apiKey, supportedProviders);
        ApiKeyScopedValue.set(context);
        
        log.debug("API key 验证通过: {}, 支持的 providers: {}", request.getRequestURI(), supportedProviders);
        return true;
    }

    /**
     * 从 Authorization header 中提取 API key
     */
    private String extractApiKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return null;
        }
        
        // 支持 "Bearer sk-xxx" 或 "sk-xxx" 格式
        String apiKey = authorizationHeader;
        if (authorizationHeader.startsWith("Bearer ")) {
            apiKey = authorizationHeader.substring(7).trim();
        }
        
        if (apiKey.startsWith("sk-")) {
            return apiKey;
        }
        
        return null;
    }

    /**
     * 获取 API key 支持的所有 providers
     * @param apiKey API key
     * @return 支持的 providers 集合，如果无效则返回空集合
     */
    private Set<String> getSupportedProviders(String apiKey) {
        Set<String> supportedProviders = new HashSet<>();
        String[] providers = {"deepseek", "gemini", "openai"};
        for (String provider : providers) {
            if (apiKeyManager.supportsProvider(apiKey, provider)) {
                String accountId = apiKeyManager.getAccountIdByApiKey(apiKey, provider);
                if (accountId != null) {
                    supportedProviders.add(provider);
                }
            }
        }
        return supportedProviders;
    }

    /**
     * 发送未授权响应
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("message", message);
        errorMap.put("param", "Please provide valid API Key, Format: Authorization: Bearer <your-api-key> or api-key: <your-api-key>");
        errorMap.put("code", "401");
        errorMap.put("type", "invalid_key");
        
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("error", errorMap);
        
        response.getWriter().write(objectMapper.writeValueAsString(responseMap));
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求处理完成后清理 ThreadLocal，避免内存泄漏
        ApiKeyScopedValue.clear();
    }
}

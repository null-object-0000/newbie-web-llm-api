package site.newbie.web.llm.api.config;

import java.util.Set;

/**
 * API Key 上下文信息
 * 存储当前请求的 API key 及其支持的 providers
 */
public class ApiKeyContext {
    private final String apiKey;
    private final Set<String> supportedProviders;
    
    public ApiKeyContext(String apiKey, Set<String> supportedProviders) {
        this.apiKey = apiKey;
        this.supportedProviders = supportedProviders;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public Set<String> getSupportedProviders() {
        return supportedProviders;
    }
    
    public boolean supportsProvider(String providerName) {
        return supportedProviders.contains(providerName);
    }
}


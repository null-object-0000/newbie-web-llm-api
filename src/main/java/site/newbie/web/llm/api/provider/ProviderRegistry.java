package site.newbie.web.llm.api.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.newbie.web.llm.api.model.ModelResponse;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 提供者注册表
 * 管理所有 LLM 提供者，并根据模型名称路由到对应的提供者
 */
@Slf4j
@Component
public class ProviderRegistry {
    
    private final List<LLMProvider> providers;
    private final Map<String, LLMProvider> modelToProvider = new HashMap<>();
    private final Map<String, LLMProvider> providerNameMap = new HashMap<>();
    
    public ProviderRegistry(List<LLMProvider> providers) {
        this.providers = providers != null ? providers : new ArrayList<>();
    }
    
    @PostConstruct
    public void init() {
        // 注册所有提供者
        for (LLMProvider provider : providers) {
            String providerName = provider.getProviderName();
            providerNameMap.put(providerName, provider);
            
            // 注册该提供者支持的所有模型
            for (String model : provider.getSupportedModels()) {
                modelToProvider.put(model, provider);
                log.info("注册模型: {} -> 提供者: {}", model, providerName);
            }
        }
        log.info("提供者注册完成，共 {} 个提供者，{} 个模型", providers.size(), modelToProvider.size());
    }
    
    /**
     * 根据模型名称获取对应的提供者
     */
    public LLMProvider getProviderByModel(String model) {
        return modelToProvider.get(model);
    }
    
    /**
     * 根据提供者名称获取提供者
     */
    public LLMProvider getProviderByName(String providerName) {
        return providerNameMap.get(providerName);
    }
    
    /**
     * 获取所有提供者信息
     */
    public Map<String, Object> getAllProviders() {
        Map<String, Object> result = new HashMap<>();
        for (LLMProvider provider : providers) {
            Map<String, Object> providerInfo = new HashMap<>();
            providerInfo.put("name", provider.getProviderName());
            providerInfo.put("models", provider.getSupportedModels());
            result.put(provider.getProviderName(), providerInfo);
        }
        return result;
    }
    
    /**
     * 获取所有可用的模型列表（自定义格式）
     */
    public List<Map<String, String>> getAllModels() {
        return modelToProvider.entrySet().stream()
                .map(entry -> {
                    Map<String, String> modelInfo = new HashMap<>();
                    modelInfo.put("model", entry.getKey());
                    modelInfo.put("provider", entry.getValue().getProviderName());
                    return modelInfo;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 获取符合 OpenAI 格式的模型列表
     */
    public ModelResponse getOpenAIModels() {
        List<ModelResponse.ModelData> modelDataList = modelToProvider.entrySet().stream()
                .map(entry -> ModelResponse.ModelData.builder()
                        .id(entry.getKey())
                        .object("model")
                        .created(System.currentTimeMillis() / 1000) // 使用当前时间戳
                        .ownedBy(entry.getValue().getProviderName())
                        .build())
                .collect(Collectors.toList());
        
        return ModelResponse.builder()
                .object("list")
                .data(modelDataList)
                .build();
    }
}


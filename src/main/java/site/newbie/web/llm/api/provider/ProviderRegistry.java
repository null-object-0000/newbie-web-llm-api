package site.newbie.web.llm.api.provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.newbie.web.llm.api.config.ApiKeyContext;
import site.newbie.web.llm.api.config.ApiKeyScopedValue;
import site.newbie.web.llm.api.model.ModelResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 提供者注册表（对外 API 专用）
 * 负责模型路由、提供器锁管理等对外 API 核心功能
 * 
 * Admin 相关功能（登录状态管理、提供器信息查询）请使用 {@link ProviderAdminService}
 */
@Slf4j
@Component
public class ProviderRegistry {
    
    private final List<LLMProvider> providers;
    private final Map<String, LLMProvider> modelToProvider = new HashMap<>();
    private final Map<String, LLMProvider> providerNameMap = new HashMap<>();
    
    // 每个提供器的并发锁：同一提供器同一时间只允许一个对话
    private final ConcurrentHashMap<String, Semaphore> providerLocks = new ConcurrentHashMap<>();
    
    public ProviderRegistry(List<LLMProvider> providers) {
        this.providers = providers != null ? providers : new ArrayList<>();
    }
    
    @PostConstruct
    public void init() {
        // 注册所有提供者
        for (LLMProvider provider : providers) {
            String providerName = provider.getProviderName();
            providerNameMap.put(providerName, provider);
            
            // 为每个提供器创建锁
            providerLocks.put(providerName, new Semaphore(1));
            
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
     * 尝试获取提供器的锁
     * @param providerName 提供器名称
     * @return true 如果成功获取锁，false 如果锁已被占用
     */
    public boolean tryAcquireLock(String providerName) {
        Semaphore lock = providerLocks.get(providerName);
        if (lock == null) {
            log.warn("未找到提供器 {} 的锁", providerName);
            return true; // 如果没有锁，默认允许
        }
        boolean acquired = lock.tryAcquire();
        if (acquired) {
            log.info("提供器 {} 已获取锁", providerName);
        } else {
            log.warn("提供器 {} 正忙，锁已被占用", providerName);
        }
        return acquired;
    }
    
    /**
     * 释放提供器的锁
     * @param providerName 提供器名称
     */
    public void releaseLock(String providerName) {
        Semaphore lock = providerLocks.get(providerName);
        if (lock != null) {
            lock.release();
            log.info("提供器 {} 已释放锁", providerName);
        }
    }
    
    /**
     * 检查提供器是否正忙
     * @param providerName 提供器名称
     * @return true 如果提供器正忙（锁被占用）
     */
    public boolean isProviderBusy(String providerName) {
        Semaphore lock = providerLocks.get(providerName);
        if (lock == null) {
            return false;
        }
        return lock.availablePermits() == 0;
    }
    
    /**
     * 根据提供者名称获取提供者
     */
    public LLMProvider getProviderByName(String providerName) {
        return providerNameMap.get(providerName);
    }
    
    /**
     * 获取所有提供者信息
     * 如果存在 API key 上下文，则根据 API key 过滤，只返回 API key 支持的 providers
     * 如果是 admin 请求（没有 API key 上下文），则返回所有 providers
     */
    public Map<String, Object> getAllProviders() {
        Map<String, Object> result = new HashMap<>();
        ApiKeyContext context = ApiKeyScopedValue.getContext();
        boolean shouldFilter = (context != null); // 只有存在 API key 上下文时才过滤
        
        for (LLMProvider provider : providers) {
            String providerName = provider.getProviderName();
            // 如果存在 API key 上下文且不支持该 provider，则跳过
            if (shouldFilter && context != null && !context.supportsProvider(providerName)) {
                continue;
            }
            Map<String, Object> providerInfo = new HashMap<>();
            providerInfo.put("name", providerName);
            providerInfo.put("models", provider.getSupportedModels());
            result.put(providerName, providerInfo);
        }
        return result;
    }

    /**
     * 获取符合 OpenAI 格式的模型列表
     * 如果存在 API key 上下文，则根据 API key 过滤，只返回 API key 支持的 providers 的模型
     * 如果是 admin 请求（没有 API key 上下文），则返回所有模型
     */
    public ModelResponse getOpenAIModels() {
        ApiKeyContext context = ApiKeyScopedValue.getContext();
        boolean shouldFilter = (context != null); // 只有存在 API key 上下文时才过滤
        
        List<ModelResponse.ModelData> modelDataList = modelToProvider.entrySet().stream()
                .filter(entry -> {
                    // 如果存在 API key 上下文，只返回 API key 支持的 provider 的模型
                    if (shouldFilter && context != null) {
                        String providerName = entry.getValue().getProviderName();
                        return context.supportsProvider(providerName);
                    }
                    // 如果没有 API key 上下文（admin 请求），返回所有模型
                    return true;
                })
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


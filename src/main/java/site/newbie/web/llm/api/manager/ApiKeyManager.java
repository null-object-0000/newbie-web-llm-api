package site.newbie.web.llm.api.manager;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 密钥管理服务
 * 管理 API 密钥与账号的映射关系
 */
@Slf4j
@Service
public class ApiKeyManager {
    
    private static final String API_KEYS_FILE = "api-keys.json";
    private static final String API_KEY_PREFIX = "sk-";
    private static final int API_KEY_LENGTH = 32;
    
    private final ObjectMapper objectMapper;
    private final AccountManager accountManager;
    private Path apiKeysFile;
    
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    // 内存缓存：apiKey -> ApiKeyInfo
    private final Map<String, ApiKeyInfo> apiKeysCache = new ConcurrentHashMap<>();
    
    // 反向索引：accountId -> Set<apiKey>
    private final Map<String, Set<String>> accountToApiKeys = new ConcurrentHashMap<>();
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    public ApiKeyManager(ObjectMapper objectMapper, AccountManager accountManager) {
        this.objectMapper = objectMapper;
        this.accountManager = accountManager;
    }
    
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            Path storageDir = Paths.get(userDataDir);
            this.apiKeysFile = storageDir.resolve(API_KEYS_FILE);
            
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
            }
            
            loadApiKeys();
            log.info("API 密钥管理服务初始化完成，共加载 {} 个 API 密钥", apiKeysCache.size());
        } catch (Exception e) {
            log.error("初始化 API 密钥管理服务失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * API 密钥信息
     */
    @Data
    public static class ApiKeyInfo {
        private String apiKey;
        // 兼容旧版本：保留 accountId 和 providerName，但优先使用 providerAccounts
        @Deprecated
        private String accountId;
        @Deprecated
        private String providerName;
        // 新版本：按提供器映射账号ID，key 是提供器名称，value 是账号ID
        private Map<String, String> providerAccounts; // providerName -> accountId
        private String name; // 密钥名称（用户自定义）
        private String description; // 密钥描述
        private long createdAt;
        private long lastUsedAt;
        private boolean enabled; // 是否启用
        
        public ApiKeyInfo() {
            this.enabled = true;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = 0;
            this.providerAccounts = new HashMap<>();
        }
        
        /**
         * 获取指定提供器的账号ID
         */
        public String getAccountIdForProvider(String providerName) {
            if (providerAccounts != null && providerAccounts.containsKey(providerName)) {
                return providerAccounts.get(providerName);
            }
            // 兼容旧版本数据
            if (this.providerName != null && this.providerName.equals(providerName)) {
                return this.accountId;
            }
            return null;
        }
        
        /**
         * 检查是否支持指定提供器
         */
        public boolean supportsProvider(String providerName) {
            return getAccountIdForProvider(providerName) != null;
        }
    }
    
    /**
     * 生成新的 API 密钥
     */
    private String generateApiKey() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(API_KEY_PREFIX);
        for (int i = 0; i < API_KEY_LENGTH; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * 创建 API 密钥（关联单个账号，兼容旧版本）
     * @param accountId 账号ID
     * @param name 密钥名称
     * @param description 密钥描述
     * @return API 密钥
     */
    public String createApiKey(String accountId, String name, String description) {
        Map<String, String> providerAccounts = new HashMap<>();
        AccountManager.AccountInfo account = accountManager.getAccount(accountId);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在: " + accountId);
        }
        providerAccounts.put(account.getProviderName(), accountId);
        return createApiKey(providerAccounts, name, description);
    }
    
    /**
     * 创建 API 密钥（可以关联多个提供器的账号，也可以不关联）
     * @param providerAccounts 提供器名称到账号ID的映射，可以为空或 null
     * @param name 密钥名称
     * @param description 密钥描述
     * @return API 密钥
     */
    public String createApiKey(Map<String, String> providerAccounts, String name, String description) {
        // 如果提供了账号，验证所有账号是否存在
        if (providerAccounts != null && !providerAccounts.isEmpty()) {
            for (Map.Entry<String, String> entry : providerAccounts.entrySet()) {
                String providerName = entry.getKey();
                String accountId = entry.getValue();
                AccountManager.AccountInfo account = accountManager.getAccount(accountId);
                if (account == null) {
                    throw new IllegalArgumentException("账号不存在: " + accountId);
                }
                if (!account.getProviderName().equals(providerName)) {
                    throw new IllegalArgumentException("账号 " + accountId + " 不属于提供器 " + providerName);
                }
            }
        }
        
        String apiKey = generateApiKey();
        ApiKeyInfo info = new ApiKeyInfo();
        info.setApiKey(apiKey);
        info.setProviderAccounts(providerAccounts != null ? new HashMap<>(providerAccounts) : new HashMap<>());
        info.setName(name != null ? name : "API Key");
        info.setDescription(description);
        
        // 兼容旧版本：如果只有一个提供器，也设置 accountId 和 providerName
        if (providerAccounts != null && providerAccounts.size() == 1) {
            Map.Entry<String, String> entry = providerAccounts.entrySet().iterator().next();
            info.setProviderName(entry.getKey());
            info.setAccountId(entry.getValue());
        }
        
        apiKeysCache.put(apiKey, info);
        
        // 更新反向索引
        if (providerAccounts != null) {
            for (String accountId : providerAccounts.values()) {
                accountToApiKeys.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(apiKey);
            }
        }
        
        saveApiKeys();
        log.info("创建 API 密钥: providerAccounts={}, name={}", providerAccounts, name);
        
        return apiKey;
    }
    
    /**
     * 根据 API 密钥获取账号ID（兼容旧版本，返回第一个提供器的账号ID）
     * @param apiKey API 密钥
     * @return 账号ID，如果密钥不存在或已禁用则返回 null
     */
    public String getAccountIdByApiKey(String apiKey) {
        ApiKeyInfo info = apiKeysCache.get(apiKey);
        if (info == null || !info.isEnabled()) {
            return null;
        }
        
        // 更新最后使用时间
        info.setLastUsedAt(System.currentTimeMillis());
        saveApiKeys();
        
        // 优先从 providerAccounts 获取
        if (info.getProviderAccounts() != null && !info.getProviderAccounts().isEmpty()) {
            return info.getProviderAccounts().values().iterator().next();
        }
        
        // 兼容旧版本
        return info.getAccountId();
    }
    
    /**
     * 根据 API 密钥和提供器名称获取账号ID
     * @param apiKey API 密钥
     * @param providerName 提供器名称
     * @return 账号ID，如果密钥不存在、已禁用或不支持该提供器则返回 null
     */
    public String getAccountIdByApiKey(String apiKey, String providerName) {
        ApiKeyInfo info = apiKeysCache.get(apiKey);
        if (info == null || !info.isEnabled()) {
            return null;
        }
        
        // 更新最后使用时间
        info.setLastUsedAt(System.currentTimeMillis());
        saveApiKeys();
        
        return info.getAccountIdForProvider(providerName);
    }
    
    /**
     * 检查 API 密钥是否支持指定提供器
     * @param apiKey API 密钥
     * @param providerName 提供器名称
     * @return true 如果支持，false 如果不支持或密钥不存在/已禁用
     */
    public boolean supportsProvider(String apiKey, String providerName) {
        ApiKeyInfo info = apiKeysCache.get(apiKey);
        if (info == null || !info.isEnabled()) {
            return false;
        }
        return info.supportsProvider(providerName);
    }
    
    /**
     * 根据 API 密钥获取密钥信息
     * @param apiKey API 密钥
     * @return 密钥信息，如果不存在则返回 null
     */
    public ApiKeyInfo getApiKeyInfo(String apiKey) {
        return apiKeysCache.get(apiKey);
    }
    
    /**
     * 获取账号的所有 API 密钥
     * @param accountId 账号ID
     * @return API 密钥列表
     */
    public List<ApiKeyInfo> getApiKeysByAccount(String accountId) {
        Set<String> apiKeys = accountToApiKeys.get(accountId);
        if (apiKeys == null || apiKeys.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<ApiKeyInfo> result = new ArrayList<>();
        for (String apiKey : apiKeys) {
            ApiKeyInfo info = apiKeysCache.get(apiKey);
            if (info != null) {
                result.add(info);
            }
        }
        
        return result;
    }
    
    /**
     * 获取所有 API 密钥
     * @return 所有 API 密钥列表
     */
    public List<ApiKeyInfo> getAllApiKeys() {
        return new ArrayList<>(apiKeysCache.values());
    }
    
    /**
     * 更新 API 密钥信息
     * @param apiKey API 密钥
     * @param name 新名称
     * @param description 新描述
     * @param enabled 是否启用
     */
    public void updateApiKey(String apiKey, String name, String description, Boolean enabled) {
        ApiKeyInfo info = apiKeysCache.get(apiKey);
        if (info == null) {
            throw new IllegalArgumentException("API 密钥不存在: " + apiKey);
        }
        
        if (name != null) {
            info.setName(name);
        }
        if (description != null) {
            info.setDescription(description);
        }
        if (enabled != null) {
            info.setEnabled(enabled);
        }
        
        saveApiKeys();
        log.info("更新 API 密钥: apiKey={}, name={}, enabled={}", apiKey, name, enabled);
    }
    
    /**
     * 更新 API 密钥关联的账号
     * @param apiKey API 密钥
     * @param providerAccounts 提供器名称到账号ID的映射，可以为空或 null（表示不关联任何账号）
     */
    public void updateProviderAccounts(String apiKey, Map<String, String> providerAccounts) {
        ApiKeyInfo info = apiKeysCache.get(apiKey);
        if (info == null) {
            throw new IllegalArgumentException("API 密钥不存在: " + apiKey);
        }
        
        // 如果提供了账号，验证所有账号是否存在
        if (providerAccounts != null && !providerAccounts.isEmpty()) {
            for (Map.Entry<String, String> entry : providerAccounts.entrySet()) {
                String providerName = entry.getKey();
                String accountId = entry.getValue();
                AccountManager.AccountInfo account = accountManager.getAccount(accountId);
                if (account == null) {
                    throw new IllegalArgumentException("账号不存在: " + accountId);
                }
                if (!account.getProviderName().equals(providerName)) {
                    throw new IllegalArgumentException("账号 " + accountId + " 不属于提供器 " + providerName);
                }
            }
        }
        
        // 从旧的反向索引中移除
        if (info.getProviderAccounts() != null) {
            for (String accountId : info.getProviderAccounts().values()) {
                Set<String> apiKeys = accountToApiKeys.get(accountId);
                if (apiKeys != null) {
                    apiKeys.remove(apiKey);
                    if (apiKeys.isEmpty()) {
                        accountToApiKeys.remove(accountId);
                    }
                }
            }
        } else if (info.getAccountId() != null) {
            // 兼容旧版本
            Set<String> apiKeys = accountToApiKeys.get(info.getAccountId());
            if (apiKeys != null) {
                apiKeys.remove(apiKey);
                if (apiKeys.isEmpty()) {
                    accountToApiKeys.remove(info.getAccountId());
                }
            }
        }
        
        // 更新 providerAccounts
        info.setProviderAccounts(providerAccounts != null ? new HashMap<>(providerAccounts) : new HashMap<>());
        
        // 兼容旧版本：如果只有一个提供器，也设置 accountId 和 providerName
        if (providerAccounts != null && providerAccounts.size() == 1) {
            Map.Entry<String, String> entry = providerAccounts.entrySet().iterator().next();
            info.setProviderName(entry.getKey());
            info.setAccountId(entry.getValue());
        } else {
            // 如果为空或多个，清除旧版本字段
            info.setProviderName(null);
            info.setAccountId(null);
        }
        
        // 更新新的反向索引
        if (providerAccounts != null) {
            for (String accountId : providerAccounts.values()) {
                accountToApiKeys.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet()).add(apiKey);
            }
        }
        
        saveApiKeys();
        log.info("更新 API 密钥关联账号: apiKey={}, providerAccounts={}", apiKey, providerAccounts);
    }
    
    /**
     * 删除 API 密钥
     * @param apiKey API 密钥
     */
    public void deleteApiKey(String apiKey) {
        ApiKeyInfo info = apiKeysCache.remove(apiKey);
        if (info != null) {
            // 从所有关联的账号中移除
            if (info.getProviderAccounts() != null) {
                for (String accountId : info.getProviderAccounts().values()) {
                    Set<String> apiKeys = accountToApiKeys.get(accountId);
                    if (apiKeys != null) {
                        apiKeys.remove(apiKey);
                        if (apiKeys.isEmpty()) {
                            accountToApiKeys.remove(accountId);
                        }
                    }
                }
            } else if (info.getAccountId() != null) {
                // 兼容旧版本
                Set<String> apiKeys = accountToApiKeys.get(info.getAccountId());
                if (apiKeys != null) {
                    apiKeys.remove(apiKey);
                    if (apiKeys.isEmpty()) {
                        accountToApiKeys.remove(info.getAccountId());
                    }
                }
            }
            saveApiKeys();
            log.info("删除 API 密钥: apiKey={}", apiKey);
        }
    }
    
    /**
     * 删除账号的所有 API 密钥
     * @param accountId 账号ID
     */
    public void deleteApiKeysByAccount(String accountId) {
        Set<String> apiKeys = accountToApiKeys.remove(accountId);
        if (apiKeys != null) {
            for (String apiKey : apiKeys) {
                apiKeysCache.remove(apiKey);
            }
            saveApiKeys();
            log.info("删除账号的所有 API 密钥: accountId={}, count={}", accountId, apiKeys.size());
        }
    }
    
    /**
     * 从文件加载 API 密钥
     */
    private void loadApiKeys() {
        if (!Files.exists(apiKeysFile)) {
            return;
        }
        
        try {
            String content = Files.readString(apiKeysFile);
            if (content == null || content.trim().isEmpty()) {
                return;
            }
            
            Map<String, ApiKeyInfo> loaded = objectMapper.readValue(content, 
                new TypeReference<Map<String, ApiKeyInfo>>() {});
            
            apiKeysCache.clear();
            accountToApiKeys.clear();
            
            for (ApiKeyInfo info : loaded.values()) {
                apiKeysCache.put(info.getApiKey(), info);
                
                // 兼容旧版本：如果 providerAccounts 为空，从 accountId 和 providerName 构建
                if (info.getProviderAccounts() == null || info.getProviderAccounts().isEmpty()) {
                    if (info.getAccountId() != null && info.getProviderName() != null) {
                        info.setProviderAccounts(new HashMap<>());
                        info.getProviderAccounts().put(info.getProviderName(), info.getAccountId());
                    }
                }
                
                // 更新反向索引
                if (info.getProviderAccounts() != null) {
                    for (String accountId : info.getProviderAccounts().values()) {
                        accountToApiKeys.computeIfAbsent(accountId, k -> ConcurrentHashMap.newKeySet())
                            .add(info.getApiKey());
                    }
                } else if (info.getAccountId() != null) {
                    // 兼容旧版本
                    accountToApiKeys.computeIfAbsent(info.getAccountId(), k -> ConcurrentHashMap.newKeySet())
                        .add(info.getApiKey());
                }
            }
            
            log.info("从文件加载了 {} 个 API 密钥", apiKeysCache.size());
        } catch (IOException e) {
            log.error("加载 API 密钥失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 保存 API 密钥到文件
     */
    private void saveApiKeys() {
        try {
            String content = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(apiKeysCache);
            Files.writeString(apiKeysFile, content);
        } catch (IOException e) {
            log.error("保存 API 密钥失败: {}", e.getMessage(), e);
        }
    }
}


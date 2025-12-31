package site.newbie.web.llm.api.manager;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 全局账号管理服务
 * 管理所有提供器的账号信息
 */
@Slf4j
@Service
public class AccountManager {
    
    private static final String ACCOUNTS_FILE = "accounts.json";
    
    private final ObjectMapper objectMapper;
    private Path accountsFile;
    
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    // 内存缓存：provider -> accountId -> AccountInfo
    private final Map<String, Map<String, AccountInfo>> accountsCache = new ConcurrentHashMap<>();
    
    public AccountManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            Path storageDir = Paths.get(userDataDir);
            this.accountsFile = storageDir.resolve(ACCOUNTS_FILE);
            
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
            }
            
            loadAccounts();
            log.info("账号管理服务初始化完成，共加载 {} 个提供器的账号", accountsCache.size());
            
            // 清理不存在的账号对应的 Chrome 配置
            cleanupOrphanedBrowserDirectories();
        } catch (Exception e) {
            log.error("初始化账号管理服务失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 账号信息
     */
    @Data
    public static class AccountInfo {
        private String accountId;
        private String providerName;
        private String accountName; // 账号名称（如邮箱、用户名等）
        private String nickname; // 昵称（登录后自动获取，如 "Changen Ni"）
        private Map<String, String> metadata; // 额外的元数据
        private long createdAt;
        private long lastUsedAt;
        @JsonProperty("isLoginVerified")
        @JsonAlias("loginVerified") // 兼容旧的 JSON 文件中的字段名
        private boolean isLoginVerified; // 是否已完成登录验证（仅对 Playwright 类提供器有效）
        private Boolean browserHeadless; // 浏览器是否无界面运行（仅对 Playwright 类提供器有效）
        
        public AccountInfo() {
            this.metadata = new HashMap<>();
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = System.currentTimeMillis();
            this.isLoginVerified = false; // 默认未完成登录验证
            this.browserHeadless = false; // 默认有界面运行
        }
    }
    
    /**
     * 获取或创建账号
     * @param providerName 提供器名称
     * @param accountName 账号名称（如邮箱）
     * @return 账号ID
     */
    public String getOrCreateAccount(String providerName, String accountName) {
        Map<String, AccountInfo> providerAccounts = accountsCache.computeIfAbsent(providerName, k -> new ConcurrentHashMap<>());
        
        // 查找是否已存在相同账号（根据账号名称）
        for (AccountInfo account : providerAccounts.values()) {
            if (accountName.equals(account.getAccountName())) {
                account.setLastUsedAt(System.currentTimeMillis());
                saveAccounts();
                return account.getAccountId();
            }
        }
        
        // 创建新账号
        String accountId = UUID.randomUUID().toString();
        AccountInfo account = new AccountInfo();
        account.setAccountId(accountId);
        account.setProviderName(providerName);
        account.setAccountName(accountName);
        
        providerAccounts.put(accountId, account);
        saveAccounts();
        
        log.info("创建新账号: provider={}, accountId={}, accountName={}", providerName, accountId, accountName);
        return accountId;
    }
    
    /**
     * 获取账号信息（通过 provider 和 accountId）
     */
    public AccountInfo getAccount(String providerName, String accountId) {
        Map<String, AccountInfo> providerAccounts = accountsCache.get(providerName);
        if (providerAccounts == null) {
            return null;
        }
        AccountInfo account = providerAccounts.get(accountId);
        if (account != null) {
            account.setLastUsedAt(System.currentTimeMillis());
            saveAccounts();
        }
        return account;
    }
    
    /**
     * 获取账号信息（仅通过 accountId，会遍历所有 provider）
     */
    public AccountInfo getAccount(String accountId) {
        for (Map<String, AccountInfo> providerAccounts : accountsCache.values()) {
            AccountInfo account = providerAccounts.get(accountId);
            if (account != null) {
                account.setLastUsedAt(System.currentTimeMillis());
                saveAccounts();
                return account;
            }
        }
        return null;
    }
    
    /**
     * 获取提供器的所有账号
     */
    public List<AccountInfo> getAccountsByProvider(String providerName) {
        Map<String, AccountInfo> providerAccounts = accountsCache.get(providerName);
        if (providerAccounts == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(providerAccounts.values());
    }
    
    /**
     * 获取所有账号
     */
    public Map<String, List<AccountInfo>> getAllAccounts() {
        Map<String, List<AccountInfo>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, AccountInfo>> entry : accountsCache.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        return result;
    }
    
    /**
     * 更新账号元数据
     */
    public void updateAccountMetadata(String providerName, String accountId, String key, String value) {
        AccountInfo account = getAccount(providerName, accountId);
        if (account != null) {
            account.getMetadata().put(key, value);
            saveAccounts();
        }
    }
    
    /**
     * 删除账号
     */
    public void deleteAccount(String providerName, String accountId) {
        Map<String, AccountInfo> providerAccounts = accountsCache.get(providerName);
        if (providerAccounts != null) {
            providerAccounts.remove(accountId);
            saveAccounts();
            log.info("删除账号: provider={}, accountId={}", providerName, accountId);
        }
    }
    
    /**
     * 从文件加载账号
     */
    private void loadAccounts() {
        if (!Files.exists(accountsFile)) {
            log.info("账号文件不存在，将创建新文件: {}", accountsFile);
            return;
        }
        
        try {
            Map<String, Map<String, AccountInfo>> loaded = objectMapper.readValue(
                    accountsFile.toFile(),
                    new TypeReference<Map<String, Map<String, AccountInfo>>>() {}
            );
            
            if (loaded != null) {
                accountsCache.clear();
                accountsCache.putAll(loaded);
                int totalAccounts = accountsCache.values().stream()
                        .mapToInt(Map::size)
                        .sum();
                log.info("从文件加载了 {} 个提供器的 {} 个账号", accountsCache.size(), totalAccounts);
            }
        } catch (Exception e) {
            log.error("加载账号失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 保存账号到文件（公开方法，供外部调用）
     */
    public void saveAccounts() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(accountsFile.toFile(), accountsCache);
            log.debug("已保存账号到文件: {}", accountsFile);
        } catch (Exception e) {
            log.error("保存账号到文件失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 清理不存在的账号对应的 Chrome 配置目录
     * 在应用启动时调用，删除那些账号已不存在的浏览器数据目录
     */
    private void cleanupOrphanedBrowserDirectories() {
        try {
            Path storageDir = Paths.get(userDataDir);
            if (!Files.exists(storageDir) || !Files.isDirectory(storageDir)) {
                return;
            }
            
            // 构建所有有效账号的目录路径集合
            Set<Path> validAccountDirs = new HashSet<>();
            for (Map.Entry<String, Map<String, AccountInfo>> providerEntry : accountsCache.entrySet()) {
                String providerName = providerEntry.getKey();
                for (String accountId : providerEntry.getValue().keySet()) {
                    Path accountDir = storageDir.resolve(providerName).resolve(accountId);
                    validAccountDirs.add(accountDir);
                }
            }
            
            // 扫描 user-data 目录下的所有提供器目录
            try (Stream<Path> providerDirs = Files.list(storageDir)) {
                for (Path providerDir : providerDirs.toList()) {
                    if (!Files.isDirectory(providerDir)) {
                        continue;
                    }
                    
                    String providerName = providerDir.getFileName().toString();
                    
                    // 跳过一些已知的非账号目录
                    if (providerName.equals("accounts.json") || 
                        providerName.equals("api-keys.json") ||
                        providerName.equals("gemini-images") ||
                        providerName.startsWith(".")) {
                        continue;
                    }
                    
                    // 扫描该提供器目录下的所有账号目录
                    try (Stream<Path> accountDirs = Files.list(providerDir)) {
                        for (Path accountDir : accountDirs.toList()) {
                            if (!Files.isDirectory(accountDir)) {
                                continue;
                            }
                            
                            String accountId = accountDir.getFileName().toString();
                            
                            // 跳过一些已知的非账号目录（如浏览器缓存目录等）
                            // 账号ID 是 UUID 格式，格式为：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
                            if (accountId.equals("Default") ||
                                accountId.equals("Crashpad") ||
                                accountId.equals("Safe Browsing") ||
                                accountId.startsWith(".")) {
                                continue;
                            }
                            
                            // 只处理 UUID 格式的目录名（账号ID 都是 UUID 格式）
                            if (!accountId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
                                continue;
                            }
                            
                            // 检查该目录是否对应一个有效的账号
                            if (!validAccountDirs.contains(accountDir)) {
                                // 账号不存在，删除该目录
                                try {
                                    deleteDirectoryRecursively(accountDir);
                                    log.info("已清理不存在的账号对应的 Chrome 配置目录: {}", accountDir);
                                } catch (Exception e) {
                                    log.warn("清理 Chrome 配置目录失败: {}, 错误: {}", accountDir, e.getMessage());
                                }
                            }
                        }
                    } catch (IOException e) {
                        log.warn("扫描提供器目录失败: {}, 错误: {}", providerDir, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("扫描 user-data 目录失败: {}", e.getMessage());
            }
            
            log.info("Chrome 配置目录清理完成");
        } catch (Exception e) {
            log.error("清理 Chrome 配置目录时出错: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 递归删除目录及其所有内容
     */
    private void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         log.warn("删除文件/目录失败: {}, 错误: {}", path, e.getMessage());
                     }
                 });
        }
    }
}


package site.newbie.web.llm.api.controller;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.newbie.web.llm.api.manager.AccountManager;
import site.newbie.web.llm.api.manager.AccountLoginService;
import site.newbie.web.llm.api.manager.ApiKeyManager;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import site.newbie.web.llm.api.provider.ProviderType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台 Controller
 * 提供账号管理和 API 密钥管理的 REST API
 * 注意：此 Controller 仅允许内网访问（通过 AdminAccessInterceptor 限制）
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "*")
public class AdminController {
    
    private final AccountManager accountManager;
    private final ApiKeyManager apiKeyManager;
    private final ProviderRegistry providerRegistry;
    private final AccountLoginService accountLoginService;
    
    public AdminController(AccountManager accountManager, 
                          ApiKeyManager apiKeyManager,
                          ProviderRegistry providerRegistry,
                          AccountLoginService accountLoginService) {
        this.accountManager = accountManager;
        this.apiKeyManager = apiKeyManager;
        this.providerRegistry = providerRegistry;
        this.accountLoginService = accountLoginService;
    }
    
    // ==================== 账号管理 API ====================
    
    /**
     * 获取所有账号
     */
    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> getAllAccounts(
            @RequestParam(required = false) String provider) {
        try {
            Map<String, Object> result = new HashMap<>();
            
            if (provider != null && !provider.isEmpty()) {
                // 获取指定提供器的账号
                List<AccountManager.AccountInfo> accounts = accountManager.getAccountsByProvider(provider);
                result.put("provider", provider);
                result.put("accounts", accounts);
                result.put("count", accounts.size());
            } else {
                // 以代码中实际注册的提供器为基准
                Map<String, Object> allProviders = providerRegistry.getAllProviders();
                Map<String, List<AccountManager.AccountInfo>> allAccounts = new HashMap<>();
                
                // 对于每个注册的提供器，获取其账号（如果没有则返回空列表）
                for (String providerName : allProviders.keySet()) {
                    List<AccountManager.AccountInfo> accounts = accountManager.getAccountsByProvider(providerName);
                    allAccounts.put(providerName, accounts);
                }
                
                result.put("accounts", allAccounts);
                int totalCount = allAccounts.values().stream()
                    .mapToInt(List::size)
                    .sum();
                result.put("totalCount", totalCount);
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取账号列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 获取账号详情
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountManager.AccountInfo> getAccount(@PathVariable String accountId) {
        try {
            AccountManager.AccountInfo account = accountManager.getAccount(accountId);
            if (account == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(account);
        } catch (Exception e) {
            log.error("获取账号详情失败: accountId={}", accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 创建账号（仅创建账号记录，不进行登录）
     */
    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            String accountId = accountManager.getOrCreateAccount(
                request.getProvider(),
                request.getAccountName()
            );
            
            AccountManager.AccountInfo account = accountManager.getAccount(accountId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("accountId", accountId);
            result.put("account", account);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("创建账号失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 启动登录流程（Playwright 类提供器）
     * 为账号创建独立的 Chrome 配置并启动浏览器
     */
    @PostMapping("/accounts/{providerName}/{accountId}/login/start")
    public ResponseEntity<Map<String, Object>> startLogin(
            @PathVariable String providerName,
            @PathVariable String accountId) {
        try {
            AccountManager.AccountInfo account = accountManager.getAccount(providerName, accountId);
            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "账号不存在"));
            }
            
            // 启动登录流程
            String sessionId = accountLoginService.startLogin(
                providerName, 
                accountId, 
                account.getAccountName()
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", sessionId);
            result.put("message", "登录流程已启动，请在浏览器中完成登录");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("启动登录流程失败: providerName={}, accountId={}", providerName, accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 验证登录状态
     * 检查是否已登录，并验证账号是否一致
     */
    @PostMapping("/accounts/login/verify")
    public ResponseEntity<Map<String, Object>> verifyLogin(@RequestBody VerifyLoginRequest request) {
        try {
            AccountLoginService.LoginVerificationResult result = 
                accountLoginService.verifyLogin(request.getSessionId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            
            if (result.isSuccess()) {
                response.put("actualAccount", result.getActualAccount());
                response.put("nickname", result.getNickname());
                
                // 更新账号信息
                AccountLoginService.LoginSession session = 
                    accountLoginService.getLoginSession(request.getSessionId());
                if (session != null) {
                    AccountManager.AccountInfo account = accountManager.getAccount(
                        session.getProviderName(), session.getAccountId());
                    if (account != null && result.getActualAccount() != null) {
                        // 更新账号名称为实际登录的账号（邮箱）
                        account.setAccountName(result.getActualAccount());
                        // 更新昵称
                        if (result.getNickname() != null && !result.getNickname().isEmpty()) {
                            account.setNickname(result.getNickname());
                        }
                        // 标记为已完成登录验证
                        account.setLoginVerified(true);
                        accountManager.saveAccounts();
                    }
                }
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("验证登录失败: sessionId={}", request.getSessionId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 获取登录会话状态
     */
    @GetMapping("/accounts/login/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getLoginSession(@PathVariable String sessionId) {
        try {
            AccountLoginService.LoginSession session = accountLoginService.getLoginSession(sessionId);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "登录会话不存在"));
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", session.getSessionId());
            result.put("providerName", session.getProviderName());
            result.put("accountId", session.getAccountId());
            result.put("accountName", session.getAccountName());
            result.put("status", session.getStatus().name());
            result.put("createdAt", session.getCreatedAt());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取登录会话失败: sessionId={}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 删除账号
     */
    @DeleteMapping("/accounts/{provider}/{accountId}")
    public ResponseEntity<Map<String, Object>> deleteAccount(
            @PathVariable String provider,
            @PathVariable String accountId) {
        try {
            // 先删除该账号的所有 API 密钥
            apiKeyManager.deleteApiKeysByAccount(accountId);
            
            // 再删除账号
            accountManager.deleteAccount(provider, accountId);
            
            return ResponseEntity.ok(Map.of("success", true, "message", "账号已删除"));
        } catch (Exception e) {
            log.error("删除账号失败: provider={}, accountId={}", provider, accountId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    // ==================== API 密钥管理 API ====================
    
    /**
     * 获取所有 API 密钥
     */
    @GetMapping("/api-keys")
    public ResponseEntity<Map<String, Object>> getAllApiKeys(
            @RequestParam(required = false) String accountId) {
        try {
            Map<String, Object> result = new HashMap<>();
            
            if (accountId != null && !accountId.isEmpty()) {
                // 获取指定账号的 API 密钥
                List<ApiKeyManager.ApiKeyInfo> apiKeys = apiKeyManager.getApiKeysByAccount(accountId);
                result.put("accountId", accountId);
                result.put("apiKeys", apiKeys);
                result.put("count", apiKeys.size());
            } else {
                // 获取所有 API 密钥
                List<ApiKeyManager.ApiKeyInfo> allApiKeys = apiKeyManager.getAllApiKeys();
                result.put("apiKeys", allApiKeys);
                result.put("count", allApiKeys.size());
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取 API 密钥列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 创建 API 密钥（可以不关联账号）
     */
    @PostMapping("/api-keys")
    public ResponseEntity<Map<String, Object>> createApiKey(@RequestBody CreateApiKeyRequest request) {
        try {
            String apiKey;
            
            // 优先使用新版本的 providerAccounts
            if (request.getProviderAccounts() != null && !request.getProviderAccounts().isEmpty()) {
                apiKey = apiKeyManager.createApiKey(
                    request.getProviderAccounts(),
                    request.getName(),
                    request.getDescription()
                );
            } else if (request.getAccountId() != null && !request.getAccountId().isEmpty()) {
                // 兼容旧版本：单个账号ID
                apiKey = apiKeyManager.createApiKey(
                    request.getAccountId(),
                    request.getName(),
                    request.getDescription()
                );
            } else {
                // 允许创建不关联账号的 API 密钥
                apiKey = apiKeyManager.createApiKey(
                    (Map<String, String>) null,
                    request.getName(),
                    request.getDescription()
                );
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("apiKey", apiKey);
            result.put("message", "API 密钥创建成功，请妥善保存");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("创建 API 密钥失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 更新 API 密钥
     */
    @PutMapping("/api-keys/{apiKey}")
    public ResponseEntity<Map<String, Object>> updateApiKey(
            @PathVariable String apiKey,
            @RequestBody UpdateApiKeyRequest request) {
        try {
            apiKeyManager.updateApiKey(
                apiKey,
                request.getName(),
                request.getDescription(),
                request.getEnabled()
            );
            
            return ResponseEntity.ok(Map.of("success", true, "message", "API 密钥已更新"));
        } catch (Exception e) {
            log.error("更新 API 密钥失败: apiKey={}", apiKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 更新 API 密钥关联的账号
     */
    @PutMapping("/api-keys/{apiKey}/accounts")
    public ResponseEntity<Map<String, Object>> updateApiKeyAccounts(
            @PathVariable String apiKey,
            @RequestBody UpdateApiKeyAccountsRequest request) {
        try {
            apiKeyManager.updateProviderAccounts(
                apiKey,
                request.getProviderAccounts()
            );
            
            return ResponseEntity.ok(Map.of("success", true, "message", "关联账号已更新"));
        } catch (Exception e) {
            log.error("更新 API 密钥关联账号失败: apiKey={}", apiKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 删除 API 密钥
     */
    @DeleteMapping("/api-keys/{apiKey}")
    public ResponseEntity<Map<String, Object>> deleteApiKey(@PathVariable String apiKey) {
        try {
            apiKeyManager.deleteApiKey(apiKey);
            return ResponseEntity.ok(Map.of("success", true, "message", "API 密钥已删除"));
        } catch (Exception e) {
            log.error("删除 API 密钥失败: apiKey={}", apiKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    // ==================== 统计信息 API ====================
    
    /**
     * 获取统计信息
     * 以代码中实际注册的提供器为基准，而不是以 JSON 文件为基准
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // 获取代码中注册的所有提供器
            Map<String, Object> allProviders = providerRegistry.getAllProviders();
            
            // 获取账号统计（以代码中注册的提供器为基准）
            Map<String, List<AccountManager.AccountInfo>> allAccounts = accountManager.getAllAccounts();
            int totalAccounts = allAccounts.values().stream()
                .mapToInt(List::size)
                .sum();
            stats.put("totalAccounts", totalAccounts);
            
            // 以代码中注册的提供器为基准，统计每个提供器的账号数量
            Map<String, Integer> accountsByProvider = new HashMap<>();
            for (String providerName : allProviders.keySet()) {
                List<AccountManager.AccountInfo> providerAccounts = allAccounts.getOrDefault(providerName, Collections.emptyList());
                accountsByProvider.put(providerName, providerAccounts.size());
            }
            stats.put("accountsByProvider", accountsByProvider);
            
            // API 密钥统计
            List<ApiKeyManager.ApiKeyInfo> allApiKeys = apiKeyManager.getAllApiKeys();
            long enabledApiKeys = allApiKeys.stream()
                .filter(ApiKeyManager.ApiKeyInfo::isEnabled)
                .count();
            stats.put("totalApiKeys", allApiKeys.size());
            stats.put("enabledApiKeys", enabledApiKeys);
            
            // 提供器统计
            stats.put("totalProviders", allProviders.size());
            stats.put("providers", allProviders.keySet());
            
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    // ==================== 请求/响应模型 ====================
    
    @Data
    public static class CreateAccountRequest {
        private String provider;
        private String accountName;
    }
    
    @Data
    public static class CreateApiKeyRequest {
        // 兼容旧版本：单个账号ID
        private String accountId;
        // 新版本：按提供器映射账号ID，key 是提供器名称，value 是账号ID
        private Map<String, String> providerAccounts; // providerName -> accountId
        private String name;
        private String description;
    }
    
    @Data
    public static class UpdateApiKeyRequest {
        private String name;
        private String description;
        private Boolean enabled;
    }
    
    @Data
    public static class UpdateApiKeyAccountsRequest {
        private Map<String, String> providerAccounts; // providerName -> accountId
    }
    
    @Data
    public static class VerifyLoginRequest {
        private String sessionId;
    }
}


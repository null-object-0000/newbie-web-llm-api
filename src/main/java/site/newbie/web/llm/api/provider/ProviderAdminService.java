package site.newbie.web.llm.api.provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.newbie.web.llm.api.manager.LoginStorageService;
import site.newbie.web.llm.api.model.LoginInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 管理服务（Admin 专用）
 * 负责提供器的登录状态管理、提供器信息查询等管理后台功能
 */
@Slf4j
@Service
public class ProviderAdminService {
    
    private final List<LLMProvider> providers;
    private final Map<String, LLMProvider> providerNameMap = new HashMap<>();
    private final LoginStorageService storageService;
    
    public ProviderAdminService(List<LLMProvider> providers, LoginStorageService storageService) {
        this.providers = providers != null ? providers : new ArrayList<>();
        this.storageService = storageService;
    }
    
    @PostConstruct
    public void init() {
        // 建立提供器名称映射
        for (LLMProvider provider : providers) {
            String providerName = provider.getProviderName();
            providerNameMap.put(providerName, provider);
            log.info("Admin 服务注册提供器: {}", providerName);
        }
        
        // 从本地存储加载登录状态
        Map<String, LoginInfo> loadedStatus = storageService.getAllLoginStatus();
        log.info("已从本地存储加载 {} 个提供器的登录状态", loadedStatus.size());
    }
    
    /**
     * 获取所有提供器信息（Admin 专用，不进行任何过滤）
     * 返回所有已注册的提供器及其支持的模型
     */
    public Map<String, Object> getAllProviders() {
        Map<String, Object> result = new HashMap<>();
        
        for (LLMProvider provider : providers) {
            String providerName = provider.getProviderName();
            Map<String, Object> providerInfo = new HashMap<>();
            providerInfo.put("name", providerName);
            providerInfo.put("models", provider.getSupportedModels());
            result.put(providerName, providerInfo);
        }
        return result;
    }
    
    /**
     * 根据提供器名称获取提供器
     */
    public LLMProvider getProviderByName(String providerName) {
        return providerNameMap.get(providerName);
    }
    
    /**
     * 获取所有提供器名称列表
     */
    public List<String> getAllProviderNames() {
        return new ArrayList<>(providerNameMap.keySet());
    }
    
    /**
     * 获取提供器数量
     */
    public int getProviderCount() {
        return providers.size();
    }
    
    // ==================== 登录状态管理 ====================
    
    /**
     * 检查提供器的登录状态（每个提供器在程序运行过程中只检查一次）
     * @param providerName 提供器名称
     * @param checkLoginFunction 检查登录状态的函数，接收 Page 参数，返回 boolean
     * @return true 如果已登录，false 如果未登录
     */
    public boolean checkLoginStatus(String providerName, 
            java.util.function.Function<com.microsoft.playwright.Page, Boolean> checkLoginFunction) {
        // 如果已经检查过，直接返回缓存的结果
        LoginInfo loginInfo = storageService.getLoginStatus(providerName);
        if (loginInfo != null) {
            log.info("提供器 {} 的登录状态已缓存: {}", providerName, loginInfo.isLoggedIn() ? "已登录" : "未登录");
            return loginInfo.isLoggedIn();
        }
        
        // 如果未检查过，执行检查并缓存结果
        log.info("首次检查提供器 {} 的登录状态", providerName);
        // 注意：这里不创建页面，由调用者传入页面或创建函数
        // 返回 false 表示未登录（保守策略），实际检查由调用者完成
        return false;
    }
    
    /**
     * 设置提供器的登录状态（持久化到本地存储）
     * @param providerName 提供器名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String providerName, boolean isLoggedIn) {
        LoginInfo loginInfo = isLoggedIn ? LoginInfo.loggedIn() : LoginInfo.notLoggedIn();
        storageService.saveLoginStatus(providerName, loginInfo);
        log.info("已保存提供器 {} 的登录状态到本地存储: {}", providerName, isLoggedIn ? "已登录" : "未登录");
    }
    
    /**
     * 设置提供器的登录状态（持久化到本地存储）
     * @param providerName 提供器名称
     * @param loginInfo 登录信息
     */
    public void setLoginStatus(String providerName, LoginInfo loginInfo) {
        storageService.saveLoginStatus(providerName, loginInfo);
        log.info("已保存提供器 {} 的登录状态到本地存储: {}", providerName, 
            loginInfo.isLoggedIn() ? "已登录" : "未登录");
    }
    
    /**
     * 获取提供器的登录状态（从本地存储）
     * @param providerName 提供器名称
     * @return null 如果未检查过，true 如果已登录，false 如果未登录
     */
    public Boolean getLoginStatus(String providerName) {
        LoginInfo loginInfo = storageService.getLoginStatus(providerName);
        if (loginInfo == null) {
            return null;
        }
        return loginInfo.isLoggedIn();
    }
    
    /**
     * 获取提供器的登录信息（从本地存储）
     * @param providerName 提供器名称
     * @return LoginInfo 如果存在，null 如果不存在
     */
    public LoginInfo getLoginInfo(String providerName) {
        return storageService.getLoginStatus(providerName);
    }
    
    /**
     * 清除提供器的登录状态
     * @param providerName 提供器名称
     */
    public void clearLoginStatus(String providerName) {
        storageService.removeLoginStatus(providerName);
        log.info("已清除提供器 {} 的登录状态", providerName);
    }
    
    /**
     * 获取所有提供器的登录状态
     * @return Map<providerName, LoginInfo>
     */
    public Map<String, LoginInfo> getAllLoginStatus() {
        return storageService.getAllLoginStatus();
    }
}


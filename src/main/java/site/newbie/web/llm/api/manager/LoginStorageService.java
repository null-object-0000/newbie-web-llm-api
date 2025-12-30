package site.newbie.web.llm.api.manager;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录状态存储服务
 * 负责将登录状态和登录会话持久化到本地文件
 * 与浏览器数据目录统一使用 user-data 目录
 */
@Slf4j
@Service
public class LoginStorageService {
    
    private static final String LOGIN_STATUS_FILE = "login_status.json";
    private static final String LOGIN_SESSIONS_FILE = "login_sessions.json";
    
    private final ObjectMapper objectMapper;
    private Path storageDir;
    private Path loginStatusFile;
    private Path loginSessionsFile;
    
    // 从配置文件读取，与浏览器数据目录保持一致
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    // 使用 @Lazy 避免循环依赖
    @Autowired(required = false)
    @Lazy
    private ProviderRegistry providerRegistry;
    
    public LoginStorageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @PostConstruct
    public void init() {
        try {
            // 在 @Value 注入后初始化路径（与浏览器数据目录保持一致）
            this.storageDir = Paths.get(userDataDir);
            this.loginStatusFile = storageDir.resolve(LOGIN_STATUS_FILE);
            this.loginSessionsFile = storageDir.resolve(LOGIN_SESSIONS_FILE);
            
            // 创建存储目录（与浏览器数据目录相同）
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                log.info("创建登录数据存储目录: {}", storageDir.toAbsolutePath());
            }
            
            // 启动时清理所有登录会话
            clearAllLoginSessions();
            
            log.info("登录存储服务初始化完成（完全基于本地文件存储，目录: {}）", storageDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("初始化登录存储服务失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 保存登录状态
     */
    public void saveLoginStatus(String providerName, LoginInfo loginInfo) {
        Map<String, LoginInfo> loginStatusMap = loadLoginStatusMap();
        loginStatusMap.put(providerName, loginInfo);
        persistLoginStatus(loginStatusMap);
    }
    
    /**
     * 获取登录状态
     */
    public LoginInfo getLoginStatus(String providerName) {
        Map<String, LoginInfo> loginStatusMap = loadLoginStatusMap();
        return loginStatusMap.get(providerName);
    }
    
    /**
     * 获取所有登录状态
     */
    public Map<String, LoginInfo> getAllLoginStatus() {
        return loadLoginStatusMap();
    }
    
    /**
     * 删除登录状态
     */
    public void removeLoginStatus(String providerName) {
        Map<String, LoginInfo> loginStatusMap = loadLoginStatusMap();
        loginStatusMap.remove(providerName);
        persistLoginStatus(loginStatusMap);
    }
    
    /**
     * 生成会话键（providerName:accountId:conversationId 或 providerName:conversationId）
     */
    private String generateSessionKey(String providerName, String accountId, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (accountId != null && !accountId.isEmpty()) {
            return providerName + ":" + accountId + ":" + conversationId;
        }
        return providerName + ":" + conversationId;
    }
    
    /**
     * 生成会话键（兼容旧接口）
     */
    private String generateSessionKey(String providerName, String conversationId) {
        return generateSessionKey(providerName, null, conversationId);
    }
    
    /**
     * 保存登录会话（兼容旧接口，使用默认账号）
     */
    public void saveLoginSession(String providerName, String conversationId, LoginSessionManager.LoginSession session) {
        saveLoginSession(providerName, null, conversationId, session);
    }
    
    /**
     * 保存登录会话
     */
    public void saveLoginSession(String providerName, String accountId, String conversationId, LoginSessionManager.LoginSession session) {
        Map<String, LoginSessionManager.LoginSession> loginSessionsMap = loadLoginSessionsMap();
        if (session != null && conversationId != null && !conversationId.isEmpty()) {
            String key = generateSessionKey(providerName, accountId, conversationId);
            // 检查是否是新建的登录会话（之前不存在）
            boolean isNewSession = !loginSessionsMap.containsKey(key);
            loginSessionsMap.put(key, session);
            log.debug("保存登录会话: key={}, state={}, isNew={}", key, session.getState(), isNewSession);
            
            // 只有在新建登录会话且状态为 WAITING_LOGIN_METHOD 时，才更新登录状态为未登录
            // 这样可以避免覆盖已登录的状态（比如用户已经登录，但是因为某种原因创建了登录会话）
            if (isNewSession && session.getState() == LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD) {
                log.info("检测到新建了 WAITING_LOGIN_METHOD 状态的登录会话，立即更新登录状态为未登录");
                if (providerRegistry != null) {
                    providerRegistry.setLoginStatus(providerName, false);
                }
            }
        } else {
            if (conversationId != null && !conversationId.isEmpty()) {
                String key = generateSessionKey(providerName, conversationId);
                loginSessionsMap.remove(key);
                log.debug("删除登录会话: key={}", key);
            }
        }
        persistLoginSessions(loginSessionsMap);
    }
    
    /**
     * 获取登录会话（兼容旧接口，使用默认账号）
     */
    public LoginSessionManager.LoginSession getLoginSession(String providerName, String conversationId) {
        return getLoginSession(providerName, null, conversationId);
    }
    
    /**
     * 获取登录会话
     */
    public LoginSessionManager.LoginSession getLoginSession(String providerName, String accountId, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        Map<String, LoginSessionManager.LoginSession> loginSessionsMap = loadLoginSessionsMap();
        String key = generateSessionKey(providerName, accountId, conversationId);
        LoginSessionManager.LoginSession session = loginSessionsMap.get(key);
        log.debug("获取登录会话: key={}, session存在={}, state={}", 
            key, session != null, session != null ? session.getState() : null);
        return session;
    }
    
    /**
     * 获取所有登录会话
     */
    public Map<String, LoginSessionManager.LoginSession> getAllLoginSessions() {
        return loadLoginSessionsMap();
    }
    
    /**
     * 删除登录会话（兼容旧接口，使用默认账号）
     */
    public void removeLoginSession(String providerName, String conversationId) {
        removeLoginSession(providerName, null, conversationId);
    }
    
    /**
     * 删除登录会话
     */
    public void removeLoginSession(String providerName, String accountId, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        Map<String, LoginSessionManager.LoginSession> loginSessionsMap = loadLoginSessionsMap();
        String key = generateSessionKey(providerName, accountId, conversationId);
        loginSessionsMap.remove(key);
        persistLoginSessions(loginSessionsMap);
    }
    
    /**
     * 根据提供器名称删除所有登录会话（用于清理）
     */
    public void removeAllLoginSessionsByProvider(String providerName) {
        Map<String, LoginSessionManager.LoginSession> loginSessionsMap = loadLoginSessionsMap();
        loginSessionsMap.entrySet().removeIf(entry -> entry.getKey().startsWith(providerName + ":"));
        persistLoginSessions(loginSessionsMap);
    }
    
    /**
     * 清理所有登录会话（启动时调用）
     */
    public void clearAllLoginSessions() {
        try {
            if (Files.exists(loginSessionsFile)) {
                Map<String, LoginSessionManager.LoginSession> loginSessionsMap = loadLoginSessionsMap();
                int count = loginSessionsMap.size();
                if (count > 0) {
                    loginSessionsMap.clear();
                    persistLoginSessions(loginSessionsMap);
                    log.info("启动时已清理 {} 个登录会话", count);
                } else {
                    log.debug("启动时检查登录会话，文件为空，无需清理");
                }
            } else {
                log.debug("启动时检查登录会话，文件不存在，无需清理");
            }
        } catch (Exception e) {
            log.error("清理登录会话失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从文件加载登录状态
     */
    private Map<String, LoginInfo> loadLoginStatusMap() {
        if (!Files.exists(loginStatusFile)) {
            return new HashMap<>();
        }
        
        try {
            Map<String, LoginInfo> loaded = objectMapper.readValue(
                    loginStatusFile.toFile(),
                    new TypeReference<>() {
                    }
            );
            return loaded != null ? loaded : new HashMap<>();
        } catch (Exception e) {
            log.error("加载登录状态失败: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * 持久化登录状态到文件
     */
    private void persistLoginStatus(Map<String, LoginInfo> loginStatusMap) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(loginStatusFile.toFile(), loginStatusMap);
            log.debug("已保存登录状态到文件: {}", loginStatusFile);
        } catch (Exception e) {
            log.error("保存登录状态失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从文件加载登录会话
     */
    private Map<String, LoginSessionManager.LoginSession> loadLoginSessionsMap() {
        if (!Files.exists(loginSessionsFile)) {
            log.debug("登录会话文件不存在: {}", loginSessionsFile);
            return new HashMap<>();
        }
        
        try {
            Map<String, LoginSessionData> loaded = objectMapper.readValue(
                    loginSessionsFile.toFile(),
                    new TypeReference<>() {
                    }
            );
            
            if (loaded == null) {
                log.debug("登录会话文件为空");
                return new HashMap<>();
            }
            
            Map<String, LoginSessionManager.LoginSession> sessionsMap = new HashMap<>();
            for (Map.Entry<String, LoginSessionData> entry : loaded.entrySet()) {
                LoginSessionManager.LoginSession session = entry.getValue().toSession();
                sessionsMap.put(entry.getKey(), session);
                log.debug("加载登录会话: key={}, state={}", entry.getKey(), session.getState());
            }
            
            log.debug("从文件加载了 {} 个登录会话", sessionsMap.size());
            return sessionsMap;
        } catch (Exception e) {
            log.error("加载登录会话失败: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * 持久化登录会话到文件
     */
    private void persistLoginSessions(Map<String, LoginSessionManager.LoginSession> loginSessionsMap) {
        try {
            // 转换为可序列化的格式
            Map<String, LoginSessionData> sessionDataMap = new HashMap<>();
            for (Map.Entry<String, LoginSessionManager.LoginSession> entry : loginSessionsMap.entrySet()) {
                LoginSessionManager.LoginSession session = entry.getValue();
                if (session != null) {
                    sessionDataMap.put(entry.getKey(), LoginSessionData.fromSession(session));
                }
            }
            
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(loginSessionsFile.toFile(), sessionDataMap);
            log.debug("已保存 {} 个登录会话到文件: {}", sessionDataMap.size(), loginSessionsFile);
        } catch (Exception e) {
            log.error("保存登录会话失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 登录会话数据（用于序列化）
     */
    @Data
    private static class LoginSessionData {
        // Getters and setters for Jackson
        private String state;
        private String loginMethod;
        private String account;
        private String password;
        private String conversationId;
        private String loginError;
        
        public static LoginSessionData fromSession(LoginSessionManager.LoginSession session) {
            LoginSessionData data = new LoginSessionData();
            data.state = session.getState() != null ? session.getState().name() : null;
            data.loginMethod = session.getLoginMethod() != null ? session.getLoginMethod().name() : null;
            data.account = session.getAccount();
            data.password = session.getPassword();
            data.conversationId = session.getConversationId();
            data.loginError = session.getLoginError();
            return data;
        }
        
        public LoginSessionManager.LoginSession toSession() {
            LoginSessionManager.LoginSession session = new LoginSessionManager.LoginSession();
            if (state != null) {
                try {
                    session.setState(LoginSessionManager.LoginSessionState.valueOf(state));
                } catch (IllegalArgumentException e) {
                    session.setState(LoginSessionManager.LoginSessionState.NOT_STARTED);
                }
            }
            if (loginMethod != null) {
                try {
                    session.setLoginMethod(LoginSessionManager.LoginMethod.valueOf(loginMethod));
                } catch (IllegalArgumentException e) {
                    // 忽略无效的登录方式
                }
            }
            session.setAccount(account);
            session.setPassword(password);
            session.setConversationId(conversationId);
            session.setLoginError(loginError);
            return session;
        }

    }
}


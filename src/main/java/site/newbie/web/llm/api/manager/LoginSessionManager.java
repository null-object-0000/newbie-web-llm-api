package site.newbie.web.llm.api.manager;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 登录会话管理器
 * 管理每个提供器的登录会话状态，使用本地存储持久化
 */
@Slf4j
@Component
public class LoginSessionManager {
    
    private final LoginStorageService storageService;
    
    public LoginSessionManager(LoginStorageService storageService) {
        this.storageService = storageService;
    }
    
    @PostConstruct
    public void init() {
        // 从存储服务加载登录会话（现在使用 providerName:conversationId 作为键）
        Map<String, LoginSession> loadedSessions = storageService.getAllLoginSessions();
        log.info("已从本地存储加载 {} 个登录会话（格式：providerName:conversationId）", loadedSessions.size());
    }
    
    /**
     * 登录会话状态
     */
    public enum LoginSessionState {
        /** 未开始登录 */
        NOT_STARTED,
        /** 等待选择登录方式 */
        WAITING_LOGIN_METHOD,
        /** 等待输入账号 */
        WAITING_ACCOUNT,
        /** 等待输入密码 */
        WAITING_PASSWORD,
        /** 登录中 */
        LOGGING_IN,
        /** 登录完成 */
        LOGGED_IN,
        /** 登录失败 */
        LOGIN_FAILED
    }
    
    /**
     * 登录方式
     */
    @Getter
    public enum LoginMethod {
        /** 手机号+验证码 */
        PHONE_VERIFY_CODE("1", "手机号+验证码登录"),
        /** 账号+密码 */
        ACCOUNT_PASSWORD("2", "账号+密码登录"),
        /** 微信扫码 */
        WECHAT_SCAN("3", "微信扫码登录");
        
        private final String code;
        private final String description;
        
        LoginMethod(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public static LoginMethod fromCode(String code) {
            for (LoginMethod method : values()) {
                if (method.code.equals(code)) {
                    return method;
                }
            }
            return null;
        }
    }
    
    /**
     * 登录会话信息
     */
    @Data
    public static class LoginSession {
        private LoginSessionState state = LoginSessionState.NOT_STARTED;
        private LoginMethod loginMethod;
        private String account; // 账号或手机号
        private String password; // 密码或验证码
        private String conversationId; // 对话ID，用于标识登录对话
        private String loginError; // 登录错误信息

        public boolean isLoginConversation() {
            return state != LoginSessionState.NOT_STARTED && 
                   state != LoginSessionState.LOGGED_IN;
        }
    }
    
    /**
     * 获取或创建登录会话（需要提供器名称和对话ID）
     */
    public LoginSession getOrCreateSession(String providerName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        LoginSession session = storageService.getLoginSession(providerName, conversationId);
        if (session == null) {
            session = new LoginSession();
            session.setConversationId(conversationId);
            storageService.saveLoginSession(providerName, conversationId, session);
        }
        return session;
    }
    
    /**
     * 获取登录会话（需要提供器名称和对话ID）
     */
    public LoginSession getSession(String providerName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        return storageService.getLoginSession(providerName, conversationId);
    }
    
    /**
     * 保存登录会话（自动持久化，需要提供器名称和对话ID）
     */
    public void saveSession(String providerName, String conversationId, LoginSession session) {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        storageService.saveLoginSession(providerName, conversationId, session);
    }
    
    /**
     * 检查对话是否是登录对话
     */
    public boolean isLoginConversation(String providerName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return false;
        }
        LoginSession session = getSession(providerName, conversationId);
        if (session == null) {
            return false;
        }
        return session.isLoginConversation() && conversationId.equals(session.getConversationId());
    }
    
    /**
     * 清除登录会话（需要提供器名称和对话ID）
     */
    public void clearSession(String providerName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        storageService.removeLoginSession(providerName, conversationId);
        log.info("已清除提供器 {} 的登录会话，对话ID: {}", providerName, conversationId);
    }
    
    /**
     * 标记登录成功
     */
    public void markLoggedIn(String providerName, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        LoginSession session = getSession(providerName, conversationId);
        if (session != null) {
            session.setState(LoginSessionState.LOGGED_IN);
            storageService.saveLoginSession(providerName, conversationId, session);
            log.info("提供器 {} 登录成功，对话ID: {}", providerName, conversationId);
        }
    }
    
    /**
     * 获取所有登录会话（用于检查是否有新会话被创建）
     */
    public Map<String, LoginSession> getAllLoginSessions() {
        return storageService.getAllLoginSessions();
    }
}


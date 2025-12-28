package site.newbie.web.llm.api.manager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.newbie.web.llm.api.provider.AccountInfo;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.ProviderRegistry;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 账号登录服务
 * 处理 Playwright 类提供器的账号登录流程
 */
@Slf4j
@Service
public class AccountLoginService {

    @Autowired
    private BrowserManager browserManager;
    
    @Autowired
    private ProviderRegistry providerRegistry;
    
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    // 登录会话：loginSessionId -> LoginSession
    private final Map<String, LoginSession> loginSessions = new ConcurrentHashMap<>();
    
    /**
     * 登录会话信息
     */
    @Data
    public static class LoginSession {
        private String sessionId;
        private String providerName;
        private String accountId;
        private String accountName; // 用户输入的账号名称
        private BrowserContext browserContext;
        private Page loginPage;
        private long createdAt;
        private LoginStatus status;
        
        public LoginSession() {
            this.sessionId = UUID.randomUUID().toString();
            this.createdAt = System.currentTimeMillis();
            this.status = LoginStatus.PENDING;
        }
    }
    
    /**
     * 登录状态
     */
    public enum LoginStatus {
        PENDING,      // 等待登录
        LOGGING_IN,   // 正在登录
        VERIFYING,    // 正在验证
        SUCCESS,      // 登录成功
        FAILED        // 登录失败
    }
    
    /**
     * 启动登录流程
     * 为指定账号创建独立的 Chrome 配置并启动浏览器
     * 如果浏览器被关闭，会自动重新启动并重试
     * @param providerName 提供器名称
     * @param accountId 账号ID
     * @param accountName 账号名称（用户输入的）
     * @return 登录会话ID
     */
    public String startLogin(String providerName, String accountId, String accountName) {
        // 检查提供器是否存在
        LLMProvider provider = providerRegistry.getProviderByName(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("提供器不存在: " + providerName);
        }
        
        // 创建登录会话
        LoginSession session = new LoginSession();
        session.setProviderName(providerName);
        session.setAccountId(accountId);
        session.setAccountName(accountName);
        session.setStatus(LoginStatus.PENDING);
        
        // 重试机制：如果浏览器被关闭，自动重新启动
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // 如果是重试，先清理旧的上下文
                if (attempt > 0) {
                    log.info("重试启动登录流程 (尝试 {}/{}): providerName={}, accountId={}", 
                            attempt + 1, maxRetries, providerName, accountId);
                    // 强制清理旧的上下文，确保重新创建
                    browserManager.clearContext(providerName, accountId);
                    // 等待一小段时间，确保资源释放
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                // 创建独立的 BrowserContext（使用 accountId）
                // 如果上下文已关闭，getOrCreateContext 会自动重新创建
                BrowserContext context = browserManager.getOrCreateContext(providerName, accountId);
                session.setBrowserContext(context);
                
                // 创建新页面并导航到登录页面
                Page page = context.newPage();
                session.setLoginPage(page);
                
                // 根据提供器导航到对应的登录页面
                String loginUrl = getLoginUrl(providerName);
                log.info("为提供器 {} 账号 {} 启动登录流程，导航到: {}", providerName, accountId, loginUrl);
                page.navigate(loginUrl);
                
                // 等待页面加载
                page.waitForLoadState();
                
                session.setStatus(LoginStatus.LOGGING_IN);
                loginSessions.put(session.getSessionId(), session);
                
                log.info("登录会话已创建: sessionId={}, providerName={}, accountId={}", 
                        session.getSessionId(), providerName, accountId);
                
                return session.getSessionId();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isBrowserClosed = errorMsg != null && (
                        errorMsg.contains("Target page, context or browser has been closed") ||
                        errorMsg.contains("TargetClosedError") ||
                        errorMsg.contains("Browser closed") ||
                        errorMsg.contains("Context closed") ||
                        errorMsg.contains("Connection closed") ||
                        errorMsg.contains("Playwright connection closed")
                );
                
                if (isBrowserClosed && attempt < maxRetries - 1) {
                    log.warn("浏览器或上下文已关闭，将自动重新启动 (尝试 {}/{}): {}", 
                            attempt + 1, maxRetries, errorMsg);
                    
                    // 清理会话中的旧引用
                    session.setBrowserContext(null);
                    session.setLoginPage(null);
                    
                    // 强制清理 BrowserManager 中的上下文缓存
                    browserManager.clearContext(providerName, accountId);
                    
                    // 等待一小段时间后重试，确保资源释放
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("启动登录流程时被中断", ie);
                    }
                    
                    // 继续重试
                    continue;
                } else {
                    // 不是浏览器关闭错误，或者已达到最大重试次数
                    log.error("启动登录流程失败: providerName={}, accountId={}, attempt={}/{}", 
                            providerName, accountId, attempt + 1, maxRetries, e);
                    throw new RuntimeException("启动登录流程失败: " + e.getMessage(), e);
                }
            }
        }
        
        // 理论上不会到达这里
        throw new RuntimeException("启动登录流程失败：已达到最大重试次数");
    }
    
    /**
     * 验证登录状态
     * 检查是否已登录，并验证账号是否一致
     * 如果浏览器被关闭，会自动重新启动并重试
     * @param sessionId 登录会话ID
     * @return 验证结果
     */
    public LoginVerificationResult verifyLogin(String sessionId) {
        LoginSession session = loginSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("登录会话不存在: " + sessionId);
        }
        
        int maxRetries = 2;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                session.setStatus(LoginStatus.VERIFYING);
                
                Page page = session.getLoginPage();
                BrowserContext context = session.getBrowserContext();
                
                // 检查页面和上下文是否有效
                boolean needRecreate = false;
                if (page == null || page.isClosed()) {
                    log.warn("登录页面已关闭，需要重新创建");
                    needRecreate = true;
                } else {
                    try {
                        // 尝试访问页面来检查是否真的有效
                        page.url();
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        if (errorMsg != null && (
                                errorMsg.contains("Target page, context or browser has been closed") ||
                                errorMsg.contains("TargetClosedError") ||
                                errorMsg.contains("Browser closed") ||
                                errorMsg.contains("Context closed")
                        )) {
                            log.warn("页面或上下文已关闭，需要重新创建: {}", errorMsg);
                            needRecreate = true;
                        } else {
                            throw e;
                        }
                    }
                }
                
                // 如果需要重新创建，尝试重新启动登录流程
                if (needRecreate && attempt < maxRetries - 1) {
                    log.info("重新创建登录页面 (尝试 {}/{})", attempt + 1, maxRetries);
                    
                    // 重新创建上下文和页面
                    context = browserManager.getOrCreateContext(session.getProviderName(), session.getAccountId());
                    session.setBrowserContext(context);
                    
                    page = context.newPage();
                    session.setLoginPage(page);
                    
                    // 重新导航到登录页面
                    String loginUrl = getLoginUrl(session.getProviderName());
                    log.info("重新导航到登录页面: {}", loginUrl);
                    page.navigate(loginUrl);
                    page.waitForLoadState();
                    
                    // 继续验证流程
                    continue;
                } else if (needRecreate) {
                    // 已达到最大重试次数
                    session.setStatus(LoginStatus.FAILED);
                    return LoginVerificationResult.failed("登录页面已关闭，无法验证登录状态");
                }
                
                // 等待页面稳定
                page.waitForLoadState();
                page.waitForTimeout(2000);
                
                // 获取提供器
                LLMProvider provider = providerRegistry.getProviderByName(session.getProviderName());
                if (provider == null) {
                    throw new RuntimeException("提供器不存在: " + session.getProviderName());
                }
                
                // 检查登录状态
                boolean isLoggedIn = provider.checkLoginStatus(page);
                if (!isLoggedIn) {
                    session.setStatus(LoginStatus.FAILED);
                    return LoginVerificationResult.failed("未检测到登录状态，请确保已完成登录");
                }
                
                // 获取实际登录的账号信息（包含账号标识和昵称）
                AccountInfo accountInfo = getActualAccountInfo(provider, page);
                if (accountInfo == null || accountInfo.getAccountId() == null || accountInfo.getAccountId().isEmpty()) {
                    session.setStatus(LoginStatus.FAILED);
                    return LoginVerificationResult.failed("无法获取登录账号信息");
                }
                
                String actualAccount = accountInfo.getAccountId();
                String nickname = accountInfo.getAccountName(); // accountName 字段存储的是昵称
                
                // 验证账号是否一致（允许部分匹配，因为用户可能输入的是邮箱前缀）
                boolean accountMatches = verifyAccountMatch(session.getAccountName(), actualAccount);
                if (!accountMatches) {
                    session.setStatus(LoginStatus.FAILED);
                    return LoginVerificationResult.failed(
                        String.format("账号不匹配。期望: %s, 实际: %s", session.getAccountName(), actualAccount));
                }
                
                // 登录成功，更新账号信息
                session.setStatus(LoginStatus.SUCCESS);
                
                LoginVerificationResult result = LoginVerificationResult.success(actualAccount, nickname);
                log.info("登录验证成功: sessionId={}, providerName={}, accountId={}, actualAccount={}, nickname={}", 
                        sessionId, session.getProviderName(), session.getAccountId(), actualAccount, nickname);
                
                return result;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isBrowserClosed = errorMsg != null && (
                        errorMsg.contains("Target page, context or browser has been closed") ||
                        errorMsg.contains("TargetClosedError") ||
                        errorMsg.contains("Browser closed") ||
                        errorMsg.contains("Context closed") ||
                        errorMsg.contains("Connection closed")
                );
                
                if (isBrowserClosed && attempt < maxRetries - 1) {
                    log.warn("浏览器或上下文已关闭，将重新创建 (尝试 {}/{})", attempt + 1, maxRetries);
                    // 清理会话中的旧引用
                    session.setBrowserContext(null);
                    session.setLoginPage(null);
                    
                    // 等待一小段时间后重试
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        session.setStatus(LoginStatus.FAILED);
                        return LoginVerificationResult.failed("验证登录时被中断");
                    }
                    
                    // 继续重试
                    continue;
                } else {
                    // 不是浏览器关闭错误，或者已达到最大重试次数
                    log.error("验证登录失败: sessionId={}, attempt={}/{}", sessionId, attempt + 1, maxRetries, e);
                    session.setStatus(LoginStatus.FAILED);
                    return LoginVerificationResult.failed("验证登录失败: " + e.getMessage());
                }
            }
        }
        
        // 理论上不会到达这里
        session.setStatus(LoginStatus.FAILED);
        return LoginVerificationResult.failed("验证登录失败：已达到最大重试次数");
    }
    
    /**
     * 获取登录会话信息
     */
    public LoginSession getLoginSession(String sessionId) {
        return loginSessions.get(sessionId);
    }
    
    /**
     * 清理登录会话
     */
    public void cleanupLoginSession(String sessionId) {
        LoginSession session = loginSessions.remove(sessionId);
        if (session != null && session.getLoginPage() != null && !session.getLoginPage().isClosed()) {
            try {
                session.getLoginPage().close();
            } catch (Exception e) {
                log.warn("关闭登录页面失败: sessionId={}", sessionId, e);
            }
        }
    }
    
    /**
     * 获取提供器的登录 URL
     */
    private String getLoginUrl(String providerName) {
        switch (providerName.toLowerCase()) {
            case "gemini":
                return "https://gemini.google.com";
            case "openai":
                return "https://chat.openai.com";
            case "deepseek":
                return "https://www.deepseek.com";
            default:
                throw new IllegalArgumentException("未知的提供器: " + providerName);
        }
    }
    
    /**
     * 从页面获取实际登录的账号信息
     * @return AccountInfo 包含账号标识和昵称
     */
    private AccountInfo getActualAccountInfo(LLMProvider provider, Page page) {
        try {
            // 使用提供器的 getCurrentAccountInfo 方法获取账号信息
            AccountInfo accountInfo = provider.getCurrentAccountInfo(page);
            if (accountInfo != null && accountInfo.isSuccess() && accountInfo.getAccountId() != null) {
                return accountInfo;
            }
            
            // 如果提供器不支持或获取失败，返回 null
            log.debug("提供器 {} 无法获取账号信息: {}", provider.getProviderName(), 
                    accountInfo != null ? accountInfo.getErrorMessage() : "返回 null");
            return null;
        } catch (Exception e) {
            log.warn("获取账号信息失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 从页面获取实际登录的账号标识（邮箱等）
     * @deprecated 使用 getActualAccountInfo 替代
     */
    @Deprecated
    private String getActualAccount(LLMProvider provider, Page page) {
        AccountInfo accountInfo = getActualAccountInfo(provider, page);
        return accountInfo != null ? accountInfo.getAccountId() : null;
    }
    
    /**
     * 从文本中提取邮箱地址
     */
    private String extractEmail(String text) {
        if (text == null) {
            return null;
        }
        Pattern pattern = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
    
    /**
     * 验证账号是否匹配
     * 匹配规则：
     * 1. 完全匹配（忽略大小写）
     * 2. 如果期望的账号包含 @，则必须完全匹配（忽略大小写）
     * 3. 如果期望的账号不包含 @，则检查是否是实际邮箱的前缀
     */
    private boolean verifyAccountMatch(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }

        String expectedLower = expected.toLowerCase().trim();
        String actualLower = actual.toLowerCase().trim();

        // 1. 完全匹配（忽略大小写）
        if (expectedLower.equals(actualLower)) {
            return true;
        }

        // 2. 如果期望的账号包含 @，则必须完全匹配
        if (expectedLower.contains("@")) {
            return expectedLower.equals(actualLower);
        }

        // 3. 如果期望的账号不包含 @，检查是否是实际邮箱的前缀
        // 例如：期望 "thien01657008216"，实际 "thien01657008216@gmail.com" 应该匹配
        if (actualLower.contains("@")) {
            String[] actualParts = actualLower.split("@");
            if (actualParts.length == 2) {
                String actualPrefix = actualParts[0];
                // 前缀完全匹配
                if (expectedLower.equals(actualPrefix)) {
                    return true;
                }
            }
        }

        return false;
    }
    
    /**
     * 登录验证结果
     */
    @Data
    public static class LoginVerificationResult {
        private boolean success;
        private String message;
        private String actualAccount;
        private String nickname; // 昵称
        
        public static LoginVerificationResult success(String actualAccount, String nickname) {
            LoginVerificationResult result = new LoginVerificationResult();
            result.setSuccess(true);
            result.setMessage("登录验证成功");
            result.setActualAccount(actualAccount);
            result.setNickname(nickname);
            return result;
        }
        
        public static LoginVerificationResult failed(String message) {
            LoginVerificationResult result = new LoginVerificationResult();
            result.setSuccess(false);
            result.setMessage(message);
            return result;
        }
    }
}


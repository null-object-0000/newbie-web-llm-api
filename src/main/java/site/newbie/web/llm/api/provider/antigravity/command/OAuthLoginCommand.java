package site.newbie.web.llm.api.provider.antigravity.command;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.antigravity.core.OAuthCallbackServer;
import site.newbie.web.llm.api.provider.antigravity.core.OAuthService;
import site.newbie.web.llm.api.provider.antigravity.core.ProjectResolver;
import site.newbie.web.llm.api.provider.antigravity.core.TokenManager;
import site.newbie.web.llm.api.provider.command.Command;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Antigravity OAuth 登录指令
 * 用法: /antigravity-login 或 /ag-login
 * 通过 Google OAuth 2.0 流程添加账号
 */
@Slf4j
public class OAuthLoginCommand implements Command {
    
    private final OAuthService oauthService;
    private final OAuthCallbackServer callbackServer;
    private final TokenManager tokenManager;
    private final ProjectResolver projectResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public OAuthLoginCommand(OAuthService oauthService, 
                            OAuthCallbackServer callbackServer,
                            TokenManager tokenManager,
                            ProjectResolver projectResolver) {
        this.oauthService = oauthService;
        this.callbackServer = callbackServer;
        this.tokenManager = tokenManager;
        this.projectResolver = projectResolver;
    }
    
    @Override
    public String getName() {
        return "antigravity-login";
    }
    
    @Override
    public String getDescription() {
        return "通过 Google OAuth 添加 Antigravity 账号";
    }
    
    @Override
    public String getExample() {
        return "/antigravity-login 或 /ag-login";
    }
    
    @Override
    public boolean requiresPage() {
        return false; // OAuth 登录不需要页面
    }
    
    @Override
    public boolean requiresLogin() {
        return false; // 登录指令本身不需要先登录
    }
    
    @Override
    public boolean requiresProvider() {
        return false; // 不需要 provider
    }
    
    @Override
    public boolean execute(Page page, ProgressCallback progressCallback, LLMProvider provider) {
        try {
            log.info("开始 Antigravity OAuth 登录流程");
            
            if (progressCallback == null) {
                log.warn("OAuth 登录指令需要进度回调");
                return false;
            }
            
            progressCallback.onProgress("正在启动 OAuth 回调服务器...");
            
            // 1. 启动回调服务器
            int port = callbackServer.start();
            String redirectUri = callbackServer.getRedirectUri();
            
            progressCallback.onProgress("回调服务器已启动: " + redirectUri);
            
            // 2. 生成授权 URL
            progressCallback.onProgress("正在生成授权 URL...");
            String authUrl = oauthService.getAuthUrl(redirectUri);
            
            progressCallback.onProgress("授权 URL 已生成");
            progressCallback.onProgress("请在浏览器中打开以下链接完成授权：");
            progressCallback.onProgress(authUrl);
            progressCallback.onProgress("（等待授权完成，最多 5 分钟）");
            
            // 3. 尝试打开浏览器（可选）
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(authUrl));
                progressCallback.onProgress("已自动打开浏览器");
            } catch (Exception e) {
                log.warn("无法自动打开浏览器: {}", e.getMessage());
                progressCallback.onProgress("请手动复制上面的链接到浏览器打开");
            }
            
            // 4. 等待授权码
            String sessionId = java.util.UUID.randomUUID().toString();
            String code;
            try {
                code = callbackServer.waitForCode(sessionId);
                progressCallback.onProgress("✅ 已收到授权码，正在交换 Token...");
            } catch (Exception e) {
                progressCallback.onProgress("❌ 等待授权码超时或失败: " + e.getMessage());
                callbackServer.cancel(sessionId);
                return false;
            }
            
            // 5. 交换 Token
            progressCallback.onProgress("正在交换 Token...");
            OAuthService.TokenResponse tokenResponse = oauthService.exchangeCode(code, redirectUri);
            
            if (tokenResponse.getRefreshToken() == null) {
                progressCallback.onProgress("⚠️ 警告: 未获取到 refresh_token");
                progressCallback.onProgress("可能原因：您之前已授权过此应用");
                progressCallback.onProgress("解决方法：请在 Google Cloud Console 撤销授权后重试");
                callbackServer.stop();
                return false;
            }
            
            progressCallback.onProgress("✅ Token 交换成功");
            
            // 6. 获取用户信息
            progressCallback.onProgress("正在获取用户信息...");
            OAuthService.UserInfo userInfo = oauthService.getUserInfo(tokenResponse.getAccessToken());
            
            progressCallback.onProgress("✅ 用户信息获取成功: " + userInfo.getEmail());
            
            // 7. 获取 Project ID
            progressCallback.onProgress("正在获取 Project ID...");
            String projectId;
            try {
                projectId = projectResolver.fetchProjectId(tokenResponse.getAccessToken());
                progressCallback.onProgress("✅ Project ID 获取成功: " + projectId);
            } catch (Exception e) {
                log.warn("获取 Project ID 失败，将使用随机生成的: {}", e.getMessage());
                projectId = projectResolver.generateMockProjectId();
                progressCallback.onProgress("⚠️ 使用随机生成的 Project ID: " + projectId);
            }
            
            // 8. 保存账号
            progressCallback.onProgress("正在保存账号信息...");
            String accountId = UUID.randomUUID().toString();
            long now = Instant.now().getEpochSecond();
            long expiryTimestamp = now + tokenResponse.getExpiresIn();
            
            saveAccount(accountId, userInfo.getEmail(), tokenResponse, projectId, expiryTimestamp);
            
            progressCallback.onProgress("✅ 账号保存成功！");
            progressCallback.onProgress("账号 ID: " + accountId);
            progressCallback.onProgress("邮箱: " + userInfo.getEmail());
            
            // 9. 重新加载账号
            try {
                tokenManager.loadAccounts();
                progressCallback.onProgress("✅ 账号已加载到内存");
            } catch (Exception e) {
                log.warn("重新加载账号失败: {}", e.getMessage());
            }
            
            progressCallback.onProgress("✅ OAuth 登录流程完成！");
            return true;
            
        } catch (Exception e) {
            log.error("OAuth 登录流程出错", e);
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 登录失败: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 保存账号到 JSON 文件
     */
    private void saveAccount(String accountId, String email, 
                           OAuthService.TokenResponse tokenResponse, 
                           String projectId, long expiryTimestamp) throws IOException {
        // 确保账号目录存在
        Path accountsDir = Paths.get(tokenManager.getAccountsDir());
        if (!Files.exists(accountsDir)) {
            Files.createDirectories(accountsDir);
        }
        
        // 构建账号 JSON
        ObjectNode account = objectMapper.createObjectNode();
        account.put("id", accountId);
        account.put("email", email);
        
        ObjectNode token = objectMapper.createObjectNode();
        token.put("access_token", tokenResponse.getAccessToken());
        token.put("refresh_token", tokenResponse.getRefreshToken());
        token.put("expires_in", tokenResponse.getExpiresIn());
        token.put("expiry_timestamp", expiryTimestamp);
        token.put("project_id", projectId);
        
        account.set("token", token);
        
        // 保存到文件（使用邮箱作为文件名的一部分，避免冲突）
        String safeEmail = email.replaceAll("[^a-zA-Z0-9]", "_");
        Path accountFile = accountsDir.resolve(safeEmail + "_" + accountId.substring(0, 8) + ".json");
        
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(accountFile.toFile(), account);
        
        log.info("账号已保存到: {}", accountFile);
    }
}


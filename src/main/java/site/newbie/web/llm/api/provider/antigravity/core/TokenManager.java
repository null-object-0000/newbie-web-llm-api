package site.newbie.web.llm.api.provider.antigravity.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Token 管理器
 * 管理 Google OAuth tokens，支持账号轮换和自动刷新
 */
@Slf4j
@Component
public class TokenManager {
    
    @Data
    public static class ProxyToken {
        private String accountId;
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private long timestamp;
        private String email;
        private Path accountPath;
        private String projectId;
    }
    
    private final List<ProxyToken> tokens = new ArrayList<>();
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${antigravity.accounts-dir:./antigravity-accounts}")
    private String accountsDir;
    
    private final OAuthService oauthService;
    
    public TokenManager(OAuthService oauthService) {
        this.oauthService = oauthService;
    }
    
    /**
     * 获取账号目录
     */
    public String getAccountsDir() {
        return accountsDir;
    }
    
    /**
     * 加载所有账号
     */
    public void loadAccounts() throws IOException {
        lock.writeLock().lock();
        try {
            tokens.clear();
            Path accountsPath = Paths.get(accountsDir);
            
            if (!Files.exists(accountsPath)) {
                log.warn("账号目录不存在: {}", accountsPath);
                Files.createDirectories(accountsPath);
                return;
            }
            
            Files.list(accountsPath)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        ProxyToken token = loadSingleAccount(path);
                        if (token != null) {
                            tokens.add(token);
                            log.info("加载账号: {} ({})", token.getEmail(), token.getAccountId());
                        }
                    } catch (Exception e) {
                        log.warn("加载账号失败 {}: {}", path, e.getMessage());
                    }
                });
            
            log.info("共加载 {} 个账号", tokens.size());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 加载单个账号
     */
    private ProxyToken loadSingleAccount(Path path) throws IOException {
        String content = Files.readString(path);
        JsonNode account = objectMapper.readTree(content);
        
        String accountId = account.get("id").asString();
        String email = account.get("email").asString();
        JsonNode tokenObj = account.get("token");
        
        String accessToken = tokenObj.get("access_token").asString();
        String refreshToken = tokenObj.get("refresh_token").asString();
        long expiresIn = tokenObj.get("expires_in").asLong();
        long timestamp = tokenObj.get("expiry_timestamp").asLong();
        
        String projectId = null;
        if (tokenObj.has("project_id")) {
            projectId = tokenObj.get("project_id").asString();
        }
        
        ProxyToken token = new ProxyToken();
        token.setAccountId(accountId);
        token.setEmail(email);
        token.setAccessToken(accessToken);
        token.setRefreshToken(refreshToken);
        token.setExpiresIn(expiresIn);
        token.setTimestamp(timestamp);
        token.setAccountPath(path);
        token.setProjectId(projectId);
        
        return token;
    }
    
    /**
     * 获取 Token（带轮换）
     */
    public TokenResult getToken(String requestType, boolean forceRotate) {
        lock.readLock().lock();
        try {
            if (tokens.isEmpty()) {
                throw new RuntimeException("Token pool is empty");
            }
            
            // 简单轮换策略
            int idx = currentIndex.getAndIncrement() % tokens.size();
            ProxyToken token = tokens.get(idx);
            
            // 检查 token 是否过期（提前5分钟刷新）
            long now = System.currentTimeMillis() / 1000;
            if (now >= token.getTimestamp() - 300) {
                log.info("账号 {} 的 token 即将过期，正在刷新...", token.getEmail());
                lock.readLock().unlock();
                
                // 切换到写锁进行刷新
                lock.writeLock().lock();
                try {
                    // 双重检查
                    if (now >= token.getTimestamp() - 300) {
                        OAuthService.TokenResponse refreshed = oauthService.refreshAccessToken(token.getRefreshToken());
                        token.setAccessToken(refreshed.getAccessToken());
                        token.setExpiresIn(refreshed.getExpiresIn());
                        token.setTimestamp(now + refreshed.getExpiresIn());
                        
                        // 保存到文件
                        saveTokenToFile(token);
                        
                        log.info("账号 {} 的 token 刷新成功", token.getEmail());
                    }
                } catch (Exception e) {
                    log.error("刷新 token 失败: {}", e.getMessage(), e);
                    throw new RuntimeException("Token 刷新失败: " + e.getMessage(), e);
                } finally {
                    lock.writeLock().unlock();
                }
                
                // 重新获取读锁
                lock.readLock().lock();
            }
            
            return new TokenResult(token.getAccessToken(), token.getProjectId(), token.getEmail());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 保存 token 到文件
     */
    private void saveTokenToFile(ProxyToken token) throws IOException {
        if (token.getAccountPath() == null) {
            log.warn("账号 {} 没有关联的文件路径，无法保存", token.getEmail());
            return;
        }
        
        JsonNode accountJson = objectMapper.readTree(token.getAccountPath().toFile());
        tools.jackson.databind.node.ObjectNode tokenObj = 
            (tools.jackson.databind.node.ObjectNode) accountJson.get("token");
        
        tokenObj.put("access_token", token.getAccessToken());
        tokenObj.put("expires_in", token.getExpiresIn());
        tokenObj.put("expiry_timestamp", token.getTimestamp());
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(token.getAccountPath().toFile(), accountJson);
        
        log.debug("已保存刷新后的 token 到文件: {}", token.getAccountPath());
    }
    
    public int size() {
        lock.readLock().lock();
        try {
            return tokens.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Data
    public static class TokenResult {
        private final String accessToken;
        private final String projectId;
        private final String email;
    }
}


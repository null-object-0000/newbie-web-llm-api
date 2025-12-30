package site.newbie.web.llm.api.provider.antigravity.core;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OAuth 服务
 * 处理 Google OAuth 2.0 授权流程
 */
@Slf4j
@Component
public class OAuthService {
    
    // Google OAuth 配置（与 Antigravity-Manager 保持一致）
    private static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "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";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${antigravity.accounts-dir:./antigravity-accounts}")
    private String accountsDir;
    
    public OAuthService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }
    
    /**
     * 生成 OAuth 授权 URL
     */
    public String getAuthUrl(String redirectUri) {
        String scopes = String.join(" ", 
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"
        );
        
        try {
            String params = String.format(
                "client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline&prompt=consent&include_granted_scopes=true",
                URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8),
                URLEncoder.encode(redirectUri, StandardCharsets.UTF_8),
                URLEncoder.encode(scopes, StandardCharsets.UTF_8)
            );
            
            return AUTH_URL + "?" + params;
        } catch (Exception e) {
            throw new RuntimeException("生成授权 URL 失败", e);
        }
    }
    
    /**
     * 使用 Authorization Code 交换 Token
     */
    public TokenResponse exchangeCode(String code, String redirectUri) throws Exception {
        String params = String.format(
            "client_id=%s&client_secret=%s&code=%s&redirect_uri=%s&grant_type=authorization_code",
            URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8),
            URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8),
            URLEncoder.encode(code, StandardCharsets.UTF_8),
            URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(params, StandardCharsets.UTF_8))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            TokenResponse tokenRes = new TokenResponse();
            tokenRes.setAccessToken(json.get("access_token").asString());
            tokenRes.setExpiresIn(json.get("expires_in").asLong());
            tokenRes.setTokenType(json.has("token_type") ? json.get("token_type").asString() : "Bearer");
            tokenRes.setRefreshToken(json.has("refresh_token") ? json.get("refresh_token").asString() : null);
            
            log.info("Token 交换成功! access_token: {}..., refresh_token: {}", 
                tokenRes.getAccessToken().substring(0, Math.min(20, tokenRes.getAccessToken().length())),
                tokenRes.getRefreshToken() != null ? "✓" : "✗ 缺失");
            
            if (tokenRes.getRefreshToken() == null) {
                log.warn("警告: Google 未返回 refresh_token。可能原因:\n" +
                    "1. 用户之前已授权过此应用\n" +
                    "2. 需要在 Google Cloud Console 撤销授权后重试\n" +
                    "3. OAuth 参数配置问题");
            }
            
            return tokenRes;
        } else {
            throw new RuntimeException("Token 交换失败: " + response.body());
        }
    }
    
    /**
     * 使用 refresh_token 刷新 access_token
     */
    public TokenResponse refreshAccessToken(String refreshToken) throws Exception {
        String params = String.format(
            "client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token",
            URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8),
            URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8),
            URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(params, StandardCharsets.UTF_8))
                .build();
        
        log.info("正在刷新 Token...");
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            TokenResponse tokenRes = new TokenResponse();
            tokenRes.setAccessToken(json.get("access_token").asString());
            tokenRes.setExpiresIn(json.get("expires_in").asLong());
            tokenRes.setTokenType(json.has("token_type") ? json.get("token_type").asString() : "Bearer");
            // 刷新时可能不返回新的 refresh_token，保留原有的
            tokenRes.setRefreshToken(refreshToken);
            
            log.info("Token 刷新成功！有效期: {} 秒", tokenRes.getExpiresIn());
            return tokenRes;
        } else {
            throw new RuntimeException("刷新失败: " + response.body());
        }
    }
    
    /**
     * 获取用户信息
     */
    public UserInfo getUserInfo(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USERINFO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            UserInfo userInfo = new UserInfo();
            userInfo.setEmail(json.get("email").asString());
            userInfo.setName(json.has("name") ? json.get("name").asString() : null);
            userInfo.setGivenName(json.has("given_name") ? json.get("given_name").asString() : null);
            userInfo.setFamilyName(json.has("family_name") ? json.get("family_name").asString() : null);
            userInfo.setPicture(json.has("picture") ? json.get("picture").asString() : null);
            return userInfo;
        } else {
            throw new RuntimeException("获取用户信息失败: " + response.body());
        }
    }
    
    @Data
    public static class TokenResponse {
        private String accessToken;
        private long expiresIn;
        private String tokenType;
        private String refreshToken;
    }
    
    @Data
    public static class UserInfo {
        private String email;
        private String name;
        private String givenName;
        private String familyName;
        private String picture;
        
        public String getDisplayName() {
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
            if (givenName != null && familyName != null) {
                return givenName + " " + familyName;
            }
            if (givenName != null) {
                return givenName;
            }
            if (familyName != null) {
                return familyName;
            }
            return email;
        }
    }
}


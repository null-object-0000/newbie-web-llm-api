package site.newbie.web.llm.api.provider.antigravity.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 上游客户端
 * 直接调用 Google v1internal API
 */
@Slf4j
@Component
public class UpstreamClient {
    
    private static final String V1_INTERNAL_BASE_URL = "https://cloudcode-pa.googleapis.com/v1internal";
    private static final String USER_AGENT = "antigravity/1.11.9 windows/amd64";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public UpstreamClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    /**
     * 构建 v1internal URL
     */
    private String buildUrl(String method, String queryString) {
        if (queryString != null && !queryString.isEmpty()) {
            return String.format("%s:%s?%s", V1_INTERNAL_BASE_URL, method, queryString);
        } else {
            return String.format("%s:%s", V1_INTERNAL_BASE_URL, method);
        }
    }
    
    /**
     * 调用 v1internal API
     */
    public HttpResponse<java.io.InputStream> callV1Internal(
            String method,
            String accessToken,
            JsonNode body,
            String queryString) throws Exception {
        
        String url = buildUrl(method, queryString);
        String requestBody = objectMapper.writeValueAsString(body);
        
        log.debug("调用 v1internal API: {} (method: {})", url, method);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(600))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
    
    /**
     * 获取可用模型列表
     */
    public JsonNode fetchAvailableModels(String accessToken) throws Exception {
        String url = buildUrl("fetchAvailableModels", null);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(600))
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Upstream error: " + response.statusCode());
        }
        
        return objectMapper.readTree(response.body());
    }
}


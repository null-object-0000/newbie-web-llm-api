package site.newbie.web.llm.api.provider.antigravity.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Project ID 解析器
 * 通过 loadCodeAssist API 获取 project_id
 */
@Slf4j
@Component
public class ProjectResolver {
    
    private static final String LOAD_CODE_ASSIST_URL = "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist";
    private static final String USER_AGENT = "antigravity/1.11.9 windows/amd64";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public ProjectResolver() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }
    
    /**
     * 获取 project_id
     */
    public String fetchProjectId(String accessToken) throws Exception {
        String requestBody = """
            {
                "metadata": {
                    "ideType": "ANTIGRAVITY"
                }
            }
            """;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOAD_CODE_ASSIST_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Host", "cloudcode-pa.googleapis.com")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (!response.body().isEmpty() && response.statusCode() == 200) {
            JsonNode data = objectMapper.readTree(response.body());
            if (data.has("cloudaicompanionProject")) {
                String projectId = data.get("cloudaicompanionProject").asString();
                log.info("成功获取 project_id: {}", projectId);
                return projectId;
            }
        }
        
        // 如果无法获取，生成随机 project_id 作为兜底
        String mockId = generateMockProjectId();
        log.warn("账号无资格获取官方 cloudaicompanionProject，将使用随机生成的 Project ID 作为兜底: {}", mockId);
        return mockId;
    }
    
    /**
     * 生成随机 project_id（当无法从 API 获取时使用）
     * 格式：{形容词}-{名词}-{5位随机字符}
     */
    public String generateMockProjectId() {
        String[] adjectives = {"useful", "bright", "swift", "calm", "bold"};
        String[] nouns = {"fuze", "wave", "spark", "flow", "core"};
        
        Random rng = new Random();
        String adj = adjectives[rng.nextInt(adjectives.length)];
        String noun = nouns[rng.nextInt(nouns.length)];
        
        // 生成5位随机字符（base36）
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder randomStr = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            randomStr.append(chars.charAt(rng.nextInt(chars.length())));
        }
        
        return String.format("%s-%s-%s", adj, noun, randomStr);
    }
}


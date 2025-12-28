package site.newbie.web.llm.api.provider.antigravity.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 响应映射器
 * 将 Gemini SSE 流转换为 OpenAI SSE 格式
 */
@Slf4j
@Component
public class ResponseMapper {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 处理 SSE 流式响应
     */
    public void processStreamResponse(
            InputStream inputStream,
            String model,
            ResponseHandler handler) throws Exception {
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            String line;
            String id = "chatcmpl-" + UUID.randomUUID();
            StringBuilder buffer = new StringBuilder();
            
            while ((line = reader.readLine()) != null) {
                // 累积多行数据（处理可能的分块）
                if (line.trim().isEmpty()) {
                    // 空行表示一个完整的 SSE 事件结束
                    if (buffer.length() > 0) {
                        String data = buffer.toString().trim();
                        if (data.startsWith("data: ")) {
                            String jsonStr = data.substring(6).trim();
                            
                            if (jsonStr.equals("[DONE]")) {
                                log.info("收到完成标记");
                                break;
                            }
                            
                            if (!jsonStr.isEmpty()) {
                                processChunk(jsonStr, id, model, handler);
                            }
                        }
                        buffer.setLength(0);
                    }
                    continue;
                }
                
                buffer.append(line).append("\n");
            }
            
            // 处理最后的数据（如果没有空行结尾）
            if (buffer.length() > 0) {
                String data = buffer.toString().trim();
                if (data.startsWith("data: ")) {
                    String jsonStr = data.substring(6).trim();
                    if (!jsonStr.equals("[DONE]") && !jsonStr.isEmpty()) {
                        processChunk(jsonStr, id, model, handler);
                    }
                }
            }
        }
    }
    
    /**
     * 处理单个 SSE 数据块
     */
    private void processChunk(String jsonStr, String id, String model, ResponseHandler handler) {
        try {
            JsonNode json = objectMapper.readTree(jsonStr);
            
            // 处理 v1internal wrapper
            JsonNode actualData = json.has("response") ? json.get("response") : json;
            
            // 提取 candidates
            JsonNode candidates = actualData.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
                return;
            }
            
            JsonNode candidate = candidates.get(0);
            JsonNode content = candidate.get("content");
            if (content == null) {
                return;
            }
            
            JsonNode parts = content.get("parts");
            if (parts == null || !parts.isArray()) {
                return;
            }
            
            // 提取文本内容
            StringBuilder contentOut = new StringBuilder();
            for (JsonNode part : parts) {
                // 思维链/推理部分
                if (part.has("thought")) {
                    String thought = part.get("thought").asString();
                    if (!thought.isEmpty()) {
                        contentOut.append("<thought>\n").append(thought).append("\n</thought>\n\n");
                    }
                }
                
                // 文本部分
                if (part.has("text")) {
                    contentOut.append(part.get("text").asString());
                }
                
                // 捕获 thoughtSignature
                if (part.has("thoughtSignature") || part.has("thought_signature")) {
                    String sig = part.has("thoughtSignature") 
                        ? part.get("thoughtSignature").asString()
                        : part.get("thought_signature").asString();
                    // TODO: 存储到全局状态
                    log.debug("捕获 thoughtSignature: {}", sig.length());
                }
            }
            
            // 处理联网搜索引文
            if (candidate.has("groundingMetadata")) {
                JsonNode grounding = candidate.get("groundingMetadata");
                StringBuilder groundingText = new StringBuilder();
                
                // 搜索词
                if (grounding.has("webSearchQueries")) {
                    JsonNode queries = grounding.get("webSearchQueries");
                    if (queries.isArray() && queries.size() > 0) {
                        List<String> queryList = new java.util.ArrayList<>();
                        for (JsonNode query : queries) {
                            if (query.isString()) {
                                queryList.add(query.asString());
                            }
                        }
                        if (!queryList.isEmpty()) {
                            groundingText.append("\n\n---\n**🔍 已为您搜索：** ");
                            groundingText.append(String.join(", ", queryList));
                        }
                    }
                }
                
                // 来源链接
                if (grounding.has("groundingChunks")) {
                    JsonNode chunks = grounding.get("groundingChunks");
                    if (chunks.isArray() && chunks.size() > 0) {
                        List<String> links = new java.util.ArrayList<>();
                        for (int i = 0; i < chunks.size(); i++) {
                            JsonNode chunk = chunks.get(i);
                            if (chunk.has("web")) {
                                JsonNode web = chunk.get("web");
                                String title = web.has("title") ? web.get("title").asString() : "网页来源";
                                String uri = web.has("uri") ? web.get("uri").asString() : "#";
                                links.add(String.format("[%d] [%s](%s)", i + 1, title, uri));
                            }
                        }
                        if (!links.isEmpty()) {
                            groundingText.append("\n\n**🌐 来源引文：**\n");
                            groundingText.append(String.join("\n", links));
                        }
                    }
                }
                
                if (groundingText.length() > 0) {
                    contentOut.append(groundingText);
                }
            }
            
            // 发送内容块
            if (contentOut.length() > 0) {
                handler.onChunk(id, contentOut.toString(), model);
            }
            
            // 提取 finish_reason
            if (candidate.has("finishReason")) {
                String finishReason = candidate.get("finishReason").asString();
                String mappedReason = switch (finishReason) {
                    case "STOP" -> "stop";
                    case "MAX_TOKENS" -> "length";
                    case "SAFETY", "RECITATION" -> "content_filter";
                    default -> "stop";
                };
                handler.onFinish(mappedReason);
            }
            
        } catch (Exception e) {
            log.warn("处理 SSE 数据块时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 响应处理器接口
     */
    public interface ResponseHandler {
        void onChunk(String id, String content, String model);
        void onFinish(String finishReason);
    }
}


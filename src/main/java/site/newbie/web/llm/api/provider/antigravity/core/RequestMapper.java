package site.newbie.web.llm.api.provider.antigravity.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 请求映射器
 * 将 OpenAI 格式的请求转换为 Gemini v1internal 格式
 */
@Slf4j
@Component
public class RequestMapper {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 转换 OpenAI 请求为 Gemini v1internal 格式
     */
    public JsonNode transformOpenAIRequest(ChatCompletionRequest request, String projectId, String mappedModel) {
        ObjectNode geminiBody = objectMapper.createObjectNode();
        
        // 1. 提取 System Messages
        List<String> systemInstructions = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatCompletionRequest.Message msg : request.getMessages()) {
                if ("system".equals(msg.getRole()) && msg.getContent() != null) {
                    systemInstructions.add(msg.getContent());
                }
            }
        }
        
        // 注入 Codex/Coding Agent 补丁
        systemInstructions.add("You are a coding agent. You MUST use the provided 'shell' tool to perform ANY filesystem operations (reading, writing, creating files). Do not output JSON code blocks for tool execution; invoke the functions directly. To create a file, use the 'shell' tool with 'New-Item' or 'Set-Content' (Powershell). NEVER simulate/hallucinate actions in text without calling the tool first.");
        
        // 2. 构建 Gemini contents（过滤掉 system）
        ArrayNode contents = objectMapper.createArrayNode();
        if (request.getMessages() != null) {
            for (ChatCompletionRequest.Message msg : request.getMessages()) {
                if ("system".equals(msg.getRole())) {
                    continue;
                }
                
                String role = switch (msg.getRole()) {
                    case "assistant" -> "model";
                    case "tool", "function" -> "user";
                    default -> msg.getRole();
                };
                
                ObjectNode contentNode = objectMapper.createObjectNode();
                contentNode.put("role", role);
                
                ArrayNode parts = objectMapper.createArrayNode();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    if (role.equals("user") && mappedModel.contains("gemini-3")) {
                        // 为 Gemini 3 用户消息添加提醒补丁
                        String reminder = "\n\n(SYSTEM REMINDER: You MUST use the 'shell' tool to perform this action. Do not simply state it is done.)";
                        parts.add(objectMapper.createObjectNode().put("text", msg.getContent() + reminder));
                    } else {
                        parts.add(objectMapper.createObjectNode().put("text", msg.getContent()));
                    }
                }
                
                contentNode.set("parts", parts);
                contents.add(contentNode);
            }
        }
        
        // 3. 构建 generationConfig
        ObjectNode genConfig = objectMapper.createObjectNode();
        genConfig.put("maxOutputTokens", 64000);
        genConfig.put("temperature", 1.0);
        genConfig.put("topP", 1.0);
        
        // 4. 构建请求体
        ObjectNode innerRequest = objectMapper.createObjectNode();
        innerRequest.set("contents", contents);
        innerRequest.set("generationConfig", genConfig);
        
        // Safety settings
        ArrayNode safetySettings = objectMapper.createArrayNode();
        String[] categories = {"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", 
                              "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT",
                              "HARM_CATEGORY_CIVIC_INTEGRITY"};
        for (String category : categories) {
            ObjectNode setting = objectMapper.createObjectNode();
            setting.put("category", category);
            setting.put("threshold", "OFF");
            safetySettings.add(setting);
        }
        innerRequest.set("safetySettings", safetySettings);
        
        // System instruction
        if (!systemInstructions.isEmpty()) {
            ObjectNode systemInstruction = objectMapper.createObjectNode();
            ArrayNode systemParts = objectMapper.createArrayNode();
            systemParts.add(objectMapper.createObjectNode().put("text", String.join("\n\n", systemInstructions)));
            systemInstruction.set("parts", systemParts);
            innerRequest.set("systemInstruction", systemInstruction);
        }
        
        // 5. 构建最终请求
        geminiBody.put("project", projectId);
        geminiBody.put("requestId", "openai-" + UUID.randomUUID());
        geminiBody.set("request", innerRequest);
        geminiBody.put("model", mappedModel);
        geminiBody.put("userAgent", "antigravity");
        geminiBody.put("requestType", "agent");
        
        return geminiBody;
    }
}


package site.newbie.web.llm.api.util;

import site.newbie.web.llm.api.model.ChatCompletionRequest;

import java.util.List;

/**
 * 对话 ID 工具类
 * 提供从历史消息中提取对话 ID 的通用能力
 */
public class ConversationIdUtils {
    
    /**
     * 对话 ID 标记格式
     */
    private static final String CONVERSATION_ID_MARKER = "```nwla-conversation-id";
    
    /**
     * 从 ChatCompletionRequest 中提取对话 ID
     * 
     * @param request 聊天请求
     * @param excludeLoginConversation 是否排除登录对话（以 "login-" 开头的 ID）
     * @return 对话 ID，如果未找到则返回 null
     */
    public static String extractConversationIdFromRequest(ChatCompletionRequest request, boolean excludeLoginConversation) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        return extractConversationIdFromMessages(request.getMessages(), excludeLoginConversation);
    }
    
    /**
     * 从消息列表中提取对话 ID
     * 
     * @param messages 消息列表
     * @param excludeLoginConversation 是否排除登录对话（以 "login-" 开头的 ID）
     * @return 对话 ID，如果未找到则返回 null
     */
    public static String extractConversationIdFromMessages(List<ChatCompletionRequest.Message> messages, boolean excludeLoginConversation) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        
        // 从后往前查找最后一条 assistant 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                String extractedId = extractConversationIdFromContent(content, excludeLoginConversation);
                if (extractedId != null && !extractedId.isEmpty()) {
                    return extractedId;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 从消息内容中提取对话 ID
     * 支持格式：```nwla-conversation-id\n{id}\n```
     * 
     * @param content 消息内容
     * @param excludeLoginConversation 是否排除登录对话（以 "login-" 开头的 ID）
     * @return 对话 ID，如果未找到则返回 null
     */
    public static String extractConversationIdFromContent(String content, boolean excludeLoginConversation) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        // 检查标记格式：```nwla-conversation-id\n{id}\n```
        int startIdx = content.indexOf(CONVERSATION_ID_MARKER);
        if (startIdx == -1) {
            return null;
        }
        
        // 找到开始标记，查找结束标记 ```
        int afterMarker = startIdx + CONVERSATION_ID_MARKER.length();
        // 跳过可能的换行
        while (afterMarker < content.length() && 
               (content.charAt(afterMarker) == '\n' || content.charAt(afterMarker) == '\r')) {
            afterMarker++;
        }
        
        // 查找结束的 ```
        int endIdx = content.indexOf("```", afterMarker);
        if (endIdx == -1 || endIdx <= afterMarker) {
            return null;
        }
        
        String extractedId = content.substring(afterMarker, endIdx).trim();
        // 提取第一行非空内容
        extractedId = extractedId.lines()
            .filter(line -> !line.trim().isEmpty() && !line.contains("```") && !line.contains("nwla-conversation-id"))
            .findFirst()
            .orElse("")
            .trim();
        
        if (extractedId.isEmpty()) {
            return null;
        }
        
        // 如果需要排除登录对话，检查是否以 "login-" 开头
        if (excludeLoginConversation && extractedId.startsWith("login-")) {
            return null;
        }
        
        return extractedId;
    }
    
    /**
     * 从 ChatCompletionRequest 中提取对话 ID（默认排除登录对话）
     * 
     * @param request 聊天请求
     * @return 对话 ID，如果未找到则返回 null
     */
    public static String extractConversationIdFromRequest(ChatCompletionRequest request) {
        return extractConversationIdFromRequest(request, true);
    }
    
    /**
     * 从消息列表中提取对话 ID（默认排除登录对话）
     * 
     * @param messages 消息列表
     * @return 对话 ID，如果未找到则返回 null
     */
    public static String extractConversationIdFromMessages(List<ChatCompletionRequest.Message> messages) {
        return extractConversationIdFromMessages(messages, true);
    }
}


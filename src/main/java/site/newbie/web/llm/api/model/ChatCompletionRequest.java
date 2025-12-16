package site.newbie.web.llm.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略 OpenAI SDK 可能发送的未知字段
public class ChatCompletionRequest {

    // 模型名称 (例如 "deepseek-web")
    private String model;

    // 聊天上下文消息列表
    private List<Message> messages;

    // 是否开启流式输出 (打字机效果)
    @Builder.Default
    private Boolean stream = false;
    
    // 是否新开对话（true: 新对话，false: 继续当前对话）
    @Builder.Default
    private Boolean newConversation = false;

    // 是否启用联网搜索（仅对支持的模型生效，例如 DeepSeek Web 搜索）
    @Builder.Default
    private Boolean webSearch = false;
    
    // 对话 URL（用于继续特定对话）
    private String conversationUrl;
    
    // 便捷方法：确保返回非 null 值
    public boolean isStream() {
        return stream != null && stream;
    }
    
    public boolean isNewConversation() {
        return newConversation != null && newConversation;
    }

    public boolean isWebSearch() {
        return webSearch != null && webSearch;
    }

    // 内部类：消息结构
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        // "user", "assistant", "system"
        private String role;
        
        // 消息内容
        private String content;
    }
}
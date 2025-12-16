package site.newbie.web.llm.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatCompletionResponse {

    private String id;
    
    // 固定为 "chat.completion" 或 "chat.completion.chunk"
    private String object;
    
    private long created;
    
    private String model;
    
    private List<Choice> choices;

    @Data
    @Builder
    public static class Choice {
        private int index;
        
        // 非流式模式下使用 message
        private ChatCompletionRequest.Message message;
        
        // 流式模式下使用 delta
        private Delta delta;

        // 停止原因 (null, "stop", "length")
        @JsonProperty("finish_reason")
        private String finishReason;
    }
    
    /**
     * 流式响应中的增量数据
     * 兼容 OpenAI API 格式，支持 reasoning_content（思考内容）和 content（最终回复）
     */
    @Data
    @Builder
    public static class Delta {
        // 角色（通常为 "assistant"）
        private String role;
        
        // 最终回复内容
        private String content;
        
        // 思考内容（reasoning content，用于 o1/o3 等推理模型）
        @JsonProperty("reasoning_content")
        private String reasoningContent;
    }
}
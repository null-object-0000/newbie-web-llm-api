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
        private ChatCompletionRequest.Message delta;

        // 停止原因 (null, "stop", "length")
        @JsonProperty("finish_reason")
        private String finishReason;
    }
}
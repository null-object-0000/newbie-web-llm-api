package site.newbie.web.llm.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI 格式的模型列表响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelResponse {
    
    @JsonProperty("object")
    @Builder.Default
    private String object = "list";
    
    private List<ModelData> data;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelData {
        private String id;
        
        @JsonProperty("object")
        @Builder.Default
        private String object = "model";
        
        private Long created;
        
        @JsonProperty("owned_by")
        private String ownedBy;
    }
}


package site.newbie.web.llm.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationResponse {
    
    /**
     * 创建时间戳
     */
    private Long created;
    
    /**
     * 生成的图片数据列表
     */
    private List<ImageData> data;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageData {
        /**
         * 图片 URL（当 response_format 为 url 时）
         */
        private String url;
        
        /**
         * Base64 编码的图片（当 response_format 为 b64_json 时）
         */
        @JsonProperty("b64_json")
        private String b64Json;
        
        /**
         * 修订后的提示词（dall-e-3 会优化用户输入的提示词）
         */
        @JsonProperty("revised_prompt")
        private String revisedPrompt;
    }
}


package site.newbie.web.llm.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageGenerationRequest {
    
    /**
     * 模型名称（可选，默认使用 gemini-web-imagegen）
     * OpenAI 格式：dall-e-2, dall-e-3
     * 本实现：gemini-web-imagegen
     */
    @Builder.Default
    private String model = "gemini-web-imagegen";
    
    /**
     * 图片描述提示词（必需）
     */
    private String prompt;
    
    /**
     * 生成图片数量（1-10，默认 1）
     */
    @Builder.Default
    private Integer n = 1;
    
    /**
     * 图片尺寸
     * OpenAI 支持：256x256, 512x512, 1024x1024（dall-e-2）
     *               1024x1024, 1792x1024, 1024x1792（dall-e-3）
     * 本实现：默认 1024x1024，实际由 Gemini 决定
     */
    @Builder.Default
    private String size = "1024x1024";
    
    /**
     * 图片质量（仅 dall-e-3）
     * standard 或 hd
     * 本实现：忽略此参数，由 Gemini 决定
     */
    private String quality;
    
    /**
     * 响应格式
     * url 或 b64_json
     * 本实现：默认 b64_json（与 OpenAI 兼容）
     */
    @JsonProperty("response_format")
    @Builder.Default
    private String responseFormat = "b64_json";
    
    /**
     * 用户标识（用于监控和滥用检测）
     */
    private String user;
    
    public boolean isStream() {
        return false; // 图片生成不支持流式
    }
    
    public int getN() {
        return n != null ? Math.max(1, Math.min(10, n)) : 1;
    }
}


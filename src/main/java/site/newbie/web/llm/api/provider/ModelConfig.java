package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;

import java.io.IOException;
import java.util.Map;

/**
 * 通用模型配置接口
 * 定义模型特定的行为和配置，所有提供者的模型配置都应实现此接口
 * 
 * @param <C> 监听上下文类型，由具体提供者定义
 */
public interface ModelConfig<C> {
    
    /**
     * 获取模型名称
     */
    String getModelName();
    
    /**
     * 配置页面
     * 根据模型特性配置页面状态（如启用/关闭特定功能）
     * 
     * @param page 页面对象
     */
    void configure(Page page);
    
    /**
     * 监听 AI 回复
     * 根据模型特性处理回复内容
     * 
     * @param context 监听上下文，由具体提供者定义
     */
    void monitorResponse(C context) throws IOException, InterruptedException;
    
    // ==================== 通用记录类型 ====================
    
    /**
     * SSE 解析结果
     */
    record SseParseResult(String thinkingContent, String responseContent, boolean finished) {}
    
    /**
     * 解析结果带索引
     */
    record ParseResultWithIndex(SseParseResult result, Integer lastActiveFragmentIndex) {}
    
    // ==================== 响应处理器接口 ====================
    
    /**
     * 响应处理器接口
     * 提供发送 SSE 数据的能力，由 Provider 实现并传递给 Config
     */
    interface ResponseHandler {
        void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException;
        void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException;
        void sendReplace(SseEmitter emitter, String id, String content, String model) throws IOException;
        void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException;
        String getSseData(Page page, String varName);
        ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex);
        String extractTextFromSse(String sseData);
    }
}


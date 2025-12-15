package site.newbie.web.llm.api.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 提供者基类，提供通用的 SSE 发送方法
 */
@RequiredArgsConstructor
public abstract class BaseProvider implements LLMProvider {
    
    protected final ObjectMapper objectMapper;
    
    /**
     * 发送 SSE 数据块
     */
    protected void sendSseChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionRequest.Message.builder().content(content).build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送整体替换消息（用于修正完整内容）
     * 使用特殊标记 __REPLACE__ 来标识这是整体替换消息
     */
    protected void sendSseReplace(SseEmitter emitter, String id, String fullContent, String model) throws IOException {
        // 在内容前添加特殊标记，前端识别后会替换整个内容
        String markedContent = "__REPLACE__" + fullContent;
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionRequest.Message.builder().content(markedContent).build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送思考内容（用于深度思考模式）
     * 使用特殊标记 __THINKING__ 来标识这是思考内容
     */
    protected void sendThinkingContent(SseEmitter emitter, String id, String thinkingContent, String model) throws IOException {
        // 在内容前添加特殊标记，前端识别后会显示为思考内容
        String markedContent = "__THINKING__" + thinkingContent;
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionRequest.Message.builder().content(markedContent).build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
    
    /**
     * 发送完成标记
     */
    protected void sendDone(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }
    
    /**
     * 发送对话 URL（用于前端保存）
     * 使用特殊标记 __URL__ 来标识这是 URL 信息
     */
    protected void sendConversationUrl(SseEmitter emitter, String id, String url, String model) throws IOException {
        // 在内容前添加特殊标记，前端识别后会保存 URL
        String markedContent = "__URL__" + url;
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionRequest.Message.builder().content(markedContent).build())
                .index(0)
                .build();

        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id)
                .object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(List.of(choice))
                .build();

        String json = objectMapper.writeValueAsString(response);
        emitter.send(SseEmitter.event().data(json));
    }
}


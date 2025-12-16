package site.newbie.web.llm.api.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 示例提供者 - 展示如何添加新的提供者
 * 这个提供者返回固定的回复，用于测试
 */
@Slf4j
@Component
public class ExampleProvider implements LLMProvider {
    
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    public ExampleProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Override
    public String getProviderName() {
        return "example";
    }
    
    @Override
    public List<String> getSupportedModels() {
        return Arrays.asList("example-model-1", "example-model-2");
    }
    
    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                String id = UUID.randomUUID().toString();
                String response = "这是一个示例提供者的回复。模型: " + request.getModel() + "\n\n" +
                        "你发送的消息是: " + request.getMessages().get(request.getMessages().size() - 1).getContent();
                
                // 模拟流式输出
                String[] words = response.split("");
                for (int i = 0; i < words.length; i++) {
                    sendSseChunk(emitter, id, words[i], request.getModel());
                    Thread.sleep(50); // 模拟延迟
                }
                
                sendDone(emitter);
            } catch (Exception e) {
                log.error("Example Provider Error", e);
                emitter.completeWithError(e);
            }
        });
    }
    
    private void sendSseChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response)));
    }
    
    private void sendDone(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }
}

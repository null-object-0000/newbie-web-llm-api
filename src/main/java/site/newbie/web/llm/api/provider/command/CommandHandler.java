package site.newbie.web.llm.api.provider.command;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 公共指令处理器
 * 处理所有 provider 的指令执行逻辑，包括全局指令和 provider 特定指令
 */
@Slf4j
public class CommandHandler {
    
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    
    /**
     * 指令执行成功后的回调接口
     * 用于处理 provider 特定的后处理逻辑（如保存 conversationId）
     */
    @FunctionalInterface
    public interface CommandSuccessCallback {
        /**
         * 指令执行成功后的回调
         * @param page 执行指令时使用的页面（可能为 null，如 help 指令）
         * @param model 模型名称
         * @param emitter SSE 发射器
         * @param finalMessage 最终消息
         * @param allSuccess 是否所有指令都执行成功
         * @return 是否已发送结果（如果返回 true，CommandHandler 将不再发送结果）
         */
        boolean onSuccess(Page page, String model, SseEmitter emitter, String finalMessage, boolean allSuccess);
    }
    
    /**
     * 处理纯指令对话（只包含指令，没有其他内容）
     *
     * @param request 聊天请求
     * @param emitter SSE 发射器
     * @param provider LLMProvider 实例
     * @param commandParser 指令解析器
     * @param getOrCreatePageFunction 获取或创建页面的函数
     * @param getConversationIdFunction 获取对话ID的函数
     * @param isNewConversationFunction 判断是否是新对话的函数
     * @param objectMapper JSON 对象映射器
     * @param successCallback 指令执行成功后的回调（可选，用于处理 provider 特定的逻辑）
     * @param releaseLockCallback 释放锁的回调（可选，用于在指令执行完成后立即释放锁）
     */
    public static void handleCommandOnly(
            ChatCompletionRequest request,
            SseEmitter emitter,
            LLMProvider provider,
            CommandParser commandParser,
            Function<ChatCompletionRequest, Page> getOrCreatePageFunction,
            Function<ChatCompletionRequest, String> getConversationIdFunction,
            Function<ChatCompletionRequest, Boolean> isNewConversationFunction,
            ObjectMapper objectMapper,
            CommandSuccessCallback successCallback,
            Runnable releaseLockCallback) {
        
        Page page = null;
        try {
            String model = request.getModel();

            // 获取用户消息
            String userMessage = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)
                    .map(ChatCompletionRequest.Message::getContent)
                    .orElse(null);

            if (userMessage == null || userMessage.trim().isEmpty()) {
                sendCommandResult(emitter, model, "❌ 未找到指令", false, objectMapper);
                return;
            }

            // 解析指令
            CommandParser.ParseResult parseResult = commandParser.parse(userMessage);

            if (!parseResult.hasCommands()) {
                sendCommandResult(emitter, model, "❌ 未找到有效指令", false, objectMapper);
                return;
            }

            log.info("检测到指令对话，包含 {} 个指令", parseResult.getCommands().size());

            // 检查是否只有 help 指令（help 指令是完全独立的，不需要页面、登录或任何依赖）
            boolean onlyHelpCommand = parseResult.getCommands().size() == 1 && 
                                     "help".equals(parseResult.getCommands().get(0).getName());
            
            // help 指令完全独立，跳过所有页面和登录相关操作
            if (onlyHelpCommand) {
                log.info("检测到 help 指令，跳过页面获取和登录检查");
            } else {
                // 需要页面来执行指令
                page = getOrCreatePageFunction.apply(request);

                // 检查是否找到了页面（对于非新对话，必须找到对应的 tab）
                if (page == null) {
                    String conversationId = getConversationIdFunction.apply(request);
                    if (conversationId != null && !conversationId.isEmpty() && 
                        !isNewConversationFunction.apply(request)) {
                        // 找不到对应的 tab，返回系统错误
                        log.error("找不到对应的 tab: conversationId={}", conversationId);
                        sendSystemError(emitter, model,
                                "系统错误：找不到对应的对话 Tab。该 Tab 可能已被关闭或丢失。请重新开始对话。", objectMapper);
                        return;
                    }
                    // 如果是新对话但 page 为 null，说明创建失败
                    sendCommandResult(emitter, model, "❌ 无法创建或获取页面", false, objectMapper);
                    return;
                }
            }

            // 创建进度回调（累积所有进度消息）
            CommandProgressCallback progressCallback = new CommandProgressCallback(emitter, model, objectMapper);
            
            // 发送开始标记
            progressCallback.sendStartMarker();

            // 执行所有指令（带重试机制）
            // help 指令不需要重试（它是完全独立的，不会失败）
            boolean allSuccess = true;
            int maxRetries = onlyHelpCommand ? 0 : 2; // help 指令不重试，其他指令最多重试2次（总共执行3次）

            for (int i = 0; i < parseResult.getCommands().size(); i++) {
                Command command = parseResult.getCommands().get(i);

                boolean commandSuccess = false;
                int retryCount = 0;

                while (retryCount <= maxRetries && !commandSuccess) {
                    try {
                        if (retryCount > 0) {
                            log.info("重试执行指令 (尝试 {}/{}): {}", retryCount + 1, maxRetries + 1, command.getName());
                            progressCallback.addProgress(String.format("重试中... (尝试 %d/%d)", retryCount + 1, maxRetries + 1));
                            if (page != null) {
                                page.waitForTimeout(1000); // 重试前等待
                            }
                        } else {
                            log.info("执行指令: {}", command.getName());
                        }

                        // help 指令可以传入 null 作为 page（它不需要页面）
                        boolean success = command.execute(page, progressCallback, provider);

                        if (success) {
                            commandSuccess = true;
                            progressCallback.addProgress("✅ 执行成功");
                            // 等待附件上传完成（如果有页面）
                            if (page != null) {
                                page.waitForTimeout(1000);
                            }
                        } else {
                            // 检查是否是不可重试的错误（通过检查进度回调中的所有消息）
                            // 如果消息中包含"搜索完成，但没有找到"等明确表示业务逻辑失败的提示，则不重试
                            if (progressCallback.hasNonRetryableError()) {
                                // 这是明确的业务逻辑失败，不应该重试
                                log.warn("指令执行失败（明确的业务逻辑错误，不重试）: {}", command.getName());
                                progressCallback.addProgress("❌ 执行失败");
                                allSuccess = false;
                                break;
                            }
                            
                            if (retryCount < maxRetries) {
                                log.warn("指令执行失败，将重试 (尝试 {}/{}): {}", retryCount + 1, maxRetries + 1, command.getName());
                                retryCount++;
                            } else {
                                log.error("指令执行失败，已重试 {} 次: {}", maxRetries, command.getName());
                                progressCallback.addProgress("❌ 执行失败（已重试 " + maxRetries + " 次）");
                                allSuccess = false;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        if (retryCount < maxRetries) {
                            log.warn("执行指令时出错，将重试 (尝试 {}/{}): {}, error: {}",
                                retryCount + 1, maxRetries + 1, command.getName(), e.getMessage());
                            retryCount++;
                            if (page != null) {
                                page.waitForTimeout(1000); // 重试前等待
                            }
                        } else {
                            log.error("执行指令时出错，已重试 {} 次: {}, error: {}", maxRetries, command.getName(), e.getMessage(), e);
                            progressCallback.addProgress("❌ 执行出错（已重试 " + maxRetries + " 次）: " + e.getMessage());
                            allSuccess = false;
                            break;
                        }
                    }
                }
            }

            // 发送结束标记
            progressCallback.sendEndMarker();

            // 发送最终结果（只显示成功或失败）
            String finalMessage = allSuccess ? "✅ 指令执行成功" : "❌ 指令执行失败";

            // 如果提供了成功回调，且不是 help 指令，调用回调处理 provider 特定的逻辑
            boolean handledByCallback = false;
            if (successCallback != null && allSuccess && !onlyHelpCommand && page != null && !page.isClosed()) {
                handledByCallback = successCallback.onSuccess(page, model, emitter, finalMessage, allSuccess);
            }

            // 如果回调没有处理，使用默认方式发送结果
            if (!handledByCallback) {
                sendCommandResult(emitter, model, finalMessage, allSuccess, objectMapper);
            }
            
            // 指令执行完成，立即释放锁（不等待 onCompletion 回调，因为可能有时序问题）
            if (releaseLockCallback != null) {
                log.debug("指令执行完成，立即释放锁");
                releaseLockCallback.run();
            }

        } catch (Exception e) {
            log.error("处理指令对话时出错", e);
            try {
                sendCommandResult(emitter, request.getModel(),
                        "```nwla-system-message\n❌ 执行指令时出错: " + e.getMessage() + "\n```", false, objectMapper);
            } catch (Exception ex) {
                log.error("发送错误消息时出错", ex);
            }
            // 出错时也要释放锁
            if (releaseLockCallback != null) {
                releaseLockCallback.run();
            }
        }
    }

    /**
     * 指令进度回调实现
     * 累积进度消息，在开始和结束时发送标记
     */
    private static class CommandProgressCallback implements Command.ProgressCallback {
        private final SseEmitter emitter;
        private final String model;
        private final ObjectMapper objectMapper;
        private final StringBuilder accumulatedContent = new StringBuilder();
        private boolean startMarkerSent = false;
        private int lastSentLength = 0;
        private int startMarkerLength = 0;
        private final List<String> progressMessages = new ArrayList<>(); // 保存所有进度消息

        public CommandProgressCallback(SseEmitter emitter, String model, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.model = model;
            this.objectMapper = objectMapper;
        }

        /**
         * 发送开始标记
         */
        public void sendStartMarker() {
            try {
                String startMarker = "```nwla-system-message\n";
                sendReasoningContent(startMarker);
                accumulatedContent.append(startMarker);
                startMarkerLength = startMarker.length();
                lastSentLength = accumulatedContent.length();
                startMarkerSent = true;
            } catch (Exception e) {
                log.warn("发送开始标记时出错: {}", e.getMessage());
            }
        }

        /**
         * 发送结束标记
         */
        public void sendEndMarker() {
            try {
                // 先发送换行（如果最后一条消息后面没有换行）
                String newline = "\n";
                sendReasoningContent(newline);
                // 然后发送结束标记
                String endMarker = "```\n";
                sendReasoningContent(endMarker);
            } catch (Exception e) {
                log.warn("发送结束标记时出错: {}", e.getMessage());
            }
        }

        /**
         * 添加进度消息（累积并实时发送增量）
         */
        public void addProgress(String message) {
            progressMessages.add(message); // 保存所有消息
            if (startMarkerSent) {
                // 添加换行（如果已有内容，即除了开始标记外还有其他内容）
                if (accumulatedContent.length() > startMarkerLength) {
                    accumulatedContent.append("\n");
                }
                accumulatedContent.append(message);
                // 实时发送增量内容
                String newContent = accumulatedContent.substring(lastSentLength);
                sendReasoningContent(newContent);
                lastSentLength = accumulatedContent.length();
            }
        }

        @Override
        public void onProgress(String message) {
            progressMessages.add(message); // 保存所有消息
            if (startMarkerSent) {
                // 添加换行（如果已有内容，即除了开始标记外还有其他内容）
                if (accumulatedContent.length() > startMarkerLength) {
                    accumulatedContent.append("\n");
                }
                accumulatedContent.append(message);
                // 实时发送增量内容
                String newContent = accumulatedContent.substring(lastSentLength);
                sendReasoningContent(newContent);
                lastSentLength = accumulatedContent.length();
            }
        }
        
        /**
         * 检查是否包含不可重试的错误消息
         * 用于判断是否是不可重试的错误
         */
        public boolean hasNonRetryableError() {
            // 检查所有进度消息中是否包含不可重试的错误提示
            for (String msg : progressMessages) {
                if (msg != null && (
                    msg.contains("搜索完成，但没有找到") ||
                    msg.contains("没有找到匹配的文件") ||
                    msg.contains("No matching results"))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 发送思考内容（增量）
         */
        private void sendReasoningContent(String content) {
            try {
                String thinkingId = UUID.randomUUID().toString();
                ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                        .delta(ChatCompletionResponse.Delta.builder()
                                .reasoningContent(content)
                                .build())
                        .index(0).build();
                ChatCompletionResponse response = ChatCompletionResponse.builder()
                        .id(thinkingId).object("chat.completion.chunk")
                        .created(System.currentTimeMillis() / 1000)
                        .model(model).choices(List.of(choice)).build();
                emitter.send(SseEmitter.event().data(
                        objectMapper.writeValueAsString(response),
                        APPLICATION_JSON_UTF8));
            } catch (Exception e) {
                log.warn("发送思考内容时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 发送指令执行结果
     */
    private static void sendCommandResult(SseEmitter emitter, String model, String message, boolean success, ObjectMapper objectMapper) {
        try {
            String id = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(message).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            emitter.send(SseEmitter.event().data(
                    objectMapper.writeValueAsString(response),
                    APPLICATION_JSON_UTF8));

            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送指令结果时出错", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 发送系统错误消息
     */
    private static void sendSystemError(SseEmitter emitter, String model, String errorMessage, ObjectMapper objectMapper) {
        try {
            String id = UUID.randomUUID().toString();
            String content = "```nwla-system-message\n" + errorMessage + "\n```";
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            emitter.send(SseEmitter.event().data(
                    objectMapper.writeValueAsString(response),
                    APPLICATION_JSON_UTF8));

            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
        } catch (Exception e) {
            log.error("发送系统错误时出错", e);
            emitter.completeWithError(e);
        }
    }
}


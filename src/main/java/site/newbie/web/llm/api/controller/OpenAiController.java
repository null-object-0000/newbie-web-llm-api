package site.newbie.web.llm.api.controller;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.AccountManager;
import site.newbie.web.llm.api.manager.ApiKeyManager;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.command.CommandParserFactory;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.model.ImageGenerationRequest;
import site.newbie.web.llm.api.model.ImageGenerationResponse;
import site.newbie.web.llm.api.model.ModelResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.gemini.GeminiProvider;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import site.newbie.web.llm.api.util.ConversationIdUtils;
import site.newbie.web.llm.api.provider.command.Command;
import site.newbie.web.llm.api.provider.command.CommandHandler;
import site.newbie.web.llm.api.provider.command.CommandParser;
import site.newbie.web.llm.api.config.ApiKeyScopedValue;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "*")
public class OpenAiController {

    private final ProviderRegistry providerRegistry;
    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper;
    private final AccountManager accountManager;
    private final ApiKeyManager apiKeyManager;
    private final CommandParserFactory commandParserFactory;
    
    @Value("${app.server.base-url:http://localhost:24753}")
    private String serverBaseUrl;
    
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;

    public OpenAiController(ProviderRegistry providerRegistry, BrowserManager browserManager, 
                           ObjectMapper objectMapper,
                           AccountManager accountManager, ApiKeyManager apiKeyManager,
                           CommandParserFactory commandParserFactory) {
        this.providerRegistry = providerRegistry;
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.accountManager = accountManager;
        this.apiKeyManager = apiKeyManager;
        this.commandParserFactory = commandParserFactory;
    }

    @PostMapping(value = "/chat/completions", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object chat(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestHeader(value = "X-Web-Search", required = false) String webSearchHeader,
            @RequestHeader(value = "X-Conversation-ID", required = false) String conversationIdHeader) {
        String apiKeyFromHeader = null;
        try {
            // 1. 简单的参数校验
            if (request == null) {
                System.err.println("请求体为 null");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Request body is required", "type", "invalid_request_error")));
            }
            
            if (request.getMessages() == null || request.getMessages().isEmpty()) {
                System.err.println("消息列表为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Message list cannot be empty", "type", "invalid_request_error")));
            }

            if (request.getModel() == null || request.getModel().isEmpty()) {
                System.err.println("模型名称为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", Map.of("message", "Model is required", "type", "invalid_request_error")));
            }

            // 2. 从 Header 读取 webSearch（如果请求体中没有设置）
            // 注意：是否新对话现在完全根据是否有 conversationId 来判断，不再接收外部参数
            if (webSearchHeader != null && !webSearchHeader.isEmpty()) {
                request.setWebSearch(Boolean.parseBoolean(webSearchHeader));
            }
            
            // 3. 从 Header 读取 conversationId（如果请求体中没有设置）
            if (conversationIdHeader != null && !conversationIdHeader.isEmpty()) {
                request.setConversationId(conversationIdHeader);
            }
            
            // 4. 从 ScopedValue 读取 API 密钥（拦截器已验证并存储）
            apiKeyFromHeader = ApiKeyScopedValue.getApiKey();
            
            // 注意：
            // - 深度思考模式现在完全根据模型名称自动判断，不再接收外部参数
            // - 是否新对话现在完全根据是否有 conversationId 来判断，不再接收外部参数
            boolean isNewConversation = (request.getConversationId() == null || request.getConversationId().isEmpty());
            log.info("收到请求: 模型=" + request.getModel() + ", 新对话=" + isNewConversation +
                    ", 联网搜索=" + request.isWebSearch() +
                    ", 对话ID=" + request.getConversationId() + ", 消息数=" + request.getMessages().size());
        } catch (Exception e) {
            System.err.println("解析请求时出错: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of("message", "Failed to parse request: " + e.getMessage(), "type", "invalid_request_error")));
        }

        // 3. 根据模型名称获取对应的提供者
        LLMProvider provider = providerRegistry.getProviderByModel(request.getModel());
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of("message", "Unsupported model: " + request.getModel(), "type", "invalid_request_error")));
        }
        
        // 4. 从 API 密钥解析 accountId（需要知道提供器后才能正确解析）
        // 注意：拦截器已经验证了 API key 的基本有效性，这里只需要验证 provider 特定逻辑
        String providerName = provider.getProviderName();
        // 检查 API key 是否支持该提供器（从 ScopedValue 检查）
        if (!ApiKeyScopedValue.supportsProvider(providerName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", Map.of("message", 
                        "API key does not support provider: " + providerName, 
                        "type", "invalid_request_error")));
        }
        String accountIdFromApiKey = apiKeyManager.getAccountIdByApiKey(apiKeyFromHeader, providerName);
        if (accountIdFromApiKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", Map.of(
                        "message", "Invalid API Key",
                        "param", "Please provide valid API Key, Format: Authorization: Bearer <your-api-key> or api-key: <your-api-key>",
                        "code", "401",
                        "type", "invalid_key"
                    )));
        }
        // 从 API key 设置 accountId
        request.setAccountId(accountIdFromApiKey);

        // 4. 判断是流式 (Stream) 还是 普通请求
        if (request.isStream()) {
            return handleStreamRequest(request, provider);
        } else {
            return handleNormalRequest(request);
        }
    }

    // 处理流式请求 (SSE)
    private Object handleStreamRequest(ChatCompletionRequest request, LLMProvider provider) {
        String providerName = provider.getProviderName();
        
        // 统一检查是否是指令对话（在 Controller 层统一处理，避免每个 provider 重复实现）
        String userMessage = null;
        if (request.getMessages() != null) {
            userMessage = request.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)
                    .map(ChatCompletionRequest.Message::getContent)
                    .orElse(null);
        }
        
        // 从 provider 获取 CommandParser（可能包含 provider 特定指令）
        CommandParser commandParser = provider.getCommandParser();
        if (commandParser != null && userMessage != null && commandParser.isCommandOnly(userMessage)) {
            // 是指令对话，统一在 Controller 层处理
            log.info("检测到指令对话，在 Controller 层统一处理");
            
            // 解析指令，检查是否需要页面或登录（以决定是否需要锁）
            CommandParser.ParseResult parseResult = commandParser.parse(userMessage);
            final boolean needsLock;
            if (parseResult.hasCommands()) {
                // 检查是否有任何指令需要页面或登录
                boolean tempNeedsLock = false;
                for (Command cmd : parseResult.getCommands()) {
                    if (cmd.requiresPage() || cmd.requiresLogin()) {
                        tempNeedsLock = true;
                        break;
                    }
                }
                needsLock = tempNeedsLock;
            } else {
                needsLock = false;
            }
            
            SseEmitter emitter = new SseEmitter(300000L);
            
            // 设置响应头（使用 AtomicBoolean 防止重复释放锁）
            java.util.concurrent.atomic.AtomicBoolean lockReleased = new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable releaseLock = () -> {
                if (needsLock && lockReleased.compareAndSet(false, true)) {
                    providerRegistry.releaseLock(providerName);
                    log.debug("已释放锁: {}", providerName);
                }
            };
            
            emitter.onError((ex) -> {
                log.error("SSE Error: {}", ex.getMessage(), ex);
                releaseLock.run();
            });
            emitter.onTimeout(() -> {
                log.warn("SSE Timeout");
                emitter.complete();
                releaseLock.run();
            });
            emitter.onCompletion(() -> {
                log.info("SSE Completed");
                releaseLock.run(); // 这里也会尝试释放，但只会释放一次
            });
            
            // 只有需要页面或登录的指令才需要获取锁
            if (needsLock) {
                if (!providerRegistry.tryAcquireLock(providerName)) {
                    log.warn("提供器 {} 正忙，拒绝指令请求", providerName);
                    try {
                        String errorMessage = providerName + " 提供器正忙，请等待当前对话完成后再试";
                        String id = UUID.randomUUID().toString();
                        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                                .delta(ChatCompletionResponse.Delta.builder().content(errorMessage).build())
                                .index(0).build();
                        ChatCompletionResponse response = ChatCompletionResponse.builder()
                                .id(id).object("chat.completion.chunk")
                                .created(System.currentTimeMillis() / 1000)
                                .model(request.getModel()).choices(List.of(choice)).build();
                        
                        MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
                        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                    return emitter;
                }
            }
            
            // 使用 CommandHandler 统一处理指令
            CommandHandler.handleCommandOnly(
                    request, emitter, provider, commandParser,
                    provider::getOrCreatePage,
                    provider::getConversationId,
                    provider::isNewConversation,
                    objectMapper,
                    provider.getCommandSuccessCallback(), // 从 provider 获取成功回调
                    releaseLock); // 传入释放锁的回调，确保指令执行完成后立即释放锁
            
            return emitter;
        }
        
        // 获取对话ID（用于标识登录对话）
        // 只从历史消息中提取登录对话ID
        String conversationId = null;
        if (request.getMessages() != null) {
            conversationId = ConversationIdUtils.extractConversationIdFromMessages(request.getMessages(), false);
            if (conversationId != null && !conversationId.isEmpty()) {
                log.debug("从历史消息中提取到对话ID: {}", conversationId);
            }
        }
        
        boolean isNewConversation = (conversationId == null || conversationId.isEmpty());
        
        log.debug("处理请求: providerName={}, conversationId={}, isNewConversation={}", 
            providerName, conversationId, isNewConversation);
        
        // 获取锁
        if (!providerRegistry.tryAcquireLock(providerName)) {
            log.warn("提供器 {} 正忙，拒绝请求", providerName);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", Map.of(
                            "message", providerName + " 提供器正忙，请等待当前对话完成后再试",
                            "type", "provider_busy_error"
                    )));
        }
        
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        
        // 设置响应头
        emitter.onError((ex) -> {
            System.err.println("SSE Error: " + ex.getMessage());
            ex.printStackTrace();
            providerRegistry.releaseLock(providerName);
        });
        
        emitter.onTimeout(() -> {
            System.err.println("SSE Timeout");
            emitter.complete();
            providerRegistry.releaseLock(providerName);
        });
        
        emitter.onCompletion(() -> {
            System.out.println("SSE Completed");
            providerRegistry.releaseLock(providerName);
        });
        
        // 直接调用提供者处理请求（登录功能已移至管理后台）
        provider.streamChat(request, emitter);
        
        return emitter;
    }

    /**
     * 获取所有可用的提供者和模型
     * 注意：API key 验证由拦截器处理
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(providerRegistry.getAllProviders());
    }
    
    /**
     * 获取所有可用的模型列表（OpenAI 兼容格式）
     * 前端可以使用 OpenAI SDK: openai.models.list()
     * 注意：API key 验证由拦截器处理
     */
    @GetMapping("/models")
    public ResponseEntity<ModelResponse> getModels() {
        return ResponseEntity.ok(providerRegistry.getOpenAIModels());
    }
    
    
    /**
     * 图片生成 API（OpenAI 兼容）
     * POST /v1/images/generations
     */
    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generateImage(
            @RequestBody ImageGenerationRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        try {
            log.info("收到图片生成请求: prompt={}, model={}, response_format={}, n={}", 
                    request != null ? (request.getPrompt() != null ? request.getPrompt().substring(0, Math.min(50, request.getPrompt().length())) : "null") : "null",
                    request != null ? request.getModel() : "null",
                    request != null ? request.getResponseFormat() : "null",
                    request != null ? request.getN() : "null");
            
            // API Key 验证（拦截器已验证基本有效性，这里只验证 provider 特定逻辑）
            // 从 ScopedValue 获取 API key
            String apiKeyFromHeader = ApiKeyScopedValue.getApiKey();
            
            // 验证 API key 是否支持 Gemini 提供器（图片生成使用 Gemini）
            if (!ApiKeyScopedValue.supportsProvider("gemini")) {
                log.warn("API key 不支持 Gemini 提供器");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ImageGenerationResponse.builder()
                                .created(System.currentTimeMillis() / 1000)
                                .data(List.of())
                                .build());
            }
            
            String accountIdFromApiKey = apiKeyManager.getAccountIdByApiKey(apiKeyFromHeader, "gemini");
            if (accountIdFromApiKey == null) {
                log.warn("无效的 API key for Gemini provider");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", Map.of(
                            "message", "Invalid API Key",
                            "param", "Please provide valid API Key, Format: Authorization: Bearer <your-api-key> or api-key: <your-api-key>",
                            "code", "401",
                            "type", "invalid_key"
                        )));
            }
            
            // 参数校验
            if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
                log.warn("图片生成请求参数错误: prompt 为空");
                throw new IllegalArgumentException("Prompt is required");
            }
            
            // 获取 Gemini Provider
            LLMProvider geminiProvider = providerRegistry.getProviderByModel("gemini-web-imagegen");
            if (geminiProvider == null || !(geminiProvider instanceof GeminiProvider)) {
                throw new IllegalArgumentException("Image generation model not available");
            }
            
            GeminiProvider provider = (GeminiProvider) geminiProvider;
            
            // 确定响应格式（默认 b64_json，支持 url）
            String responseFormat = request.getResponseFormat();
            if (responseFormat == null || responseFormat.isEmpty()) {
                responseFormat = "b64_json";
            }
            boolean returnBase64 = "b64_json".equals(responseFormat);
            
            // 生成图片（同步方法，返回文件名列表）
            log.info("开始调用 generateImageSync，prompt: {}, accountId: {}", 
                    request.getPrompt().substring(0, Math.min(50, request.getPrompt().length())), 
                    accountIdFromApiKey);
            List<String> imageFilenames = provider.generateImageSync(request.getPrompt(), request.getN(), accountIdFromApiKey);
            log.info("generateImageSync 返回，文件名数量: {}", imageFilenames != null ? imageFilenames.size() : 0);
            
            if (imageFilenames == null || imageFilenames.isEmpty()) {
                log.error("图片生成失败：返回的文件名列表为空");
                throw new RuntimeException("Failed to generate image");
            }
            
            log.info("成功生成图片，文件名: {}", imageFilenames);
            
            // 构建响应数据
            List<ImageGenerationResponse.ImageData> imageDataList = new java.util.ArrayList<>();
            
            for (String filename : imageFilenames) {
                ImageGenerationResponse.ImageData.ImageDataBuilder builder = ImageGenerationResponse.ImageData.builder();
                
                if (returnBase64) {
                    // 读取图片文件并转换为 base64
                    String base64Image = readImageAsBase64(filename);
                    if (base64Image != null) {
                        builder.b64Json(base64Image);
                    } else {
                        // 如果读取失败，降级到 URL
                        String imageUrl = buildImageUrl(filename);
                        builder.url(imageUrl);
                    }
                } else {
                    // 返回 URL
                    String imageUrl = buildImageUrl(filename);
                    builder.url(imageUrl);
                }
                
                // 添加修订后的提示词（与原始提示词相同，因为 Gemini 不提供修订）
                builder.revisedPrompt(request.getPrompt());
                
                imageDataList.add(builder.build());
            }
            
            ImageGenerationResponse response = ImageGenerationResponse.builder()
                    .created(System.currentTimeMillis() / 1000)
                    .data(imageDataList)
                    .build();
            
            // 记录响应日志（用于调试）
            try {
                String responseJson = objectMapper.writeValueAsString(response);
                log.debug("图片生成响应: {}", responseJson);
            } catch (Exception e) {
                log.warn("序列化响应失败: {}", e.getMessage());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.error("图片生成请求参数错误: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ImageGenerationResponse.builder()
                            .created(System.currentTimeMillis() / 1000)
                            .data(List.of())
                            .build());
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ImageGenerationResponse.builder()
                            .created(System.currentTimeMillis() / 1000)
                            .data(List.of())
                            .build());
        }
    }
    
    /**
     * 构建图片 URL
     */
    private String buildImageUrl(String filename) {
        String baseUrl = serverBaseUrl;
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:24753";
        }
        return baseUrl + "/api/images/" + filename;
    }
    
    /**
     * 读取图片文件并转换为 base64
     */
    private String readImageAsBase64(String filename) {
        try {
            String imagesSubdir = "gemini-images";
            java.nio.file.Path imagePath = java.nio.file.Paths.get(userDataDir, imagesSubdir, filename);
            java.io.File imageFile = imagePath.toFile();
            
            if (!imageFile.exists() || !imageFile.isFile()) {
                log.warn("图片文件不存在: {}", imagePath.toAbsolutePath());
                return null;
            }
            
            // 读取文件
            byte[] imageBytes = java.nio.file.Files.readAllBytes(imagePath);
            
            // 转换为 base64
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
            log.debug("成功读取图片并转换为 base64: {} ({} bytes)", filename, imageBytes.length);
            
            return base64Image;
            
        } catch (Exception e) {
            log.error("读取图片文件失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // 处理普通请求
    private Map<String, Object> handleNormalRequest(ChatCompletionRequest request) {
        // TODO: 暂时返回一个 Mock 数据测试接口通不通
        return Map.of(
                "id", "mock-id",
                "choices", java.util.List.of(
                        Map.of("message", Map.of("role", "assistant", "content", "这是测试回复，接口已打通！"))
                )
        );
    }
}
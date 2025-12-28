package site.newbie.web.llm.api.provider.antigravity;

import com.microsoft.playwright.Page;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import site.newbie.web.llm.api.provider.command.CommandParser;
import org.springframework.context.annotation.Lazy;
import site.newbie.web.llm.api.provider.antigravity.core.OAuthCallbackServer;
import site.newbie.web.llm.api.provider.antigravity.core.OAuthService;
import site.newbie.web.llm.api.provider.antigravity.core.ProjectResolver;
import site.newbie.web.llm.api.provider.antigravity.core.RequestMapper;
import site.newbie.web.llm.api.provider.antigravity.core.ResponseMapper;
import site.newbie.web.llm.api.provider.antigravity.core.TokenManager;
import site.newbie.web.llm.api.provider.antigravity.core.UpstreamClient;
import site.newbie.web.llm.api.provider.antigravity.command.OAuthLoginCommand;
import site.newbie.web.llm.api.provider.antigravity.model.AntigravityModelConfig;
import site.newbie.web.llm.api.provider.antigravity.model.AntigravityModelConfig.AntigravityContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Antigravity 提供者
 * 直接使用 Antigravity-Manager 的底层实现，调用 Google v1internal API
 */
@Slf4j
@Component
public class AntigravityProvider implements LLMProvider {

    private final ObjectMapper objectMapper;
    private final Map<String, AntigravityModelConfig> modelConfigs;
    private final ProviderRegistry providerRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // 核心组件
    private final TokenManager tokenManager;
    private final ProjectResolver projectResolver;
    private final UpstreamClient upstreamClient;
    private final RequestMapper requestMapper;
    private final ResponseMapper responseMapper;
    private final OAuthService oauthService;
    private final OAuthCallbackServer oauthCallbackServer;
    
    // 全局指令解析器，支持全局指令和 provider 特定指令
    private final CommandParser commandParser;

    private final ModelConfig.ResponseHandler responseHandler = new ModelConfig.ResponseHandler() {
        @Override
        public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
            AntigravityProvider.this.sendSseChunk(emitter, id, content, model);
        }

        @Override
        public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
            AntigravityProvider.this.sendThinkingContent(emitter, id, content, model);
        }

        @Override
        public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
            AntigravityProvider.this.sendUrlAndComplete(emitter, request);
        }

        @Override
        public String getSseData(Page page, String varName) {
            // Antigravity 不使用页面，返回 null
            return null;
        }

        @Override
        public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
            // Antigravity 使用 OpenAI 协议，返回空结果
            return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), lastActiveFragmentIndex);
        }

        @Override
        public String extractTextFromSse(String sseData) {
            // Antigravity 使用 OpenAI 协议，返回 null
            return null;
        }
    };

    public AntigravityProvider(ObjectMapper objectMapper,
                              List<AntigravityModelConfig> configs, 
                              @Lazy ProviderRegistry providerRegistry,
                              TokenManager tokenManager,
                              ProjectResolver projectResolver,
                              UpstreamClient upstreamClient,
                              RequestMapper requestMapper,
                              ResponseMapper responseMapper,
                              OAuthService oauthService,
                              OAuthCallbackServer oauthCallbackServer) {
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(AntigravityModelConfig::getModelName, Function.identity()));
        
        this.tokenManager = tokenManager;
        this.projectResolver = projectResolver;
        this.upstreamClient = upstreamClient;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.oauthService = oauthService;
        this.oauthCallbackServer = oauthCallbackServer;
        
        // 创建 CommandParser，支持 provider 特定命令
        this.commandParser = new CommandParser((commandName, param, extra) -> {
            if ("antigravity-login".equals(commandName) || "ag-login".equals(commandName)) {
                return new OAuthLoginCommand(oauthService, oauthCallbackServer, tokenManager, projectResolver);
            }
            return null; // 返回 null 表示不是 provider 特定命令
        });
        
        log.info("AntigravityProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
    }
    
    @PostConstruct
    public void init() {
        try {
            tokenManager.loadAccounts();
            log.info("AntigravityProvider 账号加载完成，共 {} 个账号", tokenManager.size());
        } catch (Exception e) {
            log.error("加载账号失败: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public CommandParser getCommandParser() {
        return commandParser;
    }

    @Override
    public String getProviderName() {
        return "antigravity";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.copyOf(modelConfigs.keySet());
    }

    @Override
    public boolean checkLoginStatus(Page page) {
        // Antigravity 不需要浏览器登录，总是返回 true
        // 实际的账号管理由 Antigravity-Manager 处理
        return true;
    }

    @Override
    public LoginInfo getLoginInfo(Page page) {
        // Antigravity 不需要浏览器登录
        return LoginInfo.loggedIn();
    }

    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                String model = request.getModel();
                AntigravityModelConfig config = modelConfigs.get(model);

                if (config == null) {
                    throw new IllegalArgumentException("不支持的模型: " + model);
                }

                // 调用 Google v1internal API
                AntigravityContext context = new AntigravityContext(
                        emitter, request, tokenManager, projectResolver, 
                        upstreamClient, requestMapper, responseMapper, responseHandler
                );
                config.monitorResponse(context);

            } catch (Exception e) {
                log.error("Chat Error", e);
                emitter.completeWithError(e);
            }
        });
    }

    @Override
    public Page getOrCreatePage(ChatCompletionRequest request) {
        // Antigravity 不需要页面，返回 null
        return null;
    }

    @Override
    public String getConversationId(ChatCompletionRequest request) {
        return request.getConversationId();
    }

    @Override
    public boolean isNewConversation(ChatCompletionRequest request) {
        String conversationId = getConversationId(request);
        return conversationId == null || conversationId.isEmpty() || conversationId.startsWith("login-");
    }

    // ==================== SSE 发送 ====================

    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private void sendSseChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }

    private void sendThinkingContent(SseEmitter emitter, String id, String content, String model) throws IOException {
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().reasoningContent(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }

    private void sendUrlAndComplete(SseEmitter emitter, ChatCompletionRequest request) throws IOException {
        // Antigravity 不需要发送 URL，直接完成
        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
        emitter.complete();
    }
}


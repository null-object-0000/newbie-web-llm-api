package site.newbie.web.llm.api.controller;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
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
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.model.ModelResponse;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.manager.LoginSessionManager;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
    private final LoginSessionManager loginSessionManager;

    public OpenAiController(ProviderRegistry providerRegistry, BrowserManager browserManager, 
                           ObjectMapper objectMapper, LoginSessionManager loginSessionManager) {
        this.providerRegistry = providerRegistry;
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.loginSessionManager = loginSessionManager;
    }

    @PostMapping(value = "/chat/completions", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object chat(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Web-Search", required = false) String webSearchHeader,
            @RequestHeader(value = "X-Conversation-ID", required = false) String conversationIdHeader) {
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
        
        // 获取对话ID（用于标识登录对话）
        // 只从历史消息中提取登录对话ID
        String conversationId = null;
        if (request.getMessages() != null) {
            conversationId = extractConversationIdFromHistory(request.getMessages());
            if (conversationId != null && !conversationId.isEmpty()) {
                log.debug("从历史消息中提取到对话ID: {}", conversationId);
            }
        }
        
        boolean isNewConversation = (conversationId == null || conversationId.isEmpty());
        
        log.debug("处理请求: providerName={}, conversationId={}, isNewConversation={}", 
            providerName, conversationId, isNewConversation);
        
        // 检查是否是登录对话（只要 conversationId 以 "login-" 开头就是登录对话）
        // 登录对话只能由系统回复，不能调用普通聊天功能
        boolean isLoginConversation = false;
        if (conversationId != null && conversationId.startsWith("login-")) {
            isLoginConversation = true;
            log.info("检测到登录对话，对话ID: {}", conversationId);
        }
        
        // 对于登录对话，检查是否是同一个对话的后续请求
        if (isLoginConversation) {
            // 这是登录对话的后续请求，检查锁是否被占用
            if (!providerRegistry.tryAcquireLock(providerName)) {
                // 锁被占用，可能是同一个登录对话的第一次请求还在处理
                log.warn("提供器 {} 正忙，但这是登录对话的后续请求，对话ID: {}", providerName, conversationId);
                // 对于登录对话，我们允许等待或直接返回错误
                // 这里我们返回一个友好的错误消息
                SseEmitter emitter = new SseEmitter(1000L);
                try {
                    sendSystemMessage(emitter, request.getModel(), 
                        "请等待上一条消息处理完成后再发送。", false, null, null);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
                return emitter;
            }
        } else {
            // 非登录对话或新对话，正常获取锁
            if (!providerRegistry.tryAcquireLock(providerName)) {
                log.warn("提供器 {} 正忙，拒绝请求", providerName);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", Map.of(
                                "message", providerName + " 提供器正忙，请等待当前对话完成后再试",
                                "type", "provider_busy_error"
                        )));
            }
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
        
        // 检查是否是登录对话（登录对话只能由系统回复，不能调用普通聊天功能）
        if (isLoginConversation && conversationId != null && !conversationId.isEmpty()) {
            // 这是登录对话，处理登录流程
            LoginSessionManager.LoginSession loginSession = loginSessionManager.getSession(providerName, conversationId);
            if (loginSession != null) {
                log.info("检测到登录对话，处理登录流程，对话ID: {}", conversationId);
                // 确保登录状态为未登录（因为检测到登录对话说明需要登录）
                providerRegistry.setLoginStatus(providerName, false);
                handleLoginFlow(request, provider, emitter, loginSession, providerName, conversationId);
                return emitter;
            } else {
                // 登录会话不存在，重新开始登录流程
                log.warn("登录会话不存在，重新开始登录流程，对话ID: {}", conversationId);
                // 确保登录状态为未登录
                providerRegistry.setLoginStatus(providerName, false);
                startLoginFlow(request, provider, emitter, providerName);
                return emitter;
            }
        }
        
        // 非登录对话才继续检查登录状态
        // 逻辑：如果 login_status.json 中是 true，就当做已登录（不需要再次检查）
        // 如果 login_status.json 中是 false，需要实际打开网页确认是否真的未登录
        Boolean loginStatus = providerRegistry.getLoginStatus(providerName);
        if (loginStatus == null) {
            // 首次检查，创建临时页面检查登录状态
            log.info("首次检查提供器 {} 的登录状态", providerName);
            Page tempPage = null;
            try {
                // 对于不需要登录的提供器（如example），直接返回true
                String loginCheckUrl = getLoginCheckUrl(providerName);
                if (loginCheckUrl == null) {
                    // 不需要登录检查的提供器（如example）
                    log.info("提供器 {} 不需要登录检查", providerName);
                    providerRegistry.setLoginStatus(providerName, true);
                } else {
                    // 需要登录检查的提供器
                    tempPage = browserManager.newPage(providerName);
                    tempPage.navigate(loginCheckUrl);
                    tempPage.waitForLoadState();
                    
                    // 获取登录信息
                    LoginInfo loginInfo = provider.getLoginInfo(tempPage);
                    providerRegistry.setLoginStatus(providerName, loginInfo);
                    
                    // 记录登录信息到日志
                    if (loginInfo.isLoggedIn()) {
                        log.info("提供器 {} 已登录", providerName);
                    } else {
                        log.warn("提供器 {} 未登录，进入登录流程", providerName);
                        // 未登录，进入登录流程
                        startLoginFlow(request, provider, emitter, providerName);
                        return emitter;
                    }
                }
            } catch (Exception e) {
                log.error("检查登录状态时出错: {}", e.getMessage(), e);
                // 出错时默认认为未登录（保守策略）
                providerRegistry.setLoginStatus(providerName, false);
                startLoginFlow(request, provider, emitter, providerName);
                return emitter;
            } finally {
                // 关闭临时页面
                if (tempPage != null && !tempPage.isClosed()) {
                    try {
                        tempPage.close();
                    } catch (Exception e) {
                        log.warn("关闭临时页面时出错: {}", e.getMessage());
                    }
                }
            }
        } else if (loginStatus) {
            // login_status.json 中是 true，直接信任已登录状态，不需要再次检查
            log.info("提供器 {} 已登录（从 login_status.json 读取）", providerName);
        } else {
            // login_status.json 中是 false，需要实际打开网页确认是否真的未登录
            log.info("提供器 {} 在 login_status.json 中标记为未登录，实际打开网页确认登录状态", providerName);
            Page tempPage = null;
            try {
                String loginCheckUrl = getLoginCheckUrl(providerName);
                if (loginCheckUrl == null) {
                    // 不需要登录检查的提供器（如example）
                    log.info("提供器 {} 不需要登录检查", providerName);
                    providerRegistry.setLoginStatus(providerName, true);
                } else {
                    // 需要登录检查的提供器
                    tempPage = browserManager.newPage(providerName);
                    tempPage.navigate(loginCheckUrl);
                    tempPage.waitForLoadState();
                    
                    // 获取登录信息
                    LoginInfo loginInfo = provider.getLoginInfo(tempPage);
                    
                    // 记录登录信息到日志
                    if (loginInfo.isLoggedIn()) {
                        // 实际已登录，更新状态
                        log.info("提供器 {} 实际已登录（与 login_status.json 不一致，已更新）", providerName);
                        providerRegistry.setLoginStatus(providerName, loginInfo);
                    } else {
                        // 确实未登录，进入登录流程
                        log.warn("提供器 {} 确实未登录，进入登录流程", providerName);
                        providerRegistry.setLoginStatus(providerName, loginInfo);
                        startLoginFlow(request, provider, emitter, providerName);
                        return emitter;
                    }
                }
            } catch (Exception e) {
                log.error("确认登录状态时出错: {}", e.getMessage(), e);
                // 出错时默认认为未登录（保守策略）
                startLoginFlow(request, provider, emitter, providerName);
                return emitter;
            } finally {
                // 关闭临时页面
                if (tempPage != null && !tempPage.isClosed()) {
                    try {
                        tempPage.close();
                    } catch (Exception e) {
                        log.warn("关闭临时页面时出错: {}", e.getMessage());
                    }
                }
            }
        }
        
        // 在调用 streamChat 之前，再次检查登录状态
        // 因为 streamChat 可能在创建页面时检测到登录状态丢失并更新状态
        // 我们需要在调用前再次确认登录状态
        Boolean finalLoginStatus = providerRegistry.getLoginStatus(providerName);
        if (finalLoginStatus != null && !finalLoginStatus) {
            // 登录状态已变为未登录（可能是在之前的检查中更新的），进入登录流程
            log.warn("提供器 {} 登录状态已变为未登录，进入登录流程", providerName);
            startLoginFlow(request, provider, emitter, providerName);
            return emitter;
        }
        
        // 已登录，调用提供者处理请求
        // 注意：streamChat 可能在执行过程中检测到登录状态丢失
        // 如果检测到未登录，streamChat 会发送登录提示并创建登录会话
        // 我们需要在 streamChat 执行后检查是否有新的登录会话被创建，如果有则更新登录状态
        
        // 已登录，调用提供者处理请求
        // 注意：streamChat 可能在执行过程中检测到登录状态丢失
        // 如果检测到未登录，streamChat 会发送登录提示并创建登录会话
        // LoginStorageService 在保存 WAITING_LOGIN_METHOD 状态的会话时会自动更新登录状态
        provider.streamChat(request, emitter);
        
        return emitter;
    }
    
    /**
     * 获取提供器的登录检查URL
     * @param providerName 提供器名称
     * @return 登录检查URL，如果不需要登录检查则返回null
     */
    private String getLoginCheckUrl(String providerName) {
        return switch (providerName) {
            case "deepseek" -> "https://chat.deepseek.com/";
            case "openai" -> "https://chatgpt.com/";
            case "example" ->
                // 示例提供者不需要登录
                    null;
            default -> {
                log.warn("未知的提供器 {}，无法确定登录页面URL", providerName);
                yield null;
            }
        };
    }
    
    /**
     * 开始登录流程
     * 标记当前对话为登录对话，并发送登录方式选择提示
     */
    private void startLoginFlow(ChatCompletionRequest request, LLMProvider provider, 
                                SseEmitter emitter, String providerName) {
        // 确保登录状态为未登录
        providerRegistry.setLoginStatus(providerName, false);
        
        // 只从历史消息中提取对话ID
        String conversationId = null;
        if (request.getMessages() != null) {
            conversationId = extractConversationIdFromHistory(request.getMessages());
        }
        
        // 如果没有对话ID，生成一个登录对话ID
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = "login-" + UUID.randomUUID();
            log.info("为登录流程生成对话ID: {}", conversationId);
        }
        
        // 创建新的登录会话（需要提供器名称和对话ID）
        LoginSessionManager.LoginSession session = loginSessionManager.getOrCreateSession(providerName, conversationId);
        session.setConversationId(conversationId); // 确保 conversationId 被设置
        session.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
        loginSessionManager.saveSession(providerName, conversationId, session);
        log.info("已创建并保存登录会话: providerName={}, conversationId={}, state={}", 
            providerName, conversationId, session.getState());
        
        // 发送登录方式选择提示（登录对话需要立即释放锁）
        // 在消息中包含对话ID，让前端知道这个对话ID
        sendLoginMethodSelection(emitter, request.getModel(), providerName, conversationId);
    }
    
    /**
     * 处理登录流程
     * 确保不能跳过阶段，每次从本地存储读取最新状态
     */
    private void handleLoginFlow(ChatCompletionRequest request, LLMProvider provider,
                                SseEmitter emitter, LoginSessionManager.LoginSession session, 
                                String providerName, String conversationId) {
        try {
            // 每次从本地存储重新读取最新状态，确保不会跳过阶段（需要提供器名称和对话ID）
            LoginSessionManager.LoginSession currentSession = loginSessionManager.getSession(providerName, conversationId);
            if (currentSession == null) {
                log.warn("登录会话不存在，重新开始登录流程，对话ID: {}", conversationId);
                startLoginFlow(request, provider, emitter, providerName);
                return;
            }
            
            // 获取用户输入
            String userInput = "";
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                ChatCompletionRequest.Message lastMessage = request.getMessages().getLast();
                if ("user".equals(lastMessage.getRole()) && lastMessage.getContent() != null) {
                    userInput = lastMessage.getContent().trim();
                }
            }
            
            // 根据登录状态处理用户输入（不能跳过阶段）
            LoginSessionManager.LoginSessionState state = currentSession.getState();
            
            if (state == LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD) {
                // 等待选择登录方式
                LoginSessionManager.LoginMethod method = parseLoginMethod(userInput);
                if (method == null) {
                    // 无效的选择，重新提示
                    sendLoginMethodSelection(emitter, request.getModel(), providerName, currentSession.getConversationId());
                    return;
                }
                
                if (method == LoginSessionManager.LoginMethod.ACCOUNT_PASSWORD) {
                    // 选择账号+密码登录
                    currentSession.setLoginMethod(method);
                    currentSession.setState(LoginSessionManager.LoginSessionState.WAITING_ACCOUNT);
                    loginSessionManager.saveSession(providerName, conversationId, currentSession);
                    sendSystemMessage(emitter, request.getModel(), "请输入手机号/邮箱地址：", 
                        true, providerName, conversationId);
                } else {
                    // 其他登录方式暂不支持
                    sendSystemMessage(emitter, request.getModel(), 
                        "抱歉，" + method.getDescription() + "暂不支持，请选择其他登录方式。", 
                        true, providerName, conversationId);
                }
            } else if (state == LoginSessionManager.LoginSessionState.WAITING_ACCOUNT) {
                // 等待输入账号 - 只有明确的登录方式选择（"1"、"2"、"3"）才认为是跳过阶段
                String trimmed = userInput.trim();
                if (trimmed.equals("1") || trimmed.equals("2") || trimmed.equals("3")) {
                    // 用户试图跳过账号输入阶段，直接选择登录方式，不允许
                    log.warn("检测到跳过阶段：当前状态为 WAITING_ACCOUNT，但用户输入了登录方式选择: {}", trimmed);
                    sendSystemMessage(emitter, request.getModel(), 
                        "请先输入您的账号（邮箱或用户名），不能跳过此步骤。", 
                        true, providerName, conversationId);
                    return;
                }
                
                // 其他任何输入都视为账号输入
                if (userInput.isEmpty()) {
                    sendSystemMessage(emitter, request.getModel(), "账号不能为空，请重新输入：", 
                        true, providerName, conversationId);
                    return;
                }
                currentSession.setAccount(userInput);
                currentSession.setState(LoginSessionManager.LoginSessionState.WAITING_PASSWORD);
                loginSessionManager.saveSession(providerName, conversationId, currentSession);
                sendSystemMessage(emitter, request.getModel(), "请输入您的密码：",
                    true, providerName, conversationId);
            } else if (state == LoginSessionManager.LoginSessionState.WAITING_PASSWORD) {
                // 等待输入密码 - 只有明确的登录方式选择（"1"、"2"、"3"）才认为是跳过阶段
                String trimmed = userInput.trim();
                if (trimmed.equals("1") || trimmed.equals("2") || trimmed.equals("3")) {
                    // 用户试图跳过密码输入阶段，不允许
                    log.warn("检测到跳过阶段：当前状态为 WAITING_PASSWORD，但用户输入了登录方式选择: {}", trimmed);
                    sendSystemMessage(emitter, request.getModel(), 
                        "请先输入您的密码，不能跳过此步骤。", 
                        true, providerName, conversationId);
                    return;
                }
                
                // 其他任何输入都视为密码输入
                if (userInput.isEmpty()) {
                    sendSystemMessage(emitter, request.getModel(), "密码不能为空，请重新输入：",
                        true, providerName, conversationId);
                    return;
                }
                currentSession.setPassword(userInput);
                currentSession.setState(LoginSessionManager.LoginSessionState.LOGGING_IN);
                loginSessionManager.saveSession(providerName, conversationId, currentSession);
                
                // 执行登录（登录过程可能需要较长时间，但这是登录对话，消息发送后应该释放锁）
                // 不发送"正在登录"提示，直接等待登录完成后告知结果
                boolean loginSuccess = provider.handleLogin(request, emitter, currentSession);
                
                // 重新读取最新状态（登录过程可能更新了状态）
                currentSession = loginSessionManager.getSession(providerName, conversationId);
                if (currentSession == null) {
                    log.error("登录后会话丢失，对话ID: {}", conversationId);
                    return;
                }
                
                if (loginSuccess) {
                    // 登录成功
                    currentSession.setState(LoginSessionManager.LoginSessionState.LOGGED_IN);
                    loginSessionManager.markLoggedIn(provider.getProviderName(), conversationId);
                    providerRegistry.setLoginStatus(provider.getProviderName(), true);
                    sendSystemMessage(emitter, request.getModel(), "登录成功！您现在可以使用聊天功能了。",
                        true, providerName, conversationId);
                } else {
                    // 登录失败
                    currentSession.setState(LoginSessionManager.LoginSessionState.LOGIN_FAILED);
                    loginSessionManager.saveSession(providerName, conversationId, currentSession);
                    
                    // 获取登录错误信息
                    String errorMessage;
                    if (currentSession.getLoginError() != null && !currentSession.getLoginError().isEmpty()) {
                        // 根据错误码返回友好的错误信息
                        String bizMsg = currentSession.getLoginError();
                        if ("PASSWORD_OR_USER_NAME_IS_WRONG".equals(bizMsg)) {
                            errorMessage = "登录失败：账号或密码错误，请检查后重试。";
                        } else {
                            errorMessage = "登录失败：" + bizMsg + "，请检查后重试。";
                        }
                    } else {
                        errorMessage = "登录失败，请检查账号和密码是否正确。";
                    }

                    sendSystemMessage(emitter, request.getModel(), errorMessage, 
                        true, providerName, conversationId);
                    
                    // 重置状态，允许重新登录
                    currentSession.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
                    currentSession.setAccount(null);
                    currentSession.setPassword(null);
                    currentSession.setLoginError(null);
                    loginSessionManager.saveSession(providerName, conversationId, currentSession);
                }
            } else if (state == LoginSessionManager.LoginSessionState.LOGGING_IN) {
                // 正在登录中，不允许其他操作，不发送提示消息，等待登录完成
                // 登录完成后会发送成功或失败消息
            } else if (state == LoginSessionManager.LoginSessionState.LOGGED_IN) {
                // 登录已成功，提示用户使用新对话
                sendSystemMessage(emitter, request.getModel(), 
                    "登录已成功！此对话仅用于登录流程，请开启新对话进行聊天。", 
                    true, providerName, conversationId);
            } else if (state == LoginSessionManager.LoginSessionState.LOGIN_FAILED) {
                // 登录失败，重新开始
                currentSession.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
                currentSession.setAccount(null);
                currentSession.setPassword(null);
                currentSession.setLoginError(null);
                loginSessionManager.saveSession(providerName, conversationId, currentSession);
                sendLoginMethodSelection(emitter, request.getModel(), providerName, conversationId);
            } else {
                // 其他状态，重新开始
                currentSession.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
                loginSessionManager.saveSession(providerName, conversationId, currentSession);
                sendLoginMethodSelection(emitter, request.getModel(), providerName, conversationId);
            }
        } catch (Exception e) {
            log.error("处理登录流程时出错: {}", e.getMessage(), e);
            sendSystemMessage(emitter, request.getModel(), "登录过程中出现错误，请重试。", 
                true, providerName, conversationId);
            emitter.completeWithError(e);
        }
    }

    /**
     * 从历史消息中提取对话ID
     * 支持格式：```nwla-conversation-id\n{id}\n```
     */
    private String extractConversationIdFromHistory(List<ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        
        // 从后往前查找最后一条 assistant 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                
                // 检查标记格式：```nwla-conversation-id\n{id}\n```
                String marker = "```nwla-conversation-id";
                int startIdx = content.indexOf(marker);
                if (startIdx != -1) {
                    // 找到开始标记，查找结束标记 ```
                    int afterMarker = startIdx + marker.length();
                    // 跳过可能的换行
                    while (afterMarker < content.length() && 
                           (content.charAt(afterMarker) == '\n' || content.charAt(afterMarker) == '\r')) {
                        afterMarker++;
                    }
                    // 查找结束的 ```
                    int endIdx = content.indexOf("```", afterMarker);
                    if (endIdx != -1 && endIdx > afterMarker) {
                        String extractedId = content.substring(afterMarker, endIdx).trim();
                        // 提取第一行非空内容
                        extractedId = extractedId.lines()
                            .filter(line -> !line.trim().isEmpty() && !line.contains("```") && !line.contains("nwla-conversation-id"))
                            .findFirst()
                            .orElse("")
                            .trim();
                        if (!extractedId.isEmpty()) {
                            log.debug("从历史消息中提取到对话ID: {}", extractedId);
                            return extractedId;
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 解析用户选择的登录方式
     * 只匹配明确的登录方式选择，避免误判账号/密码输入
     */
    private LoginSessionManager.LoginMethod parseLoginMethod(String userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return null;
        }
        
        String trimmed = userInput.trim();
        
        // 优先尝试精确匹配数字（1、2、3）
        LoginSessionManager.LoginMethod method = LoginSessionManager.LoginMethod.fromCode(trimmed);
        if (method != null) {
            return method;
        }
        
        // 只匹配明确的文本描述，避免误判
        // 例如："1" 或 "手机号+验证码登录" 或 "手机号验证码" 等
        // 但不匹配包含 "1" 的任意字符串（如手机号 "18913124159"）
        String lowerInput = trimmed.toLowerCase();
        
        // 检查是否是明确的登录方式描述
        if (lowerInput.equals("1") || 
            (lowerInput.contains("手机") && lowerInput.contains("验证码")) ||
            lowerInput.equals("手机号+验证码") ||
            lowerInput.equals("手机号验证码")) {
            return LoginSessionManager.LoginMethod.PHONE_VERIFY_CODE;
        } else if (lowerInput.equals("2") || 
                   (lowerInput.contains("账号") && lowerInput.contains("密码")) ||
                   lowerInput.equals("账号+密码") ||
                   lowerInput.equals("账号密码")) {
            return LoginSessionManager.LoginMethod.ACCOUNT_PASSWORD;
        } else if (lowerInput.equals("3") || 
                   (lowerInput.contains("微信") && lowerInput.contains("扫码")) ||
                   lowerInput.equals("微信扫码")) {
            return LoginSessionManager.LoginMethod.WECHAT_SCAN;
        }
        
        return null;
    }
    
    /**
     * 发送登录方式选择提示
     */
    private void sendLoginMethodSelection(SseEmitter emitter, String model) {
        sendLoginMethodSelection(emitter, model, null, null);
    }
    
    /**
     * 发送登录方式选择提示（带锁释放）
     */
    private void sendLoginMethodSelection(SseEmitter emitter, String model, String providerName, String conversationId) {
        StringBuilder message = new StringBuilder();
        message.append("```nwla-system-message\n");
        message.append("当前未登录，请选择登录方式：\n\n");
        message.append("1. 手机号+验证码登录\n");
        message.append("2. 账号+密码登录\n");
        message.append("3. 微信扫码登录\n\n");
        message.append("请输入对应的数字（1、2、3）来选择登录方式。");
        message.append("\n```");
        
        // 如果提供了对话ID，在消息中包含对话ID信息
        if (conversationId != null && !conversationId.isEmpty()) {
            message.append("\n\n```nwla-conversation-id\n");
            message.append(conversationId);
            message.append("\n```");
        }
        
        sendSystemMessage(emitter, model, message.toString(), providerName != null, providerName);
    }
    
    /**
     * 格式化系统消息，将 __SYSTEM__ 前缀转换为 nwla-system-message 标记格式
     */
    private String formatSystemMessage(String message) {
        if (message == null) {
            return null;
        }
        // 如果消息已经包含 nwla-system-message 标记，直接返回
        if (message.contains("```nwla-system-message")) {
            return message;
        }
        // 如果消息以 __SYSTEM__ 开头，转换为新格式
        if (message.startsWith("__SYSTEM__")) {
            String content = message.substring("__SYSTEM__".length()).trim();
            return "```nwla-system-message\n" + content + "\n```";
        }
        // 否则，直接包装为系统消息
        return "```nwla-system-message\n" + message + "\n```";
    }
    
    /**
     * 发送系统消息
     * @param emitter SSE 发射器
     * @param model 模型名称
     * @param message 系统消息内容（可以包含 __SYSTEM__ 前缀或已经是格式化后的消息）
     * @param isLoginConversation 是否是登录对话（登录对话的系统消息发送后立即释放锁）
     * @param providerName 提供器名称（如果 isLoginConversation 为 true，需要提供）
     * @param conversationId 对话ID（可选，如果提供会在消息中包含）
     */
    private void sendSystemMessage(SseEmitter emitter, String model, String message, 
                                   boolean isLoginConversation, String providerName, String conversationId) {
        try {
            // 检查 emitter 是否已完成
            if (emitter == null) {
                log.warn("Emitter 为 null，无法发送系统消息");
                if (isLoginConversation && providerName != null) {
                    providerRegistry.releaseLock(providerName);
                }
                return;
            }
            
            // 格式化系统消息
            String formattedMessage = formatSystemMessage(message);
            
            // 如果提供了对话ID，在消息中包含
            String finalMessage = formattedMessage;
            if (conversationId != null && !conversationId.isEmpty() && !formattedMessage.contains("```nwla-conversation-id")) {
                finalMessage = formattedMessage + "\n\n```nwla-conversation-id\n" + conversationId + "\n```";
            }
            
            String id = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(finalMessage).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            
            MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
            
            // 发送完成标记
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
            
            // 如果是登录对话，立即释放锁，避免阻塞后续请求
            // 普通对话的锁会在 emitter.onCompletion 中释放
            if (isLoginConversation && providerName != null) {
                log.info("登录对话系统消息发送完成，立即释放锁: {}", providerName);
                // 使用异步方式释放锁，避免阻塞
                new Thread(() -> {
                    try {
                        Thread.sleep(100); // 稍微延迟，确保消息已发送
                        providerRegistry.releaseLock(providerName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        } catch (IllegalStateException e) {
            // Emitter 已经完成，这是正常的（可能是并发请求）
            log.debug("Emitter 已经完成，无法发送系统消息: {}", e.getMessage());
            if (isLoginConversation && providerName != null) {
                providerRegistry.releaseLock(providerName);
            }
        } catch (IOException e) {
            log.error("发送系统消息时出错: {}", e.getMessage(), e);
            try {
                emitter.completeWithError(e);
            } catch (IllegalStateException ex) {
                log.debug("Emitter 已经完成，无法完成错误: {}", ex.getMessage());
            }
            if (isLoginConversation && providerName != null) {
                providerRegistry.releaseLock(providerName);
            }
        }
    }
    
    /**
     * 发送系统消息（重载方法，不包含对话ID）
     */
    private void sendSystemMessage(SseEmitter emitter, String model, String message, 
                                   boolean isLoginConversation, String providerName) {
        sendSystemMessage(emitter, model, message, isLoginConversation, providerName, null);
    }

    /**
     * 获取所有可用的提供者和模型
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(providerRegistry.getAllProviders());
    }
    
    /**
     * 获取所有可用的模型列表（OpenAI 兼容格式）
     * 前端可以使用 OpenAI SDK: openai.models.list()
     */
    @GetMapping("/models")
    public ResponseEntity<ModelResponse> getModels() {
        return ResponseEntity.ok(providerRegistry.getOpenAIModels());
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
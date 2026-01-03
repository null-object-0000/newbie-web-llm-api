package site.newbie.web.llm.api.provider.deepseek;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.BrowserManager;
import site.newbie.web.llm.api.manager.LoginSessionManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.ChatCompletionResponse;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.provider.AccountInfo;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.ModelConfig;
import site.newbie.web.llm.api.provider.ProviderLoginHandler;
import site.newbie.web.llm.api.provider.ProviderRegistry;
import site.newbie.web.llm.api.provider.command.CommandParser;
import site.newbie.web.llm.api.provider.deepseek.model.DeepSeekModelConfig;
import site.newbie.web.llm.api.provider.deepseek.model.DeepSeekModelConfig.DeepSeekContext;
import site.newbie.web.llm.api.util.ConversationIdUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DeepSeek 统一提供者
 * 通过依赖 DeepSeekModelConfig 实现不同模型的差异化处理
 */
@Slf4j
@Component
public class DeepSeekProvider implements LLMProvider, ProviderLoginHandler {

    private final BrowserManager browserManager;
    private final ObjectMapper objectMapper;
    private final Map<String, DeepSeekModelConfig> modelConfigs;
    private final ProviderRegistry providerRegistry;
    private final LoginSessionManager loginSessionManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // 页面管理
    private final ConcurrentHashMap<String, Page> modelPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageUrls = new ConcurrentHashMap<>();
    
    // 全局指令解析器，支持全局指令
    private final CommandParser commandParser;
    
    // SSE 拦截器配置
    private static final String SSE_DATA_VAR = "__deepseekSseData";
    private static final String SSE_INTERCEPTOR_VAR = "__deepseekSseInterceptorSet";
    private static final String[] SSE_URL_PATTERNS = {"/api/v0/chat/completion"};
    
    /**
     * 响应处理器实现，提供给 ModelConfig 使用
     */
    private final ModelConfig.ResponseHandler responseHandler = new ModelConfig.ResponseHandler() {
        @Override
        public void sendChunk(SseEmitter emitter, String id, String content, String model) throws IOException {
            DeepSeekProvider.this.sendSseChunk(emitter, id, content, model);
        }

        @Override
        public void sendThinking(SseEmitter emitter, String id, String content, String model) throws IOException {
            DeepSeekProvider.this.sendThinkingContent(emitter, id, content, model);
        }

        @Override
        public void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
            DeepSeekProvider.this.sendUrlAndComplete(page, emitter, request);
        }

        @Override
        public String getSseData(Page page, String varName) {
            return DeepSeekProvider.this.getSseDataFromPage(page, varName);
        }

        @Override
        public ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, Integer lastActiveFragmentIndex) {
            return DeepSeekProvider.this.parseSseIncremental(sseData, fragmentTypeMap, lastActiveFragmentIndex);
        }

        @Override
        public String extractTextFromSse(String sseData) {
            return DeepSeekProvider.this.extractTextFromSse(sseData);
        }
    };

    public DeepSeekProvider(BrowserManager browserManager, ObjectMapper objectMapper, 
                           List<DeepSeekModelConfig> configs, @Lazy ProviderRegistry providerRegistry,
                           LoginSessionManager loginSessionManager) {
        this.browserManager = browserManager;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
        this.loginSessionManager = loginSessionManager;
        this.modelConfigs = configs.stream()
                .collect(Collectors.toMap(DeepSeekModelConfig::getModelName, Function.identity()));
        // DeepSeek 目前没有 provider 特定的命令，只支持全局命令
        this.commandParser = new CommandParser();
        log.info("DeepSeekProvider 初始化完成，支持的模型: {}", modelConfigs.keySet());
    }
    
    @Override
    public CommandParser getCommandParser() {
        return commandParser;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.copyOf(modelConfigs.keySet());
    }

    @Override
    public boolean checkLoginStatus(Page page) {
        try {
            if (page == null || page.isClosed()) {
                log.warn("页面为空或已关闭，无法检查登录状态");
                return false;
            }
            
            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 检查是否存在聊天输入框（已登录会有聊天输入框）
            // DeepSeek 的聊天输入框通常是 textarea 元素
            Locator chatInputBox = page.locator("textarea")
                    .or(page.locator("textarea[placeholder*='输入']"))
                    .or(page.locator("textarea[placeholder*='输入消息']"))
                    .or(page.locator("textarea.ds-scroll-area"));
            
            if (chatInputBox.count() > 0) {
                // 检查输入框是否可见和可用
                Locator visibleInput = chatInputBox.first();
                if (visibleInput.isVisible() && visibleInput.isEnabled()) {
                    log.info("检测到聊天输入框，判断为已登录");
                    return true;
                }
            }
            
            // 如果没有聊天输入框，说明未登录
            log.info("未检测到聊天输入框，判断为未登录");
            return false;
        } catch (Exception e) {
            log.error("检查登录状态时出错: {}", e.getMessage(), e);
            return false;
        }
    }
    
    @Override
    public LoginInfo getLoginInfo(Page page) {
        boolean loggedIn = checkLoginStatus(page);
        if (!loggedIn) {
            return LoginInfo.notLoggedIn();
        }
        
        return LoginInfo.loggedIn();
    }
    
    @Override
    public AccountInfo getCurrentAccountInfo(Page page) {
        try {
            if (page == null || page.isClosed()) {
                return AccountInfo.failed("页面为空或已关闭");
            }
            
            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(2000);
            
            // 检查是否已登录
            if (!checkLoginStatus(page)) {
                return AccountInfo.failed("未登录");
            }
            
            // 等待页面完全初始化，确保 token 已经加载
            page.waitForTimeout(2000);
            
            // 从 localStorage 获取 token（这个必须使用 evaluate，因为 Playwright 没有直接访问 localStorage 的 API）
            String token = (String) page.evaluate("""
                () => {
                    try {
                        const userTokenStr = localStorage.getItem('userToken');
                        if (userTokenStr) {
                            const userTokenObj = JSON.parse(userTokenStr);
                            if (userTokenObj && userTokenObj.value) {
                                return userTokenObj.value;
                            }
                        }
                    } catch (e) {
                        console.error('获取 token 失败:', e);
                    }
                    return null;
                }
                """);
            
            if (token == null || token.isEmpty()) {
                return AccountInfo.failed("无法获取 token，请确保已登录。localStorage.userToken 不存在或格式不正确");
            }
            
            log.debug("已获取 token，长度: {}", token.length());
            
            // 使用 Playwright 的 APIRequestContext 发送 HTTP 请求
            APIRequestContext requestContext = page.context().request();
            
            APIResponse response;
            try {
                response = requestContext.get("https://chat.deepseek.com/api/v0/users/current", 
                    RequestOptions.create()
                        .setHeader("accept", "*/*")
                        .setHeader("accept-language", "zh-CN,zh;q=0.9")
                        .setHeader("authorization", "Bearer " + token)
                        .setHeader("x-app-version", "20241129.1")
                        .setHeader("x-client-locale", "zh_CN")
                        .setHeader("x-client-platform", "web")
                        .setHeader("x-client-version", "1.6.0"));
            } catch (Exception e) {
                log.error("API 请求失败: {}", e.getMessage(), e);
                return AccountInfo.failed("API 请求失败: " + e.getMessage());
            }
            
            if (response.status() != 200) {
                String errorText = response.text();
                log.error("API 请求失败，状态码: {}, 响应: {}", response.status(), errorText);
                return AccountInfo.failed("API 请求失败: " + response.status() + ", " + errorText);
            }
            
            String resultJson = response.text();
            if (resultJson == null || resultJson.isEmpty()) {
                log.error("API 调用返回空结果");
                return AccountInfo.failed("无法获取用户信息：API 返回空结果");
            }
            
            log.debug("API 响应长度: {} 字符", resultJson.length());
            
            // 解析响应
            JsonNode json;
            try {
                json = objectMapper.readTree(resultJson);
            } catch (Exception e) {
                log.error("解析 API 响应失败: {}, 响应内容前200字符: {}", e.getMessage(), 
                    resultJson.length() > 200 ? resultJson.substring(0, 200) : resultJson);
                return AccountInfo.failed("解析 API 响应失败: " + e.getMessage());
            }
            
            // 检查响应中的错误
            if (json.has("code") && json.get("code").asInt() != 0) {
                String errorMsg = json.has("msg") ? json.get("msg").asString() : "获取用户信息失败";
                log.error("API 返回错误: code={}, msg={}", json.get("code").asInt(), errorMsg);
                return AccountInfo.failed(errorMsg);
            }
            
            // 解析响应结构
            if (json.has("code") && json.get("code").asInt() != 0) {
                String msg = json.has("msg") ? json.get("msg").asString() : "获取用户信息失败";
                return AccountInfo.failed(msg);
            }
            
            // 提取用户信息
            JsonNode data = json.get("data");
            if (data == null || !data.has("biz_data")) {
                return AccountInfo.failed("响应格式不正确");
            }
            
            JsonNode bizData = data.get("biz_data");
            String email = null;
            String nickname = null;
            
            // 提取 email（可能是部分显示的）
            if (bizData.has("email")) {
                JsonNode emailNode = bizData.get("email");
                if (emailNode != null && !emailNode.isNull()) {
                    email = emailNode.asString();
                }
            }
            
            // 提取昵称（从 id_profiles 中获取）
            if (bizData.has("id_profiles") && bizData.get("id_profiles").isArray()) {
                JsonNode idProfiles = bizData.get("id_profiles");
                if (idProfiles.size() > 0) {
                    JsonNode firstProfile = idProfiles.get(0);
                    if (firstProfile.has("name")) {
                        JsonNode nameNode = firstProfile.get("name");
                        if (nameNode != null && !nameNode.isNull()) {
                            nickname = nameNode.asString();
                        }
                    }
                }
            }
            
            // 如果没有昵称，尝试从 id_profile 获取
            if (nickname == null && bizData.has("id_profile")) {
                JsonNode idProfile = bizData.get("id_profile");
                if (idProfile.has("name")) {
                    JsonNode nameNode = idProfile.get("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        nickname = nameNode.asString();
                    }
                }
            }
            
            if (email == null || email.isEmpty()) {
                return AccountInfo.failed("无法获取邮箱信息");
            }
            
            // 返回账号信息（email 作为 accountId，nickname 作为 accountName）
            return AccountInfo.success(nickname != null && !nickname.isEmpty() ? nickname : email, email);
            
        } catch (Exception e) {
            log.error("获取 DeepSeek 账号信息失败: {}", e.getMessage(), e);
            return AccountInfo.failed("获取账号信息失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean handleLogin(ChatCompletionRequest request, SseEmitter emitter, 
                               LoginSessionManager.LoginSession session) {
        Page page = null;
        try {
            log.info("开始处理 DeepSeek 登录流程，登录方式: {}", session.getLoginMethod());
            
            // 获取或创建登录页面（从 request 中获取 accountId）
            String accountId = request.getAccountId();
            page = getOrCreateLoginPage(accountId);
            
            // 等待页面加载
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 根据登录方式处理
            if (session.getLoginMethod() == LoginSessionManager.LoginMethod.ACCOUNT_PASSWORD) {
                return handleAccountPasswordLogin(page, session);
            } else if (session.getLoginMethod() == LoginSessionManager.LoginMethod.WECHAT_SCAN) {
                // 检查当前状态
                if (session.getState() == LoginSessionManager.LoginSessionState.LOGGING_IN) {
                    // 用户已确认扫码，检查登录状态
                    return checkWechatLoginStatus(page, session);
                } else {
                    // 首次请求，获取二维码
                    return handleWechatLogin(page, session, emitter, request);
                }
            } else {
                log.warn("不支持的登录方式: {}", session.getLoginMethod());
                return false;
            }
        } catch (Exception e) {
            log.error("处理登录时出错: {}", e.getMessage(), e);
            return false;
        } finally {
            // 注意：登录页面不关闭，保持登录状态
            if (page != null && !page.isClosed()) {
                // 登录成功后，页面可以保留用于后续聊天
                log.info("登录页面已保留");
            }
        }
    }
    
    /**
     * 获取或创建登录页面
     * @param accountId 账号ID，用于创建页面
     */
    private Page getOrCreateLoginPage(String accountId) {
        // 查找是否已有登录页面
        for (Page existingPage : modelPages.values()) {
            if (existingPage != null && !existingPage.isClosed()) {
                String url = existingPage.url();
                if (url.contains("chat.deepseek.com")) {
                    // 检查是否是登录页面（没有聊天输入框说明是登录页面）
                    try {
                        Locator chatInput = existingPage.locator("textarea")
                                .or(existingPage.locator("textarea[placeholder*='输入']"))
                                .or(existingPage.locator("textarea.ds-scroll-area"));
                        if (chatInput.count() == 0) {
                            log.info("找到现有登录页面（未检测到聊天输入框）");
                            return existingPage;
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续创建新页面
                    }
                }
            }
        }
        
        // 创建新的登录页面（使用 accountId）
        log.info("创建新的登录页面，accountId: {}", accountId);
        Page page = browserManager.newPage(getProviderName(), accountId);
        page.navigate("https://chat.deepseek.com/");
        page.waitForLoadState();
        return page;
    }
    
    /**
     * 处理账号+密码登录
     */
    private boolean handleAccountPasswordLogin(Page page, LoginSessionManager.LoginSession session) {
        try {
            log.info("开始账号+密码登录，账号: {}", session.getAccount());
            
            // 设置登录接口拦截器，监听登录响应
            setupLoginInterceptor(page);
            
            // 1. 切换到"密码登录"标签
            Locator passwordTab = page.locator(".ds-tab:has-text('密码登录')");
            if (passwordTab.count() == 0) {
                // 尝试英文
                passwordTab = page.locator(".ds-tab:has-text('Password')");
            }
            
            if (passwordTab.count() > 0) {
                passwordTab.first().click();
                page.waitForTimeout(500);
                log.info("已切换到密码登录标签");
            } else {
                log.warn("未找到密码登录标签，可能已经在密码登录模式");
            }
            
            // 2. 等待登录表单加载
            page.waitForTimeout(500);
            
            // 3. 输入账号（可能是邮箱或用户名）
            Locator accountInput = page.locator("input[placeholder*='账号']")
                    .or(page.locator("input[placeholder*='邮箱']"))
                    .or(page.locator("input[placeholder*='用户名']"))
                    .or(page.locator("input[type='text']"))
                    .or(page.locator("input[type='email']"));
            
            if (accountInput.count() == 0) {
                log.error("未找到账号输入框");
                return false;
            }
            
            accountInput.first().click();
            page.waitForTimeout(200);
            accountInput.first().fill(session.getAccount());
            page.waitForTimeout(300);
            log.info("已输入账号");
            
            // 4. 输入密码
            Locator passwordInput = page.locator("input[type='password']");
            if (passwordInput.count() == 0) {
                log.error("未找到密码输入框");
                return false;
            }
            
            passwordInput.first().click();
            page.waitForTimeout(200);
            passwordInput.first().fill(session.getPassword());
            page.waitForTimeout(300);
            log.info("已输入密码");
            
            // 5. 点击登录按钮
            Locator loginButton = page.locator(".ds-sign-up-form__register-button")
                    .or(page.locator("button:has-text('登录')"))
                    .or(page.locator("button:has-text('Login')"));
            
            if (loginButton.count() == 0) {
                log.error("未找到登录按钮");
                return false;
            }
            
            loginButton.first().click();
            log.info("已点击登录按钮");
            
            // 6. 等待登录接口响应（最多等待5秒）
            String loginResponse = null;
            for (int i = 0; i < 10; i++) {
                page.waitForTimeout(500);
                loginResponse = getLoginResponse(page);
                if (loginResponse != null && !loginResponse.isEmpty()) {
                    log.info("获取到登录接口响应");
                    break;
                }
            }
            
            // 检查登录接口响应
            if (loginResponse != null && !loginResponse.isEmpty()) {
                LoginResponseResult result = parseLoginResponse(loginResponse);
                if (result != null) {
                    if (!result.success()) {
                        log.warn("登录失败: biz_code={}, biz_msg={}", result.bizCode(), result.bizMsg());
                        // 将错误信息保存到 session 中，供 Controller 使用
                        session.setLoginError(result.bizMsg());
                        return false;
                    } else {
                        log.info("登录接口返回成功: biz_code={}", result.bizCode());
                    }
                }
            } else {
                log.warn("未获取到登录接口响应，继续等待页面变化");
            }
            
            // 7. 等待登录完成（检查聊天输入框是否出现）
            // 等待最多10秒，检查是否登录成功
            for (int i = 0; i < 10; i++) {
                page.waitForTimeout(1000);
                
                // 检查聊天输入框是否出现（已登录会有聊天输入框）
                Locator chatInput = page.locator("textarea")
                        .or(page.locator("textarea[placeholder*='输入']"))
                        .or(page.locator("textarea[placeholder*='输入消息']"))
                        .or(page.locator("textarea.ds-scroll-area"));
                
                if (chatInput.count() > 0) {
                    // 检查输入框是否可见和可用
                    Locator visibleInput = chatInput.first();
                    if (visibleInput.isVisible() && visibleInput.isEnabled()) {
                        log.info("登录成功！检测到聊天输入框");
                        return true;
                    }
                }
                
                // 检查是否有错误提示
                Locator errorMsg = page.locator("[class*='error'], [class*='Error']")
                        .or(page.locator(":has-text('错误')"))
                        .or(page.locator(":has-text('失败')"));
                if (errorMsg.count() > 0) {
                    String errorText = errorMsg.first().textContent();
                    log.warn("检测到错误提示: {}", errorText);
                    // 不立即返回false，继续等待
                }
            }
            
            // 最终检查登录状态
            Locator chatInput = page.locator("textarea.ds-scroll-area");
            if (chatInput.count() > 0) {
                log.info("登录成功！");
                return true;
            } else {
                log.warn("登录超时或失败");
                return false;
            }
        } catch (Exception e) {
            log.error("账号+密码登录过程中出错: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理微信扫码登录
     */
    private boolean handleWechatLogin(Page page, LoginSessionManager.LoginSession session,
                                     SseEmitter emitter, ChatCompletionRequest request) {
        try {
            log.info("开始微信扫码登录流程");
            
            // 检查是否已有二维码（用户可能重新请求）
            if (session.getQrCodeImageUrl() != null) {
                log.info("二维码已存在，直接发送给用户");
                sendWechatQrCode(emitter, request.getModel(), 
                    session.getQrCodeImageUrl(), session.getConversationId());
                return false; // 返回 false 表示等待用户扫码确认
            }
            
            // 1. 查找并点击微信登录按钮
            Locator wechatButton = page.locator("button:has-text('微信')")
                    .or(page.locator("button:has-text('WeChat')"))
                    .or(page.locator(".ds-sign-in-with-wechat-block"))
                    .or(page.locator("[class*='wechat']"))
                    .or(page.locator("[class*='WeChat']"));
            
            if (wechatButton.count() == 0) {
                log.error("未找到微信登录按钮");
                session.setLoginError("未找到微信登录按钮");
                return false;
            }
            
            log.info("找到微信登录按钮，准备点击");
            wechatButton.first().click();
            page.waitForTimeout(1000);
            
            // 2. 等待二维码 iframe 加载
            log.info("等待微信二维码加载...");
            page.waitForTimeout(2000);
            
            // 3. 查找二维码 iframe
            Locator qrCodeIframe = page.locator("iframe[src*='weixin.gg']")
                    .or(page.locator("iframe[src*='wechat']"))
                    .or(page.locator("iframe#wxLogin"))
                    .or(page.locator("iframe"));
            
            if (qrCodeIframe.count() == 0) {
                log.error("未找到微信二维码 iframe");
                session.setLoginError("未找到微信二维码");
                return false;
            }
            
            log.info("找到微信二维码 iframe");
            
            // 4. 获取 iframe 内容
            Frame iframeFrame = qrCodeIframe.first().elementHandle().contentFrame();
            if (iframeFrame == null) {
                log.error("无法获取 iframe 内容");
                session.setLoginError("无法加载微信二维码");
                return false;
            }
            
            // 等待 iframe 内容加载
            iframeFrame.waitForLoadState();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 5. 在 iframe 中查找二维码图片
            // 使用 JavaScript 在 iframe 中查找
            String qrCodeImageSrc = (String) iframeFrame.evaluate("""
                () => {
                    const img = document.querySelector('img.js_qrcode_img') || 
                                document.querySelector('img.web_qrcode_img') ||
                                document.querySelector('img[src*="qrcode"]') ||
                                document.querySelector('img');
                    return img ? img.src : null;
                }
                """);
            
            if (qrCodeImageSrc == null || qrCodeImageSrc.isEmpty()) {
                log.error("未找到二维码图片");
                session.setLoginError("未找到二维码图片");
                return false;
            }
            
            // 6. 处理相对路径
            String qrCodeImageUrl = qrCodeImageSrc;
            if (qrCodeImageSrc.startsWith("/")) {
                // 相对路径，需要拼接完整 URL
                String iframeUrl = iframeFrame.url();
                if (iframeUrl.contains("weixin.gg") || iframeUrl.contains("wechat")) {
                    // 从 iframe URL 提取基础 URL
                    int protocolIndex = iframeUrl.indexOf("://");
                    int pathIndex = iframeUrl.indexOf("/", protocolIndex + 3);
                    if (pathIndex > 0) {
                        String baseUrl = iframeUrl.substring(0, pathIndex);
                        qrCodeImageUrl = baseUrl + qrCodeImageSrc;
                    } else {
                        qrCodeImageUrl = iframeUrl.substring(0, iframeUrl.indexOf("/", protocolIndex + 3)) + qrCodeImageSrc;
                    }
                } else {
                    qrCodeImageUrl = "https://open.weixin.gg.com" + qrCodeImageSrc;
                }
            }
            
            log.info("获取到二维码图片 URL: {}", qrCodeImageUrl);
            
            // 7. 保存二维码信息到会话
            session.setQrCodeImageUrl(qrCodeImageUrl);
            session.setState(LoginSessionManager.LoginSessionState.WAITING_WECHAT_SCAN);
            loginSessionManager.saveSession(getProviderName(), session.getConversationId(), session);
            
            // 8. 发送二维码给前端
            sendWechatQrCode(emitter, request.getModel(), qrCodeImageUrl, 
                session.getConversationId());
            
            log.info("微信二维码已发送，等待用户扫码确认");
            return false; // 返回 false 表示等待用户扫码确认
            
        } catch (Exception e) {
            log.error("微信扫码登录过程中出错: {}", e.getMessage(), e);
            session.setLoginError("微信登录出错: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 下载图片并转换为 base64 编码，同时缩放至 160x160
     */
    private String downloadImageAsBase64(String imageUrl) {
        try {
            log.info("开始下载二维码图片: {}", imageUrl);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();
            
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            
            if (response.statusCode() == 200) {
                byte[] originalImageBytes = response.body();
                
                // 根据响应头确定图片类型，默认为 png
                String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
                String imageType = "png";
                if (contentType.contains("jpeg") || contentType.contains("jpg")) {
                    imageType = "jpeg";
                } else if (contentType.contains("gif")) {
                    imageType = "gif";
                } else if (contentType.contains("webp")) {
                    imageType = "webp";
                }
                
                // 读取原始图片
                BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalImageBytes));
                if (originalImage == null) {
                    log.warn("无法解析图片，使用原始图片");
                    String base64Image = Base64.getEncoder().encodeToString(originalImageBytes);
                    return "data:image/" + imageType + ";base64," + base64Image;
                }
                
                int originalWidth = originalImage.getWidth();
                int originalHeight = originalImage.getHeight();
                log.info("原始图片尺寸: {}x{}", originalWidth, originalHeight);
                
                // 缩放图片至 160x160
                int targetWidth = 160;
                int targetHeight = 160;
                
                // 使用与原始图片相同的类型，如果是透明图片则保留透明度
                int bufferedImageType = originalImage.getType();
                if (bufferedImageType == BufferedImage.TYPE_CUSTOM || bufferedImageType == 0) {
                    bufferedImageType = originalImage.getColorModel().hasAlpha() ? 
                        BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
                }
                
                BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, bufferedImageType);
                Graphics2D g2d = resizedImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 直接绘制并缩放
                g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
                g2d.dispose();
                
                // 验证缩放后的尺寸
                int resizedWidth = resizedImage.getWidth();
                int resizedHeight = resizedImage.getHeight();
                log.info("缩放后图片尺寸: {}x{}", resizedWidth, resizedHeight);
                
                // 将缩放后的图片转换为字节数组
                // 对于 GIF 和 WebP 格式，统一转换为 PNG 以确保兼容性
                String outputImageType = imageType;
                if ("gif".equals(imageType) || "webp".equals(imageType)) {
                    outputImageType = "png";
                }
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resizedImage, outputImageType, baos);
                byte[] resizedImageBytes = baos.toByteArray();
                
                // 转换为 base64
                String base64Image = Base64.getEncoder().encodeToString(resizedImageBytes);
                String dataUri = "data:image/" + outputImageType + ";base64," + base64Image;
                
                log.info("二维码图片下载并缩放成功，原始大小: {} bytes, 缩放后大小: {} bytes, 类型: {}", 
                        originalImageBytes.length, resizedImageBytes.length, imageType);
                return dataUri;
            } else {
                log.warn("下载二维码图片失败，状态码: {}", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("下载二维码图片时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 发送微信二维码给前端
     * 发送顺序：1. 系统消息 2. 二维码图片（base64） 3. 对话标记
     */
    private void sendWechatQrCode(SseEmitter emitter, String model, 
                                   String qrCodeImageUrl, String conversationId) {
        if (emitter == null) {
            log.warn("Emitter 为 null，无法发送二维码");
            return;
        }
        
        try {
            MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
            
            // 1. 先发送文本提示作为系统消息
            StringBuilder message = new StringBuilder();
            message.append("请使用微信扫描下方二维码完成登录。\n\n");
            message.append("二维码图片地址：").append(qrCodeImageUrl).append("\n\n");
            message.append("扫码完成后，请回复\"已扫码\"或\"确认\"来确认登录。");
            
            // 格式化系统消息
            String formattedMessage = formatSystemMessage(message.toString());
            
            String systemId = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice systemChoice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(formattedMessage).build())
                    .index(0).build();
            ChatCompletionResponse systemResponse = ChatCompletionResponse.builder()
                    .id(systemId).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(systemChoice)).build();
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(systemResponse), APPLICATION_JSON_UTF8));
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            
            // 2. 下载二维码图片并转换为 base64，然后发送作为普通消息
            String base64Image = downloadImageAsBase64(qrCodeImageUrl);
            String qrCodeImageMessage;
            if (base64Image != null) {
                // 使用 Markdown 语法，图片已在服务器端缩放到 160x160
                qrCodeImageMessage = "\n\n![二维码](" + base64Image + ")";
                log.info("使用 base64 格式发送二维码图片（160x160）");
            } else {
                // 降级到使用 URL
                qrCodeImageMessage = "\n\n![二维码](" + qrCodeImageUrl + ")";
                log.warn("无法下载图片，使用 URL 方式发送二维码");
            }
            
            String imageId = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice imageChoice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(qrCodeImageMessage).build())
                    .index(0).build();
            ChatCompletionResponse imageResponse = ChatCompletionResponse.builder()
                    .id(imageId).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(imageChoice)).build();
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(imageResponse), APPLICATION_JSON_UTF8));
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            
            // 3. 发送对话标记
            if (conversationId != null && !conversationId.isEmpty()) {
                String conversationIdMessage = "\n\n```nwla-conversation-id\n" + conversationId + "\n```";
                String conversationIdId = UUID.randomUUID().toString();
                ChatCompletionResponse.Choice conversationIdChoice = ChatCompletionResponse.Choice.builder()
                        .delta(ChatCompletionResponse.Delta.builder().content(conversationIdMessage).build())
                        .index(0).build();
                ChatCompletionResponse conversationIdResponse = ChatCompletionResponse.builder()
                        .id(conversationIdId).object("chat.completion.chunk")
                        .created(System.currentTimeMillis() / 1000)
                        .model(model).choices(List.of(conversationIdChoice)).build();
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(conversationIdResponse), APPLICATION_JSON_UTF8));
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            }
            
            emitter.complete();
            
            log.info("微信二维码已发送给前端（图片URL: {}）", qrCodeImageUrl);
            
            // 二维码发送完成，立即释放锁，允许用户发送"已扫码"消息
            String providerName = getProviderName();
            if (providerName != null) {
                new Thread(() -> {
                    try {
                        Thread.sleep(50); // 稍微延迟，确保消息已发送
                        providerRegistry.releaseLock(providerName);
                        log.info("二维码发送完成，已释放锁: {}", providerName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // 即使被中断，也要释放锁
                        providerRegistry.releaseLock(providerName);
                    }
                }).start();
            }
        } catch (Exception e) {
            log.error("发送微信二维码时出错: {}", e.getMessage(), e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.error("完成 emitter 时出错: {}", ex.getMessage());
            }
            // 出错时也要释放锁
            String providerName = getProviderName();
            if (providerName != null) {
                providerRegistry.releaseLock(providerName);
            }
        }
    }
    
    /**
     * 检查微信登录状态（在用户确认扫码后调用）
     */
    private boolean checkWechatLoginStatus(Page page, LoginSessionManager.LoginSession session) {
        try {
            log.info("检查微信登录状态");
            
            // 刷新页面或重新导航到登录页面，检查是否已登录
            page.reload();
            page.waitForLoadState();
            page.waitForTimeout(2000);
            
            // 检查是否出现聊天输入框（已登录会有聊天输入框）
            Locator chatInput = page.locator("textarea")
                    .or(page.locator("textarea[placeholder*='输入']"))
                    .or(page.locator("textarea[placeholder*='输入消息']"))
                    .or(page.locator("textarea.ds-scroll-area"));
            
            if (chatInput.count() > 0) {
                Locator visibleInput = chatInput.first();
                if (visibleInput.isVisible() && visibleInput.isEnabled()) {
                    log.info("微信登录成功！检测到聊天输入框");
                    return true;
                }
            }
            
            log.info("微信登录尚未完成，未检测到聊天输入框");
            return false;
        } catch (Exception e) {
            log.error("检查微信登录状态时出错: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
         * 登录响应结果
         */
        private record LoginResponseResult(boolean success, int bizCode, String bizMsg) {
    }
    
    /**
     * 设置登录接口拦截器
     */
    private void setupLoginInterceptor(Page page) {
        try {
            // 清空旧的登录响应数据
            page.evaluate("() => { window.__deepseekLoginResponse = null; }");
            
            String jsCode = """
                (function() {
                    if (window.__deepseekLoginInterceptorSet) return;
                    window.__deepseekLoginInterceptorSet = true;
                    
                    const originalFetch = window.fetch;
                    window.fetch = function(...args) {
                        const url = args[0];
                        if (typeof url === 'string' && url.includes('/api/v0/users/login')) {
                            return originalFetch.apply(this, args).then(async response => {
                                try {
                                    const clonedResponse = response.clone();
                                    const responseData = await clonedResponse.json();
                                    window.__deepseekLoginResponse = JSON.stringify(responseData);
                                    console.log('登录接口响应:', responseData);
                                } catch (e) {
                                    console.error('解析登录响应失败:', e);
                                }
                                return response;
                            }).catch(err => {
                                console.error('登录请求失败:', err);
                                return originalFetch.apply(this, args);
                            });
                        }
                        return originalFetch.apply(this, args);
                    };
                })();
                """;
            
            page.evaluate(jsCode);
            log.info("已设置登录接口拦截器");
        } catch (Exception e) {
            log.error("设置登录接口拦截器失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取登录接口响应
     */
    private String getLoginResponse(Page page) {
        try {
            if (page.isClosed()) return null;
            Object result = page.evaluate("() => window.__deepseekLoginResponse || null");
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            log.warn("获取登录响应时出错: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 解析登录响应
     */
    private LoginResponseResult parseLoginResponse(String responseJson) {
        try {
            JsonNode json = objectMapper.readTree(responseJson);
            
            // 检查 code 字段
            int code = json.has("code") ? json.get("code").asInt() : -1;
            
            // 检查 data 字段
            if (json.has("data") && json.get("data").isObject()) {
                JsonNode data = json.get("data");
                int bizCode = data.has("biz_code") ? data.get("biz_code").asInt() : 0;
                String bizMsg = "";
                if (data.has("biz_msg")) {
                    JsonNode bizMsgNode = data.get("biz_msg");
                    if (bizMsgNode != null && bizMsgNode.isString()) {
                        bizMsg = bizMsgNode.asString();
                    }
                }
                
                // biz_code 为 0 表示成功，非 0 表示失败
                boolean success = (code == 0 && bizCode == 0);
                
                return new LoginResponseResult(success, bizCode, bizMsg);
            }
            
            // 如果没有 data 字段，根据 code 判断
            boolean success = (code == 0);
            return new LoginResponseResult(success, code, code == 0 ? "成功" : "失败");
        } catch (Exception e) {
            log.error("解析登录响应失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void streamChat(ChatCompletionRequest request, SseEmitter emitter) {
        executor.submit(() -> {
            Page page = null;
            try {
                String model = request.getModel();
                DeepSeekModelConfig config = modelConfigs.get(model);
                
                if (config == null) {
                    throw new IllegalArgumentException("不支持的模型: " + model);
                }

                // 注意：指令检查已在 Controller 层统一处理，这里只处理普通聊天请求
                // 1. 获取或创建页面
                page = getOrCreatePage(request);
                
                // 1.5. 检查登录状态（在创建页面后再次检查，因为页面创建时可能检测到登录状态丢失）
                if (!checkLoginStatus(page)) {
                    log.warn("检测到未登录状态，发送登录提示");
                    // 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
                    // 状态更新由 Controller 层处理
                    
                    // 获取或生成对话ID
                    String conversationId = getConversationId(request);
                    if (conversationId == null || conversationId.isEmpty()) {
                        conversationId = "login-" + UUID.randomUUID();
                    }
                    
                    // 创建登录会话
                    LoginSessionManager.LoginSession session = loginSessionManager.getOrCreateSession(getProviderName(), conversationId);
                    session.setConversationId(conversationId);
                    session.setState(LoginSessionManager.LoginSessionState.WAITING_LOGIN_METHOD);
                    // 保存会话（确保状态被持久化）
                    loginSessionManager.saveSession(getProviderName(), conversationId, session);
                    
                    // 发送登录方式选择提示
                    sendLoginMethodSelection(emitter, request.getModel(), getProviderName(), conversationId);
                    return;
                }
                
                // 2. 设置 SSE 拦截器
                setupSseInterceptor(page);
                
                // 3. 如果是新对话，点击"新对话"按钮
                if (isNewConversation(request)) {
                    clickNewChatButton(page);
                }
                
                // 4. 配置模型（由 ModelConfig 实现）
                config.configure(page);
                
                // 5. 处理联网搜索
                handleWebSearchToggle(page, request.isWebSearch());
                
                // 6. 发送消息
                sendMessage(page, request);
                
                // 7. 记录发送前消息数量
                int messageCountBefore = page.locator(".ds-markdown").count();
                log.info("发送前消息数量: {}", messageCountBefore);
                
                // 8. 验证 SSE 拦截器
                verifySseInterceptor(page);
                
                // 9. 监听响应（由 ModelConfig 实现，仅支持 SSE 模式）
                DeepSeekContext context = new DeepSeekContext(
                        page, emitter, request, messageCountBefore, responseHandler
                );
                config.monitorResponse(context);

            } catch (Exception e) {
                log.error("Chat Error", e);
                emitter.completeWithError(e);
                cleanupPageOnError(page, request.getModel());
            }
        });
    }
    
    // ==================== 页面管理 ====================
    
    @Override
    public Page getOrCreatePage(ChatCompletionRequest request) {
        String model = request.getModel();
        String accountId = request.getAccountId();
        String conversationId = getConversationId(request);
        boolean isNewConversation = isNewConversation(request);
        
        Page page;
        if (!isNewConversation && conversationId != null) {
            String conversationUrl = buildUrlFromConversationId(conversationId);
            page = findOrCreatePageForUrl(conversationUrl, model, accountId);
        } else {
            page = createNewConversationPage(model, accountId);
        }
        
        return page;
    }
    
    @Override
    public String getConversationId(ChatCompletionRequest request) {
        // 首先尝试从请求中获取
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        
        // 从历史消息中提取
        if (request.getMessages() != null) {
            conversationId = ConversationIdUtils.extractConversationIdFromRequest(request, true);
        }
        return conversationId;
    }
    
    @Override
    public boolean isNewConversation(ChatCompletionRequest request) {
        String conversationId = getConversationId(request);
        return conversationId == null || conversationId.isEmpty() || conversationId.startsWith("login-");
    }
    
    private Page findOrCreatePageForUrl(String url, String model, String accountId) {
        log.info("检测到对话 URL，尝试复用: {}", url);
        
        // 查找已有页面
        Page page = findPageByUrl(url, accountId);
        
        if (page != null && !page.isClosed()) {
            String currentUrl = page.url();
            if (!currentUrl.equals(url)) {
                page.navigate(url);
                page.waitForLoadState();
                // 检测登录状态是否丢失
                checkLoginStatusLost(page);
            }
            modelPages.put(model, page);
            pageUrls.put(model, url);
            return page;
        }
        
        // 创建新页面（使用 accountId）
        page = browserManager.newPage(getProviderName(), accountId);
        modelPages.put(model, page);
        page.navigate(url);
        page.waitForLoadState();
        pageUrls.put(model, url);
        log.info("已导航到对话 URL: {}", url);
        // 检测登录状态是否丢失
        checkLoginStatusLost(page);
        return page;
    }
    
    private Page createNewConversationPage(String model, String accountId) {
        log.info("开启新对话，accountId: {}", accountId);
        
        // 关闭旧页面
        Page oldPage = modelPages.remove(model);
        if (oldPage != null && !oldPage.isClosed()) {
            try { oldPage.close(); } catch (Exception e) { log.warn("关闭旧页面时出错", e); }
        }
        
        // 创建新页面（使用 accountId）
        Page page = browserManager.newPage(getProviderName(), accountId);
        modelPages.put(model, page);
        page.navigate("https://chat.deepseek.com/");
        page.waitForLoadState();
        pageUrls.put(model, page.url());
        // 检测登录状态是否丢失
        checkLoginStatusLost(page);
        return page;
    }
    
    /**
     * 检测登录状态是否丢失（通过检查是否有登录按钮）
     * 如果检测到登录按钮，说明登录状态丢失
     * 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
     */
    private void checkLoginStatusLost(Page page) {
        try {
            if (page == null || page.isClosed()) {
                return;
            }
            
            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 检查是否有登录按钮（登录状态丢失时会出现登录按钮）
            Locator loginButton = page.locator(".ds-sign-up-form__register-button")
                    .or(page.locator("button:has-text('登录')"))
                    .or(page.locator("button:has-text('Login')"));
            
            if (loginButton.count() > 0 && loginButton.first().isVisible()) {
                log.warn("检测到登录按钮，说明登录状态已丢失");
                // 注意：不在这里更新 providerRegistry 的状态，避免循环依赖
                // 状态更新由 Controller 层处理
            }
        } catch (Exception e) {
            log.warn("检测登录状态丢失时出错: {}", e.getMessage());
        }
    }
    
    private Page findPageByUrl(String targetUrl, String accountId) {
        if (targetUrl == null) return null;
        
        // 检查 modelPages
        for (String model : modelPages.keySet()) {
            Page page = modelPages.get(model);
            if (page != null && !page.isClosed()) {
                String savedUrl = pageUrls.get(model);
                if (targetUrl.equals(savedUrl)) {
                    return page;
                }
            }
        }
        
        // 检查该提供器的所有 tab（使用 accountId）
        try {
            for (Page page : browserManager.getAllPages(getProviderName(), accountId)) {
                if (page != null && !page.isClosed() && targetUrl.equals(page.url())) {
                    return page;
                }
            }
        } catch (Exception e) {
            log.error("检查 tab 时出错: {}", e.getMessage());
        }
        
        return null;
    }
    
    private void clickNewChatButton(Page page) {
        try {
            page.waitForTimeout(1000);
            Locator newChatButton = page.locator("button:has-text('新对话')")
                    .or(page.locator("button:has-text('New Chat')"))
                    .or(page.locator("[aria-label*='New'], [aria-label*='new']"))
                    .first();
            if (newChatButton.count() > 0) {
                newChatButton.click();
                page.waitForTimeout(500);
                log.info("已点击新对话按钮");
            }
        } catch (Exception e) {
            log.warn("点击新对话按钮时出错", e);
        }
    }
    
    private void sendMessage(Page page, ChatCompletionRequest request) {
        Locator inputBox = page.locator("textarea.ds-scroll-area");
        inputBox.waitFor();
        
        String message = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(ChatCompletionRequest.Message::getContent)
                .orElse("Hello");
        
        log.info("发送消息: {}", message);
        inputBox.fill(message);

        // 验证是否填充完成
        page.waitForTimeout(200);
        String filledText = inputBox.inputValue();

        if (!filledText.equals(message)) {
            log.warn("输入框内容与预期不符，重试填充");
            inputBox.fill(message);
        }
        
        // 点击发送按钮
        page.waitForTimeout(300);
        Locator sendButton = page.locator("div.ds-icon-button").filter(
                new Locator.FilterOptions().setHas(page.locator("svg path[d*='M8.3125 0.981587']"))
        );
        if (sendButton.count() > 0) {
            sendButton.first().click();
            log.info("已点击发送按钮");
        } else {
            // 备用方案：在输入框中按 Enter 键发送
            log.warn("未找到发送按钮，使用输入框 Enter 键发送");
            inputBox.press("Enter");
        }
        page.waitForTimeout(500);
    }
    
    private void cleanupPageOnError(Page page, String model) {
        if (page != null) {
            modelPages.remove(model, page);
            try { if (!page.isClosed()) page.close(); } catch (Exception e) { }
        }
    }
    
    // ==================== SSE 拦截器 ====================
    
    private void setupSseInterceptor(Page page) {
        // 清空旧的 SSE 数据（避免复用页面时读取到旧数据）
        try {
            page.evaluate(String.format("() => { window.%s = []; }", SSE_DATA_VAR));
        } catch (Exception e) {
            log.error("初始化 SSE 数据存储失败: {}", e.getMessage());
        }

        StringBuilder urlCondition = new StringBuilder();
        for (int i = 0; i < SSE_URL_PATTERNS.length; i++) {
            if (i > 0) urlCondition.append(" || ");
            urlCondition.append("url.includes('").append(SSE_URL_PATTERNS[i]).append("')");
        }

        String jsCode = buildSseInterceptorScript(urlCondition.toString());
        
        try {
            page.evaluate(jsCode);
            log.info("已设置 SSE 拦截器");
        } catch (Exception e) {
            log.error("设置 SSE 拦截器失败: {}", e.getMessage());
        }
    }
    
    private String buildSseInterceptorScript(String urlCondition) {
        return String.format("""
            (function() {
                if (window.%s) return;
                window.%s = true;
                
                const originalFetch = window.fetch;
                window.fetch = function(...args) {
                    const url = args[0];
                    if (typeof url === 'string' && (%s)) {
                        return originalFetch.apply(this, args).then(response => {
                            const contentType = response.headers.get('content-type');
                            if (contentType && contentType.includes('text/event-stream')) {
                                const clonedResponse = response.clone();
                                const reader = clonedResponse.body.getReader();
                                const decoder = new TextDecoder();
                                window.%s = window.%s || [];
                                function readStream() {
                                    reader.read().then(({ done, value }) => {
                                        if (done) return;
                                        const chunk = decoder.decode(value, { stream: true });
                                        window.%s.push(chunk);
                                        readStream();
                                    }).catch(err => {});
                                }
                                readStream();
                            }
                            return response;
                        });
                    }
                    return originalFetch.apply(this, args);
                };
                
                const originalXHROpen = XMLHttpRequest.prototype.open;
                const originalXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                    this._interceptedUrl = url;
                    return originalXHROpen.apply(this, [method, url, ...rest]);
                };
                XMLHttpRequest.prototype.send = function(...args) {
                    if (this._interceptedUrl && (%s)) {
                        this.onreadystatechange = function() {
                            if (this.readyState === 3 || this.readyState === 4) {
                                const responseText = this.responseText;
                                if (responseText) {
                                    const contentType = this.getResponseHeader('content-type');
                                    if (contentType && contentType.includes('text/event-stream')) {
                                        window.%s = window.%s || [];
                                        if (this._lastResponseLength === undefined) this._lastResponseLength = 0;
                                        if (responseText.length > this._lastResponseLength) {
                                            window.%s.push(responseText.substring(this._lastResponseLength));
                                            this._lastResponseLength = responseText.length;
                                        }
                                    }
                                }
                            }
                        };
                    }
                    return originalXHRSend.apply(this, args);
                };
            })();
            """, SSE_INTERCEPTOR_VAR, SSE_INTERCEPTOR_VAR, urlCondition, 
            SSE_DATA_VAR, SSE_DATA_VAR, SSE_DATA_VAR,
            urlCondition.replace("url", "this._interceptedUrl"), 
            SSE_DATA_VAR, SSE_DATA_VAR, SSE_DATA_VAR);
    }
    
    private void verifySseInterceptor(Page page) {
        try {
            Object status = page.evaluate("() => window." + SSE_INTERCEPTOR_VAR + " || false");
            if (!Boolean.TRUE.equals(status)) {
                log.warn("SSE 拦截器未设置，重新设置...");
                setupSseInterceptor(page);
            }
        } catch (Exception e) {
            log.warn("检查 SSE 拦截器状态失败: {}", e.getMessage());
        }
    }
    
    private String getSseDataFromPage(Page page, String varName) {
        try {
            if (page.isClosed()) return null;
            Object result = page.evaluate(String.format("""
                () => {
                    if (window.%s && window.%s.length > 0) {
                        const data = window.%s.join('\\n');
                        window.%s = [];
                        return data;
                    }
                    return null;
                }
                """, varName, varName, varName, varName));
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== 联网搜索 ====================
    
    private void handleWebSearchToggle(Page page, boolean enable) {
        try {
            Locator toggle = page.locator("button:has-text('联网搜索')")
                    .or(page.locator("button:has-text('联网')"))
                    .first();

            if (toggle.count() > 0) {
                String className = toggle.getAttribute("class");
                boolean isActive = className != null && 
                        (className.contains("active") || className.contains("selected") ||
                         className.contains("ds-toggle-button--active"));

                if (enable && !isActive) {
                    toggle.click();
                    page.waitForTimeout(500);
                    log.info("已启用联网搜索");
                } else if (!enable && isActive) {
                    toggle.click();
                    page.waitForTimeout(300);
                    log.info("已关闭联网搜索");
                }
            }
        } catch (Exception e) {
            log.error("处理联网搜索时出错: {}", e.getMessage());
        }
    }
    
    // ==================== SSE 发送 ====================
    
    // UTF-8 编码的 MediaType，确保中文和 emoji 正确传输
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    
    /**
     * 格式化系统消息
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
            message = message.substring("__SYSTEM__".length()).trim();
        }
        // 包装为系统消息格式
        return "```nwla-system-message\n" + message + "\n```";
    }
    
    /**
     * 发送登录方式选择提示
     */
    private void sendLoginMethodSelection(SseEmitter emitter, String model, String providerName, String conversationId) {
        try {
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
            
            String id = UUID.randomUUID().toString();
            ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                    .delta(ChatCompletionResponse.Delta.builder().content(message.toString()).build())
                    .index(0).build();
            ChatCompletionResponse response = ChatCompletionResponse.builder()
                    .id(id).object("chat.completion.chunk")
                    .created(System.currentTimeMillis() / 1000)
                    .model(model).choices(List.of(choice)).build();
            
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
            emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            emitter.complete();
            
            // 释放锁
            if (providerName != null) {
                new Thread(() -> {
                    try {
                        Thread.sleep(100);
                        providerRegistry.releaseLock(providerName);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        } catch (Exception e) {
            log.error("发送登录方式选择提示时出错", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                // 忽略
            }
            if (providerName != null) {
                providerRegistry.releaseLock(providerName);
            }
        }
    }
    
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
    
    private void sendUrlAndComplete(Page page, SseEmitter emitter, ChatCompletionRequest request) throws IOException {
        try {
            if (!page.isClosed()) {
                String url = page.url();
                if (url.contains("chat.deepseek.com")) {
                    pageUrls.put(request.getModel(), url);
                    String conversationId = extractConversationIdFromUrl(url);
                    if (conversationId != null && !conversationId.isEmpty()) {
                        sendConversationId(emitter, UUID.randomUUID().toString(), conversationId, request.getModel());
                        log.info("已发送对话 ID: {} (从 URL: {})", conversationId, url);
                    } else {
                        log.warn("无法从 URL 中提取对话 ID: {}", url);
                    }
                }
            }
        } catch (Exception e) {
            log.error("发送对话 ID 时出错: {}", e.getMessage());
        }
        emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
        emitter.complete();
    }
    
    private void sendConversationId(SseEmitter emitter, String id, String conversationId, String model) throws IOException {
        String content = "\n\n```nwla-conversation-id\n" + conversationId + "\n```\n\n";
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
                .delta(ChatCompletionResponse.Delta.builder().content(content).build())
                .index(0).build();
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id(id).object("chat.completion.chunk")
                .created(System.currentTimeMillis() / 1000)
                .model(model).choices(List.of(choice)).build();
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(response), APPLICATION_JSON_UTF8));
    }
    
    // ==================== SSE 解析 ====================
    
    private String extractTextFromSse(String sseData) {
        if (sseData == null || sseData.isEmpty()) return null;
        
        StringBuilder text = new StringBuilder();
        for (String line : sseData.split("\n")) {
            line = line.trim();
            if (line.startsWith("data: ")) {
                String jsonStr = line.substring(6).trim();
                if (jsonStr.isEmpty() || jsonStr.equals("{}")) continue;
                try {
                    JsonNode json = objectMapper.readTree(jsonStr);
                    if (json.has("v") && json.get("v").isString()) {
                        text.append(json.get("v").asString());
                    }
                } catch (Exception e) { }
            }
        }
        return !text.isEmpty() ? text.toString() : null;
    }
    
    private ModelConfig.ParseResultWithIndex parseSseIncremental(String sseData, Map<Integer, String> fragmentTypeMap, 
                                                     Integer lastActiveFragmentIndex) {
        if (sseData == null || sseData.isEmpty()) {
            return new ModelConfig.ParseResultWithIndex(new ModelConfig.SseParseResult(null, null, false), lastActiveFragmentIndex);
        }
        
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder responseText = new StringBuilder();
        boolean finished = false;
        Integer currentActiveIndex = lastActiveFragmentIndex;
        
        for (String line : sseData.split("\n")) {
            line = line.trim();
            
            if (line.startsWith("event: ")) {
                String event = line.substring(7).trim();
                if ("finish".equals(event) || "close".equals(event)) {
                    finished = true;
                }
                continue;
            }
            
            if (!line.startsWith("data: ")) continue;
            String jsonStr = line.substring(6).trim();
            if (jsonStr.isEmpty() || jsonStr.equals("{}")) continue;
            
            try {
                JsonNode json = objectMapper.readTree(jsonStr);
                String path = json.has("p") ? json.get("p").asString() : null;
                String operation = json.has("o") ? json.get("o").asString() : null;
                
                // Fragment 创建（处理多种可能的路径格式）
                if ((path != null && (path.equals("fragments") || path.equals("response/fragments") || path.endsWith("/fragments")))
                        && "APPEND".equals(operation) && json.has("v") && json.get("v").isArray()) {
                    int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                            fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                    for (JsonNode fragment : json.get("v")) {
                        if (fragment.has("type")) {
                            String type = fragment.get("type").asString();
                            fragmentTypeMap.put(nextIndex, type);
                            log.info("创建 fragment {}: type={}", nextIndex, type);
                            if (fragment.has("content")) {
                                String content = fragment.get("content").asString();
                                if (content != null && !content.isEmpty()) {
                                    log.info("Fragment {} 初始内容: {}", nextIndex, content.length() > 50 ? content.substring(0, 50) + "..." : content);
                                    if ("THINK".equals(type)) thinkingText.append(content);
                                    else if ("RESPONSE".equals(type)) responseText.append(content);
                                }
                            }
                            // 更新当前活动索引为最后创建的 fragment
                            currentActiveIndex = nextIndex;
                            nextIndex++;
                        }
                    }
                    continue;
                }
                
                // 内容更新 - 处理完整路径
                if (path != null && path.contains("fragments/") && path.endsWith("/content")) {
                    Integer idx = extractFragmentIndex(path);
                    if (idx != null) {
                        // 处理 -1 索引（表示最后一个 fragment）
                        if (idx == -1 && !fragmentTypeMap.isEmpty()) {
                            idx = fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                        }
                        if (idx >= 0 && fragmentTypeMap.containsKey(idx)) {
                            currentActiveIndex = idx;
                            String type = fragmentTypeMap.get(idx);
                            if (json.has("v") && json.get("v").isString()) {
                                String content = json.get("v").asString();
                                if (content != null && !content.isEmpty()) {
                                    log.trace("Fragment {} 内容更新 (type={}): {}", idx, type, content);
                                    if ("THINK".equals(type)) thinkingText.append(content);
                                    else responseText.append(content);
                                }
                            }
                        } else {
                            log.info("Fragment 索引无效或不存在: idx={}, path={}", idx, path);
                        }
                    } else {
                        log.info("无法从路径提取索引: {}", path);
                    }
                }
                // 简单格式 - 只有 v 字段，没有 p 字段
                else if (json.has("v") && !json.has("p") && json.get("v").isString()) {
                    String content = json.get("v").asString();
                    if (content != null && !content.isEmpty()) {
                        // 根据当前活动的 fragment 类型决定是思考还是回复
                        boolean isThinking = false;
                        
                        if (currentActiveIndex != null && fragmentTypeMap.containsKey(currentActiveIndex)) {
                            // 如果当前活动 fragment 类型已知，直接使用
                            isThinking = "THINK".equals(fragmentTypeMap.get(currentActiveIndex));
                        } else {
                            // 如果 fragment 类型未知，使用启发式判断：
                            boolean hasThinkFragment = fragmentTypeMap.values().stream().anyMatch("THINK"::equals);
                            boolean hasResponseFragment = fragmentTypeMap.values().stream().anyMatch("RESPONSE"::equals);
                            
                            // 启发式规则：
                            // 1. 如果有 THINK fragment 且还没有 RESPONSE fragment，说明还在思考阶段
                            // 2. 如果已有思考内容但还没有回复内容，继续当作思考内容（深度思考进行中）
                            // 3. 如果已有回复内容，继续当作回复内容
                            // 4. 如果只有 RESPONSE fragment（没有 THINK fragment），说明是不带思考的响应
                            if (hasThinkFragment && !hasResponseFragment) {
                                // 有思考 fragment 但还没有回复 fragment，说明还在思考
                                isThinking = true;
                            } else if (thinkingText.length() > 0 && responseText.length() == 0) {
                                // 已有思考内容但还没有回复内容，说明还在深度思考中
                                isThinking = true;
                            } else if (responseText.length() > 0) {
                                // 已有回复内容，继续当作回复内容
                                isThinking = false;
                            } else if (hasResponseFragment && !hasThinkFragment) {
                                // 只有 RESPONSE fragment，没有 THINK fragment，说明是不带思考的响应
                                isThinking = false;
                            } else if (fragmentTypeMap.isEmpty()) {
                                // fragmentTypeMap 为空时，如果已有思考内容，继续当作思考内容
                                // 否则当作回复内容（保守策略，因为大多数情况下是不带思考的）
                                isThinking = thinkingText.length() > 0;
                            } else {
                                // 其他情况，默认当作回复内容
                                isThinking = false;
                            }
                        }
                        
                        log.trace("简单格式内容 (activeIdx={}, isThinking={}, hasThink={}, hasResponse={}, thinkingLen={}, responseLen={}): {}", 
                                currentActiveIndex, isThinking, 
                                fragmentTypeMap.values().stream().anyMatch("THINK"::equals),
                                fragmentTypeMap.values().stream().anyMatch("RESPONSE"::equals),
                                thinkingText.length(), responseText.length(),
                                content.length() > 50 ? content.substring(0, 50) + "..." : content);
                        if (isThinking) {
                            thinkingText.append(content);
                        } else {
                            responseText.append(content);
                        }
                    }
                }
                // BATCH 操作
                else if (path != null && "BATCH".equals(operation) && json.has("v") && json.get("v").isArray()) {
                    for (JsonNode item : json.get("v")) {
                        if (!item.has("p") || !item.has("v")) continue;
                        String itemPath = item.get("p").asString();
                        
                        // 处理 fragment 创建
                        if (itemPath != null && (itemPath.equals("fragments") || itemPath.endsWith("/fragments"))) {
                            if (item.has("o") && "APPEND".equals(item.get("o").asString()) && item.get("v").isArray()) {
                                int nextIndex = fragmentTypeMap.isEmpty() ? 0 : 
                                        fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                                for (JsonNode fragment : item.get("v")) {
                                    if (fragment.has("type")) {
                                        String type = fragment.get("type").asString();
                                        fragmentTypeMap.put(nextIndex, type);
                                        log.info("BATCH 创建 fragment {}: type={}", nextIndex, type);
                                        if (fragment.has("content")) {
                                            String content = fragment.get("content").asString();
                                            if (content != null && !content.isEmpty()) {
                                                if ("THINK".equals(type)) thinkingText.append(content);
                                                else if ("RESPONSE".equals(type)) responseText.append(content);
                                            }
                                        }
                                        // 更新当前活动索引为最后创建的 fragment
                                        currentActiveIndex = nextIndex;
                                        nextIndex++;
                                    }
                                }
                            }
                            continue;
                        }
                        
                        // 处理内容更新
                        if (itemPath != null && itemPath.contains("fragments/") && itemPath.endsWith("/content")) {
                            Integer idx = extractFragmentIndex(itemPath);
                            if (idx != null) {
                                // 处理 -1 索引（表示最后一个 fragment）
                                if (idx == -1 && !fragmentTypeMap.isEmpty()) {
                                    idx = fragmentTypeMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                                }
                                if (idx >= 0 && fragmentTypeMap.containsKey(idx)) {
                                    currentActiveIndex = idx;
                                    String type = fragmentTypeMap.get(idx);
                                    if (item.get("v").isString()) {
                                        String content = item.get("v").asString();
                                        if (content != null && !content.isEmpty()) {
                                            if ("THINK".equals(type)) thinkingText.append(content);
                                            else if ("RESPONSE".equals(type)) responseText.append(content);
                                        }
                                    }
                                } else {
                                    log.info("BATCH Fragment 索引无效或不存在: idx={}, path={}", idx, itemPath);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.info("解析 SSE 数据行时出错: {}, line: {}", e.getMessage(), line.length() > 100 ? line.substring(0, 100) + "..." : line);
            }
        }
        
        if (log.isDebugEnabled() && (thinkingText.length() > 0 || responseText.length() > 0)) {
            log.info("SSE 解析结果: thinking={} chars, response={} chars, finished={}", 
                    thinkingText.length(), responseText.length(), finished);
        }
        
        ModelConfig.SseParseResult result = new ModelConfig.SseParseResult(
                thinkingText.length() > 0 ? thinkingText.toString() : null,
                responseText.length() > 0 ? responseText.toString() : null,
                finished
        );
        return new ModelConfig.ParseResultWithIndex(result, currentActiveIndex);
    }
    
    /**
     * 从路径中提取 fragment 索引
     * 支持格式：response/fragments/0/content, fragments/0/content
     */
    private Integer extractFragmentIndex(String path) {
        if (path == null) return null;
        try {
            // 查找 "fragments/" 后面的数字
            int fragmentsIdx = path.indexOf("fragments/");
            if (fragmentsIdx >= 0) {
                String afterFragments = path.substring(fragmentsIdx + "fragments/".length());
                int slashIdx = afterFragments.indexOf('/');
                String indexStr = slashIdx > 0 ? afterFragments.substring(0, slashIdx) : afterFragments;
                return Integer.parseInt(indexStr);
            }
        } catch (Exception e) {
            log.info("提取 fragment 索引失败: path={}", path);
        }
        return null;
    }
    
    // ==================== 对话 ID 提取 ====================
    
    /**
     * 从 URL 中提取对话 ID
     * 支持格式：
     * - https://chat.deepseek.com/a/chat/s/{id}
     * - https://chat.deepseek.com/chat/{id}
     * - https://chat.deepseek.com/chat/{id}?...
     */
    private String extractConversationIdFromUrl(String url) {
        if (url == null || !url.contains("chat.deepseek.com")) {
            return null;
        }
        
        try {
            // 优先尝试匹配格式: https://chat.deepseek.com/a/chat/s/{id}
            int sChatIdx = url.indexOf("/a/chat/s/");
            if (sChatIdx >= 0) {
                String afterSChat = url.substring(sChatIdx + "/a/chat/s/".length());
                // 移除查询参数和片段
                int queryIdx = afterSChat.indexOf('?');
                int fragmentIdx = afterSChat.indexOf('#');
                int endIdx = afterSChat.length();
                if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);
                
                String id = afterSChat.substring(0, endIdx).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            
            // 尝试匹配格式: https://chat.deepseek.com/chat/{id}
            int chatIdx = url.indexOf("/chat/");
            if (chatIdx >= 0) {
                String afterChat = url.substring(chatIdx + "/chat/".length());
                // 移除查询参数和片段
                int queryIdx = afterChat.indexOf('?');
                int fragmentIdx = afterChat.indexOf('#');
                int endIdx = afterChat.length();
                if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);
                
                String id = afterChat.substring(0, endIdx).trim();
                // 如果提取的 ID 包含 s/ 前缀，去掉它
                if (id.startsWith("s/")) {
                    id = id.substring(2);
                }
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.warn("从 URL 提取对话 ID 失败: url={}, error={}", url, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 从对话 ID 构建 URL
     */
    private String buildUrlFromConversationId(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        // 使用新的 URL 格式: /a/chat/s/{id}
        return "https://chat.deepseek.com/a/chat/s/" + conversationId;
    }
    
    
    // ==================== ProviderLoginHandler 实现 ====================
    
    // 登录会话管理（用于 admin 登录）
    private final ConcurrentHashMap<String, LoginSession> adminLoginSessions = new ConcurrentHashMap<>();
    
    @Data
    private static class LoginSession {
        private String sessionId;
        private String accountId;
        private Page loginPage;
        private String qrCodeImageUrl;
        private long createdAt;
        
        public LoginSession() {
            this.sessionId = UUID.randomUUID().toString();
            this.createdAt = System.currentTimeMillis();
        }
    }
    
    @Override
    public Map<String, Object> startManualLogin(String accountId) {
        try {
            log.info("启动 DeepSeek 手动登录流程，accountId: {}", accountId);
            
            // 创建登录页面
            Page page = getOrCreateLoginPage(accountId);
            page.navigate("https://chat.deepseek.com/");
            page.waitForLoadState();
            
            // 创建登录会话
            LoginSession session = new LoginSession();
            session.setAccountId(accountId);
            session.setLoginPage(page);
            adminLoginSessions.put(session.getSessionId(), session);
            
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", session.getSessionId());
            result.put("message", "浏览器已打开，请在浏览器中完成登录");
            result.put("success", true);
            
            return result;
        } catch (Exception e) {
            log.error("启动手动登录失败: accountId={}", accountId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    @Override
    public Map<String, Object> startAccountPasswordLogin(String accountId, String account, String password) {
        try {
            log.info("启动 DeepSeek 账号+密码登录，accountId: {}, account: {}", accountId, account);
            
            // 创建登录页面
            Page page = getOrCreateLoginPage(accountId);
            page.navigate("https://chat.deepseek.com/");
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 创建登录会话
            LoginSession session = new LoginSession();
            session.setAccountId(accountId);
            session.setLoginPage(page);
            adminLoginSessions.put(session.getSessionId(), session);
            
            // 创建 LoginSessionManager.LoginSession 用于登录流程
            LoginSessionManager.LoginSession loginSession = loginSessionManager.getOrCreateSession(
                getProviderName(), "admin-" + session.getSessionId());
            loginSession.setLoginMethod(LoginSessionManager.LoginMethod.ACCOUNT_PASSWORD);
            loginSession.setAccount(account);
            loginSession.setPassword(password);
            loginSession.setState(LoginSessionManager.LoginSessionState.LOGGING_IN);
            
            // 执行登录
            boolean success = handleAccountPasswordLogin(page, loginSession);
            
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", session.getSessionId());
            result.put("success", success);
            if (success) {
                result.put("message", "登录成功");
            } else {
                String errorMsg = loginSession.getLoginError();
                result.put("message", errorMsg != null ? errorMsg : "登录失败");
                result.put("error", errorMsg);
            }
            
            return result;
        } catch (Exception e) {
            log.error("账号+密码登录失败: accountId={}", accountId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    @Override
    public Map<String, Object> startQrCodeLogin(String accountId) {
        try {
            log.info("启动 DeepSeek 二维码登录，accountId: {}", accountId);
            
            // 创建登录页面
            Page page = getOrCreateLoginPage(accountId);
            page.navigate("https://chat.deepseek.com/");
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 创建登录会话
            LoginSession session = new LoginSession();
            session.setAccountId(accountId);
            session.setLoginPage(page);
            adminLoginSessions.put(session.getSessionId(), session);
            
            // 创建 LoginSessionManager.LoginSession 用于登录流程
            String conversationId = "admin-" + session.getSessionId();
            LoginSessionManager.LoginSession loginSession = loginSessionManager.getOrCreateSession(
                getProviderName(), conversationId);
            loginSession.setLoginMethod(LoginSessionManager.LoginMethod.WECHAT_SCAN);
            loginSession.setConversationId(conversationId);
            loginSession.setState(LoginSessionManager.LoginSessionState.WAITING_WECHAT_SCAN);
            
            // 获取二维码（不使用 SSE）
            String qrCodeImageUrl = getQrCodeImageUrl(page, loginSession);
            
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", session.getSessionId());
            
            if (qrCodeImageUrl != null && !qrCodeImageUrl.isEmpty()) {
                // 下载二维码并转换为 base64
                String qrCodeBase64 = downloadImageAsBase64(qrCodeImageUrl);
                
                result.put("success", true);
                result.put("message", "二维码已生成");
                result.put("qrCodeImageUrl", qrCodeImageUrl);
                if (qrCodeBase64 != null) {
                    result.put("qrCodeBase64", qrCodeBase64);
                }
                session.setQrCodeImageUrl(qrCodeImageUrl);
            } else {
                String errorMsg = loginSession.getLoginError();
                result.put("success", false);
                result.put("message", errorMsg != null ? errorMsg : "获取二维码失败");
                result.put("error", errorMsg);
            }
            
            return result;
        } catch (Exception e) {
            log.error("二维码登录失败: accountId={}", accountId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 获取二维码图片 URL（不通过 SSE）
     */
    private String getQrCodeImageUrl(Page page, LoginSessionManager.LoginSession session) {
        try {
            log.info("开始获取微信二维码");
            
            // 1. 查找并点击微信登录按钮
            Locator wechatButton = page.locator("button:has-text('微信')")
                    .or(page.locator("button:has-text('WeChat')"))
                    .or(page.locator(".ds-sign-in-with-wechat-block"))
                    .or(page.locator("[class*='wechat']"))
                    .or(page.locator("[class*='WeChat']"));
            
            if (wechatButton.count() == 0) {
                log.error("未找到微信登录按钮");
                session.setLoginError("未找到微信登录按钮");
                return null;
            }
            
            log.info("找到微信登录按钮，准备点击");
            wechatButton.first().click();
            page.waitForTimeout(1000);
            
            // 2. 等待二维码 iframe 加载
            log.info("等待微信二维码加载...");
            page.waitForTimeout(2000);
            
            // 3. 查找二维码 iframe
            Locator qrCodeIframe = page.locator("iframe[src*='weixin.gg']")
                    .or(page.locator("iframe[src*='wechat']"))
                    .or(page.locator("iframe#wxLogin"))
                    .or(page.locator("iframe"));
            
            if (qrCodeIframe.count() == 0) {
                log.error("未找到微信二维码 iframe");
                session.setLoginError("未找到微信二维码");
                return null;
            }
            
            log.info("找到微信二维码 iframe");
            
            // 4. 获取 iframe 内容
            Frame iframeFrame = qrCodeIframe.first().elementHandle().contentFrame();
            if (iframeFrame == null) {
                log.error("无法获取 iframe 内容");
                session.setLoginError("无法加载微信二维码");
                return null;
            }
            
            // 等待 iframe 内容加载
            iframeFrame.waitForLoadState();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 5. 在 iframe 中查找二维码图片
            String qrCodeImageSrc = (String) iframeFrame.evaluate("""
                () => {
                    const img = document.querySelector('img.js_qrcode_img') || 
                                document.querySelector('img.web_qrcode_img') ||
                                document.querySelector('img[src*="qrcode"]') ||
                                document.querySelector('img');
                    return img ? img.src : null;
                }
                """);
            
            if (qrCodeImageSrc == null || qrCodeImageSrc.isEmpty()) {
                log.error("未找到二维码图片");
                session.setLoginError("未找到二维码图片");
                return null;
            }
            
            // 6. 处理相对路径
            String qrCodeImageUrl = qrCodeImageSrc;
            if (qrCodeImageSrc.startsWith("/")) {
                String iframeUrl = iframeFrame.url();
                if (iframeUrl.contains("weixin.gg") || iframeUrl.contains("wechat")) {
                    int protocolIndex = iframeUrl.indexOf("://");
                    int pathIndex = iframeUrl.indexOf("/", protocolIndex + 3);
                    if (pathIndex > 0) {
                        String baseUrl = iframeUrl.substring(0, pathIndex);
                        qrCodeImageUrl = baseUrl + qrCodeImageSrc;
                    } else {
                        qrCodeImageUrl = iframeUrl.substring(0, iframeUrl.indexOf("/", protocolIndex + 3)) + qrCodeImageSrc;
                    }
                } else {
                    qrCodeImageUrl = "https://open.weixin.gg.com" + qrCodeImageSrc;
                }
            }
            
            log.info("获取到二维码图片 URL: {}", qrCodeImageUrl);
            
            // 7. 保存二维码信息到会话
            session.setQrCodeImageUrl(qrCodeImageUrl);
            session.setState(LoginSessionManager.LoginSessionState.WAITING_WECHAT_SCAN);
            loginSessionManager.saveSession(getProviderName(), session.getConversationId(), session);
            
            return qrCodeImageUrl;
            
        } catch (Exception e) {
            log.error("获取二维码图片 URL 时出错: {}", e.getMessage(), e);
            session.setLoginError("获取二维码出错: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public Map<String, Object> confirmQrCodeScanned(String accountId, String sessionId) {
        try {
            log.info("确认二维码已扫码，accountId: {}, sessionId: {}", accountId, sessionId);
            
            LoginSession session = adminLoginSessions.get(sessionId);
            if (session == null) {
                return Map.of("success", false, "error", "登录会话不存在");
            }
            
            Page page = session.getLoginPage();
            if (page == null || page.isClosed()) {
                return Map.of("success", false, "error", "登录页面已关闭");
            }
            
            // 创建 LoginSessionManager.LoginSession
            String conversationId = "admin-" + sessionId;
            LoginSessionManager.LoginSession loginSession = loginSessionManager.getOrCreateSession(
                getProviderName(), conversationId);
            loginSession.setLoginMethod(LoginSessionManager.LoginMethod.WECHAT_SCAN);
            loginSession.setState(LoginSessionManager.LoginSessionState.LOGGING_IN);
            
            // 检查登录状态
            boolean success = checkWechatLoginStatus(page, loginSession);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (success) {
                result.put("message", "登录成功");
            } else {
                result.put("message", "登录尚未完成，请确认已扫码");
            }
            
            return result;
        } catch (Exception e) {
            log.error("确认扫码失败: accountId={}, sessionId={}", accountId, sessionId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    @Override
    public Map<String, Object> verifyLogin(String accountId) {
        try {
            log.info("验证 DeepSeek 登录状态，accountId: {}", accountId);
            
            // 获取或创建页面
            Page page = getOrCreateLoginPage(accountId);
            page.navigate("https://chat.deepseek.com/");
            page.waitForLoadState();
            page.waitForTimeout(2000);
            
            // 检查登录状态
            boolean isLoggedIn = checkLoginStatus(page);
            if (!isLoggedIn) {
                return Map.of("success", false, "message", "未登录");
            }
            
            // 获取账号信息
            AccountInfo accountInfo = getCurrentAccountInfo(page);
            if (accountInfo == null || !accountInfo.isSuccess()) {
                return Map.of("success", false, "message", "无法获取账号信息");
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "登录验证成功");
            result.put("actualAccount", accountInfo.getAccountId());
            result.put("nickname", accountInfo.getAccountName());
            
            return result;
        } catch (Exception e) {
            log.error("验证登录失败: accountId={}", accountId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    @Override
    public Page getLoginPage(String accountId) {
        return getOrCreateLoginPage(accountId);
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("清理页面，共 {} 个", modelPages.size());
        modelPages.values().forEach(page -> {
            try { if (page != null && !page.isClosed()) page.close(); } catch (Exception e) { }
        });
        modelPages.clear();
        
        // 清理 admin 登录会话
        adminLoginSessions.values().forEach(session -> {
            if (session.getLoginPage() != null && !session.getLoginPage().isClosed()) {
                try {
                    session.getLoginPage().close();
                } catch (Exception e) {
                    log.warn("关闭登录页面失败: sessionId={}", session.getSessionId(), e);
                }
            }
        });
        adminLoginSessions.clear();
    }
}


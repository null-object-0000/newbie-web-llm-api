package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.LoginSessionManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.LoginInfo;
import site.newbie.web.llm.api.provider.command.CommandHandler;
import site.newbie.web.llm.api.provider.command.CommandParser;

import java.util.List;
import java.util.function.Function;

/**
 * LLM 提供者接口
 * 所有 LLM 提供者都需要实现此接口
 */
public interface LLMProvider {
    
    /**
     * 获取提供者名称
     */
    String getProviderName();
    
    /**
     * 获取该提供者支持的所有模型列表
     */
    List<String> getSupportedModels();
    
    /**
     * 检查是否支持指定的模型
     */
    default boolean supportsModel(String model) {
        return getSupportedModels().contains(model);
    }
    
    /**
     * 检查是否已登录
     * 此方法用于检测当前提供器的登录状态
     * @param page 浏览器页面，用于检查登录状态
     * @return true 如果已登录，false 如果未登录
     */
    boolean checkLoginStatus(Page page);
    
    /**
     * 获取登录信息
     * 此方法用于获取登录状态
     * @param page 浏览器页面，用于检查登录状态
     * @return LoginInfo 包含登录状态
     */
    default LoginInfo getLoginInfo(Page page) {
        boolean loggedIn = checkLoginStatus(page);
        if (!loggedIn) {
            return LoginInfo.notLoggedIn();
        }
        return LoginInfo.loggedIn();
    }
    
    /**
     * 获取当前登录的账号信息
     * 此方法用于从页面中提取当前登录用户的账号信息（账号名称、邮箱等）
     * @param page 浏览器页面
     * @return AccountInfo 包含账号名称和账号标识（如邮箱），如果未登录或无法获取则返回失败的 AccountInfo
     */
    default AccountInfo getCurrentAccountInfo(Page page) {
        // 默认实现：不支持获取账号信息
        return AccountInfo.failed("此提供器不支持获取账号信息");
    }
    
    /**
     * 处理登录流程
     * @param request 聊天请求（包含用户输入）
     * @param emitter SSE 发射器，用于发送流式数据
     * @param session 登录会话
     * @return true 如果登录成功，false 如果登录失败或需要更多输入
     */
    default boolean handleLogin(ChatCompletionRequest request, SseEmitter emitter, 
                                 LoginSessionManager.LoginSession session) {
        // 默认实现：不支持登录
        return false;
    }
    
    /**
     * 获取指令解析器
     * 每个 provider 应该提供自己的 CommandParser 实例（可能包含 provider 特定指令）
     * @return CommandParser 实例，如果 provider 不支持指令则返回 null
     */
    default CommandParser getCommandParser() {
        // 默认实现：返回一个只支持全局指令的 CommandParser
        return new CommandParser();
    }
    
    /**
     * 获取或创建页面
     * 用于指令处理时获取页面
     * @param request 聊天请求
     * @return 页面对象，如果无法创建则返回 null
     */
    default Page getOrCreatePage(ChatCompletionRequest request) {
        // 默认实现：不支持页面管理
        return null;
    }
    
    /**
     * 获取对话ID
     * 用于指令处理时获取对话ID
     * @param request 聊天请求
     * @return 对话ID，如果不存在则返回 null
     */
    default String getConversationId(ChatCompletionRequest request) {
        // 默认实现：从请求中获取
        return request.getConversationId();
    }
    
    /**
     * 判断是否是新对话
     * 用于指令处理时判断是否是新对话
     * @param request 聊天请求
     * @return true 如果是新对话，false 如果是已有对话
     */
    default boolean isNewConversation(ChatCompletionRequest request) {
        // 默认实现：根据 conversationId 判断
        String conversationId = getConversationId(request);
        return conversationId == null || conversationId.isEmpty() || conversationId.startsWith("login-");
    }
    
    /**
     * 获取指令执行成功后的回调
     * 用于处理 provider 特定的逻辑（如保存 conversationId）
     * @return CommandSuccessCallback 实例，如果不需要则返回 null
     */
    default CommandHandler.CommandSuccessCallback getCommandSuccessCallback() {
        // 默认实现：不需要成功回调
        return null;
    }
    
    /**
     * 处理流式聊天请求
     * 注意：此方法应该只处理非指令的普通聊天请求
     * 指令检查应该在 Controller 层统一处理
     * @param request 聊天请求（已确认不包含指令或指令已处理）
     * @param emitter SSE 发射器，用于发送流式数据
     */
    void streamChat(ChatCompletionRequest request, SseEmitter emitter);
}


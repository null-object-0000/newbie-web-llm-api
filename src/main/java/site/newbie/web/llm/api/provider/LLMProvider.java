package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.manager.LoginSessionManager;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.model.LoginInfo;

import java.util.List;

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
     * 处理流式聊天请求
     * @param request 聊天请求
     * @param emitter SSE 发射器，用于发送流式数据
     */
    void streamChat(ChatCompletionRequest request, SseEmitter emitter);
}


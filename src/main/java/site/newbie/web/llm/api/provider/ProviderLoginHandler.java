package site.newbie.web.llm.api.provider;

import com.microsoft.playwright.Page;

import java.util.Map;

/**
 * 提供器登录处理器接口
 * 用于在管理后台中实现不同提供器的登录逻辑
 */
public interface ProviderLoginHandler {
    
    /**
     * 获取提供器名称
     */
    String getProviderName();
    
    /**
     * 启动手动登录流程
     * 打开浏览器，让用户手动完成登录
     * @param accountId 账号ID
     * @return 登录会话信息
     */
    Map<String, Object> startManualLogin(String accountId);
    
    /**
     * 启动账号+密码登录流程
     * 自动填写账号和密码完成登录
     * @param accountId 账号ID
     * @param account 账号（邮箱或用户名）
     * @param password 密码
     * @return 登录结果
     */
    Map<String, Object> startAccountPasswordLogin(String accountId, String account, String password);
    
    /**
     * 启动二维码登录流程
     * 获取二维码并返回给前端
     * @param accountId 账号ID
     * @return 登录会话信息，包含二维码图片（base64格式）和 sessionId
     */
    Map<String, Object> startQrCodeLogin(String accountId);
    
    /**
     * 确认二维码已扫码
     * 检查登录状态
     * @param accountId 账号ID
     * @param sessionId 登录会话ID（如果有）
     * @return 登录结果
     */
    Map<String, Object> confirmQrCodeScanned(String accountId, String sessionId);
    
    /**
     * 验证登录状态
     * @param accountId 账号ID
     * @return 验证结果
     */
    Map<String, Object> verifyLogin(String accountId);
    
    /**
     * 获取登录页面
     * @param accountId 账号ID
     * @return 页面对象
     */
    Page getLoginPage(String accountId);
}


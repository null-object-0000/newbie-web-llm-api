package site.newbie.web.llm.api.provider.command;

import com.microsoft.playwright.Page;
import site.newbie.web.llm.api.provider.LLMProvider;

/**
 * 内置指令接口
 * 全局指令和 provider 特定指令都需要实现此接口
 */
public interface Command {
    /**
     * 执行指令
     * @param page Playwright 页面对象（可能为 null，对于不需要页面的指令如 help）
     * @param progressCallback 进度回调（可选，如果为 null 则不发送进度）
     * @param provider LLMProvider 实例（可能为 null，对于不需要 provider 的指令如 help）
     * @return 是否执行成功
     */
    boolean execute(Page page, ProgressCallback progressCallback, LLMProvider provider);
    
    /**
     * 获取指令名称
     */
    String getName();
    
    /**
     * 获取指令描述
     */
    default String getDescription() {
        return getName();
    }
    
    /**
     * 获取指令使用示例
     * @return 指令使用示例，如 "/command:参数 或 /command 参数"
     */
    default String getExample() {
        return "/" + getName();
    }
    
    /**
     * 判断指令是否需要页面
     * @return true 如果需要页面，false 如果不需要（如 help 指令）
     */
    default boolean requiresPage() {
        return true;
    }
    
    /**
     * 判断指令是否需要登录
     * @return true 如果需要登录，false 如果不需要（如 help 指令）
     */
    default boolean requiresLogin() {
        return true;
    }
    
    /**
     * 判断指令是否需要 provider
     * @return true 如果需要 provider，false 如果不需要（如 help 指令）
     */
    default boolean requiresProvider() {
        return false;
    }
    
    /**
     * 进度回调接口
     */
    @FunctionalInterface
    interface ProgressCallback {
        /**
         * 发送进度消息
         * @param message 进度消息
         */
        void onProgress(String message);
    }
}


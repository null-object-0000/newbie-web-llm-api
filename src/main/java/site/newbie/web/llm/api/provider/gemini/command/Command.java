package site.newbie.web.llm.api.provider.gemini.command;

import com.microsoft.playwright.Page;

/**
 * 内置指令接口
 */
public interface Command {
    /**
     * 执行指令
     * @param page Playwright 页面对象
     * @param progressCallback 进度回调（可选，如果为 null 则不发送进度）
     * @return 是否执行成功
     */
    boolean execute(Page page, ProgressCallback progressCallback);
    
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


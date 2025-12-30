package site.newbie.web.llm.api.config;

import java.util.Optional;

/**
 * API Key ScopedValue
 * 用于在当前请求作用域中存储和访问 API key 信息
 * 使用 ThreadLocal 作为 ScopedValue 的替代方案（因为 ScopedValue 需要特定的作用域绑定）
 */
public class ApiKeyScopedValue {
    
    /**
     * ThreadLocal 用于存储当前请求的 API key 上下文
     * 在 Spring MVC 中，每个请求都在独立的线程中处理，所以 ThreadLocal 是安全的
     */
    private static final ThreadLocal<ApiKeyContext> API_KEY_CONTEXT = new ThreadLocal<>();
    
    /**
     * 设置当前请求的 API key 上下文
     * @param context API key 上下文
     */
    public static void set(ApiKeyContext context) {
        API_KEY_CONTEXT.set(context);
    }
    
    /**
     * 获取当前请求的 API key
     * @return API key，如果不存在则返回 null
     */
    public static String getApiKey() {
        return Optional.ofNullable(API_KEY_CONTEXT.get())
                .map(ApiKeyContext::getApiKey)
                .orElse(null);
    }
    
    /**
     * 获取当前请求的 API key 上下文
     * @return ApiKeyContext，如果不存在则返回 null
     */
    public static ApiKeyContext getContext() {
        return API_KEY_CONTEXT.get();
    }
    
    /**
     * 检查当前 API key 是否支持指定的 provider
     * @param providerName provider 名称
     * @return true 如果支持，false 如果不支持或 API key 不存在
     */
    public static boolean supportsProvider(String providerName) {
        return Optional.ofNullable(API_KEY_CONTEXT.get())
                .map(ctx -> ctx.supportsProvider(providerName))
                .orElse(false);
    }
    
    /**
     * 清除当前线程的 API key 上下文
     * 应该在请求处理完成后调用，避免内存泄漏
     */
    public static void clear() {
        API_KEY_CONTEXT.remove();
    }
}


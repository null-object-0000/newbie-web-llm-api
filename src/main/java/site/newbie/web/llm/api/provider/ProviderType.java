package site.newbie.web.llm.api.provider;

/**
 * 提供器类型枚举
 */
public enum ProviderType {
    /**
     * Playwright 类：通过浏览器自动化（如 Gemini、OpenAI Web、DeepSeek Web）
     */
    PLAYWRIGHT,
    
    /**
     * API 转发类：通过正规的 API 转发（如 OpenAI API、Anthropic API）
     */
    API_FORWARD,
    
    /**
     * 逆向 IDE API 类：通过逆向的 IDE API（如 Antigravity）
     */
    REVERSE_API
}



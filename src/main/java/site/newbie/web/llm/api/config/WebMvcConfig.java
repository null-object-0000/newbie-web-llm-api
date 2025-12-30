package site.newbie.web.llm.api.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AdminAccessInterceptor adminAccessInterceptor;

    @Autowired
    private ApiKeyInterceptor apiKeyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 为管理后台路径添加 IP 访问限制
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(); // 不排除任何路径，所有 /admin/** 都需要检查

        // 为 OpenAPI 接口添加 API key 验证
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns(); // 不排除任何路径，所有 /v1/** 都需要 API key
    }
}


package site.newbie.web.llm.api.provider.command;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import site.newbie.web.llm.api.provider.LLMProvider;

/**
 * 全局登录指令
 * 用法: /login
 * 这是一个全局指令，通过 LLMProvider 接口检查登录状态
 */
@Slf4j
public class LoginCommand implements Command {
    
    public LoginCommand() {
    }
    
    @Override
    public String getName() {
        return "login";
    }
    
    @Override
    public String getDescription() {
        return "检查并引导登录";
    }
    
    @Override
    public String getExample() {
        return "/login";
    }
    
    @Override
    public boolean requiresPage() {
        return true; // login 指令需要页面
    }
    
    @Override
    public boolean requiresLogin() {
        return false; // login 指令本身是用来登录的，不需要先登录
    }
    
    @Override
    public boolean requiresProvider() {
        return true; // login 指令需要 provider 来检查登录状态
    }
    
    @Override
    public boolean execute(Page page, ProgressCallback progressCallback, LLMProvider provider) {
        try {
            log.info("执行 login 指令");
            
            if (progressCallback == null) {
                log.warn("login 指令需要进度回调");
                return false;
            }
            
            if (page == null || page.isClosed()) {
                progressCallback.onProgress("❌ 页面不可用");
                return false;
            }
            
            if (provider == null) {
                progressCallback.onProgress("❌ Provider 不可用");
                return false;
            }
            
            // 等待页面加载完成
            page.waitForLoadState();
            page.waitForTimeout(1000);
            
            // 使用 provider 的 checkLoginStatus 方法检查登录状态
            progressCallback.onProgress("正在检查登录状态...");
            
            boolean isLoggedIn = provider.checkLoginStatus(page);
            
            if (isLoggedIn) {
                progressCallback.onProgress("✅ 已登录");
                return true;
            }
            
            // 未登录，尝试打开登录页面
            progressCallback.onProgress("未登录，正在打开登录页面...");
            
            // 查找登录按钮（通用选择器）
            Locator loginButton = page.locator("a[aria-label='登录']")
                    .or(page.locator("a[aria-label='Sign in']"))
                    .or(page.locator("a[href*='ServiceLogin']"))
                    .or(page.locator("button:has-text('登录')"))
                    .or(page.locator("button:has-text('Sign in')"))
                    .or(page.locator("a:has-text('登录')"))
                    .or(page.locator("a:has-text('Sign in')"))
                    .or(page.locator("a[href*='signin']"))
                    .or(page.locator("button[aria-label*='登录']"))
                    .or(page.locator("button[aria-label*='Sign in']"));
            
            if (loginButton.count() > 0 && loginButton.first().isVisible()) {
                try {
                    loginButton.first().click();
                    progressCallback.onProgress("已点击登录按钮");
                    page.waitForTimeout(2000); // 等待登录页面加载
                    
                    // 等待登录完成（最多等待 60 秒）
                    progressCallback.onProgress("请在浏览器中完成登录...");
                    progressCallback.onProgress("（等待登录完成，最多 60 秒）");
                    
                    long startTime = System.currentTimeMillis();
                    long timeout = 60 * 1000; // 60秒超时
                    
                    while (System.currentTimeMillis() - startTime < timeout) {
                        page.waitForTimeout(2000); // 每2秒检查一次
                        
                        // 使用 provider 的 checkLoginStatus 方法重新检查登录状态
                        boolean loggedIn = provider.checkLoginStatus(page);
                        if (loggedIn) {
                            progressCallback.onProgress("✅ 登录成功！");
                            return true;
                        }
                        
                        // 检查是否还在登录页面
                        String url = page.url();
                        if (!url.contains("/signin") && !url.contains("/login") && !url.contains("/auth")) {
                            // 可能已经跳转，再次检查登录状态
                            page.waitForTimeout(1000);
                            loggedIn = provider.checkLoginStatus(page);
                            if (loggedIn) {
                                progressCallback.onProgress("✅ 登录成功！");
                                return true;
                            }
                        }
                    }
                    
                    // 超时
                    progressCallback.onProgress("⏱️ 等待登录超时，请手动检查登录状态");
                    return false;
                    
                } catch (Exception e) {
                    log.error("点击登录按钮时出错: {}", e.getMessage(), e);
                    progressCallback.onProgress("❌ 打开登录页面失败: " + e.getMessage());
                    return false;
                }
            } else {
                // 没有找到登录按钮，可能已经在登录页面
                String url = page.url();
                if (url.contains("/signin") || url.contains("/login") || url.contains("/auth")) {
                    progressCallback.onProgress("已在登录页面，请在浏览器中完成登录");
                    progressCallback.onProgress("（等待登录完成，最多 60 秒）");
                    
                    // 等待登录完成
                    long startTime = System.currentTimeMillis();
                    long timeout = 60 * 1000; // 60秒超时
                    
                    while (System.currentTimeMillis() - startTime < timeout) {
                        page.waitForTimeout(2000); // 每2秒检查一次
                        
                        // 使用 provider 的 checkLoginStatus 方法检查登录状态
                        boolean loggedIn = provider.checkLoginStatus(page);
                        if (loggedIn) {
                            progressCallback.onProgress("✅ 登录成功！");
                            return true;
                        }
                    }
                    
                    progressCallback.onProgress("⏱️ 等待登录超时，请手动检查登录状态");
                    return false;
                } else {
                    progressCallback.onProgress("⚠️ 未找到登录按钮，但当前页面可能不是登录页面");
                    progressCallback.onProgress("当前 URL: " + url);
                    return false;
                }
            }
            
        } catch (Exception e) {
            log.error("执行 login 指令时出错: {}", e.getMessage(), e);
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 执行登录指令时出错: " + e.getMessage());
            }
            return false;
        }
    }
}


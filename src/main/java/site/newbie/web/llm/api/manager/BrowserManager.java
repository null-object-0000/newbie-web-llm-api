package site.newbie.web.llm.api.manager;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BrowserManager {

    private Playwright playwright;

    // 每个提供器+账号有独立的 BrowserContext，避免并发冲突
    // Key 格式: "providerName" 或 "providerName:accountId"
    private final ConcurrentHashMap<String, BrowserContext> providerContexts = new ConcurrentHashMap<>();

    // 从配置文件读取配置
    @Value("${app.browser.headless:false}") // 默认为 false，方便你第一次扫码登录
    private boolean headless;

    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;

    @PostConstruct
    public void init() {
        log.info("正在启动 Playwright 引擎...");
        log.info("浏览器模式: {}, 基础数据存储路径: {}", headless ? "Headless (无头)" : "Headed (有界面)", userDataDir);

        // 设置系统属性，阻止 Playwright Java 自动下载浏览器
        // 我们已经通过 Dockerfile 手动安装了 Chromium
        String browsersPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (browsersPath != null && !browsersPath.isEmpty()) {
            System.setProperty("playwright.browsers.path", browsersPath);
            log.info("设置 Playwright 浏览器路径: {}", browsersPath);
        }

        // 设置系统属性跳过浏览器下载
        System.setProperty("playwright.cli.skip.install", "true");

        playwright = Playwright.create();

        log.info("Playwright 启动成功！各提供器的 BrowserContext 将按需创建。");
    }

    /**
     * 检查并确保 Playwright 实例有效，如果无效则重新创建
     */
    private synchronized void ensurePlaywrightValid() {
        if (playwright == null) {
            log.warn("Playwright 实例为 null，重新创建...");
            playwright = Playwright.create();
            log.info("Playwright 实例已重新创建");
            return;
        }

        // 尝试通过访问浏览器类型来检查 Playwright 是否有效
        // 如果连接已关闭，会抛出异常
        try {
            playwright.chromium();
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("Playwright connection closed") ||
                    errorMsg.contains("Connection closed") ||
                    errorMsg.contains("Target closed"))) {
                log.warn("Playwright 实例无效（连接已关闭），重新创建: {}", errorMsg);
                try {
                    playwright.close();
                } catch (Exception closeEx) {
                    // 忽略关闭错误
                    log.debug("关闭旧的 Playwright 实例时出错: {}", closeEx.getMessage());
                }
                playwright = Playwright.create();
                log.info("Playwright 实例已重新创建");
            } else {
                // 其他类型的错误，记录但不重新创建
                log.debug("检查 Playwright 实例时出现异常（非连接关闭）: {}", errorMsg);
            }
        }
    }

    /**
     * 获取或创建指定提供器的 BrowserContext（兼容旧接口，使用默认账号）
     * @deprecated 请使用 getOrCreateContext(String providerName, String accountId) 方法
     */
    @Deprecated
    public synchronized BrowserContext getOrCreateContext(String providerName) {
        return getOrCreateContext(providerName, null);
    }
    
    /**
     * 获取或创建指定提供器和账号的 BrowserContext
     * 每个提供器+账号有独立的浏览器上下文，避免并发冲突
     * 如果 context 已关闭会自动重新创建
     * @param providerName 提供器名称
     * @param accountId 账号ID，如果为 null 则使用默认账号（兼容旧代码）
     */
    public synchronized BrowserContext getOrCreateContext(String providerName, String accountId) {
        String contextKey = accountId != null && !accountId.isEmpty() 
                ? providerName + ":" + accountId 
                : providerName;
        BrowserContext existingContext = providerContexts.get(contextKey);

        // 检查现有 context 是否有效
        if (existingContext != null) {
            try {
                // 尝试访问 context 来检查是否还有效
                existingContext.pages();
                return existingContext;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isClosed = errorMsg != null && (
                        errorMsg.contains("Target page, context or browser has been closed") ||
                        errorMsg.contains("TargetClosedError") ||
                        errorMsg.contains("Browser closed") ||
                        errorMsg.contains("Context closed") ||
                        errorMsg.contains("Connection closed")
                );
                if (isClosed) {
                    log.warn("提供器 {} 账号 {} 的 BrowserContext 已关闭，将重新创建...", providerName, accountId);
                } else {
                    log.warn("提供器 {} 账号 {} 的 BrowserContext 访问失败，将重新创建: {}", providerName, accountId, errorMsg);
                }
                providerContexts.remove(contextKey);
            }
        }

        // 确保 Playwright 实例有效
        ensurePlaywrightValid();

        // 创建新的 context
        if (accountId != null && !accountId.isEmpty()) {
            log.info("为提供器 {} 账号 {} 创建独立的 BrowserContext...", providerName, accountId);
        } else {
            log.info("为提供器 {} 创建独立的 BrowserContext...", providerName);
        }

        // 每个提供器+账号有独立的用户数据目录
        String providerDataDir = accountId != null && !accountId.isEmpty()
                ? userDataDir + "/" + providerName + "/" + accountId
                : userDataDir + "/" + providerName;

        // Gemini 和 OpenAI 强制使用 headed 模式（有界面），其他提供器使用配置的模式
        boolean useHeadless = headless;
        if ("gemini".equals(providerName) || "openai".equals(providerName)) {
            useHeadless = false;
            log.info("提供器 {} 强制使用 Headed 模式（有界面）", providerName);
        }

        // 配置启动选项
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(useHeadless)
                .setViewportSize(1366, 768)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-setuid-sandbox"
                ))
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        // 重试机制：如果 Playwright 连接关闭，尝试重新创建 Playwright 实例
        int maxRetries = 2;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                BrowserContext context = playwright.chromium().launchPersistentContext(
                        Paths.get(providerDataDir), options);

                // 注入抗检测脚本
                context.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

                providerContexts.put(contextKey, context);
                if (accountId != null && !accountId.isEmpty()) {
                    log.info("提供器 {} 账号 {} 的 BrowserContext 创建成功，数据目录: {}", providerName, accountId, providerDataDir);
                } else {
                    log.info("提供器 {} 的 BrowserContext 创建成功，数据目录: {}", providerName, providerDataDir);
                }
                return context;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isConnectionClosed = errorMsg != null && (
                        errorMsg.contains("Playwright connection closed") ||
                        errorMsg.contains("Connection closed") ||
                        errorMsg.contains("Target page, context or browser has been closed") ||
                        errorMsg.contains("TargetClosedError") ||
                        errorMsg.contains("Browser closed") ||
                        errorMsg.contains("Context closed")
                );
                
                if (isConnectionClosed) {
                    log.warn("Playwright 连接或浏览器已关闭，尝试重新创建 Playwright 实例 (尝试 {}/{})", attempt + 1, maxRetries);
                    // 清理上下文缓存
                    providerContexts.remove(contextKey);
                    // 重新创建 Playwright 实例
                    try {
                        if (playwright != null) {
                            playwright.close();
                        }
                    } catch (Exception closeEx) {
                        // 忽略关闭错误
                        log.debug("关闭旧的 Playwright 实例时出错: {}", closeEx.getMessage());
                    }
                    playwright = Playwright.create();
                    log.info("Playwright 实例已重新创建，重试创建 BrowserContext...");

                    if (attempt == maxRetries - 1) {
                        // 最后一次尝试失败，抛出异常
                        log.error("创建 BrowserContext 失败，已重试 {} 次", maxRetries);
                        throw new RuntimeException("无法创建 BrowserContext，浏览器连接问题: " + errorMsg, e);
                    }
                } else {
                    // 其他错误直接抛出
                    throw new RuntimeException("创建 BrowserContext 失败: " + (errorMsg != null ? errorMsg : e.getClass().getSimpleName()), e);
                }
            }
        }

        // 理论上不会到达这里，但为了编译需要
        throw new RuntimeException("创建 BrowserContext 失败");
    }

    /**
     * 强制清理指定提供器和账号的 BrowserContext
     * 用于在浏览器关闭后清理无效的上下文引用
     * @param providerName 提供器名称
     * @param accountId 账号ID，如果为 null 则使用默认账号
     */
    public synchronized void clearContext(String providerName, String accountId) {
        String contextKey = accountId != null && !accountId.isEmpty() 
                ? providerName + ":" + accountId 
                : providerName;
        BrowserContext context = providerContexts.remove(contextKey);
        if (context != null) {
            try {
                context.close();
                log.info("已清理提供器 {} 账号 {} 的 BrowserContext", providerName, accountId);
            } catch (Exception e) {
                log.debug("清理 BrowserContext 时出错（可能已关闭）: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 获取一个新的页面用于聊天（兼容旧接口，使用默认上下文）
     *
     * @deprecated 请使用 newPage(String providerName) 方法
     */
    @Deprecated
    public Page newPage() {
        return newPage("default");
    }

    /**
     * 为指定提供器获取一个新的页面（兼容旧接口，使用默认账号）
     * @deprecated 请使用 newPage(String providerName, String accountId) 方法
     */
    @Deprecated
    public synchronized Page newPage(String providerName) {
        return newPage(providerName, null);
    }
    
    /**
     * 为指定提供器和账号获取一个新的页面
     * 如果 context 已关闭会自动重新创建并重试
     */
    public synchronized Page newPage(String providerName, String accountId) {
        String contextKey = accountId != null && !accountId.isEmpty() 
                ? providerName + ":" + accountId 
                : providerName;
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                BrowserContext context = getOrCreateContext(providerName, accountId);
                return context.newPage();
            } catch (Exception e) {
                // context 可能已关闭，移除并重新创建
                log.warn("提供器 {} 账号 {} 创建页面失败 (尝试 {}/{}): {}", providerName, accountId, attempt + 1, maxRetries, e.getMessage());
                providerContexts.remove(contextKey);

                if (attempt < maxRetries - 1) {
                    // 等待一小段时间后重试
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("创建页面时被中断", ie);
                    }
                } else {
                    // 最后一次尝试失败，抛出异常
                    throw new RuntimeException("创建页面失败，已重试 " + maxRetries + " 次", e);
                }
            }
        }
        // 理论上不会到达这里
        throw new RuntimeException("创建页面失败");
    }

    /**
     * 获取所有打开的页面（兼容旧接口）
     *
     * @deprecated 请使用 getAllPages(String providerName) 方法
     */
    @Deprecated
    public List<Page> getAllPages() {
        List<Page> allPages = new ArrayList<>();
        for (BrowserContext context : providerContexts.values()) {
            allPages.addAll(context.pages());
        }
        return allPages;
    }

    /**
     * 获取指定提供器的所有页面（兼容旧接口，使用默认账号）
     * @deprecated 请使用 getAllPages(String providerName, String accountId) 方法
     */
    @Deprecated
    public List<Page> getAllPages(String providerName) {
        return getAllPages(providerName, null);
    }
    
    /**
     * 获取指定提供器和账号的所有页面
     */
    public List<Page> getAllPages(String providerName, String accountId) {
        String contextKey = accountId != null && !accountId.isEmpty() 
                ? providerName + ":" + accountId 
                : providerName;
        BrowserContext context = providerContexts.get(contextKey);
        if (context == null) {
            return List.of();
        }
        try {
            return context.pages();
        } catch (Exception e) {
            // context 已关闭
            log.warn("获取提供器 {} 账号 {} 页面时出错，context 可能已关闭: {}", providerName, accountId, e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("正在关闭 Playwright...");

        // 关闭所有提供器的 BrowserContext
        for (var entry : providerContexts.entrySet()) {
            try {
                log.info("关闭提供器 {} 的 BrowserContext...", entry.getKey());
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("关闭提供器 {} 的 BrowserContext 时出错: {}", entry.getKey(), e.getMessage());
            }
        }
        providerContexts.clear();

        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright 已关闭");
    }
}
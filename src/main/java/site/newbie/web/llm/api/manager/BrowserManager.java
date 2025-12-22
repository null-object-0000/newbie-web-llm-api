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
    
    // 每个提供器有独立的 BrowserContext，避免并发冲突
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
     * 获取或创建指定提供器的 BrowserContext
     * 每个提供器有独立的浏览器上下文，避免并发冲突
     * 如果 context 已关闭会自动重新创建
     */
    public synchronized BrowserContext getOrCreateContext(String providerName) {
        BrowserContext existingContext = providerContexts.get(providerName);
        
        // 检查现有 context 是否有效
        if (existingContext != null) {
            try {
                // 尝试访问 context 来检查是否还有效
                existingContext.pages();
                return existingContext;
            } catch (Exception e) {
                log.warn("提供器 {} 的 BrowserContext 已关闭，将重新创建...", providerName);
                providerContexts.remove(providerName);
            }
        }
        
        // 创建新的 context
        log.info("为提供器 {} 创建独立的 BrowserContext...", providerName);
        
        // 每个提供器有独立的用户数据目录
        String providerDataDir = userDataDir + "/" + providerName;
        
        // 配置启动选项
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setViewportSize(1920, 1080)  // 使用常见的桌面浏览器窗口大小
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-setuid-sandbox"
                ))
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        BrowserContext context = playwright.chromium().launchPersistentContext(
                Paths.get(providerDataDir), options);
        
        // 注入抗检测脚本
        context.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
        
        providerContexts.put(providerName, context);
        log.info("提供器 {} 的 BrowserContext 创建成功，数据目录: {}", providerName, providerDataDir);
        return context;
    }

    /**
     * 获取一个新的页面用于聊天（兼容旧接口，使用默认上下文）
     * @deprecated 请使用 newPage(String providerName) 方法
     */
    @Deprecated
    public Page newPage() {
        return newPage("default");
    }
    
    /**
     * 为指定提供器获取一个新的页面
     * 如果 context 已关闭会自动重新创建并重试
     */
    public synchronized Page newPage(String providerName) {
        BrowserContext context = getOrCreateContext(providerName);
        try {
            return context.newPage();
        } catch (Exception e) {
            // context 可能已关闭，移除并重新创建
            log.warn("提供器 {} 创建页面失败，将重新创建 BrowserContext: {}", providerName, e.getMessage());
            providerContexts.remove(providerName);
            
            // 重新获取 context 并创建页面
            context = getOrCreateContext(providerName);
            return context.newPage();
        }
    }

    /**
     * 获取所有打开的页面（兼容旧接口）
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
     * 获取指定提供器的所有页面
     */
    public List<Page> getAllPages(String providerName) {
        BrowserContext context = providerContexts.get(providerName);
        if (context == null) {
            return List.of();
        }
        try {
            return context.pages();
        } catch (Exception e) {
            // context 已关闭
            log.warn("获取提供器 {} 页面时出错，context 可能已关闭: {}", providerName, e.getMessage());
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
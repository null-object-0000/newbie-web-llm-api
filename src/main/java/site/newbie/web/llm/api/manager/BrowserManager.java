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
import java.util.List;

@Slf4j
@Service
public class BrowserManager {

    private Playwright playwright;
    private BrowserContext browserContext;

    // 从配置文件读取配置
    @Value("${app.browser.headless:false}") // 默认为 false，方便你第一次扫码登录
    private boolean headless;

    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;

    @PostConstruct
    public void init() {
        log.info("正在启动 Playwright 引擎...");
        log.info("浏览器模式: {}, 数据存储路径: {}", headless ? "Headless (无头)" : "Headed (有界面)", userDataDir);

        playwright = Playwright.create();

        // 配置启动选项
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setViewportSize(1280, 720) // 设置一个常见的屏幕分辨率
                // 关键：添加抗检测参数
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled", // 禁用自动化控制标识
                        "--no-sandbox",
                        "--disable-setuid-sandbox"
                ))
                // 设置真实的用户代理 (User-Agent)，防止被一眼识别为爬虫
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        // 使用 PersistentContext (持久化上下文)
        // 这样你的登录 Cookie、LocalStorage 都会保存在 userDataDir 目录里
        // 下次启动程序，不需要重新登录
        browserContext = playwright.chromium().launchPersistentContext(Paths.get(userDataDir), options);

        // 注入一段 JS 脚本，进一步抹除 WebDriver 特征 (防检测的关键！)
        browserContext.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

        log.info("Playwright 启动成功！");
    }

    /**
     * 获取一个新的页面用于聊天
     * 注意：每个请求应该调用一次这个方法，用完后必须 page.close()
     */
    public Page newPage() {
        if (browserContext == null) {
            throw new IllegalStateException("Browser is not initialized");
        }
        return browserContext.newPage();
    }

    @PreDestroy
    public void destroy() {
        log.info("正在关闭 Playwright...");
        if (browserContext != null) {
            browserContext.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright 已关闭");
    }
}
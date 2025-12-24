package site.newbie.web.llm.api.provider.gemini.command;

import com.microsoft.playwright.Locator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;

/**
 * 添加本地文件指令
 * 用法: /attach-local:文件路径 或 /attach-local 文件路径
 */
@Slf4j
public class AttachLocalFileCommand implements Command {
    private final String filePath;
    
    public AttachLocalFileCommand(String filePath) {
        this.filePath = filePath;
    }
    
    @Override
    public String getName() {
        return "attach-local";
    }
    
    @Override
    public String getDescription() {
        return "添加本地文件: " + filePath;
    }
    
    @Override
    public boolean execute(com.microsoft.playwright.Page page, ProgressCallback progressCallback) {
        try {
            log.info("执行指令: 添加本地文件 -> {}", filePath);
            if (progressCallback != null) {
                progressCallback.onProgress("开始添加本地文件: " + filePath);
            }
            
            // 1. 点击 "+" 号按钮
            Locator uploadButton = page.locator(".uploader-button-container button.mdc-icon-button");
            if (uploadButton.count() == 0) {
                log.warn("未找到上传按钮");
                return false;
            }
            uploadButton.first().click();
            log.debug("已点击上传按钮");
            if (progressCallback != null) {
                progressCallback.onProgress("正在打开上传菜单...");
            }
            
            // 2. 等待菜单出现并点击"上传文件"
            String menuSelector = ".upload-file-card-container button .menu-text:text('上传文件')";
            Locator menuItem = page.locator(menuSelector)
                    .or(page.locator(".upload-file-card-container button:has-text('上传文件')"));
            
            if (menuItem.count() == 0) {
                // 尝试英文菜单
                String englishMenuSelector = ".upload-file-card-container button .menu-text:text('Upload file')";
                menuItem = page.locator(englishMenuSelector)
                        .or(page.locator(".upload-file-card-container button:has-text('Upload file')"));
            }
            
            if (menuItem.count() == 0) {
                log.warn("未找到'上传文件'菜单项");
                return false;
            }
            
            // 3. 监听文件选择器并设置文件
            final Locator finalMenuItem = menuItem; // 创建 final 引用
            page.waitForFileChooser(() -> {
                finalMenuItem.first().click();
                log.debug("已点击'上传文件'菜单项");
            }).setFiles(Paths.get(filePath));
            
            log.info("已选择文件: {}", filePath);
            if (progressCallback != null) {
                progressCallback.onProgress("已选择文件，正在上传...");
            }
            
            // 4. 等待文件上传完成
            Locator loadingIndicator = page.locator("uploader-file-preview div.loading");
            if (loadingIndicator.count() > 0) {
                log.debug("等待文件上传完成...");
                // 等待加载指示器消失
                page.waitForTimeout(500);
                long startTime = System.currentTimeMillis();
                long timeout = 60 * 1000; // 60秒超时
                
                while (System.currentTimeMillis() - startTime < timeout) {
                    if (loadingIndicator.count() == 0 || !loadingIndicator.first().isVisible()) {
                        break;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("等待文件上传时被中断");
                        return false;
                    }
                }
                
                log.info("文件上传完成: {}", filePath);
            }
            
            if (progressCallback != null) {
                progressCallback.onProgress("✅ 文件上传完成");
            }
            return true;
            
        } catch (Exception e) {
            log.error("执行 attach-local 指令时出错: {}", e.getMessage(), e);
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 执行失败: " + e.getMessage());
            }
            return false;
        }
    }
}


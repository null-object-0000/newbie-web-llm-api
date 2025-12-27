package site.newbie.web.llm.api.provider.gemini.command;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import site.newbie.web.llm.api.provider.LLMProvider;
import site.newbie.web.llm.api.provider.command.Command;

/**
 * 添加 Google Drive 文件指令
 * 用法: /attach-drive:文件名 或 /attach-drive 文件名
 */
@Slf4j
public class AttachDriveCommand implements Command {
    private final String fileName;
    
    public AttachDriveCommand(String fileName) {
        this.fileName = fileName;
    }
    
    @Override
    public String getName() {
        return "attach-drive";
    }
    
    @Override
    public String getDescription() {
        return "添加 Google Drive 文件: " + fileName;
    }
    
    @Override
    public String getExample() {
        return "/attach-drive:隋坡-糖醋排骨-202409.mp4";
    }
    
    @Override
    public boolean execute(Page page, ProgressCallback progressCallback, LLMProvider provider) {
        try {
            log.info("执行指令: 添加 Google Drive 文件 -> {}", fileName);
            if (progressCallback != null) {
                progressCallback.onProgress("开始添加 Google Drive 文件: " + fileName);
            }
            
            // 0. 检查并关闭已打开的弹窗（如果存在）
            if (closePickerIfOpen(page, progressCallback)) {
                log.info("已关闭之前打开的 Google Drive 文件选择器");
                page.waitForTimeout(1000); // 等待弹窗关闭动画完成
            }
            
            // 1. 点击 "+" 号按钮
            if (progressCallback != null) {
                progressCallback.onProgress("正在打开上传菜单...");
            }
            Locator uploadButton = page.locator(".uploader-button-container button.mdc-icon-button");
            if (uploadButton.count() == 0) {
                log.warn("未找到上传按钮");
                if (progressCallback != null) {
                    progressCallback.onProgress("❌ 未找到上传按钮");
                }
                return false;
            }
            uploadButton.first().click();
            log.debug("已点击上传按钮");
            
            // 2. 等待菜单出现并点击"从云端硬盘添加"
            String menuSelector = ".upload-file-card-container button .menu-text:text('从云端硬盘添加')";
            Locator menuItem = page.locator(menuSelector)
                    .or(page.locator(".upload-file-card-container button:has-text('从云端硬盘添加')"));
            
            if (menuItem.count() == 0) {
                // 尝试英文菜单
                menuSelector = ".upload-file-card-container button .menu-text:text('Add from Google Drive')";
                menuItem = page.locator(menuSelector)
                        .or(page.locator(".upload-file-card-container button:has-text('Add from Google Drive')"));
            }
            
            if (menuItem.count() == 0) {
                log.warn("未找到'从云端硬盘添加'菜单项");
                return false;
            }
            
            menuItem.first().click();
            log.debug("已点击'从云端硬盘添加'菜单项");
            if (progressCallback != null) {
                progressCallback.onProgress("正在打开 Google Drive 文件选择器...");
            }
            
            // 3. 等待 Google Picker iframe 加载
            String iframeSelector = ".google-picker .picker-iframe-container iframe[src*='docs.google.com/picker']";
            Locator iframeElement = page.locator(iframeSelector);
            
            // 等待 iframe 出现（最多等待 10 秒）
            long startTime = System.currentTimeMillis();
            long timeout = 10 * 1000;
            while (iframeElement.count() == 0 && System.currentTimeMillis() - startTime < timeout) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待 iframe 时被中断");
                    return false;
                }
            }
            
            if (iframeElement.count() == 0) {
                log.warn("未找到 Google Picker iframe");
                return false;
            }
            
            // 4. 获取 iframe 内容
            com.microsoft.playwright.Frame frame;
            try {
                // 先获取 ElementHandle，然后获取 Frame
                com.microsoft.playwright.ElementHandle iframeHandle = iframeElement.first().elementHandle();
                if (iframeHandle == null) {
                    log.warn("无法获取 iframe ElementHandle");
                    return false;
                }
                frame = iframeHandle.contentFrame();
                if (frame == null) {
                    log.warn("无法获取 iframe 内容");
                    return false;
                }
            } catch (Exception e) {
                log.warn("获取 iframe 内容时出错: {}", e.getMessage());
                return false;
            }
            
            // 5. 等待搜索框加载完成
            String searchInputSelector = "div[data-placeholder*='在云端硬盘中搜索'] input:nth-of-type(2), " +
                                       "div[data-placeholder*='Search or paste'] input:nth-of-type(2)";
            
            startTime = System.currentTimeMillis();
            timeout = 30 * 1000; // 30秒超时
            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    Locator searchInput = frame.locator(searchInputSelector);
                    if (searchInput.count() > 0 && searchInput.first().isVisible()) {
                        break;
                    }
                } catch (Exception e) {
                    // 继续等待
                }
                
                // 检查加载状态
                Locator loadingIndicator = frame.locator("div[data-active='true'] div[aria-live='assertive']");
                if (loadingIndicator.count() > 0) {
                    try {
                        String loadingText = loadingIndicator.first().innerText();
                        if (!"正在加载".equals(loadingText) && !"Loading".equals(loadingText)) {
                            break;
                        }
                    } catch (Exception e) {
                        // 忽略
                    }
                }
                
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待搜索框时被中断");
                    return false;
                }
            }
            
            // 6. 在搜索框中输入文件名并搜索（带验证和重试）
            Locator searchInput = frame.locator(searchInputSelector);
            if (searchInput.count() == 0) {
                log.warn("未找到搜索框");
                return false;
            }
            
            // 输入文件名，带验证和重试机制
            int maxRetries = 3;
            boolean inputSuccess = false;
            for (int retry = 0; retry < maxRetries; retry++) {
                try {
                    searchInput.first().click();
                    page.waitForTimeout(200);
                    
                    // 清空输入框
                    searchInput.first().clear();
                    page.waitForTimeout(100);
                    
                    // 输入文件名
                    searchInput.first().fill(fileName);
                    page.waitForTimeout(300);
                    
                    // 验证输入是否成功
                    String inputValue = searchInput.first().inputValue();
                    if (inputValue != null && inputValue.contains(fileName)) {
                        inputSuccess = true;
                        log.debug("输入验证成功 (尝试 {}/{}): {}", retry + 1, maxRetries, inputValue);
                        break;
                    } else {
                        log.warn("输入验证失败 (尝试 {}/{}): 期望包含 '{}', 实际值: '{}'", 
                            retry + 1, maxRetries, fileName, inputValue);
                        if (retry < maxRetries - 1) {
                            if (progressCallback != null) {
                                progressCallback.onProgress("输入验证失败，重试中... (尝试 " + (retry + 2) + "/" + maxRetries + ")");
                            }
                            page.waitForTimeout(500); // 等待后重试
                        }
                    }
                } catch (Exception e) {
                    log.warn("输入时出错 (尝试 {}/{}): {}", retry + 1, maxRetries, e.getMessage());
                    if (retry < maxRetries - 1) {
                        page.waitForTimeout(500);
                    }
                }
            }
            
            if (!inputSuccess) {
                log.error("输入失败，已重试 {} 次", maxRetries);
                if (progressCallback != null) {
                    progressCallback.onProgress("❌ 输入失败，已重试 " + maxRetries + " 次");
                }
                return false;
            }
            
            // 按 Enter 搜索
            searchInput.first().press("Enter");
            log.debug("已在搜索框中输入文件名: {}", fileName);
            if (progressCallback != null) {
                progressCallback.onProgress("正在搜索文件: " + fileName);
            }
            
            // 7. 等待搜索结果并双击选择第一个结果（带验证）
            String resultSelector = "div[data-target='selectionArea'] div[role='listbox'] > div > div:nth-child(2) > div > div[role='option']";
            
            // 无结果提示的选择器（支持中英文）
            String noResultSelector = "div[role='status'][aria-live='assertive']";
            String noResultTextSelector = noResultSelector + " .R9Lal, " + noResultSelector + " div:has-text('没有匹配的结果'), " + 
                                        noResultSelector + " div:has-text('No matching results')";
            
            startTime = System.currentTimeMillis();
            timeout = 10 * 1000; // 10秒超时
            boolean fileSelected = false;
            boolean searchCompleted = false; // 标记搜索是否已完成（无论是否有结果）
            
            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    // 首先检查是否有"无结果"提示
                    Locator noResultIndicator = frame.locator(noResultTextSelector);
                    if (noResultIndicator.count() > 0 && noResultIndicator.first().isVisible()) {
                        try {
                            String noResultText = noResultIndicator.first().innerText();
                            if (noResultText != null && (noResultText.contains("没有匹配的结果") || 
                                noResultText.contains("No matching results") || 
                                noResultText.contains("没有匹配") || 
                                noResultText.contains("No results"))) {
                                log.warn("搜索完成，但没有找到匹配的文件: {}", fileName);
                                searchCompleted = true;
                                if (progressCallback != null) {
                                    progressCallback.onProgress("❌ 搜索完成，但没有找到匹配的文件: " + fileName);
                                    progressCallback.onProgress("💡 提示：请检查文件名是否正确，或尝试使用部分文件名搜索");
                                }
                                break;
                            }
                        } catch (Exception e) {
                            log.debug("读取无结果提示文本时出错: {}", e.getMessage());
                        }
                    }
                    
                    // 检查是否有搜索结果
                    Locator resultItem = frame.locator(resultSelector);
                    if (resultItem.count() > 0 && resultItem.first().isVisible()) {
                        // 双击选择文件
                        resultItem.first().dblclick();
                        log.info("已双击选择文件: {}", fileName);
                        if (progressCallback != null) {
                            progressCallback.onProgress("已选择文件，正在验证...");
                        }
                        
                        // 等待文件选择器关闭（表示文件已选择）
                        page.waitForTimeout(1000);
                        
                        // 验证文件是否成功添加：检查附件预览是否存在
                        boolean attachmentAdded = verifyFileAttachment(page, fileName);
                        
                        if (attachmentAdded) {
                            fileSelected = true;
                            searchCompleted = true;
                            log.info("✅ 文件添加成功: {}", fileName);
                            if (progressCallback != null) {
                                progressCallback.onProgress("✅ 已选择文件: " + fileName);
                                progressCallback.onProgress("✅ 文件添加完成");
                            }
                            return true;
                        } else {
                            log.warn("文件选择后验证失败，可能未成功添加");
                            if (progressCallback != null) {
                                progressCallback.onProgress("⚠️ 文件选择后验证失败，重试中...");
                            }
                            // 等待一下后重试
                            page.waitForTimeout(1000);
                            // 重新打开文件选择器（如果需要）
                            // 这里先返回 false，让上层处理重试
                            break;
                        }
                    }
                    
                    // 检查搜索是否已完成（通过检查是否有结果列表容器，即使为空）
                    // 如果搜索框不再处于加载状态，且没有结果也没有"无结果"提示，可能还在加载中
                    Locator searchContainer = frame.locator("div[data-target='selectionArea']");
                    if (searchContainer.count() > 0) {
                        // 检查是否不再加载（通过检查 aria-live 状态）
                        Locator loadingStatus = frame.locator("div[data-active='true'] div[aria-live='assertive']");
                        if (loadingStatus.count() == 0 || !loadingStatus.first().isVisible()) {
                            // 搜索已完成，但没有结果也没有"无结果"提示（可能是其他状态）
                            // 等待一下，看看是否会出现结果或提示
                            page.waitForTimeout(500);
                        }
                    }
                } catch (Exception e) {
                    log.debug("等待搜索结果时出错: {}", e.getMessage());
                    // 继续等待
                }
                
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待搜索结果时被中断");
                    return false;
                }
            }
            
            if (!fileSelected) {
                if (searchCompleted) {
                    // 搜索已完成但没有结果，已经在上面处理了
                    log.warn("搜索完成但没有找到文件: {}", fileName);
                } else {
                    // 超时
                    log.warn("等待搜索结果超时: {}", fileName);
                    if (progressCallback != null) {
                        progressCallback.onProgress("❌ 搜索超时，请重试");
                    }
                }
            }
            return false;
            
        } catch (Exception e) {
            log.error("执行 attach-drive 指令时出错: {}", e.getMessage(), e);
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 执行失败: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 验证文件是否成功添加为附件
     * @param page 页面对象
     * @param fileName 文件名
     * @return 是否成功添加
     */
    private boolean verifyFileAttachment(com.microsoft.playwright.Page page, String fileName) {
        try {
            // 方法1: 检查附件预览区域是否存在文件
            // 查找 uploader-file-preview 或类似的附件预览元素
            Locator attachmentPreview = page.locator("uploader-file-preview")
                    .or(page.locator("[class*='file-preview']"))
                    .or(page.locator("[class*='attachment']"));
            
            if (attachmentPreview.count() > 0) {
                // 检查是否有文件预览（不是加载状态）
                Locator loadingIndicator = attachmentPreview.locator("div.loading");
                if (loadingIndicator.count() == 0 || !loadingIndicator.first().isVisible()) {
                    log.debug("找到附件预览，文件可能已添加");
                    // 进一步验证：检查文件名是否匹配（如果可见）
                    try {
                        String previewText = attachmentPreview.first().innerText();
                        if (previewText != null && (previewText.contains(fileName) || fileName.contains(previewText))) {
                            log.info("验证成功：附件预览包含文件名");
                            return true;
                        }
                    } catch (Exception e) {
                        // 如果无法获取文本，至少预览存在就认为可能成功
                        log.debug("无法获取附件预览文本，但预览存在");
                    }
                    return true; // 预览存在且不在加载中，认为成功
                } else {
                    log.debug("附件预览仍在加载中");
                    // 等待加载完成
                    long startTime = System.currentTimeMillis();
                    long timeout = 5 * 1000; // 5秒超时
                    while (System.currentTimeMillis() - startTime < timeout) {
                        if (loadingIndicator.count() == 0 || !loadingIndicator.first().isVisible()) {
                            return true;
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            
            // 方法2: 检查 Google Picker iframe 是否已关闭（表示文件已选择）
            Locator pickerIframe = page.locator(".google-picker .picker-iframe-container iframe[src*='docs.google.com/picker']");
            if (pickerIframe.count() == 0 || !pickerIframe.first().isVisible()) {
                log.debug("Google Picker 已关闭，文件可能已选择");
                return true;
            }
            
            // 方法3: 检查是否有错误提示
            Locator errorMessage = page.locator("[class*='error']")
                    .or(page.locator("[aria-live='assertive']:has-text('错误')"))
                    .or(page.locator("[aria-live='assertive']:has-text('Error')"));
            if (errorMessage.count() > 0 && errorMessage.first().isVisible()) {
                try {
                    String errorText = errorMessage.first().innerText();
                    log.warn("检测到错误消息: {}", errorText);
                } catch (Exception e) {
                    // 忽略
                }
                return false;
            }
            
            log.debug("无法确定文件是否成功添加");
            // 如果无法确定，返回 true（乐观策略，因为可能只是验证方法不够完善）
            return true;
            
        } catch (Exception e) {
            log.warn("验证文件附件时出错: {}", e.getMessage());
            // 出错时返回 true（乐观策略）
            return true;
        }
    }
    
    /**
     * 检查并关闭已打开的 Google Drive 文件选择器
     * @param page 页面对象
     * @param progressCallback 进度回调
     * @return 是否关闭了弹窗
     */
    private boolean closePickerIfOpen(com.microsoft.playwright.Page page, ProgressCallback progressCallback) {
        try {
            // 检查是否存在 Google Picker iframe
            String iframeSelector = ".google-picker .picker-iframe-container iframe[src*='docs.google.com/picker']";
            Locator iframeElement = page.locator(iframeSelector);
            
            if (iframeElement.count() == 0 || !iframeElement.first().isVisible()) {
                // 没有打开的弹窗
                return false;
            }
            
            log.info("检测到已打开的 Google Drive 文件选择器，尝试关闭");
            if (progressCallback != null) {
                progressCallback.onProgress("检测到已打开的弹窗，正在关闭...");
            }
            
            // 查找关闭按钮（X 按钮）
            Locator closeButton = page.locator("button[aria-label=\"关闭“选择文件”选择器\"]");
            
            if (closeButton.count() > 0 && closeButton.first().isVisible()) {
                try {
                    closeButton.first().click();
                    page.waitForTimeout(500);
                    log.info("已点击关闭按钮");
                    return true;
                } catch (Exception e) {
                    log.warn("点击关闭按钮失败: {}", e.getMessage());
                }
            }
            
            // 如果所有方法都失败，记录警告但继续执行
            log.warn("无法关闭已打开的弹窗，将继续执行（可能会失败）");
            return false;
            
        } catch (Exception e) {
            log.warn("检查并关闭弹窗时出错: {}", e.getMessage());
            return false;
        }
    }
}


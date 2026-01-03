package site.newbie.web.llm.api.controller;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.newbie.web.llm.api.manager.BrowserManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 图片静态资源控制器
 * 提供 Gemini 生成的图片访问服务
 */
@Slf4j
@RestController
@RequestMapping("/api/images")
@CrossOrigin(origins = "*")
public class ImageController {
    
    private static final String IMAGES_SUBDIR = "gemini-images";
    private static final String IMAGE_URL_MAPPING_FILE = "image-url-mapping.json";
    
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    @Autowired
    private BrowserManager browserManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 获取图片资源
     * @param filename 文件名
     * @return 图片资源
     */
    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) {
        try {
            // 构建文件路径
            Path imagePath = Paths.get(userDataDir, IMAGES_SUBDIR, filename);
            File imageFile = imagePath.toFile();
            
            // 检查文件是否存在
            if (!imageFile.exists() || !imageFile.isFile()) {
                log.warn("图片文件不存在: {}", imagePath.toAbsolutePath());
                return ResponseEntity.notFound().build();
            }
            
            // 检查文件是否在允许的目录内（安全措施）
            Path imagesDir = Paths.get(userDataDir, IMAGES_SUBDIR).toAbsolutePath().normalize();
            Path requestedPath = imagePath.toAbsolutePath().normalize();
            if (!requestedPath.startsWith(imagesDir)) {
                log.warn("尝试访问不允许的路径: {}", requestedPath);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // 读取文件
            Resource resource = new FileSystemResource(imageFile);
            
            // 根据文件扩展名确定 Content-Type
            String contentType = determineContentType(filename);
            
            // 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentLength(imageFile.length());
            // 允许跨域访问
            headers.setAccessControlAllowOrigin("*");
            
            log.debug("返回图片: {} ({} bytes, {})", filename, imageFile.length(), contentType);
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("获取图片时出错: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 下载原始尺寸的图片
     * 根据文件名从映射文件中查找原始URL，然后从Gemini重新下载
     * @param filename 原始文件名
     * @return 原始尺寸的图片资源
     */
    @GetMapping("/download-original/{filename:.+}")
    public ResponseEntity<Resource> downloadOriginalImage(@PathVariable String filename) {
        try {
            log.info("请求下载原始图片: {}", filename);
            
            // 从映射文件中查找原始URL
            String originalImageUrl = getOriginalImageUrl(filename);
            if (originalImageUrl == null || originalImageUrl.isEmpty()) {
                log.warn("未找到图片的原始URL映射: {}", filename);
                return ResponseEntity.notFound().build();
            }
            
            log.info("找到原始图片URL: {}", originalImageUrl);
            
            // 从Gemini下载原始尺寸的图片
            // 需要找到对应的页面，然后使用下载按钮下载
            Resource resource = downloadOriginalImageFromGemini(originalImageUrl, filename);
            
            if (resource != null) {
                // 根据文件扩展名确定 Content-Type
                String contentType = determineContentType(filename);
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType(contentType));
                headers.setContentDispositionFormData("attachment", filename);
                headers.setAccessControlAllowOrigin("*");
                
                return ResponseEntity.ok()
                        .headers(headers)
                        .body(resource);
            } else {
                log.warn("从Gemini下载原始图片失败: {}", originalImageUrl);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            
        } catch (Exception e) {
            log.error("下载原始图片时出错: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 从映射文件中获取原始图片URL
     */
    private String getOriginalImageUrl(String filename) {
        try {
            Path mappingFile = Paths.get(userDataDir, IMAGES_SUBDIR, IMAGE_URL_MAPPING_FILE);
            if (!Files.exists(mappingFile)) {
                log.warn("图片URL映射文件不存在: {}", mappingFile.toAbsolutePath());
                return null;
            }
            
            String content = Files.readString(mappingFile);
            if (content == null || content.trim().isEmpty()) {
                return null;
            }
            
            Map<String, String> mappings = objectMapper.readValue(content, 
                new TypeReference<Map<String, String>>() {});
            
            return mappings.get(filename);
        } catch (Exception e) {
            log.error("读取图片URL映射文件时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 从Gemini下载原始尺寸的图片
     * 参考 Frame-Kitchen 项目的 downloadImage 方法
     */
    private Resource downloadOriginalImageFromGemini(String imageUrl, String filename) {
        Page page = null;
        try {
            // 从文件名中提取conversationId
            String conversationId = extractConversationIdFromFilename(filename);
            if (conversationId == null || conversationId.isEmpty()) {
                log.warn("无法从文件名提取对话ID: {}", filename);
                return null;
            }
            
            // 创建新页面并导航到对话页面
            page = browserManager.newPage("gemini");
            if (page == null || page.isClosed()) {
                log.warn("无法创建Gemini页面");
                return null;
            }
            
            // 导航到包含该图片的对话页面
            String conversationUrl = "https://gemini.google.com/app/" + conversationId;
            log.info("导航到对话页面: {}", conversationUrl);
            page.navigate(conversationUrl);
            page.waitForLoadState();
            page.waitForTimeout(2000);
            
            // 等待页面加载完成，确保图片已显示
            // 查找图片元素，确保图片存在
            Locator imageElement = page.locator("model-response img").last();
            if (imageElement.count() == 0) {
                log.warn("页面中未找到图片元素");
                return null;
            }
            
            // 参考 Frame-Kitchen 的实现：直接点击更多菜单按钮
            // 等待更多菜单按钮出现
            log.info("等待更多菜单按钮出现...");
            page.waitForSelector(".more-menu-button-container .more-menu-button", 
                new Page.WaitForSelectorOptions().setTimeout(10000));
            
            // 点击更多菜单按钮
            log.info("点击更多菜单按钮");
            page.locator(".more-menu-button-container .more-menu-button").first().click();
            page.waitForTimeout(500);
            
            // 等待下载按钮出现并点击
            log.info("等待下载按钮出现...");
            page.waitForSelector(".mat-mdc-menu-panel > div > div > button[data-test-id=\"image-download-button\"]",
                new Page.WaitForSelectorOptions().setTimeout(5000));
            
            log.info("点击下载按钮");
            page.locator(".mat-mdc-menu-panel > div > div > button[data-test-id=\"image-download-button\"]").first().click();
            
            // 等待下载提示消息出现
            log.info("等待下载提示消息...");
            page.waitForSelector(".snackbar-message div.label", 
                new Page.WaitForSelectorOptions().setTimeout(10000));
            
            // 显示下载消息
            String message = page.locator(".snackbar-message div.label").first().innerText();
            log.info("下载消息: {}", message);
            
            // 等待下载完成
            log.info("等待下载完成...");
            Page finalPage = page;
            Download download = page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(5 * 60 * 1000), () -> {
                // 下载过程中可以显示进度信息
                try {
                    Locator snackbar = finalPage.locator(".snackbar-message div.label");
                    if (snackbar.count() > 0) {
                        String currentMessage = snackbar.first().innerText();
                        if (!currentMessage.equals(message)) {
                            log.info("下载进度: {}", currentMessage);
                        }
                    }
                } catch (Exception e) {
                    // 忽略
                }
            }); // 5分钟超时
            
            // 保存下载的文件
            Path tempDir = Paths.get(userDataDir, IMAGES_SUBDIR, "temp");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            
            String tempFileName = "original_" + filename;
            Path tempFilePath = tempDir.resolve(tempFileName);
            download.saveAs(tempFilePath);
            
            log.info("原始图片已下载到: {}", tempFilePath.toAbsolutePath());
            
            return new FileSystemResource(tempFilePath.toFile());
            
        } catch (Exception e) {
            log.error("从Gemini下载原始图片时出错: {}", e.getMessage(), e);
            if (page != null && !page.isClosed()) {
                try {
                    page.close();
                } catch (Exception closeEx) {
                    // 忽略关闭错误
                }
            }
            return null;
        } finally {
            // 清理：关闭临时页面
            if (page != null && !page.isClosed()) {
                try {
                    page.close();
                } catch (Exception e) {
                    log.debug("关闭临时页面时出错: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 从文件名中提取对话ID
     * 文件名格式：gemini_{conversationId}_{timestamp}_{uuid}.{ext}
     */
    private String extractConversationIdFromFilename(String filename) {
        try {
            // 移除扩展名
            String nameWithoutExt = filename.substring(0, filename.lastIndexOf('.'));
            String[] parts = nameWithoutExt.split("_");
            if (parts.length >= 2) {
                // 返回conversationId部分（第二部分）
                return parts[1];
            }
        } catch (Exception e) {
            log.debug("从文件名提取对话ID失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 根据文件名确定 Content-Type
     */
    private String determineContentType(String filename) {
        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.endsWith(".png")) {
            return "image/png";
        } else if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerFilename.endsWith(".gif")) {
            return "image/gif";
        } else if (lowerFilename.endsWith(".webp")) {
            return "image/webp";
        } else {
            // 默认返回 PNG
            return "image/png";
        }
    }
}


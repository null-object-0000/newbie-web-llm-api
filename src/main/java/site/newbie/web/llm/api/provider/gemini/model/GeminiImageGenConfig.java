package site.newbie.web.llm.api.provider.gemini.model;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import site.newbie.web.llm.api.provider.ModelConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini 生图模型配置
 * 用于生成图片的模型
 */
@Slf4j
@Component
public class GeminiImageGenConfig implements GeminiModelConfig {
    
    public static final String MODEL_NAME = "gemini-web-imagegen";
    
    // 从配置文件读取，与浏览器数据目录保持一致
    @Value("${app.browser.user-data-dir:./user-data}")
    private String userDataDir;
    
    // 服务器基础 URL，用于构建完整的图片 URL
    @Value("${app.server.base-url:http://localhost:24753}")
    private String serverBaseUrl;
    
    private static final String IMAGES_SUBDIR = "gemini-images";
    private static final String IMAGE_URL_MAPPING_FILE = "image-url-mapping.json";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getModelName() {
        return MODEL_NAME;
    }
    
    @Override
    public void configure(Page page) {
        log.info("配置 {} 模型，启用生图工具", MODEL_NAME);
        try {
            // 点击工具箱按钮
            Locator toolboxButton = page.locator(".toolbox-drawer-button-with-label");
            if (toolboxButton.count() > 0 && toolboxButton.first().isVisible()) {
                toolboxButton.first().click();
                log.info("已点击工具箱按钮");
                page.waitForTimeout(500);
                
                // 点击"生成图片"选项
                // 参考 Frame-Kitchen 项目，使用 .mdc-list-item__content .feature-content .gds-label-l:text('生成图片')
                Locator imageGenOption = page.locator(".mdc-list-item__content .feature-content .gds-label-l:text('生成图片')")
                        .or(page.locator(".mdc-list-item__content:has-text('生成图片')"))
                        .or(page.locator(".mdc-list-item__content:has-text('Image')"))
                        .or(page.locator(".mdc-list-item__content .feature-content:has-text('生成图片')"));
                
                if (imageGenOption.count() > 0) {
                    imageGenOption.first().click();
                    log.info("已点击生成图片选项");
                    page.waitForTimeout(500);
                    
                    // 验证是否开启成功 (检查取消按钮是否出现)
                    // 参考 Frame-Kitchen 项目，使用 .toolbox-drawer-item-deselect-button[aria-label='取消选择"图片"']
                    Locator deselectButton = page.locator(".toolbox-drawer-item-deselect-button[aria-label*='取消选择']")
                            .or(page.locator(".toolbox-drawer-item-deselect-button[aria-label*='取消']"))
                            .or(page.locator(".toolbox-drawer-item-deselect-button"));
                    
                    // 等待取消按钮出现，最多等待 3 秒
                    int maxWaitAttempts = 30;
                    int attempts = 0;
                    while (attempts < maxWaitAttempts && deselectButton.count() == 0) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("等待取消按钮时被中断");
                            break;
                        }
                        attempts++;
                    }
                    
                    if (deselectButton.count() > 0) {
                        log.info("生图工具已成功启用");
                    } else {
                        log.warn("未找到取消按钮，但继续执行（可能已经启用）");
                    }
                } else {
                    log.warn("未找到生成图片选项，可能已经启用或界面已变化");
                }
            } else {
                log.warn("未找到工具箱按钮，可能已经启用或界面已变化");
            }
        } catch (Exception e) {
            log.error("启用生图工具时出错: {}", e.getMessage(), e);
            // 不抛出异常，继续执行（可能已经启用）
        }
    }

    @Override
    public void monitorResponse(GeminiContext context) throws IOException, InterruptedException {
        log.info("开始监听图片生成响应（DOM 模式）...");
        monitorResponseDom(context);
    }
    
    private void monitorResponseDom(GeminiContext context) throws IOException, InterruptedException {
        Page page = context.page();
        SseEmitter emitter = context.emitter();
        ChatCompletionRequest request = context.request();
        int messageCountBefore = context.messageCountBefore();
        ModelConfig.ResponseHandler handler = context.responseHandler();
        
        // 参考 Frame Kitchen 项目，使用 model-response message-content 作为响应选择器
        String allMarkdownSelector = "model-response message-content";
        
        Locator allResponseLocators = page.locator(allMarkdownSelector);
        int maxWaitTime = 30000;
        long waitStartTime = System.currentTimeMillis();
        while (allResponseLocators.count() <= messageCountBefore) {
            if (System.currentTimeMillis() - waitStartTime > maxWaitTime) {
                throw new RuntimeException("等待新消息超时");
            }
            Thread.sleep(100);
        }
        
        log.info("检测到新消息，开始 DOM 监听（思考过程）");
        
        // 尝试点击思考按钮以展开思考内容
        clickThoughtsButtonIfExists(page);
        
        StringBuilder collectedThinkingText = new StringBuilder();
        long startTime = System.currentTimeMillis();
        String thinkingId = UUID.randomUUID().toString();
        String lastFullText = "";
        String lastThinkingText = "";
        int noChangeCount = 0;
        boolean responseFinished = false;
        boolean thoughtsButtonClicked = false;

        // 第一阶段：监听思考过程，将所有内容作为思考过程发送
        while (true) {
            try {
                if (page.isClosed()) break;

                try {
                    // 尝试点击思考按钮（如果还未点击）
                    if (!thoughtsButtonClicked) {
                        if (clickThoughtsButtonIfExists(page)) {
                            thoughtsButtonClicked = true;
                            // 等待思考内容展开
                            Thread.sleep(500);
                        }
                    }
                    
                    // 提取思考内容（如果存在）
                    String thinkingText = extractThinkingContent(page, messageCountBefore);
                    if (thinkingText != null && !thinkingText.isEmpty()) {
                        if (thinkingText.length() > lastThinkingText.length()) {
                            String delta = thinkingText.substring(lastThinkingText.length());
                            collectedThinkingText.append(delta);
                            noChangeCount = 0;
                            // 作为思考内容发送
                            handler.sendThinking(emitter, thinkingId, delta, request.getModel());
                        } else if (thinkingText.equals(lastThinkingText) && !thinkingText.isEmpty()) {
                            noChangeCount++;
                        }
                        lastThinkingText = thinkingText;
                    }
                    
                    Locator allMarkdownLocators = page.locator(allMarkdownSelector);
                    int currentCount = allMarkdownLocators.count();
                    
                    if (currentCount > messageCountBefore) {
                        String currentText = findFinalResponseText(allMarkdownLocators, messageCountBefore, currentCount);
                        
                        if (currentText != null) {
                            // 将新增的内容作为思考过程发送（如果还没有思考内容）
                            if (thinkingText == null || thinkingText.isEmpty()) {
                                if (currentText.length() > collectedThinkingText.length()) {
                                    String delta = currentText.substring(collectedThinkingText.length());
                                    collectedThinkingText.append(delta);
                                    noChangeCount = 0;
                                    // 作为思考内容发送
                                    handler.sendThinking(emitter, thinkingId, delta, request.getModel());
                                } else if (currentText.equals(lastFullText) && !currentText.isEmpty()) {
                                    noChangeCount++;
                                }
                            }
                            lastFullText = currentText;
                        }
                    }
                } catch (Exception e) {
                    log.error("DOM 查询时出错: {}", e.getMessage());
                }

                    // 检测响应是否完成
                    if (!responseFinished) {
                        // 检查动画元素：如果存在未完成的动画，说明还在生成中
                        boolean animationActive = false;
                        try {
                            Locator animationLocator = page.locator("div.avatar div.avatar_primary_animation:not([data-test-lottie-animation-status='completed'])");
                            animationActive = animationLocator.count() > 0;
                        } catch (Exception e) {
                            log.debug("检查动画状态时出错: {}", e.getMessage());
                        }
                    
                    // 如果动画还在进行，说明还在生成，继续等待
                    if (animationActive) {
                        noChangeCount = 0; // 重置无变化计数
                        continue;
                    }
                    
                    // 参考 Frame Kitchen 项目，使用 .send-button.stop, .bard-avatar.thinking 检测思考状态
                    String thinkingSelector = ".send-button.stop, .bard-avatar.thinking";
                    boolean thinkingVisible = false;
                    try {
                        Locator thinkingLocator = page.locator(thinkingSelector);
                        // 检查是否有思考元素可见
                        thinkingVisible = thinkingLocator.all().stream().anyMatch(Locator::isVisible);
                    } catch (Exception e) {
                        // 忽略错误，继续检查
                    }
                    
                    // 检查发送按钮是否出现（对话完成后，发送按钮会重新出现）
                    boolean sendButtonVisible = false;
                    try {
                        // 查找发送按钮（不是停止按钮）
                        Locator sendButton = page.locator("input-container button.send-button:not(.stop)")
                                .or(page.locator("button[aria-label*='发送']:not(.stop)"))
                                .or(page.locator("button[aria-label*='Send']:not(.stop)"))
                                .or(page.locator("button.send-button:not(.stop):not([disabled])"));
                        
                        if (sendButton.count() > 0) {
                            sendButtonVisible = sendButton.first().isVisible();
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续检查
                    }
                    
                    // 如果动画已完成，思考元素不可见，且发送按钮可见，说明对话已完成
                    if (!animationActive && !thinkingVisible && sendButtonVisible) {
                        // 再等待一小段时间，确保状态稳定
                        Thread.sleep(500);
                        // 再次确认动画状态和发送按钮
                        try {
                            Locator animationLocator2 = page.locator("div.avatar div.avatar_primary_animation:not([data-test-lottie-animation-status='completed'])");
                            boolean animationActive2 = animationLocator2.count() > 0;
                            
                            thinkingVisible = page.locator(thinkingSelector).all().stream().anyMatch(Locator::isVisible);
                            sendButtonVisible = page.locator("input-container button.send-button:not(.stop)").count() > 0 
                                    && page.locator("input-container button.send-button:not(.stop)").first().isVisible();
                            
                            if (!animationActive2 && !thinkingVisible && sendButtonVisible) {
                                responseFinished = true;
                                log.info("检测到响应完成（动画已完成，思考状态结束，发送按钮已出现）");
                            }
                        } catch (Exception e) {
                            // 如果再次检查失败，但第一次检查通过，也认为完成
                            responseFinished = true;
                            log.info("检测到响应完成（动画已完成，思考状态结束，发送按钮已出现，二次确认时出错但继续）");
                        }
                    } else if (!animationActive && !thinkingVisible) {
                        // 如果动画已完成，思考元素不可见，但发送按钮还没出现，可能是短暂状态，继续等待
                        // 但如果长时间（比如 2 秒）都是这样，也认为完成
                        if (noChangeCount >= 20) {
                            responseFinished = true;
                            log.info("检测到响应完成（动画已完成，思考状态结束，内容稳定）");
                        }
                    }
                    
                    // 如果内容长时间不变，也认为已完成（但优先级低于动画和发送按钮检查）
                    if (!responseFinished && noChangeCount >= 30) {
                        responseFinished = true;
                        log.info("检测到响应完成（内容长时间稳定）");
                    }
                }

                if (responseFinished) {
                    break;
                }

                if (System.currentTimeMillis() - startTime > 120000) {
                    log.warn("达到超时时间，结束思考过程监听");
                    break;
                }

                Thread.sleep(100);
            } catch (Exception e) {
                if (page.isClosed()) break;
                if (isRecoverableError(e)) {
                    Thread.sleep(500);
                    continue;
                }
                log.error("监听思考过程时出错", e);
                break;
            }
        }

        // 第二阶段：响应完成后，提取图片并返回
        log.info("思考过程完成，提取图片");
        try {
            // 等待一小段时间，确保 DOM 内容完全稳定
            Thread.sleep(500);
            
            // 提取图片 URL
            String imageUrl = extractImageUrl(page, messageCountBefore);
            
            if (imageUrl != null && !imageUrl.isEmpty()) {
                log.info("找到生成的图片: {}", imageUrl);
                
                // 获取对话ID和提供器名称
                String conversationId = getConversationId(request, page);
                String providerName = "gemini";
                
                // 下载图片并保存到本地，返回文件名
                String savedFileName = downloadAndSaveImage(imageUrl, providerName, conversationId, page);
                
                if (savedFileName != null && !savedFileName.isEmpty()) {
                    // 构建完整的静态资源 URL
                    String imageUrlPath = buildFullImageUrl(savedFileName);
                    // 将图片作为 Markdown 图片格式发送
                    String imageMarkdown = String.format("\n![生成的图片](%s)\n", imageUrlPath);
                    String id = UUID.randomUUID().toString();
                    handler.sendChunk(emitter, id, imageMarkdown, request.getModel());
                    log.info("图片已下载并保存，已发送静态资源 URL: {}", imageUrlPath);
                } else {
                    log.warn("图片下载或保存失败，使用原始 URL");
                    // 降级到使用原始 URL
                    String imageMarkdown = String.format("\n![生成的图片](%s)\n", imageUrl);
                    String id = UUID.randomUUID().toString();
                    handler.sendChunk(emitter, id, imageMarkdown, request.getModel());
                }
            } else {
                log.warn("未找到生成的图片");
                // 发送提示信息
                String message = "图片生成完成，但未能提取图片 URL。请检查浏览器中的响应。";
                String id = UUID.randomUUID().toString();
                handler.sendChunk(emitter, id, message, request.getModel());
            }
        } catch (Exception e) {
            log.error("提取图片时出错: {}", e.getMessage(), e);
        }

        // 第三阶段：对话完成后，折叠思考内容
        try {
            collapseThoughts(page);
        } catch (Exception e) {
            log.warn("折叠思考内容时出错: {}", e.getMessage());
        }

        handler.sendUrlAndComplete(page, emitter, request);
    }
    
    /**
     * 检查最新的思考内容是否已经展开
     */
    private boolean isThoughtsExpanded(Page page) {
        try {
            Locator thoughtsElements = page.locator("model-thoughts structured-content-container");
            int count = thoughtsElements.count();
            if (count == 0) {
                return false;
            }
            return thoughtsElements.nth(count - 1).isVisible();
        } catch (Exception e) {
            log.debug("检查思考内容是否展开时出错: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 点击思考按钮以展开思考内容
     */
    private boolean clickThoughtsButtonIfExists(Page page) {
        try {
            if (isThoughtsExpanded(page)) {
                log.debug("思考内容已经展开，无需点击按钮");
                return true;
            }
            
            Locator thoughtsButton = page.locator(".mdc-button__label .thoughts-header-button-content");
            
            int maxWaitAttempts = 30;
            int attempts = 0;
            while (attempts < maxWaitAttempts && thoughtsButton.count() == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待思考按钮时被中断");
                    return false;
                }
                attempts++;
                if (isThoughtsExpanded(page)) {
                    log.debug("在等待按钮时检测到思考内容已展开");
                    return true;
                }
            }
            
            if (thoughtsButton.count() > 0) {
                int buttonCount = thoughtsButton.count();
                Locator latestButton = thoughtsButton.nth(buttonCount - 1);
                
                try {
                    if (latestButton.isVisible()) {
                        latestButton.click();
                        log.info("已点击最新的思考按钮（共 {} 个），等待思考内容展开", buttonCount);
                        
                        int waitAttempts = 0;
                        while (waitAttempts < 20 && !isThoughtsExpanded(page)) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("等待思考内容展开时被中断");
                                break;
                            }
                            waitAttempts++;
                        }
                        
                        if (isThoughtsExpanded(page)) {
                            log.info("思考内容已成功展开");
                            return true;
                        } else {
                            log.warn("点击按钮后思考内容未展开");
                            return false;
                        }
                    } else {
                        log.debug("最新的思考按钮不可见");
                        return false;
                    }
                } catch (Exception e) {
                    log.debug("点击最新的思考按钮失败: {}", e.getMessage());
                    return false;
                }
            }
        } catch (Exception e) {
            log.debug("未找到思考按钮或点击失败: {}", e.getMessage());
        }
        return false;
    }
    
    /**
     * 从 model-thoughts structured-content-container 元素中提取思考内容
     */
    private String extractThinkingContent(Page page, int messageCountBefore) {
        try {
            if (!isThoughtsExpanded(page)) {
                log.debug("思考内容未展开，无法提取");
                return null;
            }
            
            Locator thoughtsElements = page.locator("model-thoughts structured-content-container");
            int count = thoughtsElements.count();
            if (count > 0) {
                Locator latestThoughts = thoughtsElements.nth(count - 1);
                String innerText = latestThoughts.innerText();
                if (innerText != null && !innerText.trim().isEmpty()) {
                    return innerText.trim().replaceAll("\\s+", " ");
                }
            }
        } catch (Exception e) {
            log.debug("查找思考内容时出错: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 折叠思考内容
     */
    private void collapseThoughts(Page page) {
        try {
            if (!isThoughtsExpanded(page)) {
                log.debug("思考内容未展开，无需折叠");
                return;
            }
            
            Locator thoughtsButton = page.locator(".mdc-button__label .thoughts-header-button-content");
            
            if (thoughtsButton.count() > 0) {
                int buttonCount = thoughtsButton.count();
                Locator latestButton = thoughtsButton.nth(buttonCount - 1);
                
                try {
                    if (latestButton.isVisible()) {
                        latestButton.click();
                        log.info("已点击最新的思考按钮（共 {} 个）以折叠思考内容", buttonCount);
                        
                        int waitAttempts = 0;
                        while (waitAttempts < 10 && isThoughtsExpanded(page)) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("等待思考内容折叠时被中断");
                                break;
                            }
                            waitAttempts++;
                        }
                        
                        if (!isThoughtsExpanded(page)) {
                            log.info("思考内容已成功折叠");
                        } else {
                            log.warn("点击按钮后思考内容未折叠");
                        }
                    } else {
                        log.debug("最新的思考按钮不可见，无法折叠");
                    }
                } catch (Exception e) {
                    log.debug("点击思考按钮折叠时出错: {}", e.getMessage());
                }
            } else {
                log.debug("未找到思考按钮，无法折叠");
            }
        } catch (Exception e) {
            log.debug("折叠思考内容时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 查找最终响应文本
     */
    private String findFinalResponseText(Locator allMarkdownLocators, int messageCountBefore, int currentCount) {
        for (int i = currentCount - 1; i >= messageCountBefore; i--) {
            try {
                Locator locator = allMarkdownLocators.nth(i);
                String markdown = (String) locator.evaluate("""
                    (element) => {
                        if (!element) return '';
                        
                        function htmlToMarkdown(node) {
                            if (node.nodeType === Node.TEXT_NODE) {
                                return node.textContent || '';
                            }
                            
                            if (node.nodeType !== Node.ELEMENT_NODE) {
                                return '';
                            }
                            
                            const tagName = node.tagName?.toLowerCase();
                            const children = Array.from(node.childNodes);
                            let content = children.map(htmlToMarkdown).join('');
                            
                            switch (tagName) {
                                case 'strong':
                                case 'b':
                                    return '**' + content + '**';
                                case 'em':
                                case 'i':
                                    return '*' + content + '*';
                                case 'code':
                                    if (node.parentElement?.tagName?.toLowerCase() === 'pre') {
                                        return content;
                                    }
                                    return '`' + content + '`';
                                case 'pre':
                                    const codeElement = node.querySelector('code');
                                    const language = codeElement?.className?.match(/language-(\\w+)/)?.[1] || '';
                                    const codeContent = codeElement ? codeElement.textContent : content;
                                    return '\\n```' + language + '\\n' + codeContent + '\\n```\\n';
                                case 'a':
                                    const href = node.getAttribute('href') || '';
                                    return '[' + content + '](' + href + ')';
                                case 'p':
                                    return content + '\\n\\n';
                                case 'br':
                                    return '\\n';
                                default:
                                    return content;
                            }
                        }
                        
                        return htmlToMarkdown(element).trim();
                    }
                """);
                return markdown;
            } catch (Exception e) {
                log.warn("获取响应文本时出错，尝试使用纯文本: {}", e.getMessage());
                try {
                    Locator locator = allMarkdownLocators.nth(i);
                    return locator.innerText();
                } catch (Exception e2) {
                    // 继续查找
                }
            }
        }
        return null;
    }
    
    private boolean isRecoverableError(Exception e) {
        return e.getMessage() != null &&
                (e.getMessage().contains("Cannot find command") ||
                 e.getMessage().contains("Target closed") ||
                 e.getMessage().contains("Session closed"));
    }
    
    /**
     * 提取图片 URL
     * 参考 Frame-Kitchen 项目的 image 方法
     */
    private String extractImageUrl(Page page, int messageCountBefore) {
        try {
            Locator responseContainer = page.locator("model-response").last();
            Locator images = responseContainer.locator("img");
            int count = images.count();
            if (count > 0) {
                log.info("找到 {} 张图片", count);
                String imageUrl = images.nth(0).getAttribute("src");
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    return imageUrl;
                } else {
                    log.warn("图片元素存在但 src 属性为空");
                }
            } else {
                log.warn("响应中未找到图片");
            }
        } catch (Exception e) {
            log.error("提取图片 URL 时出错: {}", e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * 获取对话ID
     */
    private String getConversationId(ChatCompletionRequest request, Page page) {
        String conversationId = request.getConversationId();
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        // 从URL中提取
        try {
            if (page != null && !page.isClosed()) {
                String url = page.url();
                if (url.contains("gemini.google.com") || url.contains("ai.google.dev")) {
                    int appIdx = url.indexOf("/app/");
                    if (appIdx >= 0) {
                        String afterApp = url.substring(appIdx + "/app/".length());
                        int queryIdx = afterApp.indexOf('?');
                        int fragmentIdx = afterApp.indexOf('#');
                        int endIdx = afterApp.length();
                        if (queryIdx >= 0) endIdx = Math.min(endIdx, queryIdx);
                        if (fragmentIdx >= 0) endIdx = Math.min(endIdx, fragmentIdx);
                        String id = afterApp.substring(0, endIdx).trim();
                        if (!id.isEmpty()) {
                            return id;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从URL提取对话ID失败: {}", e.getMessage());
        }
        // 如果都没有，使用时间戳作为临时ID
        return "temp_" + System.currentTimeMillis();
    }
    
    /**
     * 下载图片并保存到本地文件系统
     * 使用 Playwright 的 page.request() API，自动携带浏览器上下文
     * 返回保存的文件名
     * 包含重试机制
     */
    private String downloadAndSaveImage(String imageUrl, String providerName, String conversationId, Page page) {
        int maxRetries = 3;
        long retryDelayMs = 1000; // 重试延迟1秒
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("开始下载图片 (尝试 {}/{}): {}", attempt, maxRetries, imageUrl);
                
                // 使用 Playwright 的 page.request() API 下载图片
                // 这样可以自动携带所有浏览器上下文（Cookie、User-Agent 等）
                com.microsoft.playwright.APIResponse response = page.request().get(imageUrl);
                
                if (response.status() == 200) {
                    byte[] originalImageBytes = response.body();
                    
                    // 根据响应头确定图片类型，默认为 png
                    String contentType = response.headers().get("content-type");
                    if (contentType == null || contentType.isEmpty()) {
                        contentType = "image/png";
                    }
                    
                    String imageType = "png";
                    if (contentType.contains("jpeg") || contentType.contains("jpg")) {
                        imageType = "jpeg";
                    } else if (contentType.contains("gif")) {
                        imageType = "gif";
                    } else if (contentType.contains("webp")) {
                        imageType = "webp";
                    }
                    
                    // 保存图片到本地，返回文件名
                    String savedFileName = saveImageToLocal(originalImageBytes, imageType, providerName, conversationId, imageUrl);
                    if (savedFileName != null) {
                        log.info("图片已保存到本地: {}", savedFileName);
                        return savedFileName;
                    } else {
                        log.warn("保存图片失败");
                        return null;
                    }
                } else if (response.status() == 403) {
                    log.warn("下载图片失败，状态码: {} (尝试 {}/{})", response.status(), attempt, maxRetries);
                    response.dispose(); // 释放响应资源
                    if (attempt < maxRetries) {
                        log.info("等待 {} 毫秒后重试...", retryDelayMs);
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("重试等待被中断");
                            return null;
                        }
                        // 指数退避：每次重试延迟时间翻倍
                        retryDelayMs *= 2;
                        continue;
                    } else {
                        log.error("下载图片失败，已达到最大重试次数，状态码: {}", response.status());
                        return null;
                    }
                } else {
                    log.warn("下载图片失败，状态码: {} (尝试 {}/{})", response.status(), attempt, maxRetries);
                    response.dispose(); // 释放响应资源
                    if (attempt < maxRetries) {
                        log.info("等待 {} 毫秒后重试...", retryDelayMs);
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("重试等待被中断");
                            return null;
                        }
                        retryDelayMs *= 2;
                        continue;
                    } else {
                        log.error("下载图片失败，已达到最大重试次数，状态码: {}", response.status());
                        return null;
                    }
                }
            } catch (Exception e) {
                log.error("下载图片时出错 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    log.info("等待 {} 毫秒后重试...", retryDelayMs);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断");
                        return null;
                    }
                    retryDelayMs *= 2;
                    continue;
                } else {
                    log.error("下载图片失败，已达到最大重试次数: {}", e.getMessage(), e);
                    return null;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 从 Playwright 页面获取 Cookie 并格式化为 Cookie 请求头
     */
    private String getCookiesFromPage(Page page) {
        try {
            if (page == null || page.isClosed()) {
                return null;
            }
            
            // 获取当前页面的所有 Cookie
            List<com.microsoft.playwright.options.Cookie> cookies = page.context().cookies();
            
            if (cookies == null || cookies.isEmpty()) {
                log.debug("页面中没有 Cookie");
                return null;
            }
            
            // 将 Cookie 列表格式化为 Cookie 请求头格式
            StringBuilder cookieHeader = new StringBuilder();
            for (int i = 0; i < cookies.size(); i++) {
                com.microsoft.playwright.options.Cookie cookie = cookies.get(i);
                cookieHeader.append(cookie.name).append("=").append(cookie.value);
                if (i < cookies.size() - 1) {
                    cookieHeader.append("; ");
                }
            }
            
            log.debug("已获取 {} 个 Cookie", cookies.size());
            return cookieHeader.toString();
        } catch (Exception e) {
            log.warn("获取页面 Cookie 时出错: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 从 Playwright 页面获取 User-Agent
     * 与 BrowserManager 中设置的保持一致
     */
    private String getUserAgentFromPage(Page page) {
        try {
            if (page == null || page.isClosed()) {
                return null;
            }
            
            // 通过 JavaScript 获取 navigator.userAgent
            Object userAgentObj = page.evaluate("() => navigator.userAgent");
            if (userAgentObj != null) {
                String userAgent = userAgentObj.toString();
                log.debug("从页面获取 User-Agent: {}", userAgent);
                return userAgent;
            }
        } catch (Exception e) {
            log.debug("从页面获取 User-Agent 时出错: {}", e.getMessage());
        }
        
        // 如果无法获取，返回与 BrowserManager 相同的默认值
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }
    
    /**
     * 保存图片到本地文件系统
     * @param imageBytes 图片字节数组
     * @param imageType 图片类型（png, jpeg, gif, webp）
     * @param providerName 提供器名称
     * @param conversationId 对话ID
     * @param originalImageUrl 原始图片URL（用于后续下载原始尺寸）
     * @return 保存的文件名，如果失败返回 null
     */
    private String saveImageToLocal(byte[] imageBytes, String imageType, String providerName, 
                                   String conversationId, String originalImageUrl) {
        try {
            // 创建存储目录
            Path storageDir = Paths.get(userDataDir, IMAGES_SUBDIR);
            if (!Files.exists(storageDir)) {
                Files.createDirectories(storageDir);
                log.info("创建图片存储目录: {}", storageDir.toAbsolutePath());
            }
            
            // 生成文件名：提供器名称_对话ID_时间戳_UUID.扩展名
            // 对话ID可能包含特殊字符，需要清理
            String safeConversationId = conversationId != null ? 
                conversationId.replaceAll("[^a-zA-Z0-9_-]", "_") : "unknown";
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String uuid = UUID.randomUUID().toString().substring(0, 8);
            String extension = imageType;
            if ("jpeg".equals(imageType) || "jpg".equals(imageType)) {
                extension = "jpg";
            }
            
            String fileName = String.format("%s_%s_%s_%s.%s", providerName, safeConversationId, timestamp, uuid, extension);
            Path filePath = storageDir.resolve(fileName);
            
            // 保存文件
            Files.write(filePath, imageBytes);
            
            // 保存图片URL映射关系（文件名 -> 原始URL）
            saveImageUrlMapping(fileName, originalImageUrl);
            
            log.info("图片已保存到: {}", filePath.toAbsolutePath());
            return fileName;
        } catch (Exception e) {
            log.error("保存图片到本地时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 保存图片URL映射关系到JSON文件
     */
    private void saveImageUrlMapping(String fileName, String originalImageUrl) {
        try {
            Path mappingFile = Paths.get(userDataDir, IMAGES_SUBDIR, "image-url-mapping.json");
            
            // 读取现有映射
            Map<String, String> mappings = new HashMap<>();
            if (Files.exists(mappingFile)) {
                try {
                    String content = Files.readString(mappingFile);
                    if (content != null && !content.trim().isEmpty()) {
                        mappings = objectMapper.readValue(content, 
                            new TypeReference<Map<String, String>>() {});
                    }
                } catch (Exception e) {
                    log.warn("读取图片URL映射文件失败，将创建新文件: {}", e.getMessage());
                }
            }
            
            // 添加新映射
            mappings.put(fileName, originalImageUrl);
            
            // 保存映射
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(mappingFile.toFile(), mappings);
            
            log.debug("已保存图片URL映射: {} -> {}", fileName, originalImageUrl);
        } catch (Exception e) {
            log.error("保存图片URL映射时出错: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 构建完整的图片 URL
     * @param filename 文件名
     * @return 完整的图片 URL
     */
    private String buildFullImageUrl(String filename) {
        // 确保 serverBaseUrl 不以 / 结尾
        String baseUrl = serverBaseUrl;
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        
        // 如果 baseUrl 为空，使用默认值
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:24753";
        }
        
        // 构建完整 URL
        String fullUrl = baseUrl + "/api/images/" + filename;
        log.debug("构建完整图片 URL: {}", fullUrl);
        return fullUrl;
    }
}


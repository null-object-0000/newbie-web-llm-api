package site.newbie.web.llm.api.provider.gemini.command;

import lombok.extern.slf4j.Slf4j;

/**
 * 帮助指令
 * 用法: /help 或 /help:指令名
 */
@Slf4j
public class HelpCommand implements Command {
    private final String commandName;
    
    public HelpCommand() {
        this.commandName = null;
    }
    
    public HelpCommand(String commandName) {
        this.commandName = commandName != null ? commandName.toLowerCase() : null;
    }
    
    @Override
    public String getName() {
        return "help";
    }
    
    @Override
    public String getDescription() {
        if (commandName != null) {
            return "查询指令帮助: " + commandName;
        }
        return "查询所有支持的指令";
    }
    
    @Override
    public boolean execute(com.microsoft.playwright.Page page, ProgressCallback progressCallback) {
        try {
            log.info("执行 help 指令: commandName={}", commandName);
            
            if (progressCallback == null) {
                log.warn("help 指令需要进度回调");
                return false;
            }
            
            if (commandName != null && !commandName.isEmpty()) {
                // 查询特定指令的帮助
                showCommandHelp(commandName, progressCallback);
            } else {
                // 显示所有指令的帮助
                showAllCommandsHelp(progressCallback);
            }
            
            return true;
        } catch (Exception e) {
            log.error("执行 help 指令时出错: {}", e.getMessage(), e);
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 查询帮助时出错: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * 显示所有指令的帮助
     */
    private void showAllCommandsHelp(ProgressCallback progressCallback) {
        progressCallback.onProgress("当前模型支持以下内置指令：");
        progressCallback.onProgress("");
        
        // attach-drive
        progressCallback.onProgress("1. /attach-drive:文件名 或 /attach-drive 文件名");
        progressCallback.onProgress("   添加 Google Drive 文件作为附件");
        progressCallback.onProgress("   示例: /attach-drive:隋坡-糖醋排骨-202409.mp4");
        progressCallback.onProgress("");
        
        // attach-local
        progressCallback.onProgress("2. /attach-local:文件路径 或 /attach-local 文件路径");
        progressCallback.onProgress("   添加本地文件作为附件");
        progressCallback.onProgress("   示例: /attach-local:/path/to/file.pdf");
        progressCallback.onProgress("");
        
        // help
        progressCallback.onProgress("3. /help 或 /help:指令名");
        progressCallback.onProgress("   查询支持的指令列表或特定指令的详细帮助");
        progressCallback.onProgress("   示例: /help 或 /help:attach-drive");
        progressCallback.onProgress("");
        
        // login
        progressCallback.onProgress("4. /login");
        progressCallback.onProgress("   检查登录状态，如果未登录则引导完成登录");
        progressCallback.onProgress("   示例: /login");
        progressCallback.onProgress("");
        
        progressCallback.onProgress("说明：");
        progressCallback.onProgress("- 指令对话：只包含指令（如 /attach-drive:文件名），会独立处理，不发送给 AI");
        progressCallback.onProgress("- 普通对话：指令+实际内容（如 /attach-drive:文件名 请分析），会先执行指令添加附件，然后发送消息给 AI");
    }
    
    /**
     * 显示特定指令的帮助
     */
    private void showCommandHelp(String commandName, ProgressCallback progressCallback) {
        switch (commandName) {
            case "attach-drive":
            case "attach-google-drive":
                progressCallback.onProgress("指令: /attach-drive");
                progressCallback.onProgress("");
                progressCallback.onProgress("功能: 添加 Google Drive 文件作为附件");
                progressCallback.onProgress("");
                progressCallback.onProgress("用法:");
                progressCallback.onProgress("  /attach-drive:文件名");
                progressCallback.onProgress("  /attach-drive 文件名");
                progressCallback.onProgress("");
                progressCallback.onProgress("示例:");
                progressCallback.onProgress("  /attach-drive:隋坡-糖醋排骨-202409.mp4");
                progressCallback.onProgress("  /attach-drive 隋坡-糖醋排骨-202409.mp4");
                progressCallback.onProgress("");
                progressCallback.onProgress("说明:");
                progressCallback.onProgress("- 会自动打开 Google Drive 文件选择器");
                progressCallback.onProgress("- 在搜索框中输入文件名并选择第一个结果");
                progressCallback.onProgress("- 指令对话会实时反馈操作进度");
                break;
                
            case "attach-local":
            case "attach-file":
                progressCallback.onProgress("指令: /attach-local");
                progressCallback.onProgress("");
                progressCallback.onProgress("功能: 添加本地文件作为附件");
                progressCallback.onProgress("");
                progressCallback.onProgress("用法:");
                progressCallback.onProgress("  /attach-local:文件路径");
                progressCallback.onProgress("  /attach-local 文件路径");
                progressCallback.onProgress("");
                progressCallback.onProgress("示例:");
                progressCallback.onProgress("  /attach-local:/path/to/file.pdf");
                progressCallback.onProgress("  /attach-local /path/to/file.pdf");
                progressCallback.onProgress("");
                progressCallback.onProgress("说明:");
                progressCallback.onProgress("- 会自动打开文件选择器并选择指定文件");
                progressCallback.onProgress("- 等待文件上传完成");
                progressCallback.onProgress("- 指令对话会实时反馈操作进度");
                break;
                
            case "help":
                progressCallback.onProgress("指令: /help");
                progressCallback.onProgress("");
                progressCallback.onProgress("功能: 查询支持的指令列表或特定指令的详细帮助");
                progressCallback.onProgress("");
                progressCallback.onProgress("用法:");
                progressCallback.onProgress("  /help");
                progressCallback.onProgress("  /help:指令名");
                progressCallback.onProgress("");
                progressCallback.onProgress("示例:");
                progressCallback.onProgress("  /help");
                progressCallback.onProgress("  /help:attach-drive");
                break;
                
            case "login":
                progressCallback.onProgress("指令: /login");
                progressCallback.onProgress("");
                progressCallback.onProgress("功能: 检查登录状态，如果未登录则引导完成登录");
                progressCallback.onProgress("");
                progressCallback.onProgress("用法:");
                progressCallback.onProgress("  /login");
                progressCallback.onProgress("");
                progressCallback.onProgress("示例:");
                progressCallback.onProgress("  /login");
                progressCallback.onProgress("");
                progressCallback.onProgress("说明:");
                progressCallback.onProgress("- 会自动检查当前登录状态");
                progressCallback.onProgress("- 如果已登录，直接返回成功");
                progressCallback.onProgress("- 如果未登录，会点击登录按钮并等待用户完成登录");
                progressCallback.onProgress("- 最多等待 60 秒，超时后需要手动检查登录状态");
                break;
                
            default:
                progressCallback.onProgress("未知指令: " + commandName);
                progressCallback.onProgress("");
                progressCallback.onProgress("使用 /help 查看所有支持的指令");
                break;
        }
    }
}


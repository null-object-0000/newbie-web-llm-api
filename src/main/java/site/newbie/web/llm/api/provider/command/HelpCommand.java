package site.newbie.web.llm.api.provider.command;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局帮助指令
 * 用法: /help 或 /help:指令名
 * 这是一个全局指令，不依赖任何 provider 或页面
 */
@Slf4j
public class HelpCommand implements Command {
    private final String commandName;
    private final CommandParser commandParser; // CommandParser 引用，用于动态获取 provider 特定指令
    
    public HelpCommand() {
        this(null, null);
    }
    
    public HelpCommand(String commandName) {
        this(commandName, null);
    }
    
    public HelpCommand(String commandName, CommandParser commandParser) {
        this.commandName = commandName != null ? commandName.toLowerCase() : null;
        this.commandParser = commandParser;
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
    public String getExample() {
        if (commandName != null && !commandName.isEmpty()) {
            return "/help:" + commandName;
        }
        return "/help 或 /help:指令名";
    }
    
    @Override
    public boolean requiresPage() {
        return false; // help 指令不需要页面
    }
    
    @Override
    public boolean requiresLogin() {
        return false; // help 指令不需要登录
    }
    
    @Override
    public boolean requiresProvider() {
        return false; // help 指令不需要 provider
    }
    
    @Override
    public boolean execute(com.microsoft.playwright.Page page, ProgressCallback progressCallback, 
                          site.newbie.web.llm.api.provider.LLMProvider provider) {
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
        progressCallback.onProgress("当前支持以下全局指令：");
        progressCallback.onProgress("");
        
        // 从 CommandParser 获取所有全局指令
        List<Command> globalCommands = commandParser != null ? commandParser.getGlobalCommands() : getDefaultGlobalCommands();
        int index = 1;
        for (Command cmd : globalCommands) {
            String cmdName = cmd.getName();
            // help 指令特殊处理，显示完整格式
            if ("help".equals(cmdName)) {
                progressCallback.onProgress(index + ". /" + cmdName + " 或 /help:指令名");
            } else {
                progressCallback.onProgress(index + ". /" + cmdName);
            }
            progressCallback.onProgress("   " + getCommandDescription(cmd));
            progressCallback.onProgress("   示例: " + cmd.getExample());
            progressCallback.onProgress("");
            index++;
        }
        
        // 显示 provider 特定指令（如果有）
        List<Command> providerCommands = commandParser != null ? commandParser.getProviderCommands() : null;
        if (providerCommands != null && !providerCommands.isEmpty()) {
            progressCallback.onProgress("当前模型还支持以下特定指令：");
            progressCallback.onProgress("");
            for (Command cmd : providerCommands) {
                String cmdName = cmd.getName();
                progressCallback.onProgress(index + ". /" + cmdName);
                progressCallback.onProgress("   " + getCommandDescription(cmd));
                progressCallback.onProgress("   示例: " + cmd.getExample());
                progressCallback.onProgress("");
                index++;
            }
        }
        
        progressCallback.onProgress("说明：");
        progressCallback.onProgress("- 指令对话：只包含指令（如 /login），会独立处理，不发送给 AI");
        progressCallback.onProgress("- 普通对话：指令+实际内容（如 /login 请检查状态），会先执行指令，然后发送消息给 AI");
    }
    
    /**
     * 显示特定指令的帮助
     */
    private void showCommandHelp(String commandName, ProgressCallback progressCallback) {
        // 先检查是否是全局指令
        List<Command> globalCommands = commandParser != null ? commandParser.getGlobalCommands() : getDefaultGlobalCommands();
        for (Command cmd : globalCommands) {
            if (cmd.getName().equalsIgnoreCase(commandName)) {
                progressCallback.onProgress("指令: /" + cmd.getName());
                progressCallback.onProgress("");
                progressCallback.onProgress("功能: " + getCommandDescription(cmd));
                progressCallback.onProgress("");
                progressCallback.onProgress("用法:");
                progressCallback.onProgress("  " + cmd.getExample());
                progressCallback.onProgress("");
                progressCallback.onProgress("示例:");
                // 特殊处理 help 指令的详细示例
                if ("help".equalsIgnoreCase(commandName)) {
                    progressCallback.onProgress("  /help");
                    progressCallback.onProgress("  /help:指令名");
                // login 指令已移除，登录功能改为在管理后台统一操作
                // 账号管理指令已移除，账号管理功能改为在管理后台统一操作
                } else {
                    progressCallback.onProgress("  " + cmd.getExample());
                }
                return;
            }
        }
        
        // 检查是否是 provider 特定指令
        List<Command> providerCommands = commandParser != null ? commandParser.getProviderCommands() : null;
        if (providerCommands != null) {
            for (Command cmd : providerCommands) {
                if (cmd.getName().equalsIgnoreCase(commandName)) {
                    String cmdName = cmd.getName();
                    progressCallback.onProgress("指令: /" + cmdName);
                    progressCallback.onProgress("");
                    progressCallback.onProgress("功能: " + getCommandDescription(cmd));
                    progressCallback.onProgress("");
                    progressCallback.onProgress("用法:");
                    progressCallback.onProgress("  " + cmd.getExample());
                    progressCallback.onProgress("");
                    progressCallback.onProgress("示例:");
                    // 根据指令类型提供具体示例
                    switch (cmdName.toLowerCase()) {
                        case "attach-drive":
                        case "attach-google-drive":
                            progressCallback.onProgress("  /attach-drive:文档.docx");
                            progressCallback.onProgress("  /attach-drive 报告.pdf");
                            break;
                        case "attach-local":
                        case "attach-file":
                        case "attach-local-file":
                            progressCallback.onProgress("  /attach-local:C:\\Users\\用户名\\Documents\\file.txt");
                            progressCallback.onProgress("  /attach-local /path/to/file.pdf");
                            break;
                        default:
                            progressCallback.onProgress("  " + cmd.getExample());
                    }
                    return;
                }
            }
        }
        
        // 未知指令
        progressCallback.onProgress("未知指令: " + commandName);
        progressCallback.onProgress("");
        progressCallback.onProgress("使用 /help 查看所有支持的指令");
    }
    
    /**
     * 获取指令的描述（去除参数部分，只保留功能描述）
     */
    private String getCommandDescription(Command cmd) {
        String description = cmd.getDescription();
        // 如果描述包含参数（如 "添加 Google Drive 文件: dummy"），只保留功能部分
        if (description != null && description.contains(": ")) {
            int colonIndex = description.lastIndexOf(": ");
            if (colonIndex > 0) {
                return description.substring(0, colonIndex);
            }
        }
        return description != null ? description : cmd.getName();
    }
    
    /**
     * 获取默认的全局指令列表（当 commandParser 为 null 时使用）
     */
    private List<Command> getDefaultGlobalCommands() {
        List<Command> commands = new ArrayList<>();
        commands.add(new HelpCommand());
        // login 指令已移除，登录功能改为在管理后台统一操作
        return commands;
    }
}


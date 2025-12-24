package site.newbie.web.llm.api.provider.gemini.command;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置指令解析器
 * 解析用户消息中的内置指令，例如：
 * - /attach-drive:文件名
 * - /attach-local:文件路径
 */
@Slf4j
public class CommandParser {
    
    // 指令模式：/command:参数 或 /command 参数
    // 支持连字符和数字，例如：/attach-drive:文件名
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
        "/([\\w-]+)(?::([^\\s]+))?(?:\\s+(.+))?",
        Pattern.CASE_INSENSITIVE
    );
    
    /**
     * 判断是否是指令对话（只包含指令，没有其他内容）
     * @param message 用户消息
     * @return 是否是指令对话
     */
    public static boolean isCommandOnly(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        ParseResult result = parse(message);
        // 如果清理后的消息为空或只有空白，说明是指令对话
        return result.getCommands().size() > 0 && 
               (result.getCleanedMessage() == null || result.getCleanedMessage().trim().isEmpty());
    }
    
    /**
     * 解析消息中的指令
     * @param message 用户消息
     * @return 解析结果，包含清理后的消息和指令列表
     */
    public static ParseResult parse(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new ParseResult(message, List.of());
        }
        
        List<Command> commands = new ArrayList<>();
        StringBuilder cleanedMessage = new StringBuilder(message);
        
        // 查找所有指令
        Matcher matcher = COMMAND_PATTERN.matcher(message);
        List<CommandMatch> matches = new ArrayList<>();
        
        while (matcher.find()) {
            String commandName = matcher.group(1);
            String param = matcher.group(2);
            String extra = matcher.group(3);
            
            // 如果 param 为空，尝试从 extra 中提取
            if (param == null && extra != null) {
                // 尝试提取第一个参数（可能是文件名或路径）
                String[] parts = extra.split("\\s+", 2);
                if (parts.length > 0) {
                    param = parts[0];
                    extra = parts.length > 1 ? parts[1] : null;
                }
            }
            
            matches.add(new CommandMatch(
                matcher.start(),
                matcher.end(),
                commandName,
                param,
                extra
            ));
        }
        
        // 从后往前处理，避免索引偏移
        for (int i = matches.size() - 1; i >= 0; i--) {
            CommandMatch match = matches.get(i);
            Command command = createCommand(match.commandName, match.param, match.extra);
            if (command != null) {
                commands.add(command);
                // 从消息中移除指令
                cleanedMessage.delete(match.start, match.end);
            }
        }
        
        String finalMessage = cleanedMessage.toString().trim();
        
        log.info("解析指令: 原始消息长度={}, 清理后长度={}, 指令数={}", 
            message.length(), finalMessage.length(), commands.size());
        
        return new ParseResult(finalMessage, commands);
    }
    
    private static Command createCommand(String commandName, String param, String extra) {
        if (commandName == null) {
            return null;
        }
        
        commandName = commandName.toLowerCase();
        
        switch (commandName) {
            case "attach-drive":
            case "attach-google-drive":
                if (param != null && !param.trim().isEmpty()) {
                    return new AttachDriveCommand(param.trim());
                }
                log.warn("attach-drive 指令缺少文件名参数");
                return null;
                
            case "attach-local":
            case "attach-file":
                if (param != null && !param.trim().isEmpty()) {
                    return new AttachLocalFileCommand(param.trim());
                }
                log.warn("attach-local 指令缺少文件路径参数");
                return null;
                
            case "help":
                // help 指令可以带参数（查询特定指令的帮助），也可以不带参数（显示所有指令）
                if (param != null && !param.trim().isEmpty()) {
                    return new HelpCommand(param.trim());
                }
                return new HelpCommand();
                
            case "login":
                // login 指令不需要参数
                return new LoginCommand();
                
            default:
                log.debug("未知指令: {}", commandName);
                return null;
        }
    }
    
    /**
     * 指令匹配结果
     */
    private static class CommandMatch {
        final int start;
        final int end;
        final String commandName;
        final String param;
        final String extra;
        
        CommandMatch(int start, int end, String commandName, String param, String extra) {
            this.start = start;
            this.end = end;
            this.commandName = commandName;
            this.param = param;
            this.extra = extra;
        }
    }
    
    /**
     * 解析结果
     */
    public static class ParseResult {
        private final String cleanedMessage;
        private final List<Command> commands;
        
        public ParseResult(String cleanedMessage, List<Command> commands) {
            this.cleanedMessage = cleanedMessage;
            this.commands = commands;
        }
        
        public String getCleanedMessage() {
            return cleanedMessage;
        }
        
        public List<Command> getCommands() {
            return commands;
        }
        
        public boolean hasCommands() {
            return !commands.isEmpty();
        }
    }
}


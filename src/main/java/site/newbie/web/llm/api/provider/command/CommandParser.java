package site.newbie.web.llm.api.provider.command;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局指令解析器
 * 解析用户消息中的内置指令，支持全局指令和 provider 特定指令
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
     * Provider 特定指令创建器接口
     */
    @FunctionalInterface
    public interface ProviderCommandFactory {
        /**
         * 创建 provider 特定指令
         * @param commandName 指令名称
         * @param param 指令参数（冒号后的参数）
         * @param extra 额外参数（空格后的参数）
         * @return 指令实例，如果无法创建则返回 null
         */
        Command create(String commandName, String param, String extra);
    }
    
    // Provider 特定指令创建器（由 provider 注册）
    private final ProviderCommandFactory providerCommandFactory;
    
    /**
     * 创建全局指令解析器（只支持全局指令）
     */
    public CommandParser() {
        this(null);
    }
    
    /**
     * 创建支持 provider 特定指令的解析器
     * @param providerCommandFactory provider 特定指令的创建器
     */
    public CommandParser(ProviderCommandFactory providerCommandFactory) {
        this.providerCommandFactory = providerCommandFactory;
    }
    
    /**
     * 判断是否是指令对话（只包含指令，没有其他内容）
     * @param message 用户消息
     * @return 是否是指令对话
     */
    public boolean isCommandOnly(String message) {
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
    public ParseResult parse(String message) {
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
    
    /**
     * 创建指令实例
     * 优先创建全局指令，如果不存在则尝试创建 provider 特定指令
     */
    private Command createCommand(String commandName, String param, String extra) {
        if (commandName == null) {
            return null;
        }
        
        commandName = commandName.toLowerCase();
        
        // 先尝试创建全局指令
        Command globalCommand = createGlobalCommand(commandName, param, extra);
        if (globalCommand != null) {
            return globalCommand;
        }
        
        // 如果不是全局指令，尝试创建 provider 特定指令
        if (providerCommandFactory != null) {
            try {
                Command providerCommand = providerCommandFactory.create(commandName, param, extra);
                if (providerCommand != null) {
                    return providerCommand;
                }
            } catch (Exception e) {
                log.debug("创建 provider 特定指令失败: commandName={}, error={}", commandName, e.getMessage());
            }
        }
        
        log.debug("未知指令: {}", commandName);
        return null;
    }
    
    /**
     * 创建全局指令
     */
    private Command createGlobalCommand(String commandName, String param, String extra) {
        switch (commandName) {
            case "help":
                // help 指令可以带参数（查询特定指令的帮助），也可以不带参数（显示所有指令）
                // 传入 CommandParser 的引用，让 HelpCommand 可以动态获取 provider 特定指令
                if (param != null && !param.trim().isEmpty()) {
                    return new HelpCommand(param.trim(), this);
                }
                return new HelpCommand(null, this);
                
            case "login":
                // login 指令不需要参数
                return new LoginCommand();
                
            default:
                return null; // 不是全局指令
        }
    }
    
    /**
     * 获取所有全局指令的列表（用于帮助显示）
     * @return 全局指令列表
     */
    public List<Command> getGlobalCommands() {
        List<Command> commands = new ArrayList<>();
        // 获取所有已知的全局指令
        String[] globalCommandNames = {"help", "login"};
        
        for (String cmdName : globalCommandNames) {
            try {
                Command cmd = createGlobalCommand(cmdName, null, null);
                if (cmd != null) {
                    commands.add(cmd);
                }
            } catch (Exception e) {
                log.debug("无法创建全局指令用于帮助显示: commandName={}, error={}", cmdName, e.getMessage());
            }
        }
        
        return commands;
    }
    
    /**
     * 获取所有 provider 特定指令的列表（用于帮助显示）
     * 通过尝试创建已知的指令名称来发现可用的指令
     * @return provider 特定指令列表
     */
    public List<Command> getProviderCommands() {
        if (providerCommandFactory == null) {
            return List.of();
        }
        
        List<Command> commands = new ArrayList<>();
        // 常见的 provider 特定指令名称（可以根据实际情况扩展）
        // 注意：这里只尝试不带参数的创建，用于获取指令的基本信息
        String[] commonCommandNames = {
            "attach-drive", 
            "attach-google-drive",
            "attach-local", 
            "attach-file",
            "attach-local-file"
        };
        
        // 使用 Set 来去重（同一个指令可能有多个别名）
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        
        for (String cmdName : commonCommandNames) {
            try {
                // 尝试创建指令（使用虚拟参数，只用于获取指令信息）
                // 注意：某些指令可能需要参数，这里使用 "dummy" 作为占位符
                Command cmd = providerCommandFactory.create(cmdName, "dummy", null);
                if (cmd != null) {
                    String actualName = cmd.getName();
                    // 避免重复添加相同的指令
                    if (!seenNames.contains(actualName)) {
                        commands.add(cmd);
                        seenNames.add(actualName);
                    }
                }
            } catch (Exception e) {
                // 忽略创建失败的命令（可能该 provider 不支持此命令，或需要特定参数）
                log.debug("无法创建指令用于帮助显示: commandName={}, error={}", cmdName, e.getMessage());
            }
        }
        
        return commands;
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


package site.newbie.web.llm.api.provider.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CommandParser 工厂
 * 用于创建 CommandParser 实例
 */
@Slf4j
@Component
public class CommandParserFactory {
    
    public CommandParserFactory() {
    }
    
    /**
     * 创建全局 CommandParser
     */
    public CommandParser createGlobalParser() {
        return new CommandParser(null);
    }
    
    /**
     * 创建支持 provider 特定指令的 CommandParser
     */
    public CommandParser createParser(CommandParser.ProviderCommandFactory providerCommandFactory) {
        return new CommandParser(providerCommandFactory);
    }
}


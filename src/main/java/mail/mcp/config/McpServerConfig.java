package mail.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stdio MCP uses a single stdout stream. Keeping synchronous tool execution immediate prevents
 * concurrent boundedElastic tool completions from racing while enqueueing responses (MCP Java SDK
 * issue #686). A slow tool call therefore blocks later calls of the same session — long work such as
 * mailbox synchronization must run in the background and return at once (docs/tools-agent.md).
 *
 * <p>Copied from redmine-mcp-server {@code config/McpServerConfig} @ {@code f62b3e5}.
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    @Bean
    McpSyncServerCustomizer stdioSyncServerCustomizer() {
        return serverBuilder -> {
            log.info("Applying MCP sync server customization: immediateExecution=true");
            serverBuilder.immediateExecution(true);
        };
    }
}

package mail.mcp.config;

import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.convert.Pandoc;
import mail.mcp.sync.SyncService;
import mail.mcp.index.WordForms;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Background sync wiring; pandoc is checked only when a run is requested. */
@Configuration(proxyBeanMethods = false)
public class SyncConfig {
    @Bean
    public SyncService syncService(ConnectionRegistry registry, ObjectMapper mapper, MailMcpProperties properties) {
        return new SyncService(registry, Pandoc::locate, mapper,
                () -> WordForms.load(properties.resolvedWordFormsFile()));
    }
}

package mail.mcp.config;

import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wiring of the connection registry: the connections file is read once, at startup.
 *
 * <p>Modeled on jdbc-mcp-server {@code config/ConnectionsConfig} @ {@code c2c4362}; there are no
 * per-connection Spring child contexts here, a connection is a plain object graph.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailMcpProperties.class)
public class ConnectionsConfig {

    @Bean
    public MailConnectionFactory mailConnectionFactory() {
        return MailConnectionFactory.standard();
    }

    @Bean(destroyMethod = "close")
    public ConnectionRegistry connectionRegistry(MailMcpProperties properties, ObjectMapper mapper,
                                                 MailConnectionFactory factory) {
        return new ConnectionRegistry(ConnectionsLoader.load(properties, mapper, System::getenv), factory);
    }
}

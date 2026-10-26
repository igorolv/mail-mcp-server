package mail.mcp.config;

import mail.mcp.redaction.SecretRedactor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedactionConfig {
    @Bean
    SecretRedactor secretRedactor(MailMcpProperties properties) {
        return new SecretRedactor(properties.redactSecrets());
    }
}

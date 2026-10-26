package mail.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailMcpPropertiesTest {
    @Test void redactionSetting() {
        assertThat(MailMcpProperties.fromEnvironment(name -> null).redactSecrets()).isFalse();
        assertThat(new MailMcpProperties(null, null).redactSecrets()).isFalse();
        assertThat(MailMcpProperties.fromEnvironment(name -> name.equals(MailMcpProperties.REDACT_SECRETS_ENV)
                ? "true" : null).redactSecrets()).isTrue();
        assertThat(MailMcpProperties.fromEnvironment(name -> name.equals(MailMcpProperties.REDACT_SECRETS_ENV)
                ? "false" : null).redactSecrets()).isFalse();
        assertThatThrownBy(() -> MailMcpProperties.fromEnvironment(name -> name.equals(MailMcpProperties.REDACT_SECRETS_ENV)
                ? "invalid" : null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MAIL_MCP_REDACT_SECRETS must be true or false");
    }
}

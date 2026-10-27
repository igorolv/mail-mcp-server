package mail.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailMcpPropertiesTest {
    @Test void wordFormsFileSetting() {
        var defaults = MailMcpProperties.fromEnvironment(name -> null);
        assertThat(defaults.resolvedWordFormsFile()).isEqualTo(defaults.resolvedDataDir().resolve("word-forms.txt"));
        assertThat(new MailMcpProperties("data", null).resolvedWordFormsFile())
                .isEqualTo(java.nio.file.Path.of("data/word-forms.txt"));
        assertThat(new MailMcpProperties("data", null, true).resolvedWordFormsFile())
                .isEqualTo(java.nio.file.Path.of("data/word-forms.txt"));
        var configured = MailMcpProperties.fromEnvironment(name -> switch (name) {
            case MailMcpProperties.DATA_DIR_ENV -> "data";
            case MailMcpProperties.WORD_FORMS_FILE_ENV -> " rules/custom.txt ";
            default -> null;
        });
        assertThat(configured.resolvedWordFormsFile()).isEqualTo(java.nio.file.Path.of("rules/custom.txt"));
        assertThat(new MailMcpProperties("data", null, false, "  ").resolvedWordFormsFile())
                .isEqualTo(java.nio.file.Path.of("data/word-forms.txt"));
        assertThat(new MailMcpProperties("data", null, false, "~/rules.txt").resolvedWordFormsFile())
                .isEqualTo(java.nio.file.Path.of(System.getProperty("user.home"), "rules.txt"));
    }
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

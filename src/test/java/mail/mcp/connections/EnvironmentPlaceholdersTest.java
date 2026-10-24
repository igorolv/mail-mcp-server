package mail.mcp.connections;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentPlaceholdersTest {

    private static final UnaryOperator<String> ENV = Map.of("HOST", "webmail.example.com", "PORT", "443")::get;

    @Test
    void textWithoutPlaceholdersIsReturnedAsIs() {
        assertThat(EnvironmentPlaceholders.resolve("plain $ text {}", "f", ENV)).isEqualTo("plain $ text {}");
        assertThat(EnvironmentPlaceholders.resolve(null, "f", ENV)).isNull();
    }

    @Test
    void everyPlaceholderInAStringIsReplaced() {
        assertThat(EnvironmentPlaceholders.resolve("https://${HOST}:${ PORT }/EWS/Exchange.asmx", "f", ENV))
                .isEqualTo("https://webmail.example.com:443/EWS/Exchange.asmx");
    }

    @Test
    void resolvedValuesAreNotResolvedAgain() {
        UnaryOperator<String> env = Map.of("A", "${B}", "B", "secret")::get;

        assertThat(EnvironmentPlaceholders.resolve("${A}", "f", env)).isEqualTo("${B}");
    }

    @Test
    void unsetVariableNamesTheVariableAndTheField() {
        assertThatThrownBy(() -> EnvironmentPlaceholders.resolve("${NO_SUCH}", "connections.work.password", ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_SUCH")
                .hasMessageContaining("connections.work.password");
    }

    @Test
    void unterminatedAndEmptyPlaceholdersAreErrors() {
        assertThatThrownBy(() -> EnvironmentPlaceholders.resolve("${HOST", "f", ENV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unterminated");
        assertThatThrownBy(() -> EnvironmentPlaceholders.resolve("${ }", "f", ENV))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Empty");
    }
}

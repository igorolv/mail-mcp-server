package mail.mcp.convert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class PandocTest {
    @TempDir Path temp;

    public static boolean pandocAvailable() {
        try { Pandoc.locate(); return true; }
        catch (PandocException e) { return false; }
    }

    @Test void htmlBecomesMarkdownWithoutStylesOrScripts() {
        assumeTrue(pandocAvailable());
        assertThat(Pandoc.locate().htmlToMarkdown("""
                <html><head><style>PRIVATE_STYLE</style></head><body>
                <h1>Привет</h1><p><strong>Bold</strong></p><script>PRIVATE_SCRIPT</script></body></html>
                """))
                .contains("# Привет", "**Bold**").doesNotContain("PRIVATE_STYLE", "PRIVATE_SCRIPT", "\r");
    }

    @Test void sandboxRefusesLocalFiles() throws Exception {
        assumeTrue(pandocAvailable());
        var file = temp.resolve("private.txt");
        Files.writeString(file, "SYNTHETIC_LOCAL_FILE_CONTENT");
        assertThat(Pandoc.locate().htmlToMarkdown("<p>Visible</p><img src=\"" + file.toUri() + "\">"))
                .contains("Visible").doesNotContain("SYNTHETIC_LOCAL_FILE_CONTENT");
    }

    @Test void missingExecutableIsReported() {
        assertThatThrownBy(() -> new Pandoc("nonexistent-pandoc-it05").htmlToMarkdown("<p>private</p>"))
                .isInstanceOf(PandocException.class).hasMessage("pandoc I/O failed").hasNoCause();
    }
}

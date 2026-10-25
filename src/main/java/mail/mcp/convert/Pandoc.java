package mail.mcp.convert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A sandboxed pandoc process per document conversion; no timeout or retries. */
public final class Pandoc {
    private final String executable;

    public Pandoc(String executable) { this.executable = executable; }

    /** Checks that pandoc is on PATH before any sync work starts. */
    public static Pandoc locate() {
        try {
            var process = new ProcessBuilder("pandoc", "--version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            if (process.waitFor() == 0) { return new Pandoc("pandoc"); }
        } catch (IOException e) { /* fixed message below */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        throw new PandocException("pandoc not found on PATH");
    }

    public String htmlToMarkdown(String html) { return toMarkdown(html, "html"); }

    public String toMarkdown(String text, String format) { return convert(text, null, format); }

    public String toMarkdown(Path file, String format) { return convert(null, file, format); }

    private String convert(String text, Path file, String format) {
        try {
            var command = new ArrayList<>(List.of(executable, "--sandbox", "-f", format, "-t", "gfm",
                    "--wrap=none", "--eol=lf"));
            if (file != null) { command.add(file.toAbsolutePath().toString()); }
            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            IOException writeFailure = null;
            try (var input = process.getOutputStream()) {
                if (text != null) { input.write(text.getBytes(StandardCharsets.UTF_8)); }
            }
            catch (IOException e) { writeFailure = e; }
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0) { throw new PandocException("pandoc failed with exit code " + exit); }
            if (writeFailure != null) { throw new PandocException("pandoc I/O failed"); }
            return output;
        } catch (IOException e) { throw new PandocException("pandoc I/O failed"); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PandocException("pandoc interrupted");
        }
    }
}

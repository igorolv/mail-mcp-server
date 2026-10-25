package mail.mcp.convert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A sandboxed pandoc process per HTML conversion; no timeout or retries. */
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

    public String htmlToMarkdown(String html) {
        try {
            var process = new ProcessBuilder(executable, "--sandbox", "-f", "html", "-t", "gfm",
                    "--wrap=none", "--eol=lf").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            IOException writeFailure = null;
            try (var input = process.getOutputStream()) { input.write(html.getBytes(StandardCharsets.UTF_8)); }
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

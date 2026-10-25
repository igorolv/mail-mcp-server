package mail.mcp.extraction;

import java.util.Objects;

/** One thread-confined budget for an entire EML/MSG/TNEF/ZIP chain. Never reset on re-entry. */
public final class ExtractionContext {
    private final Budget budget;
    private final int containerDepth;

    public ExtractionContext(ExtractionLimits limits) {
        this(new Budget(Objects.requireNonNull(limits)), 0);
    }

    private ExtractionContext(Budget budget, int containerDepth) {
        this.budget = budget;
        this.containerDepth = containerDepth;
    }

    public ExtractionLimits limits() { return budget.limits; }
    public int containerDepth() { return containerDepth; }

    public ExtractionContext child() {
        checkTime();
        if (containerDepth >= limits().maxContainerDepth()) fail("container-depth-limit");
        return new ExtractionContext(budget, containerDepth + 1);
    }

    public void checkTime() {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - budget.started >= budget.duration) {
            fail("time-limit");
        }
    }

    public void part(int mimeDepth) {
        checkTime();
        if (mimeDepth > limits().maxMimeDepth()) fail("mime-depth-limit");
        if (budget.parts >= limits().maxParts()) fail("part-count-limit");
        budget.parts++;
    }

    public void header(int entityCount, long bytes) {
        checkTime();
        if (entityCount > limits().maxHeadersPerEntity() || budget.headers >= limits().maxHeaders()) {
            fail("header-count-limit");
        }
        if (bytes > limits().maxHeaderBytes() - budget.headerBytes) fail("header-bytes-limit");
        budget.headers++;
        budget.headerBytes += bytes;
    }

    public void file() {
        checkTime();
        if (budget.files >= limits().maxFiles()) fail("file-count-limit");
        budget.files++;
    }

    /** Charge actual decoded/expanded bytes before writing or allocating them. */
    public void derivedBytes(long bytes) {
        checkTime();
        if (bytes < 0) throw new IllegalArgumentException("Negative byte count");
        if (bytes > limits().maxDerivedBytes() - budget.derivedBytes) fail("derived-bytes-limit");
        budget.derivedBytes += bytes;
    }

    public void textChars(int chars) {
        checkTime();
        if (chars < 0) throw new IllegalArgumentException("Negative character count");
        if (chars > limits().maxTextChars() - budget.textChars) fail("text-limit");
        budget.textChars += chars;
    }

    public Usage usage() {
        return new Usage(budget.parts, budget.headers, budget.headerBytes, budget.files,
                budget.derivedBytes, budget.textChars);
    }

    public record Usage(int parts, int headers, long headerBytes, int files, long derivedBytes, int textChars) { }

    private static void fail(String code) { throw new ExtractionLimitException(code); }

    private static final class Budget {
        final ExtractionLimits limits;
        final long started = System.nanoTime();
        final long duration;
        int parts, headers, files, textChars;
        long headerBytes, derivedBytes;

        Budget(ExtractionLimits limits) {
            this.limits = limits;
            duration = limits.maxDuration().toNanos();
        }
    }
}

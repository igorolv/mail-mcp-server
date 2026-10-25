package mail.mcp.extraction;

import java.time.Duration;

/** Initial offline limits, supplied by the caller rather than mailbox configuration policy. */
public record ExtractionLimits(long maxInputBytes, long maxPartBytes, long maxDerivedBytes,
                               int maxParts, int maxHeadersPerEntity, int maxHeaders,
                               long maxHeaderBytes, int maxHeaderLength, int maxMimeDepth,
                               int maxContainerDepth, int maxFiles, int maxTextChars,
                               int maxHtmlBytes, int maxHtmlNodes, Duration maxDuration) {
    public ExtractionLimits {
        if (maxInputBytes < 1 || maxPartBytes < 1 || maxDerivedBytes < 1 || maxParts < 1
                || maxHeadersPerEntity < 1 || maxHeaders < 1 || maxHeaderBytes < 1
                || maxHeaderLength < 1 || maxMimeDepth < 1 || maxContainerDepth < 0
                || maxFiles < 1 || maxTextChars < 1 || maxHtmlBytes < 1 || maxHtmlNodes < 1
                || maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                || maxDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid extraction limits");
        }
    }

    public static ExtractionLimits defaults() {
        return new ExtractionLimits(50L << 20, 25L << 20, 100L << 20, 512, 200, 4096,
                1L << 20, 64 << 10, 32, 5, 256, 200_000, 2 << 20, 100_000,
                Duration.ofSeconds(30));
    }
}

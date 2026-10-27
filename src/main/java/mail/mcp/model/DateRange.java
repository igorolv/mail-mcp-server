package mail.mcp.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Inclusive lower and exclusive upper MIME header date bounds. */
public record DateRange(Instant since, Instant until) {
    public static DateRange parse(String since, String until) {
        Instant lower = bound(since), upper = bound(until);
        if (lower != null && upper != null && !lower.isBefore(upper)) {
            throw new IllegalArgumentException("since must be before until");
        }
        return new DateRange(lower, upper);
    }

    private static Instant bound(String value) {
        if (value == null) { return null; }
        try {
            if (value.matches(".*[.,][0-9]{4,}.*")) { throw new IllegalArgumentException("sub-millisecond date"); }
            Instant result = value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : OffsetDateTime.parse(value).toInstant();
            if (result.getNano() % 1_000_000 != 0) { throw new IllegalArgumentException("sub-millisecond date"); }
            result.toEpochMilli();
            return result;
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid date bound: use YYYY-MM-DD or an ISO-8601 datetime with offset", e);
        }
    }

    public boolean present() { return since != null || until != null; }
}

package mail.mcp.model;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class DateRangeTest {
 @Test void parsesDatesAsUtcAndNormalizesExplicitOffsets() {
  var r = DateRange.parse("2026-10-26", "2026-10-28T03:00:00+03:00");
  assertThat(r.since()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
  assertThat(r.until()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
 }
 @Test void rejectsInvalidBlankUnzonedReversedAndOverpreciseBounds() {
  for (String s : new String[]{"", " ", "2026-02-30", "2026-10-26T00:00:00", "2026-10-26T00:00:00.0001Z", "2026-10-26T00:00:00.0000Z", "+999999999-01-01T00:00:00Z"})
   assertThatThrownBy(() -> DateRange.parse(s, null)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> DateRange.parse("2026-10-26", "2026-10-26")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(() -> DateRange.parse("2026-10-27", "2026-10-26")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void handlesEpochMillisecondExtremesWithoutOverflow() {
  for (long n : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
   String value = Instant.ofEpochMilli(n).toString();
   assertThat(DateRange.parse(value, null).since().toEpochMilli()).isEqualTo(n);
   assertThat(DateRange.parse(null, value).until().toEpochMilli()).isEqualTo(n);
  }
 }
}

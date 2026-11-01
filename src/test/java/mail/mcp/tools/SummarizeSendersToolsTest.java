package mail.mcp.tools;

import mail.mcp.api.SenderFolderInfo;
import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.index.MailIndex;
import mail.mcp.model.Address;
import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummarizeSendersToolsTest {
    private static final Instant MAY = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant JUNE = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant JULY = Instant.parse("2026-07-01T00:00:00Z");

    @TempDir Path dir;
    private int nextKey;

    private SearchTools tools() {
        return new SearchTools(new ConnectionRegistry(List.of(
                new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir.resolve("local"), "unconfigured")),
                definition -> { throw new AssertionError("no source construction"); }), new JsonConfig().mailMcpObjectMapper());
    }

    private void put(MailIndex writer, String folder, List<Address> from, Instant date, String subject, String id) {
        String key = new UUID(0, ++nextKey).toString();
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage(subject, from, null, null, date, id, null, null, null), "Body", null);
    }

    private static List<Address> from(String name, String address) { return List.of(new Address(name, address)); }

    @Test void countsSendersWithDatesFoldersAndCoverage() {
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(writer, "f1", from("Shop", "News@Shop.example.com"), MAY, "Sale", "<1>");
            put(writer, "f2", from(null, "news@shop.example.com"), JULY, "Receipt", "<2>");
            put(writer, "f1", from("Help", "help@shop.example.com"), JUNE, "Ticket", "<3>");
            put(writer, "f1", from("Doe, Jane", "jane@other.example.org"), null, "Hello", "<4>");
            writer.put(new MessageMeta(new UUID(0, 99).toString(), "f1", "INBOX", "failed", 0, null, Instant.now(), MessageState.FAILED, "broken"),
                    null, null, null);
            writer.commit();
        }
        var result = tools().summarizeSenders("local", null, null, null, null, null, null, null, null);
        assertThat(result.groupBy()).isEqualTo("address");
        assertThat(result.field()).isEqualTo("any");
        assertThat(result.messages()).isEqualTo(5);
        assertThat(result.withoutSender()).isEqualTo(1);
        assertThat(result.undated()).isEqualTo(2);
        assertThat(result.earliest()).isEqualTo(MAY);
        assertThat(result.latest()).isEqualTo(JULY);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.nextOffset()).isNull();
        var top = result.senders().getFirst();
        assertThat(top.sender()).isEqualTo("news@shop.example.com");
        assertThat(top.messages()).isEqualTo(2);
        assertThat(top.earliest()).isEqualTo(MAY);
        assertThat(top.latest()).isEqualTo(JULY);
        assertThat(top.folders()).containsExactly(new SenderFolderInfo("f1", 1), new SenderFolderInfo("f2", 1));
        assertThat(result.senders().get(1).sender()).isEqualTo("help@shop.example.com");
        var undated = result.senders().get(2);
        assertThat(undated.sender()).isEqualTo("jane@other.example.org");
        assertThat(undated.earliest()).isNull();

        var domains = tools().summarizeSenders("local", "domain", null, null, null, null, null, null, null);
        assertThat(domains.groupBy()).isEqualTo("domain");
        assertThat(domains.senders().getFirst().sender()).isEqualTo("shop.example.com");
        assertThat(domains.senders().getFirst().messages()).isEqualTo(3);
        assertThat(domains.senders().getFirst().folders())
                .containsExactly(new SenderFolderInfo("f1", 2), new SenderFolderInfo("f2", 1));
    }

    @Test void copiesCountOnceAsMessagesAndInEveryFolder() {
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(writer, "f1", from(null, "user@example.com"), MAY, "Same", "<copy>");
            put(writer, "f2", from(null, "user@example.com"), MAY, "Same", "<copy>");
            writer.commit();
        }
        var result = tools().summarizeSenders("local", null, null, null, null, null, null, null, null);
        assertThat(result.messages()).isEqualTo(1);
        assertThat(result.senders().getFirst().messages()).isEqualTo(1);
        assertThat(result.senders().getFirst().folders())
                .containsExactly(new SenderFolderInfo("f1", 1), new SenderFolderInfo("f2", 1));
    }

    @Test void severalSendersAndUnparsableValuesAreCountedSeparately() {
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(writer, "f1", List.of(new Address("A", "a@example.com"), new Address("B", "b@example.com")), MAY, "Two", "<1>");
            put(writer, "f1", List.of(new Address(null, "a@example.com"), new Address(null, "b@example.com")), MAY, "Bare", "<2>");
            put(writer, "f1", from("No address", "not-an-address"), MAY, "Odd", "<3>");
            put(writer, "f1", from("a@example.com", "real@example.com"), MAY, "Name like address", "<4>");
            writer.commit();
        }
        var result = tools().summarizeSenders("local", null, null, null, null, null, null, null, null);
        assertThat(result.messages()).isEqualTo(4);
        assertThat(result.withoutSender()).isEqualTo(3);
        assertThat(result.senders()).singleElement().satisfies(s -> assertThat(s.sender()).isEqualTo("real@example.com"));
    }

    @Test void filtersMatchSearchAndPagesCountSenders() {
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(writer, "f1", from(null, "a@example.com"), MAY, "Receipt", "<1>");
            put(writer, "f1", from(null, "a@example.com"), JULY, "Receipt", "<2>");
            put(writer, "f2", from(null, "b@example.com"), JUNE, "Receipt", "<3>");
            put(writer, "f2", from(null, "c@example.com"), JUNE, "Newsletter", "<4>");
            writer.commit();
        }
        var tools = tools();
        var subject = tools.summarizeSenders("local", null, "receipt", "subject", null, null, null, null, null);
        assertThat(subject.query()).isEqualTo("receipt");
        assertThat(subject.field()).isEqualTo("subject");
        assertThat(subject.messages()).isEqualTo(3);
        assertThat(subject.senders()).extracting(s -> s.sender()).containsExactly("a@example.com", "b@example.com");

        var address = tools.summarizeSenders("local", null, "a@example.com", "from", null, null, null, null, null);
        assertThat(address.messages()).isEqualTo(2);

        var period = tools.summarizeSenders("local", null, null, null, null, "2026-06-01", "2026-07-01", null, null);
        assertThat(period.messages()).isEqualTo(2);
        assertThat(period.since()).isEqualTo(JUNE);
        assertThat(period.until()).isEqualTo(JULY);

        var first = tools.summarizeSenders("local", null, null, null, null, null, null, 0, 2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.messages()).isEqualTo(4);
        assertThat(first.senders()).hasSize(2);
        assertThat(first.nextOffset()).isEqualTo(2);
        var last = tools.summarizeSenders("local", null, null, null, null, null, null, first.nextOffset(), 2);
        assertThat(last.senders()).extracting(s -> s.sender()).containsExactly("c@example.com");
        assertThat(last.nextOffset()).isNull();
        var beyond = tools.summarizeSenders("local", null, null, null, null, null, null, Integer.MAX_VALUE, 1);
        assertThat(beyond.senders()).isEmpty();
        assertThat(beyond.nextOffset()).isNull();
        assertThat(beyond.messages()).isEqualTo(4);
    }

    @Test void missingIndexGivesZeroAndInvalidArgumentsFail() {
        var tools = tools();
        var empty = tools.summarizeSenders("local", null, null, null, null, null, null, null, null);
        assertThat(empty.messages()).isZero();
        assertThat(empty.senders()).isEmpty();
        assertThatThrownBy(() -> tools.summarizeSenders("local", "subject", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupBy");
        assertThatThrownBy(() -> tools.summarizeSenders("local", null, "x".repeat(1001), null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query");
        assertThatThrownBy(() -> tools.summarizeSenders("local", null, null, null, null, null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("offset");
        assertThatThrownBy(() -> tools.summarizeSenders("local", null, null, null, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> tools.summarizeSenders("local", null, null, null, "f9", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("folder");
        assertThatThrownBy(() -> tools.summarizeSenders("missing", null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connection");
    }
}

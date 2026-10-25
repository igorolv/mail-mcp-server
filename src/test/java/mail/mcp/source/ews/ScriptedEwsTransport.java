package mail.mcp.source.ews;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/** Sequential synthetic responses, with operation assertions and complete request recording. */
final class ScriptedEwsTransport implements EwsTransport {
    private record Reply(EwsOperation operation, String fixture, EwsException failure) { }
    private final Queue<Reply> replies = new ArrayDeque<>();
    final List<String> requests = new ArrayList<>();
    final List<EwsOperation> operations = new ArrayList<>();
    boolean closed;

    ScriptedEwsTransport answer(EwsOperation operation, String fixture) {
        replies.add(new Reply(operation, fixture, null));
        return this;
    }

    ScriptedEwsTransport fail(EwsOperation operation, EwsException failure) {
        replies.add(new Reply(operation, null, failure));
        return this;
    }

    @Override public <T> T exchange(EwsOperation operation, byte[] envelope, ResponseHandler<T> handler) {
        requests.add(new String(envelope, StandardCharsets.UTF_8));
        operations.add(operation);
        assertThat(closed).as("transport is open").isFalse();
        assertThat(replies).as("queued response for %s", operation).isNotEmpty();
        var reply = replies.remove();
        assertThat(operation).isEqualTo(reply.operation());
        if (reply.failure() != null) {
            throw reply.failure();
        }
        try (var in = EwsResponseReaderTest.fixture(reply.fixture())) {
            return handler.handle(in);
        } catch (IOException | XMLStreamException e) {
            throw new AssertionError("Cannot read synthetic response", e);
        }
    }

    void assertDrained() { assertThat(replies).isEmpty(); }
    @Override public int requestCount() { return requests.size(); }
    @Override public void close() { closed = true; }
}

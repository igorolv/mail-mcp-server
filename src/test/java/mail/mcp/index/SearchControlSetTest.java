package mail.mcp.index;

import mail.mcp.config.JsonConfig;
import mail.mcp.convert.Pandoc;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SearchControlSetTest {
    @TempDir Path dir;

    @Test void controlSetMeetsBaseline() throws Exception {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        var store = new MessageStore(dir, mapper);
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7")));
        var processor = new MessageProcessor(store, new Pandoc("unused"));
        Map<String, String> ids = new HashMap<>();
        var corpus = mapper.readTree(Path.of("testdata/search/corpus.json").toFile()).get("messages");
        assertThat(corpus.size()).isEqualTo(48);
        try (var index = MailIndex.openWriter(dir)) {
            for (var entry : corpus) {
                String file = entry.get("file").asString();
                String key = store.newKey();
                // The corpus filename, rather than the message headers, maps hits to S… IDs.
                ids.put(key, Path.of(file).getFileName().toString().split("-")[0]);
                Files.copy(Path.of("testdata/search").resolve(file),
                        store.createMessageDir(key).resolve(MessageStore.MESSAGE_FILE));
                var meta = new MessageMeta(key, "f1", "INBOX", file, 0, null, Instant.now(), MessageState.DOWNLOADED, null);
                store.writeMeta(meta);
                var processed = processor.process(meta);
                assertThat(processed.meta().state()).as(file).isEqualTo(MessageState.CONVERTED);
                index.put(processed.meta(), processed.message(), processed.body());
            }
            index.commit();
        }
        var queries = mapper.readTree(Path.of("testdata/search/queries.json").toFile()).get("queries");
        assertThat(queries.size()).isEqualTo(80);
        // Counters: expected matches found, expected matches, forbidden matches found.
        Map<String, int[]> categories = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            for (var query : queries) {
                String id = query.get("id").asString();
                String field = query.get("field").asString();
                // Q061 exercises attachment text, deliberately unavailable until IT-08.
                Set<String> found = "attachmentText".equals(field) ? Set.of()
                        : reader.search(query.get("query").asString(), SearchField.of(field), null, 0, 50)
                        .hits().stream().map(hit -> ids.get(hit.card().key())).collect(Collectors.toSet());
                int[] stats = categories.computeIfAbsent(query.get("category").asString(), name -> new int[3]);
                for (var expected : query.get("expected")) {
                    stats[1]++;
                    if (found.contains(expected.asString())) { stats[0]++; }
                    else { misses.add(id + ":" + expected.asString()); }
                }
                for (var excluded : query.get("mustNotMatch")) {
                    if (found.contains(excluded.asString())) {
                        stats[2]++;
                        wrong.add(id + ":" + excluded.asString());
                    }
                }
            }
        }
        int[] totals = new int[3];
        categories.forEach((category, stats) -> {
            System.out.printf("%s: %d/%d, false positives %d%n", category, stats[0], stats[1], stats[2]);
            for (int i = 0; i < totals.length; i++) { totals[i] += stats[i]; }
        });
        double recall = (double) totals[0] / totals[1];
        System.out.printf(Locale.ROOT, "TOTAL: recall %d/%d = %.6f, false positives %d%n", totals[0], totals[1], recall, totals[2]);
        System.out.println("MISSES: " + misses);
        System.out.println("FALSE POSITIVES: " + wrong);
        assertThat(recall).isGreaterThanOrEqualTo(0.90);
        assertThat(totals[2]).isLessThanOrEqualTo(7);
    }
}

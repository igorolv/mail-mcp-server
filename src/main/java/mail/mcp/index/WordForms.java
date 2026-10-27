package mail.mcp.index;

import org.tartarus.snowball.ext.RussianStemmer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Optional user rules, normalized before Russian stemming; no built-in dictionary. */
public record WordForms(Map<String, String> mapping, Map<String, Integer> sources) {
    private static final WordForms NONE = new WordForms(Map.of(), Map.of());

    public WordForms {
        mapping = Map.copyOf(mapping);
        sources = Map.copyOf(sources);
    }

    public static WordForms none() { return NONE; }
    public boolean empty() { return mapping.isEmpty(); }
    public Integer source(String normalizedWord) { return sources.get(normalizedWord); }

    public static WordForms load(Path file) {
        final String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); }
        catch (NoSuchFileException e) { return none(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        String[] lines = (text.startsWith("\uFEFF") ? text.substring(1) : text).split("\\R", -1);
        Map<String, String> mapping = new HashMap<>();
        Map<String, Integer> sources = new HashMap<>();
        var stemmer = new RussianStemmer();
        for (int i = 0; i < lines.length; i++) {
            int line = i + 1;
            String content = lines[i].split("#", 2)[0].replaceAll("(?U)^\\s+|\\s+$", "");
            if (content.isBlank()) { continue; }
            String[] words = content.toLowerCase(Locale.ROOT).replace('ё', 'е').split("(?U)\\s+");
            for (String word : words) {
                if (!word.matches("\\p{L}+")) {
                    throw error(file, line, "words must contain letters only: " + word);
                }
            }
            if (java.util.Arrays.stream(words).distinct().count() < 2) {
                throw error(file, line, "a group must contain at least two distinct words");
            }
            stemmer.setCurrent(words[0]);
            stemmer.stem();
            String stem = stemmer.getCurrent();
            for (String word : words) {
                Integer previous = sources.putIfAbsent(word, line);
                if (previous != null && previous != line) {
                    throw error(file, line, "word " + word + " is already listed on line " + previous);
                }
                mapping.put(word, stem);
            }
        }
        return mapping.isEmpty() ? none() : new WordForms(mapping, sources);
    }

    public String hash() {
        if (empty()) { return null; }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(mapping).forEach((word, stem) ->
                    digest.update((word + "\t" + stem + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static IllegalArgumentException error(Path file, int line, String reason) {
        return new IllegalArgumentException(file.getFileName() + " line " + line + ": " + reason);
    }
}

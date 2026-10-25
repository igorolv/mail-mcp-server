package mail.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;

/** Message lifecycle on disk; written to meta.json and the index in lowercase. */
public enum MessageState {
    @JsonProperty("downloaded") DOWNLOADED,
    @JsonProperty("converted") CONVERTED,
    @JsonProperty("failed") FAILED;

    /** The lowercase value used in meta.json and the index. */
    public String id() { return name().toLowerCase(Locale.ROOT); }
}

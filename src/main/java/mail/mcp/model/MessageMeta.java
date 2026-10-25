package mail.mcp.model;

import java.time.Instant;

/**
 * Contents of meta.json: what the .eml does not say about a message.
 * size and sha256: of message.eml; 0 and null when the download failed.
 * error: why the state is failed, otherwise null.
 */
public record MessageMeta(String key, String folderKey, String folder, String ref, long size,
                          String sha256, Instant downloadedAt, MessageState state, String error) { }

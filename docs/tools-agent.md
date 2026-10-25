# docs/tools-agent.md — MCP tools and background synchronization

IT-06 completes plan.md 7.4. Tools are Spring services returning `api/` records, never `model/`
records. Only JSON-RPC is written to stdout. Failures are logged through `ToolLogger.failed`
and rethrown; `IllegalArgumentException` becomes an MCP tool error with its message.

## Tools

| Tool | Arguments | Result and errors |
|---|---|---|
| `listConnections` | None | Configured mailboxes in configuration order, capabilities and configuration errors; no credentials or source access. |
| `sync` | Required `connection` | Immediate `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused`, with explanation. Unknown/missing connection or unusable definition raises `IllegalArgumentException`. Missing pandoc raises `IllegalStateException`. |
| `syncStatus` | Required `connection` | Current/last local run, per-folder progress, summed downloaded/failed/removed counts, timestamps, error and `signInRejected`. No file means `never`. |
| `listFolders` | Required `connection` | Stored folder keys and paths, committed catalog counts and `synced` (cursor exists), in stored order. No index means zero counts. |
| `listMessages` | Required `connection`, `folder`; optional `offset`, `limit` | Folder cards sorted by descending date (undated last), then key; total, offset, optional nextOffset. Offset defaults to 0; limit to 50, capped at 200. Unknown folder is an error. |
| `getMessage` | Required `connection`, `key`; optional `offset`, `maxChars` | Metadata, MIME headers, body.md chunk, bodyLength, optional nextOffset and attachment file paths. Offset defaults to 0; maxChars to 20,000, capped at 100,000. Invalid/nonexistent UUID is an error. |

Offsets must be nonnegative and sizes positive; invalid values raise `IllegalArgumentException`.
Offsets at or beyond the end yield an empty page/chunk with no nextOffset. Body offsets and
lengths count Java string characters (UTF-16 code units). Missing body.md means absent body and
zero bodyLength. Header parsing failure leaves headers absent but still returns metadata and
saved body. Attachment paths are absent for missing files. Null fields are omitted from JSON.

Every message response includes an absolute `localPath` to its directory. An agent with local
file access may read those files directly. All reading tools use local data only, without
creating a source, acquiring the writer lock, checking pandoc or accessing the network.
Coverage can be incomplete during synchronization: check `listFolders.synced` for the first
completed folder pass and `syncStatus` for current progress. A committed catalog can lag files
being processed. `MailIndexReader` opens the latest commit once per call.

All tools advertise read-only and non-destructive hints. `sync` is not idempotent. Search,
attachment text, MCP resources, mail flags and mail writing are not implemented here.

## Status file and writer ownership

`sync-status.json` is atomically replaced by `SyncRunner`, which runs only while its caller holds
the connection's Lucene writer lock. Fields: `state` (running/completed/failed), `pid`,
`startedAt`, optional `finishedAt`, ordered `folders`, optional `error`, `signInRejected`.
Each folder entry contains `path`, cumulative `downloaded`, `failed`, `removed`, `reset`, `done`.
Progress replaces an entry without changing its first-appearance order. Status is written before
reconciliation, after each progress report, and on completion/failure. Run errors use fixed
`SyncErrors` descriptions; a final status-write failure is suppressed onto the original error.

Readers in any process can inspect the file. If it says running but its pid is no longer alive
and this process is not running that connection, `syncStatus` reports `interrupted` without
rewriting the file. A live pid is treated as running; there is no timeout or retry policy.

An IMAP authentication failure or EWS authentication exception sets `signInRejected=true`.
Every MCP process then refuses further starts before looking for pandoc or opening a source.
The user fixes the local connection configuration and runs `mail-sync <connection>` in a terminal;
a successful run clears the flag. Restart the MCP server as well if its startup configuration
has changed. Tools do not automatically retry a rejected sign-in.

`SyncService.start` checks the definition, in-process running map, rejected status and pandoc,
then opens the Lucene writer before constructing a lazy source. A competing writer returns
`lockedByOtherProcess` without opening a source or changing status. One daemon platform thread
named `sync-<connection>` owns the source and index for its run; it closes both, logs failures by
exception class only and removes itself from the running map. There is no scheduler or queue.
The `SyncTools` Spring service uses bean name `mailSyncTools` because Spring AI defines its own
`syncTools` bean. `SyncConfig` supplies `SyncService` with `Pandoc::locate`.

# docs/tools-agent.md — MCP tools and background synchronization

Eight tools (plan.md 3.7). Tools are Spring services returning `api/` records, never `model/`
records.
`MAIL_MCP_REDACT_SECRETS=true` optionally filters response text; see `docs/redaction-agent.md`.
Downloads, files, index, lookup IDs, stored filenames and original local paths stay unchanged.
Only JSON-RPC is written to stdout. Failures are logged through `ToolLogger.failed`
and rethrown; `IllegalArgumentException` becomes an MCP tool error with its message.

## Tools

| Tool | Arguments | Result and errors |
|---|---|---|
| `listConnections` | None | Configured mailboxes in configuration order, capabilities and configuration errors; no credentials or source access. |
| `sync` | Required `connection` | Immediate `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused`, with explanation. Unknown/missing connection or unusable definition raises `IllegalArgumentException`. Missing pandoc raises `IllegalStateException`. |
| `syncStatus` | Required `connection` | Current/last local run, per-folder progress, summed downloaded/failed/removed counts, timestamps, error and `signInRejected`. No file means `never`. |
| `listFolders` | Required `connection` | Stored folder keys and paths, committed catalog counts and `synced` (cursor exists), in stored order. No index means zero counts. |
| `listMessages` | Required `connection`, `folder`; optional `offset`, `limit` | Folder cards sorted by descending date (undated last), then key; total, offset, optional nextOffset. Offset defaults to 0; limit to 50, capped at 200. Unknown folder is an error. |
| `getMessage` | Required `connection`, `key`; optional `offset`, `maxChars` | Metadata, MIME headers, body.md chunk, bodyLength, optional nextOffset and attachment file paths and optional Markdown `textPath`. Offset defaults to 0; maxChars to 20,000, capped at 100,000. Invalid/nonexistent UUID is an error. |
| `getAttachment` | Required `connection`, `key`, `file`; optional `offset`, `maxChars` | Original file path and saved Markdown chunk, textPath, textLength and optional nextOffset. `file` must exactly match a stored filename in that message's expanded attachment list, including files inside EML, ZIP, MSG and TNEF containers. Unknown file (including traversal or another message's filename) is an error. Offset defaults to 0; maxChars to 20,000, capped at 100,000. |
| `search` | Required `connection`, `query`; optional `field`, `folder`, `offset`, `limit` | Local cards with BM25 score and body or saved attachment snippet, sorted by score descending then key. Exact total, offset and optional nextOffset. Query must be nonblank and at most 1,000 characters. Offset defaults to 0; limit to 20, capped at 100. Unknown field/folder is an error. No index means zero hits. |

Offsets must be nonnegative and sizes positive; invalid values raise `IllegalArgumentException`.
Offsets at or beyond the end yield an empty page/chunk with no nextOffset. Body and attachment text offsets
and lengths count Java string characters (UTF-16 code units). Missing body.md means absent body
and zero bodyLength. Header parsing failure leaves headers absent but still returns metadata and
saved body. Attachment paths are absent for missing files. `AttachmentRef.textPath` points to `<file>.md`
when it exists. `getAttachment` returns `key`, `file`, original `name`, `contentType`, decoded
`size`, `path`, `textPath`, `text`, `offset`, `nextOffset`, `textLength`. Without Markdown,
`text` and `textPath` are absent, textLength is zero and nextOffset is absent. Null fields are
omitted from JSON.

`getMessage` includes files inside saved EML, ZIP, MSG and TNEF containers after the MIME
attachments, expanded breadth first with one flat numbering. `AttachmentRef.parent` is the
containing attachment's exact stored `file`, absent for the message's own attachments. It is
a lookup filename and remains unchanged by secret filtering. `getAttachment` accepts the
entries' `file` values directly. The list is rebuilt without writes; before `mail-reprocess`,
entries may be listed without `path` or `textPath`. Missing containers yield no entries.
The `parent` contract is unchanged for MSG/TNEF children, including derived standalone embedded
MSG files. Finish offline reprocessing of a connection before attachment lookup/search after
this upgrade: new containers can renumber later extracted files. `mail-reindex` alone is
insufficient to create children/Markdown; original MIME is preserved.

With secret filtering enabled, full body/attachment text is filtered in memory before slicing;
offsets and lengths refer to the filtered view. Headers, display names, descriptions, status text
and folder paths are also filtered. Local file paths and attachment `file` values remain original.

Attachment text is available for DOCX, ODT, RTF, EPUB, HTML/HTM, CSV, PDF, DOC, XLS, PPT, XLSX, PPTX, EML, MSG, TNEF and plain
text up to 20 MiB of decoded bytes, when conversion yields nonblank text. `path` is the original
attachment file and `textPath` is its UTF-8 Markdown; the tool never converts on demand.
Unsupported, oversized or failed attachments retain their original files. See `docs/mime-agent.md`.
Use offline `mail-reprocess <connection>` to create Markdown for messages already on disk,
with no sync running.
Binary DOC/XLS/PPT support targets Office 97-2003 and also applies to files inside containers.
It uses the same saved-text lookup and snippet attribution; legacy text is never converted
on demand. Encrypted or malformed documents retain their file without Markdown.
One reprocessing pass creates both container children and legacy Office text; `mail-reindex`
alone cannot create that text. Pending real-data checks are tracked in plan.md 2.4.

Every message response includes an absolute `localPath` to its directory. An agent with local
file access may read those files directly. All reading tools use local data only, without
creating a source, acquiring the writer lock, checking pandoc or accessing the network.
Coverage can be incomplete during synchronization: check `listFolders.synced` for the first
completed folder pass and `syncStatus` for current progress. A committed catalog can lag files
being processed. `MailIndexReader` opens the latest commit once per call.

All tools advertise read-only and non-destructive hints. `sync` is not idempotent.
MCP resources, mail flags and mail writing are not implemented here.

## Search

`SearchTools.search` returns `SearchResult(connection, query, field, folder, total, offset,
nextOffset, hits)`. Each `SearchHitInfo` includes key, folder key, date, displayed from, subject,
hasAttachments, state, score, snippet and the absolute message directory `localPath`.
Snippets use Markdown `**matches**` and `… ` between passages, at most one passage per hit.
For `any`, a real body highlight wins; otherwise the first attachment with a highlightable query
clause supplies the passage. `attachmentText` selects attachments first, even when the body
contains the same words. Other field groups retain body snippet/context behavior.
`snippetAttachmentFile` is the exact `AttachmentInfo.file()` without the sidecar suffix, usable
directly as `getAttachment(connection, key, file, ...)`. `snippetAttachmentName` is the original
display name, falling back to `file` when null/blank, with optional secret filtering. The lookup
filename is unchanged. Both fields are optional and omitted for body or absent snippets.

Only eligible returned page hits read local `message.eml` once with `MimeReader.read(eml, null)`,
expand saved containers, then read UTF-8 `attachments/<file>.md` one at a time in expanded list
order. The first matching attachment supplies the snippet. No conversion or writes occur.
An original `.md` attachment uses its `.md.md` sidecar. A missing binary does not block a sidecar.
Missing/broken MIME or missing, unreadable, malformed UTF-8 or blank sidecars retain the hit and
fall back to body context (null without a body) when no later usable match exists.

`UnifiedHighlighter` reanalyzes the complete selected text, including English-field matches and
occurrences beyond 10,000 characters. Selection uses raw text regardless of the redaction flag;
with filtering enabled, the complete selected text is filtered before passage selection and
highlighting. If the match is removed, safe leading context retains the attachment identity;
blank transformed text has no snippet or attachment fields. Raw matches, scores, totals and
pagination stay unchanged. The query echo is filtered too. Schema 3 and disk format remain
unchanged; existing Markdown needs no migration, reindexing or reprocessing.

| Field group | Index fields |
|---|---|
| `any` (default) | All fields below |
| `subject` | `subject`, `subject_en` |
| `body` | `body`, `body_en` |
| `from` | `fromAddress`, `fromName` |
| `to` | `toAddress`, `toName`, `ccAddress`, `ccName` |
| `attachmentName` | `attachmentName`, `attachmentFile` |
| `attachmentText` | `attachmentText`, `attachmentText_en` |

A query consisting of one full address excludes `fromName`, `toName` and `ccName`; all other
fields in the chosen group still apply, including body text for `any`.

Every word is required, quoted text is a phrase, and stop-word-only queries return nothing.
`SimpleQueryParser` enables phrase and whitespace operators; fuzzy/wildcard syntax is disabled.
Russian and English word forms match; full addresses must be complete. Filenames match whole
or by component. Search has no source or pandoc access; `listFolders.synced` shows coverage.
An old schema raises `IllegalStateException` with:
`The index was built by an older version; run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it`.
Use offline `mail-reindex` or a requested sync to rebuild from disk.

The 2026-10-26 offline control set processes all 48 synthetic MIME messages and scores
all 80 queries: recall **79/86 = 0.918605**, **7 false positives**. Q061 now finds S001 through
`attachmentText` (the CSV attachment). Before attachment text the baseline was 78/86 = 0.906977.
The fixtures' redaction-before-index proposal is superseded: raw synthetic text is indexed,
and optional response filtering does not remove search matches.

| Category | Expected matches found / expected | False positives |
|---|---|---|
| ru-noun-forms | 16/16 | 0 |
| ru-irregular | 0/7 | 0 |
| ru-verb-adj | 5/5 | 0 |
| ru-yo | 3/3 | 0 |
| ru-names | 5/5 | 3 |
| en-forms | 17/17 | 0 |
| mixed-lang | 5/5 | 0 |
| address | 4/4 | 0 |
| filename | 8/8 | 0 |
| identifier | 5/5 | 0 |
| precision | 6/6 | 2 |
| stopwords-phrase | 3/3 | 0 |
| case | 2/2 | 0 |
| redacted | 0/0 | 2 |

Misses: Q011:S010, Q012:S009, Q012:S011, Q013:S010, Q014:S013, Q015:S015, Q016:S017.
Wrong matches: Q025:S024, Q028:S025, Q029:S024, Q067:S037, Q068:S041,
Q079:S047, Q080:S048. Snowball misses irregular Russian forms and conflates some names;
precision collisions and raw synthetic secret matches remain accepted limitations.

## Status file and writer ownership

`sync-status.json` is atomically replaced by `SyncRunner`, which runs only while its caller holds
the connection's Lucene writer lock. Fields: `state` (running/completed/failed), `pid`,
`startedAt`, optional `finishedAt`, ordered `folders`, optional `error`, `signInRejected`.
Each folder entry contains `path`, cumulative `downloaded`, `failed`, `removed`, `reset`, `done`, `rescanned`. `reset` means IMAP UIDVALIDITY invalidated remote refs;
`rescanned` means EWS recovered an invalid SyncState by a full traversal without invalidating
known refs. Old status JSON without `rescanned` reads as false.
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
exception class only and removes itself from the running map. After resources close, one INFO
summary reports state, durationMs, downloaded, failed and removed. Downloaded counts converted
new messages; failed counts per-message failures; removed counts removed local messages. Totals
use the latest cumulative report for each folder, so on failure an unreported partial batch may
be missing. Run-level errors do not increase failed. No summary is emitted for a start that does
not reach the worker. A close failure makes the summary failed even if the runner already saved
completed status. The summary contains no mail data or connection identifiers. There is no
scheduler or queue.
The `SyncTools` Spring service uses bean name `mailSyncTools` because Spring AI defines its own
`syncTools` bean. `SyncConfig` supplies `SyncService` with `Pandoc::locate`.

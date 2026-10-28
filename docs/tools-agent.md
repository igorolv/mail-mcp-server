# docs/tools-agent.md — MCP tools and background synchronization

Nine tools; the user-facing description of search, dates, copies and conversations is
`docs/search.md`. Tools are Spring services returning `api/` records, never `model/`
records.
`MAIL_MCP_REDACT_SECRETS=true` optionally filters response text; see `docs/redaction-agent.md`.
Downloads, files, index, lookup IDs, stored filenames and original local paths stay unchanged.
Only JSON-RPC is written to stdout. Failures are logged through `ToolLogger.failed`
and rethrown; `IllegalArgumentException` becomes an MCP tool error with its message.

Tool and parameter descriptions are read by the calling model, so they hold only what it needs
to choose and call a tool: what the tool returns, where each argument comes from, its format,
default and cap, query syntax, and results that would otherwise be misread (partial data during
sync, grouped copies, missing attachment text). Keep converters, size-limit rationale,
`mail-reprocess`/`mail-reindex`, secret filtering, tie-break order and wording that repeats the
output schema in this document instead.

Descriptions map returned local message keys and attachment `file` identifiers to the next
tool call with the same connection. Listing offsets count cards; body and attachment offsets
continue only their own text, using the issuing tool's `nextOffset`. Date parameter descriptions
are consistent between `search` and `listMessages`. User-facing reading and coverage details are
in `docs/search.md`; background sync outcomes and cross-process locking are in `docs/clients.md`.

## Tools

| Tool | Arguments | Result and errors |
|---|---|---|
| `listConnections` | None | Configured mailboxes in configuration order, capabilities and configuration errors; no credentials or source access. |
| `sync` | Required `connection` | Immediate `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused`, with explanation. Unknown/missing connection or unusable definition raises `IllegalArgumentException`. Missing pandoc raises `IllegalStateException`. |
| `syncStatus` | Required `connection` | Current/last local run, per-folder progress, summed downloaded/failed/removed counts, timestamps, error and `signInRejected`. No file means `never`. |
| `listFolders` | Required `connection` | Stored folder keys and paths, committed catalog counts and `synced` (cursor exists), in stored order. No index means zero counts. |
| `listMessages` | Required `connection`, `folder`; optional `offset`, `limit`, `since`, `until` | Folder cards sorted by descending date (undated last), then key; total, offset, optional nextOffset. Offset defaults to 0; limit to 50, capped at 200. Unknown folder is an error. |
| `getMessage` | Required `connection`, `key`; optional `offset`, `maxChars` | Metadata, MIME headers, body.md chunk, bodyLength, optional nextOffset and attachment file paths and optional Markdown `textPath`. Offset defaults to 0; maxChars to 20,000, capped at 100,000. Invalid/nonexistent UUID is an error. |
| `getThread` | Required `connection`, `key`; optional `offset`, `limit` | Local conversation cards across folders, ascending date (undated last), then key; exact total, offset and optional nextOffset. Offset defaults to 0; limit to 50, capped at 200. Invalid UUID or unknown connection is an error before opening the index. Missing index/anchor is an error. |
| `getAttachment` | Required `connection`, `key`, `file`; optional `offset`, `maxChars` | Original file path and saved Markdown chunk, textPath, textLength and optional nextOffset. `file` must exactly match a stored filename in that message's expanded attachment list, including files inside EML, ZIP, MSG and TNEF containers. Unknown file (including traversal or another message's filename) is an error. Offset defaults to 0; maxChars to 20,000, capped at 100,000. |
| `search` | Required `connection`; optional `query`, `field`, `folder`, `offset`, `limit`, `since`, `until` | Local cards with BM25 score and body or saved attachment snippet, sorted by score descending then key. Exact total, offset and optional nextOffset. Query is at most 1,000 characters and may be omitted or blank only with a date bound. Offset defaults to 0; limit to 20, capped at 100. Unknown field/folder is an error. No index means zero hits. |

`ConnectionInfo.localDataAvailable` is `Files.isDirectory(connectionDir)`, not evidence of
indexed messages or a completed sync. `FolderInfo.synced` is the presence of a cursor, not a
freshness check. `SyncService.start` maps any Lucene writer lock conflict to
`lockedByOtherProcess`, including a lock held by an offline command. That status starts no new
run and cannot imply that `syncStatus` describes the lock holder. `SyncRunner` records
`completed` after the folder pass even when individual messages increment the `failed` counter.
These distinctions are reflected in tool descriptions and response schemas.

`since` and `until` select the MIME header date, as strict `YYYY-MM-DD` at UTC midnight or
ISO-8601 datetime with explicit offset and no sub-millisecond precision. Bounds are normalized
to UTC instants in responses. `since` is inclusive and `until` exclusive: October 26–27 UTC
uses `since=2026-10-26`, `until=2026-10-28`. Either may be omitted; together they must be
ordered. Undated messages match only without bounds. Date-only search uses all folders unless
one is specified, sorts by descending date then key, returns score 0 and no snippet or attachment
attribution, and reads no MIME or sidecars. Text queries retain scoring and snippets. Folder and
field are validated in both modes, and a stop-word-only query remains a text query with zero hits.
Both readers reject old schemas; the existing schema and index remain unchanged.

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
text up to 20 MiB of decoded bytes, except XLSX up to 5 MiB inclusive, when conversion yields
nonblank text. The exception also applies inside containers; binary XLS retains 20 MiB.
Conversion is best effort: compressed XLSX size does not bound eager POI memory usage,
and smaller spreadsheets can still be expensive. It does not guarantee prevention of all
out-of-memory failures. `path` is the original
attachment file and `textPath` is its UTF-8 Markdown; the tool never converts on demand.
Unsupported, oversized or failed attachments retain their original files. See `docs/mime-agent.md`.
Use offline `mail-reprocess <connection>` to create Markdown for messages already on disk,
with no sync running.
Existing XLSX sidecars remain readable and searchable until optional user-run reprocessing
removes old sidecars and indexed attachment text for XLSX over 5 MiB. Reads and `mail-reindex`
of converted messages retain saved text. No index schema change is needed.
Binary DOC/XLS/PPT support targets Office 97-2003 and also applies to files inside containers.
It uses the same saved-text lookup and snippet attribution; legacy text is never converted
on demand. Encrypted or malformed documents retain their file without Markdown.
One reprocessing pass creates both container children and legacy Office text; `mail-reindex`
alone cannot create that text. Pending real-data checks are tracked in plan.md section 2.

Every message response includes an absolute `localPath` to its directory. An agent with local
file access may read those files directly. All reading tools use local data only, without
creating a source, acquiring the writer lock, checking pandoc or accessing the network.
Coverage can be incomplete during synchronization: check `listFolders.synced` for the first
completed folder pass and `syncStatus` for current progress. A committed catalog can lag files
being processed. `MailIndexReader` opens the latest commit once per call.

All tools advertise read-only and non-destructive hints. `sync` is not idempotent.
MCP resources and mail writing are not implemented here.

## Message flags

`MessageSummary`, `SearchHitInfo`, `GetMessageResult` and `ThreadMessageInfo` append optional
Boolean `read` and `flagged`. True and false are JSON booleans; unknown is omitted.
They describe the latest successful remote observation, not live state. `flagged` means the
user flag/star, not sender importance or Gmail Important. Booleans bypass SecretRedactor.

`getMessage` reads flags from metadata even without readable MIME; list, text/date-only search
and thread cards read the committed schema-5 index. Existing date bounds, conversation links,
sorting and pagination are unchanged. Opening the writer rebuilds old schemas offline, or the
user runs `mail-reindex <connection>`; old metadata remains unknown until a requested sync.
Every sync refreshes flags on known mail from the folder changes (IMAP FLAGS in the UID listing,
EWS ReadFlagChange/Update), without MIME downloads, conversion or a pass over all messages. Missing fields preserve known values independently; false clears them.
Original files, state/error, SHA-256 and download time remain unchanged. Flags do not participate
in move matching; destination observations apply after reuse. Flag-only updates and moves count
as zero downloaded, failed and removed, preserving rescan reports and the background summary.
Real-server behavior remains to verify.

## Message labels

The same four records append optional `List<String> labels` after `flagged`: mailbox labels of
the latest observation, omitted when unknown or when the provider has none, empty when known
empty. Only Gmail provides them now (`docs/imap-source-agent.md`); the contract and the
`label` search group are provider-neutral, so another source can fill them without changing
it. Labels are text and pass through `SecretRedactor.texts`, like folder paths. `getMessage`
reads them from metadata; list, search and thread cards from the stored `labels` index field.
The `field` description of `search` lists `label`; it is part of `any`. The provider `stableId`
is used only for synchronization and is never returned.

## Threads

`ThreadTools.getThread` returns `GetThreadResult(connection, key, total, offset, nextOffset,
messages)`. `ThreadMessageInfo` includes key, local folder key, date, displayed from, subject,
hasAttachments, state and absolute message directory localPath. No bodies or raw header IDs
are exposed. Subject/from use `SecretRedactor`; keys, folder keys, paths and dates stay original.
Optional `copies` contains the other local copies as `{key, folder}` and is omitted when empty;
copy keys and folder keys bypass `SecretRedactor`.

One committed `MailIndexReader` snapshot supplies the anchor and live documents from one
connection. Each local key is a node, separate from header-ID nodes, linked to its own
Message-ID and every usable In-Reply-To/References ID. Matching is case-sensitive. Surrounding
whitespace is trimmed; IDs must be angle-bracketed, nonempty, without whitespace or nested
brackets. Message-ID must be exactly one such ID with no other text; reply headers contribute
all valid IDs, deduplicated. Missing/malformed IDs contribute no links. A known card without
usable headers is a singleton, including failed messages.

An undirected traversal with visited sets finds ancestors, descendants and missing-parent
siblings and terminates on cycles. No subject, participant, date or provider-ID creates links.
The complete component sorts by ascending date, undated last, then key and groups copies
using the identity rule in Search below. The first member (smallest key for equal-date copies)
represents the group; other members appear in the same order under `copies`. Grouping happens
before paging. total and offsets count groups, exactly for the snapshot; offsets beyond the end
return no cards and no nextOffset. Separate calls can see different commits, as with listMessages; no snapshot token
or cache is retained.

Only key, ref, date, from, subject and the three stored header fields are scanned; full stored card
fields are fetched for the returned page. No bodies, MIME, attachment text, source construction
or pandoc are involved; reading works while the writer lock is held. Conversations use schema 4 without persisted thread data. Missing index/anchor raises
`IllegalArgumentException("Message is not indexed; use listMessages")`. Old schemas raise the
same rebuild error as search below.

## Search

Text search reloads optional user rules through a `Supplier<WordForms>` on every call and
opens its reader with that snapshot. Invalid rules raise `IllegalArgumentException` naming
the file and line. After the schema check, a changed effective mapping raises
`IllegalStateException("The word forms list changed since the index was built; run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it")`.
Text queries and highlighting share that analyzer. Explicitly listed words override Russian
stop words as well; English fields and unlisted stop words keep their existing behavior.
Missing/empty rules keep the existing
analyzer and require no reindex for indexes built without rules. Date-only searches do not
load or validate the file, and ignore its commit hash; listMessages and getThread also keep
working. No reader call triggers a rebuild. `SyncService.start` loads and validates the rules
on every call before opening the writer or signing in; a differing hash rebuilds through
existing disk reconciliation. Spring supplies the configured path lazily, so edits need no restart.

`SearchTools.search` returns `SearchResult(connection, query, field, folder, total, offset,
nextOffset, hits, since, until)`. Each `SearchHitInfo` includes key, folder key, date, displayed from, subject,
hasAttachments, state, score, snippet and the absolute message directory `localPath`.
Optional `copies` lists other matching local copies as `{key, folder}`; empty lists are omitted.
Copies need the same stored Message-ID after `strip()`, exactly one nonempty bracketed ID
without whitespace or nested brackets, compared case-sensitively; equal known stored dates;
and equal nonblank stored from and subject strings, compared verbatim. Missing/invalid parts
leave each document separate, including failed entries without headers. Bodies, sizes, folders
and flags do not participate; grouping never crosses connections.

The reader groups the complete matching set after query, folder and date filters, before paging.
The first member in score-descending/key order (date-descending/key for date-only queries)
represents the group with its card, score and snippet. Other members keep that order in `copies`;
only documents matching the query and filters themselves participate. Exact total, offset,
limit and nextOffset count groups. Small stored identity fields are read for all matches;
full cards and snippets only for the returned page. Grouping uses existing schema-4 fields
without reindexing. Stored messages, listMessages and listFolders counts remain unchanged.

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
pagination stay unchanged. The query echo is filtered too. Snippets use the current schema-4
fields and existing saved Markdown without reprocessing.

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

Query words are combined with MUST after analysis; quoted text is a phrase. Text analyzers
remove common stop words, so stop-word-only queries may return nothing. Address and filename
analysis differ as described in `docs/storage-agent.md`.
`SimpleQueryParser` enables phrase and whitespace operators; fuzzy/wildcard syntax is disabled.
Russian and English word forms match; full addresses must be complete. Filenames match whole
or by component. Search has no source or pandoc access; `listFolders.synced` shows coverage.
The tool description warns the caller that irregular forms and synonyms may be missed and tells
it to retry with other forms, synonyms or the other language before concluding mail is absent.
The tool description separates search modes, retry guidance and reading results into paragraphs.
Parameter descriptions own query syntax, field selection, folder keys, pagination and dates.
They explicitly rule out query prefixes such as `from:`, illustrate an exact address lookup,
distinguish it from `any` content matches, and identify dates as MIME-header dates. The result
guidance maps the connection, hit key and `snippetAttachmentFile` to `getMessage`/`getAttachment`,
notes that snippets can show partial matches or body context, and explains that matching copies
are grouped before counting and paging.
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
This baseline stays unchanged without a word forms file. The 2026-10-27 offline test with
`src/test/resources/word-forms/control-set.txt` (four groups, including `человек люди`) reaches
**7/7** irregular matches and **86/86** overall, with the same **7 false positives**.
Explicitly listed stop words are retained only in Russian analysis; other stop words and
English fields keep their original behavior.

## Status file and writer ownership

Requested syncs read source metadata only for added refs, in batches of at most 100 without
MIME/body fetches. EWS uses IdOnly GetItem identity and flag properties; IMAP uses
ENVELOPE/size/UID/FLAGS and verifies UIDVALIDITY after reopening with EXAMINE. Known messages
keep saved fingerprints; a removed one without a fingerprint is matched by its saved headers.
No reader tool initiates these metadata requests.

The runner reuses a removed converted message only for a unique complete Message-ID/metadata
match in a different included folder of the same connection; retained copies, incomplete
identities and UIDVALIDITY resets disqualify reuse. This heuristic can hide different remote
content even when all metadata is equal. SHA-256 remains the hash of the saved original.
Reused messages keep UUID and all original paths/content; cards gain the new folder/ref after
commit. All folders are discovered before removal; matches commit first and removals check the
old identity. Folder identities retain old/null cursors, including vanished folders, until the
final replacement after all planned changes commit. On interruption, reconciliation repairs an
indexed folder/ref/path or flags differing from disk metadata and reruns skip current local refs.
Final reports for retained folders follow the final cursor save; batch reports still mark
`done=false`. See `docs/storage-agent.md` for exact eligibility and recovery rules. This path is
verified offline; availability on the user's servers remains to verify.

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
A rejected sign-in also drops a `windowsCredential` or `passwordCommand` value kept in this
process. A password that could not be obtained is not a rejection: no credentials were sent, `signInRejected` stays false, the fixed
message goes to `error`, and the next `sync` may start (`docs/build-config-agent.md`,
"Windows Credential Manager" and "Password commands").

`SyncService.start` checks the definition, in-process running map, rejected status and pandoc,
then opens the Lucene writer before constructing a lazy source. A competing writer returns
`lockedByOtherProcess` without opening a source or changing status. One daemon platform thread
named `sync-<connection>` owns the source and index for its run; it closes both, logs failures by
exception class only and removes itself from the running map. After resources close, one INFO
summary reports state, durationMs, downloaded, failed and removed:
`Background sync finished: state={}, durationMs={}, downloaded={}, failed={}, removed={}`. Downloaded counts converted
new messages; failed counts per-message failures; removed counts removed local messages. Reused
apparent moves count as none of these, so zero downloads may include recognized moves. Totals
use the latest cumulative report for each folder, so on failure an unreported partial batch may
be missing. Run-level errors do not increase failed. No summary is emitted for a start that does
not reach the worker. A close failure makes the summary failed even if the runner already saved
completed status. The summary contains no mail data or connection identifiers. There is no
scheduler or queue.
The `SyncTools` Spring service uses bean name `mailSyncTools` because Spring AI defines its own
`syncTools` bean. `SyncConfig` supplies `SyncService` with `Pandoc::locate`.

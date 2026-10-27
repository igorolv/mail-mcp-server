# docs/storage-agent.md — message directories and the derived catalog

Message directories, the derived Lucene index (schema 4), synchronization, MIME/body
processing, search and attachment text. See `docs/plan.md` 3.2–3.6 for the design.
`MessageStore` and `MailIndex` take one connection directory; neither contacts a mailbox.

## Disk layout

Under `ConnectionDefinition.connectionDir()`:

```text
<connection>/
  folders.json
  sync-status.json
  messages/<k2>/<key>/
    message.eml
    meta.json
    body.md
    attachments/<n>__<sanitized name>
    attachments/<n>__<sanitized name>.md
  index/
```

`key` is a lowercase canonical UUID; `k2` is its first two characters. `messageDir` validates
the key without I/O. `createMessageDir` requires a new directory. `keys` returns sorted valid
message directory names under the matching prefix; unrelated names and files are ignored.
`delete` removes a message directory recursively and leaves its prefix directory.

`meta.json` example (synthetic):

```json
{
  "key": "3f2a0000-0000-0000-0000-000000000001",
  "folderKey": "f1",
  "folder": "INBOX",
  "ref": "101",
  "size": 12,
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "downloadedAt": "2026-10-25T12:00:00Z",
  "state": "downloaded"
}
```

States are `downloaded`, `converted` and `failed`. A failed download has size 0, no SHA-256 and
an `error` explaining the failure; it may have no `message.eml`. Null properties are omitted
by the configured Jackson 3 mapper and read back as null. A converted message has its selected
body in UTF-8 `body.md` (absent when there is no body) and decoded attachment files under
`attachments/` (absent when there are none). A conversion failure keeps the original MIME,
size and SHA-256, with `state=failed` and a fixed error. Supported attachments up to 20 MiB of
decoded bytes, except XLSX up to 5 MiB inclusive, can also have UTF-8
`<file>.md`; absent or failed attachment
text never changes metadata or the converted message state.
See `docs/mime-agent.md` for parsing, naming and pandoc rules.
The XLSX exception uses the resolved format and actual file size, including inside containers;
binary XLS retains 20 MiB. Conversion is best effort: compressed XLSX size does not bound
eager POI memory usage, and smaller spreadsheets can still be expensive. The threshold reduces
risk without guaranteeing prevention of out-of-memory failures.
Existing XLSX sidecars/text remain readable and searchable until optional user-run
`mail-reprocess <connection>` replaces output and index entries, removing old sidecars and
indexed attachment text for XLSX over 5 MiB. Reads and `mail-reindex` of converted messages
retain saved text; MIME and original attachments are preserved, with no index schema change.

`MessageMeta.fingerprint` is nullable and old JSON reads it as null; the old Java constructor
also defaults it to null. `MessageFingerprint(messageId, size, sentAt, from, subject)` holds
source-reported metadata. Its nullable Long size is independent of `MessageMeta.size` (MIME
bytes); EWS item size can differ. Matching needs one nonempty angle-bracketed Message-ID without
whitespace/nested brackets, positive size, known Instant, nonblank sender and subject. Only
ID/address outer whitespace is stripped; comparison preserves case and the exact decoded subject.
Processing, reprocessing and reindexing preserve the fingerprint. It is not indexed; flags have separate schema-4 fields.
Binary DOC/XLS/PPT (Office 97-2003) use the same `<file>.md` sidecars and index fields,
including documents inside containers. No disk/API/schema change is required. Existing
files need offline `mail-reprocess`, not merely `mail-reindex`, to create the newly supported
Markdown; one pass covers containers and legacy Office text (pending real-data checks: plan.md
2.4).

Files inside EML, ZIP, MSG and TNEF containers share the same flat `attachments/` directory
and numbering as MIME attachments. Their `AttachmentInfo.parent` points to the containing
attachment's stored filename. The expanded list is derived from saved containers during
processing and every read; there is no new JSON file or `meta.json` field. Existing messages
need offline `mail-reprocess <connection>` to save extracted files and Markdown and update
their indexed text. Until then, readers may list entries without paths or text paths.
Index fields come from the expanded attachment list and saved Markdown; schema remains 3.
MSG/TNEF expansion can renumber later extracted files in a mixed list. Finish reprocessing a
connection before attachment lookup/search with this version; extracted filenames are not
stable across the upgrade. `mail-reindex` alone does not create children or their Markdown.
Original MIME/container bytes are preserved; embedded MAPI directories gain derived standalone
`.msg` files with adapted property headers and the root named-property mapping.

`folders.json` example (synthetic):

```json
{
  "folders": [
    { "key": "f1", "remoteId": "INBOX", "path": "INBOX", "cursor": "7" }
  ]
}
```

Sync assigns local folder keys. The source owns the opaque cursor; null means no full pass yet.
A missing folders file returns an empty list. Missing metadata returns an empty `Optional`.

`SyncRunner` matches folders by `remoteId`, preserving a known folder's key and cursor while
updating its path. New keys are `f<n>`, one above the largest number in the local list before
vanished folders are dropped, starting at `f1`. A key dropped in an earlier run may be reused.
After restoration, changes and metadata for all included folders are discovered before any
removal or cursor update. Discovery failure retains candidates and old cursors. New/retained
folders in server order are saved with old/null cursors before applying changes, followed by
vanished-folder entries. Their identities remain until all changes commit. A reset removes all
local messages of that folder, even if the source's removed list is incomplete, then downloads
the added refs; both ends of a UIDVALIDITY reset are excluded from move matching. EWS rescans
do not invalidate refs and remain eligible.

Move matching uses the pre-run metadata before refreshing retained messages. An old converted
message with existing MIME must be explicitly removed in this run, with a complete fingerprint
equal to a new ref's fingerprint in a different folder of this connection. The fingerprint is the
saved one or, when none is saved, one built from the saved `message.eml` headers
(`MimeReader.fingerprint`; never persisted). Exactly one old occurrence and one current remote
occurrence are required across the entire connection: current means the saved fingerprints of
retained messages plus the fetched ones of added refs. A retained copy without a saved
fingerprint is not visible to this check. Ambiguity, missing evidence, failed
or downloaded entries and cross-connection entries cannot supply moves. No candidate is kept
after a completed run. Duplicate Message-IDs with different metadata remain distinct identities.

Only added refs receive a metadata snapshot, in protocol batches of at most 100 without
MIME/body downloads (Д29). Known messages keep their saved fingerprints: a flag-only observation
carries none and never clears one. Reuse atomically
rewrites the folder key/path, remote ref, fingerprint and observed destination flags, then rebuilds that index document
with `MessageProcessor.load`. UUID, physical directory, state/error, MIME size/SHA-256/time,
body, attachments and sidecars stay unchanged. Equal metadata can conceal different bytes:
this heuristic does not verify remote byte identity; SHA-256 describes the saved original only.

Nullable `MessageMeta.read` and `flagged` store the latest successful observations.
Old JSON and compatibility constructors default both to null; null is omitted, while false
serializes as a boolean false. `flagged` means the user flag/star, not sender importance or
Gmail Important. Processing, failures, offline reprocessing and reindexing preserve both.

Every flag observation (from the folder changes for known refs, from metadata for added refs)
refreshes each known field independently: missing/unsupported data retains
the old value, explicit false clears it. Added messages receive observed flags even when download
fails. Retained entries rewrite metadata atomically only when it changes; changed flags rebuild
that index document from saved content without download, conversion or content writes.
State, error, download time, SHA-256, body and attachments remain unchanged. Unchanged flags
cause no metadata/document rewrites. Flags are excluded from move fingerprints; reused moves
take observed destination flags. UIDVALIDITY resets use only fresh metadata.
Flag-only updates and moves add no downloaded/failed/removed count; rescan reporting is unchanged.

Schema 4 adds stored string flag fields and automatically rebuilds older indexes from disk
through the existing restore path. Old metadata restores unknown flags; an offline reindex
cannot observe remote state. Reconciliation repairs a flag mismatch after metadata was saved
before an interrupted index write, just as for folder/ref mismatches.

## Write order and failures

`SyncRunner` creates the message directory, asks `MailSource` to download `message.eml`, then
writes `meta.json` with `state=downloaded`, size, SHA-256 and download time. `MessageProcessor`
removes output from any earlier attempt, saves decoded attachments and `body.md`, then writes
metadata with `state=converted` or `failed`. The runner calls `MailIndex.put` with the resulting
metadata, headers, body Markdown and joined attachment text (null text/headers on failure).
`MessageStore` never writes MIME.
Without metadata, the directory is incomplete.
Metadata writes require an existing message directory; saving folders creates the connection
directory if needed. Constructors perform no I/O.

The runner applies matches and commits before unmatched removals, including vanished-folder
removals. It deletes an old key only while metadata still identifies the old folder/ref, so a
reassigned key survives removal of its original folder. Downloads commit in batches of 100
messages (including failed ones), and at folder end. Only after all planned changes commit does
one `folders.json` replacement save all new cursors and drop vanished folders. No cursor advances
on partial completion. With old cursors, current local refs skip already stored additions on
rerun. A `MessageUnavailableException` writes `state=failed`, size 0, null SHA-256 and the
exception's fixed message, without `message.eml`. Its ref is indexed, so the next run does not
retry it; normal removals delete failed messages too. Other exceptions propagate without retries
or cleanup; the next run reconciles before contacting the source. Closing the index commits
pending entries, including a partial batch with completed metadata.

`FolderProgress.downloaded` counts new messages ending converted; `failed` includes download
and conversion failures. Reused moves count as neither downloaded, failed nor removed; zero
downloads can include recognized moves. Conversion failures are not automatically retried. The offline
`mail-reprocess` command processes every message with metadata and MIME, replacing output and
index fields, including creating attachment `.md` files for messages already on disk; entries
without MIME remain unchanged on disk. Reprocessing also retains failed metadata-only catalog
entries when a word-rules change recreates the index, without increasing its counters.

Metadata, folders and sync status JSON files are written to a temporary file in the target
directory, then moved with `ATOMIC_MOVE` and `REPLACE_EXISTING`. Temporary files are cleaned up
after a write. File I/O failures become `UncheckedIOException`; Jackson exceptions propagate unchanged.

## Lucene catalog and reconciliation

`MailIndexReader.open(dir, forms)` keeps one rule snapshot per call. `current()` checks only
schema; `wordFormsCurrent()` separately compares the commit hash with the supplied rules.
Thus date-only search, listMessages and getThread continue reading current-schema commits
after rule edits. Reader tools never rebuild an index.

`MailIndex.openWriter` creates `index/` and opens an `IndexWriter` on `FSDirectory`, using
`MailAnalyzers.analyzer(forms)`. Missing/empty forms use the unchanged shared analyzer.
Lucene core, analysis-common, queryparser and highlighter use 10.5.2.
Fields come from metadata,
parsed MIME headers/attachment descriptions and saved Markdown:

| Field | Value | Type |
|---|---|---|
| `key` | Local UUID | `StringField`, stored |
| `keySort` | Local UUID for tie-breaking | `SortedDocValuesField`, not stored |
| `ref` | `folderKey + "/" + ref` | `StringField`, stored |
| `folder` | Folder path | `StringField`, stored |
| `state` | Lowercase state | `StringField`, stored |
| `read`, `flagged` | Literal `true` or `false`; omitted when unknown | `StringField`, stored |
| `error` | Failure explanation, only when non-null | `StoredField` |
| `date` | Epoch milliseconds, absent when null | `LongPoint`, `StoredField`, `NumericDocValuesField` |
| `subject`, `body` | Decoded subject and Markdown body, absent when null | `TextField`, stored |
| `subject_en`, `body_en` | Same subject and body | `TextField`, not stored |
| `attachmentText`, `attachmentText_en` | Saved attachment Markdown, in attachment order joined by a blank line | `TextField`, not stored |
| `from`, `to`, `cc` | Display addresses joined by `, ` as `Name <address>` or `address` | `StoredField` only |
| `fromAddress`, `toAddress`, `ccAddress` | One complete address per value | `TextField`, not stored |
| `fromName`, `toName`, `ccName` | One non-null display name per value | `TextField`, not stored |
| `messageId`, `inReplyTo` | Header value, absent when null | `StringField`, stored |
| `references` | One field per reference ID | `StringField`, stored |
| `attachmentNames` | Decoded names, or stored filenames when unnamed, joined by newline | `StoredField` only |
| `attachmentName`, `attachmentFile` | One decoded name per attachment, falling back to its stored filename | `TextField`, not stored |

`MailAnalyzers` uses one shared `PerFieldAnalyzerWrapper` without rules; nonempty rules get
an analyzer for the loaded snapshot. The ordinary default is `RussianAnalyzer`.
The `_en` fields use `EnglishAnalyzer` with both English and Russian default stop words.
Address fields and `attachmentFile` use `KeywordTokenizer` and `LowerCaseFilter`, preserving
complete addresses and filenames. `attachmentName` uses `PatternTokenizer("[^\\p{L}\\p{N}]+", -1)`,
`LowerCaseFilter` and Snowball's `RussianStemmer`, splitting underscores and punctuation.
Attachment contents use the Russian default analyzer and English analyzer for `attachmentText_en`.
They are indexed without being stored. Snippets can read individual saved Markdown sidecars
after paging; index fields and schema remain unchanged, with no migration or reindexing needed.

`WordForms` holds immutable normalized word→stem and source-line maps loaded from the optional
user file. Its hash is lowercase SHA-256 over UTF-8 `<word>\t<stem>\n` lines sorted by word;
empty rules have a null hash. Comments, spacing and group order do not affect the hash.
Nonempty rules insert `WordFormsFilter` before Russian Snowball in the default analyzer and
attachment-name parts analyzer. Listed words are excluded from the Russian stop set before
filtering, so user rules override stop words such as `человек`; unlisted stops stay unchanged.
It preserves offsets and marks replacements as keywords;
English and exact fields are unchanged. Query parsing and snippets use the reader's analyzer.

Every commit, including closing the writer, carries user data `schema=4` (`MailIndex.SCHEMA`)
and `wordForms=<hash>` for nonempty rules only. Without rules, the analyzer and commit data
are unchanged. Opening an index with another/missing schema or a differing `wordForms` entry
uses `OpenMode.CREATE`, rebuilding in place (both absent hashes count as equal).
`openWriter(dir, true, forms)` always recreates it. Legacy writer/reader overloads use
`WordForms.none()`. `wordFormsChanged()` reports whether an existing commit had a different
rule hash; reprocessing uses it to retain metadata-only failures specifically on that rebuild.
No second generation is kept.
The next `SyncRunner.restore` reconciles every missing entry from disk before network access:
converted entries load MIME headers and saved body/attachment Markdown, downloaded entries
are processed,
and failed entries get metadata only. It commits every 100 restored entries and at the end.
`mail-reindex <connection>` runs this restoration offline with recreation forced; it checks
pandoc before taking the writer lock because downloaded messages may need conversion.

`put` replaces by key; `delete` removes by key. `keys` and `folderRefs` open and close an NRT
reader of the writer, so pending changes are visible before commit. Folder lookup uses the
`folderKey + "/"` prefix: `f1` excludes `f10`, and `/` inside a server ref is preserved.

Lucene's `write.lock` allows one writer per connection. A second writer fails immediately with
`IndexLockedException` and the message `Connection is being synchronized by another process`.
There is no retry or additional lock. Closing commits the writer and closes the directory.
Other Lucene I/O failures become `UncheckedIOException`.

`reconcile(store, checkMetadata)` has two modes (Д30). The full mode (`reconcile(store)`, offline
commands, and a sync whose previous `sync-status.json` is not `completed`) deletes incomplete
message directories, removes indexed keys absent from disk, reads every meta.json to compare the
stored folder path, combined folder/ref and flags, and returns sorted keys missing from the index
or with mismatched identity or flags. The light mode, used after a completed run, compares only
directory keys with document keys and opens no meta.json: a completed run leaves both in step.
A sync run reads meta.json only of messages it removes, moves or changes: flags of retained
messages are compared with the stored index fields (`folderFlags`), and move uniqueness looks up
documents by Message-ID (`keysByMessageId`: the parsed header field `messageId` or the unstored
`fingerprintId` from the saved fingerprint; documents written before Д30 have only the former,
and no schema change or rebuild is needed). It does not index or
commit them itself. `SyncRunner` processes downloaded entries, loads converted entries using
MIME headers, saved `body.md` and attachment Markdown without writes, and indexes failed
entries with null headers, body and attachment text. Matching index identities and flags are left alone;
no full rebuild or reconversion is needed. This closes an interruption after metadata reassignment
before its index write. Interruptions after index commit and before cursor save also resume
with the same destination key and content, without repeating the MIME download. Failed
messages with metadata remain valid without MIME. Deleting `index/` loses no source data:
reconciliation restores headers and body from disk before the runner accesses the source.
`mail-reprocess` also restores processed entries offline, without sign-in.

`cli/MailSyncCommand` owns the source and writer, takes the writer lock before the first network
request and closes the source before the index. `SyncRunner` opens and closes neither; it reports
cumulative `FolderProgress` after each committed batch and final folder reports after all cursors
are saved (or a vanished folder's removals commit). `FolderProgress` lives in `model/`. `SyncService` runs the same runner in
the background; `MailIndexReader` reads the last commit; `mail-reindex` and `mail-reprocess`
rebuild offline.

## Sync status and tool readers

`MessageStore.readSyncStatus` returns an empty Optional without `sync-status.json`;
`writeSyncStatus` uses the same atomic JSON replacement. Only `SyncRunner`, while its caller
holds the writer lock, writes production status. It records running before reconciliation,
latest per-folder progress in first-appearance order, then completed or failed with finishedAt.
`SyncStatus` contains state, pid, startedAt, finishedAt, folders, error and signInRejected.
Folder progress distinguishes `reset` (IMAP UIDVALIDITY invalidated remote refs) from `rescanned`
(EWS recovered an invalid SyncState without invalidating known refs); old JSON without
`rescanned` reads as false. `done` retains its cursor-saved or folder-deleted meaning.
The background summary uses the latest progress per folder: converted new messages, per-message
failures and removed local messages. A failure can leave an unreported partial batch out of those
totals; a close failure can yield a failed summary after completed status was persisted.
Failures use fixed `SyncErrors.describe` text and authentication failures set signInRejected.
A final status-write failure is suppressed onto the original exception. A successful explicit
`mail-sync` clears the rejection flag; MCP starts refuse a persisted rejection. Readers report
interrupted when a running status belongs to a dead pid and no local run exists. See
`docs/tools-agent.md` for the wire contract and recovery.

`MailIndexReader.open` never takes a writer lock, returns empty without a committed index and
opens one snapshot of the latest commit per call. It counts folder messages with a PrefixQuery
on ref, and pages stored MessageCard fields by date descending (missing dates last), then key.
New documents have sorted `keySort` doc values; documents without them still sort by date.
The separate field preserves the `key` field's doc values type NONE.
`current()` checks commit schema; search, listMessages and getThread reject old schemas until a rebuild.
Optional date bounds filter the existing MIME-header `date` LongPoint, with inclusive since and
exclusive until; undated documents are excluded. Date-only search sorts by date descending then
key with score 0 and no snippets. No schema change or reindex is needed.
Search uses equal-weight field groups, required words and phrases through `SimpleQueryParser`,
an optional folder ref prefix FILTER, exact counts and score descending/key ascending sorting.
`SearchSnippets` uses in-memory `UnifiedHighlighter` with the parsed query without the folder or date
filter. `any` prefers a real body highlight, then the first matching attachment; `attachmentText`
selects attachments first. Other groups and overloads without a `MessageStore` keep body-only
snippets. Only eligible page hits enumerate local MIME once without output and read saved UTF-8
`attachments/<AttachmentInfo.file()>.md` in expanded list order, one text at a time. The list
starts with MIME attachments and appends container entries breadth first. Missing/broken MIME
or unusable sidecars keep the hit and body context fallback (null without a body). Original
binaries are not needed for an existing sidecar; `.md` attachments use `.md.md` sidecars.
The formatter uses `**` around matches and `… ` between passages, at most one passage. Body and
attachment fields mask only their own `_en` field; phrase positions apply to each individual text.
The highlighters see complete supplied text, including matches beyond character 10,000.
An optional output transform filters the complete selected text before rendering. If it removes
the match, safe context keeps the selected attachment identity; blank transformed text has no
snippet/identity. The exact lookup filename and the separately filtered display name are optional
search-hit fields. This changes no matches, scores, totals, pagination, files or index schema.
See `docs/redaction-agent.md`; no reprocessing or migration is required for existing sidecars.
The writer's NRT lookups remain separate; tools see only committed documents.

`MailIndexReader.thread` scans live documents' stored key, ref, date, from, subject, messageId,
inReplyTo and references in that same snapshot. Local keys and header IDs are separate graph
nodes: a key links to its own usable Message-ID and all usable reply/reference IDs. Matching is case-sensitive;
surrounding whitespace is trimmed. IDs must be nonempty and angle-bracketed without whitespace
or nested brackets. Message-ID must consist of exactly one ID; reply headers contribute all
valid IDs, deduplicated. Invalid/missing headers create no links; a failed card can be a singleton.
The undirected component includes ancestors, descendants and missing-parent siblings; visited
key/ID sets terminate cycles. Deleted documents contribute no links and copies retain their keys.
No subject, participant, date or provider identifier creates a link.
Stored dates sort the complete component ascending, undated last, then key. Copies group
before paging by equal case-sensitive stripped single valid bracketed Message-ID, equal known
stored date and equal nonblank stored from/subject strings. Missing/invalid identity stays
separate. The first member (smallest key for copies sharing a date) represents the group;
the rest appear under `copies` as key/folderKey pairs. Bodies, sizes, folders and flags are
not compared. Search applies the same grouping after query/folder/date filters, with the first
member in its score/date order representing the group; only matching copies are included.
Totals and pagination count groups. No stored copy is merged or deleted, and folder counts
and listMessages still count every document. Only the returned page loads full stored card
fields, never bodies or attachment text/MIME for threads.
The exact total is computed before paging. No writer lock, source or pandoc is needed; pages
across calls can change during sync. No persisted thread data, schema change or reindex is needed.

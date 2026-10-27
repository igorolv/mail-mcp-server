# Mail MCP Server

A local MCP server (stdio) that lets an AI agent read several mailboxes:
- Microsoft Exchange over EWS;
- Mail.ru and Gmail over IMAP.

It copies every message to disk as the original `.eml`, saves attachments as files, converts the
body and attachments to Markdown, and builds a local Lucene index with Russian and English word
forms. Tools answer from that local copy only; the mailbox is never modified.

**Status: draft version, 2026-10-26.** The server has been checked end to end on two real
mailboxes: Exchange 2013 with NTLM (about 8 thousand messages) and Mail.ru (about 34 thousand).
Gmail with OAuth2 is implemented but has not been tried against a real account. Development and
checks run on Windows. Possible improvements are listed in
[docs/plan.md](docs/plan.md#4-возможные-доработки) (in Russian).

EML/ZIP/MSG/TNEF extraction and DOC/XLS/PPT text are verified offline; data synced before them
needs `mail-reprocess`, and their check on real messages is still pending
([docs/plan.md](docs/plan.md#24-ещё-не-проверено-на-реальной-почте), section 2.4).

Local conversations (`getThread`) are accepted offline; checking a known conversation on real
mail after restarting the server is still pending. Flags are verified offline and need a
user-requested sync to populate existing mail; the schema-4 index rebuilds from disk.

**Contents:** [Features](#features) · [How it works](#how-it-works) · [Quickstart](#quickstart) ·
[MCP tools](#mcp-tools) · [Commands](#commands) · [Read-only protection](#read-only-protection) ·
[Data and privacy](#data-and-privacy) · [Environment variables](#environment-variables) ·
[Build](#build) · [Stack](#stack) · [Documentation](#documentation) ·
[Troubleshooting](#troubleshooting)

## Features

- **Several mailboxes at once.** They are described in one
  [`connections.json`](docs/build-config-agent.md#the-connections-file) outside the project.
  Every tool takes a `connection` argument. Credentials stay in that file, never in the MCP client
  configuration.
- **Exchange without Graph.** A thin SOAP client on StAX speaks EWS to Exchange 2013 and later,
  with NTLM through Apache HttpClient 5 (a bare login works; the server fills in the domain).
  Incremental sync uses `SyncFolderItems`.
- **IMAP** on Eclipse Angus Mail:
  - app passwords for Mail.ru and Gmail, or OAuth2 (PKCE, XOAUTH2) for Gmail;
  - incremental sync by `UIDVALIDITY` and UID.
- **Full local copy** per message: `message.eml` as downloaded, `meta.json`, `body.md`, the
  attachments, and `<attachment>.md` with the attachment's text.
  - Bodies, DOCX, ODT, RTF, EPUB, HTML and CSV are converted with sandboxed pandoc.
  - PDF goes through PDFBox; DOC/XLS/PPT (Office 97-2003), XLSX and PPTX through Apache POI.
  - Attachment text conversion is best effort, up to 20 MiB of decoded bytes except XLSX up to
    5 MiB (inclusive), including files inside containers. Larger files keep their original bytes
    without new Markdown; binary XLS retains the 20 MiB threshold. Compressed XLSX size does
    not bound POI memory usage, and smaller spreadsheets can still be expensive. This reduces
    risk without guaranteeing prevention of out-of-memory failures.
    Existing XLSX text remains readable and searchable until optional user-run
    `mail-reprocess <connection>` removes old sidecars and indexed text for XLSX over 5 MiB.
    `mail-reindex` alone retains saved text for converted messages; the index schema is unchanged.
  - Files inside EML, ZIP, Outlook MSG and TNEF (`winmail.dat`) containers are extracted as
    attachments and converted too; message headers and selected bodies become Markdown.
    Each child's `parent` is its container's stored filename. Original MIME and container bytes
    are preserved; embedded MAPI messages gain standalone `.msg` files.
    Use `mail-reprocess` for existing messages (`mail-reindex` alone is insufficient).
    Finish reprocessing a connection before attachment lookup/search after this upgrade:
    newly supported containers can renumber later extracted files in mixed lists.
    A single reprocessing pass covers both container extraction and legacy Office text.
- **Search:** Lucene with `RussianAnalyzer` plus an English field, exact address and file name
  terms, file name parts, phrases, and a highlighted snippet. It searches subject, body, sender,
  recipients, attachment names and attachment text.
- **Local conversations:** `getThread` follows stored Message-ID, In-Reply-To and References
  across folders of one mailbox, returning chronological cards from a local message key.
- **Heuristic move reuse:** an unambiguous move between included folders can keep the local
  UUID, directory and all saved content without downloading or converting MIME again.
  This is verified offline; availability on the user's servers remains to verify (проверить).
- **Read and star snapshots:** optional `read` and `flagged` booleans in message details, lists,
  searches and conversations. Missing means unknown; false means observed unset. `flagged` is
  the user flag/star, independent of sender importance and Gmail Important.
- **Background sync** on request. The tool returns at once, `syncStatus` shows progress, and
  several server processes can share one data directory. Folder progress distinguishes an IMAP
  UIDVALIDITY `reset` from an EWS `rescanned` recovery of an invalid SyncState; old status files
  default `rescanned` to false. Each finished background run logs its state, elapsed milliseconds,
  and cumulative downloaded (converted), failed and removed message counts. On failure, counts
  reflect the latest reported progress and may omit part of an unfinished batch. A resource close
  failure can leave the saved status completed while the summary says failed.
- **Bounded answers:** pages of message cards, message bodies and attachment text in chunks with
  `nextOffset`. Every message carries `localPath`, so an agent with file access can open the
  files directly.
- **Optional secret replacement** in tool response text, enabled with `MAIL_MCP_REDACT_SECRETS=true`.
  Downloads, local files and the search index keep their original content.

## How it works

```
MailSource (EWS | IMAP)  ──download .eml──▶  SyncRunner  ──▶  <data-dir>/<connection>/messages/…
                                                │                 message.eml, meta.json, body.md,
                                                │                 attachments/<file>, <file>.md
                                                ▼
                                          Lucene index  ◀── MCP tools (local data only)
```

- Synchronization runs only when asked: the `sync` tool or the `mail-sync` command.
- It always takes everything, then updates additions, deletions, vanished folders and flag snapshots.
- One sign-in per run and no retries: a stale password must not lock a domain account.
- The disk is the full copy; the index is derived and can be rebuilt offline (`mail-reindex`).

Each sync reads identity metadata for all present messages, including known ones, in batches of
at most 100: EWS `GetItem` identity and flag properties or IMAP `ENVELOPE`, UID, `RFC822.SIZE`
and `FLAGS`, without bodies. Known mail refreshes on every requested sync.
The first sync after upgrading seeds old local metadata without downloading known MIME or
reprocessing it. A message moved before seeding uses the normal download path.

Reuse requires an explicitly removed, converted local message with MIME, a new ref in another
folder, and exactly one old and one current occurrence of its complete Message-ID/size/sent
date/sender/subject fingerprint in the connection. IDs and addresses keep their case and lose
only outer whitespace; the decoded subject is compared exactly. Incomplete identities,
ambiguous copies and IMAP UIDVALIDITY resets fall back to normal deletion and download.
This is a heuristic: equal metadata can hide different content. SHA-256 still describes the
saved original, and EWS item size is separate from the saved MIME size; remote byte identity
is not verified.

All folders are discovered before applying changes. Matches update metadata and the index
before unmatched removals. Old cursors and vanished-folder identities remain until all planned
changes commit; the next run repairs an index whose folder/ref or flags disagree with metadata and uses
current local refs to resume. Content paths stay unchanged. Reused messages count as neither
downloaded, failed nor removed, so `0 downloaded` can include recognized moves.

The same metadata pass refreshes `read` and `flagged` independently. Missing/unsupported flag
data keeps the last known value; explicit false clears it. Flag-only updates and reused moves
increase no download/failure/removal counter and leave MIME, body, attachments, SHA-256, download
time and processing state unchanged. EWS Update/ReadFlagChange events need no special handling:
the full pass sees final state even without additions or removals, at the cost of metadata traffic
for all present messages on every requested sync.

Schema 4 adds stored flag fields. Opening the writer automatically rebuilds older indexes
offline from disk; `mail-reindex <connection>` also rebuilds without network access. Old metadata
has unknown flags until the next requested sync; reindexing alone cannot obtain remote state.
`listMessages`, text/date-only `search` and `getThread` use committed index values;
`getMessage` uses metadata even when MIME parsing fails. Real-server flag refresh remains
to verify (проверить); only the user changes flags in their client and runs credentialed checks.

Design, decisions and real-mailbox results: [docs/plan.md](docs/plan.md) (in Russian).

## Quickstart

**1. Install** JDK 25 and [pandoc](https://pandoc.org/installing.html) 3.x. Pandoc must be on
`PATH`: sync and reprocessing refuse to start without it.

**2. Build** the jar:

```powershell
.\gradlew.bat bootJar      # -> build\libs\mail-mcp-server.jar
```

**3. Describe your mailboxes** in `%USERPROFILE%\.mail-mcp-server\connections.json` (on other
systems, `~/.mail-mcp-server/connections.json`). There is one entry per mailbox, keyed by its
connection name:

```json
{
  "connections": {
    "work": {
      "type": "ews",
      "url": "https://webmail.example.com/EWS/Exchange.asmx",
      "auth": "ntlm",
      "username": "user",
      "password": "${MAIL_WORK_PASSWORD}",
      "description": "Work mail, Exchange 2013"
    },
    "mailru": {
      "type": "imap",
      "host": "imap.mail.ru",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@mail.ru",
      "password": "${MAILRU_APP_PASSWORD}",
      "description": "Personal Mail.ru"
    }
  }
}
```

- Any string value may be `${VAR}`. An unset variable stops startup rather than becoming an empty
  password.
- Keep the file readable only by its owner.
- All fields, Gmail with OAuth2 and validation rules:
  [docs/build-config-agent.md](docs/build-config-agent.md#the-connections-file),
  [docs/gmail-oauth-agent.md](docs/gmail-oauth-agent.md).

**4. Run the first sync from a terminal.** It downloads the whole mailbox, so on a large mailbox
it takes a long time; Exchange costs one MIME request per new message plus batched metadata
requests for all present messages. Run it again to check that it ends
with `0 downloaded`:

```powershell
java -jar build\libs\mail-mcp-server.jar mail-sync work
```

**5. Register the server** with your MCP client. Claude Code:

```powershell
claude mcp add mail -- java -jar C:\path\to\mail-mcp-server.jar
```

opencode (`~/.config/opencode/opencode.jsonc`, section `mcp`):

```json
"mail": {
  "type": "local",
  "command": ["java", "-jar", "C:\\path\\to\\mail-mcp-server.jar"],
  "enabled": true
}
```

**6. Ask the agent** in plain words, for example:
- *"Which mailboxes can you read?"*
- *"Find the letters about the supply contract in `work` and show the attachments."*

## MCP tools

Nine tools use an explicit connection name.

| Tool | What it does |
|---|---|
| `listConnections` | Configured mailboxes, their type, sign-in method and configuration errors; never contacts a server |
| `sync` | Starts a background synchronization and returns at once: `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused` (after a rejected sign-in) |
| `syncStatus` | Progress and result of the current or last run, per folder |
| `listFolders` | Local folders with message counts and whether each was synced completely |
| `listMessages` | Message cards of one folder, newest first, in pages |
| `getMessage` | Headers, the body in chunks, attachments with the paths of the file and of its text |
| `getThread` | Conversation cards across folders of one connection, anchored by a local message UUID, oldest first |
| `getAttachment` | The text of one attachment in chunks, and the file path |
| `search` | Every word is required, `"…"` is a phrase. Fields: `any`, `subject`, `body`, `from`, `to`, `attachmentName`, `attachmentText`. Returns cards with score and snippet |

Arguments, bounds and errors: [docs/tools-agent.md](docs/tools-agent.md).

`getThread(connection, key, offset, limit)` links local keys to case-sensitive IDs in stored
Message-ID, In-Reply-To and References. It follows the complete undirected component, including
siblings whose parent is absent locally. IDs must be nonempty and angle-bracketed, without
whitespace or nested brackets; Message-ID must contain exactly one ID and no other text.
Subject, participants and provider conversation IDs do not create links. After finding the
complete component, copies of one message count once. Cards sort by ascending date, undated
last, then key; the smallest key represents each copy group, with exact total and nextOffset
(default limit 50, maximum 200).
Only subject and displayed sender are optionally filtered; keys, folder keys, dates and local
paths remain usable. The result contains no bodies or raw header IDs; reading uses no bodies,
attachment text or MIME.
Each call reads one committed snapshot without the writer lock, network or pandoc; pages can
change during concurrent sync. Conversations use the schema-4 catalog without separate thread data.

In `search` and `getThread`, copies require the same valid single bracketed Message-ID
(case-sensitive after trimming outer whitespace), a known equal date, and equal nonblank
stored sender and subject. Incomplete identities stay separate. Other copies appear under
`copies` as `{key, folder}`; the field is absent when empty. Search groups all matches after
query, folder and date filters, before paging. The first match in score order (date order for
date-only searches), then key, supplies the card, score and snippet. Only copies that match
themselves are listed. Totals and page offsets count groups. Grouping uses existing index
fields; no reindex is needed. All stored copies retain their keys and files; `listMessages`
and folder counts still include each copy.

Both `search` and `listMessages` accept optional `since` and `until` MIME header dates.
Use strict `YYYY-MM-DD` (UTC midnight) or an ISO-8601 datetime with `Z` or a numeric
offset, at millisecond precision. `since` is inclusive and `until` exclusive; for October
26–27 UTC use `since: "2026-10-26"`, `until: "2026-10-28"`. Undated messages are excluded
when a bound is supplied. `search` may omit `query` when a bound is present: it searches all
folders unless `folder` is supplied, returns newest first then key, with score 0 and no snippet.
Bounds are returned as normalized UTC instants; date filtering uses the existing date fields.

For `any`, a body match supplies the snippet; otherwise search uses the first matching saved
attachment Markdown in expanded attachment list order. `attachmentText` selects attachments first;
other groups keep body snippets/context. Attachment snippets include `snippetAttachmentFile` (the exact
`file` for `getAttachment`) and `snippetAttachmentName` (display name, falling back to `file`).
Both are omitted for body or absent snippets. Search reads local MIME and existing sidecars
only for eligible page hits, without conversion or writes. Missing/unreadable MIME or sidecars
fall back to body context, or no snippet without a body. The complete selected text is filtered
before highlighting when secret filtering is enabled. No reindexing or reprocessing is needed
when attachment Markdown already exists; the disk format and index schema remain unchanged.

## Optional word forms

Search works without any word forms setup. With no file (or an empty/comment-only file),
the existing Russian/English analysis and index commit data stay unchanged; no reindex is needed.
To join irregular forms or single-word synonyms, optionally create UTF-8
`<data-dir>/word-forms.txt`, shared by all connections:

```text
# First word chooses the Snowball stem for the whole group
письмо писем
ребёнок дети
```

Each line is a group of at least two distinct letter-only words separated by whitespace.
`#` starts a comment; a leading UTF-8 BOM is accepted. Case and `ё`/`е` are normalized.
Repeats within one group are allowed; a word cannot belong to two groups. No dictionary is
built into the jar. Single-word synonyms are allowed, but widen results and can reduce precision.
The first word determines the stem: reordering groups is harmless; changing their first words
can change the rules. Listed words override Russian stop words too (for example, `человек`
in `человек люди`); unlisted stop words and English analysis stay unchanged. Only listed forms
are overridden; other forms still use Snowball.

After editing, inspect the analysis and rebuild each connection's index offline:

```powershell
java -jar build\libs\mail-mcp-server.jar mail-analyze письмо писем договоры
java -jar build\libs\mail-mcp-server.jar mail-reindex work
java -jar build\libs\mail-mcp-server.jar mail-reindex mailru
```

`mail-analyze` needs no connections, mail, pandoc or network. It shows the applied rule and
line number, ordinary Snowball stems and stop words. `MAIL_MCP_WORD_FORMS_FILE` can override
the path. Text search and requested sync reload the file on each call, without restarting.
When the effective mapping changes (including removal), text search asks for `mail-reindex`
or a requested sync rebuild; date-only search, message lists and conversations keep working.
Comments, spacing and group order do not require rebuilding. An invalid file reports its
filename and line; it blocks text search and writers before index access or sign-in.

## Commands

The same jar has user-run modes; none of them starts the MCP transport.

| Command | What it does |
|---|---|
| `mail-sync <connection>` | Synchronizes one mailbox and prints a line per folder |
| `mail-reprocess <connection>` | Offline: parses every stored message again, rewrites `body.md` and attachment text, reindexes |
| `mail-reindex <connection>` | Offline: rebuilds the index from disk |
| `mail-analyze <word>...` | Offline: shows Russian stems, word rule sources and stop words |
| `gmail-oauth <connection>` | Gets the first Gmail OAuth2 consent in the browser and stores the refresh token |

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Unexpected error |
| 2 | Configuration, arguments or missing pandoc |
| 3 | Sign-in rejected; there is no retry |
| 4 | Network, protocol or local storage error |
| 5 | Another process is syncing this mailbox |

For `mail-analyze`, file reading errors also use exit code 2.

Details: [docs/build-config-agent.md](docs/build-config-agent.md).

## Read-only protection

The server never sends, moves, deletes or flags mail:

- **EWS:** requests can only be built for an allow-list of ten read operations. `GetItem` does not
  mark a message as read.
- **IMAP:** a command guard lets through `EXAMINE`, `BODY.PEEK` partial fetches and `CLOSE`, and
  rejects `SELECT`, `STORE`, `EXPUNGE`, `APPEND`, `COPY`, `MOVE` and every other change before it
  is sent.
- **Sign-in:** one attempt per run, never retried. After a rejected sign-in the `sync` tool refuses
  the mailbox in every process until the user fixes the configuration and runs `mail-sync` in a
  terminal.

## Data and privacy

Everything is stored locally and **unfiltered** in `%USERPROFILE%\.mail-mcp-server\<connection>\`:

- the original messages and attachments;
- their Markdown;
- the index.

Tools return message content and local file paths to the agent, so the model sees your mail.
Set `MAIL_MCP_REDACT_SECRETS=true` in the MCP server's environment and restart it to replace
recognized passwords, verification codes, tokens and private keys in response text with markers
such as `[redacted:password]`. The default is false. No reprocessing or reindexing is needed.

The filter reduces accidental inclusion of secrets in context. Original paths and stored filenames
remain usable; direct file access still returns unfiltered content. Search still matches the raw
index, but snippets use filtered text. Body/attachment offsets and lengths refer to the filtered
view when enabled. This is best-effort text detection: it can miss secrets or hide ordinary text,
and does not anonymize personal data or process images. See [filter details](docs/redaction-agent.md).
Disk space is not saved: expect roughly the size of the mailbox plus its text.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_MCP_DATA_DIR` | `%USERPROFILE%\.mail-mcp-server` | Root of local data and logs |
| `MAIL_MCP_CONNECTIONS_FILE` | `<data-dir>\connections.json` | The connections file |
| `MAIL_MCP_WORD_FORMS_FILE` | `<data-dir>\word-forms.txt` | Optional user word groups; absent means ordinary analysis |
| `MAIL_MCP_REDACT_SECRETS` | `false` | Replace recognized secrets in tool response text only; restart to apply |

Everything about a mailbox lives in its `connections.json` entry.

## Build

```powershell
.\gradlew.bat build        # compile, all tests, bootJar -> build\libs\mail-mcp-server.jar
```

The build needs a JDK 25 toolchain; Gradle can download one. Tests never contact a mail server:
they use loopback fakes and the synthetic data sets in `testdata/`. Tests that need pandoc are
skipped when it is not installed.

## Stack

- Java 25, Gradle 9 with a version catalog
- Spring Boot `4.2.0-RC1`, Spring AI `2.1.0-RC1` MCP server (stdio)
- Apache HttpClient 5.6.5 (EWS with NTLM)
- Eclipse Angus Mail 2.0.5 (IMAP and MIME parsing)
- Apache Lucene 10.5.2 (core, analysis-common, queryparser, highlighter)
- pandoc 3.x (external process), Apache PDFBox 3.0.5, Apache POI 5.4.1

## Documentation

| Document | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | Rules for AI agents changing this repository, and the document map |
| [docs/plan.md](docs/plan.md) | Plan (Russian): design, real-mailbox results, possible improvements, decisions |
| [docs/architecture-agent.md](docs/architecture-agent.md) | Packages, layers, dependencies |
| [docs/build-config-agent.md](docs/build-config-agent.md) | Build, settings, connections file, all commands |
| [docs/tools-agent.md](docs/tools-agent.md) | MCP tools, status file, processes |
| [docs/storage-agent.md](docs/storage-agent.md) | Disk layout, index fields, reconciliation |
| [docs/mime-agent.md](docs/mime-agent.md) | MIME parsing, attachments, pandoc |
| [docs/ews-source-agent.md](docs/ews-source-agent.md), [docs/imap-source-agent.md](docs/imap-source-agent.md), [docs/gmail-oauth-agent.md](docs/gmail-oauth-agent.md) | Mail sources and sign-in |
| [docs/impl-tasks/](docs/impl-tasks/README.md) | How implementation briefs for agent sessions are written |
| [docs/research/](docs/research/) | Background research (Russian) |

## Troubleshooting

- **`RESULT: FAILED (... sign-in rejected ...)`, exit code 3.** Do not run it again blindly: every
  attempt with a wrong password counts toward the account lockout policy. Check the login and
  password in `connections.json` first. For Exchange, a bare login with `auth: ntlm` is the
  combination known to work.
- **`pandoc not found; install pandoc and put it on PATH`.** Install pandoc and restart the
  terminal or the MCP client, so that it sees the new `PATH`.
- **`Connection is being synchronized by another process`, exit code 5.** A sync of the same
  mailbox runs in another server process or terminal. Wait for it, or check `syncStatus`.
- **The build fails to overwrite `build\libs\mail-mcp-server.jar`.** A running MCP client keeps
  the jar open on Windows. Stop the server in the client, build, then reconnect.
- **`search` says the index was built by an older version.** Run
  `mail-reindex <connection>` (offline) or start a sync.
- **Damaged but readable PDFs.** Text extraction still runs; PDFBox and FontBox log only errors
  to keep command reports readable.

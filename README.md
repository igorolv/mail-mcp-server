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
- It always takes everything, then only changes: new messages, deletions, vanished folders.
- One sign-in per run and no retries: a stale password must not lock a domain account.
- The disk is the full copy; the index is derived and can be rebuilt offline (`mail-reindex`).

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
it takes a long time; Exchange costs one request per message. Run it again to check that it ends
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

| Tool | What it does |
|---|---|
| `listConnections` | Configured mailboxes, their type, sign-in method and configuration errors; never contacts a server |
| `sync` | Starts a background synchronization and returns at once: `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused` (after a rejected sign-in) |
| `syncStatus` | Progress and result of the current or last run, per folder |
| `listFolders` | Local folders with message counts and whether each was synced completely |
| `listMessages` | Message cards of one folder, newest first, in pages |
| `getMessage` | Headers, the body in chunks, attachments with the paths of the file and of its text |
| `getAttachment` | The text of one attachment in chunks, and the file path |
| `search` | Every word is required, `"…"` is a phrase. Fields: `any`, `subject`, `body`, `from`, `to`, `attachmentName`, `attachmentText`. Returns cards with score and snippet |

Arguments, bounds and errors: [docs/tools-agent.md](docs/tools-agent.md).

For `any`, a body match supplies the snippet; otherwise search uses the first matching saved
attachment Markdown in expanded attachment list order. `attachmentText` selects attachments first;
other groups keep body snippets/context. Attachment snippets include `snippetAttachmentFile` (the exact
`file` for `getAttachment`) and `snippetAttachmentName` (display name, falling back to `file`).
Both are omitted for body or absent snippets. Search reads local MIME and existing sidecars
only for eligible page hits, without conversion or writes. Missing/unreadable MIME or sidecars
fall back to body context, or no snippet without a body. The complete selected text is filtered
before highlighting when secret filtering is enabled. No reindexing or reprocessing is needed
when attachment Markdown already exists; the disk format and index schema remain unchanged.

## Commands

The same jar has user-run modes; none of them starts the MCP transport.

| Command | What it does |
|---|---|
| `mail-sync <connection>` | Synchronizes one mailbox and prints a line per folder |
| `mail-reprocess <connection>` | Offline: parses every stored message again, rewrites `body.md` and attachment text, reindexes |
| `mail-reindex <connection>` | Offline: rebuilds the index from disk |
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

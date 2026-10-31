# Mail MCP Server

[![Java 25](https://img.shields.io/badge/Java-25-blue?logo=openjdk)](https://adoptium.net/)
[![MCP](https://img.shields.io/badge/MCP-server-8A2BE2)](https://modelcontextprotocol.io/)

MCP server (stdio) that lets an AI agent read several mailboxes — Microsoft Exchange over EWS,
Mail.ru and Gmail over IMAP — from a local copy of the included folders. It downloads messages as the original
`.eml`, saves attachments as files, converts bodies and attachments (PDF, Office, archives,
attached messages) to Markdown and builds a local Lucene index with Russian and English word
forms. Tools answer from the local copy only; the mailbox is never modified.

**Status:** version `0.1.0` is in preparation. Checked end to end on Exchange 2013 with NTLM
(about 8 thousand messages) and Mail.ru (about 34 thousand). Gmail app-password synchronization
has also completed; real OAuth2 sign-in is unverified. Developed and tested on Windows.

**Contents:** [Features](#features) ·
[Typical Scenarios](#typical-scenarios) ·
[Quickstart](#quickstart) ·
[Architecture](#architecture) ·
[MCP Tools](#mcp-tools) ·
[Commands](#commands) ·
[Read-only Protection](#read-only-protection) ·
[Data and Privacy](#data-and-privacy) ·
[Server Environment Variables](#server-environment-variables) ·
[Build](#build) ·
[Stack](#stack) ·
[Documentation](#documentation) ·
[Troubleshooting](#troubleshooting) ·
[License](#license)

## Features

- **Several mailboxes** in one [`connections.json`](docs/connections.md) outside the project;
  every tool except `listConnections` takes a `connection` argument. Passwords can stay out of the file: in an environment
  variable, Windows Credential Manager or a password manager.
- **Exchange without Graph:** EWS with NTLM for Exchange 2013 and later, incremental sync with
  `SyncFolderItems`. **IMAP** for Mail.ru and Gmail with app passwords, Gmail also with OAuth2.
- **Full local copy:** the original message, its attachments and their Markdown text, so an agent
  with file access can open the files directly.
- **Attachment text:** PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, ODT, RTF, EPUB, HTML, CSV; files
  inside ZIP archives, attached `.eml` and Outlook `.msg` messages and `winmail.dat` are extracted
  and converted too.
- **Search** in Russian and English word forms over subject, body, addresses, attachment names
  and attachment text; date filters; snippets; copies of one message shown once; optional
  [word forms file](docs/search.md#word-forms) for irregular forms.
- **Conversations** across folders, linked by `Message-ID`, `In-Reply-To` and `References`.
- **Read-only at the protocol level**, one sign-in per synchronization, no retries, and refusal
  after a rejected sign-in to reduce the risk of account lockout.
  Details: [Read-only Protection](#read-only-protection).
- **Background sync on request**, incremental, with recognized moves between folders and
  read/flag snapshots; several clients can share one data directory.
- **Optional secret filter** that replaces passwords, codes and tokens in tool responses.

## Typical Scenarios

The agent picks the tools itself; these are the chains it usually follows.

| Scenario | Example request | Tools |
|---|---|---|
| Find mail on a topic | "Find the letters about the supply contract in `work` and show the attachments." | `search` → `getMessage` → `getAttachment` |
| Mail of a period | "What came to my work mail yesterday?" | `search` with `since`/`until` and no query → `getMessage` |
| Follow a conversation | "Show the whole discussion around this letter." | `search` → `getThread` → `getMessage` |
| Read an attachment | "What does the price list in the last letter from the supplier say?" | `search` (`from`, `attachmentName`) → `getAttachment` |
| Bring mail up to date | "Update my work mail and tell me what is new." | `sync` → `syncStatus` → `search` with `since` |
| Browse a folder | "List the latest messages in my inbox." | `listFolders` → `listMessages` |

## Quickstart

**1. Install the prerequisites**

- JDK 25.
- [Pandoc 3.x](https://pandoc.org/installing.html), installed separately from this server.
  **Pandoc is required**, and the directory containing its executable must be on `PATH`.

After installation, open a new terminal and check:

```powershell
java -version
pandoc --version
```

The commands must report Java 25 and pandoc 3.x. If `pandoc` is not found, add its installation
directory to `PATH`, reopen the terminal and check again before continuing.
Fully close and reopen any already running MCP client so that the server inherits the updated
`PATH`.

Pandoc is required for synchronization, reprocessing and reindexing. Reading previously
synchronized mail works without it.

**2. Build** the jar:

```powershell
.\gradlew.bat bootJar      # -> build\libs\mail-mcp-server.jar
```

**3. Get a password for each mailbox.** Which password depends on the provider:

| Mailbox | Password |
|---|---|
| Exchange (EWS) | Your domain account password. NTLM with a bare login (no domain part) is the combination known to work. [Exchange setup](docs/connections.md#exchange-ews) |
| Mail.ru | An app password (a password for external applications) created in the mailbox's security settings. [Mail.ru setup](docs/connections.md#mailru) |
| Gmail | An app password: profile picture → **Manage your Google Account** → **Security & sign-in** → **2-Step Verification** → **App passwords**; it requires 2-Step Verification. Use the 16 characters without spaces. Where app passwords are unavailable, sign in with [OAuth2](docs/connections.md#oauth2) instead. [Gmail setup](docs/connections.md#gmail) |

**4. Choose where the server reads each password.** Every source works with every mailbox. Use
exactly one source per connection; different connections may use different sources.

| Source | Key in the connection entry | Suits | Keep in mind |
|---|---|---|---|
| Windows Credential Manager | `"windowsCredential": "mail-mcp/work"` | Windows, including MCP clients started from the desktop | 64-bit Windows only; the credential belongs to one Windows user |
| Password manager | `"passwordCommand": ["op", "read", "op://Private/Work mail/password"]` | Any OS, a password already kept in a manager | The program must print only the password; unlock the manager before a sync |
| Literal | `"password": "..."` | The quickest setup | The password lies in the file; in JSON write `\` as `\\` and `"` as `\"` |
| Environment variable | `"password": "${MAIL_WORK_PASSWORD}"` | Clients and `mail-sync` started from one terminal | Clients started from the desktop do not see it; an unset variable stops the server |

For Credential Manager, you choose a name per mailbox, such as `mail-mcp/work`, and write it both
as `windowsCredential` in `connections.json` and as the address of a Windows credential. Search
Start for **Credential Manager** → **Windows Credentials** → **Add a generic credential**. Set the
address to that name, the user name to a plain label such as `work` (the server ignores it) and
the password to the mailbox password from step 3. Repeat for each connection with its own name.
`cmdkey /list:mail-mcp/work` shows whether Windows finds the entry, without the password.
Setup of every source, password manager recipes and password changes:
[Passwords](docs/connections.md#passwords).

**5. Describe your mailboxes** in `%USERPROFILE%\.mail-mcp-server\connections.json`
(`~/.mail-mcp-server/connections.json` elsewhere) — one entry per mailbox, keyed by the
connection name. Keep only the mailboxes you need and replace the example addresses. This example
reads every password from Credential Manager; replace any `windowsCredential` line with another
key from step 4.

```json
{
  "connections": {
    "work": {
      "type": "ews",
      "url": "https://webmail.example.com/EWS/Exchange.asmx",
      "auth": "ntlm",
      "username": "user",
      "windowsCredential": "mail-mcp/work",
      "description": "Work mail, Exchange 2013"
    },
    "mailru": {
      "type": "imap",
      "host": "imap.mail.ru",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@mail.ru",
      "windowsCredential": "mail-mcp/mailru",
      "description": "Personal Mail.ru"
    },
    "gmail": {
      "type": "imap",
      "host": "imap.gmail.com",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@gmail.com",
      "windowsCredential": "mail-mcp/gmail",
      "description": "Personal Gmail"
    }
  }
}
```

Make the file readable only by you, which matters most with a literal password:

```powershell
icacls "$HOME\.mail-mcp-server\connections.json" /inheritance:r /grant:r "${env:USERNAME}:F"
```

An entry without a password is reported as unusable and never signs in. Every field:
[docs/connections.md](docs/connections.md).

**6. Run the first synchronization** in a terminal. It downloads the whole mailbox, which takes
a while on a large one; a second run should end with `0 downloaded`:

```powershell
java -jar build\libs\mail-mcp-server.jar mail-sync work
```

Use `mailru` or `gmail` instead of `work` to synchronize those connections. With a Gmail app
password, run `mail-sync gmail` directly; `gmail-oauth` is only for the OAuth2 alternative.

**7. Register the server** with your MCP client, with an absolute path to the jar. Claude Code:

```powershell
claude mcp add --scope user mail -- java -jar C:/path/to/mail-mcp-server.jar
```

Codex CLI, OpenCode, VS Code, Cursor, Claude Desktop and environment variables:
[docs/clients.md](docs/clients.md).

The MCP client must launch the server with a `PATH` that includes pandoc. If the client was
running before you installed pandoc or changed `PATH`, fully close and reopen it.

**8. Try it.** Ask the agent in plain words:

- *"Which mailboxes can you read?"* — it calls `listConnections`.
- *"Find the letters about the supply contract in `work` from last week."* — it calls `search`
  with a date filter.

## Architecture

```text
 MCP client (Claude Code, Codex, OpenCode, VS Code, ...)
      |  JSON-RPC over stdin / stdout, no network port
      v
+- mail-mcp-server: one JVM per client -------------------------------------------+
|                                                                                 |
|  MCP layer: 9 tools; all except listConnections require connection              |
|      |                                   |                                      |
|      | read tools                        | sync: background thread              |
|      v                                   v                                      |
|  Lucene index per connection  <----  SyncRunner (one writer per connection)     |
|  catalog + search, rebuilt               |  .eml -> MIME parsing -> body.md,    |
|  offline from disk                       |  attachments and their Markdown      |
|      ^                                   v                                      |
|      +------- <data-dir>/<connection>/messages/<key>/                           |
|               message.eml, meta.json, body.md, attachments/                     |
|                                                                                 |
|  Read-only mail sources: EWS (SOAP, NTLM) | IMAP (Angus Mail) ---> mail servers |
+---------------------------------------------------------------------------------+
```

- **Local copy first.** Synchronization runs only on request — the `sync` tool or the `mail-sync`
  command. It downloads included folders once, then only changes: new and deleted messages, moves
  between folders, read and flag state. All other tools read local files and the index; they
  never contact a mail server and work while it is unreachable.
- **The disk is the copy, the index is derived.** Each message has its own directory with the
  original `message.eml`. The Lucene index is rebuilt from disk without network access
  ([`mail-reindex`](docs/commands.md#mail-reindex)).
- **Conversion.** Bodies and DOCX/ODT/RTF/EPUB/HTML/CSV go through sandboxed pandoc, one process
  per file; PDF through PDFBox; Office formats, Outlook messages and TNEF through Apache POI.
- **Processes.** The client starts the server as a child process. Several servers (one per
  client) may share a data directory: all of them read; one at a time synchronizes a connection.
- **Sign-in.** One attempt per synchronization, never retried. After a rejected sign-in, `sync`
  refuses the connection until you fix it and run `mail-sync` in a terminal.

## MCP Tools

Every tool except `listConnections` takes `connection` as a required argument; `listConnections`
lists the names.

| Tool | What it does |
|---|---|
| `listConnections` | Configured mailboxes with type, sign-in method, description and configuration errors; never contacts a server |
| `sync` | Starts a background synchronization and returns at once: `started`, `alreadyRunning`, `lockedByOtherProcess` or `refused` (after a rejected sign-in) |
| `syncStatus` | Progress and result of the current or last run: its stage and each folder |
| `listFolders` | Local folders with message counts and whether each was synchronized completely |
| `listMessages` | Message cards of one folder, newest first, in pages; optional `since`/`until` |
| `getMessage` | Headers, the body in chunks, attachments with the paths of the file and of its text |
| `getAttachment` | The text of one attachment in chunks, and the file path |
| `search` | All words required, `"…"` for a phrase, Russian and English word forms. Fields `any`, `subject`, `body`, `from`, `to`, `attachmentName`, `attachmentText`, `label`; optional folder and dates. Cards with score and snippet |
| `getThread` | The conversation of a message across folders, oldest first |

- Lists and text come in pages and chunks with `nextOffset`; every card has an absolute
  `localPath` to the message directory.
- Cards carry optional `read`, `flagged` and `labels` (Gmail labels) — the state seen at the last
  synchronization.
- The same message in several folders appears once in `search` and `getThread`, with the other
  copies under `copies`.

Query syntax, fields, dates, snippets, copies, conversations and word forms:
[docs/search.md](docs/search.md). Arguments, bounds and errors: [docs/tools-agent.md](docs/tools-agent.md).

## Commands

The same jar has commands that you run in a terminal; none of them starts the MCP transport.

| Command | What it does |
|---|---|
| `mail-sync <connection>` | Synchronizes one mailbox and prints a line per folder |
| `mail-reprocess <connection>` | Offline: converts every stored message again and reindexes |
| `mail-reindex <connection>` | Offline: rebuilds the index from disk |
| `mail-analyze <word>...` | Offline: shows how search analyzes words |
| `gmail-oauth <connection>` | Gets the first Gmail OAuth2 consent in the browser |

Exit codes: 0 success, 1 unexpected error, 2 configuration or arguments (including missing pandoc
or an unavailable password), 3 sign-in rejected, 4 network, protocol or storage error, 5 another
process is writing this connection. Output, examples and when to run what:
[docs/commands.md](docs/commands.md).

## Read-only Protection

The server never sends, moves, deletes or flags mail, and this is enforced at the protocol level:

- **EWS:** requests can be built only for an allow-list of ten read operations; downloading a
  message does not mark it as read.
- **IMAP:** a command guard lets through `EXAMINE`, `BODY.PEEK` fetches and `CLOSE`, and rejects
  `SELECT`, `STORE`, `EXPUNGE`, `APPEND`, `COPY`, `MOVE` and every other change before it is sent.
- **Sign-in:** one attempt per run, never retried; after a rejection the `sync` tool refuses the
  connection in every process until a successful `mail-sync` in a terminal.

Credentials live in `connections.json` or the stores it names, never in the MCP client
configuration. More: [docs/security.md](docs/security.md).

## Data and Privacy

Everything is stored locally and **unfiltered** in `%USERPROFILE%\.mail-mcp-server\<connection>\`:
the original messages and attachments, their Markdown text and the index — roughly the size of
the mailbox plus its text. Tools return message content and local file paths, so the model sees
your mail.

Set `MAIL_MCP_REDACT_SECRETS=true` in the server's environment to replace recognized passwords,
verification codes, tokens and private keys in tool responses with markers such as
`[redacted:password]`. Local files and the index keep the original text. The filter is best
effort and reduces accidental exposure; it is not an anonymizer. Details:
[Secret filter](docs/security.md#secret-filter).

## Server Environment Variables

Mailboxes and credentials live in [`connections.json`](docs/connections.md). The environment
configures only the server process:

| Variable | Default | Description |
|---|---|---|
| `MAIL_MCP_DATA_DIR` | `%USERPROFILE%\.mail-mcp-server` | Root of local data and logs |
| `MAIL_MCP_CONNECTIONS_FILE` | `<data-dir>\connections.json` | The connections file |
| `MAIL_MCP_WORD_FORMS_FILE` | `<data-dir>\word-forms.txt` | Optional [word forms](docs/search.md#word-forms) |
| `MAIL_MCP_REDACT_SECRETS` | `false` | Replace recognized secrets in tool responses; restart to apply |

How to set them in each client: [docs/clients.md](docs/clients.md#environment-variables).

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
- Apache HttpClient 5.6.5 (EWS with NTLM), StAX for SOAP
- Eclipse Angus Mail 2.0.5 (IMAP and MIME parsing)
- Apache Lucene 10.5.2 (index, Russian and English analysis, highlighting)
- pandoc 3.x (external process), Apache PDFBox 3.0.5, Apache POI 5.4.1

## Documentation

| Document | Contents |
|---|---|
| [docs/connections.md](docs/connections.md) | The connections file, passwords, Exchange, Mail.ru and Gmail setup |
| [docs/clients.md](docs/clients.md) | Registering the server with MCP clients, environment variables |
| [docs/commands.md](docs/commands.md) | User-run commands, their output and exit codes |
| [docs/search.md](docs/search.md) | Search syntax, dates, copies, conversations, word forms |
| [docs/security.md](docs/security.md) | Read-only protection, sign-in policy, credentials, local data, secret filter |
| [AGENTS.md](AGENTS.md) | Rules for AI agents changing this repository, and the map of engineering documents (`docs/*-agent.md`) |
| [docs/plan.md](docs/plan.md), [docs/decisions.md](docs/decisions.md), [docs/results.md](docs/results.md) | Plan, decisions and real-mailbox results |

## Troubleshooting

- **The MCP client says the server failed to start.** Run `java -jar <path>\mail-mcp-server.jar`
  in a terminal: a malformed `connections.json` or an unset `${VAR}` is reported there. Check that
  `java` on the client's `PATH` is version 25. See
  [Checking a configuration](docs/connections.md#checking-a-configuration).
- **A connection shows `configError` in `listConnections`.** The entry is unusable; the others
  keep working. [Configuration errors](docs/connections.md#configuration-errors) lists the causes.
  The file is read only at startup: restart the server after editing it.
- **`RESULT: FAILED (... sign-in rejected ...)`, exit code 3.** Do not run it again blindly: every
  attempt with a wrong password counts toward the account lockout policy. Check the login and
  password first. For Exchange, a bare login with `auth: ntlm` is the combination known to work.
- **`RESULT: FAILED (EWS connection failed: ...)`, exit code 4.** The Exchange server was not
  reached, so no sign-in was rejected. `server name not found` usually means a wrong or placeholder
  host in `url`, or a name known only inside the company network: connect to it or its VPN. Other
  reasons: [`mail-sync`](docs/commands.md#mail-sync).
- **`Password of connection '...' is unavailable: ...; no sign-in was attempted`.** Nothing was
  sent to the mail server. For `windowsCredential`, check that a *generic* credential with exactly
  that target name exists under the Windows user running the server (`cmdkey /list:<name>` prints
  `* NONE *` if not); see [Credential Manager setup](docs/connections.md#windows-credential-manager).
  For `passwordCommand`, run the program in a terminal and check that it
  prints only the password; unlock the password manager, or run `mail-sync` in a terminal where it
  can ask.
- **Gmail does not show App passwords.** Open **2-Step Verification** first, then **App passwords**,
  or use the direct link in [Gmail setup](docs/connections.md#app-passwords). That section also
  explains account restrictions and the OAuth2 alternative.
- **`pandoc not found; install pandoc and put it on PATH`.** Run `pandoc --version` in a new
  terminal. If it is not found, install pandoc and add the directory containing its executable
  to `PATH` (see [Quickstart](#quickstart)). A successful terminal check does not update an
  already running MCP client's environment: fully close and reopen the client so that the
  server inherits the new `PATH`.
- **`Connection is being synchronized by another process`, exit code 5.** A sync of the same
  mailbox or an offline data command holds the local write lock. Wait for that operation to
  finish. `syncStatus` reports sync runs only, not progress of offline commands.
- **Search says the index was built by an older version, or that the word forms changed.** Run
  `mail-reindex <connection>` (offline) or start a sync.
- **Search misses a message.** Check `listFolders` (`synced`) and `syncStatus`: results are
  incomplete during a synchronization. Irregular Russian forms and synonyms do not match without
  a [word forms file](docs/search.md#word-forms); try another form or the other language.
- **The build fails to overwrite `build\libs\mail-mcp-server.jar`.** A running MCP client keeps
  the jar open on Windows. Stop the server in the client, build, then reconnect.

## License

This project is licensed under the Apache License, Version 2.0.

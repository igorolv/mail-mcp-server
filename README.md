# Mail MCP Server

[![Java 25](https://img.shields.io/badge/Java-25-blue?logo=openjdk)](https://adoptium.net/)
[![MCP](https://img.shields.io/badge/MCP-server-8A2BE2)](https://modelcontextprotocol.io/)

MCP server (stdio) that lets an AI agent read several mailboxes — Microsoft Exchange over EWS,
Mail.ru and Gmail over IMAP — from a full local copy. It downloads every message as the original
`.eml`, saves attachments as files, converts bodies and attachments (PDF, Office, archives,
attached messages) to Markdown and builds a local Lucene index with Russian and English word
forms. Tools answer from the local copy only; the mailbox is never modified.

**Status:** version `0.1.0` is in preparation. Checked end to end on Exchange 2013 with NTLM
(about 8 thousand messages) and Mail.ru (about 34 thousand). Gmail supports app passwords and
OAuth2; real-mail verification is pending. Developed and tested on Windows.

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
  every tool takes a `connection` argument. Passwords can stay out of the file: in an environment
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
- **Read-only at the protocol level**, one sign-in per synchronization, no retries — a stale
  password cannot lock a domain account. Details: [Read-only Protection](#read-only-protection).
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

**1. Install** JDK 25 and [pandoc](https://pandoc.org/installing.html) 3.x. Pandoc must be on
`PATH`: synchronization refuses to start without it.

**2. Build** the jar:

```powershell
.\gradlew.bat bootJar      # -> build\libs\mail-mcp-server.jar
```

**3. Describe your mailboxes** in `%USERPROFILE%\.mail-mcp-server\connections.json`
(`~/.mail-mcp-server/connections.json` elsewhere) — one entry per mailbox, keyed by the
connection name. This example shows three password sources:

| Connection | Password source | JSON key |
|---|---|---|
| Exchange (`work`) | Environment variable | `password` with `${MAIL_WORK_PASSWORD}` |
| Mail.ru (`mailru`) | Password manager CLI (1Password in this example) | `passwordCommand` |
| Gmail (`gmail`) | Windows Credential Manager | `windowsCredential` |

These choices are independent of the mail provider: any password-based connection can use any
of these sources. Keep only the mailboxes you need, replace the example usernames and choose
**one password source per entry**.

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
      "passwordCommand": ["op", "read", "op://Private/Mailru/password"],
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

Keep the file readable only by you. Every field and provider setup:
[docs/connections.md](docs/connections.md).

**4. Prepare the passwords** for the sources you chose:

- **Exchange — environment variable.** In PowerShell, enter the mailbox password at a hidden
  prompt; the following stores it in the current process environment:

  ```powershell
  $workPassword = Read-Host 'Exchange password' -AsSecureString
  $env:MAIL_WORK_PASSWORD = [System.Net.NetworkCredential]::new('', $workPassword).Password
  ```

  Run `mail-sync` and start your MCP client from this terminal so they inherit the variable.
  A client opened elsewhere needs the variable in its own environment, or a different password
  source. An unset `${VAR}` prevents server startup. For Exchange, NTLM with a bare login
  (no domain) is the combination known to work. [Environment variables](docs/connections.md#environment-variables).
- **Mail.ru — password command.** Create a Mail.ru app password and save it in your password
  manager. The example uses [1Password CLI](https://developer.1password.com/docs/cli/reference/commands/read/):
  install `op` on the client's `PATH`, sign in/unlock the manager, and replace
  `op://Private/Mailru/password` with your vault/item/field reference. The server runs the array
  without a shell and reads its single output line as the password. For another manager, replace
  the array with its command, or use either of the other sources.
  [Password manager recipes and prompts](docs/connections.md#password-commands).
- **Gmail — Windows Credential Manager (64-bit Windows).** In Gmail, click your profile picture
  → **Manage your Google Account**
  → **Security & sign-in** → **2-Step Verification** → **App passwords**. Create one for
  `mail-mcp-server`; this requires 2-Step Verification. In Windows, search Start for
  **Credential Manager** → **Windows Credentials** → **Add a generic credential**. Set the address
  to `mail-mcp/gmail`, the user name to `gmail` and the password to the generated app password.
  The actual Gmail address stays in `connections.json`.
  [Credential Manager setup and password changes](docs/connections.md#windows-credential-manager).
  [Detailed Gmail setup, direct link and OAuth2 alternative](docs/connections.md#gmail).

**5. Run the first synchronization** in a terminal. It downloads the whole mailbox, which takes
a while on a large one; a second run should end with `0 downloaded`:

```powershell
java -jar build\libs\mail-mcp-server.jar mail-sync work
```

Use `mailru` or `gmail` instead of `work` to synchronize those connections. With a Gmail app
password, run `mail-sync gmail` directly; `gmail-oauth` is only for the OAuth2 alternative.

**6. Register the server** with your MCP client, with an absolute path to the jar. Claude Code:

```powershell
claude mcp add --scope user mail -- java -jar C:/path/to/mail-mcp-server.jar
```

Codex CLI, OpenCode, VS Code, Cursor, Claude Desktop and environment variables:
[docs/clients.md](docs/clients.md).

**7. Try it.** Ask the agent in plain words:

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
|  MCP layer: 9 tools, every call names its `connection`                          |
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
  command. It downloads everything once, then only changes: new and deleted messages, moves
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
| `syncStatus` | Progress and result of the current or last run, per folder |
| `listFolders` | Local folders with message counts and whether each was synchronized completely |
| `listMessages` | Message cards of one folder, newest first, in pages; optional `since`/`until` |
| `getMessage` | Headers, the body in chunks, attachments with the paths of the file and of its text |
| `getAttachment` | The text of one attachment in chunks, and the file path |
| `search` | All words required, `"…"` for a phrase, Russian and English word forms. Fields `any`, `subject`, `body`, `from`, `to`, `attachmentName`, `attachmentText`; optional folder and dates. Cards with score and snippet |
| `getThread` | The conversation of a message across folders, oldest first |

- Lists and text come in pages and chunks with `nextOffset`; every card has an absolute
  `localPath` to the message directory.
- Cards carry optional `read` and `flagged` — the state seen at the last synchronization.
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
- **`Password of connection '...' is unavailable: ...; no sign-in was attempted`.** Nothing was
  sent to the mail server. For `windowsCredential`, check that a *generic* credential with exactly
  that target name exists under the Windows user running the server; see
  [Credential Manager setup](docs/connections.md#windows-credential-manager).
  For `passwordCommand`, run the program in a terminal and check that it
  prints only the password; unlock the password manager, or run `mail-sync` in a terminal where it
  can ask.
- **Gmail does not show App passwords.** Open **2-Step Verification** first, then **App passwords**,
  or use the direct link in [Gmail setup](docs/connections.md#app-passwords). That section also
  explains account restrictions and the OAuth2 alternative.
- **`pandoc not found; install pandoc and put it on PATH`.** Install pandoc and restart the
  terminal or the MCP client, so that it sees the new `PATH`.
- **`Connection is being synchronized by another process`, exit code 5.** A sync of the same
  mailbox runs in another server process or terminal. Wait for it, or check `syncStatus`.
- **Search says the index was built by an older version, or that the word forms changed.** Run
  `mail-reindex <connection>` (offline) or start a sync.
- **Search misses a message.** Check `listFolders` (`synced`) and `syncStatus`: results are
  incomplete during a synchronization. Irregular Russian forms and synonyms do not match without
  a [word forms file](docs/search.md#word-forms); try another form or the other language.
- **The build fails to overwrite `build\libs\mail-mcp-server.jar`.** A running MCP client keeps
  the jar open on Windows. Stop the server in the client, build, then reconnect.

## License

This project is licensed under the Apache License, Version 2.0.

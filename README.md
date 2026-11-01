# Mail MCP Server

[![Java 25](https://img.shields.io/badge/Java-25-blue?logo=openjdk)](https://adoptium.net/)
[![MCP](https://img.shields.io/badge/MCP-server-8A2BE2)](https://modelcontextprotocol.io/)

**Give your AI agent your whole mailbox, including on-premises Exchange, with no way to change it.**

MCP server (stdio) for Microsoft Exchange over EWS, Mail.ru and Gmail over IMAP. It downloads every
message once, converts bodies and attachments to Markdown on your machine, builds a Lucene search
index, and answers the agent from that copy.

**Status:** version `0.1.0` is in preparation. Checked end to end on Exchange 2013 (about 8
thousand messages), Mail.ru (about 34 thousand) and Gmail with an app password. Developed and
tested on Windows.

**Contents:** [Features](#features) ·
[How it works](#how-it-works) ·
[Typical Scenarios](#typical-scenarios) ·
[Quickstart](#quickstart) ·
[MCP Tools](#mcp-tools) ·
[Commands](#commands) ·
[Build](#build) ·
[Stack](#stack) ·
[Documentation](#documentation) ·
[License](#license)

## Features

- **Works with on-premises Exchange.** EWS with NTLM and your normal domain password, tested on
  Exchange 2013. No Microsoft Graph needed. Mail.ru and Gmail work over IMAP.
- **Attachments are searchable too.** Text from PDF, Word, Excel, PowerPoint, ZIP archives,
  attached emails, Outlook `.msg` and `winmail.dat` is extracted and indexed, so the agent can
  find the contract inside the attachment, not just the message that carried it.
- **Search that understands word forms.** Russian and English, across subject, body, addresses
  and attachments. Copies of one message appear once, and conversations are followed across
  folders ([search](docs/search.md)).
- **Cannot change your mailbox.** Read-only is enforced at the protocol level: write commands are
  rejected before they are sent, and downloading a message does not mark it as read
  ([security](docs/security.md)).
- **Careful with your account.** One sign-in per sync and no retries. After a rejected password,
  it stops trying until you fix it, which reduces the risk of a domain lockout.
- **Fast, and works offline.** The agent reads a local copy, so answers don't depend on the mail
  server. Sync runs only when you ask for it.
- **Your mail stays on your machine.** No cloud service of its own: conversion and indexing are
  local, and the local copy is unfiltered. Your AI client still sees what the tools return, and an
  optional [secret filter](docs/security.md#secret-filter) masks passwords and codes in responses.
- **Several mailboxes, one server.** Passwords can stay out of the config, in Windows Credential
  Manager, a password manager or an environment variable ([connections](docs/connections.md)).

## How it works

1. **Download all mail.** The `sync` tool copies the included folders of each mailbox as original `.eml` files, then only changes on later runs.
2. **Convert it locally to Markdown.** Bodies and attachments — PDF, Office documents, archives,
   attached messages — are converted with pandoc (required), PDFBox and Apache POI.
3. **Index it.** A Lucene index per mailbox with Russian and English word forms.
4. **Use it from MCP.** Tools search and read the local copy only; they never contact a mail
   server.

```text
 MCP client (Claude Code, Codex, OpenCode, VS Code, ...)
      |  JSON-RPC over stdin / stdout
      v
 mail-mcp-server --- search, read ---> Lucene index + <data-dir>/<connection>/messages/
      |                                       ^
      +--- sync (on request) --- EWS / IMAP --+--> .eml -> Markdown -> index
```

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

**1. Install** JDK 25 and [pandoc 3.x](https://pandoc.org/installing.html). Pandoc is required
and must be on `PATH`. Check in a new terminal:

```powershell
java -version
pandoc --version
```

**2. Build** the jar:

```powershell
.\gradlew.bat bootJar      # -> build\libs\mail-mcp-server.jar
```

**3. Describe your mailboxes** in `%USERPROFILE%\.mail-mcp-server\connections.json`
(`~/.mail-mcp-server/connections.json` elsewhere), one entry per mailbox. Keep only the ones you
need and replace the example addresses:

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
      "passwordCommand": ["security", "find-generic-password", "-s", "mail-mcp", "-a", "mailru", "-w"],
      "description": "Personal Mail.ru"
    },
    "gmail": {
      "type": "imap",
      "host": "imap.gmail.com",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@gmail.com",
      "password": "${MAIL_GMAIL_PASSWORD}",
      "description": "Personal Gmail"
    }
  }
}
```

Each entry shows a different password source: `work` reads Windows Credential Manager, `mailru`
runs a command that prints the password (here the macOS Keychain; 1Password, `pass` and others
work the same way), and `gmail` reads an environment variable, which must be set wherever the
server starts. A literal password works too. Which password each provider needs (domain password, Mail.ru or Gmail
app password, Gmail OAuth2) and how to store it: [docs/connections.md](docs/connections.md).

**4. Register the server** with your MCP client, with an absolute path to the jar. Claude Code:

```powershell
claude mcp add --scope user mail -- java -jar C:/path/to/mail-mcp-server.jar
```

Other clients and environment variables: [docs/clients.md](docs/clients.md). If you installed
pandoc or changed `PATH` while the client was running, fully restart the client.

**5. Download your mail.** Ask the agent: *"Sync my `work` mailbox and tell me when it's done."*

The first sync downloads the whole mailbox and can take a long time, so keep the client open. If it
stops, ask again and it continues. A password manager must be unlocked or ask in its own window,
because the server has no terminal to ask in. For a large mailbox, or to see progress in a
terminal, use [`mail-sync`](docs/commands.md#mail-sync).

**6. Try it.** Ask the agent: *"Which mailboxes can you read?"* or *"Find the letters about the
supply contract in `work` from last week."*

Something went wrong: [docs/troubleshooting.md](docs/troubleshooting.md).

## MCP Tools

Every tool except `listConnections` takes a required `connection` argument.

| Tool | What it does |
|---|---|
| `listConnections` | Configured mailboxes with type, sign-in method, description and configuration errors |
| `sync` | Starts a background synchronization and returns at once |
| `syncStatus` | Progress and result of the current or last run |
| `listFolders` | Local folders with message counts |
| `listMessages` | Message cards of one folder, newest first, in pages |
| `getMessage` | Headers, the body in chunks, attachments with file paths |
| `getAttachment` | The text of one attachment in chunks, and the file path |
| `search` | Full-text search with Russian and English word forms, fields, folder and date filters |
| `getThread` | The conversation of a message across folders, oldest first |

Query syntax and results: [docs/search.md](docs/search.md). Arguments and limits:
[docs/tools-agent.md](docs/tools-agent.md).

## Commands

Offline maintenance, sign-in recovery and Gmail OAuth2 consent run in a terminal:
[docs/commands.md](docs/commands.md).

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
| [docs/troubleshooting.md](docs/troubleshooting.md) | Common problems and what to check first |
| [AGENTS.md](AGENTS.md) | Rules for AI agents changing this repository, and the map of engineering documents (`docs/*-agent.md`) |
| [docs/plan.md](docs/plan.md), [docs/decisions.md](docs/decisions.md), [docs/results.md](docs/results.md) | Plan, decisions and real-mailbox results |

## License

This project is licensed under the Apache License, Version 2.0.

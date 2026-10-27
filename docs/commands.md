# Commands

The server jar also has commands that you run yourself in a terminal. None of them starts the
MCP transport. The examples use `mail-mcp-server.jar` in the current directory; after a local build
it is `build\libs\mail-mcp-server.jar`.

- [Overview](#overview)
- [mail-sync](#mail-sync)
- [mail-reprocess](#mail-reprocess)
- [mail-reindex](#mail-reindex)
- [mail-analyze](#mail-analyze)
- [gmail-oauth](#gmail-oauth)
- [Exit codes](#exit-codes)
- [When to run what](#when-to-run-what)

## Overview

| Command | Network | What it does |
|---|---|---|
| `mail-sync <connection>` | yes, one sign-in | Synchronizes one mailbox and prints a line per folder |
| `mail-reprocess <connection>` | no | Parses every stored message again, rewrites body and attachment text, reindexes |
| `mail-reindex <connection>` | no | Rebuilds the connection's search index from the files on disk |
| `mail-analyze <word>...` | no | Shows how search analyzes words: Russian stems, word-forms rules, stop words |
| `gmail-oauth <connection>` | yes, Google only | Gets the first Gmail OAuth2 consent in the browser and stores the refresh token |

Common rules:

- `--help` or `-h` prints usage and exits with 0.
- `mail-sync`, `mail-reprocess` and `mail-reindex` change local data. Only one process at a time
  may write a connection's data, so they exit with code 5 while another command or an MCP server
  syncs the same connection. A running MCP server that is not syncing does not block them.
- The same three need [pandoc](https://pandoc.org/installing.html) on `PATH` and check for it
  before they open the index or the mailbox. They also check the optional
  [word forms file](search.md#word-forms) first and stop on an invalid rule.
- Output shows folder paths and counts. Addresses, subjects, credentials and server error text
  are never printed. Failures print `RESULT: FAILED (<fixed message>)`; local error details are
  withheld.
- Log messages go to stderr; Lucene may print a `jdk.incubator.vector` warning there.

## mail-sync

```powershell
java -jar mail-mcp-server.jar mail-sync work
```

Synchronizes one mailbox: downloads new messages, converts them, removes messages deleted on the
server, recognizes moves between folders and refreshes read/flag snapshots. It is the same work
as the MCP `sync` tool, but in the foreground with progress output. The server signs in **once**
and never retries.

```text
Mail sync (read-only; one sign-in attempt)
Connection: mailru (imap, app-password; address and credentials hidden)
  INBOX: 100 downloaded, 0 failed, 0 removed (in progress)
  INBOX: 120 downloaded, 1 failed, 2 removed
RESULT: OK (1 folders, 120 downloaded, 1 failed, 2 removed)
```

- The first run downloads the whole mailbox and may take long: Exchange needs one request per
  message. Run the command again: with no new mail it ends with `0 downloaded`, and its time
  depends on the number of folders and changes, not on the size of the mailbox.
- Folder lines are cumulative; `(in progress)` lines appear every batch of 100 messages. A folder
  whose IMAP `UIDVALIDITY` changed is downloaded anew and its line ends with `, folder reset`.
- `downloaded` counts new messages, `failed` messages that could not be downloaded or converted
  (they are not retried), `removed` local messages deleted because they are gone from the server.
  A recognized move counts as none of them.
- When the search index has to be rebuilt (a new index version or changed word forms), the run
  first restores it from disk without network access and prints `<N> messages indexed` every
  1,000 messages.
- After an interruption, the next run cleans up incomplete directories and continues without
  downloading stored messages again.
- **A rejected sign-in** prints `RESULT: FAILED (... sign-in rejected ...)` and exits with 3. Do not
  run it again blindly: every attempt with a wrong password counts toward the account lockout.
  The MCP `sync` tool refuses this connection until a successful `mail-sync` clears the state; see
  [Security](security.md#sign-in-policy).
- A password from [Windows Credential Manager or a password command](connections.md#passwords)
  is read before the sign-in. If it cannot be obtained, nothing is sent and the command exits with 2.

Folders that are synchronized:

- IMAP: all folders except those marked `\Noselect`, `\Trash`, `\Junk` or `\Drafts`;
- EWS: mail folders under the mailbox root, except Deleted Items, Junk Email, Drafts, Outbox and
  their subfolders.

## mail-reprocess

```powershell
java -jar mail-mcp-server.jar mail-reprocess work
```

Converts every stored message again from its saved `message.eml`, offline: rewrites `body.md`, the
attachment files and their Markdown text, and replaces the index entries. Run it after upgrading
to a version that converts messages differently, for example one that extracts text from more
attachment formats. Messages that failed to convert before are tried again; downloads that failed
stay as they are.

```text
Mail reprocess (offline)
  1000 messages processed
  failed: MIME parsing failed (MessagingException): 2
RESULT: OK (1002 messages, 1000 converted, 2 failed)
```

Progress prints every 1,000 messages; failure reasons are summarized before the result. A large
mailbox takes a while: about 35 minutes for 34 thousand messages with attachments. Finish
reprocessing a connection before relying on attachment lookup or search: newly extracted files
can change the numbering of attachment file names.

## mail-reindex

```powershell
java -jar mail-mcp-server.jar mail-reindex work
```

Rebuilds the connection's search index from the files on disk, offline, without converting
messages again. Run it after editing the [word forms file](search.md#word-forms), or when a tool
says the index was built by an older version (a requested sync rebuilds it as well).

```text
Mail reindex (offline)
  1000 messages indexed
RESULT: OK (1002 messages indexed)
```

## mail-analyze

```powershell
java -jar mail-mcp-server.jar mail-analyze письмо писем договоры и
```

Shows how search analyzes each word, using the current word forms file. It needs no connections,
index, pandoc or network.

```text
Word forms: <data-dir>/word-forms.txt
писем -> письм (word-forms.txt line 1)
договоры -> договор (Snowball)
и -> stop word
RESULT: OK
```

Without a word forms file the first line adds `(none found; using Snowball)`. An invalid file
prints `RESULT: FAILED (<filename> line <n>: <reason>)` and exits with 2.

## gmail-oauth

```powershell
java -jar mail-mcp-server.jar gmail-oauth gmail
```

Only for a Gmail connection with `auth: oauth2`; see [Gmail](connections.md#gmail) for the Google
Cloud setup. The command opens the system browser, waits up to five minutes for your consent on a
local callback address, and saves the refresh token. It reads no mail. Run `mail-sync` afterwards.
Run it again whenever Google revokes or expires the token.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success, or `--help` |
| 1 | Unexpected local error |
| 2 | Wrong arguments, invalid configuration or word forms file, missing pandoc, OAuth failure, or a password that could not be obtained (no sign-in) |
| 3 | Sign-in rejected; no retry |
| 4 | Network, protocol or local storage error |
| 5 | Another process is writing this connection's data |

`mail-reprocess` exits with 0 even when some messages fail to convert; the result line counts them.

## When to run what

| Situation | Command |
|---|---|
| First setup of a mailbox | `mail-sync <connection>` |
| A sign-in was rejected | Fix the configuration, then `mail-sync <connection>` |
| A new version converts mail differently (see its release notes) | `mail-reprocess <connection>` |
| You edited `word-forms.txt` | `mail-analyze` to check, then `mail-reindex` for each connection |
| A tool says the index was built by an older version | `mail-reindex <connection>`, or ask the agent to `sync` |
| The Gmail OAuth token expired | `gmail-oauth <connection>`, then `mail-sync <connection>` |

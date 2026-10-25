# docs/build-config-agent.md — build, tests, configuration and the connections file

Read this before building, running tests, adding a setting or changing the connections file format.

## Build, run, test

Always use the Gradle wrapper (`gradlew.bat` on Windows PowerShell, `./gradlew` elsewhere). The
build needs a JDK 25 toolchain; Gradle downloads one through the `foojay-resolver-convention`
plugin when none is installed (`api.foojay.io` must be reachable then), or point `JAVA_HOME` at a
local JDK 25.

```powershell
.\gradlew.bat build        # compile + tests + bootJar -> build\libs\mail-mcp-server.jar
.\gradlew.bat test         # all tests; none needs a network or a mail server
.\gradlew.bat bootJar      # only the runnable jar
.\gradlew.bat clean
```

Tests never contact a real mail server. `HttpClient5EwsTransportTest` runs a fake EWS endpoint on
`127.0.0.1` that answers with a synthetic NTLM challenge; `McpStdioServerTest` starts the server in
a child JVM and talks JSON-RPC over its stdin/stdout. `ImapClientTest` uses a plain loopback fake
IMAP endpoint; production always uses verified implicit TLS. Do not add tests that sign in to a real
mailbox (`AGENTS.md`, invariant 3).

To smoke-test the server without an MCP client: run `java -jar build\libs\mail-mcp-server.jar`;
it waits for JSON-RPC on stdin. Logs go to stderr and to
`<data-dir>\logs\mail-mcp-server.log`.

## Server settings

Bound from `mail-mcp.*` in `application.yml` into `config/MailMcpProperties`:

| Environment variable | Default | Meaning |
|---|---|---|
| `MAIL_MCP_DATA_DIR` | `%USERPROFILE%\.mail-mcp-server` | Root of local data: `<data-dir>\<connection>\`, `logs\` |
| `MAIL_MCP_CONNECTIONS_FILE` | `<data-dir>\connections.json` | The connections file |

To add a setting: add a component to `MailMcpProperties` (or a nested record), a line under
`mail-mcp:` in `application.yml` with a `MAIL_MCP_*` override, and a row to this table. Code that
runs without Spring (the user-run commands) reads the same variables through
`MailMcpProperties.fromEnvironment`. Never hard-code `~/.mail-mcp-server`; use
`resolvedDataDir()`. Tests use `@TempDir`, never the developer's home directory.

## The connections file

Modeled on jdbc-mcp-server `docs/connections.md` @ `c2c4362`. Read once at startup by
`connections/ConnectionsLoader`; edit it, then restart the server. Example with placeholders only:

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
    "gmail": {
      "type": "imap",
      "host": "imap.gmail.com",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@gmail.com",
      "password": "${GMAIL_APP_PASSWORD}",
      "description": "Personal Gmail"
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

| Key | Types | Meaning |
|---|---|---|
| `type` | all | `ews` or `imap`; required |
| `description` | all | What the mailbox is for; shown by `listConnections` |
| `auth` | all | EWS: `ntlm` (default) or `basic`. IMAP: `app-password` (default) or Gmail-only `oauth2` |
| `username`, `password` | all | Username required; password required except for OAuth2 and unused there. Any string value may use `${VAR}` |
| `oauthClientFile` | imap/oauth2 | Required path to downloaded Google Desktop client JSON; supports `${VAR}` and leading `~`. Token file is derived as `<data-dir>/<connection>/oauth/google-tokens.json` |
| `url` | ews | Absolute `https://` EWS endpoint; plain `http` is refused |
| `domain` | ews | NTLM domain. Leave out for a bare login: the NTLM domain field stays empty |
| `connectTimeoutSeconds`, `readTimeoutSeconds` | ews | Defaults 15 and 120 |
| `host`, `port`, `tls` | imap | Port 993 by default; `tls: false` is refused |

Connection names: `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, at most 64 characters, not `.` or `..`;
the name is also the data directory name.

What stops startup: invalid JSON, an invalid name, an entry that is not an object, a value of the
wrong JSON type, an unset `${VAR}`. What only makes one entry unusable (`configError` in
`listConnections`): a missing or unknown `type` or `auth`, a missing URL, host, login or
password, out-of-range values. Unknown keys and keys of the other type are logged as warnings and
ignored. A missing file starts the server with no connections.

Secrets: the file lives outside the repository; restrict it to the owner
(PowerShell: `icacls "$HOME\.mail-mcp-server\connections.json" /inheritance:r /grant:r "${env:USERNAME}:F"`).
This protects against accidents, not against a process running as the same user. Passwords are
masked in `toString` of `EwsSettings` and `ImapSettings`, never logged and never returned by a tool.
Agents must not open the user's real connections file.

Add or edit entries only in your local editor; agents must not modify an existing secret-bearing
file to append connections. Do not paste it into chat. For IMAP app-password authentication,
use the provider's app password. Placeholder references are optional; a literal password in this
owner-only external file is supported by the loader too.

## Mail sync

Run by the user only: `cli/MailSyncCommand` (`mail.mcp.cli`) signs in to one real mailbox.
The connection name is required. Both IMAP and EWS are supported through `MailSource`.

```powershell
.\gradlew.bat bootJar
java -jar build\libs\mail-mcp-server.jar mail-sync mailru
java -jar build\libs\mail-mcp-server.jar mail-sync work
# Alternative: .\gradlew.bat mailSync --args="mailru"
```

One run uses one lazy source session and one Lucene writer. It takes the writer lock before any
network request, reconciles disk and index, lists folders, removes vanished folders/messages,
downloads new messages as `message.eml` and `meta.json`, and commits the index after every 100
messages. A folder's opaque cursor is saved only after the folder finishes. Messages remain
`converted` after MIME parsing, attachment saving and body conversion; download or conversion
failures get `state=failed` and are not retried. Headers and Markdown are indexed. Pandoc is
required on `PATH` and checked before the index or mailbox is opened; missing pandoc exits 2.
`SyncRunner` also atomically writes `sync-status.json` before work, after folder progress and
on completion/failure. A rejected sign-in sets `signInRejected`; MCP `sync` then refuses starts.
Fix the configuration locally and run `mail-sync <connection>` in a terminal; a successful run
clears the flag. Restart the MCP server if its startup configuration changed. MCP `sync` runs
in the background; the CLI remains synchronous with the same output and exit codes.
See `docs/tools-agent.md` for status fields and cross-process reading.

The first run downloads the whole mailbox and may take a while (EWS uses one `GetItem` per
message). Run the same command again:
with no new mail it ends with `0 downloaded`. After an interruption, the next run removes
incomplete directories, restores missing index entries from metadata and resumes without
downloading messages already stored. Failures end the run without automatic retries.

Output (folder paths are shown; addresses, subjects and credentials are hidden):

```text
Mail sync (read-only; one sign-in attempt)
Connection: mailru (imap, app-password; address and credentials hidden)
  INBOX: 100 downloaded, 0 failed, 0 removed (in progress)
  INBOX: 120 downloaded, 1 failed, 2 removed
RESULT: OK (1 folders, 120 downloaded, 1 failed, 2 removed)
```

A reset adds `, folder reset` to the folder line. Batch lines are cumulative; the result sums
only completed folder lines, including folders deleted locally. Errors print
`RESULT: FAILED (<fixed message>)`; configuration and unexpected local details are withheld.
Lucene may print its `jdk.incubator.vector` warning to stderr.

EWS failures print only these lines, without the host or server message:

```text
RESULT: FAILED (EWS sign-in rejected; check the connection locally before the next run)
RESULT: FAILED (EWS error: <code>)
```

`<code>` is the response code for an EWS error response or SOAP fault when present; otherwise
it is the exception's simple class name. No EWS failure is retried, including `ErrorServerBusy`.

| Exit | Meaning |
|---|---|
| 0 | Success, or `--help` / `-h` |
| 1 | Unexpected local error; details withheld |
| 2 | Wrong arguments, invalid configuration/selection, missing pandoc, or OAuth failure |
| 3 | IMAP or EWS sign-in rejected; no retry |
| 4 | IMAP/EWS/network/protocol or local storage error |
| 5 | Another process holds the connection's Lucene writer lock; no sign-in |

## Reprocessing

`cli/MailReprocessCommand` converts local messages offline and never constructs a mailbox
connection or signs in. Pandoc is required on `PATH` even for plain text messages; there is
no path setting or fallback. It is checked after configuration/selection and before the
Lucene writer is opened. Both commands print this failure and exit 2 if the check fails:

```text
RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)
```

```powershell
java -jar build\libs\mail-mcp-server.jar mail-reprocess mailru
java -jar build\libs\mail-mcp-server.jar mail-reprocess work
# Alternative: .\gradlew.bat mailReprocess --args="mailru"
```

The command processes sorted message keys with both metadata and `message.eml`, including
previous conversion failures. Download failures without MIME and incomplete directories stay
unchanged. Old `body.md` and `attachments/` are replaced, metadata becomes converted/failed,
and the index entry is replaced. Commit every 100 processed messages and at the end. Progress
prints every 1,000 messages. Before the result, distinct failure reasons are printed in
descending count order (ties by reason):

```text
Mail reprocess (offline)
  1000 messages processed
  failed: MIME parsing failed (MessagingException): 2
RESULT: OK (1002 messages, 1000 converted, 2 failed)
```

Exit codes: 0 completed (even with per-message conversion failures) or help; 2 invalid
arguments/configuration/selection or missing pandoc; 5 connection index locked; 4 local
storage error; 1 unexpected local error. Local error details are withheld. Conversion uses
one sandboxed pandoc process per HTML body, no retries or timeout; see `docs/mime-agent.md`.
Pandoc-dependent tests use a shared availability helper and skip when it is absent.

## Reindexing

`cli/MailReindexCommand` rebuilds one connection's index from local disk, without creating a
source or contacting a mailbox. Pandoc must be on `PATH` and is checked before opening or
recreating the index: messages still downloaded may need body conversion.

```powershell
java -jar build\libs\mail-mcp-server.jar mail-reindex work
java -jar build\libs\mail-mcp-server.jar mail-reindex mailru
# Alternative: .\gradlew.bat mailReindex --args="work"
```

No sync or other writer may be running for the same connection. The command forces recreation
in place, then calls `SyncRunner.restore`: converted messages load headers and saved body.md,
downloaded messages are processed, failed messages get metadata only. Incomplete directories
are removed. Originals and converted output remain unchanged for converted messages.
The index commits every 100 entries and at the end, with commit data `schema=2`.
Progress goes to the command's output only; server restoration stays silent.

```text
Mail reindex (offline)
  1000 messages indexed
RESULT: OK (1002 messages indexed)
```

Progress prints every 1,000 entries. The result counts all indexed messages, including failed
metadata entries. Exit codes: 0 success/help; 2 arguments/configuration/selection or missing
pandoc; 4 local storage error; 5 connection index locked; 1 unexpected local error.
The missing-pandoc message is the same as for reprocessing. Local error details are withheld.
Old-schema indexes also recreate automatically when a writer opens during sync; until then,
the search tool asks for reindexing. Reader tools never initiate a rebuild.

## Gmail OAuth2

The user-run `gmail-oauth <connection>` command obtains initial consent through the system browser,
PKCE and a local callback. Detailed Google Cloud setup, the exact JSON entry and security/lifecycle
rules are in [gmail-oauth-agent.md](gmail-oauth-agent.md). Set `auth: oauth2`, remove the Gmail
password and add `oauthClientFile`; OAuth is accepted only for `imap.gmail.com:993` with TLS.
The command class is `cli/GmailOAuthCommand` (`mail.mcp.cli`).

```powershell
java -jar build\libs\mail-mcp-server.jar gmail-oauth gmail
java -jar build\libs\mail-mcp-server.jar mail-sync gmail
```

No app password is needed. Google Cloud must have a Desktop OAuth client, appropriate consent and
the mailbox added as a test user when using External/Testing. IMAP requires `https://mail.google.com/`.
Refresh tokens are saved with owner-only permissions; access tokens stay in memory. Refresh is lazy,
never retries and does not open a browser. Expired/revoked refresh tokens require the first command again.
Only the user runs real authorization and sign-in; agents may run `gmail-oauth --help`.

## Synthetic data sets

`testdata/mime/` has 20 MIME cases, `testdata/redaction/` has 256 detector cases and 8 mixed
documents, and `testdata/search/` has 48 messages with 80 control queries. Each directory has an
English README, JSON expectations and a complete manifest. Russian reports are in
`docs/research/RT-11-synthetic-mime.md`, `RT-12-redaction-samples.md`, `RT-13-search-queries.md`.
Helper generators/validators stay under ignored `testdata/.work/`; only data is tracked.
They feed `MimeReaderTest` and `SearchControlSetTest`; `testdata/redaction/` waits for the optional
filter (plan.md section 4, Д6).

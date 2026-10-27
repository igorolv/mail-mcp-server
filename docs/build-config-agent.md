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
| `MAIL_MCP_WORD_FORMS_FILE` | `<data-dir>\word-forms.txt` | Optional UTF-8 word groups, shared by all connections; missing/empty keeps ordinary analysis. Reloaded per text search and requested sync. |
| `MAIL_MCP_REDACT_SECRETS` | `false` | Optional secret replacement in tool response text; restart to apply. CLI output and local data stay unfiltered. See `docs/redaction-agent.md`. |

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
| `passwordCommand` | ews, imap/app-password | Instead of `password`: a JSON array, the program and its arguments, that prints the password; see [Password commands](#password-commands). Each element may use `${VAR}`. Ignored for OAuth2 |
| `oauthClientFile` | imap/oauth2 | Required path to downloaded Google Desktop client JSON; supports `${VAR}` and leading `~`. Token file is derived as `<data-dir>/<connection>/oauth/google-tokens.json` |
| `url` | ews | Absolute `https://` EWS endpoint; plain `http` is refused |
| `domain` | ews | NTLM domain. Leave out for a bare login: the NTLM domain field stays empty |
| `connectTimeoutSeconds`, `readTimeoutSeconds` | ews | Defaults 15 and 120 |
| `host`, `port`, `tls` | imap | Port 993 by default; `tls: false` is refused |

Connection names: `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, at most 64 characters, not `.` or `..`;
the name is also the data directory name.

What stops startup: invalid JSON, an invalid name, an entry that is not an object, a value of the
wrong JSON type, an unset `${VAR}`. What only makes one entry unusable (`configError` in
`listConnections`): a missing or unknown `type` or `auth`, a missing URL, host or login, neither
or both of `password` and `passwordCommand`, an empty `passwordCommand` or an empty element,
out-of-range values. Unknown keys and keys of the other type are logged as warnings and
ignored. A missing file starts the server with no connections. A `passwordCommand` that is not
an array of strings is a wrong JSON type and stops startup.

Secrets: the file lives outside the repository; restrict it to the owner
(PowerShell: `icacls "$HOME\.mail-mcp-server\connections.json" /inheritance:r /grant:r "${env:USERNAME}:F"`).
This protects against accidents, not against a process running as the same user. Passwords are
masked in `toString` of `EwsSettings` and `ImapSettings`, never logged and never returned by a tool.
Agents must not open the user's real connections file.

Add or edit entries only in your local editor; agents must not modify an existing secret-bearing
file to append connections. Do not paste it into chat. For IMAP app-password authentication,
use the provider's app password. Placeholder references are optional; a literal password in this
owner-only external file is supported by the loader too.

### Password commands

For users who do not want the password in this file or in environment variables (plan.md 3.1,
Д22). `connections/PasswordCommand` runs the configured program and takes its output as the
password:

```json
"work": {
  "type": "ews",
  "url": "https://webmail.example.com/EWS/Exchange.asmx",
  "username": "user",
  "passwordCommand": ["op", "read", "op://Private/Work mail/password"]
}
```

- **When it runs:** right before the first sign-in of a synchronization, never at startup and
  never for reading tools, which use local data only. Opening a source session does not run it
  (`HttpClient5EwsTransport` sets credentials before its first request; IMAP asks on connect).
- **Kept in memory:** the value stays in the process until it exits, so frequent syncs do not unlock
  the password manager each time. A rejected sign-in drops it (`SyncService`). After changing the
  password in the manager, restart the MCP server, as with a literal password.
- **Execution:** no shell; the array goes to the program as is. On Windows a `.exe` on `PATH` is
  found by name; `.cmd`/`.bat` wrappers need `["cmd", "/c", "name", ...]`. The program inherits the
  server's environment and working directory.
- **Output:** UTF-8, one non-empty line. One trailing `\n` or `\r\n` and a leading byte order mark
  are removed; other whitespace is kept. More than one line, empty output, invalid UTF-8 or more
  than 64 KiB is a failure. Windows PowerShell 5.1 does not write UTF-8 to a pipe by default;
  scripts must write bytes (as `scripts/Get-StoredPassword.ps1` does).
- **In the server:** the program gets no input (end of file at once) and 60 seconds; its error
  output goes to the log only, shortened. It cannot ask anything in a terminal: unlock the manager
  beforehand or use one that asks in its own window.
- **In `mail-sync`:** the program uses the user's terminal for input and error output and gets
  5 minutes, so a master-password prompt works there.
- **Failure:** a nonzero exit code, a timeout, a program that cannot start or bad output ends the
  run with a fixed `PasswordCommandException` message (no program output). No credentials were
  sent, so it is never a rejected sign-in: `signInRejected` stays false, MCP `sync` may start
  again, and `mail-sync` exits 2.

This keeps the password out of files and environment variables that agents read routinely. It
does not stop a process running as the same user from running the command itself.

Recipes (check each tool's own documentation; all of them must print only the password):

| Store | `passwordCommand` |
|---|---|
| Windows Credential Manager | `["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", "C:\\path\\to\\mail-mcp-server\\scripts\\Get-StoredPassword.ps1", "mail-mcp/work"]` |
| 1Password CLI | `["op", "read", "op://<vault>/<item>/password"]` |
| PowerShell SecretManagement | `["powershell", "-NoProfile", "-NonInteractive", "-Command", "$b = [Text.UTF8Encoding]::new($false).GetBytes((Get-Secret -Name mail-work -AsPlainText)); [Console]::OpenStandardOutput().Write($b, 0, $b.Length)"]`; a SecretStore vault must be unlocked or configured without a prompt |
| macOS Keychain | `["security", "find-generic-password", "-s", "mail-mcp", "-a", "work", "-w"]` |
| Linux Secret Service | `["secret-tool", "lookup", "service", "mail-mcp", "account", "work"]` |
| pass / gopass | `["pass", "show", "mail/work"]`; the first line must be the only line |

For Windows Credential Manager, add the credential yourself: Control Panel → Credential Manager →
Windows Credentials → Add a generic credential, internet or network address `mail-mcp/work`, any
user name, the password. `cmdkey /generic:mail-mcp/work /user:user /pass` should ask for the
password instead of taking it on the command line (unverified); never put it on the command line.
The script reads only generic credentials, prints UTF-8 without a line break and exits 1 with a
message on standard error when the credential is missing. Managers that need a master password on
standard input for each call (`keepassxc-cli`, Bitwarden CLI without a session) work only in
`mail-sync`.

## Word forms and analysis

`MailMcpProperties.resolvedWordFormsFile()` resolves `MAIL_MCP_WORD_FORMS_FILE` (including
leading `~`), or `<data-dir>/word-forms.txt`. The file is optional and never created automatically.
`index/WordForms` reads UTF-8 with an optional BOM. `#` starts comments; whitespace separates
letter-only words in a group. Each group needs two distinct words after lowercasing and `ё`→`е`;
within-group repeats are allowed, cross-group repeats fail with both line numbers. The first
word's Russian Snowball stem is used for every listed word. Explicitly listed words are
excluded from the Russian stop set, so `человек люди` also works; unlisted stop words and
English analysis stay unchanged. Single-word synonyms widen results.
See README.md "Optional word forms" for the edit → analyze → reindex cycle.

All three writer commands (`mail-sync`, `mail-reprocess`, `mail-reindex`) validate the file after
argument checking and before opening the index, locating pandoc or constructing a connection.
Invalid rules print `RESULT: FAILED (<filename> line <n>: <reason>)` and exit 2.
A missing/empty file uses ordinary analysis and does not add a `wordForms` commit entry.
The commands pass the loaded snapshot to the writer; changes require a rebuild per connection.
A requested sync also recreates an index with a different rule hash and restores from disk.

`cli/MailAnalyzeCommand` dispatches before Spring, independently of connections, index,
pandoc and network:

```powershell
java -jar build\libs\mail-mcp-server.jar mail-analyze писем договоры и
```

Example with `письмо писем` on line 1 of the optional file:

```text
Word forms: <data-dir>/word-forms.txt
писем -> письм (word-forms.txt line 1)
договоры -> договор (Snowball)
и -> stop word
RESULT: OK
```

With a missing file the first line adds `(none found; using Snowball)`. Each argument is
standard-tokenized and lowercased; every token prints its Russian body stem, rule source or
stop-word status. Exit 0 means success or `--help`/`-h`; exit 2 means usage, invalid rules or
file reading errors. Invalid rules print `RESULT: FAILED (<filename> line <n>: <reason>)`;
file I/O errors use `RESULT: FAILED (local file error; details withheld)`.

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
network request, reconciles disk and index state/identity, and discovers changes and metadata for
all included folders before removals. Unique apparent moves reuse saved content with the same
UUID and paths; unmatched vanished folders/messages are removed locally. New messages download
as `message.eml` and `meta.json`, with index commits after every 100 messages. New/retained folder
identities keep old/null cursors and vanished entries until all planned changes commit, then one
folders-file replacement saves all new cursors. Messages remain
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
with no new mail it ends with `0 downloaded`; reused moves also increase no downloaded, failed
or removed count. Each run reads identity and read/star metadata for all present refs in batches
of at most 100, without MIME/body retrieval; the first run seeds old stores without repeat
downloads or reprocessing. Flag-only changes also increase no counter and preserve content,
state/error and download time. Missing flag fields retain known values; explicit false clears
them. Matching remains heuristic and can conceal different remote content; see
`docs/storage-agent.md`. After an interruption, the next run removes
incomplete directories, restores missing or identity/flag-mismatched index entries from metadata and resumes without
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
| 2 | Wrong arguments, invalid configuration/selection, missing pandoc, OAuth failure, or failed password command (no sign-in) |
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
unchanged on disk; failed metadata-only catalog entries are retained if a rules change recreated
the index, without increasing the processed counter. Old `body.md` and `attachments/` are replaced, metadata becomes converted/failed,
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
The index commits every 100 entries and at the end, with commit data `schema=4` and
`wordForms=<hash>` only when nonempty rules are active.
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
search, listMessages and getThread ask for reindexing. Reader tools never initiate a rebuild.
Saved read/star observations survive the offline rebuild; old metadata without flags remains
unknown until a user-requested sync. Reindexing alone cannot obtain remote flag state.

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

For a personal Gmail mailbox the simpler default is an app password (`auth: app-password`),
kept in a password manager through `passwordCommand`: no Google Cloud project, no repeated
consent while the OAuth app is in Testing and no token file. Both grant full IMAP access and both
can be revoked separately. Use OAuth when app passwords are unavailable, for example when a
Workspace administrator disallows them. The OAuth refresh token remains a plaintext owner-only
file; encrypting it is plan.md Д27.

## Synthetic data sets

`testdata/mime/` has 20 MIME cases, `testdata/redaction/` has 256 detector cases and 8 mixed
documents, and `testdata/search/` has 48 messages with 80 control queries. Each directory has an
English README, JSON expectations and a complete manifest. Russian reports are in
`docs/research/RT-11-synthetic-mime.md`, `RT-12-redaction-samples.md`, `RT-13-search-queries.md`.
Helper generators/validators stay under ignored `testdata/.work/`; only data is tracked.
They feed `MimeReaderTest`, `SearchControlSetTest` and `SecretRedactorTest`. The latter checks
all 256 cases and eight mixed documents against the secrets-only output policy; PII is unchanged
and two incomplete PEM examples intentionally differ from the old expectations. See
`docs/redaction-agent.md`.

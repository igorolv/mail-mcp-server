# docs/build-config-agent.md — build, tests, configuration and the connections file

Read this before building, running tests, adding a setting, changing the connections file format
or changing a user-run command. The user-facing descriptions are the single source for formats,
output and exit codes; this file covers the implementation and the rules for agents:

- connections file fields, password sources, provider setup, validation:
  [connections.md](connections.md);
- user-run commands, their output and exit codes: [commands.md](commands.md);
- client registration and environment variables: [clients.md](clients.md);
- word forms file format: [search.md](search.md#word-forms).

When you change behavior those documents describe, update them in the same change.

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
`mail-mcp:` in `application.yml` with a `MAIL_MCP_*` override, and a row to this table and to
the table in `docs/clients.md`. Code that runs without Spring (the user-run commands) reads the
same variables through `MailMcpProperties.fromEnvironment`. Never hard-code `~/.mail-mcp-server`;
use `resolvedDataDir()`. Tests use `@TempDir`, never the developer's home directory.

## The connections file

Modeled on jdbc-mcp-server `docs/connections.md` @ `c2c4362`. Read once at startup by
`connections/ConnectionsLoader`; `ConnectionsLoaderTest` covers the format. To add a key: extend
the loader and `ConnectionDefinition`, add a test, and add a row to "Connection fields" in
`docs/connections.md`.

Validation has two levels, listed in `docs/connections.md`, "Configuration errors": problems
that make the whole file unusable stop startup; problems of one entry become its `configError`
in `listConnections` and leave the other connections working. Unknown keys and keys of the other
type are logged as warnings and ignored.

Rules for agents:

- Agents must not open, modify or append to the user's real connections file, and must not ask
  for it in chat. Use synthetic files for checks.
- Passwords are masked in `toString` of `EwsSettings` and `ImapSettings`, never logged and never
  returned by a tool. Keep it that way for every new secret-bearing field.
- Placeholder references are optional; a literal password in the owner-only external file is
  supported by the loader too.

### Password sources

`connections/Secret` is a literal (including a resolved `${VAR}`), a `WindowsCredential` or a
`PasswordCommand` (plan.md 3.1; decisions Д22, Д28). The last two are asked for only at sign-in:
`HttpClient5EwsTransport` sets credentials before its first request, IMAP asks on connect, so
opening a source session does not run them. The value is kept in the process; a rejected sign-in
drops it (`SyncService`). A failure to obtain it is a `SecretUnavailableException` with a fixed
message: no credentials were sent, so `signInRejected` stays false, MCP `sync` may start again
and `mail-sync` exits 2.

**`WindowsCredential`** reads a generic credential with `CredReadW` through the Java foreign
function API: no PowerShell, script or external program. The blob is decoded as UTF-16LE, as
Credential Manager stores it; trailing NUL characters are removed. A missing credential, an empty
password or an odd-length blob is a `SecretUnavailableException`. The jar manifest has
`Enable-Native-Access: ALL-UNNAMED`, so `java -jar` prints no restricted-method warning; tests and
the `mailSync` Gradle task pass `--enable-native-access=ALL-UNNAMED`. On platforms other than
64-bit Windows the key makes the entry unusable.

**`PasswordCommand`** runs the configured array without a shell. In the server the program gets
no input (end of file at once), 60 seconds, and its error output goes to the log only,
shortened. In `mail-sync` it inherits the user's terminal and gets 5 minutes. A nonzero exit code,
a timeout, a program that cannot start or output that breaks the rules in `docs/connections.md`
ends the run with the fixed message, never including the program's output.

## Word forms and analysis

The file format and the edit → analyze → reindex cycle are in `docs/search.md`, "Word forms".

`MailMcpProperties.resolvedWordFormsFile()` resolves `MAIL_MCP_WORD_FORMS_FILE` (including
leading `~`), or `<data-dir>/word-forms.txt`. The file is optional and never created automatically.
`index/WordForms` reads UTF-8 with an optional BOM; each group needs two distinct words after
lowercasing and `ё`→`е`; within-group repeats are allowed, cross-group repeats fail with both line
numbers. The first word's Russian Snowball stem is used for every listed word. Explicitly listed
words are excluded from the Russian stop set; unlisted stop words and English analysis stay
unchanged.

All three writer commands (`mail-sync`, `mail-reprocess`, `mail-reindex`) validate the file after
argument checking and before opening the index, locating pandoc or constructing a connection.
A missing/empty file uses ordinary analysis and does not add a `wordForms` commit entry.
The commands pass the loaded snapshot to the writer; changes require a rebuild per connection.
A requested sync also recreates an index with a different rule hash and restores from disk.

`cli/MailAnalyzeCommand` dispatches before Spring, independently of connections, index,
pandoc and network. Each argument is standard-tokenized and lowercased; every token prints its
Russian body stem, rule source or stop-word status. File I/O errors print
`RESULT: FAILED (local file error; details withheld)`.

## User-run commands

Output, exit codes and when to run each command: `docs/commands.md`. All commands dispatch from
`MailMcpServerApplication.main` before Spring starts (`docs/architecture-agent.md`).

### mail-sync

Run by the user only: `cli/MailSyncCommand` signs in to one real mailbox. The connection name is
required. Gradle alternative: `.\gradlew.bat mailSync --args="mailru"`.

One run uses one lazy source session and one Lucene writer. It takes the writer lock before any
network request, reconciles disk and index (every meta.json only when the previous run did not
complete; otherwise directory keys only, Д30), and discovers changes and metadata for
all included folders before removals. Unique apparent moves reuse saved content with the same
UUID and paths; unmatched vanished folders/messages are removed locally. New messages download
as `message.eml` and `meta.json`, with index commits after every 100 messages. New/retained folder
identities keep old/null cursors and vanished entries until all planned changes commit, then one
folders-file replacement saves all new cursors. Messages remain `converted` after MIME parsing,
attachment saving and body conversion; download or conversion failures get `state=failed` and
are not retried. Pandoc is checked before the index or mailbox is opened.

`SyncRunner` atomically writes `sync-status.json` before work, after folder progress and on
completion/failure. A rejected sign-in sets `signInRejected`; MCP `sync` then refuses starts
until a successful `mail-sync` clears the flag. MCP `sync` runs the same runner in the
background; the CLI remains synchronous. See `docs/tools-agent.md` for status fields and
cross-process reading, `docs/storage-agent.md` for move matching and reconciliation.

Each run reads identity metadata only for new messages, in batches of at most 100, without
MIME/body retrieval; read/star of known messages comes with the folder changes (Д29). Flag-only
changes and reused moves increase no counter and preserve content, state/error and download time.

Restoring an index rebuilt for an old schema or changed word forms prints progress every 1,000
messages, like `mail-reindex`; the MCP `sync` tool restores silently. Batch lines are cumulative;
the result sums only completed folder lines, including folders deleted locally. Configuration
and unexpected local details are withheld. An IMAP failure also prints one WARN line to stderr
with the failed operation and exception class names, never server text
(`docs/imap-source-agent.md`, "Failure diagnostics"). EWS failures print only these lines,
without the host or server message:

```text
RESULT: FAILED (EWS sign-in rejected; check the connection locally before the next run)
RESULT: FAILED (EWS error: <code>)
```

`<code>` is the response code for an EWS error response or SOAP fault when present; otherwise
it is the exception's simple class name. No EWS failure is retried, including `ErrorServerBusy`.

### mail-reprocess

`cli/MailReprocessCommand` converts local messages offline and never constructs a mailbox
connection or signs in. Gradle alternative: `.\gradlew.bat mailReprocess --args="mailru"`.
Pandoc is required on `PATH` even for plain text messages; there is no path setting or fallback.
It is checked after configuration/selection and before the Lucene writer is opened.

The command processes sorted message keys with both metadata and `message.eml`, including
previous conversion failures. Download failures without MIME and incomplete directories stay
unchanged on disk; failed metadata-only catalog entries are retained if a rules change recreated
the index, without increasing the processed counter. Old `body.md` and `attachments/` are
replaced, metadata becomes converted/failed, and the index entry is replaced. Commit every 100
processed messages and at the end. Distinct failure reasons are printed in descending count order
(ties by reason). Conversion uses one sandboxed pandoc process per HTML body, no retries or
timeout; see `docs/mime-agent.md`. Pandoc-dependent tests use a shared availability helper and
skip when it is absent.

### mail-reindex

`cli/MailReindexCommand` rebuilds one connection's index from local disk, without creating a
source or contacting a mailbox. Gradle alternative: `.\gradlew.bat mailReindex --args="work"`.
Pandoc must be on `PATH` and is checked before opening or recreating the index: messages still
downloaded may need body conversion.

No sync or other writer may be running for the same connection. The command forces recreation
in place, then calls `SyncRunner.restore`: converted messages load headers and saved body.md,
downloaded messages are processed, failed messages get metadata only. Incomplete directories
are removed. Originals and converted output remain unchanged for converted messages.
The index commits every 100 entries and at the end, with commit data `schema=4` and
`wordForms=<hash>` only when nonempty rules are active. Progress goes to the command's output
only; server restoration stays silent. The result counts all indexed messages, including failed
metadata entries.

Old-schema indexes also recreate automatically when a writer opens during sync; until then,
search, listMessages and getThread ask for reindexing. Reader tools never initiate a rebuild.
Saved read/star observations survive the offline rebuild; old metadata without flags remains
unknown until a user-requested sync.

### gmail-oauth

`cli/GmailOAuthCommand` obtains initial consent through the system browser, PKCE and a local
callback. Implementation, security and lifecycle rules: [gmail-oauth-agent.md](gmail-oauth-agent.md);
the user's Google Cloud setup: `docs/connections.md`, "Gmail". Only the user runs real
authorization and sign-in; agents may run `gmail-oauth --help`.

For a personal Gmail mailbox the simpler default is an app password kept in Windows Credential
Manager or a password manager: no Google Cloud project, no repeated consent while the OAuth app
is in Testing and no token file. The OAuth refresh token remains a plaintext owner-only file;
encrypting it is plan.md Д27.

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

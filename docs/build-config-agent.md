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
runs without Spring (the spike) reads the same variables through
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

## The EWS spike

Run by the user, never by an agent: it signs in to the real mailbox.

```powershell
.\gradlew.bat bootJar
java -jar build\libs\mail-mcp-server.jar ews-spike work
# or: .\gradlew.bat ewsSpike --args="work"
```

The connection name may be omitted when exactly one `ews` connection exists. Exit codes: `0`
success, `2` configuration problem (nothing was sent), `3` sign-in rejected, `4` EWS or network
error, `1` unexpected. Output format and what to check: `docs/ews-source-agent.md`.

## The IMAP spike

The Mail.ru user-run spike succeeded on 2026-10-25 (plan.md 3.4); Gmail still needs a successful
check. No real IMAP sign-in has been performed by agents. Add an IMAP entry
to the existing connections file locally using the schema above. Use the provider's app password;
fill the file only in your local editor. Agents must not open it or modify an existing secret-bearing
file to append entries. Do not paste it into chat. Placeholder references are optional; a literal
password in this owner-only external file is supported by the loader too (plan.md 5.7).

```powershell
.\gradlew.bat bootJar
java -jar build\libs\mail-mcp-server.jar imap-spike gmail
java -jar build\libs\mail-mcp-server.jar imap-spike mailru
# Alternative for one chosen connection: .\gradlew.bat imapSpike --args="gmail"
```

Run only the connection you have configured; a sign-in rejection stops the client without a retry.
The spike uses one `MailSource` session in three steps: LIST the folders to sync, EXAMINE INBOX
and list its UIDs with the UIDVALIDITY cursor, then download the highest UID with BODY.PEEK.
There is no per-download \Seen comparison; the command guard and offline tests verify read-only
behavior. It saves original MIME under `<data-dir>\<connection>\spike\`. The highest UID is
chosen; it is not a search for the newest Date header.
The report hides subjects, addresses, credentials and folder names. An empty inbox is successful.
Exit codes: 0 success, 2 invalid config/selection, 3 sign-in rejected, 4 IMAP/network/limit/local
save error, 1 unexpected failure. Details: `docs/imap-source-agent.md`.

## Gmail OAuth2

The user-run `gmail-oauth <connection>` command obtains initial consent through the system browser,
PKCE and a local callback. Detailed Google Cloud setup, the exact JSON entry and security/lifecycle
rules are in [gmail-oauth-agent.md](gmail-oauth-agent.md). Set `auth: oauth2`, remove the Gmail
password and add `oauthClientFile`; OAuth is accepted only for `imap.gmail.com:993` with TLS.

```powershell
java -jar build\libs\mail-mcp-server.jar gmail-oauth gmail
java -jar build\libs\mail-mcp-server.jar imap-spike gmail
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
Helper generators/validators stay under ignored `testdata/.work/`; only data is tracked, per the briefs.
They are inputs for the parser, index and search tests of plan.md steps 7.4–7.5 and for a later
optional filter, not implementations of those subsystems.

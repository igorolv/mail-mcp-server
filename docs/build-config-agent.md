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

Tests never contact a mail server. `HttpClient5EwsTransportTest` runs a fake EWS endpoint on
`127.0.0.1` that answers with a synthetic NTLM challenge; `McpStdioServerTest` starts the server in
a child JVM and talks JSON-RPC over its stdin/stdout. Do not add tests that sign in to a real
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
    }
  }
}
```

| Key | Types | Meaning |
|---|---|---|
| `type` | all | `ews` or `imap`; required |
| `description` | all | What the mailbox is for; shown by `listConnections` |
| `auth` | all | EWS: `ntlm` (default) or `basic`. IMAP: `app-password` (default); `oauth2` is reserved and gives a `configError` |
| `username`, `password` | all | Required. Any string value may use `${VAR}` |
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

## The EWS spike (stage 1)

Run by the user, never by an agent: it signs in to the real mailbox.

```powershell
.\gradlew.bat bootJar
java -jar build\libs\mail-mcp-server.jar ews-spike work
# or: .\gradlew.bat ewsSpike --args="work"
```

The connection name may be omitted when exactly one `ews` connection exists. Exit codes: `0`
success, `2` configuration problem (nothing was sent), `3` sign-in rejected, `4` EWS or network
error, `1` unexpected. Output format and what to check: `docs/ews-source-agent.md`.

# docs/architecture-agent.md — source layout, technology and layer boundaries

Read this when locating code, adding a package, changing a dependency, or changing the server
transport and Spring wiring. The target design is `docs/plan.md` section 6 (packages: 6.3), the
work order is section 7, decisions are in section 10. This file describes what exists now and
where planned code goes.

## Status (after IT-02, first half of step 7.2)

Implemented: Gradle build, Spring Boot application with the stdio MCP transport, the connections
file with a lazy registry, the `listConnections` tool, a read-only EWS client (`GetFolder`,
`FindItem`, `GetItem` with MIME) over Apache HttpClient 5 with NTLM, a read-only IMAP client on
Angus Mail and `ImapMailSource` (filtered folders, UID changes, MIME downloads in one session),
Gmail Desktop OAuth2 with PKCE and IMAP XOAUTH2, and three user-run commands: `ews-spike`,
`imap-spike`, `gmail-oauth`. User-run spikes succeeded
on 2026-10-25 for EWS (plan.md 3.3) and Mail.ru (plan.md 3.4). Gmail is supported but its sign-in
is unverified; the user deferred it, their local connections are `work` and `mailru`. Synthetic
RT-11–RT-13 data sets are in `testdata/`. IT-02 adds message directories with atomically written
`meta.json` and `folders.json`, and a derived Lucene catalog with a single writer and disk
reconciliation. See `docs/storage-agent.md`.

Not implemented: `EwsMailSource`, synchronization and `sync-status.json`, MIME parsing, pandoc
conversion, text indexing, and all mail tools except `listConnections`. The next task is IT-03,
the second half of plan.md 7.2 (`SyncRunner`, `mail-sync`, `spike/` → `cli/`).

## Transport and execution

`spring.main.web-application-type: none` keeps the server on stdio only. `System.out` is the
JSON-RPC channel; use SLF4J instead. `logback-spring.xml` has only `FILE` and `STDERR` appenders,
and the HttpClient `headers`/`wire` loggers are `OFF` because they would print `Authorization`
headers. `McpServerConfig` sets `immediateExecution(true)` on the `McpSyncServer` builder (copied
from redmine-mcp-server @ `f62b3e5`): tool calls of one session run one at a time, so long work —
synchronization — must run in the background and return at once (plan.md 6.6).
`config/McpStdioConcurrencyTest` documents the race; its disabled half passed once with MCP SDK
2.1.0 on 2026-10-24, which is not yet a reason to drop the setting.

The non-server modes are `ews-spike`, `imap-spike` and `gmail-oauth`: `MailMcpServerApplication.main`
dispatches to their commands before Spring starts, so the stdio transport is never opened.
All three select `logback-spike.xml` (stderr only) through the `logback.configurationFile`
system property. Step 7.2 adds `mail-sync` the same way.

## Tech stack and version sources

- **Java 25** toolchain (`build.gradle.kts`).
- **Spring Boot `4.2.0-RC1`**, **Spring AI `2.1.0-RC1`** MCP server (stdio) — decision П6; move to
  `2.1.0` when released. MCP Java SDK 2.1.0, Jackson 3 (`tools.jackson`) come transitively.
- **Apache HttpClient 5.6.5** classic — EWS transport with explicitly registered NTLM (П1).
- **Eclipse Angus Mail 2.0.5**, **Jakarta Mail API 2.1.5** — IMAP with implicit TLS (П3); also the
  MIME parser from step 7.4 (plan.md 6.7).
- **Lucene Core 10.5.2** — derived catalog, NRT lookups and the per-connection write lock (IT-02).
- StAX from the JDK for SOAP; no generated EWS classes, no `ews-java-api`.
- **Gradle 9.8.2** wrapper with the version catalog `gradle/libs.versions.toml`.

Planned, added only in their step: Lucene text fields with `RussianAnalyzer` (7.4), pandoc
as a required external program run as `pandoc server` (7.4), PDFBox and POI from the
redmine-mcp-server parsers (7.6). Do not add Mime4j, jsoup, SQLite or any other library the plan
does not name (plan.md 6.9).

If you change a dependency, update `libs.versions.toml`, not the build script.

## Source layout

The package root is `mail.mcp` (deliberately without a company or account name, see `AGENTS.md`).
There is no `service/` layer. Dependencies flow downward only; planned packages are in
brackets:

```
tools/ ──► [sync/] ──► source/ (MailSource; ews/, imap/)
  │           ├──────► [mime/], [convert/]
  │           └──────► store/, index/
  └──────────────────► store/, index/          (tool readers planned)
index/ ──► store/                              (reconciliation)
connections/ ──► source/                        (builds lazy MailSource session factories)
model/   — shared disk records; more records planned for step 7.4
api/     — MCP wire records returned by tools
config/  — Spring configuration, properties, MCP customizer
spike/   — user-run commands without Spring (becomes cli/ in step 7.2)
```

### Existing packages

| Package | Responsibility | What goes here |
|---|---|---|
| `MailMcpServerApplication` | Entry point. Empty apart from command dispatch. | Nothing else. |
| `config/` | Spring wiring. | `MailMcpProperties` (`mail-mcp.*`), `ConnectionsConfig` (loader + registry beans), `McpServerConfig`, `JsonConfig`. |
| `connections/` | The connections file and the registry. | `ConnectionsFile` (JSON shape), `ConnectionsLoader`, `ConnectionDefinition`, `EnvironmentPlaceholders`, `ConnectionType`, `AuthKind`, `ConnectionRegistry`, `MailConnection`, `MailConnectionFactory`. `MailConnection.openSource()` supplies a fresh lazy IMAP session, owned by its caller. EWS has no source until step 7.3; the EWS spike creates its client directly. |
| `source/` (root) | Common mailbox session contract. | `MailSource`, `RemoteFolder`, `FolderChanges`, `Downloaded`, `MessageUnavailableException` (GONE/TOO_LARGE leaves the session usable). |
| `source/ews/` | Thin read-only EWS client. | `EwsClient`, `EwsOperation` (allow-list), `EwsXml` (envelope, parser settings), `EwsResponseReader` (StAX), `EwsTransport` + `HttpClient5EwsTransport`, result records, `Ews*Exception`. See `docs/ews-source-agent.md`. |
| `source/imap/` | Read-only IMAP source and client. | `ImapMailSource`, `ImapClient` (one session across folders/downloads), `ReadOnlyImapStore` (wire command guard), `ImapSettings`, `ImapFolderInfo`, `FolderUids`, sanitized `ImapException`. See `docs/imap-source-agent.md`. |
| `source/oauth/` | Provider-independent OAuth components. | `OAuthLoopbackReceiver` and sanitized `OAuthException`. No provider endpoints, settings or provider-specific messages. |
| `source/oauth/google/` | Google Desktop OAuth and refresh credentials. | All `Google*` classes and the package-private `OAuthJson` helper. Depends on shared `source/oauth/` components. See `docs/gmail-oauth-agent.md`. |
| `model/` | Shared disk records and lifecycle. | `MessageState`, `MessageMeta`, `LocalFolder` (IT-02), one type per file. Planned for 7.4: `MailMessage`, `Address`, `AttachmentInfo`, `MessageCard`. |
| `store/` | Local message directories and metadata. | `MessageStore`: UUID paths, atomic `meta.json` and `folders.json`, listing and deletion. `sync-status.json` remains planned for IT-03. See `docs/storage-agent.md`. |
| `index/` | Derived Lucene catalog, writer side. | `MailIndex`: metadata fields, NRT key/ref lookups, commit and reconciliation through `store/`; `IndexLockedException`. Text fields and tool readers remain planned for 7.4. See `docs/storage-agent.md`. |
| `tools/` | Thin `@McpTool` adapters, Spring `@Service` beans. | `ConnectionTools` (`listConnections`), `ToolLogger`. |
| `api/` | Stable MCP response records with `@Schema`. | `ConnectionInfo`, `ListConnectionsResult`. Never expose `source/` or `model/` records or settings here. |
| `spike/` | User-run commands without Spring. | `EwsSpikeCommand`, `ImapSpikeCommand`, `GmailOAuthCommand`. Renamed to `cli/` in step 7.2. |

### Planned packages

Add a package only in the step that needs it, then move its row to the table above.

| Package | Step | What goes here (plan.md 6.3) |
|---|---|---|
| `sync/` | 7.2 | `SyncRunner` (one run), `SyncService` (background, status) |
| `cli/` | 7.2 | today's `spike/` commands plus `mail-sync` |
| `mime/` | 7.4 | `MimeReader`: `.eml` → `MailMessage` and part streams |
| `convert/` | 7.4, 7.6 | `PandocServer` (7.4); PDF/XLSX/PPTX converters copied from redmine-mcp-server (7.6) |

## Rules for new code

- Every tool takes a required `connection` argument resolved through
  `ConnectionRegistry.resolve`; there is no default connection and no cross-mailbox call.
- Tools return `api/` records; secrets, hosts and logins never appear in a tool response. Mail
  content and local paths to message files may be returned (plan.md 6.8).
- Anything that reaches a mail server goes through `source/`; only allow-listed EWS operations
  exist (`EwsOperation`). Adding one needs a decision in plan.md section 10.
- Follow plan.md 6.9: no limit budgets, extra databases, diagnostic codes or speculative
  safeguards that the step's done criterion does not require.
- Code copied from sibling projects names its source and commit in the class Javadoc (П5).
- Keep OAuth provider implementations in `source/oauth/<provider>/`. Shared `source/oauth/`
  components must not import provider packages or mention a provider in their messages. Add a
  new provider package when it is implemented; do not introduce an unused provider framework.

Resources: `application.yml` (defaults and env overrides), `logback-spring.xml`,
`logback-spike.xml`. Tests mirror the main packages; synthetic EWS responses live in
`src/test/resources/ews/`, mail fixtures in `testdata/`.

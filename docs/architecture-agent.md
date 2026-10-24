# docs/architecture-agent.md — source layout, technology and layer boundaries

Read this when locating code, adding a package, changing a dependency, or changing the server
transport and Spring wiring. The design behind it is in `docs/plan.md` (sections 5–7, decisions
П1–П13 in section 10); this file describes what is implemented.

## Status (stage 1, EWS spike)

Implemented: Gradle build, empty Spring Boot application with the stdio MCP transport, the
connections file with a lazy registry, the `listConnections` tool, a read-only EWS client
(`GetFolder`, `FindItem`, `GetItem` with MIME) over Apache HttpClient 5 with NTLM, and the EWS
spike command the user runs. Not implemented yet: IMAP source, local store (SQLite), MIME parsing,
attachment extraction, Lucene index, redaction, synchronization, and all mail tools except
`listConnections`.

## Transport and execution

`spring.main.web-application-type: none` keeps the server on stdio only. `System.out` is the
JSON-RPC channel; use SLF4J instead. `logback-spring.xml` has only `FILE` and `STDERR` appenders,
and the HttpClient `headers`/`wire` loggers are `OFF` because they would print `Authorization`
headers. `McpServerConfig` sets `immediateExecution(true)` on the `McpSyncServer` builder (copied
from redmine-mcp-server @ `f62b3e5`): tool calls of one session run one at a time, so long work —
future synchronization — must run in the background and return at once (plan.md 5.2).
`config/McpStdioConcurrencyTest` documents the race; its disabled half passed once with MCP SDK
2.1.0 on 2026-10-24, which is not yet a reason to drop the setting.

The EWS spike is the one non-server mode: `MailMcpServerApplication.main` dispatches
`ews-spike` to `spike/EwsSpikeCommand` before Spring starts, so the stdio transport is never opened
in that mode. The spike selects `logback-spike.xml` (stderr only) through the
`logback.configurationFile` system property.

## Tech stack and version sources

- **Java 25** toolchain (`build.gradle.kts`).
- **Spring Boot `4.2.0-RC1`**, **Spring AI `2.1.0-RC1`** MCP server (stdio) — decision П6; move to
  `2.1.0` when released. MCP Java SDK 2.1.0, Jackson 3 (`tools.jackson`) come transitively.
- **Apache HttpClient 5.6.5** classic — EWS transport with explicitly registered NTLM (П1).
- StAX from the JDK for SOAP; no generated EWS classes, no `ews-java-api`.
- **Gradle 9.8.2** wrapper with the version catalog `gradle/libs.versions.toml`.

If you change a dependency, update `libs.versions.toml`, not the build script.

## Source layout

The package root is `mail.mcp` (deliberately without a company or account name, see `AGENTS.md`).
Dependencies flow downward only:

```
tools/  →  service/ (not yet)  →  source/ews, source/imap
   ↓                                  ↑ settings records
connections/  ────────────────────────┘
api/     ← MCP wire records returned by tools
config/  — Spring configuration, properties, MCP customizer
spike/   — the EWS spike command, no Spring
```

| Package | Responsibility | What goes here |
|---|---|---|
| `MailMcpServerApplication` | Entry point. Empty apart from the `ews-spike` dispatch. | Nothing else. |
| `config/` | Spring wiring. | `MailMcpProperties` (`mail-mcp.*`), `ConnectionsConfig` (loader + registry beans), `McpServerConfig`, `JsonConfig`. |
| `connections/` | The connections file and the registry. | `ConnectionsFile` (JSON shape), `ConnectionsLoader`, `ConnectionDefinition`, `EnvironmentPlaceholders`, `ConnectionType`, `AuthKind`, `ConnectionRegistry`, `MailConnection`, `MailConnectionFactory`. |
| `source/ews/` | Thin read-only EWS client. | `EwsClient`, `EwsOperation` (allow-list), `EwsXml` (envelope, parser settings), `EwsResponseReader` (StAX), `EwsTransport` + `HttpClient5EwsTransport`, result records, `Ews*Exception`. See `docs/ews-source-agent.md`. |
| `source/imap/` | IMAP source (later). | Only `ImapSettings` so far. |
| `tools/` | Thin `@McpTool` adapters, Spring `@Service` beans. | `ConnectionTools` (`listConnections`), `ToolLogger`. |
| `api/` | Stable MCP response records with `@Schema`. | `ConnectionInfo`, `ListConnectionsResult`. Never expose `source/` records or settings here. |
| `spike/` | Stage-1 spike, run by the user. | `EwsSpikeCommand`. Delete or fold into a real command once synchronization exists. |

Planned packages (`service/`, `store/`, `extraction/`, `index/`, `redaction/`) follow plan.md
section 6; add each to this table when it gets code.

## Rules for new code

- Every future tool takes a required `connection` argument resolved through
  `ConnectionRegistry.resolve`; there is no default connection and no cross-mailbox call.
- Tools return `api/` records; secrets, hosts and logins never appear in a tool response.
- Anything that reaches a mail server goes through `source/`; only allow-listed EWS operations
  exist (`EwsOperation`). Adding one needs a decision in plan.md section 10.
- Code copied from sibling projects names its source and commit in the class Javadoc (П5).

Resources: `application.yml` (defaults and env overrides), `logback-spring.xml`,
`logback-spike.xml`. Tests mirror the main packages; synthetic EWS responses live in
`src/test/resources/ews/`.

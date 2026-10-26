# docs/architecture-agent.md — source layout, technology and layer boundaries

Read this when locating code, adding a package, changing a dependency, or changing the server
transport and Spring wiring. The design summary is `docs/plan.md` section 3 (packages: 3.3),
possible improvements are in section 4, decisions in section 5. This file describes what exists.

## Status (2026-10-26: draft version complete)

Implemented and checked on the user's real mailboxes (plan.md section 2):

- Gradle build, Spring Boot application with the stdio MCP transport, the connections file with
  a lazy registry.
- Read-only EWS client over Apache HttpClient 5 with NTLM (`GetFolder`, `FindFolder`,
  `SyncFolderItems`, `GetItem` with MIME) and `EwsMailSource`.
- Read-only IMAP client on Angus Mail and `ImapMailSource`; Gmail Desktop OAuth2 with PKCE and
  XOAUTH2 (supported, real sign-in unverified; the user deferred Gmail).
- Message directories with `meta.json`, `body.md`, attachment files and their `.md`; MIME
  reading (`docs/mime-agent.md`); a derived Lucene index, schema 3, with Russian/English search
  (`docs/storage-agent.md`).
- `SyncRunner`, background `SyncService` and `sync-status.json`.
- Eight MCP tools (`docs/tools-agent.md`): `listConnections`, `sync`, `syncStatus`,
  `listFolders`, `listMessages`, `getMessage`, `getAttachment`, `search`.
- User-run commands in `cli/`: `mail-sync`, `mail-reprocess`, `mail-reindex`, `gmail-oauth`.
- Synthetic data sets in `testdata/` (`mime`, `search`, `redaction`); the search control set
  gives recall 79/86 = 0.919 with 7 false positives.

`convert/PdfText`, `XlsxText`/`XlsText` and `PptxText`/`PptText` are adapted from redmine-mcp-server
`extraction/parser/{PdfTextParser,XlsxTextParser,PptxTextParser}.java` at commit `f62b3e5`.

Optional tool-output secret replacement for Д6 was accepted on 2026-10-26 after offline review;
see `docs/redaction-agent.md`. Local files and the index remain unfiltered.

Not implemented: PII anonymization, MCP resources, threads, flags, embeddings, writing
mail; see plan.md section 4.

## Transport and execution

`spring.main.web-application-type: none` keeps the server on stdio only. `System.out` is the
JSON-RPC channel; use SLF4J instead. `logback-spring.xml` has only `FILE` and `STDERR` appenders,
and the HttpClient `headers`/`wire` loggers are `OFF` because they would print `Authorization`
headers. Both logging configurations keep the Angus/Jakarta Mail loggers off and log only errors
from PDFBox and FontBox. `McpServerConfig` sets `immediateExecution(true)` on the `McpSyncServer` builder (copied
from redmine-mcp-server @ `f62b3e5`): tool calls of one session run one at a time, so long work —
synchronization — runs in the background and returns at once (plan.md 3.6).
`config/McpStdioConcurrencyTest` documents the race; its disabled half passed once with MCP SDK
2.1.0 on 2026-10-24, which is not yet a reason to drop the setting.

The non-server modes are `gmail-oauth`, `mail-sync`, `mail-reprocess`
and `mail-reindex`: `MailMcpServerApplication.main`
dispatches to their commands before Spring starts, so the stdio transport is never opened.
All four select `logback-cli.xml` (stderr only) through the `logback.configurationFile`
system property. `mail-sync` runs synchronously and prints progress; MCP `sync` starts a daemon
platform thread through `SyncService` and returns at once.

## Tech stack and version sources

- **Java 25** toolchain (`build.gradle.kts`).
- **Spring Boot `4.2.0-RC1`**, **Spring AI `2.1.0-RC1`** MCP server (stdio) — decision П6; move to
  `2.1.0` when released. MCP Java SDK 2.1.0, Jackson 3 (`tools.jackson`) come transitively.
- **Apache HttpClient 5.6.5** classic — EWS transport with explicitly registered NTLM (П1).
- **Eclipse Angus Mail 2.0.5**, **Jakarta Mail API 2.1.5** — IMAP with implicit TLS (П3); also the
  MIME parser (plan.md 3.5).
- **Lucene Core 10.5.2** — derived catalog, NRT lookups and the per-connection write lock.
- **Lucene analysis-common, queryparser, highlighter 10.5.2** — field analyzers,
  `SimpleQueryParser` and `UnifiedHighlighter`, all on the catalog's version.
- **PDFBox 3.0.5**, **POI poi-ooxml and poi-scratchpad 5.4.1** — PDF, XLSX/PPTX text;
  HWPF/HSSF/HSLF for binary DOC/XLS/PPT, HSMF/HMEF for MSG/TNEF children and message text.
- StAX from the JDK for SOAP; no generated EWS classes, no `ews-java-api`.
- **Gradle 9.8.2** wrapper with the version catalog `gradle/libs.versions.toml`.

Pandoc is required on `PATH` for sync/reprocessing/reindexing; `Pandoc` runs one sandboxed process per
body or supported attachment conversion. PDFBox and POI run in-process, without the sibling
parser framework. Do not add Mime4j, jsoup, SQLite or any other library the plan
does not name (plan.md 3.8).

If you change a dependency, update `libs.versions.toml`, not the build script.

## Source layout

The package root is `mail.mcp` (deliberately without a company or account name, see `AGENTS.md`).
There is no `service/` layer. Dependencies flow downward only:

```
tools/ ──► sync/SyncService ──► sync/ ──► source/ (MailSource; ews/, imap/)
  │                                ├────► mime/, convert/
  │                                └────► store/, index/
  └──────────────────► store/, index/          (committed tool readers)
index/ ──► store/                              (reconciliation)
       └─► mime/                               (read-only snippet attachment enumeration)
connections/ ──► source/                        (builds lazy MailSource session factories)
model/   — shared disk and catalog records
api/     — MCP wire records returned by tools
config/  — Spring configuration, properties, MCP customizer
cli/ ──► sync/, connections/, source/, store/, index/  (user-run commands without Spring)
```

### Existing packages

| Package | Responsibility | What goes here |
|---|---|---|
| `MailMcpServerApplication` | Entry point. Empty apart from command dispatch. | Nothing else. |
| `config/` | Spring wiring. | `MailMcpProperties` (`mail-mcp.*`), `ConnectionsConfig` (loader + registry beans), `McpServerConfig`, `JsonConfig`, `SyncConfig` (`SyncService` with `Pandoc::locate`). |
| `connections/` | The connections file and the registry. | `ConnectionsFile` (JSON shape), `ConnectionsLoader`, `ConnectionDefinition`, `EnvironmentPlaceholders`, `ConnectionType`, `AuthKind`, `ConnectionRegistry`, `MailConnection`, `MailConnectionFactory`. `MailConnection.openSource()` supplies a fresh lazy IMAP or EWS session, owned by its caller. |
| `source/` (root) | Common mailbox session contract. | `MailSource`, `RemoteFolder`, `FolderChanges`, `Downloaded`, `MessageUnavailableException` (GONE/TOO_LARGE leaves the session usable). |
| `source/ews/` | Read-only EWS source and thin client. | `EwsMailSource`, `EwsClient`, `EwsOperation` (allow-list), `EwsXml` (envelope, parser settings), `EwsResponseReader` (StAX), `EwsTransport` + `HttpClient5EwsTransport`, result records (`EwsFolderNode`, `EwsFolderPage`, `EwsSyncPage`), `Ews*Exception`. See `docs/ews-source-agent.md`. |
| `source/imap/` | Read-only IMAP source and client. | `ImapMailSource`, `ImapClient` (one session across folders/downloads), `ReadOnlyImapStore` (wire command guard), `ImapSettings`, `ImapFolderInfo`, `FolderUids`, sanitized `ImapException`. See `docs/imap-source-agent.md`. |
| `source/oauth/` | Provider-independent OAuth components. | `OAuthLoopbackReceiver` and sanitized `OAuthException`. No provider endpoints, settings or provider-specific messages. |
| `source/oauth/google/` | Google Desktop OAuth and refresh credentials. | All `Google*` classes and the package-private `OAuthJson` helper. Depends on shared `source/oauth/` components. See `docs/gmail-oauth-agent.md`. |
| `model/` | Shared disk records and lifecycle. | `MessageState`, `MessageMeta`, `LocalFolder`, `MailMessage`, `Address`, `AttachmentInfo`, `MessageCard`, `SearchHit`, `SearchResults`, `FolderProgress`, `SyncStatus`, one type per file. |
| `store/` | Local message directories and metadata. | `MessageStore`: UUID paths, atomic `meta.json`, `folders.json` and `sync-status.json`, listing and deletion. See `docs/storage-agent.md`. |
| `index/` | Derived Lucene catalog and committed readers. | `MailIndex`: schema 3, metadata, headers/body/attachment text fields, NRT key/ref lookups, commit and reconciliation; `IndexLockedException`. `MailAnalyzers` shares per-field analysis, `SearchField` defines groups. `MailIndexReader` reads committed counts, sorted cards and scored search hits without the writer lock. Package-private `SearchSnippets` selects a body or saved attachment passage after paging. See `docs/storage-agent.md`. |
| `tools/` | Thin `@McpTool` adapters, Spring `@Service` beans. | `ConnectionTools` (`listConnections`), `SyncTools` (`sync`, `syncStatus`), `MailTools` (`listFolders`, `listMessages`, `getMessage`), `SearchTools` (`search`), `ToolLogger`. |
| `api/` | Stable MCP response records with `@Schema`. | Discovery, sync start/status, folder/message pages, body chunks, attachment references, `SearchResult` and `SearchHitInfo`. Never expose `source/` or `model/` records or settings here. |
| `sync/` | One synchronization run and local conversion. | `SyncRunner`, `MessageProcessor`, `Processed`: reconcile by state, folders, removals, downloads/conversion in batches of 100, cursor after the folder. Caller owns the source and writer. `SyncService` owns background runs, `SyncStart` their immediate result; `SyncErrors` shares fixed CLI/status failure messages. |
| `cli/` | User-run commands without Spring. | `GmailOAuthCommand`, `MailSyncCommand`, `MailReprocessCommand`, `MailReindexCommand`. Check pandoc before the index or sign-in; source closes before the index. Reprocessing and reindexing are offline. |
| `mime/` | Local MIME and container reading. | `MimeReader`, `ParsedMessage`: decoded headers, selected body and MIME attachment files. `Containers` coordinates breadth-first EML/ZIP/MSG/TNEF expansion; package-private `MsgReader`/`TnefReader` enumerate immediate children using POI. See `docs/mime-agent.md`. |
| `convert/` | Body and attachment text. | `Pandoc`, `PandocException`: sandboxed process per conversion; `AttachmentText` dispatches formats. `PdfText`, `XlsxText`/`XlsText`, `PptxText`/`PptText` adapt sibling extraction methods; `DocText` uses HWPF `WordExtractor`. |
| `redaction/` | Tool-output text only. | `SecretRedactor`: offline detectors and merged replacement; wired by `config/RedactionConfig`. Never called by storage, index writers, MIME parsing, conversion or synchronization. |

`SearchSnippets` uses `MimeReader.read(eml, null)` and `Containers.expand(..., false)` to enumerate
MIME attachments followed by EML/ZIP/MSG/TNEF children breadth first without writing.
Only eligible returned page hits reach local MIME and UTF-8
`attachments/<file>.md`; no source, converter, cache or manifest is involved. For `any`, a real
body highlight wins; otherwise the first matching attachment is used. `attachmentText` selects
attachments first. Other field groups and legacy reader overloads keep body-only snippets.
Missing local candidates retain body context. The complete selected text is transformed before
rendering. Search hits expose the exact lookup filename and a separately filtered display name
only for attachment snippets. Schema 3 and disk layout are unchanged. Existing data requires
`mail-reprocess` to create newly supported children/Markdown; finish a connection before using
attachment lookup/search because extracted filenames can be renumbered in mixed lists.

### Planned packages

No additional packages are needed for the current step. Add packages only in the step that
needs them and register them above. Attachment converters live in `convert/`.

## Rules for new code

- Every tool takes a required `connection` argument resolved through
  `ConnectionRegistry` name/definition lookups (source resolution only for sync); there is no
  default connection and no cross-mailbox call.
- Tools return `api/` records; connection credentials, hosts and logins are not returned. Mail
  content and local paths to message files may be returned (plan.md 3.7). Optional output
  filtering applies to human-readable response text, not original paths or lookup identifiers.
- Anything that reaches a mail server goes through `source/`; only allow-listed EWS operations
  exist (`EwsOperation`). Adding one needs a decision in plan.md section 5.
- Follow plan.md 3.8: no limit budgets, extra databases, diagnostic codes or speculative
  safeguards that the step's done criterion does not require.
- Code copied from sibling projects names its source and commit in the class Javadoc (П5).
- Keep OAuth provider implementations in `source/oauth/<provider>/`. Shared `source/oauth/`
  components must not import provider packages or mention a provider in their messages. Add a
  new provider package when it is implemented; do not introduce an unused provider framework.

Resources: `application.yml` (defaults and env overrides), `logback-spring.xml`,
`logback-cli.xml`. Tests mirror the main packages; synthetic EWS responses live in
`src/test/resources/ews/`, mail fixtures in `testdata/`.

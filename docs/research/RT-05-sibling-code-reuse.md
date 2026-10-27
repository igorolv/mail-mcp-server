# RT-05 — Reusing code from sibling projects

- Date: 2026-10-24
- Author: Codex, RT-05 session
- Brief: docs/research-tasks/RT-05-sibling-code-reuse.md
- Local working trees inspected: `C:\git\redmine-mcp-server` and `C:\git\jdbc-mcp-server`.
- Revisions: Redmine `f62b3e5d546d04533e4259a573293d949939a48d` (2026-09-30), JDBC `c2c4362121b4b51868add386f4916346aacc38a9` (2026-09-25); both trees clean. [code][1][9]

## Summary

- [code] In Redmine, all 24 files in `extraction/` were checked, including 14 `parser/` files, and all 13 files in `compression/`, including `steps/`. Almost all extraction is independent of Redmine REST, but eight classes directly use `RedmineMcpProperties` [2][4].
- [code] The pipeline runs **all** applicable PRIMARY parsers, then FALLBACK parsers. DOCX produces one text result through `hasTextPart()`, while images and embedded objects are handled separately [2].
- [code] There is no ready-made common protection for depth and total bytes: ZIP checks depth, its byte counter restarts for every archive, and DOCX is unpacked without these checks. Reuse requires stronger limits [2].
- [code] Of response compression, only `ResponseCompressor`, `CompressionStep`, and `CompressionResult` are common; attachment handlers depend on `api.AttachmentContent`, and the rest depend on Redmine issues and journals [4].
- [code] Both projects use the same `immediateExecution(true)` pattern, Jackson 3 with `NON_NULL`, and logging to stderr and a file. JDBC additionally has MDC with the connection name and a test for serialized stdio responses [5][11][13].
- [code] In JDBC, all nine files in `connection/` and the three required tool files were checked. `EnvironmentPlaceholders` is fully generic as a standalone class; the registry is reusable after replacing types, while the context factory is strongly coupled to JDBC [10][12].
- [code] The stacks differ: Redmine uses Java 25 / Boot `4.2.0-M2` / AI `2.1.0-M1`; JDBC uses Java 21 / Boot `4.0.0` / AI `2.0.1`. Gradle is the same: `9.3.1`. JDBC does not declare PDFBox, POI, or Tika [7][15].
- [assumption] For the first stage, copying with adaptation and transferring suitable tests is recommended. Consider a shared library after a second real consumer of the same stable contract appears; the decision remains with the user.

## Answers to the questions

### В1. Extraction pipeline: purpose, dependencies, size, tests

[code] Full inventory is in the “Extraction” table: 24 rows for source files, with nested types included under their owning files. The package does not import `client/`, `api/`, or `service/`; direct property dependencies occur in `ExtractionLimits`, `ExtractionPipeline`, `ParseContext`, `PandocAvailability`, `DocxPandocParser`, `ZipParser`, `TikaMetadataParser`, and `TikaTextFallbackParser` — **eight classes**, including both Tika parsers [2].

[code] Main path: `extract(Path, logicalName, contentType, workDir)` → shared `ParseContext` → PRIMARY → FALLBACK. Spring assembles `List<DocumentParser>` with `@Order`; `processNow()` immediately runs a child file and adds depth. `InputSink` adds `producer` and `parent`, and counts parts and whether the current input has text [2].

[code] `docs/retrieval-extraction-agent.md` still says “first canParse wins” and mentions `shouldStop()`, but the real interface has `applies()`, `emit()`, `processNow()`, `hasTextPart()`, `emittedCount()`, and `primaryApplied()`; `shouldStop()` is absent. Use source code when transferring [2][8].

[code] Limits are enforced in several places, so the name `ExtractionLimits` does not mean complete protection [2][6]:

- `maxTotalParts` limits emitted parts and entry into recursive calls; an already running ZIP/DOCX parser may continue reading and writing files after the limit is reached.
- `maxDepth` is checked only by `ZipParser.applies()`. `processNow()` and DOCX extractors do not perform a common depth check.
- `maxTotalBytes` is a local counter for one `ZipParser.parse()` call, not a tree-wide counter. ZIP reads every entry into `ByteArrayOutputStream`, limiting each entry's size.
- DOCX objects are read through `readAllBytes()`, images through `getData()`; their unpacked bytes do not count against a common limit. PDF/POI/plain text may build all text in memory.
- `AttachmentService` applies the returned-character limit **after** extraction; `ExtractionPipeline` does not apply it.

[assumption] Before using this for mail, add shared counters for bytes/parts/depth, stop parsers when limits are exhausted, and give child files separate directories. ZIP uses shared `workDir/zip/<entryName>`, while DOCX hashes only `logicalName`, so equal names in different branches can refer to the same path [2].

[code] PDFBox extracts the text layer without its own OCR; a missing text layer becomes a status string. TXT is read strictly as UTF-8; HTML is also treated as plain text and keeps its markup. Tika handles unknown formats, but there is no separate MIME pipeline modeling nested messages [2].

[code] Main checks are in `DocumentExtractionTest` and `AttachmentServiceTest`, with a separate `DocxTextParserTest`. Pandoc is checked by a test tagged `@Tag("integration")` and conditional on the executable being present; ordinary `test` excludes that tag. Merely including a parser in `ExtractionTestPipelines` does not prove its branches were tested [3][7].

[code] Boundaries with the application lie outside the extraction package itself [2][3][6]:

| Boundary | Already separated | Still project-specific |
|---|---|---|
| Getting the source file | Pipeline accepts a ready `Path` | `AttachmentService` receives RedmineAttachment and calls IssueSnapshotService |
| Working directory | Passed as `workDir` argument | Computed by `IssueSnapshotService.attachmentExtractedDir(issueId, attachmentId)` |
| Internal result | `ExtractedPart` does not import MCP API | AttachmentService converts it to `AttachmentContent.Part` |
| Response limit | Parsers do not know about MCP request | effectiveLimits/toContentPart and truncated/totalChars indicators live in AttachmentService |
| Parser set | Production uses Spring components and @Order | Test helper builds the list manually and disables Pandoc |

[assumption] Therefore copying `extraction/` does not require copying the Redmine client or IssueSnapshotService. It needs its own wrapper for the ready file, settings, and result; copying all of AttachmentService for its few text-limit functions is not worthwhile.

### В2. Response limits: common code and Redmine coupling

[code] `ResponseCompressor.fit<T>()` applies steps in sequence and measures `mapper.writeValueAsString(value).length()`. `CompressionStep<T>` and `CompressionResult<T>` contain no domain types. They can be transferred with a mechanical package change; Spring, SLF4J, and Jackson remain compressor dependencies [4].

[code] This limits Java characters, not tokens or UTF-8 bytes. Once steps are exhausted, the compressor returns `overBudget=true` rather than rejecting the response. `IssueCompression` and `AttachmentContentCompression` add `compressionNotes` after measurement and do not treat `overBudget` as an error [4].

[code] `HistoryCompression` separately measures characters and UTF-8, includes notes in `Draft`, preserves `journalId`, and returns `nextOffset`. Even a single oversized event gets a recoverably shortened representation. However, all data shapes and continuation methods are tied to Redmine [4].

[assumption] The common step mechanism and principle “shorten → explain how to continue” are suitable for mail. Journal/changeset classes are unnecessary; attachment handlers must be rewritten for mail response records and sanitized files. Check the final size after adding all notes [4][6].

### В3. Server infrastructure, JSON, logging, and MDC

[code] Both `McpServerConfig` classes register `McpSyncServerCustomizer`, which calls `serverBuilder.immediateExecution(true)`. The JDBC comment ties this to lost competing SDK responses; this is repository evidence, and the status of an upstream fix was not checked separately [5][11].

[code] `McpStdioConcurrencyTest.immediateExecutionKeepsToolResponsesSerialized()` uses local input/output channels: the second tool does not start until the first completes. A test of concurrent completion with ordinary SDK settings is marked `@Disabled`. The test builds an SDK server manually; it does not test Spring bean registration [13].

[assumption] Transfer the fix with its test and additionally verify customizer wiring when building the mail server. A long-running sync tool will delay subsequent calls in the same session; isolating registry locks does not remove this transport limitation [10][13].

[code] `JsonConfig` behaves identically in both projects: Jackson 3 (`tools.jackson`), module discovery, `FAIL_ON_EMPTY_BEANS=false`, disabled alphabetical sorting, exclusion of null properties while retaining null content elements. Package and bean name differ [5][11].

[code] MCP has its own serialization: JDBC `OutputSchemaSmokeTest` independently reproduces the server mapper from project `JsonConfig`. Thus transferring one bean does not replace checks of actual JSON and required output-schema fields [13].

[code] Both logback files write only to stderr and a rolling file: 10 MB per file, 30-day history, 512 MB total. JDBC adds `[connection=%X{connection:-server}]` to both patterns. The projects' `ToolLogger` methods and messages match [5][11].

[code] The JDBC MDC interceptor creates CGLIB proxies for beans with `@McpTool`, finds a String parameter by Java name `connection`, sets MDC before entering the method, and restores its previous value in `finally`. Retain parameter names and annotation discovery on proxies; the existing test checks Spring AI detection [12][13].

### В4. Connections: what is common and what depends on JDBC

[code] The complete connection inventory includes nine source files in `connection/`, three required files in `tools/`, two response records, and `config/ConnectionsConfig`. Common path: JSON → definitions → `ConnectionRegistry` → factory → lazy context. `listConnections` reads definitions and checks the local file without creating remote connections [10][12].

[code] Common loading rules: entry order is preserved; a missing file or empty map gives an empty registry; malformed JSON, a null entry, invalid name, missing `url`, or undefined environment variable stops startup. Unknown dialect/unsupported URL and absent external driver are recorded as `configError` on the individual entry [10].

[code] Names use `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, at most 64 characters, with `.` and `..` forbidden and a single directory name checked. The value selects the connection and directory. This does not check every Windows filesystem restriction, such as reserved device names [10].

[code] `EnvironmentPlaceholders` has no Spring/JDBC dependency: it accepts a lookup function and substitutes multiple `${VAR}` occurrences in a string and in list elements. There is no recursive substitution, escaping, or default-value syntax; the name inside braces is trimmed, and an unset variable raises an error naming the field [10].

[code] Lazy registry initialization uses `volatile` and a separate `Holder` lock per name. A context-creation exception is not cached — the next request retries creation. However, a created context remains registered if later service lookup or a remote request fails [10].

[code] `isInitialized()` means a context exists; `poolInitialized()` separately checks JDBC pool creation. `listConnections.initialized` uses the first indicator: it does not confirm successful authorization or server availability [10][12].

[code] The JDBC factory creates a child Spring context, marks definitions lazy, and scans four JDBC packages; `ConnectionScopeConfig` creates a DataSource through `DataSourceConfig`/Hikari. The root application intentionally scans only common packages so one connection's services do not become global [10][11].

[assumption] Transfer name-selection rules, directory separation, and resource closing; replace settings, type detection, services, and factory with mail equivalents. Do not transfer automatic retries after context-creation errors into authorization without a separate policy: the mail project accounts for account lockout. This report does not define the architecture of new mail classes.

### В5. Settings binding and what to replicate

[code] Redmine binds `redmine-mcp.*` to nested `RedmineMcpProperties` records: `@DefaultValue`, constants, and value normalization in constructors; URL/API key are separated into `RedmineClientProperties`. Registration is through `RedmineConfig.@EnableConfigurationProperties`. YAML explicitly maps settings to `REDMINE_MCP_*` [5][6].

[code] JDBC uses `@ConfigurationProperties("jdbc-mcp")` for common paths; `@ConstructorBinding` selects the constructor when additional ones exist. `JdbcProperties`, `UsageProperties`, `StructureSnapshotProperties`, and `DriverProperties` are created by the JSON loader from `DEFAULTS`, not by binding each mailbox's environment [10][11][14].

[code] `tools.*` flags in both projects are read using `@ConditionalOnProperty` on tool classes; they are not fields in the main properties record. Groups are enabled by default. JDBC resources are disabled by default; Redmine writes are enabled by default [5][6][11][12].

The proposals below are [assumption]; values in the “Example” column were checked in [6][10][14]. `MAIL_MCP_*` names are recommendations, not an existing interface.

| Setting | Example | What to replicate for mail |
|---|---|---|
| Data directory | Redmine: absolute normalized path; JDBC: supports leading `~`, omits `toAbsolutePath()` | `MAIL_MCP_DATA_DIR`, one path-resolution rule for storage, extraction, index, and logs |
| Connections file | JDBC: separate from data dir, defaults to `<data-dir>/connections.json` | `MAIL_MCP_CONNECTIONS_FILE`, read once, no default connection |
| Mailbox settings | JDBC: JSON, strings with `${VAR}`, other JSON types not substituted | `ews`/`imap` type and properties from file; do not transfer `REDMINE_URL`/API key or JDBC fields |
| Attachment text | Redmine: 30,000 per part, 40,000 total, request up to 200,000 | Same three kinds of limits; confirm values for mail use |
| Whole response | Redmine: 50,000 characters, up to five images | Response limit and truncation metadata; account for final JSON, see В2 |
| Unpacking | Redmine: depth 1, 100 parts, 50 MiB total, 10 MiB per entry, 100 ZIP entries | Replicate the parameter set, fix counter scope, see В1 |
| Pandoc / Tika | Redmine: probe 2 s, conversion 30 s, Tika 5 242 880 in `BodyContentHandler`, 40 fields | Optional Pandoc, time/text limits; clarify unit of `body-limit-bytes` |
| Network request time | Redmine HTTP: connect 10 s, read 30 s | Principle of finite timeouts; separate values/settings for EWS/IMAP |
| Tool groups | Both projects: YAML → env → `@ConditionalOnProperty` | `MAIL_MCP_TOOLS_*`, conditional group registration and tool-set checks |
| Write / resources | Redmine write=true; JDBC resources=false | For mail, write=false under accepted plan; enable resources via separate flag if needed |

### В6. Pinned versions and implications for shared code

[code] Exact declarations are in the versions table. The brief incorrectly assumes Java versions match: Redmine uses `JavaLanguageVersion.of(25)` and JDBC uses `of(21)`. Both projects use Jackson 3 in source, but the exact resolved Jackson versions are not listed in the catalogs [5][7][11][15].

[assumption] A shared JAR that must also work in existing JDBC should reasonably compile for Java 21. Building the common library for 25 would require raising JDBC's minimum Java version. Compatibility with specific dependencies must be checked by building both consumers.

[assumption] Extraction should be separated from Boot/MCP: accept its own settings and leave Spring registration in the application. Otherwise the library would pull in incompatible Boot/AI versions. For the compressor, Jackson 3 API `tools.jackson` matters; its type names are incompatible with Jackson 2 [2][4][7][15].

[code] Redmine disables ordinary `jar` and produces `bootJar`; JDBC does the same. Therefore publishing either application as-is does not create a separate extraction library: a standalone module producing an ordinary JAR would be required [7][15].

### В7. Copying, shared library, composite build, or submodule

All table assessments are [assumption] for three projects by one author. Basis: inventory size and dependencies, different Java/Boot/AI versions, and existing `JsonConfig`/`ToolLogger` duplication [2][4][5][7][10][11][15].

| Option | Independent releases and version drift | Development and verification cost | Best when |
|---|---|---|---|
| Copy selected classes | Projects evolve separately; fixes must be transferred manually | Minimal infrastructure; copied tests remain local, provenance records revision | Now, while mail contracts change and much code needs adaptation |
| Library + Maven Local | Consumers pin their own version, but local artifact may be absent on another machine | Separate JAR, version, publish-before-build; verify library and consumers | Personal prototype for extracting a common core; not the only reproducible-build method |
| Library + GitHub Packages | Release library separately and update apps gradually | More publication/access setup in build environments; Java 21/25 and Boot/AI consumer matrix | Stable contract and repeated identical fixes in at least two projects |
| Gradle composite build | Consumer can substitute source project from a neighboring build | Convenient joint changes and debugging; needs agreed paths/revisions and checks for all consumers | Developing an already separated library; published artifact is convenient for normal builds |
| Git submodule | Repository pins an exact source revision, updated separately | Clone/update submodule, Gradle hookup, and verification remain separate tasks | When keeping shared source alongside is important and its lifecycle will be maintained |

[assumption] A composite build is a way to build multiple projects; a submodule is a way to deliver source. They can be combined, but neither removes dependencies on `RedmineMcpProperties` or JDBC models. Putting all infrastructure classes into one library would only move those dependencies to a shared place.

[assumption] **Recommendation: start by copying and adapting.** Move to a shared library if the same corrected mechanism is actually used by at least two applications and changes are regularly duplicated. For a stable common core, prefer a versioned JAR; keep Maven Local/composite as development conveniences. Choose a submodule when source code is explicitly preferred and its lifecycle will be maintained.

[assumption] Minimum verification differs by option:

- Copying: autonomous tests for transferred functionality in mail, and record source revisions for future fixes.
- Library: core tests plus builds of both existing consumers on their pinned versions; changing the artifact version must not automatically update every app.
- Composite/submodule: the same consumer checks and a clean-clone check with pinned source. Working only with already prepared neighboring directories does not prove reproducibility.

## Comparison tables

### Reading the inventory

[code] Paths are relative to `src/main/java/<org>/<project>/mcp/`: `E` = Redmine `extraction/`, `C` = Redmine `compression/`, `R` = Redmine package root, `J` = JDBC package root. Resources use full repository-relative paths. Line counts include blank lines and comments; nested types are included in their owner file's size [2][4][5][10][11][12].

Purpose, dependencies, size, and tests in the tables are [code] from the cited sources; “Verdict” and “What to adapt” are [assumption]. `as is` allows mechanical package/bean renaming and replacing project examples in comments. `No separate test` means no targeted check was found, not that the class is known to be untested.

Redmine test abbreviations (paths relative to `src/test/java/<org>/redmine/mcp/`) [3]:

- D = `tools/DocumentExtractionTest`; A = `service/AttachmentServiceTest`; AT = `tools/AttachmentToolsTest`.
- DX = `extraction/parser/DocxTextParserTest`; DP = `extraction/DocxPandocParserIntegrationTest` (requires Pandoc, integration tag).
- RC, IC, AC = `service/compression/ResponseCompressorTest`, `IssueCompressionTest`, `AttachmentContentCompressionTest`.
- CF, JD, JT = `service/compression/steps/ChangesetCommentsFirstLineStepTest`, `JournalDetailsOmitStepTest`, `JournalsTailKeepStepTest`.
- H = `tools/IssueStructureToolsTest`, including large histories, continuation through `nextOffset`, and one oversized event.

### Extraction

Source for all 24 rows: [2]; tests: [3]. Types from `extraction/` are treated as part of the reusable block; external libraries and links to the rest of the app are called out separately.

| Class / file | Purpose | Depends on project code | ≈ lines | Tests | Verdict | What to adapt |
|---|---|---|---:|---|---|---|
| `E/DocumentParser.java` | Parser contract; nested enum `Phase` | Only ParseInput/ParseSink | 25 | DX, D indirectly | as is | Keep two phases and multiple applicable parsers |
| `E/ExtractedPart.java` | Result part, text, and links | Nothing outside extraction | 48 | D, A indirectly | adapt | Separate internal paths from exposed sanitized files |
| `E/ExtractionLimits.java` | Four extraction limits | `RedmineMcpProperties.Limits` | 23 | D indirectly; not all limits checked | adapt | Remove factory from Redmine settings |
| `E/ExtractionPipeline.java` | Orchestration and recursion; nested `InputSink` | `RedmineMcpProperties`, Spring, SLF4J | 129 | D, DX, DP | adapt | Shared counters, stopping, own configuration |
| `E/FileTypeDetector.java` | Type by extension and Content-Type | Spring; no Redmine types | 84 | D: PDF/ZIP/XSD with generic Content-Type | adapt | Mail types, MIME parameters, distinguish HTML from text |
| `E/PandocAvailability.java` | Find/check Pandoc; nested `Detected` | `RedmineMcpProperties`, Spring, SLF4J | 131 | DP; no separate test for all search paths | adapt | Directory, settings, log messages |
| `E/ParseContext.java` | One extraction context | `RedmineMcpProperties.Extraction` | 15 | D, DX indirectly | adapt | Independent settings and shared mutable limit state |
| `E/ParseInput.java` | File, logical name, parent, depth | Only ParseContext | 30 | D indirectly | adapt | Do not use name alone as part identity; reconsider nameless root |
| `E/ParseSink.java` | Emit parts and process child files | Only ExtractedPart | 31 | DX, D indirectly | adapt | Stop signal when limits are exhausted |
| `E/TextNormalizer.java` | Newlines and whitespace | JDK only | 19 | D indirectly | as is | No algorithm changes |
| `E/parser/AbstractDocumentParser.java` | Common invocation, normalization, error emission | Extraction only; SLF4J | 61 | D: documents and corrupt file | adapt | Limit text while reading; keep errors out of indexed text |
| `E/parser/BinaryFallbackParser.java` | Final part without text | FileTypeDetector; Spring | 65 | D indirectly; nested binary not separately checked | adapt | Links and notes under file policy |
| `E/parser/DocxEmbeddedExtractor.java` | Extract embedded DOCX objects | POI, FileTypeDetector, Spring, SLF4J | 89 | No separate test; included in D helper | adapt | Depth/bytes/parts, unique paths, file reuse |
| `E/parser/DocxMediaExtractor.java` | Extract DOCX images | POI, FileTypeDetector, Spring, SLF4J | 87 | D: embedded image | adapt | Same limits/paths; do not only check matching sizes |
| `E/parser/DocxPandocParser.java` | DOCX → GFM via external process | `RedmineMcpProperties`, PandocAvailability, Spring, SLF4J | 131 | DP; DX checks duplicate skip with stub parser | adapt | Settings, `pandoc.md` cache identity, output size |
| `E/parser/DocxTextParser.java` | DOCX text/tables via POI | AbstractDocumentParser, FileTypeDetector; POI, Spring | 75 | DX, D | adapt | Preserve hasTextPart; limit text accumulation |
| `E/parser/ImagePassthroughParser.java` | Image reference part | FileTypeDetector; Spring | 54 | D: DOCX image; AT/A: image response | adapt | Policy for exposing local links |
| `E/parser/PdfTextParser.java` | PDF text layer | AbstractDocumentParser, FileTypeDetector; PDFBox, Spring | 46 | D: PDF and generic Content-Type | adapt | Read/result limits and explicit no-text status |
| `E/parser/PlainTextParser.java` | UTF-8 text file | FileTypeDetector, TextNormalizer; Spring, SLF4J | 57 | D: TXT/JSON/XSD | adapt | Encodings and separate HTML; bounded reading |
| `E/parser/PptxTextParser.java` | Slide and table text | AbstractDocumentParser, FileTypeDetector; POI, Spring | 72 | D: PPTX | adapt | Limit text accumulation |
| `E/parser/TikaMetadataParser.java` | Root-file metadata | `RedmineMcpProperties`, FileTypeDetector; Tika, Spring, SLF4J | 104 | No separate test; included in D | adapt | Settings, value length, metadata filtering |
| `E/parser/TikaTextFallbackParser.java` | Fallback text extraction | `RedmineMcpProperties`, FileTypeDetector; Tika, Spring, SLF4J | 87 | No separate legacy/mail-format test | adapt | Settings; BodyContentHandler overflow currently caught by general catch |
| `E/parser/XlsxTextParser.java` | Sheets and formatted cells | AbstractDocumentParser, FileTypeDetector; POI, Spring | 58 | D: XLSX, multiple sheets | adapt | Limit reading/text accumulation |
| `E/parser/ZipParser.java` | Recursive ZIP unpacking; nested `ArchiveReadLimitException` | `RedmineMcpProperties`, FileTypeDetector; Spring, SLF4J | 168 | D: supported files in ZIP; no separate attack/limit tests | adapt | Shared counters, stop writes, separate directory per archive |

### Response compression

Sources: [4]; tests: [3]. Includes all 13 files, including all seven `steps/` files.

| Class / file | Purpose | Depends on project code | ≈ lines | Tests | Verdict | What to adapt |
|---|---|---|---:|---|---|---|
| `C/CompressionResult.java` | Value, size, notes, overBudget | None | 11 | RC indirectly | as is | Consumer must handle overBudget |
| `C/CompressionStep.java` | Compression step; nested `Compressed<T>` | None | 29 | RC, IC, AC | as is | Package only |
| `C/ResponseCompressor.java` | Applies steps up to limit | Jackson 3, Spring, SLF4J; no domain models | 59 | RC | as is | Keep mechanism; final check belongs outside |
| `C/AttachmentContentCompression.java` | Attachment-response limit policy | `api.AttachmentContent`, `RedmineMcpProperties` | 62 | AC, AT | adapt | Mail model/settings, final size including notes |
| `C/HistoryCompression.java` | Shortens/pages history | `api.IssueHistory`, `api.Opaque`, settings, Jackson 3 | 266 | H | unnecessary | Do not transfer `Draft`, `IndexedDuration`, `TextStep`; use continuation principle |
| `C/IssueCompression.java` | Sequence of issue reductions | `api.Issue`, `RedmineMcpProperties` | 58 | IC | unnecessary | Replace with mail policy if needed |
| `C/steps/AttachmentContentImagePartsCollapseStep.java` | Keeps first images | `api.AttachmentContent`, Rewriter | 35 | AC | adapt | Model and access to sanitized materials |
| `C/steps/AttachmentContentRewriter.java` | Rebuilds parts; `CollapseResult`, `TruncateResult` | `api.AttachmentContent.Part` and with-methods | 107 | AC, AT indirectly | adapt | Model, part order, exact continuation method |
| `C/steps/AttachmentContentTextPartsTruncateStep.java` | Truncates text parts | `api.AttachmentContent`, Rewriter | 35 | AC | adapt | Model, limit, continuation message |
| `C/steps/ChangesetCommentsFirstLineStep.java` | First line of commit comment | `api.Issue`, `api.Changeset` | 59 | CF, IC | unnecessary | No mail equivalent |
| `C/steps/JournalDetailsOmitStep.java` | Removes journal field changes | `api.Issue`, `api.Journal` | 51 | JD, IC | unnecessary | No mail equivalent |
| `C/steps/JournalNoteContentTruncateStep.java` | Truncates notes while retaining original length | `api.Issue`, `api.Journal` | 80 | IC, including repeated truncation | unnecessary | Use idea of preserving original length |
| `C/steps/JournalsTailKeepStep.java` | Keeps last entries | `api.Issue` | 39 | JT, IC | unnecessary | Do not substitute for paged mail reading |

### Server infrastructure

Sources: Redmine [5][6], JDBC [11][12]; tests [3][13].

| Class / file | Purpose | Project-code dependency | ≈ lines | Tests | Verdict | What to adapt |
|---|---|---|---:|---|---|---|
| `R/config/McpServerConfig.java` | `immediateExecution=true` with logging | Spring AI customizer, SLF4J | 25 | No separate Redmine test | as is | Package name; verify bean registration |
| `J/config/McpServerConfig.java` | Same; SDK comment | Spring AI customizer | 23 | `config/McpStdioConcurrencyTest` builds server manually | as is | Use one variant and JDBC test |
| `R/config/JsonConfig.java` | Jackson 3 mapper | Jackson, Spring; no Redmine models | 30 | `config/JsonConfigTest` | as is | Bean/package name |
| `J/config/JsonConfig.java` | Same mapper | Jackson, Spring; no JDBC models | 30 | `model/OutputSchemaSmokeTest` checks separate MCP mapper | as is | Add check for actual MCP serialization |
| Redmine `src/main/resources/logback-spring.xml` | stderr + rolling file | `redmine-mcp.data-dir` | 29 | No separate test | adapt | Property, paths, file name, JDBC MDC format |
| JDBC `src/main/resources/logback-spring.xml` | Same + connection in MDC | `jdbc-mcp.data-dir`, MDC key | 31 | No separate XML test | adapt | Property/names, remove usage-package logger |
| `R/tools/ToolLogger.java` | Duration and errors | SLF4J only | 18 | No separate test | as is | Filter error messages passed in at call boundary |
| `J/tools/ToolLogger.java` | Same helper | SLF4J only | 18 | No separate test | as is | Do not duplicate both variants |
| `J/tools/ToolConnectionMdcBeanPostProcessor.java` | MDC for entire tool call | Spring AOP, McpTool, SLF4J; no JDBC types | 90 | Same-named test in `tools/` | as is | Preserve connection parameter; background jobs need separate MDC transfer |
| Both `src/main/resources/application.yml` | stdio, banner off, env and groups | Properties and registration conditions | 93 / 46 | `tools/ToolGroupConditionTest` in both | adapt | Server name/prefixes; JDBC-specific DataSourceAutoConfiguration exclusion |

### Connections

Sources: [10][11][12]; tests: [13]. Test names without paths refer to `src/test/java/<org>/jdbc/mcp/connection/`, except where `tools/` is specified.

| Class / file | Purpose | Project-code dependency | ≈ lines | Tests | Verdict | What to adapt |
|---|---|---|---:|---|---|---|
| `J/connection/ConnectionsFile.java` | JSON container and nested `Entry` | No JDBC imports; fields are effectively JDBC-specific | 60 | ConnectionsLoaderTest | adapt | Keep name map; replace JDBC fields with mail fields |
| `J/connection/ConnectionsLoader.java` | Reads JSON, substitutes strings, creates definitions | All JDBC properties, DatabaseKind, Definition, mapper | 207 | ConnectionsLoaderTest | adapt | Required fields/type, defaults, configError, safe messages |
| `J/connection/ConnectionDefinition.java` | Settings and name rules | JDBC/driver/catalog properties, DatabaseKind, DataSourceConfig.maskUrl | 113 | ConnectionDefinitionTest, ConnectionsLoaderTest | adapt | Keep name validation; remove SQL URL, snapshot.db, dialects |
| `J/connection/EnvironmentPlaceholders.java` | `${VAR}` and string lists | JDK only | 69 | ConnectionsLoaderTest: substitution and missing variable | as is | Replace project example in Javadoc; add edge-case checks |
| `J/connection/ConnectionRegistry.java` | Lazy contexts; nested `Holder` | Definition, Context, Factory, SLF4J | 154 | ConnectionRegistryTest, including concurrency/isolation/close | adapt | New definition/context types, auth retry policy |
| `J/connection/ConnectionContext.java` | Access to service graph and close | SQL executor/guard/dialect/plan, metadata/usage, JDBC properties | 178 | ConnectionRegistryTest, SpringConnectionContextFactoryTest | adapt | Keep lifecycle; replace JDBC services and poolInitialized indicator |
| `J/connection/ConnectionContextFactory.java` | Functional factory contract | Definition, Context | 12 | ConnectionRegistryTest with stub factory | adapt | New types; interface shape remains |
| `J/connection/ConnectionScopeConfig.java` | Creates DataSource and closes pool | DataSourceConfig, Hikari through it, DatabaseKind, JDBC properties | 34 | SpringConnectionContextFactoryTest | unnecessary | JDBC bean not needed; mail registration will differ |
| `J/connection/SpringConnectionContextFactory.java` | Lazy child Spring context | Four JDBC packages, six settings/engine types, DataSource | 109 | SpringConnectionContextFactoryTest | adapt | Packages, primary settings, EWS/IMAP sources, readiness and close |
| `J/tools/ConnectionTools.java` | listConnections without remote call | Registry, Definition, ConnectionInfo, ListConnectionsResult | 64 | `tools/ToolConnectionRoutingTest` | adapt | Mailbox type and local-data availability instead of SQL schema/catalog |
| `J/tools/ToolConnections.java` | Selects context and maps errors | Registry, Context, ToolErrors | 37 | `tools/ToolConnectionRoutingTest` | adapt | Parameter description and accepted mail error contract |
| `J/tools/ToolConnectionMdcBeanPostProcessor.java` | Sets connection in MDC | No JDBC types; see infrastructure table for dependencies | 90 | `tools/ToolConnectionMdcBeanPostProcessorTest` | as is | Transfer once, not separately per source |
| `J/model/connection/ConnectionInfo.java` | One list entry | Swagger @Schema; JDBC fields kind/defaultSchema/snapshotAvailable | 22 | `tools/ToolConnectionRoutingTest` | adapt | Mail fields, explicit required/nullable behavior |
| `J/model/connection/ListConnectionsResult.java` | List wrapper | ConnectionInfo, Swagger @Schema | 12 | `tools/ToolConnectionRoutingTest` | adapt | Replace list element type |
| `J/config/ConnectionsConfig.java` | Loads file and registers Factory/Registry | JdbcMcpProperties, Loader, Spring Factory, mapper | 36 | No separate test; factory and registry checked separately | adapt | Mail settings/factory, destroyMethod=close |

### Versions

All values are [code] from catalogs, build files, and wrapper; these are pinned declarations, not an audit of the resolved dependency graph [7][15].

| Library | redmine-mcp-server | jdbc-mcp-server |
|---|---|---|
| Spring Boot | `4.2.0-M2` | `4.0.0` |
| Spring AI MCP server | `2.1.0-M1` | `2.0.1` |
| Java toolchain | `25` | `21` |
| Gradle wrapper | `9.3.1` | `9.3.1` |
| PDFBox | `3.0.5` | Not declared in catalog/build |
| Apache POI (`poi-ooxml`) | `5.4.0` | Not declared in catalog/build |
| Apache Tika (`core`, `parsers-standard-package`) | `3.2.0` | Not declared in catalog/build |
| Jackson | Jackson 3 API `tools.jackson`; exact version not directly pinned | Jackson 3 API `tools.jackson`; exact version not directly pinned |
| Spring dependency-management plugin | `1.1.7` | `1.1.7` |

## Recommendation

[assumption] Transfer selected code by copying, recording its source and revision in the transfer record. Do not add shared-library publishing infrastructure now: much of the useful code still needs changes for mail settings, response models, and data filtering.

1. First reuse infrastructure choices: one `McpServerConfig`, one `JsonConfig`, `ToolLogger`, JDBC MDC, and logging template. Transfer stdio, schema/JSON, and proxy tool-detection checks.
2. Use Redmine's parser contract and parsers as the extraction basis, replace property binding, and strengthen limits from В1. Operational errors must not become document text indexed by search.
3. Use the three common compressor types, but write mail-specific steps and check final response size. Do not transfer `IssueCompression`, `HistoryCompression`, or journal steps.
4. Transfer the named-connection model with an adapted loader/registry. Do not transfer JDBC pool, SQL services, or their settings; distinguish configuration errors from sign-in attempts.
5. When transferring tests, replace Redmine mocks and `TestRedmineMcpProperties`, keep synthetic documents and temporary directories. Add checks for a shared limit across ZIP → DOCX → nested file and same-named files in different branches.

[assumption] A shared library becomes preferable to copying once corrected extraction limits and types stabilize and multiple projects need the same changes. Then extract a small core without MCP wire records or application settings, compile for the common minimum Java, and test every supported consumer. Maven Local/composite is convenient for joint development; published versions suit independent releases. Conditions are in В7.

## What could not be verified

- [code] Builds and tests were not run: the task was to read two repositories and write one report. The cited test coverage was found in source, not confirmed as passing on this machine. During implementation, run selected offline tests in the appropriate environment.
- [code] No targeted tests for global extraction stopping, a global unpacked-byte counter, or child-path collisions were found in the inspected set. This is a coverage gap, not a result from testing specially prepared files [3].
- [code] Actual resolved versions of Jackson, MCP SDK, and transitive libraries are unknown. Catalogs provide direct declarations only. A developer can inspect them with Gradle `dependencyInsight` in a separate build session [7][15].
- [code] The SDK-fix comment and disabled concurrency test do not establish upstream status as of 2026-10-24. Web verification was outside this task; before removing `immediateExecution`, check dependencies and reproduce the concurrency test [11][13].
- [code] For `body-limit-bytes`, only that the value is passed to `BodyContentHandler` and exceptions are caught wholesale was established. Unit of the parameter and retention of partial results require checking that Tika version's source or a separate test [2].
- [assumption] Compatibility of an extracted core with both Boot/AI and Java versions was not checked: no separate library exists yet. Check both consumers after removing project dependencies.
- [code] Source web pages were not opened: references below identify locally read files/directories at the respective clean revisions. Dates are commit dates, not library release dates.

## What else to study

- [assumption] RT-09: MIME, nested `.eml`/`.msg`, TNEF, and encodings; Tika fallback does not replace a model of a message and its parts.
- [assumption] Before exposing paths, decide which sanitized files are available to the model; Redmine's existing `localPath` hints assume access to the original [6].

## Sources

All sources were read locally on 2026-10-24. Each reference pins a revision; table paths identify a particular file within the directory. The Redmine commit is dated 2026-09-30 and JDBC 2026-09-25; individual files may have changed earlier.

1. Redmine, revision and tree — `C:\git\redmine-mcp-server` @ `f62b3e5`, (read 2026-10-24).
2. Redmine, all extraction and parser files — `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/main/java/<org>/redmine/mcp/extraction` (read 2026-10-24).
3. Redmine, extraction, tools, service/compression, and config tests — `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/test/java/<org>/redmine/mcp` (read 2026-10-24).
4. Redmine, all compression and steps files — `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/main/java/<org>/redmine/mcp/compression` (read 2026-10-24).
5. Redmine, config/McpServerConfig, JsonConfig, RedmineMcpProperties, RedmineClientProperties, RedmineConfig; tools/ToolLogger — `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/main/java/<org>/redmine/mcp` (read 2026-10-24).
6. Redmine, AttachmentService and resources — `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/main/java/<org>/redmine/mcp/service/AttachmentService.java` ; `C:\git\redmine-mcp-server` @ `f62b3e5`, `src/main/resources` (read 2026-10-24).
7. Redmine, versions and build — `C:\git\redmine-mcp-server` @ `f62b3e5`, gradle/libs.versions.toml ; `C:\git\redmine-mcp-server` @ `f62b3e5`, build.gradle.kts ; `C:\git\redmine-mcp-server` @ `f62b3e5`, gradle/wrapper/gradle-wrapper.properties (read 2026-10-24).
8. Redmine, AGENTS.md and all five docs/*-agent.md — `C:\git\redmine-mcp-server` @ `f62b3e5`, AGENTS.md ; `C:\git\redmine-mcp-server` @ `f62b3e5`, docs (read 2026-10-24).
9. JDBC, revision and tree — `C:\git\jdbc-mcp-server` @ `c2c4362`, (read 2026-10-24).
10. JDBC, all connection files — `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/main/java/<org>/jdbc/mcp/connection` (read 2026-10-24).
11. JDBC, config, application, and resources — `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/main/java/<org>/jdbc/mcp` ; `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/main/resources` (read 2026-10-24).
12. JDBC, ConnectionTools, ToolConnections, ToolConnectionMdcBeanPostProcessor, ToolLogger; model/connection records — `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/main/java/<org>/jdbc/mcp/tools` ; `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/main/java/<org>/jdbc/mcp/model/connection` (read 2026-10-24).
13. JDBC, connection tests, config/McpStdioConcurrencyTest, tools, and model — `C:\git\jdbc-mcp-server` @ `c2c4362`, `src/test/java/<org>/jdbc/mcp` (read 2026-10-24).
14. JDBC, connections guide and AGENTS.md — `C:\git\jdbc-mcp-server` @ `c2c4362`, docs/connections.md ; `C:\git\jdbc-mcp-server` @ `c2c4362`, AGENTS.md (read 2026-10-24).
15. JDBC, versions and build — `C:\git\jdbc-mcp-server` @ `c2c4362`, gradle/libs.versions.toml ; `C:\git\jdbc-mcp-server` @ `c2c4362`, build.gradle.kts ; `C:\git\jdbc-mcp-server` @ `c2c4362`, gradle/wrapper/gradle-wrapper.properties (read 2026-10-24).

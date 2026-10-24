# RT-05 — Code reuse from sibling projects

- **Result file:** `docs/research/RT-05-sibling-code-reuse.md`
- **Size:** M.
- **Fills:** `docs/research.md`, sections 6 and 7; `docs/plan.md`, open question 10.
- **Needs:** read access to `C:\git\redmine-mcp-server` and `C:\git\jdbc-mcp-server`. If these
  paths are not available to you, write that in the result file and stop.

## Goal

Make an inventory of the code we could take from the two sibling projects, say what each part
depends on, and compare "copy into this repository" with "extract a shared library".

## What we already know

- `redmine-mcp-server`: Java 25, Spring Boot 4, Spring AI MCP server, stdio. Packages `api/`,
  `client/`, `compression/`, `config/`, `extraction/` (+ `extraction/parser/`), `focus/`,
  `service/`, `tools/`. Read its `AGENTS.md` and `docs/*-agent.md` first.
- `jdbc-mcp-server`: same stack. Package `connection/` (`ConnectionsLoader`, `ConnectionsFile`,
  `ConnectionDefinition`, `ConnectionRegistry`, `ConnectionContext`, `ConnectionContextFactory`,
  `ConnectionScopeConfig`, `EnvironmentPlaceholders`, `SpringConnectionContextFactory`), plus
  `tools/ConnectionTools`, `tools/ToolConnections`, `tools/ToolConnectionMdcBeanPostProcessor`.
  Read its `AGENTS.md` and `docs/connections.md` first.
- The two projects pin different versions (`gradle/libs.versions.toml`): check and report them.

## Questions

- **В1. Extraction pipeline** (`redmine-mcp-server/.../extraction/` and `parser/`): for every
  class — purpose in one line, dependencies on Redmine-specific or project-specific types
  (configuration properties, `api/` records, services), approximate size in lines, and a verdict:
  `как есть` / `адаптировать` / `не нужно`. Note the tests that cover it.
- **В2. Response bounding** (`compression/`): which parts are generic and which are tied to Redmine
  issue/journal shapes. Verdict per class.
- **В3. Server plumbing** in both projects: `McpServerConfig` (the `immediateExecution` fix),
  `JsonConfig`, logging setup (`logback-spring.xml`), tool logging helpers
  (`ToolLogger` or similar), MDC handling. Verdict per item.
- **В4. Connections** (`jdbc-mcp-server/.../connection/` and the connection tools): for every
  class — purpose, JDBC-specific parts (DataSource, Hikari, dialects) vs generic parts (file
  loading, name validation, `${VAR}` placeholders, lazy registry), verdict.
- **В5. Configuration properties:** how each project binds settings (`@ConfigurationProperties`,
  env vars) and which settings we would mirror (data dir, limits, tool group flags).
- **В6. Versions:** Spring Boot, Spring AI, Java, Gradle, PDFBox, POI, Tika in each project;
  differences that matter for sharing code.
- **В7. Copy vs shared library:** compare (a) copying classes, (b) a shared library published to
  Maven Local / GitHub Packages, (c) a Gradle composite build or git submodule. Consider: three
  projects by one author, different release cadence, version drift, testing effort.

## Where to look

Only the two local repositories (source, tests, `build.gradle.kts`, `gradle/libs.versions.toml`,
`AGENTS.md`, `docs/`). No web search is needed.

## Required tables

One table per area (extraction, compression, plumbing, connections):

| Класс / файл | Назначение | Зависит от проектного кода | ≈ строк | Тесты | Вердикт | Что адаптировать |
|---|---|---|---|---|---|---|

| Библиотека | redmine-mcp-server | jdbc-mcp-server |
|---|---|---|

## Out of scope

- Changing either sibling repository.
- Copying any code into this repository.
- Designing our mail-specific classes beyond naming what would need adapting.

## Done when

- Every class in the listed packages appears in a table with a verdict.
- В7 ends with a recommendation and the conditions under which the other option would be better.

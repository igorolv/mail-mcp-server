# RT-10 — MCP resources and client support

- **Result file:** `docs/research/RT-10-mcp-resources.md`
- **Size:** S.
- **Fills:** `docs/plan.md`, section 5.5.

## Goal

Find out how our server can expose downloaded messages and attachments as MCP resources, and
which MCP clients will actually use them.

## What we already know

- Idea: once a message is downloaded, expose it as a resource such as
  `mail://<connection>/message/<id>` and attachments as `mail://<connection>/attachment/<id>`
  (`docs/plan.md`, 5.5 and 5.7).
- `jdbc-mcp-server` already publishes resource templates, disabled by default
  (`JDBC_MCP_RESOURCES_ENABLED`); see its `AGENTS.md`, section about resources.
- Server stack: Spring AI MCP server 2.x (Java).

## Questions

- **В1. Specification:** in the current MCP specification version — resources, resource
  templates, `resources/list` vs templates, `resources/subscribe` and `list_changed`
  notifications, `completion/complete` for template arguments, and **resource links / embedded
  resources returned from tool results**. Which version introduced what.
- **В2. Client support matrix** for: Claude Code, Claude Desktop, Cursor, VS Code (GitHub Copilot
  agent mode), OpenAI Codex CLI, ChatGPT (connectors / developer mode), and any other popular
  client. Columns: lists resources, reads resources, templates, subscriptions, shows resource
  links from tool results, lets the user attach a resource to context.
- **В3. Spring AI MCP server:** how to register resources and templates (annotations such as
  `@McpResource` or the programmatic API), how to return resource links from tools, any
  limitations in the 2.x line.
- **В4. Practice:** how 2–3 existing MCP servers that handle documents or files expose them
  (resources, links, or plain tool output), and what works best for large content.

## Where to look

- `https://modelcontextprotocol.io/specification` (latest version and changelog).
- Client documentation and release notes (Claude Code docs, Cursor docs, VS Code MCP docs, Codex
  docs, OpenAI help pages).
- Spring AI reference documentation, MCP server section.

## Required tables

| Клиент | Список ресурсов | Чтение | Шаблоны | Подписки | Ссылки на ресурсы из инструментов | Прикрепление в контекст | Источник |
|---|---|---|---|---|---|---|---|

## Out of scope

- Implementing resources; running clients.
- Prompts and sampling features of MCP (mention in one line if relevant).

## Done when

- В1–В4 answered; the matrix covers at least 6 clients.
- The recommendation says whether to rely on resources, on resource links in tool results, or on
  plain file paths (`localPath`), and why.

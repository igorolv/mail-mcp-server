# RT-01 — Existing mail MCP servers

- **Result file:** `docs/research/RT-01-mail-mcp-servers.md`
- **Size:** L. If one session is not enough, split it: session A covers Exchange / Microsoft 365 /
  Outlook servers and writes `docs/research/RT-01a-mail-mcp-servers.md`; session B covers
  IMAP / Gmail / other providers and writes `docs/research/RT-01b-mail-mcp-servers.md`.
- **Fills:** `docs/research.md`, section 3.

## Goal

Survey existing MCP servers that give AI agents access to email, and extract ideas, rules and
anti-patterns for our own server. We are not looking for a server to adopt; we are building our
own (see `docs/plan.md`, section 2) and want to learn from others.

## What we already know

Read `docs/plan.md` sections 1, 4, 5 and 6 for our planned features: several mailboxes (EWS and
IMAP), attachments saved as local files with extracted text, a local search index over mail and
attachments, a sensitive-data filter, read-only by default, optional sending later.

## Questions

- **В1.** Which mail MCP servers exist? Aim for at least 15 if that many exist; include official
  or vendor ones and popular community ones. For each, fill the main table below.
- **В2.** Which of them build a **local index or RAG** over mail (full-text, vectors)? How:
  engine, what is indexed (attachments too?), how sync works.
- **В3.** How do they handle **attachments**: download to disk, return base64, extract text,
  size limits?
- **В4.** What **safety** measures do they have: read-only mode, separate flag or confirmation for
  sending, allow-lists of recipients, redaction of secrets or PII, defence against **prompt
  injection inside email content** (emails are untrusted input that reaches the model)?
- **В5.** **Tool design**: tool names and count, how messages and threads are identified,
  pagination, how large responses are bounded, server-side vs local search.
- **В6.** How do they handle **several accounts** (one server per account, or a list of accounts
  in one server)? How are credentials stored (env vars, config file, OS keychain, OAuth tokens)?
- **В7.** Which ideas are worth borrowing, and which anti-patterns should we avoid? Give 5–10 of
  each, with the project they come from.

## Where to look

- Official MCP registry: `https://registry.modelcontextprotocol.io/`
- `https://github.com/modelcontextprotocol/servers` (README lists reference and community servers)
- Catalogues: `https://glama.ai/mcp/servers`, `https://mcpservers.org/`, `https://mcp.so/`
- GitHub search, for example: `mcp server email`, `mcp imap`, `mcp gmail`, `mcp outlook`,
  `mcp exchange ews`, `mcp microsoft graph mail`, `model context protocol mail`.
- For each project read its README and, for the most interesting 3–5, skim the tool definitions in
  the source code.

## Required tables

Main table, one row per project:

| Проект | Ссылка | Язык | Протоколы (IMAP/SMTP/EWS/Graph/Gmail API/…) | Вход (пароль/OAuth/…) | Несколько ящиков | Вложения | Поиск (сервер/локальный индекс/векторы) | Режим только чтения / отправка | Защита данных | Лицензия | Последний релиз или коммит | Звёзды (≈) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|

Second table for the 3–5 most relevant projects: their tool list (name → one-line purpose).

## Out of scope

- Installing or running any server; signing in to any mailbox.
- Calendar, contacts and tasks features (mention in one line if a server has them).
- Choosing a library for our implementation (that is RT-02, RT-04, RT-09).

## Done when

- The main table has at least 15 rows (or every project you could find, if fewer, with the
  search queries you used listed under "Что не удалось проверить").
- В2–В7 are answered, each with references to rows of the main table.
- The recommendation section lists concrete ideas to borrow and pitfalls to avoid.

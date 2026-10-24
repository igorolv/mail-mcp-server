# Research tasks — shared rules

Each `RT-NN-*.md` file in this directory is a self-contained research brief for one agent
session. The briefs are written for a capable but not top-tier model: they say exactly what to
find, where to look, what to write and when to stop.

Most tasks are **research tasks**: the result is one Russian document in `docs/research/`.
Tasks marked `data` in the task list are **data tasks**: they create synthetic test files under
`testdata/` plus a short Russian report in `docs/research/` (see "Data tasks" below).

## How to start a session

Give the agent this prompt, replacing the file name:

```text
Read AGENTS.md, then docs/research-tasks/README.md, then docs/research-tasks/RT-NN-<slug>.md.
Do the task described there and write only the files it names. Do not edit any other file.
```

To run it non-interactively with the Codex CLI, pass the prompt on stdin, enable web search,
limit writes to the repository and switch off MCP servers the task does not need. Use the CLI
bundled with the Codex desktop app if the npm `codex` is too old for the configured model.

```powershell
$prompt | codex --search exec -C <repository> -s workspace-write -c 'mcp_servers.<name>.enabled=false' -o <last-message-file> -
```

Ready-to-run form for one brief (Windows PowerShell 5.1, from any directory; set `$id` to the
brief's file name without `.md`, and list in `-c` the MCP servers of your Codex config that the
task does not need):

```powershell
$id = 'RT-11-synthetic-mime'; "Read AGENTS.md, then docs/research-tasks/README.md, then docs/research-tasks/$id.md. Do the task described there and write only the files it names. Do not edit any other file." | codex --search exec -C C:\git\mail-mcp-server -s workspace-write -c 'mcp_servers.jdbc.enabled=false' -c 'mcp_servers.redmine.enabled=false' -c 'mcp_servers.sonar.enabled=false' -o "$env:TEMP\$id.last.txt" -
```

The data tasks RT-11, RT-12 and RT-13 write to separate directories and can run in parallel.
RT-11 may need a second run with the same command; the brief says how it continues.

## Task list

| ID | Brief | Result file | Size | Needs | Wave |
|---|---|---|---|---|---|
| RT-01 | [Existing mail MCP servers](RT-01-mail-mcp-servers.md) | `docs/research/RT-01-mail-mcp-servers.md` (or `RT-01a-…` + `RT-01b-…` when split) | L | web | 1 |
| RT-02 | [EWS client and NTLM in Java](RT-02-ews-java-client.md) | `docs/research/RT-02-ews-java-client.md` | M | web | 1 |
| RT-03 | [EWS operations reference for Exchange 2013](RT-03-ews-operations.md) | `docs/research/RT-03-ews-operations.md` | M | web | 1 |
| RT-04 | [IMAP access to Gmail and Mail.ru](RT-04-imap-gmail-mailru.md) | `docs/research/RT-04-imap-gmail-mailru.md` | M | web | 1 |
| RT-05 | [Code reuse from sibling projects](RT-05-sibling-code-reuse.md) | `docs/research/RT-05-sibling-code-reuse.md` | M | local repos or GitHub | 1 |
| RT-06 | [Local search engine and Russian morphology](RT-06-search-engine.md) | `docs/research/RT-06-search-engine.md` | M | web | 2 |
| RT-07 | [Embedding models for Russian mail](RT-07-embeddings.md) | `docs/research/RT-07-embeddings.md` | M | web | 2 |
| RT-08 | [Sensitive-data detectors](RT-08-redaction-detectors.md) | `docs/research/RT-08-redaction-detectors.md` | M | web | 2 |
| RT-09 | [MIME parsing and mail attachments in Java](RT-09-mime-attachments.md) | `docs/research/RT-09-mime-attachments.md` | M | web, local repo | 2 |
| RT-10 | [MCP resources and client support](RT-10-mcp-resources.md) | `docs/research/RT-10-mcp-resources.md` | S | web | 2 |
| RT-11 | [Synthetic `.eml` messages for the parsing pipeline](RT-11-synthetic-mime.md) — `data` | `testdata/mime/` + `docs/research/RT-11-synthetic-mime.md` | L | local tools; web for specs | 3 |
| RT-12 | [Synthetic samples for the sensitive-data detectors](RT-12-redaction-samples.md) — `data` | `testdata/redaction/` + `docs/research/RT-12-redaction-samples.md` | M | local tools | 3 |
| RT-13 | [Control set of search queries and a synthetic corpus](RT-13-search-queries.md) — `data` | `testdata/search/` + `docs/research/RT-13-search-queries.md` | M | local tools | 3 |

Size: S ≈ one short session, M ≈ one full session, L ≈ may need two sessions (the brief says how
to split). All tasks are independent: each writes only its own files, so any number can run in
parallel. Wave 1 feeds the next design decisions; wave 2 can wait; wave 3 prepares offline test
data for stage 1 and later stages (`docs/plan.md`, sections 7 and 9).

## Rules for every task

1. **Write only your own files:** the result file under `docs/research/` and, for a data task,
   the data directory its brief names (rule D1). Do not edit `docs/plan.md`,
   `docs/research.md`, `AGENTS.md`, other briefs or other results. Consolidation into
   `docs/research.md` is done later, by the user, from all results together.
2. **Language:** the result file is written **in Russian**. Keep identifiers, API names, class
   names, URLs and quotes from English sources in their original form.
3. **No real environment values.** Use the placeholders from `AGENTS.md` (`example.com`,
   `webmail.example.com`, `user`, `user@gmail.com`, `user@mail.ru`). Do not open `.local/`.
   The sibling projects are cited by **local path and commit** (for example
   `C:\git\redmine-mcp-server` @ `f62b3e5`), never by their GitHub URL, because the GitHub
   account name is the user's login. Write their Java package root as `<org>` (for example
   `src/main/java/<org>/redmine/mcp/`), because it contains the company name. If a catalogue or
   search result lists the sibling projects themselves, mention them only as "sibling project".
4. **No sign-ins, no credentials.** Do not connect to any mailbox, do not create accounts, do not
   request API keys or OAuth clients. If a question can only be answered by signing in, say so
   in the section "Что не удалось проверить" and describe what the user should check.
5. **No code in the repository.** Do not add source files, build files or scripts. Short code or
   request snippets inside the result file are fine when the brief asks for them. Data tasks
   create data files, not code; their helper scripts stay outside git (rule D4).
6. **Cite sources.** Every non-obvious fact gets a source: URL plus the date you read it. Prefer
   primary sources: official documentation, the project's repository, release notes, RFCs.
7. **Mark how sure you are.** Tag each finding as one of:
   - `[док]` — stated in official documentation or a specification;
   - `[код]` — seen in source code or a repository (give the file path or link);
   - `[вторично]` — blog post, forum, Stack Overflow, issue comment;
   - `[предположение]` — your inference; explain why.
   Never present an assumption or a memory as a checked fact.
8. **Freshness matters.** Today's date is given in your environment; check it. Products and
   policies change (Google auth rules, library maintenance). Record the publication or release
   date of key sources, and prefer 2025–2026 material. When sources disagree, give both.
9. **Do not decide for the user.** Give a recommendation with its reasons and trade-offs, but
   label it as a recommendation. Decisions are recorded by the user in `docs/plan.md`.
10. **Stay in scope.** Each brief has an "Out of scope" list. If you find something important
    outside the scope, mention it in one line under "Что ещё стоит изучить" and move on.
11. **Stop when the brief's "Done when" list is satisfied.** Breadth over polish: a complete
    table with honest gaps beats a polished partial one.
12. **Plain Russian.** Write ordinary Russian sentences. Keep English only for names that are
    literally English: API operations, elements, classes, parameters, libraries, quotes. Do not
    use English jargon as Russian words: write «изменения», not «delta»; «текущий адрес
    письма», not «locator»; «лимит нагрузки», not «budget». If an English term is needed, give
    the Russian meaning on first use, for example «бюджет нагрузки (budget)».
13. **Keep it short.** The section "Кратко" must work on its own: a reader who reads only it
    knows the main answers and the recommendation. Aim for **300–400 lines** in the whole file
    (sources excluded). Prefer tables to prose, one example per operation or option, and no
    repetition of the same fact in several sections — refer back instead ("см. В3").

## Result file template

Copy this structure into the result file. Keep the headings in this order; drop a section only
if the brief says so.

```markdown
# RT-NN — <название задачи>

- Дата: YYYY-MM-DD
- Исполнитель: <модель / сессия>
- Бриф: docs/research-tasks/RT-NN-<slug>.md

## Кратко

5–10 пунктов: главные выводы, каждый в одну-две строки, с пометкой уверенности.

## Ответы на вопросы

### В1. <вопрос из брифа>
Ответ, пометки [док]/[код]/[вторично]/[предположение], ссылки на источники [N].

### В2. ...

## Сравнительные таблицы

(если бриф их требует)

## Рекомендация

Что рекомендуешь и почему; какие компромиссы; что повлияет на выбор. Это рекомендация, решение
за пользователем.

## Что не удалось проверить

Список: что осталось неизвестным, почему, как это проверить (кто и чем).

## Что ещё стоит изучить

Короткий список находок за пределами задачи.

## Источники

1. <Название> — <URL> (прочитано YYYY-MM-DD; дата публикации, если есть)
2. ...
```

## Self-check before finishing

- [ ] Every question in the brief has an answer or an explicit "не удалось выяснить".
- [ ] Every table the brief asks for exists and has no silently empty cells (write `?` and
      explain in "Что не удалось проверить").
- [ ] Every non-obvious fact has a certainty tag and a numbered source.
- [ ] No real host names, domains, IPs or logins; no passwords or tokens.
- [ ] Plain Russian (rule 12); "Кратко" readable on its own; about 300–400 lines without sources
      (rule 13).
- [ ] Only the result file was created or changed (`git status` shows nothing else).

## Data tasks

A data task creates synthetic test files that later tests run against without network access
and without real mail. Rules 1–13 apply to its report; rules D1–D7 apply to the data.

### Where the data lives

```text
testdata/                 test data, outside the future src/ tree
  .gitattributes          keeps every file byte-for-byte (no line-ending conversion); do not edit
  .gitignore              ignores .work/
  .work/RT-NN/            helper scripts and scratch files of one task; never committed
  mime/                   RT-11: .eml messages + <case>.expected.json + manifest.json
  redaction/              RT-12: detector samples (JSON) + mixed/ documents
  search/                 RT-13: corpus/*.eml + corpus.json + queries.json
```

Each data directory has its own `README.md` (in English: layout, JSON fields, conventions) and
a manifest listing every file. Expected results are JSON files next to the data, in the format
the brief defines; the brief's format is the contract for future tests.

### Rules for data

- **D1. Own directory only.** Write only into the data directory your brief names and into
  `testdata/.work/RT-NN/`. Do not touch other data directories, `testdata/.gitattributes` or
  `testdata/.gitignore`.
- **D2. Everything is invented.** No real messages, people, companies, addresses, phone or
  document numbers, keys, tokens or URLs — not even "anonymized" real ones. Mail addresses and
  hosts only on `example.com`, `example.org`, `example.net` and their subdomains. Names are plain
  invented ones, never public figures and never names from the user's environment. Do not copy
  sample values from web pages or other projects' test files: generate them.
- **D3. Generated, then checked.** Hashes, sizes, checksums and "this text occurs in that
  file" claims are computed by a script, not estimated. The report says what was checked and how.
- **D4. Helper scripts stay outside git.** Put them in `testdata/.work/RT-NN/` or the system
  temp directory. Use only tools already installed; do not install packages or download files.
  Do not use Outlook or any other mail client, and never send anything.
- **D5. Bytes are exact.** `.eml` files use CRLF line endings; JSON, `.txt` and `README.md` use
  LF; all text is UTF-8 without BOM unless a case needs another charset. Write files in binary
  mode where line endings matter.
- **D6. Small and safe.** No zip bombs, executables, macros or real keys and certificates. Keep
  each file under 64 KiB unless the brief allows more; tests lower limits instead of using
  huge inputs.
- **D7. Report is short.** About 100–200 lines without sources. The data itself is the result;
  the report explains how it was made, what it covers and what is missing.

### Report template for data tasks

```markdown
# RT-NN — <название задачи>

- Дата: YYYY-MM-DD
- Исполнитель: <модель / сессия>
- Бриф: docs/research-tasks/RT-NN-<slug>.md
- Данные: testdata/<set>/

## Кратко

3–7 пунктов: что создано (сколько файлов и случаев), что проверено, главные пробелы.

## Состав набора

Таблица, которую требует бриф: один случай или одна группа случаев в строке.

## Как получены файлы

Инструменты и версии, ключевые команды или короткие фрагменты кода, что делалось вручную.

## Проверка

Что проверено скриптом, чем ещё (библиотека и версия), что не проверялось и почему.

## Спорные места

Случаи, где ожидаемый результат зависит от ещё не принятого решения; варианты.

## Что не удалось сделать

Недостающие случаи и причины; как их можно получить позже.

## Что ещё стоит изучить

Короткий список находок за пределами задачи.

## Источники

1. <Название> — <URL> (прочитано YYYY-MM-DD)
```

### Self-check for data tasks

- [ ] Every required case or category from the brief exists, or is listed in "Что не удалось
      сделать".
- [ ] Every JSON file parses; the manifest lists every data file and nothing that is missing.
- [ ] All scripted checks from the brief's "Done when" pass; their results are in "Проверка".
- [ ] A search of the data directory finds no mail domains other than `example.com`,
      `example.org`, `example.net`, and no values copied from sources.
- [ ] `git status` shows changes only in your data directory and your report file.

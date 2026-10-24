# mail-mcp-server — instructions for agents

This repository will become a local stdio MCP server that lets AI agents read several mailboxes
(a corporate Exchange server over EWS, Gmail and Mail.ru over IMAP), turn attachments into local
files with extracted text, search mail and attachments through a local index, and filter
sensitive data before anything reaches the model.

**Current status: stage 1, EWS spike.** There is a Gradle build, a Spring Boot stdio MCP server
with one tool (`listConnections`), the connections file with a lazy registry, and a read-only EWS
client with NTLM plus a spike command the user runs against the real mailbox. IMAP, local
storage, MIME parsing, search and redaction are still design only (`docs/plan.md`). Add new tools
or subsystems only when the task asks for them.

## Repository contents

| Path | What it is |
|---|---|
| `AGENTS.md` | This file: rules for agents changing the repository |
| `CLAUDE.md` | One line, `@AGENTS.md`, so Claude Code loads this file. Keep all rules here, not there |
| `docs/plan.md` | Main design discussion (in Russian): idea, environment, goals, key ideas, architecture draft, stages, open questions, decision log, discussion log |
| `docs/research.md` | Research notes (in Russian): access protocols, libraries, existing MCP servers, search engines, redaction tools, what to reuse from sibling projects |
| `docs/research-tasks/` | Research briefs `RT-NN-*.md` for separate agent sessions, plus `README.md` with shared rules and the result template |
| `docs/research/` | Research results, one file per brief (in Russian). Created by research sessions |
| `docs/*-agent.md` | Engineering documents (in English), see the routing table below |
| `src/main/java/mail/mcp/` | Server source; layout in `docs/architecture-agent.md` |
| `src/test/` | Unit tests and synthetic EWS responses (`src/test/resources/ews/`); none needs a network |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/` | Gradle build, version catalog `gradle/libs.versions.toml`, wrapper |
| `gradlew`, `gradlew.bat` | Gradle wrapper scripts |
| `testdata/` | Synthetic test data sets (MIME, redaction samples, search queries) produced by data tasks RT-11…RT-13; byte-exact (`testdata/.gitattributes`) |
| `scripts/ews-login-check.ps1` | Interactive EWS sign-in check (`GetFolder` for `inbox`); run by the user, not by agents |
| `.gitignore` | Ignores `.local/`, Gradle output and IDE files |
| `.gitattributes` | Line endings of wrapper scripts; keeps `.eml` and EWS fixtures byte-exact |
| `.local/` | **Ignored by git.** Real environment values (host names, domain, login). May not exist in your checkout |

## Instruction routing

Read this file first. Then read only the documents whose `When` matches the task.

| Document | When | What it holds |
|---|---|---|
| `docs/plan.md` | any design question; recording a finding, decision or next step | the project plan and the full discussion history |
| `docs/research.md` | comparing protocols, libraries, existing solutions; recording research results | research tables with statuses and conclusions |
| `docs/research-tasks/README.md` | running a research task `RT-NN`, or writing a new brief | how to start a session, task list, shared rules, result template, self-check |
| `docs/research-tasks/RT-NN-*.md` | you were given that task | goal, known context, questions, sources, required tables, out of scope, done criteria |
| `docs/architecture-agent.md` | locating code, adding a package, changing a dependency, Spring wiring or the stdio transport | what is implemented, tech stack, package layout and layer rules |
| `docs/build-config-agent.md` | building, running tests, adding a setting, changing the connections file format, running the spike | Gradle commands, `MAIL_MCP_*` settings, `connections.json` keys and validation, spike invocation |
| `docs/ews-source-agent.md` | changing `source/ews/`, adding an EWS operation, NTLM questions, reading spike output | allow-list, response checks, NTLM transport rules, known risks, spike output format |
| `scripts/ews-login-check.ps1` | changing or explaining the EWS sign-in check | the script itself; its help block documents parameters |

Name new engineering documents `docs/<topic>-agent.md`, write them in English, and register each
one in this table.

## Invariants that apply to every change

1. **No real environment values in tracked files.** Never write the real company domain, mail
   host names, IP addresses, server names or logins into any tracked file or commit message. Use
   these placeholders instead:

   | Placeholder | Meaning |
   |---|---|
   | `example.com` | company mail domain |
   | `webmail.example.com` | OWA / EWS host |
   | `mail.example.com` | ActiveSync host (same server) |
   | `user`, `user@example.com` | the user's login and corporate address |
   | `user@gmail.com`, `user@mail.ru` | personal mailboxes |

   The same applies to the sibling projects: cite them by local path and commit, not by GitHub
   URL (the account name equals the login), and write their Java package root as `<org>`
   (it contains the company name).

   The real mapping lives only in `.local/environment.md`, which git ignores. Do not copy values
   out of `.local/`. If you record probe results, put raw details (IP, server name, certificate)
   into `.local/` and only anonymized conclusions into `docs/`. The repository is published on
   GitHub.

2. **Never handle passwords.** Do not ask the user to paste a password into the chat, do not
   put passwords into files, scripts, command lines, documentation examples, logs or commit
   messages. Scripts that need a password prompt for it interactively
   (`Read-Host -AsSecureString`) and keep it in memory only. In the future server, credentials
   live only in the connections file outside the repository (see `docs/plan.md`, section 5.7).

3. **Do not sign in to mailboxes yourself.** The mail accounts are real. Every sign-in attempt
   counts toward the domain lockout policy. Credentialed checks are run by the user; you prepare
   the script and interpret the output the user pastes back. Anonymous requests that only read
   response headers are allowed only when the task asks for them.

4. **Read-only by default.** No script or code may send, move, delete or flag mail, or change
   mailbox settings, unless `docs/plan.md` records a decision that enables that operation.
   Writing mail is a planned optional feature behind an explicit flag, not part of the MVP.

5. **Do not push or rewrite history unless asked.** Commit only when the task asks for it.
   Never force-push, amend or rebase published commits without an explicit request.

## Working with the discussion documents

- `docs/plan.md` and `docs/research.md` are written **in Russian**. Keep Russian when you edit
  them. Agent documents (`AGENTS.md`, `docs/*-agent.md`) are written **in English**.
- Do not delete discussion history. Mark an answered open question by appending
  `→ *answer (section N)*` or by striking it through; do not remove it.
- When you record something in `docs/plan.md`:
  - update the section the finding belongs to (for example 3.x for environment checks);
  - tick or add the matching checkbox in section 9 "Ближайшие шаги";
  - add a row to the decision log (section 10) **only for decisions the user made**, not for
    your own proposals;
  - add a dated entry to section 11 "Ход обсуждения".
- Always use absolute dates in `YYYY-MM-DD` form, never "today" or "yesterday".
- In `docs/research.md` keep the status marks: ⬜ not reviewed, 🔍 in progress, ✅ reviewed
  (conclusion written). A fact you have not verified from a primary source is marked
  "проверить" (to verify); do not present recollection as fact.
- Keep section numbering and internal links (`plan.md 5.7`, `раздел 3.1`) consistent when you
  add or move sections.

## Planned architecture

Summary of `docs/plan.md` sections 5–7 and the decision log (section 10, decisions П1–П13),
so you can keep changes consistent with it. What is already implemented is listed in
`docs/architecture-agent.md`; the rest is design.

- Java 25, Spring Boot `4.2.0-RC1`, Spring AI `2.1.0-RC1` (the 2.1 line is a deliberate choice;
  move to `2.1.0` when it is released), Gradle 9 with a version catalog.
- stdio is the only transport; nothing but JSON-RPC may be written to `System.out`.
- Layers: `tools/ -> service/ -> source/` (EWS, IMAP), plus `connections/`, `store/`,
  `extraction/`, `index/`, `redaction/`; `api/` records are the MCP wire contract.
- Several mailboxes from the start: a `connections.json` file outside the repository
  (`%USERPROFILE%\.mail-mcp-server\`), modeled on the sibling project `jdbc-mcp-server`. Each
  connection has a name, a type (`ews` or `imap`), an `auth` kind, its own properties and
  credentials.
- EWS source: own thin SOAP client on StAX with a pluggable transport — Apache HttpClient 5 with
  NTLM explicitly registered first, `HttpsURLConnection` as fallback. IMAP source (Gmail,
  Mail.ru): Eclipse Angus Mail with app passwords; OAuth2 for Gmail later.
- Read-only at protocol level: an allow-list of 10 EWS read operations; IMAP `EXAMINE` and
  `BODY.PEEK`; never set flags in the mailbox.
- Every source delivers messages as MIME (`.eml`), so storage, parsing and attachment extraction
  are shared. MIME parsing: Apache James Mime4j (streaming), plus POI HSMF/HMEF, jsoup, iCal4j.
  The extraction pipeline copied from `redmine-mcp-server` must first get one text per file and
  shared depth and size limits for the whole nesting chain.
- Message key: a local UUID per connection; the current server address, `Message-ID` and a MIME
  hash are stored separately.
- Storage: SQLite per connection for metadata and sync state; originals on disk and never
  returned to the model; Lucene index (BM25, `RussianAnalyzer`) built from redacted text.
  Embeddings are not in the MVP.
- Sensitive-data filter runs both before indexing and on every output; `localPath` given to the
  model points only to redacted text. Tools are the primary access path; MCP resources are an
  addition.
- Every tool, including local search, takes a required `connection`; there is no cross-mailbox
  call.
- Synchronization runs only on request, through a dedicated tool with a depth parameter (new
  only / N days / N more messages back / full history). It starts work in the background of its
  own server process and returns at once; `indexStatus` reports progress and coverage. All other
  tools answer immediately from local data and report what is covered. Deletions and moves are
  applied by the same incremental sync. Attachment text is extracted during sync up to a size
  limit. Defaults: every real mail folder except Deleted, Junk, Outbox, Drafts and search
  folders, full history; Gmail syncs All Mail once with labels.
- Several server processes (different client sessions) share one data directory. All of them
  read; only the process holding the per-connection lock (OS file lock plus a row in SQLite)
  syncs and writes the Lucene index. A sign-in failure stops syncing that connection with no
  retries — repeated attempts with a stale password lock the domain account.
- Implementation order: EWS spike first; the offline pipeline (parse, store, index, redact) on
  synthetic `.eml` in parallel; IMAP right after EWS works.

Sibling projects used as references (same author, both public on GitHub):

| Project | Local path | What to look at |
|---|---|---|
| `redmine-mcp-server` | `C:\git\redmine-mcp-server` | `AGENTS.md`, `docs/*-agent.md`: layering, attachment extraction pipeline, snapshots, bounded responses |
| `jdbc-mcp-server` | `C:\git\jdbc-mcp-server` | `docs/connections.md`, package `connection/`: connections file, `${VAR}` placeholders, lazy registry |

## Java code

- The package root is `mail.mcp`. Never use a package, group id or class name that contains the
  company name or the user's account name (invariant 1).
- Tests must not sign in to a real mailbox or reach the network; use synthetic responses
  (`src/test/resources/ews/`) and the fake loopback endpoint of `HttpClient5EwsTransportTest`.
- Code copied from a sibling project names its source project and commit in the class Javadoc.
- The EWS spike (`ews-spike` mode of the jar, `ewsSpike` Gradle task) signs in to the real mailbox:
  only the user runs it. Agents interpret the output the user pastes back
  (`docs/ews-source-agent.md`).

## Scripts

- Target **Windows PowerShell 5.1** (`powershell.exe`), which is the user's default shell. Do not
  use PowerShell 7-only syntax (`&&`, `||`, `?:`, `??`, `?.`).
- Keep `.ps1` files **ASCII-only**. Windows PowerShell 5.1 reads a UTF-8 file without a BOM as
  the ANSI code page, so Cyrillic text in a script becomes garbage. Write script messages in
  English.
- Host names and logins come from parameters or environment variables, never from hard-coded
  defaults. `scripts/ews-login-check.ps1` uses `MAIL_EWS_SERVER` and `MAIL_EWS_LOGIN`.
- Known result: EWS sign-in works with **NTLM and a bare login** (no domain part). Basic with a
  bare login is rejected (`401`); Basic with a domain prefix was not tried and is not needed.
  Details are in `docs/plan.md`, section 3.2.

The user runs the script like this:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ews-login-check.ps1 -Server webmail.example.com -Logins user -Methods NTLM
```

## Verification before you finish

1. **Scripts:** parse-check every changed `.ps1` without running it:

   ```powershell
   $errors = $null; [void][System.Management.Automation.Language.Parser]::ParseFile("$PWD\scripts\ews-login-check.ps1", [ref]$null, [ref]$errors); $errors.Count
   ```

   The result must be `0`. Also confirm the file has no non-ASCII characters.
2. **Java:** `.\gradlew.bat build` passes (compile, all tests, `bootJar`).
3. **Real values:** if `.local/environment.md` exists, check that none of the real values listed
   there appear in your diff (`git diff`) or commit message.
4. **Documents:** check that every path and section reference you wrote exists.
5. **Commits** (only when asked): English, imperative summary line of at most ~72 characters, a
   body that says what changed and why.

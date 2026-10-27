# mail-mcp-server — instructions for agents

A local stdio MCP server that lets AI agents read several mailboxes: a corporate Exchange server
over EWS, Mail.ru and Gmail over IMAP. It keeps a full local copy of every message (`.eml`,
metadata, body and attachments with Markdown text), indexes it with Lucene and answers tools from
local data only. User-facing overview: `README.md`.

**Current status (2026-10-27): the draft version is complete and checked on the user's real
mailboxes** (`work` over EWS, `mailru` over IMAP; `docs/plan.md` section 2).
- Nine MCP tools, background sync, offline reprocessing and reindexing work.
- Gmail OAuth2 is implemented, but its real sign-in is unverified. The user deferred Gmail, so do
  not treat Gmail setup as a required next step.
- Features accepted offline but not yet checked by the user on real mail are listed in
  `docs/plan.md` 2.4 (container expansion and legacy Office text need a user-run
  `mail-reprocess`; background summaries and EWS rescan reporting await observation during
  user-requested sync; date filters, date-only search and local conversations await local
  queries after restart; heuristic move reuse needs user-run seeding and a manual test move;
  applying the 5 MiB XLSX text threshold to existing mail needs optional user-run reprocessing).

Work now consists of polishing and optional improvements:
- `docs/plan.md` section 4 lists the open ones (`Д4`, `Д7`, …); the user chooses which to take.
  Accepted items are removed from that table.
- Add new tools or subsystems only when the task asks for them.
- `docs/plan.md` 3.8 lists what no change may add without a user decision.
- An offline MIME pipeline with shared limits (commit `d6534ca`) was reverted on 2026-10-25 by
  user decision as over-engineering; do not rebuild it.

## Repository contents

| Path | What it is |
|---|---|
| `README.md` | Project overview for users: features, quickstart, tools, commands, troubleshooting |
| `AGENTS.md` | This file: rules for agents changing the repository |
| `CLAUDE.md` | One line, `@AGENTS.md`, so Claude Code loads this file. Keep all rules here, not there |
| `docs/plan.md` | The plan (in Russian): idea and status, environment facts and real-mailbox results, design summary, possible improvements, current decisions, research index |
| `docs/research/` | Background research results `RT-NN-*.md` (in Russian), from 2026-10-24; the decisions taken from them are in `docs/plan.md` section 5 |
| `docs/impl-tasks/` | Implementation briefs `IT-NN-*.md` for separate agent sessions, plus `README.md` with shared rules and the report template |
| `docs/*-agent.md` | Engineering documents (in English), see the routing table below |
| `src/main/java/mail/mcp/` | Server source; layout in `docs/architecture-agent.md` |
| `src/test/` | Unit tests and synthetic EWS responses (`src/test/resources/ews/`); none needs a network |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/` | Gradle build, version catalog `gradle/libs.versions.toml`, wrapper |
| `gradlew`, `gradlew.bat` | Gradle wrapper scripts |
| `testdata/` | Synthetic test data sets (MIME, redaction samples, search queries); byte-exact (`testdata/.gitattributes`) |
| `.gitignore` | Ignores `.local/`, Gradle output and IDE files |
| `.gitattributes` | Line endings of wrapper scripts; keeps `.eml` and EWS fixtures byte-exact |
| `.local/` | **Ignored by git.** Real environment values (host names, domain, login). May not exist in your checkout |

## Instruction routing

Read this file first. Then read only the documents whose `When` matches the task.

| Document | When | What it holds |
|---|---|---|
| `docs/plan.md` | any design question; choosing or recording an improvement; recording a finding or a decision | status, environment facts, design summary, improvements table, decisions, research index |
| `docs/impl-tasks/README.md` | implementing a brief `IT-NN`, or writing a new implementation brief | how to start a session, task list, shared rules, report template, self-check |
| `docs/impl-tasks/IT-NN-*.md` | you were given that task | goal, files to read, fixed decisions, types and behavior, named tests, what not to build, files and documents to change |
| `docs/research/RT-NN-*.md` | working on an improvement whose row in plan.md section 6 names that research | background facts, sources and comparisons; check them against the current code and decisions |
| `docs/architecture-agent.md` | locating code, adding a package, changing a dependency, Spring wiring or the stdio transport | what is implemented, tech stack, package layout and layer rules |
| `docs/build-config-agent.md` | building, running tests, adding a setting, changing the connections file format, running a user command | Gradle commands, `MAIL_MCP_*` settings, `connections.json` keys and validation, every jar mode with output and exit codes |
| `docs/ews-source-agent.md` | changing `source/ews/`, adding an EWS operation, NTLM questions | allow-list, response checks, NTLM transport rules, `EwsMailSource`, known risks |
| `docs/imap-source-agent.md` | changing `source/imap/` or IMAP authentication | TLS, command allow-list, one sign-in attempt, EXAMINE/PEEK, bounded MIME downloads |
| `docs/gmail-oauth-agent.md` | changing `source/oauth/google/`, Gmail OAuth configuration, authorization or refresh | Desktop client setup, PKCE/state/loopback flow, owner-only refresh storage, token endpoint and XOAUTH2 rules |
| `docs/tools-agent.md` | changing `tools/`, `api/`, `SyncService`, `sync-status.json` or a tool's arguments and bounds | the nine tools, local-only reading, status file fields, single writer across processes, sign-in refusal, search control-set numbers |
| `docs/redaction-agent.md` | changing `redaction/`, output filtering or its setting | output-only secret replacement, detectors, chunk/highlight ordering, unchanged original paths and control-set policy |
| `docs/mime-agent.md` | changing `mime/` or `convert/`, MIME parsing rules, attachment names, pandoc calls, attachment text, `mail-reprocess` | Angus properties, body and attachment rules, stored names, sandboxed pandoc, attachment formats, known `testdata/mime` differences |
| `docs/storage-agent.md` | changing `model/`, `store/` or `index/`, the on-disk layout, `meta.json`, `folders.json` or the Lucene index | message directories, write order and atomic writes, index fields and schema, single writer, reconciliation |

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
   live only in the connections file outside the repository (`docs/build-config-agent.md`).

3. **Do not sign in to mailboxes yourself.** The mail accounts are real. Every sign-in attempt
   counts toward the domain lockout policy. Credentialed checks are run by the user; you prepare
   the script and interpret the output the user pastes back. Anonymous requests that only read
   response headers are allowed only when the task asks for them.

4. **Read-only by default.** No script or code may send, move, delete or flag mail, or change
   mailbox settings, unless `docs/plan.md` records a decision that enables that operation.
   Writing mail is a possible improvement behind an explicit flag (plan.md section 4, Д15).

5. **Do not push or rewrite history unless asked.** Commit only when the task asks for it.
   Never force-push, amend or rebase published commits without an explicit request.

## Working with the plan

- `docs/plan.md` and `docs/research/` are written **in Russian**. Keep Russian when you edit
  them. Agent documents (`AGENTS.md`, `docs/*-agent.md`, `README.md`) are written **in English**.
- `docs/plan.md` describes the current state, not the history. Replace outdated statements
  instead of appending corrections. Git keeps the history; the full discussion up to 2026-10-26 is
  in the commits before the plan was rewritten.
- When you record something in `docs/plan.md`:
  - a verified fact or a real-mailbox result goes to section 2, anonymized: numbers only, no
    folder names, addresses, file names or message content;
  - a design change goes to section 3;
  - an improvement's status changes in the section 4 table: ⬜ → 🔨 when work starts, ✖ with a
    reason when rejected. When accepted, delete its row, add its number to the list of done items
    above the table, describe the result in section 3 and list anything still awaiting a
    real-mail check in 2.4. Numbers are never reused. A new idea is a new row with the next number;
  - section 5 gets a row **only for decisions the user made**; mark your own proposals
    «Предложено».
- Delete an implementation brief once it is accepted and recorded, and update the task list in
  `docs/impl-tasks/README.md`.
- Always use absolute dates in `YYYY-MM-DD` form, never "today" or "yesterday".
- Mark a fact you have not verified from a primary source "проверить" (to verify); do not
  present recollection as fact.
- Keep section numbering and internal links (`plan.md 3.5`, `раздел 2.1`) consistent when you
  add or move sections. Code comments cite plan sections too.

## Architecture summary

This summarizes `docs/plan.md` section 3 and the decisions in section 5, so you can keep changes
consistent with them. The plan is authoritative where this summary is shorter. What exists in
code is listed in `docs/architecture-agent.md`.

- **Stack:** Java 25, Spring Boot `4.2.0-RC1`, Spring AI `2.1.0-RC1`, Gradle 9 with a version
  catalog. The 2.1 line is a deliberate choice; move to `2.1.0` when it is released.
- **Transport:** stdio only; nothing but JSON-RPC may be written to `System.out`.
- **Packages:** `config/`, `connections/`, `source/` (`MailSource`; `ews/`, `imap/`, `oauth/`,
  `oauth/google/`), `model/` (one record per file), `mime/`, `convert/`, `store/`, `index/`,
  `sync/`, `redaction/`, `tools/`, `api/`, `cli/`. There is no `service/` layer. `api/` records are the MCP
  wire contract; `model/` records are not returned directly.
- **Connections:** a `connections.json` file outside the repository
  (`%USERPROFILE%\.mail-mcp-server\`), modeled on the sibling project `jdbc-mcp-server`.
  - Each connection has a name, a type (`ews` or `imap`), an `auth` kind, its own properties and
    credentials.
  - Every tool, including search, takes a required `connection`.
- **Mail sources:**
  - EWS: own thin SOAP client on StAX over Apache HttpClient 5 with NTLM explicitly registered.
  - IMAP (Gmail, Mail.ru): Eclipse Angus Mail with app passwords or Gmail OAuth2/XOAUTH2.
  - Initial Gmail OAuth consent is a user-run command, never an MCP tool.
- **Read-only at protocol level:** an allow-list of 10 EWS read operations; IMAP `EXAMINE`,
  `BODY.PEEK` and `CLOSE`, never `SELECT`; never set flags in the mailbox.
- **MIME:** every source delivers MIME (`.eml`), kept exactly as downloaded and parsed with Angus
  Mail. `Containers` expands EML, ZIP, MSG and TNEF breadth first into a flat attachment list
  with immediate `parent` links, depth 5 and message-wide extracted-output limits of 1,000
  files / 256 MiB. ZIP entries have a 20 MiB limit. Original containers remain unchanged;
  embedded MAPI messages gain standalone `.msg` files. There is no separate parsing process
  or shared budget object; POI parsing itself is eager. Existing data needs user-run
  `mail-reprocess`; finish a connection before attachment lookup/search because extracted
  filenames can change across this upgrade.
- **Pandoc is required** (user decision).
  - One sandboxed `pandoc --sandbox` process per conversion turns HTML bodies and
    DOCX/ODT/RTF/EPUB/HTML/CSV attachments into Markdown.
  - PDF uses PDFBox; DOC/XLS/PPT (Office 97-2003), XLSX and PPTX use POI.
  - Attachment text conversion admits up to 20 MiB of decoded bytes, except XLSX up to 5 MiB
    inclusive, using the resolved format and actual file size, including inside containers.
    This reduces risk without bounding eager POI memory use. Existing XLSX text remains until
    optional user-run `mail-reprocess`; reads and `mail-reindex` of converted messages retain it.
  - MSG headers and the first nonblank plain/HTML/RTF body become Markdown; TNEF provides
    its subject and RTF body. HTML/RTF use pandoc. Declared code pages are applied without guessing.
  - Without pandoc, sync and reprocessing refuse to start.
- **Storage, no SQLite:**
  - one directory per message, `<connection>/messages/<k2>/<key>/`, with `message.eml`,
    `meta.json`, `body.md` and `attachments/` (each file plus its `.md`);
  - `folders.json` with an opaque cursor per folder, and `sync-status.json`;
  - the disk is the complete copy. One Lucene index per connection (catalog and search, schema
    version in the commit data) is derived and rebuilt from disk without the network.
- **Optional output filter:** `MAIL_MCP_REDACT_SECRETS=true` replaces recognized secrets only in
  tool response text. Downloads, disk and index hold everything as is. Tools still return original
  file paths and stored filenames. This reduces accidental context exposure, not intentional
  extraction. See `docs/redaction-agent.md`.
- **Local conversations:** `getThread` follows case-sensitive Message-ID, In-Reply-To and
  References across folders of one connection in one committed index snapshot. Copies remain
  separate; cards sort oldest first, undated last, then key before paging. No bodies, MIME,
  source or pandoc are read; schema 3 is unchanged and no reindex is needed.
- **Synchronization:**
  - runs only on request: the `sync` tool in the background, or `mail-sync`;
  - always syncs everything, then incrementally;
  - metadata-only snapshots of every present ref seed nullable remote fingerprints without
    redownloading known MIME. A unique complete Message-ID/metadata match can reuse an explicitly
    removed converted message in another included folder of the same connection, preserving UUID,
    directory and all content. Equality is heuristic; SHA-256 describes the saved original only.
    Ambiguity, missing evidence and UIDVALIDITY resets use normal deletion/download;
  - discovers all folders before removal, commits reused moves before unmatched deletions,
    and saves all cursors only after all changes commit. Reconciliation repairs index folder/ref
    mismatches from metadata without reconversion; reused moves increase no sync counter;
  - one sign-in per run, no retries. After a rejected sign-in the `sync` tool refuses the
    connection until the user runs `mail-sync`;
  - several server processes share one data directory; only the holder of the Lucene write lock
    syncs.

Sibling projects used as references (same author, both public on GitHub):

| Project | Local path | What to look at |
|---|---|---|
| `redmine-mcp-server` | `C:\git\redmine-mcp-server` | `AGENTS.md`, `docs/*-agent.md`: layering, attachment extraction, bounded responses |
| `jdbc-mcp-server` | `C:\git\jdbc-mcp-server` | `README.md`, `docs/connections.md`, package `connection/`: connections file, `${VAR}` placeholders, lazy registry |

## Java code

- The package root is `mail.mcp`. Never use a package, group id or class name that contains the
  company name or the user's account name (invariant 1).
- Tests must not sign in to a real mailbox or reach an external network; use synthetic responses
  (`src/test/resources/ews/`) and the fake loopback endpoints of `HttpClient5EwsTransportTest`
  and `ImapClientTest`.
- Code copied from a sibling project names its source project and commit in the class Javadoc.
- The `mail-sync` command signs in to a real mailbox, so only the user runs it. Agents interpret
  the output the user pastes back (`docs/build-config-agent.md`).
  Never open the real connections file or saved original messages; use synthetic files for checks.
- The `gmail-oauth` command opens a browser and exchanges a real authorization code. Only the
  user runs it. Agents must not open downloaded OAuth client JSON or token files.

## Scripts

- Target **Windows PowerShell 5.1** (`powershell.exe`), which is the user's default shell. Do not
  use PowerShell 7-only syntax (`&&`, `||`, `?:`, `??`, `?.`).
- Keep `.ps1` files **ASCII-only**. Windows PowerShell 5.1 reads a UTF-8 file without a BOM as
  the ANSI code page, so Cyrillic text in a script becomes garbage. Write script messages in
  English.
- Host names and logins come from parameters or environment variables, never from hard-coded
  defaults.
- Known result: EWS sign-in works with **NTLM and a bare login** (no domain part). Basic with a
  bare login is rejected (`401`); Basic with a domain prefix was not tried and is not needed.
  Details are in `docs/plan.md`, section 2.1.

## Verification before you finish

1. **Scripts:** parse-check every changed `.ps1` without running it:

   ```powershell
   $errors = $null; [void][System.Management.Automation.Language.Parser]::ParseFile("$PWD\path\to\script.ps1", [ref]$null, [ref]$errors); $errors.Count
   ```

   The result must be `0`. Also confirm the file has no non-ASCII characters.
2. **Java:** `.\gradlew.bat build` passes (compile, all tests, `bootJar`).
3. **Real values:** if `.local/environment.md` exists, check that none of the real values listed
   there appear in your diff (`git diff`) or commit message.
4. **Documents:** check that every path and section reference you wrote exists.
5. **Commits** (only when asked): English, imperative summary line of at most ~72 characters, a
   body that says what changed and why.

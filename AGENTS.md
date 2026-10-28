# mail-mcp-server — instructions for agents

A local stdio MCP server that lets AI agents read several mailboxes: a corporate Exchange server
over EWS, Mail.ru and Gmail over IMAP. It keeps a full local copy of every message (`.eml`,
metadata, body and attachments with Markdown text), indexes it with Lucene and answers tools from
local data only. User-facing overview: `README.md`.

**Current status (2026-10-28): the draft version is complete and checked on the user's real
mailboxes** (`work` over EWS, `mailru` over IMAP; `docs/results.md`). The first published version
`0.1.0` (Apache 2.0) is in preparation: `docs/plan.md` section 4 lists its scope, Д18
(publication) and Д7 (Gmail).
- Nine MCP tools, background sync, offline reprocessing and reindexing work.
- Gmail app-password sign-in and downloads are verified, including 256 KiB IMAP blocks.
  Full synchronization verification and Gmail label handling remain part of `0.1.0` (Д7);
  real OAuth2 sign-in is unverified. The user runs every real-mail check.
- Features accepted offline but not yet checked by the user on real mail are listed in
  `docs/plan.md` section 2, each with how to check it. Only the first, a user-run
  `mail-reprocess` of both mailboxes, blocks `0.1.0`; the rest are checked as the user works.

Work now consists of finishing `0.1.0`, then optional improvements:
- `docs/plan.md` section 4 lists what `0.1.0` includes; accepted items are removed from that table.
- `docs/backlog.md` lists improvements after `0.1.0` (`Д13`, `Д15`, …); the user chooses which to
  take and moves them into the version table.
- Add new tools or subsystems only when the task asks for them.
- `docs/decisions.md` section 1 lists what no change may add without a user decision.
- An offline MIME pipeline with shared limits (commit `d6534ca`) was reverted on 2026-10-25 by
  user decision as over-engineering; do not rebuild it.

## Repository contents

| Path | What it is |
|---|---|
| `README.md` | Project overview for users: features, quickstart, tools, commands, troubleshooting |
| `AGENTS.md` | This file: rules for agents changing the repository |
| `CLAUDE.md` | One line, `@AGENTS.md`, so Claude Code loads this file. Keep all rules here, not there |
| `docs/plan.md` | The plan: idea and status, checks pending on real mail, principles and data flow, scope of `0.1.0`, how work is done |
| `docs/results.md` | Environment facts and real-mailbox results, numbers only |
| `docs/decisions.md` | The user's current decisions, including rejected ideas, and what no change may add without one |
| `docs/backlog.md` | Possible improvements after `0.1.0` |
| `docs/connections.md`, `docs/clients.md`, `docs/commands.md`, `docs/search.md`, `docs/security.md` | User documents (in English): connections file, client registration, user-run commands, search, security |
| `docs/research/` | Background research results `RT-NN-*.md`, from 2026-10-24, with an index in `README.md`; the decisions taken from them are in `docs/decisions.md` |
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
| `docs/plan.md` | any design question; choosing or recording an improvement; recording a pending real-mail check | status, pending checks, principles, data flow, scope of `0.1.0` |
| `docs/backlog.md` | choosing, adding or rejecting an improvement after `0.1.0` | improvements table with sizes and sources |
| `docs/decisions.md` | any design question; before adding a dependency, an operation or a safeguard; recording a user decision | what not to add without the user, current decisions with reasons |
| `docs/results.md` | interpreting user-run output; recording a real-mailbox result | environment facts and real-mailbox numbers |
| `docs/impl-tasks/README.md` | implementing a brief `IT-NN`, or writing a new implementation brief | how to start a session, task list, shared rules, report template, self-check |
| `docs/impl-tasks/IT-NN-*.md` | you were given that task | goal, files to read, fixed decisions, types and behavior, named tests, what not to build, files and documents to change |
| `docs/research/RT-NN-*.md` | working on an improvement whose row in `docs/research/README.md` names that research | background facts, sources and comparisons; check them against the current code and decisions |
| `docs/connections.md`, `clients.md`, `commands.md`, `search.md`, `security.md` | changing user-visible behavior: connection keys, settings, command output or exit codes, search syntax, read-only and privacy guarantees | the user-facing description; the single source for formats, output and exit codes |
| `docs/architecture-agent.md` | locating code, adding a package, changing a dependency, Spring wiring or the stdio transport | what is implemented, tech stack, package layout and layer rules |
| `docs/build-config-agent.md` | building, running tests, adding a setting, changing the connections file format, running a user command | Gradle commands, `MAIL_MCP_*` settings, implementation of the connections file, password sources and every jar mode; rules for agents |
| `docs/ews-source-agent.md` | changing `source/ews/`, adding an EWS operation, NTLM questions | allow-list, response checks, NTLM transport rules, `EwsMailSource`, known risks |
| `docs/imap-source-agent.md` | changing `source/imap/` or IMAP authentication | TLS, command allow-list, one sign-in attempt, EXAMINE/PEEK, bounded MIME downloads |
| `docs/gmail-oauth-agent.md` | changing `source/oauth/google/`, Gmail OAuth configuration, authorization or refresh | facts behind the user's Desktop client setup, PKCE/state/loopback flow, owner-only refresh storage, token endpoint and XOAUTH2 rules |
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
   (`Read-Host -AsSecureString`) and keep it in memory only. In the server, credentials live
   only in the connections file outside the repository or in the stores it names
   (`docs/connections.md`).

3. **Do not sign in to mailboxes yourself.** The mail accounts are real. Every sign-in attempt
   counts toward the domain lockout policy. Credentialed checks are run by the user; you prepare
   the script and interpret the output the user pastes back. Anonymous requests that only read
   response headers are allowed only when the task asks for them.

4. **Read-only by default.** No script or code may send, move, delete or flag mail, or change
   mailbox settings, unless `docs/decisions.md` records a decision that enables that operation.
   Writing mail is a possible improvement behind an explicit flag (`docs/backlog.md`, Д15).

5. **Do not push or rewrite history unless asked.** Commit only when the task asks for it.
   Never force-push, amend or rebase published commits without an explicit request.

## Working with the plan

- All project documentation is written **in English**, including `AGENTS.md`, `README.md`,
  `docs/`, research reports and test data READMEs. Keep literal non-English examples where they
  demonstrate language-specific behavior or quote synthetic fixture data. Existing improvement
  and decision identifiers (`Д…`, `П…`) stay unchanged so references remain stable.
- User documents describe what a user sees and does: formats, settings, output, exit codes,
  guarantees. They are the single source for those; engineering documents describe the
  implementation and link to them instead of repeating them. A change of user-visible behavior
  updates the user document in the same change. User documents describe the current version for a
  new user; upgrade steps for the user's own data belong in `docs/plan.md` section 2.
- The project documents describe the current state, not the history. Replace outdated statements
  instead of appending corrections. Git keeps the history.
- Where to record what:
  - a verified fact or a real-mailbox result goes to `docs/results.md`, anonymized: numbers only,
    no folder names, addresses, file names or message content;
  - a feature accepted offline but awaiting a real-mail check goes to `docs/plan.md` section 2
    with its acceptance date; once the user's check passes, its result moves to `results.md`;
  - a design change goes to the engineering document that owns the code, and to `docs/plan.md`
    section 3 only if it changes a principle or the data flow;
  - an improvement in the current version is a row of the `docs/plan.md` section 4 table:
    ⬜ → 🔨 when work starts. When accepted, delete its row, add its number to the list of done
    items above the table, describe the result in the engineering and user documents and list
    anything still awaiting a real-mail check in section 2;
  - an improvement for later is a row of `docs/backlog.md`; a new idea is a new row there with the
    next free number (stated above its table). Numbers are never reused. When the user takes an
    item into the version, move its row to `docs/plan.md` section 4. A rejected idea is deleted
    from the table and recorded with its reason in `docs/decisions.md`;
  - `docs/decisions.md` section 2 gets a row **only for decisions the user made**; mark your own
    proposals "Proposed". Rows go in date order.
- Delete an implementation brief once it is accepted and recorded, and update the task list in
  `docs/impl-tasks/README.md`.
- Always use absolute dates in `YYYY-MM-DD` form, never "today" or "yesterday".
- Mark a fact you have not verified from a primary source "to verify"; do not
  present recollection as fact.
- Keep section numbering and internal links (`plan.md 3.1`, `results.md, section 1`) consistent
  when you add or move sections. Code comments cite these documents too.

## Architecture summary

This summarizes the engineering documents and the decisions in `docs/decisions.md`, so you can
keep changes consistent with them. Those documents are authoritative where this summary is shorter. What exists in
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
  - A password is a literal, a `${VAR}`, `windowsCredential` (a generic Windows Credential Manager
    entry read with `CredReadW` through the foreign function API) or `passwordCommand` (a password
    manager CLI run without a shell). The last two are read right before the first sign-in and kept
    in process memory until a rejected sign-in; their failure is not a rejected sign-in.
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
  References across folders of one connection in one committed index snapshot. Cards sort
  oldest first, undated last, then key; copies group before paging. No bodies, MIME,
  source or pandoc are read; conversations use the schema-4 catalog without persisted threads.
- **Message copies:** search and threads group equal valid stripped single Message-ID,
  known stored date and nonblank stored sender/subject strings, within one connection.
  Search groups only matching documents after filters; the first in score/date order then key
  supplies the card, score and snippet. Threads use the smallest key as representative.
  Other copies appear as unfiltered key/folder pairs under optional `copies`; totals and paging
  count groups. Stored copies, listMessages and folder counts stay unchanged; no reindex is needed.
- **Optional word forms:** shared user UTF-8 `word-forms.txt` under the data directory, overridden
  by `MAIL_MCP_WORD_FORMS_FILE`; no built-in dictionary. Groups map listed words to the first
  word's Russian Snowball stem, including explicitly listed Russian stop words. Unlisted words,
  English and exact fields keep their behavior. Text search and requested sync reload per call;
  differing `wordForms` commit hashes block text search until offline reindex or requested sync
  rebuilds from disk. Date-only search, lists and threads ignore the hash. Missing/empty rules
  preserve the analyzer and commit data. `mail-analyze` diagnoses rules without mail or pandoc.
- **Read/star snapshots:** nullable `read` and `flagged` represent the latest successful remote
  observation, not live state. Unknown is omitted; explicit false clears a known value.
  IMAP reads Seen/Flagged of every UID with the UID listing; EWS takes ReadFlagChange from
  SyncFolderItems and reads IsRead and Flag/FlagStatus only for items with an Update change; new
  messages get them from their metadata. Sender importance and Gmail Important are unrelated. Changed flags
  update only metadata and the derived document, preserving content, state/error, download time
  and counters. Flags are excluded from move identity; destination observations apply on reuse,
  while UIDVALIDITY resets use fresh values. Schema 4 rebuilds old indexes offline from disk;
  old metadata stays unknown until requested sync. Reconciliation repairs flag mismatches too.
- **Synchronization:**
  - runs only on request: the `sync` tool in the background, or `mail-sync`;
  - always syncs everything, then incrementally;
  - identity metadata is read only for added refs; known refs keep stored fingerprints, and a
    removed message saved without one gets it from its saved headers. There is no per-run pass
    over all messages. A unique complete Message-ID/metadata match can reuse an explicitly
    removed converted message in another included folder of the same connection, preserving UUID,
    directory and all content. Equality is heuristic; SHA-256 describes the saved original only.
    Ambiguity, missing evidence and UIDVALIDITY resets use normal deletion/download;
  - discovers all folders before removal, commits reused moves before unmatched deletions,
    and saves all cursors only after all changes commit. After a run that did not complete,
    reconciliation repairs index folder/ref/flag mismatches from metadata without reconversion;
    after a completed run it compares keys only. A run reads meta.json only of messages it removes,
    moves or changes (flags compare with the index, move uniqueness looks up Message-IDs in the
    index), so a run without changes costs a key listing and one change request per folder;
    reused moves increase no sync counter;
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
  the output the user pastes back (`docs/commands.md`, `docs/build-config-agent.md`).
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
  Details are in `docs/results.md`, section 1.

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

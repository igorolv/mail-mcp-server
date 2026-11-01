# mail-mcp-server — instructions for agents

A local stdio MCP server that lets AI agents read several mailboxes: a corporate Exchange server
over EWS, Mail.ru and Gmail over IMAP. It keeps a local copy of messages in the included
folders (`.eml`, metadata, body and attachments with Markdown text), indexes it with Lucene and
serves reading tools from local data only. User-facing overview: `README.md`.

**Current status:** the first version, `0.1.0` (Apache 2.0), was published on 2026-11-01. Version
scope and checks pending on real mail are maintained only in
`docs/plan.md` sections 4 and 2; verified real-mail results are in `docs/results.md`.
Only the user runs real-mail checks.

Work now consists of optional improvements:
- `docs/plan.md` section 4 lists work taken into a version; accepted items are removed from that table.
- Improvements after `0.1.0` are GitHub issues; the user chooses which to take and moves them into
  the version table.
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
| `docs/connections.md`, `docs/clients.md`, `docs/commands.md`, `docs/search.md`, `docs/security.md`, `docs/troubleshooting.md` | User documents (in English): connections file, client registration, user-run commands, search, security, troubleshooting |
| `docs/research/` | Background research results `RT-NN-*.md`, from 2026-10-24, with an index in `README.md`; the decisions taken from them are in `docs/decisions.md` |
| `docs/impl-tasks/` | Implementation briefs `IT-NN-*.md` for separate agent sessions, plus `README.md` with shared rules and the report template |
| `docs/*-agent.md` | Engineering documents (in English), see the routing table below |
| `src/main/java/mail/mcp/` | Server source; layout in `docs/architecture-agent.md` |
| `src/test/` | Unit tests and synthetic EWS responses (`src/test/resources/ews/`); none needs a network |
| `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` | Apache 2.0 license, copyright notice, dependency licenses |
| `.github/` | GitHub Actions: `workflows/ci.yml`, `workflows/release.yml` and the notes of each release in `release-notes/` |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/` | Gradle build, version, version catalog `gradle/libs.versions.toml`, wrapper |
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
   (it contains the company name). This repository's own GitHub URLs and the account name in
   `NOTICE` are the only exception: the repository's address already shows them.

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
   Writing mail would need such a decision and an explicit flag.

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
  instead of appending corrections. Git keeps the history; dated research reports and recorded
  real-mail results retain their original scope.
- Keep each changeable fact in its owning document from the routing table. Other documents may
  summarize it and link to the owner, but must not repeat full algorithms or verification status.
  User decisions constrain implementation; user documents own the public contract; engineering
  documents own implementation details. If these disagree, check the code and recorded decisions
  and correct the affected documents without inventing a new user decision.
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
  - an improvement for later or a new idea is a GitHub issue, not a document. When the user takes
    an issue into the version, add a row to `docs/plan.md` section 4 with the next free number
    (stated above its table) and a link to the issue. A rejected idea is recorded with its reason
    in `docs/decisions.md`;
  - `docs/decisions.md` section 2 gets a row **only for decisions the user made**. Unconfirmed
    agent proposals belong in section 3, separate from user decisions. Rows go in date order.
- Delete an implementation brief once it is accepted and recorded, and update the task list in
  `docs/impl-tasks/README.md`.
- Always use absolute dates in `YYYY-MM-DD` form, never "today" or "yesterday".
- Mark a fact you have not verified from a primary source "to verify"; do not
  present recollection as fact.
- Keep section numbering and internal links (`plan.md 3.1`, `results.md, section 1`) consistent
  when you add or move sections. Code comments cite these documents too.

## Architecture summary

Package layout, layers and dependency versions are maintained in `docs/architecture-agent.md`.
Keep these boundaries in every change:

- Stdio only: nothing but JSON-RPC may be written to `System.out`.
- `api/` records are the MCP wire contract; `model/` records are not returned directly.
  There is no `service/` layer.
- Every tool except `listConnections` requires `connection`; no call searches across mailboxes.
- Reading tools use local data only. Sync runs only on request, with one sign-in and no retry.
  Initial OAuth consent is a user-run command. Protocol guards enforce read-only mail access.
- Saved originals are unfiltered; Lucene is derived and rebuildable offline. Storage, reuse,
  reconciliation and index schema are owned by `docs/storage-agent.md`.
- Pandoc is required for sync, reprocessing and reindexing. MIME/container rules and conversion
  limits are owned by `docs/mime-agent.md`; command behavior is in `docs/commands.md`.
- Optional secret filtering affects tool response text only. Original paths and lookup filenames
  remain available; see `docs/redaction-agent.md`.

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

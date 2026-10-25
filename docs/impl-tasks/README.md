# Implementation tasks — shared rules

Each `IT-NN-*.md` file in this directory is a self-contained implementation brief for one agent
session. A brief implements one step (or half a step) of `docs/plan.md` section 7. The briefs are
written for a capable but not top-tier model: they name the files, the types, the behavior, the
tests and what not to build. When a brief and your own judgment disagree, follow the brief and
say so in the report.

Briefs are written one at a time, after the previous one has been reviewed and accepted, so each
brief matches the code as it really is.

## How to start a session

Give the agent this prompt, replacing the file name:

```text
Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/IT-NN-<slug>.md.
Implement the task described there. Change only the files it allows. Do not commit.
```

Non-interactive run with the Codex CLI (Windows PowerShell 5.1; set `$id` to the brief's file
name without `.md`, and list in `-c` the MCP servers of your Codex config the task does not need):

```powershell
$id = 'IT-01-mail-source-imap'; "Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/$id.md. Implement the task described there. Change only the files it allows. Do not commit." | codex exec -C C:\git\mail-mcp-server -s workspace-write -c 'mcp_servers.jdbc.enabled=false' -c 'mcp_servers.redmine.enabled=false' -c 'mcp_servers.sonar.enabled=false' -o "$env:TEMP\$id.last.txt" -
```

The final message (saved by `-o`) is the report described below. The user and a reviewing agent
check the diff against the brief before the next brief is written.

## Task list

| ID | Plan step | Brief | Status |
|---|---|---|---|
| IT-01 | 7.1 | [MailSource and the IMAP source](IT-01-mail-source-imap.md) | accepted 2026-10-25 |
| IT-02 | 7.2, first half | [Message directories and the Lucene catalog](IT-02-store-and-catalog.md) | accepted 2026-10-25 |
| IT-03 | 7.2, second half | [`SyncRunner` and the `mail-sync` command](IT-03-sync-runner.md) | accepted 2026-10-25 |
| IT-04 | 7.3 | [`EwsMailSource`](IT-04-ews-source.md) | accepted 2026-10-25 |
| IT-05 | 7.4, first half | [MIME reading, attachments, `body.md`, reprocessing](IT-05-mime-and-body.md) | accepted 2026-10-25 |
| IT-06 | 7.4, second half | [Background sync and the first mail tools](IT-06-sync-and-read-tools.md) | accepted 2026-10-26 |
| IT-07 | 7.5 | search | not written |
| IT-08 | 7.6 | attachment text | not written |

## Rules for every task

1. **Read in this order:** `AGENTS.md`, this file, your brief, then exactly the files the brief
   lists under "Read first". Read other code only to answer a concrete question.
2. **Change only the files the brief allows.** The brief lists files to create, to change and,
   sometimes, to delete. If you find that another file must change, stop and ask (rule 9).
3. **Do not edit** `docs/plan.md`, `docs/research.md`, `AGENTS.md`, other briefs, research
   results or `testdata/`. The reviewer records results in the plan after acceptance. Update the
   engineering documents (`docs/*-agent.md`) only where the brief's "Documents" section says.
4. **No new dependencies** unless the brief names the library and version. Versions go to
   `gradle/libs.versions.toml`, never into `build.gradle.kts` directly.
5. **Invariants from `AGENTS.md` always apply:** no real host names, domains or logins in any
   file; never handle passwords; never sign in to a real mailbox; read-only at protocol level.
6. **Build only what the brief asks for.** `docs/plan.md` 6.9 lists what no task may add:
   limit budgets, extra databases, diagnostic codes, speculative safeguards, process isolation.
   No "while I'm here" refactoring, no extra abstraction layers, no unused parameters or types.
7. **Write code like the surrounding code:** same naming, Javadoc density and idioms; one public
   type per file; records for data. Code copied from a sibling project names the project and
   commit in its class Javadoc.
8. **Tests are offline** (fake loopback servers, synthetic files, `@TempDir`). Write the tests
   the brief names, with those names. All existing tests keep passing; do not delete or weaken an
   existing test unless the brief says so.
9. **Stop and ask instead of guessing.** If the brief contradicts the code, a library behaves
   differently than the brief says, or a choice is not covered by the brief: do not improvise a
   design. Finish what is unaffected, leave the rest, and put the question in the report.
10. **Verify:** `.\gradlew.bat build` passes (compile, all tests, `bootJar`). Run it and report the
    numbers. If `.local/environment.md` exists, make sure no value from it appears in your diff.
11. **Do not commit, push, or change git history.** The user commits after review.

## Report (the final message)

```markdown
# IT-NN report

## Done
- <file>: <what changed, one line each>

## Tests
- `.\gradlew.bat build`: <passed/failed>, <N> tests, <N> skipped, <N> failed
- New tests: <TestClass.method>, ...

## Deviations from the brief
- <what and why>, or "none"

## Questions
- <what is unclear and what you left undone because of it>, or "none"

## For the user to run
- <user-run command and expected result>, or "nothing"
```

## Self-check before finishing

- [ ] Every item under "What to build" is done or listed under "Questions".
- [ ] Every test the brief names exists with that name and passes.
- [ ] `git status` shows only files the brief allows.
- [ ] Nothing from "Do not" was added.
- [ ] The documents the brief names are updated; no other document changed.
- [ ] `.\gradlew.bat build` passed.

# Implementation tasks — shared rules

Each `IT-NN-*.md` file in this directory is a self-contained implementation brief for one agent
session. A brief implements one improvement from `docs/plan.md` section 4 (`Д1`, `Д2`, …), or a
part of it. The briefs are written for a capable but not top-tier model: they name the files, the
types, the behavior, the tests and what not to build. When a brief and your own judgment disagree,
follow the brief and say so in the report.

Briefs are written one at a time, after the previous one has been reviewed and accepted, so each
brief matches the code as it really is. Small briefs that touch different files may be open at the
same time; run them one after another, each from a clean working tree. A brief is deleted once it
is accepted and recorded in the plan; git keeps it. The draft version was built by briefs
IT-01…IT-08 (2026-10-25…26).

## The cycle: three sessions per brief

Every brief goes through three separate agent sessions, each started with a fresh context:

1. **Implement**: writes the code, does not commit.
2. **Review**: checks the diff against the brief, gives a verdict and does not change files.
3. **Record and commit**: updates the plan and commits. Run it only after an `accepted` verdict
   and any user-run check the review lists.

If the review says `changes needed`, start a new implementation session with the fix prompt
from the review report, then review again.

The user starts each session. A strong model is needed only to write briefs for large
improvements (size L in plan.md section 4) and to review risky changes; everything else can run
on any capable model.

### 1. Implement

```text
Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/IT-NN-<slug>.md.
Implement the task described there. Change only the files it allows. Do not commit.
When done, reply with the implementation report from docs/impl-tasks/README.md.
```

Non-interactive run with the Codex CLI (Windows PowerShell 5.1; set `$id` to the brief's file
name without `.md`, and list in `-c` the MCP servers of your Codex config the task does not need):

```powershell
$id = 'IT-09-<slug>'; "Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/$id.md. Implement the task described there. Change only the files it allows. Do not commit. When done, reply with the implementation report from docs/impl-tasks/README.md." | codex exec -C C:\git\mail-mcp-server -s workspace-write -c 'mcp_servers.jdbc.enabled=false' -c 'mcp_servers.redmine.enabled=false' -c 'mcp_servers.sonar.enabled=false' -o "$env:TEMP\$id.last.txt" -
```

### 2. Review

```text
Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/IT-NN-<slug>.md.
The uncommitted changes in the working tree implement that brief. Review them by the section
"Review checklist" of docs/impl-tasks/README.md. Do not change any file. Reply with the review
report from docs/impl-tasks/README.md.
```

### 3. Record and commit

```text
Read AGENTS.md, then docs/impl-tasks/README.md, then docs/impl-tasks/IT-NN-<slug>.md.
The review accepted the uncommitted changes. <Paste the user-run results here, or write
"No user-run check was needed.">. Record the result by the section "Recording an accepted brief"
of docs/impl-tasks/README.md, then commit. I, the user, ask you to commit.
```

## Review checklist

The reviewer checks; it does not fix. Work through every point and report what you found.

1. **Files.** `git status --short` lists only the files under "Files" of the brief (moves and
   deletions included). Anything else is a defect.
2. **Diff against the brief.** Read the whole `git diff`, including new files (`git status`
   lists them; read them in full).
   - Every item under "What to build" is done as written.
   - Nothing from "Do not" was added.
   - Behavior that is not in the brief is a deviation; judge whether it is harmless.
3. **Tests.**
   - Every test the brief names exists with that exact name (search for `void <name>`).
   - Run `.\gradlew.bat build --rerun-tasks`.
   - Count tests, skipped and failures from `build\test-results\test\*.xml`. The only test
     skipped by design is `McpStdioConcurrencyTest.simultaneousToolCompletionsDeliverEveryResponse`.
4. **Real values.** If `.local/environment.md` exists, run the check below. Never print the
   values themselves. The script skips the placeholders, a few generic words and repository paths
   that `.local` happens to list. Any hit it prints is a defect: report the file and the line with
   the value masked.

   ```powershell
   $ph = @('example.com','mail.example.com','webmail.example.com','user@example.com'); $vals = [regex]::Matches((Get-Content .local\environment.md -Raw), '`([^`]+)`') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Length -ge 4 -and -not ($ph -contains $_.ToLower()) -and -not (@('work','user','mail','imap','json') -contains $_.ToLower()) -and -not (Test-Path $_) } | Sort-Object -Unique; $lines = @((git diff 2>$null) -split "`n" | Where-Object { $_ -match '^\+[^+]' }); foreach ($f in (git ls-files --others --exclude-standard)) { $lines += Get-Content $f -Encoding UTF8 }; foreach ($v in $vals) { $n = @($lines | Where-Object { $_.IndexOf($v, [StringComparison]::OrdinalIgnoreCase) -ge 0 }).Count; if ($n -gt 0) { "value of length $($v.Length): $n line(s)" } }; 'check done'
   ```

5. **Documents.** The documents named under "Documents" of the brief are updated and describe
   the code as it now is. Every path and section reference written in the diff exists.
6. **Verdict.**
   - `accepted`: the brief is done; minor notes are allowed.
   - `changes needed`: a defect must be fixed first. Write a fix prompt for a new implementation
     session that names the brief, each defect and the expected fix, and ends with "Do not
     commit."

## Recording an accepted brief

1. `docs/plan.md` (Russian, current state only; see AGENTS.md "Working with the plan"):
   - **Section 4:** set the brief's improvement to ✅ with the date in `YYYY-MM-DD` form in the
     status column.
   - **Section 2:** add the user-run results, numbers only. Skip this when there were none.
   - **Section 3:** update any statement the change made untrue.
   - **Section 5:** add a row only for a decision the user made in this brief.
2. Update every other document that still describes the old behavior. Check `README.md` and
   the `AGENTS.md` status paragraph too.
3. Delete the brief file and remove its row from the task list below.
4. Run `.\gradlew.bat build` again and the real-values check above.
5. Commit everything with `git add` of the changed files (never `git add -A` on files you did not
   touch) and a message as AGENTS.md "Verification" point 5 says. Do not push.

## Task list

| ID | Improvement | Brief | Status |
|---|---|---|---|
| IT-09 | Д1 | [Quieter PDFBox logs](IT-09-quiet-pdfbox-logs.md) | ready |
| IT-10 | Д2 | [Exact address search](IT-10-exact-address-search.md) | ready |
| IT-11 | Д17 | [Remove the spikes](IT-11-remove-spikes.md) | ready |

Run them in this order, each from a clean working tree. IT-11 changes `README.md` and
`AGENTS.md`, so record IT-09 and IT-10 first. Numbering continues from IT-12.

## Rules for every implementation session

1. **Read in this order:** `AGENTS.md`, this file, your brief, then exactly the files the brief
   lists under "Read first". Read other code only to answer a concrete question.
2. **Change only the files the brief allows.** The brief lists files to create, to change and,
   sometimes, to delete. If you find that another file must change, stop and ask (rule 9).
3. **Do not edit** `docs/plan.md`, `AGENTS.md`, other briefs, research results
   (`docs/research/`) or `testdata/`, and edit `README.md` only when the brief lists it. The
   recording session updates the plan after acceptance. Update the
   engineering documents (`docs/*-agent.md`) only where the brief's "Documents" section says.
4. **No new dependencies** unless the brief names the library and version. Versions go to
   `gradle/libs.versions.toml`, never into `build.gradle.kts` directly.
5. **Invariants from `AGENTS.md` always apply:** no real host names, domains or logins in any
   file; never handle passwords; never sign in to a real mailbox; read-only at protocol level.
6. **Build only what the brief asks for.** `docs/plan.md` 3.8 lists what no task may add:
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

## Implementation report (the final message of an implementation session)

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

## Review report (the final message of a review session)

```markdown
# IT-NN review

Verdict: accepted | changes needed

## Checks
- Files: <ok / list of unexpected files>
- Brief: <ok / items missing or done differently>
- Do not: <ok / what was added>
- Tests: <N> tests, <N> skipped, <N> failed; named tests <all present / missing: …>
- Real values: <no hits / file:line with the value masked>
- Documents: <ok / what is stale>

## Deviations
- <behavior not in the brief and whether it is harmless>, or "none"

## For the user to run
- <user-run command and what result to expect>, or "nothing"

## Fix prompt
<only for "changes needed": a prompt for a new implementation session>
```

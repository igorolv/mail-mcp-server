# IT-11 — remove the spikes

Improvement: `docs/plan.md` section 4, Д17. Size: S, one session. Run it after IT-09 is
recorded: both briefs touch the logging configuration.

## Goal

Three diagnostic tools from the start of the project are now covered by the real commands:
- the jar modes `ews-spike` and `imap-spike`;
- the PowerShell sign-in check `scripts/ews-login-check.ps1`.

Each spike signs in once and fetches one message; `mail-sync` does the same and more, with the
same one-attempt rule. Remove them together with the code only they use, and point every
instruction that sent the user to a spike at `mail-sync`. The logging configuration of the
user-run commands is still called `logback-spike.xml`; rename it to `logback-cli.xml`.

## Read first

1. `src/main/java/mail/mcp/MailMcpServerApplication.java`, `build.gradle.kts`.
2. `src/main/java/mail/mcp/cli/` — all files.
3. `src/main/java/mail/mcp/source/ews/EwsClient.java`, `EwsResponseReader.java`.
4. `src/test/java/mail/mcp/source/ews/{EwsRequestTest,EwsResponseReaderTest}.java`.
5. The documents listed under "Documents".

## What to build

### Delete

| What | Files |
|---|---|
| Spike commands | `src/main/java/mail/mcp/cli/{EwsSpikeCommand,ImapSpikeCommand}.java` |
| Their tests | `src/test/java/mail/mcp/cli/{EwsSpikeCommandTest,ImapSpikeCommandTest}.java` |
| Sign-in script | `scripts/ews-login-check.ps1`, and the `scripts/` directory if it is then empty |
| FindItem code (only the EWS spike used it) | `EwsClient.findNewestItems`; `EwsResponseReader.readItemPage` and its private `ItemFields`; the records `EwsItemPage` and `EwsItemRef` |
| FindItem tests | in `EwsRequestTest`, the test that checks the `FindItem` request (`findItemAsksForTheNewestItemsFirst`); in `EwsResponseReaderTest`, the two `FindItem` tests (`findItemReadsEveryItemTypeInServerOrder`, `findItemOnAnEmptyFolderReturnsNoItems`) |
| FindItem fixtures | `src/test/resources/ews/finditem-newest.xml`, `finditem-empty.xml` |

Keep:
- `EwsClient.getFolder`, `EwsResponseReader.readFolder` and `EwsFolder`: the transport and
  response-check tests use them as the simplest request;
- `EwsOperation.FIND_ITEM`: the allow-list does not change.

Before deleting a type, search for every use. If anything outside the list above still uses it,
stop and ask.

### Change

- `MailMcpServerApplication`: remove the two dispatch branches and their imports; update the
  Javadoc list of modes.
- `build.gradle.kts`: remove the tasks `ewsSpike` and `imapSpike` and their comments.
- `GmailOAuthCommand`: the hint printed after a successful authorization becomes
  `Next: java -jar build\libs\mail-mcp-server.jar mail-sync <name>`. Update its test, if one
  checks that line.
- Rename `src/main/resources/logback-spike.xml` to `logback-cli.xml` (`git mv`). Change every
  `logback-spike.xml` string in `cli/` and in comments to `logback-cli.xml`.

## Tests

No new test. All remaining tests pass. The number of tests drops by exactly the deleted ones:
- the spike command tests;
- one `EwsRequestTest` test;
- two `EwsResponseReaderTest` tests.

Report the number.

## Do not

- Change `EwsOperation`, the transport, `getFolder`, or any sync, store, index or tool code.
- Remove `EwsFolder` or the `getfolder-*.xml` fixtures.
- Add a replacement diagnostic command.

## Files

Delete: the files in the table above.

Move: `src/main/resources/logback-spike.xml` → `src/main/resources/logback-cli.xml`.

Change:
- `MailMcpServerApplication.java`, `build.gradle.kts`
- `cli/{GmailOAuthCommand,MailSyncCommand,MailReprocessCommand,MailReindexCommand}.java`, and the
  `GmailOAuthCommand` test if needed
- `EwsClient.java`, `EwsResponseReader.java`, `EwsRequestTest.java`, `EwsResponseReaderTest.java`
- `README.md` and the documents below

## Documents

Remove every instruction to run a spike or the PowerShell script; describe the current commands
instead:

- **`docs/build-config-agent.md`:**
  - remove the sections "The EWS spike" and "The IMAP spike". Keep their still-valid rules (fill
    the connections file locally, agents never open it) in "The connections file" or "Mail sync";
  - in "Gmail OAuth2", the step after `gmail-oauth` is `mail-sync gmail`;
  - "the spike" in "Server settings" becomes "the user-run commands".
- **`docs/ews-source-agent.md`:**
  - remove the section "The spike: what it prints and how to read it";
  - keep the verified facts (NTLM handshake costs 3 requests, then the connection is reused);
  - drop the `findNewestItems` method from the list;
  - title and first paragraph no longer mention the spike.
- **`docs/imap-source-agent.md`:**
  - remove the section "User-run spike" and the `ImapSpikeCommandTest` paragraph;
  - keep the verified facts as one short paragraph;
  - title and first paragraph no longer mention the spike.
- **`docs/gmail-oauth-agent.md`:** the check after authorization is `mail-sync gmail`.
- **`docs/architecture-agent.md`:**
  - the lists of user-run modes (`mail-sync`, `mail-reprocess`, `mail-reindex`, `gmail-oauth`);
  - the `cli/` row of the package table;
  - `logback-cli.xml`.
- **`README.md`:** remove the `ews-spike`, `imap-spike` row from the commands table.

## For the recording session

Besides the usual recording, change `AGENTS.md`:
- remove the `scripts/ews-login-check.ps1` rows from "Repository contents" and "Instruction
  routing";
- in the routing rows of `docs/ews-source-agent.md` and `docs/imap-source-agent.md`, remove
  "reading spike output";
- in "Java code", replace the two spike bullets with one: `mail-sync` signs in to a real mailbox,
  so only the user runs it, and agents interpret the output the user pastes back;
- keep the "Scripts" section, but drop the sentences about `ews-login-check.ps1` and the example
  command. Keep the rules for any future script: PowerShell 5.1, ASCII only, no hard-coded hosts
  or logins;
- "Verification before you finish", point 1: parse-check every changed `.ps1`, with the path as
  a placeholder.

In `docs/plan.md`:
- section 2.1: «скрипт `scripts/ews-login-check.ps1`» becomes «PowerShell-скрипт проверки входа
  (удалён в IT-11)»;
- section 3.7: remove the spikes from the list of commands.

## Done when

- `.\gradlew.bat build` passes; the report gives the old and new test counts.
- `git status` shows only the deletions, the move and the files listed above.
- `git grep -n -i "spike"` finds nothing outside `docs/research/` and `docs/plan.md`.
- The report lists under "For the user to run": nothing.

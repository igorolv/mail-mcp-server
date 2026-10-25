# IT-05 — MIME reading, attachments, `body.md` and reprocessing

Plan step: `docs/plan.md` 7.4, first half. Size: one full session. Next brief: IT-06 (tools
`sync`, `syncStatus`, `listFolders`, `listMessages`, `getMessage`; not written yet).

## Goal

Every downloaded message becomes a usable local copy:
- attachments are saved as files;
- the body goes to `body.md` (HTML through pandoc);
- the state changes to `converted` or `failed`;
- headers and body go into the index.

`SyncRunner` does this right after each download. A new user-run command, `mail-reprocess
<connection>`, does it offline for messages already on disk: both real mailboxes hold about 46,000
messages in state `downloaded` from steps 7.2–7.3. Nothing is sent to a model yet. There are no MCP
tools in this task.

## Read first

1. `docs/plan.md` sections 6.2, 6.4, 6.5, 6.7, 6.9 and 7.4 (Russian; what you need is repeated
   below).
2. `docs/storage-agent.md`, then `src/main/java/mail/mcp/{model,store,index,sync}/` — all files.
3. `src/main/java/mail/mcp/cli/MailSyncCommand.java` and its test — the pattern for the new
   command.
4. `src/main/java/mail/mcp/MailMcpServerApplication.java`, `build.gradle.kts`.
5. `testdata/mime/README.md`, then any `testdata/mime/M*.expected.json` you need.

## Already decided — do not revisit

- **Pandoc runs as one sandboxed process per conversion, not as `pandoc server`.** The reviewer
  checked pandoc 3.1.11 on 2026-10-25. `pandoc server` listens on `0.0.0.0`, and there is no option
  to bind it to localhost only. So the fallback from plan.md 6.7 applies:
  `pandoc --sandbox -f html -t gfm --wrap=none --eol=lf`, input on stdin, output on stdout, both
  UTF-8.
  - `--sandbox` blocks file and network access. The reviewer checked this: a LaTeX `\input` of a
    local file is refused.
  - One run costs about 27 ms on the user's machine.
  - A failed conversion exits non-zero; unknown formats exit 21.
- Pandoc is required (user decision). `mail-sync` and `mail-reprocess` check for it **before**
  opening the index or contacting a mailbox. There is no fallback without pandoc and no setting for
  its path: `pandoc` must be on `PATH`.
- MIME is parsed with Angus Mail, `new MimeMessage(session, new SharedFileInputStream(file))`. No
  other MIME library. Nested messages, MSG, TNEF, ZIP, S/MIME and calendars are not opened; they
  are saved as attachment files.
- Charsets: the declared charset is used, without guessing (plan.md 6.9). `M05` stays garbled on
  purpose.
- Three Angus system properties, verified on all 20 `testdata/mime` sets:

  | Property | Value | Why |
  |---|---|---|
  | `mail.mime.decodefilename` | `true` | RFC 2047 encoded-word file names (`M02`); common in Outlook mail |
  | `mail.mime.parameters.strict` | `false` | otherwise `filename*=utf-8''/etc/passwd` throws a `ParseException` (`M16` part 3) |
  | `mail.mime.base64.ignoreerrors` | `true` | truncated base64 (`M14`) yields its 5 decoded bytes, matching the expected SHA-256, instead of an exception |

  Angus reads the first two **once, when the class loads**, so they must be set before any
  `jakarta.mail` class is touched.
- Attachment text (`.md` next to attachments), search, the analyzer change, the sensitive-data
  filter and MCP tools are later steps (7.5–7.7, IT-06).

## What the reviewer measured with Angus on `testdata/mime` (with the three properties)

All 20 parse. Results that matter for this task:

- `M01`: RFC 2231 continuations are decoded.
- `M02`: the encoded-word name is decoded. When both are present, the disposition `filename` wins
  over `name` (`chosen-name.txt`).
- `M04`: `InternetAddress.getPersonal()` returns the decoded name.
- `M14`: the attachment yields 5 bytes.
- `M16`: Angus already strips the control character in part 6. Part 3's name is `/etc/passwd`;
  the expectation has `null` there, so this is a deviation.
- `M18`: the subject contains U+FFFD, which README allows.
- `M20`: `getSentDate()` is `null` and the `Date` header is kept. `To` contains a group address.
- `M09`: the saved `message/rfc822` part is 6276 bytes, while the expectation says 6278.

## What to build

### 1. Records in `model/` (one per file, Javadoc like `MessageMeta`)

- `Address(String name, String address)` — `name` is `null` when absent.
- `AttachmentInfo(String file, String name, String contentType, long size, String contentId, boolean inline)`:
  - `file` — the stored file name under `attachments/`;
  - `name` — the decoded name from the message, or `null`;
  - `contentType` — the lowercase base type, for example `image/png`;
  - `contentId` — without angle brackets, or `null`;
  - `inline` — true when the disposition is `inline`.
- `MailMessage(String subject, List<Address> from, List<Address> to, List<Address> cc, Instant date,
  String messageId, String inReplyTo, List<String> references, List<AttachmentInfo> attachments)`.
  Lists are copied and never `null`. `date` is `null` when the header is missing or invalid.

### 2. `mime/MimeReader` (new package)

```java
public final class MimeReader {
    /** Sets the three Angus properties above; call before any jakarta.mail class is used. */
    public static void configureAngus();
    /**
     * Parses message.eml. When attachmentsDir is not null, writes every attachment there
     * (created only if there is one); null only lists them.
     */
    public static ParsedMessage read(Path eml, Path attachmentsDir) throws MessagingException, IOException;
}
public record ParsedMessage(MailMessage message, String body, boolean html) { }  // mime/, own file
```

**Headers**
- `subject` comes from `getSubject()`.
- `from`, `to` and `cc` come from `getFrom()` and `getRecipients(..)`, mapped to
  `Address(getPersonal(), getAddress())`. A header that throws becomes an empty list.
- `date` is `getSentDate()` as an `Instant`, or `null`.
- `messageId` is `getMessageID()`, `inReplyTo` is the first `In-Reply-To` header, and `references`
  is the `References` header split on whitespace.

**Walk.** Go depth-first over `multipart/*`. Every other part is a leaf.
- A leaf is a **text candidate** when all of these hold:
  - its type is `text/plain` or `text/html`;
  - its disposition is not `attachment`;
  - it has no file name.
- Every other leaf is an **attachment**, including `message/rfc822` (never opened), inline images
  with a `Content-ID`, and unnamed binary parts.

**Body**
- The body is the first `text/plain` candidate. Without one, it is the first `text/html`
  candidate, with `html = true`. Without either, `body` is `null`.
- The text comes from the part's `getContent()` string, which uses the declared charset.
- Other candidates (the HTML alternative of `M06`, the second text part of `M05`) are ignored.

**Attachments** are numbered from 1 in walk order.
- The stored name is `<n>__<sanitized name>`; without a name it is `<n>__part`.
- To sanitize a name:
  - replace `<>:"/\|?*`, control characters and trailing dots or spaces with `_`;
  - cut the result to 100 characters.
- The `<n>__` prefix keeps names unique and keeps Windows reserved names such as `CON.txt` from
  being used bare.
- Bytes are the decoded stream (`getInputStream()`), copied as is. `size` is the number of bytes
  read, also when nothing is written.

### 3. `convert/Pandoc` (new package)

```java
public final class Pandoc {
    /** Runs "pandoc --version"; throws PandocException("pandoc not found on PATH") if that fails. */
    public static Pandoc locate();
    public Pandoc(String executable);     // no check, for tests that never convert
    /** HTML to GitHub Markdown in a sandboxed pandoc process. */
    public String htmlToMarkdown(String html);
}
public final class PandocException extends RuntimeException { }  // fixed messages, no mail content
```

- Use `ProcessBuilder` with stderr set to `Redirect.DISCARD`.
- Write the UTF-8 bytes of `html` to stdin, close it, read stdout as UTF-8, then `waitFor`.
- A non-zero exit becomes `PandocException("pandoc failed with exit code <n>")`.
- No timeout and no retry (plan.md 6.9).

### 4. `sync/MessageProcessor` (new)

```java
public final class MessageProcessor {
    public MessageProcessor(MessageStore store, Pandoc pandoc);
    /** Steps b-c: attachments and body.md, then meta.json converted or failed. */
    public Processed process(MessageMeta meta);
    /** For a converted message whose index entry is missing: reads, writes nothing. */
    public Processed load(MessageMeta meta);
}
public record Processed(MessageMeta meta, MailMessage message, String body) { }  // sync/, own file
```

**`process`**
1. Delete `body.md` and `attachments/` left by an earlier attempt.
2. Call `MimeReader.read(eml, dir/attachments)`.
3. Get the Markdown: `body` as is for plain text, `pandoc.htmlToMarkdown(body)` for HTML.
4. Write `body.md` (UTF-8) when there is a body.
5. Write `meta.json` with `state=converted`, `error=null`.
6. Return the new meta, the message and the Markdown.

On `MessagingException` or `IOException` from `MimeReader`, or on `PandocException`:
- write `meta.json` with `state=failed`;
- set `error` to `"MIME parsing failed (<simple class name>)"`, or to the pandoc message;
- return it with `message` and `body` set to `null`;
- `message.eml` stays.

`MessageStore` gets one constant `BODY_FILE = "body.md"` and one `ATTACHMENTS_DIR = "attachments"`.
No other store change.

**`load`** reads with `attachmentsDir = null` and takes the body from `body.md` (or `null` if it
is missing). If the read fails, return the meta with `null` message and body; do not change
`meta.json`.

### 5. Index (`index/MailIndex`)

Replace `put(MessageMeta)` with `put(MessageMeta meta, MailMessage message, String body)`.
`message` and `body` may be `null`. The analyzer stays the default `StandardAnalyzer` (step 7.5
changes it and rebuilds).

New fields, as in plan.md 6.5:

| Field | How it is stored |
|---|---|
| `date` | `LongPoint`, `StoredField` and `NumericDocValuesField`, epoch millis; absent when null |
| `subject`, `from`, `to`, `cc` | `TextField`, stored; addresses formatted `Name <address>` or `address`, joined with `, ` |
| `messageId`, `inReplyTo` | `StringField`, stored |
| `references` | `StringField`, stored; one field per id |
| `body` | `TextField`, stored |
| `attachmentNames` | `TextField`, stored; the `name`, or else the `file`, of each attachment, joined with `\n` |

Change `reconcile(MessageStore store)` to return the sorted keys that are on disk with
`meta.json` but missing from the index. It no longer indexes them itself. Deleting directories
without `meta.json` and deleting orphan documents stay as they are.

### 6. `SyncRunner`

- Constructor: `SyncRunner(MessageStore, MailIndex, MailSource, MessageProcessor)`.
- **Start of a run.** For each key `reconcile` returns:
  - `downloaded` → `process`;
  - `converted` → `load`;
  - `failed` → `put(meta, null, null)`.
  Commit once after the loop.
- **Download.** After a successful download, write `meta.json` (`downloaded`) as today, then
  `process` it, then `put` the result. A failed download is put with `null`, `null`.
- **Counters.** `FolderProgress.downloaded` counts new messages that ended `converted`; `failed`
  counts new messages that ended `failed`, whether the download or the conversion failed.
  Batches, commits and cursor handling do not change.

### 7. `cli/MailReprocessCommand` (new) and `MailSyncCommand`

Usage: `mail-reprocess <connection>`, jar mode `mail-reprocess`, Gradle task `mailReprocess`. It
has the same shape as `MailSyncCommand`, with a testable
`run(args, properties, out, pandocLocator, env)`. It never builds a `MailConnection` and never
contacts a mailbox.

**Order**
1. Load the configuration and select the connection.
2. `Pandoc.locate()`. On failure: `RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)`,
   exit 2.
3. `MailIndex.openWriter`. When it is locked: exit 5 with the existing message.
4. For every key in `store.keys()` whose meta exists and whose directory has `message.eml`:
   `process`, then `put`. Messages that failed to download (no `message.eml`) stay as they are.
5. Commit after every 100 messages. Print `  <n> messages processed` after every 1,000.
6. Before the result, print one line per distinct failure reason with its count, sorted by count
   descending: `  failed: <error>: <count>`.
7. Finish with `RESULT: OK (<n> messages, <c> converted, <f> failed)`.

Error handling and exit codes follow `MailSyncCommand` (local storage → 4, unexpected → 1).

**Changes to `MailSyncCommand`**
- Check for pandoc first, with the same failure line and exit 2.
- Build `MessageProcessor` for `SyncRunner`.
- Give its `run(..)` the same `pandocLocator` parameter, so tests do not need pandoc.

**Startup**
- `MailMcpServerApplication.main` calls `MimeReader.configureAngus()` as its first statement and
  dispatches `mail-reprocess`.
- `MailSyncCommand.run(String[])` and `MailReprocessCommand.run(String[])` call it too, because
  the Gradle tasks start them directly.
- In `build.gradle.kts`, `tasks.test` sets the same three system properties.

## Tests

All offline. A test that needs pandoc starts with `assumeTrue(pandocAvailable())`, so it is
skipped on a machine without pandoc. Put that helper in one test class and reuse it.

`mime/MimeReaderTest`:

- `angusPropertiesAreSet`: the three system properties have the values above in the test JVM.
- `testdataSetsMatchExpectations`: a parameterized test over all 20 `testdata/mime` cases.
  - **Headers:** subject; from and to (name and address); date as an instant (or `null`);
    messageId.
  - **Body:** whether there is one, plain or HTML. For plain bodies, every `textContains` string
    is present.
  - **Attachments:** the expected top-level `parts` that are attachments under the rule above
    (ignore `children`). For each: decoded size, SHA-256 of the written file, decoded name.
  - **Deviations:** where a case differs from the expectation and section 2 explains why, assert
    our value with a comment naming the rule. Known cases: `M05` (no charset correction), `M09`
    (embedded message length), `M16` part 3 (name), `M17` (`1__part`). Any **other** difference:
    stop and ask (rule 9).
- `storedNamesAreSafeOnWindows`: `M16` file names contain none of `<>:"/\|?*` or control
  characters and stay inside `attachments/`; there are no duplicates; `3__CON.txt` is used.
- `longNameIsCutTo100Characters`: a synthetic message built in the test.
- `nullDirectoryListsWithoutWriting`

`convert/PandocTest` (needs pandoc):

- `htmlBecomesMarkdownWithoutStylesOrScripts`
- `sandboxRefusesLocalFiles`: an HTML `<img src="file:///...">` pointing at a temp file does not
  bring the file's content into the output.
- `missingExecutableIsReported`: does not need pandoc; it uses a non-existent executable name.

`sync/MessageProcessorTest`:

- `plainMessageIsConvertedWithBodyAndAttachments` (`M01`)
- `htmlOnlyMessageBecomesMarkdown` (`M07`, needs pandoc): `textContains` present,
  `textNotContains` absent.
- `unreadableMessageIsFailedAndKeepsTheOriginal`: a synthetic `message.eml` that makes Angus
  throw, or a pandoc that exits non-zero (`new Pandoc` pointing at a non-existent name); the meta
  is `failed` with a fixed error and `message.eml` is unchanged.
- `reprocessingReplacesOldOutput`: process twice; no leftover attachment from the first run.
- `loadReadsWithoutWriting`

`SyncRunnerTest`: update the existing tests to the new constructor and to `converted`. Add:

- `downloadedMessageWithoutIndexEntryIsProcessedOnReconcile`
- `indexRebuildRestoresHeaderFields`: delete `index/`; after the next run, `subject` and `body`
  are back.

`cli/MailReprocessCommandTest`:

- `missingPandocStopsBeforeTheIndex`
- `lockedIndexExitsFive`
- `reprocessConvertsDownloadedMessagesAndPrintsReasons`: synthetic directories with two good
  messages and one unparseable message; checks the lines and the `RESULT` line, and that the
  index has `converted` and `failed`.

`MailSyncCommandTest`: `missingPandocStopsBeforeSignIn`. Existing tests pass a locator that
returns `new Pandoc("pandoc")`.

## Do not

- Start `pandoc server`, add a pandoc path setting, a timeout, a retry, a process pool or a
  fallback without pandoc.
- Convert attachments to `.md`, add PDFBox or POI, or index attachment text (step 7.6).
- Open nested messages, ZIP, MSG, TNEF or S/MIME; guess charsets; strip HTML yourself.
- Add `RussianAnalyzer` or `lucene-analysis-common` (step 7.5), MCP tools or `api/` records
  (IT-06), or `MessageCard` (IT-06).
- Add states, warning codes or fields beyond those listed (plan.md 6.9). In particular, the
  `warnings` of the expected JSON files are not a contract.
- Write headers into `meta.json` or any new JSON file.

## Stop and ask when

- A `testdata/mime` case differs from its expectation in a way that section 2 does not explain.
- The Angus properties do not take effect in the test JVM.
- Pandoc behaves differently from the facts above (exit codes, encoding, sandbox).

## Files

Create:

- `src/main/java/mail/mcp/model/{Address,AttachmentInfo,MailMessage}.java`
- `src/main/java/mail/mcp/mime/{MimeReader,ParsedMessage}.java`
- `src/main/java/mail/mcp/convert/{Pandoc,PandocException}.java`
- `src/main/java/mail/mcp/sync/{MessageProcessor,Processed}.java`
- `src/main/java/mail/mcp/cli/MailReprocessCommand.java`
- matching tests:
  - `src/test/java/mail/mcp/mime/MimeReaderTest.java`
  - `src/test/java/mail/mcp/convert/PandocTest.java`
  - `src/test/java/mail/mcp/sync/MessageProcessorTest.java`
  - `src/test/java/mail/mcp/cli/MailReprocessCommandTest.java`
- `docs/mime-agent.md`

Change:

- `index/MailIndex.java`, `store/MessageStore.java` (two constants), `sync/SyncRunner.java`
- `cli/MailSyncCommand.java`, `MailMcpServerApplication.java`, `build.gradle.kts`
- `SyncRunnerTest.java`, `MailSyncCommandTest.java`, and other tests that call `MailIndex.put` or
  `reconcile`
- the documents below

## Documents

- `docs/mime-agent.md` (new, English): the Angus properties and why each one is needed; the walk,
  body and attachment rules; stored names; pandoc as a sandboxed process per conversion and the
  measured facts; what is never opened; the known `testdata/mime` deviations. The reviewer
  registers it in `AGENTS.md`; do not edit `AGENTS.md`.
- `docs/storage-agent.md`: `body.md`, `attachments/`, the `converted` state, the new write order,
  `failed` after conversion, the new index fields, and reconciliation by state.
- `docs/build-config-agent.md`: pandoc is required on `PATH`; a section "Reprocessing" with the
  command (jar and Gradle), output and exit codes; `mail-sync` now checks pandoc (exit 2).
- `docs/architecture-agent.md`: packages `mime/` and `convert/` move to the existing packages;
  `PandocServer` becomes `Pandoc`; the status line.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes. Report how many tests
  were skipped for lack of pandoc. The reviewer's machine has pandoc.
- `git status` shows only the files listed under "Files".
- The report lists under "For the user to run":
  - `java -jar build\libs\mail-mcp-server.jar mail-reprocess mailru`
  - `java -jar build\libs\mail-mcp-server.jar mail-reprocess work`

  Both run offline, with no sign-in. Expect most messages `converted`; the failure reasons are
  printed with counts.

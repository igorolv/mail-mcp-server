# IT-20 — Lower the XLSX conversion threshold (Д21)

Prepared: 2026-10-27. Baseline: `d3fd516`. Independent of the other five briefs.

## Goal

Reduce exposure to eager `XSSFWorkbook` memory use by admitting XLSX text conversion only up
to **5 MiB** of decoded attachment bytes. This is a conservative implementation choice, not a
measured safe heap limit. No JVM out-of-memory incident is established by this brief. The user
selected Д21 for work; do not manufacture an incident to justify it.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.1, 3.5, 3.8 and Д21 in section 4.
- `docs/mime-agent.md`, `docs/tools-agent.md`, `docs/storage-agent.md`, `docs/build-config-agent.md`.
- `src/main/java/mail/mcp/convert/AttachmentText.java`, `XlsxText.java` in the same directory.
- `src/main/java/mail/mcp/sync/MessageProcessor.java`.
- `src/test/java/mail/mcp/convert/AttachmentTextTest.java`,
  `src/test/java/mail/mcp/sync/MessageProcessorTest.java`.

## What to build

1. Keep the existing overall 20 MiB check. Add an XLSX-specific constant of `5L * 1024 * 1024`
   in `AttachmentText`. After format selection, before `XlsxText.extract`, skip XLSX whose
   actual `Files.size(file)` exceeds this constant. Exactly 5 MiB is eligible. Use the resolved
   format, so uppercase suffixes, no filename and MIME-type fallback cannot bypass the check.
   Retain current format-selection precedence; do not introduce signature sniffing.
2. Skipping returns `Optional.empty()`. Keep the original attachment, message state and all
   download/extraction thresholds unchanged. Do not log attachment names/content or add an
   error to meta.json. Other formats keep the 20 MiB threshold, including binary XLS.
3. The same dispatch already handles files inside containers: cover this path with a test.
   During reprocessing, the existing output cleanup must remove an old XLSX sidecar when the
   new limit skips it; rebuilt attachmentText must no longer contain that sidecar's text.
   Do not erase old sidecars merely on a read or reindex. Existing text remains usable until
   user-run reprocessing. No index schema change.
4. Update descriptions that promise 20 MiB for every format, including tool annotations.
   Explain that compressed size does not bound POI memory usage and smaller spreadsheets can
   still be expensive. Do not promise prevention of all out-of-memory failures.

## Named tests

- `AttachmentTextTest.xlsxAtFiveMiBIsEligibleAndOneByteOverIsSkipped`
- `AttachmentTextTest.xlsxLimitUsesResolvedFormatAndActualFileSize`
- `AttachmentTextTest.nonXlsxFormatsKeepTwentyMiBLimit`
- `MessageProcessorTest.oversizedNestedXlsxKeepsBinaryWithoutMarkdown`
- `MessageProcessorTest.reprocessRemovesPreviouslyIndexedOversizedXlsxText`

Generate a small valid workbook and pad its ZIP with a harmless stored entry to hit exact
byte boundaries; do not create a workbook that exhausts heap. The at-limit fixture must produce
known text, so an empty result for a corrupt file cannot falsely prove the size check. For the
dispatch test, use misleading `AttachmentInfo.size` values and MIME fallback. Test fixture
helpers may be private methods in these test classes; do not modify `testdata/`.

## Files

Change:

- `src/main/java/mail/mcp/convert/AttachmentText.java`
- `src/main/java/mail/mcp/tools/MailTools.java`, `SearchTools.java` in the same directory
  (descriptions only).
- The two test classes named above.
- Documents listed below.

`XlsxText.java` and production processing/storage code should not need changes.

## Documents

Update `README.md`, `docs/mime-agent.md`, `docs/tools-agent.md`, `docs/storage-agent.md` with
the 5 MiB XLSX exception, best-effort meaning and reprocess/reindex distinction. Recording
updates plan.md 3.1/3.5 and the AGENTS.md MIME summary if needed. No invented real-mail results.

## Do not

Do not add SAX/event parsing, subprocesses, shared budgets, heap monitoring, OOME recovery,
new settings, dependencies, ZIP limits or limits for other formats. Do not run reprocessing on
real stored messages.

## Acceptance

Run `.\gradlew.bat build` and shared checks. Offline tests are sufficient. Reprocessing real
mail is optional and user-run; it can remove searchable text for XLSX between 5 and 20 MiB.

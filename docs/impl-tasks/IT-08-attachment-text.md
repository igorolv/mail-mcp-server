# IT-08 — attachment text

Plan step: `docs/plan.md` 7.6. Size: one full session. This is the last brief of steps 7.1–7.6;
step 7.7 waits for user decisions.

## Goal

Attachments get a text form next to the file: `attachments/<file>.md`. Search finds a message by
a word inside its attachment. A new tool `getAttachment` returns that text in chunks, with the
file path.

The formats follow plan.md 6.7:
- pandoc for DOCX, ODT, RTF, EPUB, HTML and CSV;
- PDFBox for PDF;
- POI for XLSX and PPTX;
- plain text as is.

Attachments over 20 MiB are kept without `.md`. A failed conversion of one attachment leaves
only that attachment without `.md`; the message stays `converted`.

## Read first

1. `docs/plan.md` sections 6.4, 6.5, 6.7, 6.9 and 7.6 (Russian; what you need is repeated below).
2. `docs/mime-agent.md`, `docs/storage-agent.md`, `docs/tools-agent.md`.
3. `src/main/java/mail/mcp/{mime,convert,sync,index}/` — all files; `model/AttachmentInfo.java`,
   `tools/MailTools.java`, `api/AttachmentRef.java`, `api/GetMessageResult.java`.
4. In the sibling project `C:\git\redmine-mcp-server` at commit `f62b3e5`, only the
   `extractText` methods of `extraction/parser/PdfTextParser.java`, `XlsxTextParser.java` and
   `PptxTextParser.java`. Do not copy the parser framework around them (`AbstractDocumentParser`,
   `FileTypeDetector`, Spring annotations). Never write that project's package root anywhere: it
   contains the company name (AGENTS.md invariant 1).

## Already decided — do not revisit

- **Pandoc reads attachment files given on the command line.** The reviewer checked pandoc
  3.1.11 on 2026-10-26:
  - `pandoc --sandbox -f <format> -t gfm --wrap=none --eol=lf <file>` reads DOCX, ODT, RTF and
    EPUB (the sandbox still blocks files referenced from inside the document);
  - a broken DOCX exits 63;
  - a CSV with `;` separators, as Russian Excel writes it, becomes one column, and its text is
    still searchable; that is acceptable.
- **Text formats go through stdin.** Pandoc assumes UTF-8 input, so HTML and CSV attachments are
  first decoded with their declared charset (UTF-8 when none is declared, malformed bytes
  replaced), then passed on stdin as the HTML body is today.
- **Plain text** attachments are decoded the same way and written as is.
- The limit for conversion is 20 MiB (`20 * 1024 * 1024` bytes) of decoded attachment size.
  Larger files get no `.md`. There is no other budget, timeout or counter (plan.md 6.9).
- Unsupported formats get no `.md`: images, archives, nested messages, MSG, TNEF, calendars,
  S/MIME, and old binary Office files (`.doc`, `.xls`, `.ppt`). No OCR.
- Blank extracted text means no `.md`.
- Errors are not recorded per attachment. `meta.json` and the message state do not change because
  of an attachment.
- Index schema goes to `3`. The existing schema check recreates the index on open, and
  restore/reprocess fills it. Attachment text is indexed, not stored (plan.md 6.5).
- Library versions, from the local Gradle cache, the same line as the sibling project:
  - `org.apache.pdfbox:pdfbox:3.0.5`;
  - `org.apache.poi:poi-ooxml:5.4.1`.

## What to build

### 1. `model/AttachmentInfo` gets `String charset`

- The value is the `charset` parameter of the part's `Content-Type`, lowercased. It is `null`
  when the part does not declare one.
- `MimeReader` fills it.
- Update the constructor calls in tests. No other `MimeReader` change.

### 2. `convert/`

- `Pandoc`: add `String toMarkdown(Path file, String format)`, which passes the file as an
  argument, and `String toMarkdown(String text, String format)`, which uses stdin. The existing
  `htmlToMarkdown(String)` becomes `toMarkdown(text, "html")`; keep the method as a one-line
  delegate or replace its calls. Error handling is as today.
- `PdfText`, `XlsxText`, `PptxText`: one final class each, with one
  `static String extract(Path file) throws IOException`.
  - The bodies are the sibling `extractText` methods, reading from the file.
  - The placeholder strings of the sibling, such as "(PDF contains no extractable text …)", are
    dropped: return an empty string instead.
  - Each class Javadoc names `redmine-mcp-server`, the class path
    `extraction/parser/<Name>.java` and commit `f62b3e5` (AGENTS.md "Java code").
- `AttachmentText`:
  `static Optional<String> toMarkdown(Path file, AttachmentInfo info, Pandoc pandoc)`.
  - It picks the converter by the lowercase extension of `info.name()`, otherwise of
    `info.file()`. When there is no known extension, it uses the content type:

    | Extension | Content type | Converter |
    |---|---|---|
    | `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | pandoc file, `docx` |
    | `odt` | `application/vnd.oasis.opendocument.text` | pandoc file, `odt` |
    | `rtf` | `application/rtf`, `text/rtf` | pandoc file, `rtf` |
    | `epub` | `application/epub+zip` | pandoc file, `epub` |
    | `html`, `htm` | `text/html` | decode, pandoc stdin, `html` |
    | `csv` | `text/csv` | decode, pandoc stdin, `csv` |
    | `pdf` | `application/pdf` | `PdfText` |
    | `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `XlsxText` |
    | `pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | `PptxText` |
    | `txt` | `text/plain` | decode, as is |

  - It returns empty when:
    - the file is larger than the limit;
    - the format is not in the table;
    - the text is blank;
    - any converter throws (`IOException`, `PandocException`, or a `RuntimeException` from POI
      or PDFBox).
  - Every failure is logged at debug level with the exception's class name only.

### 3. `sync/MessageProcessor`

- **`process`**: after the attachments and `body.md` are written, call `AttachmentText` for every
  attachment. Write each result to `attachments/<file>.md` (UTF-8). Collect the texts in
  attachment order, joined with a blank line.
- **`load`**: read the existing `.md` files instead of converting.
- `Processed` gets a fourth component, `String attachmentText` (`null` when none).
- Writing a `.md` is local storage: an `IOException` there propagates as today for `body.md`.

### 4. Index and search

- `MailIndex.put(meta, message, body, attachmentText)`:
  - new fields `attachmentText` (Russian) and `attachmentText_en` (English), `TextField`, not
    stored;
  - `SCHEMA = "3"`;
  - the English analyzer covers `attachmentText_en` in `MailAnalyzers`.
- `SearchField`:
  - add `ATTACHMENT_TEXT("attachmentText", "attachmentText", "attachmentText_en")`;
  - add both fields to `ANY`.
- The snippet stays on `body`. A hit found only in attachment text may have a leading-text
  snippet; that is acceptable.

### 5. Tool `getAttachment` (in `tools/MailTools`) and `api/`

- `getAttachment(connection, key, file, offset, maxChars)`:
  - `file` is the stored name from `getMessage`. It must equal the `file` of one attachment
    listed by `MimeReader.read(eml, null)`; anything else is an `IllegalArgumentException`.
  - Bounds are as for `getMessage` (20,000 by default, at most 100,000 characters).
- `api/GetAttachmentResult(key, file, name, contentType, size, path, textPath, text, offset,
  nextOffset, textLength)`. `text` and `textPath` are absent without `.md`.
- `api/AttachmentRef` gets `textPath` (absent without `.md`), so `getMessage` shows which
  attachments have text.
- The tool description says: the text exists only for the listed formats up to 20 MiB; `path` is
  the original file; `textPath` is the Markdown.

## Tests

All offline. Tests that need pandoc start with the existing `assumeTrue(pandocAvailable())`.
Build the synthetic files in the test itself:
- DOCX with POI `XWPFDocument`;
- XLSX with `XSSFWorkbook`;
- PPTX with `XMLSlideShow`;
- PDF with PDFBox `PDDocument` and a standard 14 font, Latin text only, so no font file is needed.

`convert/AttachmentTextTest`:
- `pdfTextIsExtracted`
- `xlsxCellsAreExtracted`
- `pptxSlideTextIsExtracted`
- `docxGoesThroughPandoc` (pandoc)
- `csvAndHtmlAreDecodedWithTheirCharset`: a `windows-1251` CSV gives correct Cyrillic (pandoc).
- `plainTextIsDecodedAsIs`
- `unknownFormatHasNoText`: PNG bytes, `.zip`, `.doc`.
- `largerThanTheLimitHasNoText`: a sparse 20 MiB + 1 byte file; it is never read.
- `brokenFileHasNoText`: a `.docx` and a `.pdf` with garbage bytes.
- `blankTextHasNoText`

`sync/MessageProcessorTest` (add):
- `attachmentMarkdownIsWrittenNextToTheFile`
- `failedAttachmentLeavesTheMessageConverted`
- `loadReadsAttachmentMarkdown`

`index/SearchControlSetTest`:
- It now needs pandoc for the CSV attachment.
- Assert recall ≥ 0.91 and false positives ≤ 7. Q061 must now be found.
- Report the new numbers.

`index/MailIndexReaderTest`: `attachmentTextFieldFindsTheMessage`. `index/MailIndexTest`: the
schema test uses `3`.

`tools/MailToolsTest`:
- `getAttachmentChunksTheText`
- `getAttachmentRejectsUnknownFile`, including `..\..\x` and a name of another message.
- `getMessageShowsTextPath`

`McpStdioServerTest`: eight tool names; call `getAttachment` once.

## Do not

- Copy the sibling's parser framework, ZIP parser, Tika, embedded or media extractors, or add
  Tika.
- Add OCR, `.doc`/`.xls`/`.ppt`, nested message parsing or archive unpacking.
- Add a per-attachment state, error field, counter, budget or timeout.
- Store attachment text in the index or in `meta.json`.
- Change the message state because of an attachment.

## Stop and ask when

- POI or PDFBox at the versions above pulls in a dependency conflict with Spring Boot or Lucene.
- The control set drops below 0.91 or exceeds 7 false positives.
- Pandoc behaves differently from the facts above.

## Files

Create:

- `src/main/java/mail/mcp/convert/{AttachmentText,PdfText,XlsxText,PptxText}.java`
- `src/main/java/mail/mcp/api/GetAttachmentResult.java`
- tests: `src/test/java/mail/mcp/convert/AttachmentTextTest.java`

Change:

- `gradle/libs.versions.toml`, `build.gradle.kts`
- `model/AttachmentInfo.java`, `mime/MimeReader.java` (charset only)
- `convert/Pandoc.java`, `sync/{MessageProcessor,Processed,SyncRunner}.java`
- `index/{MailIndex,MailAnalyzers,SearchField}.java`
- `tools/MailTools.java`, `api/AttachmentRef.java`, `cli/` commands that call `put` or use
  `Processed`
- tests that construct `AttachmentInfo` or `Processed`, or call `put`; `McpStdioServerTest.java`
- the documents below

## Documents

- `docs/mime-agent.md`: attachment conversion — the format table, the limit, charset decoding,
  pandoc file and stdin modes, the measured facts, what is never converted.
- `docs/storage-agent.md`: `<file>.md`, `attachmentText` fields, schema 3, and that
  `mail-reprocess` creates the `.md` files for messages already on disk.
- `docs/tools-agent.md`: `getAttachment`, `textPath`, the `attachmentText` search field, and the
  new control-set numbers.
- `docs/architecture-agent.md`: PDFBox and POI, the copied classes with their source, the status
  line.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- The report gives the control-set numbers.
- `git status` shows only the files listed under "Files".
- The report lists under "For the user to run":
  - `java -jar build\libs\mail-mcp-server.jar mail-reprocess work`, then the same for `mailru`.
    They run offline and are slower than before, because attachments are converted. No sync may
    be running.
  - Then, from opencode: `search` with `field: attachmentText` for a word that is only inside an
    attachment, and `getAttachment` on one PDF or DOCX.

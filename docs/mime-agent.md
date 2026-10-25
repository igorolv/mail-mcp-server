# docs/mime-agent.md — MIME reading and body conversion

`mime/MimeReader` reads local MIME using
Angus Mail and `MimeMessage` over `SharedFileInputStream`. No mail source is involved.
The reader closes the backing file after parsing, including the shared views retained by
Jakarta Mail; no stream escapes the reader.

## Angus startup properties

Call `MimeReader.configureAngus()` before any Jakarta Mail class is used. The application
entry point and both standalone sync/reprocess entry points do this. Gradle sets the same
properties on the test JVM. Angus reads the first two once at class initialization.

| Property | Value | Reason |
|---|---|---|
| `mail.mime.decodefilename` | `true` | Outlook-style RFC 2047 encoded-word filenames (M02) |
| `mail.mime.parameters.strict` | `false` | Accept the unquoted extended filename `/etc/passwd` in M16 |
| `mail.mime.base64.ignoreerrors` | `true` | Preserve the five decoded bytes of truncated base64 in M14 |

## Headers, body and attachments

Headers come from Jakarta Mail getters, including decoded personal names. An address header
that throws becomes an empty list. Addresses are mapped directly; a group remains a group
address. The date is null when absent or invalid. References are split on whitespace.
`MailMessage` copies all lists and normalizes null lists to empty lists. Headers are not
written into metadata or another JSON file.

Walk `multipart/*` depth first. All other parts are leaves. A body candidate is `text/plain`
or `text/html`, without a filename and without attachment disposition. Select the first plain
candidate, otherwise the first HTML candidate, otherwise no body. Other candidates are ignored.
Use the declared charset without guessing. Every other leaf is an attachment, including unnamed
binary parts and inline images. Nested messages, MSG, TNEF, ZIP, S/MIME and calendars are never
opened by the reader: they remain opaque attachment files.

Attachments are numbered from one in walk order. Stored names are `<n>__<sanitized name>` or
`<n>__part`. Replace Windows-forbidden characters `<>:"/\|?*`, control characters, and trailing
dots/spaces with `_`; truncate the sanitized name to 100 characters. Truncation also leaves no
trailing dot or space. The prefix prevents collisions and bare reserved names such as `CON.txt`.
The decoded name is kept separately. `Content-ID` loses enclosing angle brackets. Content types
are lowercase base types; inline means disposition `inline`. `AttachmentInfo.charset` is the
lowercase declared Content-Type charset parameter, or null when absent.

`getInputStream()` supplies decoded attachment bytes. Size counts bytes read. Passing a null
attachment directory still lists and counts attachments, but writes nothing. A non-null
directory is created only when an attachment exists.

## Pandoc and processing

`convert/Pandoc.locate()` runs `pandoc --version`. Pandoc must be on `PATH`; there is no path
setting or fallback. Both commands check it before opening the index or contacting a mailbox.
Each body or attachment conversion through pandoc starts one process:

```text
pandoc --sandbox -f <format> -t gfm --wrap=none --eol=lf [file]
```

Stdin text and output are UTF-8, stderr is discarded, stdin is closed after writing. A nonzero exit
has the fixed message `pandoc failed with exit code <n>`. I/O and interruption messages are
fixed too and never include mail or process output. There are no timeouts or retries.

The reviewer measured pandoc 3.1.11 on 2026-10-25: `pandoc server` listens on `0.0.0.0` without
a localhost-only option; therefore the sandboxed per-conversion mode applies (plan.md 3.5).
A run cost about 27 ms; unknown formats exited 21. The sandbox refused a local LaTeX `\input`.
Tests cover UTF-8 Markdown, removal of styles/scripts and no inclusion of local image content.

`MessageProcessor.process` removes previous body/attachments, parses MIME, converts HTML,
writes UTF-8 `body.md` if there is a body, then writes converted metadata. MIME errors and
pandoc failures write failed metadata with a fixed reason; the original MIME stays. Local
cleanup/body-write errors propagate as local storage errors. `load` reads MIME and saved
Markdown without writing, returning null message/body/attachmentText on read failure.
`Processed.attachmentText` joins available texts in attachment order
with a blank line, or is null when none exists.

## Synthetic fixture differences

The tests cover all 20 `testdata/mime` cases, without modifying fixtures or treating warnings
as a contract. These differences follow the rules above and were accepted in review
(2026-10-25):

- M05: declared ISO-8859-1 stays garbled; the second candidate is ignored.
- M09: the opaque embedded-message stream is 6,276 bytes rather than 6,278; appending CRLF
  reproduces the expected hash.
- M16 part 3: the decoded name is `/etc/passwd`, sanitized only for storage.
- M16 part 6: Angus retains the control character in the decoded name, matching the fixture;
  storage replaces it with `_`. The XML test report renders that character as `?`; the brief's
  statement that Angus strips it was wrong (the reviewer's console hid the character).
- M17: the unnamed attachment is `1__part`, without a guessed extension.
- M18: split encoded words retain U+FFFD as allowed by the fixture README.
- M19: the opaque embedded-message stream is 3,381 bytes rather than 3,391; keep Angus output
  and its pinned SHA-256 without traversing the nested message. Like M09, the difference is
  probably the trailing CRLF lost at each nesting level; nested messages are not opened (plan.md
  3.5), and `message.eml` keeps the original bytes.
- M20: the invalid date stays null and the recipient group stays a group address.

## Attachment text

After attachment files and body.md are saved, `AttachmentText` converts each supported file to
UTF-8 `attachments/<file>.md`. Files larger than **20 MiB (20 * 1024 * 1024 decoded bytes)**
are kept without Markdown. Blank text and conversion errors also produce no Markdown. A failed
attachment never changes message state or adds an error to metadata. Exceptions are logged at
debug level by class name only. Markdown write errors propagate as local storage failures.
`MessageProcessor.load` reads existing Markdown without running converters.

The lowercase extension of the decoded name (stored filename when unnamed) selects the format;
without a known extension the MIME base content type selects it.

| Extensions | Content types | Conversion |
|---|---|---|
| docx | application/vnd.openxmlformats-officedocument.wordprocessingml.document | pandoc file, docx |
| odt | application/vnd.oasis.opendocument.text | pandoc file, odt |
| rtf | application/rtf, text/rtf | pandoc file, rtf |
| epub | application/epub+zip | pandoc file, epub |
| html, htm | text/html | decode, pandoc stdin, html |
| csv | text/csv | decode, pandoc stdin, csv |
| pdf | application/pdf | PDFBox |
| xlsx | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet | POI |
| pptx | application/vnd.openxmlformats-officedocument.presentationml.presentation | POI |
| txt | text/plain | decode, save as is |

HTML, CSV and plain text use the declared charset, UTF-8 when absent, with malformed bytes
replaced; no charset guessing. Text passed to pandoc stdin is UTF-8. DOCX/ODT/RTF/EPUB are
passed as file arguments, with stdin closed. `htmlToMarkdown` delegates to the generic stdin
method. There are no additional budgets, timeouts, counters or per-attachment states.

The reviewer measured pandoc 3.1.11 on 2026-10-26: sandboxed file arguments work for DOCX, ODT,
RTF and EPUB, while references inside a document remain sandboxed. Broken DOCX exits 63.
Semicolon-separated CSV becomes one column; its text remains searchable, which is accepted.

Images, archives, nested messages, MSG, TNEF, calendars, S/MIME and binary DOC/XLS/PPT are never
converted; no OCR, archive expansion or embedded-message parsing is added.

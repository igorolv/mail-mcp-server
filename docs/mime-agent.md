# docs/mime-agent.md — MIME reading and body conversion

IT-05 implements the first half of plan.md 7.4. `mime/MimeReader` reads local MIME using
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
are lowercase base types; inline means disposition `inline`.

`getInputStream()` supplies decoded attachment bytes. Size counts bytes read. Passing a null
attachment directory still lists and counts attachments, but writes nothing. A non-null
directory is created only when an attachment exists.

## Pandoc and processing

`convert/Pandoc.locate()` runs `pandoc --version`. Pandoc must be on `PATH`; there is no path
setting or fallback. Both commands check it before opening the index or contacting a mailbox.
Each HTML conversion starts one process:

```text
pandoc --sandbox -f html -t gfm --wrap=none --eol=lf
```

Input and output are UTF-8, stderr is discarded, stdin is closed after writing. A nonzero exit
has the fixed message `pandoc failed with exit code <n>`. I/O and interruption messages are
fixed too and never include mail or process output. There are no timeouts or retries.

The reviewer measured pandoc 3.1.11 on 2026-10-25: `pandoc server` listens on `0.0.0.0` without
a localhost-only option; therefore the sandboxed per-conversion fallback in plan.md 6.7 applies.
A run cost about 27 ms; unknown formats exited 21. The sandbox refused a local LaTeX `\input`.
Tests cover UTF-8 Markdown, removal of styles/scripts and no inclusion of local image content.

`MessageProcessor.process` removes previous body/attachments, parses MIME, converts HTML,
writes UTF-8 `body.md` if there is a body, then writes converted metadata. MIME errors and
pandoc failures write failed metadata with a fixed reason; the original MIME stays. Local
cleanup/body-write errors propagate as local storage errors. `load` reads MIME and saved
Markdown without writing, returning null message/body on read failure. Attachment text is
deferred to step 7.6.

## Synthetic fixture differences

The tests cover all 20 `testdata/mime` cases, without modifying fixtures or treating warnings
as a contract. These differences follow the rules above and were accepted in the IT-05 review
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
  6.9), and `message.eml` keeps the original bytes.
- M20: the invalid date stays null and the recipient group stays a group address.

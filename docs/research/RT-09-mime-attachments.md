# RT-09 — MIME and mail attachments in Java

- Date: 2026-10-24
- Author: Codex / GPT-6, research session RT-09
- Brief: `docs/research-tasks/RT-09-mime-attachments.md`

## Summary

- [assumption] Recommend **Apache James Mime4j 0.8.15, streaming API** for shared parsing of
  `.eml` from EWS and IMAP: it allows saving parts to disk and explicitly limiting structure.
  New shared limits are confirmed in release 0.8.15 of 2026-09-03. [1–3]
- [docs] Angus/Jakarta Mail offers a convenient object model and header decoding;
  `MimeMessage(InputStream)` does not guarantee constant memory. Tika's `RFC822Parser` uses
  Mime4j but buffers some bodies internally, so it is not fully streaming either. [4–7]
- [assumption] Handle RFC 2047 and RFC 2231 separately; use Jakarta Mail `ParameterList` for
  filename parameters and a bounded compatibility mode for nonstandard encoded-word filenames.
  Determine the charset of each text part separately. [8–12]
- [assumption] Preserve the complete body and original; choose one `multipart/alternative`
  representation. Convert HTML with jsoup and custom link/table preservation; quote/signature
  removal should remain an optional derived representation. [13–18]
- [docs] POI HSMF reads MSG, POI HMEF reads TNEF/`winmail.dat`; `poi-scratchpad` is required.
  Both formats need separate attachment traversal, not just aggregate text extraction. [19–23]
- [assumption] New MVP parsers: `EmlMessageParser`, `MsgMessageParser`, `TnefParser`,
  `HtmlBodyParser`, `CalendarInviteParser` and a recognizing `SmimeEnvelopeParser`.
  Limit S/MIME to detection in the MVP; ICS to reading invitations without calendar actions. [24–28]
- [code] The sibling project actually runs **all** matching PRIMARY parsers; early ordering
  alone does not prevent duplicates. `PlainTextParser` captures HTML/ICS, Tika can parse
  containers again, and the ZIP byte counter is local to each archive. [29]
- [assumption] Shared counters are needed across MIME → MSG/TNEF → ZIP → document, with a
  separate MIME depth limit, safe internal names and a process limiting parser work.
  Limiting response length does not bound parsing memory. [3, 29–31]

## Answers to the questions

### В1. MIME parsers: Angus Mail, Mime4j, Tika

[docs] `MimeMessage` belongs to the Jakarta Mail API, for which Eclipse Angus Mail provides a
compatible implementation. For a fair comparison, the table covers API + Angus, rather than a
class in the `org.eclipse.angus` package. [4, 32]

| Candidate | Memory and reading method | Damaged messages | Headers | License and maintenance |
|---|---|---|---|---|
| Angus / `MimeMessage` | [docs] Object model; an ordinary stream is saved as body bytes, `SharedInputStream` allows sharing a stream; multipart is parsed on access | [docs] Modes for a missing final boundary, absent boundary parameter and invalid encoded words | [docs] `getSubject`, addresses, `MimeUtility`, `ParameterList`; raw headers available separately | [docs] EPL-2.0 / GPL-2.0 with Classpath Exception, some parts EDL-1.0; the site lists stable 2.0.5 [4, 8, 32, 33] |
| Mime4j: `MimeStreamParser` / `MimeTokenStream` | [docs] [code] Events or a token sequence; the consumer decides what to save; body decoding is explicitly enabled | [docs] [code] Lenient parsing and error handling; structural limits through `MimeConfig` | [code] `DecoderUtil`, typed fields from the DOM module usable without constructing a body tree | [docs] [code] Apache-2.0; release 0.8.15 adds limits, see below [1–3, 7, 12] |
| Mime4j DOM | [docs] A message tree; the storage module supports temporary files for large bodies | [docs] Same parsing foundation; building the tree increases object counts | [docs] Convenient field and body access | [docs] Apache-2.0, same project; storage must be configured [1, 2] |
| Tika `RFC822Parser` | [code] Streaming Mime4j → SAX/XHTML; `MailContentHandler` accumulates bytes for alternatives and some top-level parts | [code] `MimeConfig` is passed in `ParseContext`; no separate promise to repair every damaged message | [code] Produces message metadata and passes attachments to `EmbeddedDocumentExtractor` | [code] Apache-2.0; source and API 3.2.3 were checked here, not a claim about the latest Tika release [5–7] |

[docs] [code] The Mime4j site still shows 2017-06-15 and version `0.8.2-SNAPSHOT`; its historical
comparison of leniency with JavaMail is not a current measurement. The separate 0.8.15
announcement and tagged source confirm that the project is current. [1–3]

[code] `MimeConfig` in **tag 0.8.15** has `maxPartCount`, `maxNestingDepth`,
`maxTotalHeaderCount`, `maxTotalHeaderLen`, and limits for individual fields, lines, header
counts and entity content. `maxContentLen` does not replace an application-wide counter of
decoded attachments and unpacked archives. [3]

[assumption] Streaming Mime4j is preferable to DOM for mail-mcp-server: the output should be
our own normalized records and files; a mutable message tree is unnecessary. Angus remains
suitable for IMAP; this does not require using its object model as the shared EWS/IMAP parser.
[1, 4, 29]

[assumption] Tika is useful as a source of normalization examples and a fallback document
extractor. Using one `RFC822Parser` as the entire mail layer is harder: it needs a custom
attachment handler and control over buffers, alternatives and CID. [5–7]

### В2. Headers, filenames and Russian charsets

[docs] RFC 2047 defines encoded words such as `=?charset?B?...?=` and `=?charset?Q?...?=` in
allowed header positions; it is not a MIME parameter encoding method. RFC 2231 defines
`filename*=charset'language'value` and continuations `filename*0*`, `filename*1*`.
Do not percent-decode ordinary `filename=`. [9, 10]

[assumption] Recommended filename normalization order: [8–11, 34]

1. Parse `Content-Disposition` parameters, assemble and decode RFC 2231; take a nonempty
   `filename`. If absent, parse `name` from `Content-Type`.
2. On conflict, retain the original values and a warning; do not concatenate different names.
   This priority between the two headers follows MS-OXCMAIL.
3. If the name still contains a complete encoded-word, apply RFC 2047 once for compatibility
   with noncompliant senders; record this in the result.
4. If there is no name, assign an internal `part-<id>` and a separately determined extension;
   do not treat `Content-ID` or the message subject as a trusted path.
5. Store `originalFilename`, `decodedFilename` and `storedFilename` separately; save the file
   under an internal identifier, see В5.

[docs] Jakarta Mail enables RFC 2231 by default (`mail.mime.decodeparameters=true`).
`ParameterList` supports a lenient mode: it may retain the original value on a decoding error;
`mail.mime.decodeparameters.strict=true` enables strict mode. [11]

[docs] `mail.mime.decodefilename=true` enables `MimeUtility.decodeText` for filenames; the
documentation explicitly describes it as compatibility with clients that violate MIME.
`mail.mime.decodetext.strict=false` allows encoded words inside a word. These are global
System properties; do not change them for each message. [8]

[code] In the inspected Mime4j `ContentTypeFieldLenientImpl`, parameters enter a map as
name/value pairs. That class does not establish that every RFC 2231 name is already assembled
and decoded. Therefore, recommend an explicit `ParameterList` adapter for the parameter section
of the header, rather than assuming a ready `filename`. [11, 35]

[docs] [assumption] For Outlook/Exchange, `filename` takes priority over `name`, and long names
from MAPI/TNEF must be considered. Include nonstandard encoded words, unquoted spaces, broken
continuations and unknown charsets in the examples; their frequency in the user's Exchange
has not been checked. [8, 11, 20, 23, 34]

[docs] [code] Java 25 includes `KOI8-R` and `windows-1251`; Mime4j `DecoderUtil` can decode
RFC 2047 and accept a fallback charset and an override table. This repairs a known error;
it does not automatically recognize a language. [12, 36]

[assumption] For bodies, first decode `Content-Transfer-Encoding`, then decode the resulting
bytes using the `charset` of **that particular part**. Headers may use another charset. For a
missing/unknown charset, preserve bytes and `charsetWarning`; use a controlled fallback choice
of UTF-8 / windows-1251 / KOI8-R and record the method. [9, 12, 37]

[assumption] An incorrect but existing label such as `iso-8859-1` usually causes no decoder
error: the result can be unreadable. The inspected APIs do not guarantee correct automatic
repair. Allow automatic replacement only under a verifiable rule or connection setting;
keep the original result available. [8, 12, 36]

[assumption] The normalized result should contain the original header and parsed `subject`,
`from`, `to`, `cc`, `replyTo`, `messageId`, `inReplyTo`, `references`, and a date with time zone.
Use the standard address parser; leave an invalid date unknown and preserve repeated fields.
Do not treat `Message-ID` as a guarantee of local file uniqueness. [4, 38]

### В3. Body selection, HTML, quotes and signatures

[docs] `multipart/alternative` parts are alternative representations of the same content;
RFC 2046 recommends placing the richer representation last and selecting the last supported
one. Simply joining plain text and HTML duplicates text. [13]

[assumption] For MCP text responses, propose an explicitly documented policy: nonempty usable
`text/plain`, otherwise converted HTML. This deliberately prefers plain text over literally
following the richest-supported-alternative rule. If plain text is only a placeholder, allow
HTML under a bounded, verifiable heuristic. [13]

[assumption] In `multipart/mixed`, join consecutive body parts with separators, but exclude
attached file text. In `multipart/related`, honor the root part specified by `start`, if any,
and keep images as separate objects. An attached `message/rfc822` always gets its own headers
and body, rather than continuing its parent's body. [13, 39]

[docs] [code] Tika defaults to `extractAllAlternatives=false`, but its alternative handler has
its own selection logic and buffers. Its behavior cannot be treated as implementing the proposed
plain-text preference without separate verification. [5–7]

[docs] jsoup parses an HTML string into a DOM. `Element.text()` returns text; `href` is obtained
separately through `attr`, and the link address does not automatically appear in the text.
`HtmlToPlainText` is a simple formatting example that needs extending for real mail. [14–16]

[assumption] `HtmlBodyParser` should traverse the DOM and produce plain text:

- Paragraphs and `<br>` → line breaks; lists → bulleted lines; `<pre>` → preserved whitespace.
- Links → `label (URL)` if the URL differs from the label; text only, without following links.
- `<tr>` → a row; `<th>/<td>` → delimited cells; row/column limits.
- Exclude `script`, `style` and utility elements; do not fully emulate arbitrary CSS hiding:
  this is a text extractor, not a browser.
- Preserve original HTML separately; represent `cid:` as an internal-part link and `img alt`
  as bounded text. Do not fetch external URLs, CSS, fonts or images.

[assumption] This uses jsoup mechanisms [14–16], but preserving tables and links is our policy;
compare results with fallback Tika `JSoupParser` on synthetic examples. Tika's XHTML output
does not guarantee usable plain text. [40]

[code] `github/email_reply_parser` (Ruby, MIT) splits plain text into fragments from bottom to
top, marking quotes, signatures and hidden sections. The README warns about English separators;
the code already handles some multiline headers, so the README is not more accurate than the
code. [17]

[code] Mailgun `talon` (Python, Apache-2.0) handles plain/HTML quotes, simple signature extraction
and a machine learning variant. Useful files include `quotations.py`, `html_quotations.py` and
`signature.bruteforce` examples; full Russian coverage is unproven. [18]

[assumption] Do not add a Python/Ruby process for cleanup in the MVP. Ideas can be ported:
`>` and `blockquote` blocks, confirmed reply separators, the `-- ` signature line, and separate
rules for Russian `От:`, `Отправлено:`, `Кому:`, `Тема:`. Do not truncate a message at a lone
`С уважением` or any colon. [17, 18]

[assumption] Store `bodyTextFull` and optional `bodyTextReply`, with removed ranges and the rule
version described. Return full text by default; incorrect cleanup can remove a forwarded task
or a reply interleaved with quoted lines. [17, 18]

### В4. Mail attachment formats

[docs] Capabilities, libraries, licenses and limits for each case are collected in the first table under
Comparison tables. Important processing details follow. [19–28]

[code] [assumption] MSG is not MIME. HSMF provides `MAPIMessage` and `AttachmentChunks`, with
separate access to an attached MAPI message and ordinary attachment bytes. An adapter to the
same normalized model is needed; converting the entire MSG into an artificial EML is unnecessary.
For the body, use plain text, then HTML, then RTF through an extractor. [19, 21, 23]

[docs] [code] HMEF provides the subject, RTF body and attachments, including long names;
`getContents()` returns a byte array. The constructor reads attributes and accumulates objects,
so limiting only child-file writes is too late. [20, 22]

[assumption] Check TNEF by MIME type and signature, including `application/ms-tnef`; the name
`winmail.dat` alone is a hint, not proof. Send HMEF attachments through the shared pipeline;
convert the RTF body to text with the existing Tika, rather than returning RTF controls. [20, 22, 40]

[docs] [assumption] Read `text/calendar`/ICS as an attachment with `UID`, `METHOD`, `SUMMARY`,
`DESCRIPTION`, `LOCATION`, `DTSTART`, `DTEND`, `TZID`, attendees and organizer. Preserve `RRULE`
but do not expand recurrence; do not execute `VALARM` or download `ATTACH`. This extracts the
invitation text without accepting a meeting or accessing the calendar. [24, 25]

[docs] S/MIME distinguishes `multipart/signed` with a separate `application/pkcs7-signature`
(`.p7s`), opaque signatures `application/pkcs7-mime; smime-type=signed-data`, and encrypted
envelopes `smime-type=enveloped-data` (often `.p7m`). The extension alone cannot distinguish
them. [26]

[assumption] In the MVP, read the first `multipart/signed` part as ordinary MIME, but set
`signatureStatus=not_verified`; store `.p7s` as a signature, not a body. For opaque CMS, return
`opaque_signed` / `encrypted` / `unknown_cms` based on available evidence, the original, and the
reason text is unavailable. Do not report an "empty message". [26]

[docs] [assumption] For expanded support, Bouncy Castle provides CMS and S/MIME; there is a
separate S/MIME library variant for Jakarta Mail. Extracting signed-data requires no private
key; decryption requires the recipient's key. Signature verification and certificate trust
are separate operations. These are not recommended for the MVP. [26–28]

### В5. Limits and dangerous input

[assumption] The following are **initial proposed values**, not Exchange or library limits.
Tune them on anonymized examples; reflect exceeded limits in the result, without automatically
retrying parsing with limits disabled. Basis: [3, 29–31].

| Limit | Proposal | Where to check |
|---|---|---|
| Original EML / one container | 50 MiB / 25 MiB for MSG and TNEF | On receipt and before the parser constructor; count bytes actually read |
| Decoded part | 25 MiB | During Base64/QP decoding and writing, before `readAllBytes`/string construction |
| All derived bytes | 100 MiB per root message | Shared mutable counter across MIME, TNEF, MSG, ZIP and documents |
| MIME parts / headers | 512 parts; 200 fields per entity; 4096 total | `MimeConfig` + a shared counter when reentering the parser |
| Header length | 64 KiB per field/physical line; 1 MiB total | Before accumulating the header; corresponding `MimeConfig` settings |
| MIME depth | 32, root entity = 1 | Inside the MIME parser; distinct from file-container depth |
| EML/MSG/TNEF/ZIP depth | 5 transitions; root file = 0 | Before every recursive call, in a context shared by all types |
| Output objects / archive entries | 256 objects; 200 entries per ZIP | Count skipped parts and directories too, not just returned text |
| HTML / ICS | HTML ≤ 2 MiB and 100000 DOM nodes; ICS ≤ 1 MiB and 1000 components | Bytes before DOM/model construction; additionally count nodes/components during or after parsing |
| Result text | 200000 characters total, 20000 per part | While accumulating extracted text; MCP response limits apply separately |
| Time / memory | 30 s and 512 MiB per isolated processing process | A process terminated at the deadline; cooperative checks between parts |

[code] [assumption] Mime4j 0.8.15 exposes native settings for some of these [3]; count decoded
bytes, transitions between formats and results in the application. Local limits on each new
parser instance do not constitute a shared limit.

[assumption] Stop ZIP bombs based on bytes actually extracted and entry counts, including
nested OOXML/ZIP. Compression ratio is an additional signal, not a replacement for the shared
limit; do not trust declared `ZipEntry.getSize()` or the MIME `size` parameter. [29–31]

[assumption] For an incorrect/unclosed boundary, allow only bounded lenient parsing with a
warning; preserve the original and partial result if structure is lost. Do not search for
arbitrary boundaries with repeated passes without time/byte limits. [3, 8, 13]

[assumption] Count large inline images in total volume even without OCR. Do not decode pixels
in the MVP; bind CID to an internal ID within the message/related context, rather than treating
it as a globally unique path. Future OCR needs a separate pixel limit. [39]

[assumption] Do not use attachment names as relative paths: exclude `..`, absolute and UNC
paths, drive letters, Windows ADS (`:`), control characters, reserved names and case collisions.
Prefer writing by ID with `CREATE_NEW`, verifying that the normalized path stays inside the
owned directory; retain display names as metadata. [29, 31]

[docs] [assumption] POI and Tika warn about untrusted documents. An output limit does not
prevent internal allocations in HSMF/HMEF, DOM and PDF/Office parsers; timing out a Java thread
does not guarantee that parsing stops. A hard boundary needs a process with limited memory
and networking disabled. [30, 31]

[assumption] Preserve original bytes before normalization; write derived files atomically.
Distinguish `partial`, `limit_exceeded`, `unsupported`, `encrypted` and `parse_error`; report
the skipped part ID and reason. Exception text must not contain the entire body or headers.
This extends the sibling project's recoverable-response approach. [29]

### В6. Fit with the existing pipeline

[code] Inspected `C:\git\redmine-mcp-server` @
`f62b3e5d546d04533e4259a573293d949939a48d`; the working tree was unchanged. Java paths below
are relative to `src/main/java/<org>/redmine/mcp/`. [29]

[code] `extraction/ExtractionPipeline.java` runs all matching PRIMARY parsers, then FALLBACK;
`@Order` controls order, not selection of a single handler. This differs from "first canParse
wins" in `docs/retrieval-extraction-agent.md`. The actual interface uses `applies`, and
`ParseSink` lacks the documented `shouldStop()`. [29]

[code] `ParseSink.processNow` increments `depth` and passes the same `ParseContext`, but that
context currently contains only immutable limit values. The shared list bounds the total
number of emitted `ExtractedPart` objects; it does not count all parsed parts. [29]

[code] `ZipParser` checks depth but buffers entries in `ByteArrayOutputStream`; `totalBytes`
starts anew for each archive. `FileTypeDetector.isPlainText` treats every `text/*` and `.html`
as plain text; `PlainTextParser` reads the whole file as UTF-8. [29]

[assumption] Port the recursive handler contract and results with `producer`/`parent`, adding
stable `partId`, `parentPartId`, MIME path and CID. Call the shared mail normalizer for both
the root message and `EmlMessageParser`; do not route the root body through a generic document
fallback that lacks mail context. [29]

| Existing element | Required change in the future mail-mcp-server |
|---|---|
| `PlainTextParser` / `FileTypeDetector` | [assumption] Exclude HTML, calendar, RFC822 and other mail containers; separate MIME parameters from the type, carry charset in `ParseInput`; read with bounds. Basis: [29] |
| `ExtractionPipeline` / `ParseContext` | [assumption] Add shared counters and a stop reason, check depth before recursion, and use mutually exclusive container recognition rules. Basis: [29] |
| `ZipParser` | [assumption] Write a bounded stream into a separate container directory; track shared consumption and inspected entry counts; do not reset the limit for ZIP inside EML. Basis: [29] |
| `TikaTextFallbackParser` | [assumption] Exclude recognized mail containers even on empty text/error; pass a bounded `EmbeddedDocumentExtractor`, `MimeConfig` and shared context for allowed cases. Basis: [6, 7, 29] |
| `TikaMetadataParser` | [assumption] Do not run `AutoDetectParser` again for EML/MSG/TNEF/S/MIME; obtain metadata from the first parse. Currently `DefaultHandler` discards output, but does not prevent nested parser work. Basis: [29] |
| `ImagePassthroughParser` | [assumption] Add `contentId`, `inline`, a part link and size limits; return a short record instead of many identical images. Basis: [29, 39] |
| PDF/Office and `BinaryFallbackParser` | [assumption] Keep format logic; connect shared resource controls. For incomplete/encrypted messages, return a specific status instead of generic "no text". Basis: [29–31] |

## Comparison tables

### Formats and special cases

| Format / case | Library | Extracted content | Limits | License | Source |
|---|---|---|---|---|---|
| EML, attached `message/rfc822` | Mime4j core + DOM fields | [docs] [code] Headers, bodies, MIME parts; the application saves child files | [assumption] Recursion and normalized model are ours; В5 limits required | [code] Apache-2.0 | [1–3, 12] |
| EML as aggregate text | Tika `RFC822Parser` | [code] XHTML/metadata and embedded objects through a handler | [code] Alternative buffers; a custom handler is needed for the required parts catalog | [code] Apache-2.0 | [5–7] |
| `.msg`, `application/vnd.ms-outlook` | POI HSMF / `MAPIMessage` | [docs] [code] Header properties, plain/HTML/RTF, attachments and nested MSG | [docs] `poi-scratchpad`, weaker API compatibility guarantees than ordinary POI; not all MAPI fields are guaranteed | [docs] Apache-2.0 | [19, 21, 23, 41] |
| `winmail.dat`, `application/ms-tnef`, also x-tnef | POI HMEF / `HMEFMessage` | [docs] [code] Subject, RTF, attachment names and bytes, MAPI/TNEF attributes | [docs] [code] `poi-scratchpad`; byte arrays; incomplete attribute support, requiring a limit before model construction | [docs] Apache-2.0 | [20, 22, 41] |
| TNEF: another candidate | Freeutils JTNEF | [docs] TNEF reading and attachment extraction | [assumption] No MVP advantage over the already used POI identified; current compatibility unverified | [docs] GPL; the author offers separate commercial licensing | [42] |
| Inline `cid:` / `multipart/related` | Mime4j + jsoup | [docs] [assumption] Part bytes, CID and HTML links; the application builds associations | [assumption] No OCR or remote image downloads; CID is scoped to the message | [code] [docs] Apache-2.0 + MIT | [1, 14, 39, 43] |
| HTML-only | jsoup; fallback Tika `JSoupParser` | [docs] [assumption] Text and link attributes; readable tables are a custom rule | [docs] [assumption] DOM needs memory; CSS layout is not reproduced | [docs] [code] MIT / Apache-2.0 | [14–16, 40, 43] |
| `text/calendar`, `.ics` | iCal4j `CalendarBuilder` | [docs] Component/property model; invitation fields listed in В4 | [assumption] Read-only; no external zone/URI updates or recurrence expansion; model limit | [code] BSD-3-Clause | [24, 25, 44] |
| S/MIME `.p7m`, `.p7s`, `multipart/signed` | MIME parser; Bouncy Castle for expanded support | [docs] [assumption] MVP: envelope type and clear part of detached signatures; CMS later | [docs] [assumption] Ciphertext needs a key; extraction does not imply signature verification | [code] Mime4j Apache-2.0; Bouncy Castle MIT | [26–28] |
| Quotes / signature | `email_reply_parser`, `talon` as idea sources | [code] Reply fragments, quotes, signatures | [assumption] Not a ready Java dependency; Russian accuracy unconfirmed, possible text loss | [code] MIT / Apache-2.0 | [17, 18] |

### New parsers and their order

[assumption] All rows recommend **new MVP components**; numbers are proposed `@Order` values,
not changes to the sibling project. Limit groups: M — MIME/headers, C — containers, H — HTML,
I — ICS; values and shared limits are in В5. Exclude each mail container from the old
text/metadata/fallback handlers.

| New parser | MIME type / indicator | Library | Parser ordering | Limits |
|---|---|---|---|---|
| `SmimeEnvelopeParser` | `application/(x-)pkcs7-mime`, `(x-)pkcs7-signature`, `.p7m/.p7s` as hints | MIME headers; CMS later through BC | PRIMARY 40, before containers and Tika; MIME traversal also recognizes `multipart/signed` | M/C; no opaque CMS decryption/unpacking in MVP [26–28] |
| `EmlMessageParser` | `message/rfc822`, `.eml` with header confirmation | Mime4j core, typed DOM fields, `ParameterList` | PRIMARY 50, before plain 100; same normalizer as the root message | M/C, streaming writes, shared part/byte limit [1–3, 11] |
| `MsgMessageParser` | `application/vnd.ms-outlook`, `.msg` + OLE/MAPI structure | POI HSMF, `poi-scratchpad` | PRIMARY 60, before plain and Tika; OLE signature without MAPI is insufficient | C, ≤25 MiB before model construction, nested MSG counts toward depth [19, 23] |
| `TnefParser` | `application/ms-tnef`, `application/vnd.ms-tnef`, `application/x-tnef`; TNEF signature | POI HMEF, `poi-scratchpad` | PRIMARY 70, before Tika; `winmail.dat` name is only a hint | C, ≤25 MiB before model construction, shared derived-file consumption [20, 22, 40] |
| `CalendarInviteParser` | `text/calendar`, `.ics` + `BEGIN:VCALENDAR` | iCal4j | PRIMARY 80, before plain; excluded from `PlainTextParser` | I/C, no recurrence, alarms or external access [24, 25, 44] |
| `HtmlBodyParser` | `text/html`, `application/xhtml+xml`, `.html/.htm` | jsoup | PRIMARY 90, before plain; also used for MIME body selection | H, bounded output and links without fetching [14–16] |

## Recommendation

[assumption] Choose **Mime4j 0.8.15 with `MimeStreamParser`** as the EML normalization foundation.
Use core for streaming and the DOM module only for field parsing; use `ParameterList` for
RFC 2231 without building a second `MimeMessage` tree. Benefits: part and resource control;
cost: custom body selection, file storage and diagnostic rules. This is a recommendation;
the user decides. [1–3, 11]

[assumption] Following Tika, enable `setContentDecoding(true)` and `setNoRecurse()`:
the MIME parser traverses multipart, while an attached `message/rfc822` is saved as a separate
file and passed to the shared handler with increased depth and preserved shared counters.
This provides one control point for EML → other container transitions. [6, 29]

[assumption] Add the six components in the second table for the MVP; `SmimeEnvelopeParser`
only recognizes envelopes. Use POI HSMF/HMEF, jsoup and iCal4j; retain existing document
extractors. First fix type overlaps, shared limit context and repeated Tika parsing, otherwise
new `@Order` values will not ensure the required behavior. [29]

[assumption] Before implementation, establish synthetic examples: RFC 2231 continuations,
encoded-word filenames, KOI8-R/1251 and wrong charset labels, plain+HTML, HTML-only, CID,
EML→MSG→ZIP, TNEF, ICS with TZID, signed/encrypted S/MIME and damaged boundaries. Criteria:
one selected body text, separately saved attachments, no external requests and an explicit
stop reason for every limit. No examples were run in this session.

## What could not be verified

- Real Exchange/Gmail/Mail.ru messages and their filename/charset quirks: no mailbox access.
  The user can separately provide anonymized EML; no sign-in required.
- Charset repair accuracy, table formatting and Russian quote removal: no corpus tests.
  Check synthetic and anonymized messages before enabling heuristics.
- Complete RFC 2231 support across Mime4j 0.8.15 paths: inspecting one lenient class does not
  establish it. Until verified, recommend explicit `ParameterList`, see В2. [11, 35]
- HSMF/HMEF behavior across all MAPI variants and unknown properties: documentation and code
  confirm APIs, not format completeness. Separate MSG/TNEF examples are needed. [19, 20, 23]
- Java 25 compatibility of the complete chosen combination and transitive version alignment:
  the brief prohibits building and execution. Inspect dependencies, then verify separately.
- Tika source here is pinned to 3.2.3; the sibling project uses Tika 3.2.0 and POI 5.4.0.
  These are not recommended current dependency versions. [29]
- Proposed 50/100 MiB, depth and time values are unmeasured; the user must approve values after
  testing. Public keys, private keys and S/MIME trust verification were not used in this session.

## Further research

- [assumption] Compare EWS item attachment results with saved MIME: if conversion is incomplete,
  consider a separate source download of the attached object.
- [assumption] Choosing a separate local process for document parser isolation and managing
  its files/memory while retaining stdio MCP as the only transport. [30]
- [docs] For future S/MIME, consider RFC 9788 on protecting inner headers; external headers
  do not always match protected ones. [45]

## Sources

All web sources were read on 2026-10-24. Publication dates of undated pages are unknown;
RFC dates below are specification dates, not evidence of implementation freshness.

1. [Apache James Mime4j: overview](https://james.apache.org/mime4j/) — site published 2017-06-15; historical streaming/DOM/storage description, not the current release number.
2. [Apache James MIME4J 0.8.15](https://james.apache.org/james/update/2026/09/03/mime4j-0.8.15.html) — announcement of 2026-09-03, new configurable limits.
3. [MimeConfig.java, tag apache-mime4j-project-0.8.15](https://raw.githubusercontent.com/apache/james-mime4j/apache-mime4j-project-0.8.15/core/src/main/java/org/apache/james/mime4j/stream/MimeConfig.java) — limit fields, Builder and comments checked.
4. [Jakarta Mail MimeMessage](https://eclipse-ee4j.github.io/angus-mail/docs/api/jakarta.mail/jakarta/mail/internet/MimeMessage.html) — constructors, `content`, `contentStream`, headers and `SharedInputStream`; undated.
5. [Tika 3.2.3 RFC822Parser API](https://tika.incubator.apache.org/3.2.3/api/org/apache/tika/parser/mail/RFC822Parser) — `ParseContext`, `extractAllAlternatives`; release date not established here.
6. [Tika RFC822Parser.java, tag 3.2.3](https://raw.githubusercontent.com/apache/tika/3.2.3/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-mail-module/src/main/java/org/apache/tika/parser/mail/RFC822Parser.java) — Mime4j and decoding configuration, Apache-2.0.
7. [Tika MailContentHandler.java, tag 3.2.3](https://raw.githubusercontent.com/apache/tika/3.2.3/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-mail-module/src/main/java/org/apache/tika/parser/mail/MailContentHandler.java) — alternative buffers, attachments, typed fields.
8. [Jakarta Mail internet package: MIME properties](https://eclipse-ee4j.github.io/angus-mail/docs/api/jakarta.mail/jakarta/mail/internet/package-summary.html) — filename decoding, compatibility and multipart boundaries; undated.
9. [RFC 2047](https://www.rfc-editor.org/rfc/rfc2047) — MIME Header Extensions, 1996-11.
10. [RFC 2231](https://www.rfc-editor.org/rfc/rfc2231) — parameters, charsets and continuations, 1997-11.
11. [Jakarta Mail ParameterList](https://eclipse-ee4j.github.io/angus-mail/docs/api/jakarta.mail/jakarta/mail/internet/ParameterList.html) — RFC 2231 and compatibility modes; undated.
12. [Mime4j DecoderUtil.java](https://raw.githubusercontent.com/apache/james-mime4j/master/core/src/main/java/org/apache/james/mime4j/codec/DecoderUtil.java) — RFC 2047, fallback charset and overrides; master inspected, not a promise that all APIs exist in older versions.
13. [RFC 2046](https://www.rfc-editor.org/rfc/rfc2046) — multipart, alternatives and message/rfc822, 1996-11.
14. [jsoup: Parse a document from a String](https://jsoup.org/cookbook/input/parse-document-from-string) — 2010-01-17; local HTML parsing.
15. [jsoup: Extract attributes, text, and HTML](https://jsoup.org/cookbook/extracting-data/attributes-text-html) — 2010-02-09; distinction between text and href.
16. [jsoup HtmlToPlainText](https://jsoup.org/apidocs/org/jsoup/examples/HtmlToPlainText) — example and formatting limits; undated.
17. [github/email_reply_parser](https://github.com/github/email_reply_parser) and [lib/email_reply_parser.rb](https://raw.githubusercontent.com/github/email_reply_parser/master/lib/email_reply_parser.rb) — README, MIT, fragment algorithm; master inspected, release date not established.
18. [Mailgun talon](https://github.com/mailgun/talon) and [talon/quotations.py](https://raw.githubusercontent.com/mailgun/talon/master/talon/quotations.py) — README, Apache-2.0, plain/HTML and heuristics; master inspected, release date not established.
19. [Apache POI HSMF](https://poi.apache.org/components/hsmf/index.html) — MSG, scratchpad and compatibility limits; page undated.
20. [Apache POI HMEF](https://poi.apache.org/components/hmef/index.html) — TNEF, body, names and attachments; page undated.
21. [POI MAPIMessage API 5.0](https://poi.apache.org/apidocs/5.0/org/apache/poi/hsmf/MAPIMessage.html) — plain/HTML/RTF body APIs; an old reference, not a version recommendation.
22. [POI HMEFMessage.java](https://raw.githubusercontent.com/apache/poi/trunk/poi-scratchpad/src/main/java/org/apache/poi/hmef/HMEFMessage.java) — TNEF signature, attribute reading and accumulation; trunk inspected.
23. [POI AttachmentChunks.java](https://raw.githubusercontent.com/apache/poi/trunk/poi-scratchpad/src/main/java/org/apache/poi/hsmf/datatypes/AttachmentChunks.java) — nested MSG, data, names and Content-ID; trunk inspected.
24. [iCal4j Parser](https://www.ical4j.org/parser/) — `CalendarParser`, handlers, `CalendarBuilder`; undated.
25. [RFC 5545](https://www.rfc-editor.org/rfc/rfc5545) — iCalendar, properties and types, 2009-09.
26. [RFC 8551](https://www.rfc-editor.org/rfc/rfc8551) — S/MIME 4.0, signatures and encryption, 2019-04; updated by RFC 9788.
27. [Bouncy Castle Java documentation](https://www.bouncycastle.org/documentation/documentation-java/) — CMS/S/MIME and a separate Jakarta Mail library; page undated.
28. [Bouncy Castle LICENSE.html](https://raw.githubusercontent.com/bcgit/bc-java/main/LICENSE.html) — MIT, copyright 2000–2026.
29. Local primary source: `C:\git\redmine-mcp-server` @ `f62b3e5d546d04533e4259a573293d949939a48d`, read on 2026-10-24. Inspected `AGENTS.md`, `docs/architecture-agent.md`, `docs/retrieval-extraction-agent.md`, `gradle/libs.versions.toml`; under `src/main/java/<org>/redmine/mcp/extraction/`: `ExtractionPipeline.java`, `ParseSink.java`, `ParseContext.java`, `ExtractionLimits.java`, `FileTypeDetector.java`, `parser/PlainTextParser.java`, `parser/ZipParser.java`, `parser/TikaTextFallbackParser.java`, `parser/TikaMetadataParser.java`, `parser/ImagePassthroughParser.java`, `parser/BinaryFallbackParser.java`; ordering annotations of the other parsers. GitHub URL deliberately omitted.
30. [Apache Tika Security Model](https://tika.apache.org/security-model.html) — untrusted documents, protection boundaries, process isolation; covers several branches, 4.x APIs do not automatically apply to 3.x.
31. [Apache POI Security guidance](https://poi.apache.org/security.html) — safe processing of untrusted documents; page undated.
32. [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) — Jakarta Mail implementation, stable 2.0.5; news section lags, ending at 2.0.3 of 2024-03-04.
33. [Angus Mail License](https://eclipse-ee4j.github.io/angus-mail/AngusMail-License) — EPL/GPL with Classpath Exception and EDL for some parts; undated.
34. [Microsoft MS-OXCMAIL 2.2.3.4.1.1 File Name](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxcmail/55f34e65-1955-4758-8f3a-ab1c64d74360) — Content-Disposition/Content-Type filename priority; publication date unknown in the accessible view.
35. [Mime4j ContentTypeFieldLenientImpl.java](https://github.com/apache/james-mime4j/blob/master/dom/src/main/java/org/apache/james/mime4j/field/ContentTypeFieldLenientImpl.java) — master inspected: `parse` and parameter map; not a check of every RFC 2231 decoder.
36. [Java SE 25 Supported Encodings](https://docs.oracle.com/en/java/javase/25/intl/supported-encodings.html) — KOI8-R, windows-1251 and explicit charset selection.
37. [RFC 2045](https://www.rfc-editor.org/rfc/rfc2045) — MIME Content-Type, charset and Content-Transfer-Encoding, 1996-11.
38. [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) — Internet Message Format, headers and dates, 2008-10.
39. [RFC 2392](https://www.rfc-editor.org/rfc/rfc2392) — Content-ID URLs, 1998-08; [RFC 2387](https://www.rfc-editor.org/rfc/rfc2387) — multipart/related, 1998-08.
40. [Tika 3.2.3 Supported Formats](https://tika.apache.org/3.2.3/formats.html) — jsoup for HTML, RTF and mail formats; pinned reference version.
41. [Apache POI Legal Stuff](https://poi.apache.org/legal.html) — Apache-2.0 license.
42. [Freeutils JTNEF](https://www.freeutils.net/source/jtnef/index.jsp) — capabilities and GPL / commercial licensing; page undated.
43. [jsoup License](https://jsoup.org/license) — MIT, copyright 2009–2026.
44. [iCal4j repository](https://github.com/ical4j/ical4j) — BSD-3-Clause, `net.fortuna.ical4j.timezone.update.enabled` configuration; [Configuration](https://www.ical4j.org/configuring/) — relaxed/outlook hints; release date not established.
45. [RFC 9788](https://www.rfc-editor.org/rfc/rfc9788) — Header Protection for Cryptographically Protected Email; publication date not established in this session.

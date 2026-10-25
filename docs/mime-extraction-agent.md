# Offline MIME extraction

Read this when changing `extraction/`, MIME normalization, body selection, attachment persistence,
or the shared budget. Design: `plan.md` 5.1 and 6, research: `research/RT-09-mime-attachments.md`.

## Entry point and ownership

`extraction/mime/EmlParser.parse(InputStream, Path, ExtractionContext)` is an internal Java API.
It has no Spring bean, command, MCP tool, mailbox configuration or source dependency. Input is
caller-owned and remains open. The existing output root is caller-controlled; each invocation
creates a fresh `mime-<UUID>` directory. The caller owns successful and partial result directories
and must eventually remove them. Ordinary I/O failures propagate and roll back only that newly
created directory; an unfinished leaf file is always removed.

Mime4j 0.8.15 `MimeStreamParser` decodes transfer encoding while visiting MIME parts. Its DOM
module parses header fields only; no DOM message or Jakarta `MimeMessage` is constructed.
Jakarta `ParameterList` handles RFC 2231; no global mail properties are changed. jsoup 1.23.2
parses bounded HTML strings locally, without URL connections or browser execution.

`ParsedMail` and its nested records are internal **unredacted** data, not the MCP wire contract.
Neither their text nor their original paths may be exposed through a future tool before the
redaction layer exists. This is not a metadata database or the final message-directory layout.

## Result and current choices

- Headers retain every occurrence, its original field body and unfolded/decoded value. Subject,
  From/To/Cc/Reply-To, date with offset, Message-ID, In-Reply-To and References are normalized.
  Address groups flatten to mailboxes; original fields retain group syntax. Invalid dates stay
  null. Repeated fields produce a warning; repeated recipients are retained. Raw EML ownership
  stays with the caller, including envelope bytes and any malformed lines Mime4j cannot parse.
- Parts retain MIME type, disposition, original headers, display filename, Content-ID, charset,
  decoded byte count, SHA-256, storage path, format and status. The metadata tree preserves
  related/CID scope; there is no global CID filename or map. Multipart nodes have no byte hash
  or storage file: only decoded leaves are persisted.
- Every decoded leaf, including inline images and unselected body alternatives, is stored with
  an internally generated `<UUID>.bin` name. Names from Content-Disposition/Content-Type are
  metadata only. Files use `CREATE_NEW`, `NOFOLLOW_LINKS` and a parent-path check in the fresh
  directory. No filename sanitization can redirect output, overwrite another file or collide by
  case. This assumes a caller-controlled root, not resistance to a hostile process with the same
  filesystem permissions racing directory replacement.
- Disposition filename takes precedence over type name; conflicting values and encoded words
  in parameters produce diagnostics. Unsupported parameter syntax remains in the original header;
  a decoded filename may be null. Internal filenames and warning codes in RT-11 are proposals,
  not the production contract.
- Exactly one body is returned for each message. For `multipart/alternative`, choose a nonblank
  direct `text/plain` child first, otherwise the first supported nonblank alternative. Related
  chooses `start`, or the first child if absent; missing `start` warns and falls back to the first.
  Signed chooses its first child. Mixed/other multiparts choose the first eligible body subtree.
  Attachments, named parts and embedded-message bodies never become parent body text. Other
  representations remain on disk. No placeholder-body heuristic or concatenation of mixed text
  is implemented; these choices are visible implementation defaults, not new user decisions.
- Text uses its declared charset. Missing/unsupported charset uses US-ASCII with diagnostics;
  undecodable bytes become replacement characters. A Latin-1 body with many high characters
  warns of a suspected mismatch but is **not** converted to CP1251. Original bytes remain available.
  No charset guessing, quote stripping or signature stripping occurs. Invalid per-word encoded
  header bytes warn even when Mime4j successfully joins adjacent encoded words.
- HTML retains paragraph/line/list/pre/table structure, image alt text and HTTP(S)/mailto/CID
  link targets as text. Scripts, styles, templates, hidden attributes and explicit inline
  `display:none`/`visibility:hidden` are excluded. Arbitrary CSS visibility is not simulated.
  No remote resource, CSS, image, calendar attachment or link is fetched.

## Containers and status

`message/rfc822` is saved before re-entry through the same parser and `context.child()`. Its
`nestedMessage` has independent headers/body/part paths (numbering restarts inside that message).
Root nonmultipart path is `1`; multipart root path is empty and children use dotted sections.
These are local structural identifiers, not promised server IMAP sections.

MSG, TNEF, ZIP and calendar MIME types are saved with `UNSUPPORTED`, an `unexpanded-*` diagnostic,
and no claimed extracted children/text. Classification is by MIME type, not verified binary
signature or filename extension. Other binary formats are preserved as opaque leaf files, with
no document extraction attempted. Attached/named multipart entities explicitly report unsupported
container preservation: their leaves survive, but no serialized multipart attachment is produced.

S/MIME is classified from headers only. Detached signed content is readable, with
`signature-not-verified`; this never establishes validity or trust. Opaque signed/unknown CMS
is unsupported, and enveloped CMS is encrypted. No keys, decryption or CMS verification are used.

Top-level status is `OK`, `PARTIAL` (including a readable body with unsupported attachments),
`UNSUPPORTED` (no body and unexpanded format), `ENCRYPTED`, `LIMIT_EXCEEDED`, or `PARSE_ERROR`.
Nested limits propagate to the parent. Read available diagnostics recursively in nested messages.
Lenient parsing retains completed parts. Decoder/structure warnings mark the message partial;
a leaf warned about during decoding is discarded rather than presented as intact. Diagnostic
codes carry only a part path, never library exception messages or sender data. I/O errors are
exceptions, not parse diagnostics.

## Shared limits

`ExtractionContext` is thread-confined and shared across the entire future container chain.
Create it once per root input; call `child()` before entering EML/MSG/TNEF/ZIP, never create a new
root context on recursion. Charge `part`, `header`, `file`, `derivedBytes`, and `textChars` before
retaining the corresponding resource. A child shares counters and deadline, with depth + 1.
Usage is cumulative, including discarded work; no refund or automatic retry disables limits.

`ExtractionLimits.defaults()` supplies initial, caller-replaceable values from RT-09 suggestions:
50 MiB input per EML, 25 MiB decoded leaf, 100 MiB total derived bytes, 512 MIME entities,
200 headers/entity, 4096 headers and 1 MiB header bytes across re-entry; 64 KiB field/physical line,
MIME depth 32 (root = 1), container depth 5 (root = 0), 256 output files, 200000 total returned
text characters, HTML input 2 MiB and 100000 DOM nodes; cooperative 30-second deadline.
An expanded EML's bytes and its decoded children both count as derived bytes. MIME-local native
guards supplement application counters across re-entry. Input byte guard covers preamble/epilogue
as well; Mime4j's per-body content guard is disabled because application byte guards cover this
without conflating input and decoded sizes. A physical-line guard also bounds body lines.

These values are not finalized mailbox policy. Time checks cannot interrupt a blocked stream or
library call, and the HTML node check runs after DOM construction. The isolated worker process
with hard memory/time bounds from plan.md П10 is **not implemented** in this first offline step.
It remains required before production processing of arbitrary documents. Future extractors must
apply format-specific limits before creating their library object models.

## Tests and expectation differences

`EmlParserFixtureTest` reads all 20 `testdata/mime/manifest.json` cases and paired expectations:
headers, selected body, MIME leaf metadata, decoded hashes/bytes and nested EML structure. It
stops expectations at unsupported formats. `EmlParserTest` covers each budget, shared re-entry,
depth stop, expired context, rollback, internal names, related start, attachment exclusion,
HTML, thread headers, invalid dates and unknown charsets. All inputs are synthetic and offline.

The original RT-11 files are unchanged. Explicit deviations:

| Case | Handling in this step |
|---|---|
| M05 | Honor ISO-8859-1; warn, retain bytes; do not assert generated CP1251 ground truth as output. |
| M09/M19 | Original nested EML size/hash expectations describe Python reserialization, which adds CRLF. Supplemental `src/test/resources/mime/wire-expectations.json` describes actual bytes between MIME delimiters; no reserialization changes stored bytes. |
| M09/M10/M11 | Nested MSG, TNEF and ICS remain saved/unsupported; no ZIP descendants or calendar fields are claimed. |
| M14 | Mime4j recovers only `Hel`, versus the Python baseline `Hello`. Discard the damaged attachment, retain the first body, report partial and diagnostics, as allowed by the fixture README. |
| M16 | RFC 2231 unquoted `/etc/passwd` is rejected by ParameterList; raw header retained, display name null, partial with parameter diagnostic. All storage names are UUIDs; proposed `part-N.txt` and case-collision warnings are not adopted. |
| M18 | Mime4j joins split words into the expected subject; emit `header-encoded-word-split` anyway. |
| M19 | Stop before container depth 6; retain the unopened EML bytes and a limit diagnostic. |

Supplemental byte expectations were independently obtained by splitting the synthetic wire data
at each declared MIME boundary, excluding the CRLF introducing the next delimiter (RFC 2046).
Python's SMTP serialization reproduces the original expected values instead; M09 has 6276 wire
bytes vs 6278 reserialized, M19 first nested message 3381 vs 3391.

Dependency APIs were checked against their resolved 0.8.15 sources and official documentation:
[Mime4j MimeConfig](https://raw.githubusercontent.com/apache/james-mime4j/apache-mime4j-project-0.8.15/core/src/main/java/org/apache/james/mime4j/stream/MimeConfig.java),
[jsoup local string parsing](https://jsoup.org/cookbook/input/parse-document-from-string),
[jsoup releases](https://jsoup.org/news/), checked 2026-10-25.

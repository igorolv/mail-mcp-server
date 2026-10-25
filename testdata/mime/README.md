# Synthetic MIME parser fixtures

Twenty invented messages, M01-M20, including real nested MSG/ZIP, TNEF and CMS structures.
No mailbox or mail client was used. The parser and extractor are not implemented by this data task.
EML uses CRLF. JSON/Markdown use UTF-8 without BOM and LF. Each EML is smaller than 64 KiB.

## Layout and fields

Each EML has a paired expected JSON. manifest.json lists all cases and all files, including itself;
its own hash is omitted. Case fields: id, file, expected, title, covers, source, sha256, generated.
M09 containerSteps records generated status for EML, MSG, ZIP and TXT.
Expected fields: id, status, headers, body, parts, warnings, notes, plus case-specific fields.
Headers: decoded subject, from/to objects (name/address), date or null, messageId; M20 also rawTo.
Body: selectedPart or null, contentType, charsetDeclared/Actual, textContains and textNotContains.
Parts: path, contentType, disposition, filenameRaw/Decoded, contentId, decoded size/SHA-256, status,
children. Multipart container size/hash uses its serialized MIME entity; other parts use decoded bytes.
For message/rfc822 the decoded bytes are the embedded message. Synthetic round trips preserve them.
storedFilename in unsafe/unnamed cases is a proposed internal name, never a sender-controlled path.
children of ZIP, MSG and TNEF extend dotted paths logically; these extensions are not server IMAP sections.
For ordinary MIME, paths use IMAP section numbering and the root nonmultipart body is 1.
Warnings and internal stored names are proposals; no production API contract is chosen here.
RemoteResources lists URLs that must not be fetched; cidMap maps inline references to part paths.
Calendar: method, uid, summary, dtstart value/tzid, rrule text, organizer, attendees.
Smime: kind (detached_signed/encrypted/opaque_signed), cmsValid, signatureStatus where relevant.
Limits: maxContainerDepth/rootDepth; stoppedAtPart names the first unexpanded part.
Statuses: ok, partial, encrypted, unsupported, limit_exceeded, parse_error.

## Proposed warning codes

filename-encoded-word-nonstandard: RFC 2047 used inside a parameter (compatibility case).
filename-conflict: disposition filename takes priority over content-type name.
charset-mismatch-suspected / charset-missing: wrong/absent declaration; keep original bytes.
boundary-not-closed / base64-truncated: bounded lenient parsing returns partial data.
unsafe-filename / case-collision: use internal names and preserve display names separately.
filename-missing: derive internal part name and extension from content type.
header-encoded-word-split: invalid split of UTF-8 across encoded words; warning is required.
container-depth-limit: stop before entering depth 6 with root depth 0.
date-invalid / header-repeated: keep headers; do not invent a valid date.

## Policy limits

M05 charsetActual records generated ground truth, not a decision to auto-correct Latin-1 declarations.
M18 subject records combined-byte ground truth; retaining a decoding defect with warning is acceptable.
M06 selects plain text once, while HTML stays available as another representation.
M07/M08 textContains refers to visible text; scripts/styles and remote fetches are excluded.
M12 signatureStatus remains not_verified in the future parser, even though fixture generation verified CMS.
M13 must report encrypted; M15 opaque_signed is intentionally unsupported by the MVP.
M19 lowers the container limit instead of using a large payload; MIME and container depths are distinct.
M14 decoded truncated Base64 bytes are the bounded Python recovery baseline; dropping the damaged
attachment is also acceptable if partial status and warnings remain explicit.

## Generation and independent checks

Python stdlib: manual MIME, email parsing, base64, quopri, struct, zlib and zipfile.
PNG has valid CRCs and a zlib-compressed 1x1 RGB scanline. ZIP holds one small text file.
POI 5.4.1 from the existing local cache creates CFB/MSG and reads it back with HSMF.
TNEF is hand-built with attribute checksums, MAPI properties, Unicode filename and MELA RTF;
POI HMEF 5.4.1 independently checks RTF, filename and attachment bytes.
OpenSSL 3.0.3 produces and verifies detached/opaque CMS and encrypts/decrypts enveloped CMS.
Only resulting MIME enters this set; the throwaway key and certificate remain in ignored scratch.
Helpers stay in testdata/.work/RT-11/. No fixture dependency was installed or downloaded.
Python checks selected text, decoded sizes/hashes, limits and CRLF. Production Mime4j behavior is unmeasured.

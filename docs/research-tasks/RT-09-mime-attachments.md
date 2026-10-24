# RT-09 — MIME parsing and mail attachments in Java

- **Result file:** `docs/research/RT-09-mime-attachments.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 2; `docs/plan.md`, section 5.1.
- **Needs:** web; read access to `C:\git\redmine-mcp-server` for В6 (if unavailable, answer В6
  from the list below).

## Goal

Decide how to turn a raw message (`.eml`, delivered by both EWS and IMAP) into normalized
headers, body text and attachments, and which new parsers the attachment pipeline needs for
mail-specific formats.

## What we already know

- Every source delivers MIME (`docs/plan.md`, 5.7).
- `redmine-mcp-server` has an extraction pipeline: `PlainTextParser`, `PdfTextParser` (PDFBox),
  `DocxPandocParser` / `DocxTextParser` (pandoc / POI), `XlsxTextParser`, `PptxTextParser`,
  `ZipParser` (depth-bounded), `ImagePassthroughParser`, `TikaTextFallbackParser`,
  `TikaMetadataParser`, `BinaryFallbackParser`, with limits on bytes, parts and depth.
- Mail-specific cases we expect: attached messages (`message/rfc822`, `.eml`, `.msg`),
  `winmail.dat` (TNEF), inline images (`cid:`), HTML-only bodies, old Russian charsets.

## Questions

- **В1. MIME parsers:** Eclipse Angus Mail (`MimeMessage`), Apache James Mime4j (DOM and
  streaming APIs), Apache Tika `RFC822Parser`. Compare: streaming vs in-memory, robustness to
  broken messages, header decoding, licence, maintenance.
- **В2. Headers and names:** decoding RFC 2047 encoded words and RFC 2231 file names; known
  problems with Outlook / Exchange-generated names; Russian charsets (`koi8-r`, `windows-1251`)
  and mislabelled charsets — how libraries cope.
- **В3. Body text:** choosing between `text/plain` and `text/html` in `multipart/alternative`;
  HTML to text (jsoup, Tika, others) keeping links and tables readable; stripping quoted replies
  and signatures — libraries or heuristics that exist (any language, as idea sources).
- **В4. Mail-specific attachments:** `.msg` (Apache POI HSMF), `winmail.dat` / TNEF (Apache POI
  HMEF, other libraries), nested `message/rfc822`, calendar invitations (`text/calendar`, `.ics`),
  S/MIME (`smime.p7m`, `.p7s`) — at least detection, `application/ms-tnef`. For each: library,
  what it extracts, limitations.
- **В5. Safety limits:** zip bombs, deeply nested messages, huge inline images, malformed MIME
  boundaries — what limits to apply and where.
- **В6. Pipeline fit:** which new parsers to add to the existing pipeline (name, content type,
  library, position in parser order), and which existing parsers need changes for mail.

## Where to look

- Eclipse Angus Mail docs; Apache James Mime4j site; Apache Tika supported formats.
- Apache POI HSMF and HMEF documentation.
- jsoup documentation.
- RFC 2045–2049, RFC 2047, RFC 2231, RFC 5322.
- For quote/signature stripping: open-source projects such as `email-reply-parser`
  (GitHub) or `talon` (Mailgun) — as idea sources, mark `[код]`.

## Required tables

| Формат / случай | Библиотека | Что извлекается | Ограничения | Лицензия | Источник |
|---|---|---|---|---|---|

| Новый парсер | MIME-тип / признак | Библиотека | Место в порядке парсеров | Лимиты |
|---|---|---|---|---|

## Out of scope

- Writing or running code; changing `redmine-mcp-server`.
- Search indexing (RT-06) and secret detection (RT-08).

## Done when

- В1–В6 answered; both tables filled.
- The recommendation names the MIME parser to use and the list of new parsers for the MVP.

# RT-11 — Synthetic `.eml` messages for the parsing pipeline

- **Kind:** data task (see README, "Data tasks").
- **Data directory:** `testdata/mime/`.
- **Report file:** `docs/research/RT-11-synthetic-mime.md`.
- **Size:** L — may need two sessions (see "If you run out of time").
- **Fills:** `docs/plan.md`, section 9 (synthetic `.eml` set); input for decision П10 and the
  offline pipeline of stage 1 (`docs/plan.md`, section 7).
- **Needs:** tools already installed on the machine; web only to re-read RFCs and format specs.

## Goal

Create a small, fully synthetic set of mail messages that covers the hard MIME cases listed in
`docs/research/RT-09-mime-attachments.md` ("Рекомендация"), and, for every message, a
machine-readable description of what a correct parser must produce. Future tests of the
Mime4j-based normalizer and the six new parsers (decision П10) will run against this set
without network access and without real mail.

## What we already know

- Parser choice and pipeline shape: `docs/research/RT-09-mime-attachments.md` — В2 (header
  and file name decoding, charset rules), В4 (MSG, TNEF, ICS, S/MIME), В5 (limits, statuses,
  unsafe names), the table "Новые парсеры и их порядок". Read these sections first.
- Decision П10 (`docs/plan.md`, section 10): Mime4j streaming, six new parsers, one text per
  file, shared depth and size limits for the whole nesting chain.
- Statuses proposed in RT-09 В5: `ok` (added here for the normal case), `partial`,
  `limit_exceeded`, `unsupported`, `encrypted`, `parse_error`. S/MIME kinds from RT-09 В4:
  `signatureStatus: not_verified`, `opaque_signed`, `encrypted`, `unknown_cms`.
- Git on this machine converts line endings by default; `testdata/.gitattributes` switches that
  off for everything under `testdata/`, so bytes you write are stored as written.

## Required cases

Each row is one message file. IDs are fixed so that other documents can refer to them. Cases
M01–M14 are required; M15–M20 are wanted if time allows.

| ID | Slug | What the message must contain | What it tests |
|---|---|---|---|
| M01 | `rfc2231-continuations` | Attachment whose Russian file name is split into `filename*0*`, `filename*1*`, `filename*2*` (UTF-8, percent-encoded) | RFC 2231 assembly and decoding |
| M02 | `encoded-word-filename` | Attachment name given only as an RFC 2047 encoded word inside `name=` / `filename=` (Outlook style), plus a second attachment where `filename` and `name` disagree | Compatibility decoding, `filename` over `name` priority, conflict warning |
| M03 | `koi8r` | Subject as `=?koi8-r?B?...?=`, `text/plain; charset=koi8-r` body in quoted-printable | Old Russian charset in headers and body |
| M04 | `windows-1251` | Subject as `=?windows-1251?Q?...?=`, body in `windows-1251` base64, sender display name in another charset than the subject | Per-part charset, mixed header charsets |
| M05 | `mislabelled-charset` | Body bytes in `windows-1251` but labelled `iso-8859-1`; a second text part with no `charset` at all | Charset warning; original bytes kept |
| M06 | `plain-and-html` | `multipart/alternative` with `text/plain` and `text/html` that differ slightly | One selected body, no duplicated text |
| M07 | `html-only` | Only `text/html`: a table, links, a `<style>` block, a remote image `https://example.com/pixel.png` | HTML to text, links kept, nothing fetched |
| M08 | `cid-inline-images` | `multipart/related`: HTML referencing two `cid:` images (tiny valid PNGs), one extra image part not referenced | CID mapping, inline vs attachment |
| M09 | `eml-msg-zip-chain` | Attached message that contains a `.msg` that contains a `.zip` with one `.txt` (see "Binary formats" for the fallback) | Nested containers, shared depth counter |
| M10 | `tnef-winmail` | `application/ms-tnef` part named `winmail.dat` carrying an RTF body and one attachment with a long Russian name | TNEF detection by type and signature |
| M11 | `ics-tzid` | `text/calendar; method=REQUEST` with `VTIMEZONE`, `DTSTART;TZID=Europe/Moscow`, `RRULE`, `VALARM`, an `ATTACH` URL on `example.com`, organizer and attendees | Invitation fields; no recurrence expansion, no fetch |
| M12 | `smime-signed` | `multipart/signed; protocol="application/pkcs7-signature"` with a readable first part and a `.p7s` signature part | First part readable, `signatureStatus=not_verified` |
| M13 | `smime-encrypted` | `application/pkcs7-mime; smime-type=enveloped-data`, `smime.p7m` | Status `encrypted`, not "empty message" |
| M14 | `broken-boundary` | `multipart/mixed` whose closing delimiter is missing and whose last base64 part is truncated | Bounded lenient parsing, status `partial` |
| M15 | `smime-opaque-signed` | `application/pkcs7-mime; smime-type=signed-data` | `opaque_signed` |
| M16 | `unsafe-filenames` | Attachments named `..\..\evil.txt`, `/etc/passwd`, `CON.txt`, `report.txt:hidden`, a name with control characters, two names differing only in case | Safe stored names, display names kept |
| M17 | `no-filename` | Attachment without `filename` and `name` | Internal `part-<id>` name, extension from type |
| M18 | `split-encoded-word` | Subject whose UTF-8 encoded words are split in the middle of a multi-byte character, and adjacent encoded words separated by folding whitespace | Header decoding across words |
| M19 | `nesting-over-limit` | Six levels of attached `message/rfc822` (one more than the proposed limit of 5) | `limit_exceeded` with the part where it stopped |
| M20 | `bad-headers` | Repeated `To`, unparseable `Date`, address list with a group and an encoded display name | Headers kept, date left undefined |

## Rules for the content

- **Everything is invented.** No real messages, people, companies, addresses, phone numbers,
  document numbers or URLs. Addresses only on `example.com`, `example.org`, `example.net` or
  their subdomains. Use plain invented names (`Иван Тестов`, `Anna Example`), never public
  figures and never names from the user's environment.
- **Text is short and readable.** A few sentences of ordinary Russian or English business mail
  per part, enough to check decoding (`textContains`). Each message under 64 KiB; the whole set
  under 2 MiB.
- **No secrets.** Message text must not contain anything the sensitive-data detectors of RT-08
  would hide (passwords, tokens, document numbers); that is RT-12's set.
- **No dangerous payloads.** No zip bombs, no executable files, no macros, no real
  certificates or keys of anyone. Limit cases (M19) are small; tests lower the limits instead.
- **Bytes on disk.** `.eml` files use CRLF line endings (RFC 5322); write them in binary mode.
  Do not run any formatter over them.
- **Never use a mail client.** Do not create messages through Outlook, Thunderbird or any other
  installed mail program, and do not send them anywhere — that would touch real mailboxes.

## Binary formats

Write helper scripts only in `testdata/.work/RT-11/` (ignored by git) or in the system temp
directory. Do not install packages and do not download anything; first check what is
installed (for example `python --version`, `java -version`, `openssl version`).

- **PNG:** generate a valid 1×1 or 4×4 image with the Python standard library (`zlib`, `struct`).
- **ZIP:** Python `zipfile`.
- **Other attachments:** use a type only if the file is valid for it (a minimal hand-written PDF
  is fine). Otherwise attach `text/plain` with a `.txt` name; the file name tests do not need a
  real PDF.
- **TNEF (M10):** build it by hand from the specification MS-OXTNEF (signature `0x223E9F78`,
  attribute records with checksums). Cite the specification.
- **MSG (M09):** a `.msg` file is an OLE compound file with MAPI property streams (MS-OXMSG,
  MS-CFB). If you can produce a valid one with installed tools, do it. If you cannot, build the
  chain as attached message → attached message → `.zip` → `.txt`, set `"generated": false` for
  the MSG step in `manifest.json`, and describe in the report exactly what is missing and how
  the user could produce the file later.
- **S/MIME (M12, M13, M15):** if `openssl` is available, create a throwaway self-signed
  certificate for `test@example.com` in the scratch directory and use `openssl cms` to sign and
  encrypt. Keep only the resulting messages; never put the private key into `testdata/`. Without
  `openssl`, use base64 bodies of the right MIME structure and set `"cmsValid": false`.
- **Independent check (optional, recommended):** if jars of Mime4j, Angus/Jakarta Mail, POI or
  Tika already exist in the local Gradle or Maven cache (`%USERPROFILE%\.gradle\caches\modules-2`,
  `%USERPROFILE%\.m2\repository`), you may parse the files with a throwaway single-file Java
  program run by `java -cp <jars> Check.java` from the scratch directory. Record which library
  and version agreed with `expected.json` and which did not. Do not download jars.

## Files to create

```text
testdata/mime/
  README.md                         English: purpose, layout, JSON fields, warning codes, how the files were made
  manifest.json                     list of all cases
  M01-rfc2231-continuations.eml
  M01-rfc2231-continuations.expected.json
  ...
```

`manifest.json`:

```json
{
  "set": "mime",
  "brief": "docs/research-tasks/RT-11-synthetic-mime.md",
  "created": "YYYY-MM-DD",
  "cases": [
    {
      "id": "M01",
      "file": "M01-rfc2231-continuations.eml",
      "expected": "M01-rfc2231-continuations.expected.json",
      "title": "RFC 2231 file name with continuations",
      "covers": ["rfc2231", "filename"],
      "source": "docs/research/RT-09-mime-attachments.md, В2",
      "sha256": "<hash of the .eml file>",
      "generated": true
    }
  ]
}
```

`<case>.expected.json` — the result of parsing, as a correct parser should report it.
Part paths use IMAP section numbers (`1`, `1.2`, `2.1.1`); the root body of a non-multipart
message is `1`.

```json
{
  "id": "M01",
  "status": "ok",
  "headers": {
    "subject": "Договор поставки — проект",
    "from": [{ "name": "Иван Тестов", "address": "ivan.testov@example.com" }],
    "to": [{ "name": null, "address": "anna@example.org" }],
    "date": "2026-03-12T10:15:00+03:00",
    "messageId": "<m01.20260312@example.com>"
  },
  "body": {
    "selectedPart": "1.1",
    "contentType": "text/plain",
    "charsetDeclared": "utf-8",
    "charsetActual": "utf-8",
    "textContains": ["проект договора во вложении"],
    "textNotContains": ["<html"]
  },
  "parts": [
    {
      "path": "2",
      "contentType": "application/pdf",
      "disposition": "attachment",
      "filenameRaw": "filename*0*=utf-8''%D0%94...; filename*1*=...",
      "filenameDecoded": "Договор поставки №15 от 12.03.2026.pdf",
      "contentId": null,
      "size": 1234,
      "sha256": "<hash of decoded bytes>",
      "status": "ok",
      "children": []
    }
  ],
  "warnings": [],
  "notes": "Free text: what exactly is tricky here and which RT-09 rule applies."
}
```

Field rules:

- `children` describes what is inside a container part (attached message, MSG, TNEF, ZIP),
  using the same structure recursively, so M09 shows the whole chain.
- `warnings` uses a small vocabulary of codes that you define in `testdata/mime/README.md`
  (for example `charset-mismatch-suspected`, `filename-conflict`,
  `filename-encoded-word-nonstandard`, `boundary-not-closed`). Mark the list as a proposal.
- Where RT-09 leaves a policy open (for example whether to auto-correct a mislabelled charset,
  M05), record the ground truth (`charsetActual`, the correct Russian text) and write in
  `notes` which outcomes are acceptable. Do not invent a decision.
- `size` and `sha256` are of the decoded bytes; compute them with a script, do not estimate.
- For M07 and M08 list remote resources that must **not** be fetched in `"remoteResources"`.
- For M11 add `"calendar"` with `method`, `uid`, `summary`, `dtstart` (with `tzid`), `rrule`
  (as text, not expanded), `organizer`, `attendees`.
- For S/MIME cases add `"smime": { "kind": "...", "cmsValid": true|false }`.

## Report file

Write `docs/research/RT-11-synthetic-mime.md` in Russian with the data-task report template
from the README. In "Состав набора" give one table row per case: ID, file, what it covers,
generated yes/no, checked by (own script / library and version / not checked).

## If you run out of time

Finish whole cases, not halves: a case counts only with its `.eml`, `.expected.json` and
manifest row. Order of work: M01–M08 (text only), M14, M11, M12–M13, M10, M09, then M15–M20.
List unfinished cases in the report under "Что не удалось сделать". A second session given the
same prompt reads the manifest and the report and continues from the first missing case.

## Out of scope

- Writing parser code, tests or build files; changing `redmine-mcp-server`.
- Real or "anonymized real" messages — the user may supply those separately later.
- Secret-detector samples (RT-12) and the search corpus (RT-13).

## Done when

- M01–M14 exist with `.eml` and `.expected.json`, each listed in `manifest.json` with a correct
  `sha256`; M09's MSG step either exists or is marked `"generated": false` with an explanation.
- Every `.expected.json` parses as JSON, and every `textContains` string really occurs in the
  decoded text of the selected part (checked by a script).
- `testdata/mime/README.md` documents the layout, all JSON fields and the warning codes.
- The report lists how each binary file was produced and what was checked independently.
- `git status` shows changes only in `testdata/mime/` and the report file.

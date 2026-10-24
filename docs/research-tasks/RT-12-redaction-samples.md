# RT-12 — Synthetic samples for the sensitive-data detectors

- **Kind:** data task (see README, "Data tasks").
- **Data directory:** `testdata/redaction/`.
- **Report file:** `docs/research/RT-12-redaction-samples.md`.
- **Size:** M.
- **Fills:** `docs/plan.md`, section 9 (synthetic detector samples); input for decision П11 and
  stage 5 (`docs/plan.md`, section 7).
- **Needs:** tools already installed on the machine (a script language to compute checksums);
  web only to re-check a checksum algorithm or a token format.

## Goal

Create positive and negative text samples for the 15 MVP detectors recommended in
`docs/research/RT-08-redaction-detectors.md`, each with the exact expected findings, so that the
future filter can be measured for misses and false positives before it touches real mail.

## What we already know

- Decision П11 (`docs/plan.md`, section 10): the filter runs both before indexing and on every
  output; 15 detectors in the MVP; the replacement shows neither length nor tail of the value.
- The 15 MVP detectors (RT-08, "Рекомендация"; formats and checks in the catalogue table):

  | ID | Detector | Key checks from RT-08 |
  |---|---|---|
  | D01 | Пароль RU/EN | Value only, quotes, field boundary, no entropy threshold (В2) |
  | D03 | Пароль от Wi-Fi / PSK | Link to the network; SSID is not a secret |
  | D05 | Код подтверждения / OTP | Label required; 4–10 digits; not an order number |
  | D08 | Учётные данные в URI | Whole `userinfo` before `@`; percent-encoding |
  | D09 | `Authorization: Basic` | Valid Base64 with a `:` inside; whole token |
  | D10 | `Authorization: Bearer` | Header or auth field; JWT not required |
  | D11 | JWT / JWE | 3 or 5 base64url segments; JSON header; whole token |
  | D12 | PEM private key | Matching `END` line of the same type; whole block |
  | D14 | GitHub token | `ghp_…`, `github_pat_…`; length and boundaries |
  | D18 | Общий API-ключ / token / secret | Field name context; entropy only as a hint |
  | D20 | Строка подключения | JDBC / ADO.NET / URI grammar; several secret fields |
  | D22 | Паспорт РФ | `2+2+6` digits next to a label; no checksum exists |
  | D23 | СНИЛС | Checksum (В3), historical range below `001-001-998` |
  | D25 | ИНН-12 | Two check digits (В3) |
  | D28 | Банковская карта | Luhn, 12–19 digits, no substring of a longer number |

- RT-08 В2 gives the value-boundary rules, В3 the checksum algorithms and a few hand-computed
  synthetic values, В4 the typical false positives (order numbers, versions, hashes,
  «смените пароль»), В6 the table and config-file cases. Read these sections first.
- RT-08 В4 proposes: a value with an explicit label (`СНИЛС`, `ИНН`, `паспорт`) but a wrong
  checksum is still hidden, with lower confidence. This is a recommendation, not a decision.

## Required coverage

For **each** of the 15 detectors:

- at least 6 positive and 6 negative cases (aim for 8 + 8);
- both Russian and English wording wherever the detector has a text label;
- the variants below that apply to the detector:

| Variant | Examples of what to cover |
|---|---|
| Quotes | `"…"`, `'…'`, `«…»`, backticks; a quote character inside the value |
| Boundaries | value followed by `,` `.` `;` `)`, by the next field on the same line (`пароль: … логин: …`), by end of text |
| Line breaks | label at the end of a line and value on the next; a long token wrapped by a mail client; text in a quoted reply (`> `) |
| Tables | rows as they come out of a spreadsheet or HTML table: tab-separated, pipe-separated, header cell `Пароль` and value in the next cell |
| Config text | `.env`, INI, YAML, JSON, XML fragments; `${VAR}` references (negative) |
| Checksums | a valid value; the same value with one digit changed (`checksum: invalid`), with and without a label |
| Look-alikes (negatives) | order and invoice numbers, versions, dates, phone-like numbers, SHA-256 hashes, UUIDs, base64 of an image, «смените пароль», «пароль не требуется», `пароль: ********` |
| Overlaps | one value matched by two detectors (JWT inside `Authorization: Bearer`, password inside a JDBC URL with `userinfo`, a card number in a password field) |

In addition create at least 8 **mixed documents** (`mixed/`): realistic mail bodies of 10–40
lines, each combining several detectors, a quoted reply, a table and harmless look-alikes,
for example an IT access handover, a VPN setup letter, an HR letter with СНИЛС and ИНН, a
developer thread with tokens and a connection string.

## Rules for the values

- **Every value is invented and generated, not copied.** Do not take document numbers, card
  numbers, keys or tokens from any web page, documentation example, gitleaks or Presidio test
  file — such examples are sometimes real. Generate digits randomly and compute check digits
  with a script; generate tokens from random characters.
- **Card numbers:** prefer the payment systems' published test numbers or random Luhn-valid
  numbers with a test-range prefix; say in the report which you used.
- **Checksums are verified by a script**, never by hand only. Every case with a checksum has
  `"checksum": "valid"` or `"invalid"`, and the report says which script language computed it.
- **Provider tokens must not look live.** For D14 the token must deliberately fail GitHub's
  built-in token checksum where the format has one, so that GitHub push protection and secret
  scanners do not treat it as a real leak; for D12 the key body is random base64 that is not a
  valid key. If you are not sure a sample could pass a provider's own validation, say so in the
  report («проверить»). Never call any provider API to check a token.
- **No real people or places.** Names are plain invented ones; addresses only on `example.com`,
  `example.org`, `example.net`; hosts like `db.example.com`, `vpn.example.com`.
- **Text files use LF line endings and UTF-8 without BOM.**

Write helper scripts only in `testdata/.work/RT-12/` (ignored by git) or in the system temp
directory. Do not install packages and do not download anything.

## Files to create

```text
testdata/redaction/
  README.md                    English: purpose, layout, JSON fields, matching rules
  manifest.json                list of all files with case counts per detector
  D01-password.json            cases for D01
  D03-wifi-password.json
  ...                          one file per detector, 15 files
  mixed/
    X01-access-handover.txt
    X01-access-handover.expected.json
    ...
```

Detector file `Dnn-<slug>.json`:

```json
{
  "detector": "D23",
  "title": "СНИЛС",
  "source": "docs/research/RT-08-redaction-detectors.md, В3 and catalogue D23",
  "cases": [
    {
      "id": "D23-P01",
      "kind": "positive",
      "lang": "ru",
      "variants": ["label", "checksum"],
      "text": "Прошу внести в анкету СНИЛС <generated value> до пятницы.",
      "checksum": "valid",
      "expected": [
        { "detector": "D23", "match": "<generated value>", "occurrence": 1, "confidence": "high" }
      ],
      "note": "Grouped with hyphens and a space, as on the card."
    }
  ]
}
```

(`<generated value>` stands for the value your script generated; in the real file both places
hold the same literal string.)

Mixed document `X<nn>-<slug>.expected.json`:

```json
{
  "id": "X01",
  "file": "X01-access-handover.txt",
  "expected": [
    { "detector": "D01", "match": "…", "occurrence": 1, "confidence": "high" }
  ],
  "redactedText": "full text with every merged finding replaced by [скрыто:D01], [скрыто:D23] …",
  "note": "Which overlaps are tested and how ranges merge."
}
```

Matching rules — write them into `testdata/redaction/README.md`:

- `expected` is the **complete** list of findings of the 15 MVP detectors in that text. A
  finding not in the list is a false positive; an expected finding not reported is a miss.
  Negative cases therefore have `"expected": []` unless another MVP detector legitimately fires.
- `match` is the exact substring that must be hidden; `occurrence` counts non-overlapping
  occurrences of that substring from the start of `text`, starting at 1. No character offsets.
- `confidence` is `high` or `low`. Use `low` only for the RT-08 В4 case "explicit label, wrong
  checksum" and for ambiguous boundaries (RT-08 В2, step 5); tag such cases with the variant
  `disputed`, because the policy is not decided yet.
- Overlaps: when findings overlap, the hidden range is their union (RT-08, "Рекомендация");
  `redactedText` shows the result with the canonical marker `[скрыто:Dnn]` of the larger
  finding. The marker is a test convention, not the final replacement style.
- Content of non-MVP detectors (phones, e-mail addresses, ОГРН, БИК) may appear freely; it is
  not expected to be hidden.

## Report file

Write `docs/research/RT-12-redaction-samples.md` in Russian with the data-task report template
from the README. In "Состав набора" give a table: detector, positives, negatives, variants
covered, variants not covered and why. Add a short list of the `disputed` cases so the user can
decide the policy.

## Out of scope

- Implementing detectors or running any third-party secret scanner on the repository.
- Choosing the final replacement text and the place of the filter (decided in П11).
- Detectors outside the MVP 15 (they can be added later as separate files).
- MIME messages (RT-11) and the search corpus (RT-13).

## Done when

- 15 detector files exist, each with at least 6 positive and 6 negative cases covering the
  applicable variants; at least 8 mixed documents with `.expected.json`.
- Every JSON file parses; every `match` occurs in its `text` at least `occurrence` times
  (checked by a script); every `checksum` field agrees with a scripted check.
- `testdata/redaction/README.md` documents the layout, fields and matching rules;
  `manifest.json` lists every file.
- `git status` shows changes only in `testdata/redaction/` and the report file.

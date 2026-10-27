# RT-13 — Control queries and synthetic correspondence

- Date: 2026-10-25
- Author: Codex
- Brief: docs/research-tasks/RT-13-search-queries.md
- Data: testdata/search/

## Summary

- [code] Created 48 messages and 80 queries; 16 messages contain an attachment.
- [code] All 14 categories have the required query counts; language minimums are met.
- [code] JSON stores literal evidence for every expected and excluded message.
- [code] Two invented sensitive strings exist only in originals and are excluded from search.
- [assumption] Desired search quality is manually annotated; no analyzer was run.

## Set contents

| Category | Queries | Predicted misses | Note |
|---|---:|---:|---|
| `ru-noun-forms` | 10 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `ru-irregular` | 6 | 6 | Evidence is in `evidence` and `nearMissEvidence` |
| `ru-verb-adj` | 5 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `ru-yo` | 3 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `ru-names` | 5 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `en-forms` | 15 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `mixed-lang` | 5 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `address` | 6 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `filename` | 6 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `identifier` | 5 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `precision` | 6 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `stopwords-phrase` | 4 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `case` | 2 | 0 | Evidence is in `evidence` and `nearMissEvidence` |
| `redacted` | 2 | 0 | Evidence is in `evidence` and `nearMissEvidence` |

### Groups checked

- `ru-noun-forms`: Q001, Q002, Q003, Q004, Q005, Q006, Q007, Q008, Q009, Q010.
  Expected results apply only to the specified field; other messages are not evaluated.
- `ru-irregular`: Q011, Q012, Q013, Q014, Q015, Q016.
  Expected results apply only to the specified field; other messages are not evaluated.
- `ru-verb-adj`: Q017, Q018, Q019, Q020, Q021.
  Expected results apply only to the specified field; other messages are not evaluated.
- `ru-yo`: Q022, Q023, Q024.
  Expected results apply only to the specified field; other messages are not evaluated.
- `ru-names`: Q025, Q026, Q027, Q028, Q029.
  Expected results apply only to the specified field; other messages are not evaluated.
- `en-forms`: Q030, Q031, Q032, Q033, Q034, Q035, Q036, Q037, Q038, Q039, Q040, Q041, Q042, Q043, Q044.
  Expected results apply only to the specified field; other messages are not evaluated.
- `mixed-lang`: Q045, Q046, Q047, Q048, Q049.
  Expected results apply only to the specified field; other messages are not evaluated.
- `address`: Q050, Q051, Q052, Q053, Q054, Q055.
  Expected results apply only to the specified field; other messages are not evaluated.
- `filename`: Q056, Q057, Q058, Q059, Q060, Q061.
  Expected results apply only to the specified field; other messages are not evaluated.
- `identifier`: Q062, Q063, Q064, Q065, Q066.
  Expected results apply only to the specified field; other messages are not evaluated.
- `precision`: Q067, Q068, Q069, Q070, Q071, Q072.
  Expected results apply only to the specified field; other messages are not evaluated.
- `stopwords-phrase`: Q073, Q074, Q075, Q076.
  Expected results apply only to the specified field; other messages are not evaluated.
- `case`: Q077, Q078.
  Expected results apply only to the specified field; other messages are not evaluated.
- `redacted`: Q079, Q080.
  Expected results apply only to the specified field; other messages are not evaluated.

## File generation

- Python 3.14.3, standard library only: email, json, hashlib.
- Generator: `testdata/.work/RT-13/generate.py`, excluded from git.
- Text, subjects, addresses and attachment names were invented for this set.
- `.eml` files are created with EmailMessage, SMTP policy and a UTF-8/Base64 body.
- Filenames are passed through MIME parameters; dates are normalized by email.
- JSON is written as UTF-8 without BOM with LF; messages in binary mode with CRLF.
- SHA-256 is calculated over each file's final bytes.
- The manifest lists all files; it omits its own hash to avoid a self-reference.

## Verification

- [code] Every message was parsed back with Python email; subjects and bodies were compared.
- [code] Decoded names and text of all 16 attachments were checked.
- [code] Minimums checked: 40 Russian, 15 English and 70 total queries.
- [code] Per-category minimums and non-overlapping lists were checked.
- [code] Every evidence value occurs in the specified field of the original message.
- [code] SHA-256 and the absence of bare LF in `.eml` were checked.
- [code] All query values reference existing IDs only.
- [code] Both invented strings were removed from redactedBody.
- No analyzers, index, detectors or network services were run.

### First morphology queries to check

- `Q011`: `письмо`, expected S010.
- `Q012`: `писем`, expected S009, S011.
- `Q013`: `письмами`, expected S010.
- `Q014`: `человек`, expected S013.
- `Q015`: `ребёнок`, expected S015.
- `Q016`: `год`, expected S017.

## Disputed cases

- Expected lists describe desired matches, not measured Snowball behavior.
- Predicted misses for `письмо`/`писем` are based on RT-06 В2; other irregular stems are assumptions.
- Results for the name `Анна` exclude its diminutive `Аня`.
- Address-part queries do not match full addresses: this is the contract proposed in RT-06.
- If address-part search is allowed later, these expectations must be revised.
- Filenames are preserved whole and split into parts; fields are a proposal for future tests.
- Result order is not evaluated; rankFirst is null everywhere.
- A shared stem does not prove similar meaning; precision cases contain explicit exclusions.

## What could not be done

- Lucene behavior was not measured: outside the task.
- Specific misses or extra matches will become known when the index is connected.
- Corpus signatures do not imitate real DKIM or S/MIME: these are ordinary messages.

## Further research

- Compare per-category results when running RussianAnalyzer and EnglishAnalyzer.
- Compare a dictionary analyzer on the same set only if misses are significant.

## Sources

- Local requirements: docs/research-tasks/RT-13-search-queries.md, read on 2026-10-25.
- Local morphology analysis: docs/research/RT-06-search-engine.md, В2, read on 2026-10-25.

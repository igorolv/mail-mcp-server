# RT-13 — Control set of search queries and a synthetic corpus

- **Kind:** data task (see README, "Data tasks").
- **Data directory:** `testdata/search/`.
- **Report file:** `docs/research/RT-13-search-queries.md`.
- **Size:** M.
- **Fills:** `docs/plan.md`, section 9 (control queries for morphology, decision П9); input for
  stage 4 (`docs/plan.md`, section 7).
- **Needs:** tools already installed on the machine; no web needed.

## Goal

Create a small synthetic mail corpus and a set of Russian and English queries with the messages
each query must and must not find. The set will show, before and after any change of analyzer,
how well the local search handles Russian word forms, English, addresses, file names and
identifiers — the risks named in `docs/research/RT-06-search-engine.md`.

## What we already know

- Decision П9 (`docs/plan.md`, section 10): Lucene 10.x with `RussianAnalyzer`, a separate
  English field, separate fields for addresses and file names; metadata in SQLite.
- RT-06 В2 lists the main morphology risks of Snowball, with sources: `письмо / письма →
  письм` but `писем → пис` (miss); `план / плана / планами → план`; `договор / договора /
  договорами → договор` (hand-derived). Irregular forms and different words that share a stem
  are the two failure directions. Hunspell and LuceneMorphology are the fallback candidates if
  the control queries show significant misses (RT-06, "Рекомендация").
- RT-06 В2, "Адреса и файлы": a full address is an exact term; a person's name is analyzed
  text; a file name is kept whole and also split into parts (`договор`, `2026`, `pdf`).
- Decision П11: the index is built from redacted text, so a secret in a message must not be
  findable.

## Required coverage

Corpus: **40–80 messages**. Queries: **at least 70**, of which at least 40 Russian, 15 English,
the rest mixed or technical. Cover every category below with at least the given number of
queries.

| Category | Min | What to include |
|---|---|---|
| `ru-noun-forms` | 10 | `договор / договора / договором / договорами`, `счёт / счета / счетов`, `план / планами`, `встреча / встречи / встреч`; query in one form, messages in others |
| `ru-irregular` | 6 | `письмо / писем / письмами`, `человек / люди`, `ребёнок / дети`, `год / лет`, other forms with a changing stem |
| `ru-verb-adj` | 5 | `отправил / отправлю / отправлено`, `согласован / согласование`, `срочный / срочно` — say which ones should match |
| `ru-yo` | 3 | `ё / е`: `счёт / счет`, `отчёт / отчет` |
| `ru-names` | 5 | surnames in cases: `Тестов / Тестову / Тестовым`, female forms `Тестова`; first name in diminutive is **not** expected to match |
| `en-forms` | 8 | `report / reports / reporting`, `meeting / meetings`, `invoice / invoices`, irregular `send / sent` |
| `mixed-lang` | 5 | Russian text with English terms (`релиз`, `deploy`, `Jira`), one query word in each language |
| `address` | 6 | full address (`ivan.testov@example.com`), local part only, domain only, display name; partial address behaviour stated |
| `filename` | 6 | full name with extension, a word from the name in another case, the extension alone, date or number inside the name (`Договор_поставки_№15-2026.pdf`) |
| `identifier` | 5 | `Д-2026/015`, `INC-4512`, `ООО`, `НДС`, version `2.1.0`; hyphenated words `онлайн-встреча`, `Wi-Fi` |
| `precision` | 6 | near-miss words that must **not** match: different words with a common Snowball stem, homonyms (`вина`), short words that are prefixes of others; candidates found by you are marked `[предположение]` |
| `stopwords-phrase` | 4 | a query of stop words only (expected empty), a phrase query in quotes, word order |
| `case` | 2 | upper case and mixed case of Russian and English words |
| `redacted` | 2 | a fake password or document number present in a message; the query for that value expects **no** match (П11) |

## Rules for the corpus

- **Everything is invented.** No real messages, people, companies, addresses, document numbers
  or URLs. Addresses only on `example.com`, `example.org`, `example.net`; plain invented names.
- **Ordinary business mail.** Short messages (3–20 lines) on a few invented topics (supply
  contract, monthly report, release, office move, invoices), so that one word appears in
  several messages and forms. Some messages are replies with quoted text.
- **Messages are `.eml`** (UTF-8, `text/plain`, CRLF line endings, written in binary mode), with
  `From`, `To`, `Subject`, `Date`, `Message-ID`. About a third carry an attachment that matters
  for the queries: `text/plain`, `text/csv` or `text/html`, or a minimal valid PDF only if you
  can write it correctly. The attachment's file name is part of the test.
- **No secrets** except the two `redacted` cases, which use obviously invented values.
- Keep the corpus independent of RT-11 and RT-12: do not reuse their files.

Write helper scripts only in `testdata/.work/RT-13/` (ignored by git) or in the system temp
directory. Do not install packages and do not download anything.

## Files to create

```text
testdata/search/
  README.md          English: purpose, layout, JSON fields, how to score
  corpus.json        list of messages: id, file, subject, what forms and terms each one contains
  corpus/
    S001-<slug>.eml
    ...
  queries.json       all queries
```

`queries.json`:

```json
{
  "set": "search",
  "brief": "docs/research-tasks/RT-13-search-queries.md",
  "created": "YYYY-MM-DD",
  "queries": [
    {
      "id": "Q001",
      "query": "договорами",
      "lang": "ru",
      "category": "ru-noun-forms",
      "field": "any",
      "expected": ["S003", "S014", "S027"],
      "mustNotMatch": ["S040"],
      "rankFirst": null,
      "snowball": "hit",
      "basis": "RT-06 В2: договор / договора / договорами → договор, hand-derived [предположение]",
      "note": "S040 contains «договориться», a different word."
    }
  ]
}
```

Field rules:

- `expected` is the set of messages a good search **must** return for the query (the ideal,
  independent of any analyzer); order is not tested. `mustNotMatch` lists near-miss messages
  that must **not** be returned. Messages in neither list are not scored.
- `field` is `any`, `subject`, `body`, `from`, `to`, `attachmentName` or `attachmentText`.
  The tool API is not designed yet, so mark field-specific queries as a proposal in the README.
- `rankFirst` names one message only when one result is clearly the best; otherwise `null`.
- `snowball` predicts how `RussianAnalyzer` / Snowball behaves for this query: `hit`, `miss` or
  `unknown`; `basis` gives the reason with a certainty tag (`[код]` when RT-06 cites the Snowball
  test files, `[предположение]` for your own derivation). Do not run or install Lucene; the
  prediction is only a hint, the `expected` set is the ground truth. Prefer `unknown` to a guess.
- Every `expected` message must really contain the matching word form, address or name (checked
  by a script on the decoded text, subject, addresses and attachment names); every
  `mustNotMatch` message must contain the near-miss it is meant to test.

## Report file

Write `docs/research/RT-13-search-queries.md` in Russian with the data-task report template
from the README. In "Состав набора" give a table: category, number of queries, number of
`snowball: miss` predictions, notes. List the queries where you expect Snowball to fail; they
are the first test of whether Hunspell or a dictionary analyzer is needed (RT-06,
"Рекомендация").

## Out of scope

- Running or configuring Lucene, choosing analyzers, designing the search tool API.
- Fuzzy search and typo tolerance (may be a later category).
- Embeddings and semantic queries (decision П12: not in the MVP).
- MIME edge cases (RT-11) and detector samples (RT-12).

## Done when

- The corpus has 40–80 `.eml` files listed in `corpus.json`; `queries.json` has at least 70
  queries covering every category with its minimum.
- Every JSON file parses; the scripted check of `expected` and `mustNotMatch` passes.
- `testdata/search/README.md` documents the layout, fields and scoring (recall on `expected`,
  false positives on `mustNotMatch`).
- `git status` shows changes only in `testdata/search/` and the report file.

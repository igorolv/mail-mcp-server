# RT-06 — Local search engine and Russian morphology

- **Result file:** `docs/research/RT-06-search-engine.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 4; `docs/plan.md`, open question 5.

## Goal

Choose candidates for the embedded local search index of our Java server: full-text BM25 by
default, optional vector search, good Russian and English morphology, incremental (append-mostly)
indexing.

## What we already know

- What we index: subject, sender and recipients, body text (from HTML), extracted attachment
  text, attachment file names. Mail is mostly Russian, partly English (`docs/plan.md`, 5.3).
- Scale guess: a few mailboxes; the corporate Inbox alone has about 2000 messages. Plan for up to
  ~100 000 messages plus attachments.
- Candidates from our notes: Apache Lucene (BM25 + HNSW vectors, `RussianAnalyzer`) and SQLite
  FTS5 (+ an extension for vectors). Other options are welcome.
- Embedded only: no separate server process for the user to run.

## Questions

- **В1. Apache Lucene** (current major version): BM25 as the default similarity; vector fields
  (`KnnFloatVectorField`, HNSW) and quantization; how to combine BM25 and kNN results (built-in
  support or manual reciprocal rank fusion); highlighting / snippets; near-real-time search and
  updates/deletes; index size and memory expectations at our scale.
- **В2. Russian morphology for Lucene:** `RussianAnalyzer` (Snowball stemmer) quality and known
  problems; dictionary-based options such as the `russianmorphology` project (LuceneMorphology),
  Hunspell with Russian dictionaries; mixed Russian + English text in one field; e-mail addresses
  and file names as tokens.
- **В3. SQLite FTS5:** BM25 ranking, available tokenizers and the lack of a Russian stemmer; can
  a custom tokenizer be plugged in from Java (`sqlite-jdbc`)? Vector options (`sqlite-vec`) and
  their Java usability.
- **В4. Other embeddable options** worth a line each: H2 full-text, Apache Lucene-based libraries
  (for example Hibernate Search without a database), Tantivy via bindings, anything else you find.
  Mark the ones that require a separate server as out of scope.
- **В5. Incremental indexing patterns** for append-mostly data: document IDs, updates when a
  message moves or its flags change, deletions, re-index after an analyzer change.
- **В6. Licences** of every candidate and dependency.

## Where to look

- `https://lucene.apache.org/` — release notes, Javadoc for `similarities`, `KnnFloatVectorField`,
  `org.apache.lucene.analysis.ru`, highlighter modules.
- `https://github.com/AKuznetsov/russianmorphology` (check activity and licence).
- SQLite FTS5 documentation `https://www.sqlite.org/fts5.html`; `sqlite-vec` repository;
  `xerial/sqlite-jdbc` documentation.
- Blog posts or talks comparing Russian stemming approaches (mark as `[вторично]`).

## Required tables

| Движок | BM25 | Векторы | Русская морфология | Встраивается в JVM | Обновления/удаления | Лицензия | Зрелость / активность | Источник |
|---|---|---|---|---|---|---|---|---|

| Вариант морфологии | Подход (стемминг/словарь) | Качество (примеры) | Зависимости | Лицензия | Активность |
|---|---|---|---|---|---|

Include 3–5 short Russian examples showing how each morphology option treats word forms
(e.g. «договор / договора / договорами», «письмо / писем»), from documentation or reports;
do not run code.

## Out of scope

- Writing or running benchmarks or any code.
- Embedding model choice (RT-07).
- Search UX and tool design.

## Done when

- В1–В6 answered; both tables filled.
- The recommendation names a primary engine and morphology option, and the main risk of each.

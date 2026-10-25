# IT-07 — search

Plan step: `docs/plan.md` 7.5. Size: one full session. Next brief: IT-08 (attachment text,
step 7.6; not written yet).

## Goal

An agent finds messages by words, as people write them in Russian and English. A new tool
`search(connection, query, field, folder, offset, limit)` returns message cards with a score and a
highlighted snippet of the body.

The index gets real analyzers and fields as decided in П9:
- Russian text: `RussianAnalyzer`;
- the same text again in an English field;
- full addresses and full file names as exact terms;
- file names also split into parts.

A schema version in the index makes an old index rebuild itself from disk, without the network. A
new offline command, `mail-reindex <connection>`, does that on request. The test runs the control
set `testdata/search` (48 messages, 80 queries) and prints recall and false positives. The plan
records the result as the baseline.

## Read first

1. `docs/plan.md` sections 6.5, 6.9 and 7.5, and decision П9 in section 10 (Russian).
2. `docs/research/RT-06-search-engine.md`, sections «Русский и английский в одном исходном поле»,
   «Адреса и файлы» and «Фрагменты и подсветка» (Russian; the facts you need are below).
3. `testdata/search/README.md`, `queries.json`, `corpus.json` (structure only).
4. `docs/storage-agent.md`, `docs/tools-agent.md`.
5. `src/main/java/mail/mcp/index/` — all files; `sync/SyncRunner.java`,
   `cli/MailReprocessCommand.java`, `tools/MailTools.java`, `api/MessageSummary.java`.

## Already decided — do not revisit

The reviewer built a prototype on 2026-10-26 with Lucene 10.5.2 on the 48 corpus messages and the
80 queries. Everything below was measured there; follow it as written.

**Analyzers.** One `PerFieldAnalyzerWrapper`, with `RussianAnalyzer` as the default:

| Name | Chain | Used for |
|---|---|---|
| Russian (default) | `new RussianAnalyzer()` | `subject`, `body`, `fromName`, `toName`, `ccName` |
| English | `new EnglishAnalyzer(stops)`, where `stops` is the English default stop set plus `RussianAnalyzer.getDefaultStopSet()` | `subject_en`, `body_en` |
| Exact | `KeywordTokenizer` + `LowerCaseFilter` | `fromAddress`, `toAddress`, `ccAddress`, `attachmentFile` |
| Name parts | `PatternTokenizer("[^\\p{L}\\p{N}]+", -1)` + `LowerCaseFilter` + `SnowballFilter(new RussianStemmer())` | `attachmentName` |

Why each choice:
- Without the Russian stop words in the English field, the stop-word-only query `и в на` returns a
  message (Q073).
- `StandardTokenizer` would split `ivan.testov@example.com` into parts. The local-part and
  domain-only queries (Q052, Q053) must not match, so addresses use the exact analyzer.
- `StandardTokenizer` keeps `Договор_поставки` as one token. The pattern tokenizer splits it, so
  `договорами` finds the file (Q057).

**Query parsing.** Lucene's `SimpleQueryParser` (module `lucene-queryparser`), with the
per-field analyzer, equal field weights and `setDefaultOperator(Occur.MUST)`.
- It never throws on agent input.
- `"..."` means a phrase.
- Every word is required.
- A stop-word-only query yields `null`, which means no results.

**Field groups of the `field` argument**

| `field` | Index fields searched |
|---|---|
| `any` (default) | all fields below |
| `subject` | `subject`, `subject_en` |
| `body` | `body`, `body_en` |
| `from` | `fromAddress`, `fromName` |
| `to` | `toAddress`, `toName`, `ccAddress`, `ccName` |
| `attachmentName` | `attachmentName`, `attachmentFile` |

**Snippet.** `UnifiedHighlighter` (module `lucene-highlighter`) on the stored `body` field, with
offsets from re-analysis (the default when no offsets are indexed).
- Use a `DefaultPassageFormatter("**", "**", "… ", false)`; the body is Markdown.
- The snippet is `null` when the message has no body.

**Prototype result** (fields built from `corpus.json` directly):
- recall 79/86 = 0.919, with 7 false positives;
- the misses are all 7 `ru-irregular` cases (Snowball cannot join `письмо`/`писем`, `человек`, `ребёнок`,
  `год` and similar);
- the false positives:
  - 3 `ru-names` (stems collide: `Тестову` → `Тестова`, `Анна` ↔ `Аня`);
  - 2 `precision`;
  - 2 `redacted`: the sensitive-data filter is deferred, plan.md 6.9.

  These are accepted limits of Snowball and of the deferred filter. Do not try to fix them.
- Your number may differ by one: attachment text is step 7.6, so Q061 must miss here.

**Schema version.** The schema version is stored as commit user data `schema=2` on every commit.
Opening a writer on an index whose last commit has another value, or none, recreates it empty
(`OpenMode.CREATE`). The next reconcile then rebuilds every document from disk:
- `converted` messages are loaded from `message.eml` and `body.md`;
- `downloaded` messages are processed;
- `failed` messages are indexed with metadata only.

No mailbox is contacted for this.

Not in this task:
- attachment text (step 7.6);
- Hunspell or another morphology;
- cross-mailbox search;
- query syntax beyond `SimpleQueryParser`;
- fuzzy or wildcard search;
- tuning field weights;
- the filter.

## What to build

### 1. Dependencies

`gradle/libs.versions.toml`: add `lucene-analysis-common`, `lucene-queryparser` and
`lucene-highlighter`, all at the existing `lucene` version (10.5.2). Then `build.gradle.kts`.

### 2. `index/MailAnalyzers` (new)

A final class with one static `Analyzer analyzer()`, built once, as in the table above.
`MailIndex` and `MailIndexReader` both use it. No other public members.

### 3. `index/MailIndex`

- `IndexWriterConfig(MailAnalyzers.analyzer())`.
- `public static final String SCHEMA = "2"`. Every commit sets live commit data `schema=2`.
- `openWriter(Path)` keeps its signature. It recreates the index when the existing commit's
  schema differs.
- New `openWriter(Path, boolean recreate)` always recreates when `recreate` is true.
- `put` builds these fields:

  | Field | How it is stored | Notes |
  |---|---|---|
  | `subject` | `TextField`, stored | |
  | `subject_en` | `TextField`, not stored | |
  | `body` | `TextField`, stored | |
  | `body_en` | `TextField`, not stored | |
  | `from`, `to`, `cc` | `StoredField` only | the display strings, as today, for cards |
  | `fromAddress`, `toAddress`, `ccAddress` | `TextField`, not stored | one value per address |
  | `fromName`, `toName`, `ccName` | `TextField`, not stored | one per address that has a name |
  | `attachmentNames` | `StoredField` only | as today |
  | `attachmentName`, `attachmentFile` | `TextField`, not stored | one value per attachment: the `name`, else the `file` |

  `date`, `key`, `keySort`, `ref`, `folder`, `state`, `error`, `messageId`, `inReplyTo` and
  `references` do not change.

### 4. Search (`index/`, `model/`)

- `index/SearchField`: an enum `ANY, SUBJECT, BODY, FROM, TO, ATTACHMENT_NAME` with the field
  lists above. `static SearchField of(String)` accepts the lowercase names from the table; an
  unknown name is an `IllegalArgumentException` listing the valid ones.
- `model/SearchHit(MessageCard card, float score, String snippet)` and
  `model/SearchResults(int total, List<SearchHit> hits)`.
- `MailIndexReader`:
  - `boolean current()` — true when the last commit has `schema=2`;
  - `SearchResults search(String query, SearchField field, String folderKey, int offset, int limit)`.
  - Behaviour:
    - `folderKey` is optional; when given, the ref `PrefixQuery` is a `FILTER` clause.
    - Order is by score, then `keySort`.
    - `total` is `searcher.count(query)`.
    - A `null` parsed query returns `total=0` and no hits.
  - The card is built exactly as in `cards`. Factor out a private method and use it in both.

### 5. Tool `search` (`tools/SearchTools`, new) and `api/` records

- `search(connection, query, field, folder, offset, limit)`:
  - `query` is required, non-blank, at most 1,000 characters.
  - `field` is optional (`any`).
  - `folder` is an optional folder key; an unknown one is rejected as in `listMessages`.
  - `limit` defaults to 20, at most 100. `offset` defaults to 0.
  - No index: `total=0`.
  - An index with an old schema: `IllegalStateException("The index was built by an older version;
    run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it")`.
- `api/SearchResult(connection, query, field, folder, total, offset, nextOffset, hits)` and
  `api/SearchHitInfo(key, folder, date, from, subject, hasAttachments, state, score, snippet, localPath)`.
  `folder` in a hit is the folder key.
- The tool description must explain:
  - the field names;
  - that every word is required and `"..."` is a phrase;
  - that Russian and English word forms are matched, full addresses must be complete, and file
    names match whole or by part;
  - that results come from local data only and that `listFolders.synced` shows coverage;
  - that `localPath` points at files the agent may read.

  Annotations as for `listMessages`.

### 6. `cli/MailReindexCommand` (new)

- Usage: `mail-reindex <connection>`, jar mode `mail-reindex`, Gradle task `mailReindex`.
- It has the shape of `MailReprocessCommand`: offline, pandoc check (messages in state
  `downloaded` may need it), exit codes 2/4/5/1.
- It runs `MailIndex.openWriter(dir, true)`, then the reconcile loop.
  - Make that loop a `public static void restore(MessageStore, MailIndex, MessageProcessor)` in
    `SyncRunner`. `SyncRunner.run` uses it too.
- Output:
  - `  <n> messages indexed` every 1,000 messages;
  - then `RESULT: OK (<n> messages indexed)`.
- `MailMcpServerApplication` dispatches it.

## Tests

All offline.

`index/SearchControlSetTest`:
- Build a connection directory in `@TempDir` from the 48 files in `testdata/search/corpus/`. They
  are all plain text, so no pandoc is needed.
- Process them through `MessageProcessor` into one folder and index them with `MailIndex`.
- Run every query of `queries.json` through `MailIndexReader.search` with its `field`, `limit` 50.
- Scoring follows README:
  - recall = expected IDs found / expected IDs over all queries with a non-empty `expected`;
  - false positives = `mustNotMatch` IDs returned.
- Print the totals and one line per category to stdout of the test. Gradle keeps this in the
  report.
- Assert recall ≥ 0.90 and false positives ≤ 7.
- Report the exact numbers and the missed or wrongly matched query ids in your report.
- Map corpus `S…` ids to keys through the file name you indexed.

`index/MailIndexTest` (add):
- `oldSchemaIsRecreatedOnOpen`: an index committed without user data reopens empty.
- `recreateFlagEmptiesTheIndex`
- `addressIsAnExactTerm`: a full address finds; the local part or the domain alone does not.

`index/MailIndexReaderTest` (add):
- `searchFiltersByFolderAndPages`
- `stopWordOnlyQueryFindsNothing`
- `snippetMarksTheMatch`: it contains `**…**`.
- `oldSchemaIsNotCurrent`

`tools/SearchToolsTest`:
- `searchReturnsCardsWithLocalPath`
- `unknownFieldOrFolderIsRejected`
- `blankOrTooLongQueryIsRejected`
- `oldIndexAsksForReindex`

`cli/MailReindexCommandTest`:
- `reindexRebuildsHeadersAndBodyWithoutTheNetwork`
- `lockedIndexExitsFive`
- `missingPandocStopsBeforeTheIndex`

`McpStdioServerTest`: the tool list has seven names. Call `search` once on the synthetic data.

Existing tests: those that read `from`/`subject` text through the index keep passing. Adjust
only what the field changes require, and say so.

## Do not

- Index attachment text or add `getAttachment` (step 7.6).
- Add Hunspell, LuceneMorphology, embeddings, a query language of your own, fuzzy/wildcard
  search, synonyms or field boosts.
- Search across connections.
- Keep two index generations or switch between them. The schema bump recreates the index in
  place.
- Change `MimeReader`, `MessageProcessor` or the disk layout.

## Stop and ask when

- The control set gives recall below 0.90 or more than 7 false positives. Report the numbers and
  the failing queries; do not tune the analyzers on your own.
- `UnifiedHighlighter` cannot produce snippets from the stored `body` with the per-field
  analyzer.
- An existing test needs a change other than the field changes above.

## Files

Create:

- `src/main/java/mail/mcp/index/{MailAnalyzers,SearchField}.java`
- `src/main/java/mail/mcp/model/{SearchHit,SearchResults}.java`
- `src/main/java/mail/mcp/api/{SearchResult,SearchHitInfo}.java`
- `src/main/java/mail/mcp/tools/SearchTools.java`
- `src/main/java/mail/mcp/cli/MailReindexCommand.java`
- tests:
  - `src/test/java/mail/mcp/index/SearchControlSetTest.java`
  - `src/test/java/mail/mcp/tools/SearchToolsTest.java`
  - `src/test/java/mail/mcp/cli/MailReindexCommandTest.java`

Change:

- `gradle/libs.versions.toml`, `build.gradle.kts`
- `index/{MailIndex,MailIndexReader}.java`, `sync/SyncRunner.java`
- `MailMcpServerApplication.java`
- `MailIndexTest.java`, `MailIndexReaderTest.java`, `McpStdioServerTest.java`, and tests affected
  by the field changes
- the documents below

## Documents

- `docs/storage-agent.md`: the new index fields and analyzers; the schema version and the
  rebuild on open; `mail-reindex`.
- `docs/tools-agent.md`: the `search` tool (arguments, field groups, bounds, errors, snippet
  format), and the control-set numbers with the known limits.
- `docs/build-config-agent.md`: section "Reindexing" (command, output, exit codes).
- `docs/architecture-agent.md`: the three Lucene modules, new classes, the status line.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- The report gives the control-set recall, false positives, per-category numbers and the ids of
  missed or wrongly matched queries.
- `git status` shows only the files listed under "Files".
- The report lists under "For the user to run":
  - `java -jar build\libs\mail-mcp-server.jar mail-reindex work`, then `mail-reindex mailru`
    (offline; no sync may be running for the same connection, or the command exits 5);
  - then, from opencode, `search` on each mailbox: a Russian word in another grammatical form, an
    English word, a full sender address, and a file name part.

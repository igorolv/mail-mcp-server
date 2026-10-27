# RT-06 — Local search engine and Russian morphology

- Date: 2026-10-24
- Author: Codex / RT-06 research session
- Brief: `docs/research-tasks/RT-06-search-engine.md` (historical path)
- Context: [plan.md](../plan.md), former sections 5.2–5.3; former `research.md`, section 4.
- Method: documentation and source code; code, measurements, and mailbox connections were not run.

## Summary

- [assumption] I recommend Apache Lucene 10.5.2: BM25, morphology, text fragments, and vector search are available inside the JVM without a separate server. This is a proposal, not a decision [1–8].
- [docs] The current main branch is 10.x; release 10.5.2 is dated 2026-10-04. Java 21+ requirement is compatible with planned Java 25 at the minimum-version level [1–2].
- [code] Lucene has a ready-made `TopDocs.rrf`: there is no need to implement ordinary reciprocal-rank fusion of BM25 and kNN results manually [6].
- [assumption] Start with `RussianAnalyzer` / Snowball and a separate English field. The main risk is missed irregular forms: `письмо → письм`, `писем → пис` [9–12].
- [docs] `russianmorphology` uses the AOT dictionary and returns several analyses for homonyms. Lucene 10 compatibility is unconfirmed; README, POM, and history disagree [13–15].
- [code] Hunspell with the LibreOffice dictionary is a fallback candidate, but a spelling dictionary does not guarantee that every form is grouped: `писем` is listed separately from `письмо/K` [16–18].
- [docs] FTS5 has BM25, but no built-in Russian stemmer. Tokenizer interface is C; xerial loads extensions, but no ready Java registration interface was found [21–23].
- [assumption] Keep SQLite FTS5 + `sqlite-vec` as an alternative: it requires shipping a native extension and additional Russian text processing [21–26].
- [assumption] For 100,000 messages, extracted-text volume and number of fragments matter more than message count. There are no reliable index-size or memory figures without a corpus; see В1.

## Answers to the questions

### В1. Apache Lucene: capabilities, updates, and resources

**Version and BM25.** [docs] The official downloads page listed 10.5.2 on the research date. The release announcement separately notes a fix for HNSW search completeness under rare persistent deletions — a relevant case for a mail index [1].

[docs] BM25 is the default relevance function. `BM25Similarity` defaults are `k1=1.2`, `b=0.75`; both can be tuned. Increasing subject weight relative to body is a query/field setting, not a reason to change engines. The latter is a recommendation [3].

**Vectors.** [docs] `KnnFloatVectorField` contains one dense vector per document for that field; `KnnFloatVectorQuery` searches for nearest documents and accepts a filter. With a sufficiently narrow filter, the implementation may switch from approximate to exact search [4–5].

[assumption] If an attachment is divided into ten semantic fragments, store them as ten search documents with a common attachment identifier. Multiple vectors cannot be treated simply as multiple values of one `KnnFloatVectorField` [4].

[docs] HNSW is available through vector-index formats. `Lucene104HnswScalarQuantizedVectorsFormat` has graph-connectivity and construction-queue settings; the format API is marked experimental [7].

[docs] `ScalarEncoding` lists 1, 2, 4, 7, and 8 bits per component; 1/2-bit encodings use asymmetric processing with a 4-bit query. These are capabilities of specific formats, not automatic settings for every vector field [7–8].

[assumption] For the first vector implementation, keep original precision, then compare quantization by retrieval recall. Quantized component size is not the whole index size: there is a graph and metadata, and the format may retain original vectors [7–8].

**BM25 + kNN.** [code] `TopDocs.rrf(topN, k, hits)` sums `1/(k+rank)` for a document, with ranks starting at 1. Here `k` is a smoothing constant, not the number of neighbors. Result identity is the pair `(shardIndex, doc)` [6].

[assumption] Run both searches on the same acquired `IndexSearcher`, with identical connection/availability filters; combine sufficiently broad candidate lists. RRF cannot recover a document missed by both searches. For different indexes or snapshots, first match results using their own stable keys [6, 29].

[docs] Version 10.5 also introduced `BayesianScoreQuery` and `LogOddsFusionQuery` for probabilistic score fusion [1]. [assumption] RRF is simpler to start with: it does not require calibrating comparable numerical BM25 and vector-similarity scores. I do not recommend simply adding those scores [6].

**Fragments and highlighting.** [docs] The `lucene-highlighter` module's `UnifiedHighlighter` obtains offsets from postings, term vectors, or re-analysis of source text. These are three ways to get match positions with different storage and reading costs [19].

[assumption] Keep extracted text outside the index as a recoverable source; consider positions with offsets for large fields and re-analysis for short ones. A vector result by itself contains no lexical match: it needs a saved fragment, not a promise to highlight query words [4, 19].

**Search soon after writing.** [docs] `SearcherManager` safely shares search objects between threads. Documentation recommends refreshing them in the background with `maybeRefresh()`. An old reader continues to use its snapshot [29–30].

[docs] `IndexWriter.updateDocument` replaces a document by deleting the old one and adding a new one; `deleteDocuments` is available, and changes may initially remain in buffers. `commit()` persists state to disk [30]. [assumption] Plan reader refresh for visibility separately from commit for recovery after failure.

**Size and memory.** [docs] Lucene has no universal resource requirements; they depend on document sizes, result counts, and other settings. `MMapDirectory` maps files into address space; mapped size does not mean equal Java heap use [2, 31].

[assumption] The following are arithmetic planning scenarios, not Lucene measurements or performance guarantees:

| Scenario | Calculation | Not included |
|---|---|---|
| 100,000 messages, 20 KB extracted text each | About 2 GB of text | Attachments beyond this estimate, MIME, index copies |
| 100,000 vectors of 384 float32 | 153.6 MB, about 146 MiB | HNSW, IDs, segments, text |
| 1,000,000 fragments of 384 float32 | 1.536 GB, about 1.43 GiB | Same; a different scale from 100,000 messages |
| 100,000 vectors of 768 float32 | 307.2 MB, about 293 MiB | Same |
| 384 components, 4 bits instead of 32 | Theoretically 8 times smaller for the components themselves | Original vectors and other structures |

[assumption] Plan for a disk-based index and bounded write buffer, not loading all mail into heap. Account separately for heap, physical memory for mapped files, OS cache, attachment extraction, and embedding model. Rebuild requires a second index and space for segment merges; exact multiplier is unknown [30–32].

### В2. Russian morphology, mixed text, addresses, and filenames

[code] In Lucene 10.5.2, `RussianAnalyzer` builds `StandardTokenizer → LowerCaseFilter → StopFilter → SetKeywordMarkerFilter` when exclusions exist, then `SnowballFilter(RussianStemmer)`. Stop words and stemming exclusions can be supplied externally [9].

[docs] Snowball removes endings by rules; it is not a dictionary of normal forms. Its authors explicitly warn that irregular inflection produces different stems. The `ё → е` replacement has been in the Snowball implementation since 2018-03-16 [10].

[assumption] For mail, the risk goes in two directions: forms of one word remain different, or different words receive the same stem. Preserve terms, abbreviations, contract numbers, and proper names in additional fields without stemming [9–12].

[docs] `russianmorphology` / `LuceneMorphology` uses the AOT dictionary, heuristics for unknown words, and multiple analyses for homonyms. README recommends reusing the morphology object because its data is large [13].

[code] The available POM specifies Lucene 9.3.0 / morphology 1.5, while README specifies Lucene 9.9 / 1.6. Available history shows top commit `71af7f6` from 2022-09-28. These source records disagree; the last development date cannot be established [13–15].

[assumption] Do not bring an old Lucene transitively alongside 10.x. Before choosing a dictionary-based option, check a specific commit, build, and adapter against 10.5.2. The existence of a Java library does not prove its filters are compatible with the new major version [14].

[docs] Lucene's `HunspellStemFilter` applies a dictionary and affix rules, and can emit all stems or only the longest. This is a Java Lucene implementation; native `libhunspell` is not required. `.dic` / `.aff` dictionaries are supplied separately [16].

[assumption] Do not equate Hunspell with full lemmatization: quality depends on the particular dictionary. A word listed separately for spell-checking may remain a separate search term instead of being grouped with another form [16–18].

**Examples without execution.** The results below come from published data, rules, and descriptions. `[code]` means file inspection, not running a test; manual deductions are labeled.

| Option | Short example | Basis and verification limit |
|---|---|---|
| Snowball | `письмо / письма → письм` | [code] Paired `voc.txt` / `output.txt` lines 27694, 27704, zero-based numbering [11–12] |
| Snowball | `писем → пис`, so it does not match `письмо` | [code] Line 27684 in the same files [11–12] |
| Snowball | `план / плана / планами → план` | [code] Lines 27788–27790 in the same files [11–12] |
| Snowball | `договор / договора / договорами → договор` | [assumption] Manual deduction from removing `а`/`ами`; no ready test for this triplet was found [10] |
| LuceneMorphology | `вина → вино, вина` | [docs] Explicit homonym example in README; context is not used to choose meaning [13] |
| LuceneMorphology | `ёлка / елка` treated alike for `ё/е` | [assumption] Applying the rule stated in README; exact lemma list not checked [13] |
| LuceneMorphology | `наибольший` treated as a separate word | [docs] Limitation in README; grouping with `большой` is not promised [13] |
| Hunspell + LibreOffice ru_RU | `договорами → договор` | [code] `договор/K`, rule `SFX K 0 ами`; allowable analysis read manually [17–18] |
| Hunspell + LibreOffice ru_RU | `письмами → письмо` | [code] `письмо/K`, rule `SFX K о ами`; allowable analysis read manually [17–18] |
| Hunspell + LibreOffice ru_RU | `писем → писем` | [code] Standalone entry `писем`; no rule `письмо → писем` found. Full filter output requires separate check [17–18] |
| Hunspell + LibreOffice ru_RU | `машины → машина` | [code] `машина/I`, rule `SFX I а ы`; allowable analysis shown, not the full list [17–18] |
| FTS5 unicode61 | `договор / договора / договорами` remain distinct | [code] Case normalization without Russian stemming [43] |
| FTS5 unicode61 | `письмо / писем` remain distinct | [code] No dictionary-based form reduction [43] |
| FTS5 porter | `план / планами` has no guaranteed Russian grouping | [docs] Porter is for English; it is not a Russian stemmer [21] |

[assumption] These examples show mechanisms and limitations, but do not prove comparative quality on business correspondence. Only the described LuceneMorphology cases are confirmed; complete lemmas for `договора` and `письмо` remain unknown and must not be assumed correct.

**Russian and English in one source field.** [code] The standard `RussianAnalyzer` chain does not contain an English stemmer [9]. [assumption] For an MVP, copy source text into two indexed fields: Russian with `RussianAnalyzer`, English with `EnglishAnalyzer`; search both and combine results. This is simpler than detecting the language of every word [45].

[assumption] Two fields cost additional postings and require weight tuning. Do not blindly add two scores for the same text; the best field result can be selected. An alternative is a custom chain branching by alphabet, but mixed-word handling must be checked. This applies to subject, body, participant names, and attachment text, not full addresses [9, 20].

**Addresses and files.** [docs] `UAX29URLEmailTokenizer` recognizes addresses/URLs with separate rules. `WordDelimiterGraphFilter` can split technical names while preserving the original token; documentation warns about tokenizers that discard delimiters in advance [20].

[assumption] Store full address `user@example.com` as a separate exact term with explicit normalization; analyze a person's name as text. For a file, store full name and extension separately, and make components (`договор`, `2026`, `pdf`) available for full-text search. Split the name first, then apply language filters; do not stem a full address.

### В3. SQLite FTS5 and sqlite-vec from Java

[docs] `bm25()` returns a lower score for a better match; default sort is ascending. There are column weights, `highlight()`, and `snippet()`. Built-in tokenizers are `unicode61`, `ascii`, English `porter`, and `trigram`; the latter searches substrings, not lemmas [21].

[docs] A custom tokenizer is registered through C interface `fts5_api` / `xCreateTokenizer` [44]. [code] The inspected xerial `NativeDB` supports loading extensions, but no FTS5 tokenizer-registration interface was found. A Java SQL function does not replace that API [22–23].

| Russian morphology path | Conclusion for a Java server |
|---|---|
| C extension with tokenizer | [assumption] Viable architecture via library loading, but we become responsible for builds for every OS/architecture [22–23, 44] |
| Custom JNI/FFM bridge to C API | [assumption] Possible, but beyond ordinary JDBC and not checked in this task [22, 44] |
| Precomputed Java lemmas/stems | [assumption] Index transformed text and transform queries the same way; separately store original and offset mapping [43–44] |

[assumption] The last option avoids a custom native tokenizer, but makes fragments and phrase search less straightforward: word lengths change, and multiple lemmas at one position cannot be represented losslessly as an ordinary string. That is a significant cost of the workaround.

[code] xerial includes FTS5 and supports `SQLiteConfig.enableLoadExtension(true)` with SQL `load_extension`. Custom tokenizers and other third-party extensions are not included in the JAR [23].

[docs] `sqlite-vec` is a C extension with no external dependencies; it stores float32, int8, and binary vectors. It is pre-1.0 and warns of incompatible changes. The documentation page shows version `0.1.10-alpha.4`; this is not grounds for treating it as a stable release [24].

[docs] kNN supports `vec0` and searches over ordinary tables with distance functions. `vec0` documentation describes full scans with filters and partitioning by key; no HNSW support was found for this option. Do not transfer Lucene HNSW characteristics to it [25–26].

[assumption] The practical Java path is to ship the correct-architecture `vec0.dll`, load it through xerial in every connection in use, pass vectors using SQL/BLOB, and combine FTS5/kNN results by stable `rowid`. Java is not listed among official integrations; Windows + Java 25 + specific binaries requires separate verification [23–25].

### В4. Other embedded options

| Option | Suitability and limitation |
|---|---|
| H2 Native FullText | [docs] Embedded Java option, index in special tables, updated with data. BM25 and Russian morphology are not stated [27] |
| H2 FullTextLucene | [docs] Separate Lucene integration; documentation requires 9.7.0 or a binary-compatible version. [assumption] This wrapper does not solve the task better than direct Lucene 10 [27] |
| Hibernate Search 8.4 | [docs] `Standalone POJO Mapper` works without ORM/DB; changes are supplied explicitly. Lucene backend is embedded [28] |
| Hibernate Search versions | [docs] Stable 8.4.0.Final from 2026-05-27: ordinary backend uses Lucene 9.12.3; `lucene-next` is experimental on 10.4.0. Do not treat forcing 10.5.2 as verified [28] |
| Tantivy through tantivy4java | [code] Rust engine with BM25, updates, and deletes; JNI wrapper exists. Tantivy source has a Russian stemmer despite README's incomplete language list [33–35] |
| Tantivy suitability | [assumption] Fallback: core maturity does not prove Java-wrapper maturity. Windows support and wrapper vector API are unconfirmed; the packaging example uses Linux classifier [35] |
| Elasticsearch / OpenSearch / Solr / server Quickwit | [docs] Considered deployment methods require a separate service; outside embedded constraint. Not MVP candidates [28, 33, 38] |

### В5. Incremental indexing of growing mail

[assumption] Proposed pattern below, not an approved schema. It keeps the index rebuildable from local storage and accounts for moves described in `plan.md 5.2`.

| Event / object | Proposed action |
|---|---|
| Message identifier | Own `messageKey` within `connection`; keep a separate mapping to current source addresses. `InternetMessageId` and hash are matching signals, not guaranteed unique identifiers. |
| Search document | Key from `connection`, `messageKey`, content type, attachment ID, and fragment number. Do not persist internal Lucene docID as an external key [6, 30]. |
| New message / repeated sync | Repeatable replacement by key; do not `addDocument` on every repeated event. In Lucene use `updateDocument(Term, Document)` [30]. |
| New attachment extraction | Replace only its documents. Store extraction and text-splitting versions to remove stale extra fragments. |
| Move | Keep `messageKey`, update source mapping and folder. If matching is not proven, do not merge messages solely by Message-ID header. |
| Flags / categories | If stored only in storage, do not reindex text. If used in index filters, update the index too or the filter will be stale. |
| Lucene metadata change | Numeric/Binary DocValues can be changed with specialized methods; this does not change postings or stored fields. For another schema, replace the document [30]. |
| Message deletion | Delete all its search documents by common key, including attachments/vectors; delete only the folder link if another valid link remains. |
| Deletion visibility | Refresh reader with deletes applied; disk space is freed later during merges. Until refresh, an old snapshot may show old state [29–30]. |
| SQLite | Update ordinary tables, FTS5, and vector table consistently in one transaction; external-content FTS5 does not track the source table automatically, so triggers/explicit writes are needed [21, 26]. |

[assumption] Commit sync state after events and original content are safely stored. Update the index using repeatable operations with a version marker. Lucene commit and writing to separate storage are not atomic together: after a failure, unfinished events must be replayed or the index rebuilt [30].

[assumption] When analyzer, dictionary, `ё` normalization, stop words, or field schema changes, increment `indexSchemaVersion` / `analyzerVersion`. Build a new index directory, catch up changes, check completeness, and switch active generation. Close the old one after readers release it. Rebuilding from local text does not require another mailbox sign-in [9, 29–30].

[assumption] Version model and vector dimension separately. A model change requires recalculating embeddings, but not necessarily extracting text again or changing the BM25 index. Model selection belongs in RT-07.

### В6. Licenses for engines and considered dependencies

[code] This checks license labels and texts in primary sources; it is not a legal opinion. The components under consideration are listed; the complete dependency set of a future build is not yet known. Check it against pinned artifacts and their NOTICE files.

| Component | License / separate condition | Source |
|---|---|---|
| Lucene core, analysis-common, highlighter, queryparser | Apache-2.0; retain applicable third-party NOTICE files | [36] |
| Snowball inside Lucene | Three-clause BSD; text included in Lucene LICENSE | [36] |
| russianmorphology | Apache-2.0 according to README/POM; verify provenance of the specific dictionary data set separately | [13–14] |
| Lucene HunspellStemFilter | Apache-2.0 as part of Lucene; do not confuse with native Hunspell license | [16, 36] |
| LibreOffice ru_RU `.dic` / `.aff` | Permissive BSD-like license of its own: retain notices, mark modifications, do not use author's name for promotion | [37] |
| SQLite / FTS5 | Public domain, no separate FTS5 license | [39] |
| xerial sqlite-jdbc | Apache-2.0; inherited parts are BSD-2-Clause (`LICENSE.zentus`) | [23, 40] |
| sqlite-vec | Dual MIT / Apache-2.0; MIT text checked | [24, 41] |
| H2 | Choose MPL-2.0 or EPL-1.0; Lucene, if used, has its own notices | [42, 36] |
| Hibernate Search 8.4 | Apache-2.0; backend inherits Lucene dependencies | [28] |
| Tantivy / tantivy4java | MIT / Apache-2.0 respectively; transitive Rust/JNI components not audited | [33, 35] |
| Elasticsearch / OpenSearch / Solr / Quickwit | Not candidates: server libraries are not proposed as dependencies; distribution licenses were not researched here | [38] |

## Comparison tables

In the tables, `[docs]` / `[code]` refer to checked capabilities; suitability and risk assessments are `[assumption]`. `?` marks a specific gap described after the recommendation.

| Engine | BM25 | Vectors | Russian morphology | Embedded in JVM | Updates/deletes | License | Maturity / activity | Source |
|---|---|---|---|---|---|---|---|---|
| Lucene 10.5.2 | Default | HNSW, quantization, RRF | RussianAnalyzer, Hunspell | Yes, Java | Replacement, deletes, NRT | Apache-2.0 + notices | [docs] Released 2026-10-04; mature core, some APIs experimental | [1–9, 16, 29–30, 36] |
| SQLite FTS5 + sqlite-vec | Yes, lower score is better | Extension, full scan; HNSW unconfirmed | Additional processing needed | Yes, JDBC + native libraries | SQL; coordinate FTS and vec explicitly | Public domain + Apache/BSD + MIT/Apache | [docs] SQLite mature, sqlite-vec pre-1.0; Java combination ? | [21–26, 39–41] |
| H2 Native FullText | Not stated | Not in FT API reviewed | No ready option stated | Yes, Java | Index updated with tables | MPL-2.0 / EPL-1.0 | [docs] Supported H2 feature; not a specialized search stack | [27, 42] |
| H2 FullTextLucene | Through Lucene; wrapper tuning ? | Available through wrapper ? | Wrapper tuning ? | Yes | On table changes | H2 + Apache-2.0 | [docs] Documentation tied to Lucene 9.7, not 10.x | [27] |
| Hibernate Search 8.4 + Lucene | Through backend | kNN in DSL | Through Lucene analyzers | Yes, without ORM/DB | Explicit Standalone Mapper operations | Apache-2.0 | [docs] 2026-05-27; 10.4 backend experimental | [28] |
| Tantivy + tantivy4java | Yes | Java capabilities ? | Russian in core; JNI setup ? | JNI; Windows distribution ? | Delete and reindex | MIT + Apache-2.0 | [code] Active core; wrapper maturity not established separately | [33–35] |

| Morphology option | Approach (stemming/dictionary) | Quality (examples) | Dependencies | License | Activity |
|---|---|---|---|---|---|
| RussianAnalyzer / Snowball | Ending rules, stop words | [code] `письмо/письма` together, `писем` separately; see В2 | lucene-analysis-common | Apache-2.0 + BSD Snowball | [code] Included in Lucene 10.5.2; no separate project needed [9–12, 36] |
| russianmorphology / LuceneMorphology | AOT dictionary + heuristics | [docs] `вина → вино, вина`; context does not resolve; more examples in В2 | Dictionaries, morph/russian; Lucene 10 adapter ? | Apache-2.0 per project; verify data set | [code] README/POM versions differ, commit recency ? [13–15] |
| Hunspell + LibreOffice ru_RU | Dictionary + affix rules | [code] `письмами → письмо` analysis exists, `писем` separate; see В2 | lucene-analysis-common + `.aff/.dic`; no libhunspell | Apache-2.0 + separate dictionary license | [docs] Lucene supports filter; dictionary README describes old changes dated 2012-08-24, dictionary freshness ? [16–18, 37] |
| FTS5 unicode61 / porter | No Russian morphology; porter for English | [docs] Three Russian cases in В2; forms not guaranteed to group | SQLite / sqlite-jdbc; additional Russian analysis | Public domain + JDBC licenses | [code] Included in FTS5; no built-in Russian stemmer [23, 43] |

## Recommendation

[assumption] Main candidate: **Lucene 10.5.2**, initial Russian morphology: **RussianAnalyzer / Snowball**. For English, use a parallel `EnglishAnalyzer` field; for addresses, identifiers, and full filename, use fields without language stemming. Basis: В1–В3; advantage is minimal custom glue in a Java server.

[assumption] Main engine risk is maintaining schema, consistency recovery, and index updates ourselves. Main morphology risk is missing irregular forms and no proven quality on mail queries. Having a stemmer satisfies technical availability but does not confirm that “good morphology” works in practice.

[assumption] If control queries show significant misses, first compare Hunspell with a pinned dictionary; consider LuceneMorphology after checking its 10.x adapter and data provenance. Do not move to old Lucene solely for unverified lemmatization. Choose SQLite instead of Lucene if a unified transactional database matters more than language-analysis complexity and extension distribution; that alternative needs separate Java/Windows checks.

## What could not be verified

- Actual size, memory, speed, and quality: measurements were prohibited by the brief. During implementation, a developer needs an anonymized corpus with Russian/English queries and long attachments.
- Exact LuceneMorphology lemmas for `договор` and `письмо/писем`: no ready primary examples were obtained. Check data at a specific commit; do not count this as a verified dictionary advantage.
- Current state of russianmorphology: README, POM, and available history disagree. Before adding the dependency, obtain a consistent commit and check compatibility with Lucene 10.5.2.
- Full Hunspell output, additional analyses, and unknown-word behavior: dictionary and rules were read, but the filter was not run. Check with the selected version's Russian dictionary.
- Shipping sqlite-vec through xerial on Windows/Java 25: no official Java example was verified here. Developer must check DLL loading, operations, and recovery; mailbox sign-in is not needed.
- `?` for H2/Tantivy: wrapper configuration capabilities and ready Windows libraries are unconfirmed. Investigate only if revisiting these candidates; do not treat core API as wrapper API.
- Complete transitive license inventory, AOT data provenance, and ru_RU freshness: pin artifacts/commits and check included licenses before distribution. No new code exists yet.

## What else to study

- RT-07: model, dimensions, and number of vectors per message/attachment.
- RT-08: text cleanup before external embeddings and before returning fragments.
- Separate design for reliable matching of messages across moves and multiple labels.

## Sources

All sources were read on **2026-10-24**. For mutable pages without a publication date, the version/branch is given; absence of a date does not imply a recent update.

1. [Lucene Downloads](https://lucene.apache.org/core/downloads) and [Core News](https://lucene.apache.org/core/corenews.html) — 10.5.2, released 2026-10-04; 10.5.0 — 2026-06-25.
2. [Lucene 10.5.2 System Requirements](https://lucene.apache.org/core/10_5_2/SYSTEM_REQUIREMENTS.html) — Java requirements and resource caveat.
3. [Lucene FAQ: default similarity](https://cwiki.apache.org/confluence/spaces/LUCENE/pages/119540990/LuceneFAQ) and [Similarities 10.5.2](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/search/similarities/package-summary.html) — BM25 and parameters.
4. [KnnFloatVectorField 10.5.2](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/document/KnnFloatVectorField.html).
5. [KnnFloatVectorQuery 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/search/KnnFloatVectorQuery.html) — filtering and exact-search selection.
6. [TopDocs.java, releases/lucene/10.5.2](https://raw.githubusercontent.com/apache/lucene/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/search/TopDocs.java) — `rrf` method, keys, and formula.
7. [Lucene104HnswScalarQuantizedVectorsFormat 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/codecs/lucene104/Lucene104HnswScalarQuantizedVectorsFormat.html).
8. [ScalarEncoding 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/util/quantization/QuantizedByteVectorValues.ScalarEncoding.html).
9. [RussianAnalyzer.java, releases/lucene/10.5.2](https://raw.githubusercontent.com/apache/lucene/releases/lucene/10.5.2/lucene/analysis/common/src/java/org/apache/lucene/analysis/ru/RussianAnalyzer.java).
10. [Snowball: Russian stemming algorithm](https://snowballstem.org/algorithms/russian/stemmer.html) — limitations and `ё` change dated 2018-03-16.
11. [snowball-data/russian/voc.txt, master](https://raw.githubusercontent.com/snowballstem/snowball-data/master/russian/voc.txt) — input word forms.
12. [snowball-data/russian/output.txt, master](https://raw.githubusercontent.com/snowballstem/snowball-data/master/russian/output.txt) — expected stems on the same lines.
13. [russianmorphology README, master](https://raw.githubusercontent.com/AKuznetsov/russianmorphology/master/README.md) — versions 9.9/1.6, AOT, homonyms, limitations.
14. [russianmorphology/pom.xml, master](https://github.com/AKuznetsov/russianmorphology/blob/master/pom.xml) — versions 9.3.0/1.5, license.
15. [russianmorphology: master history](https://github.com/AKuznetsov/russianmorphology/commits/master/) — available top commit `71af7f6`, 2022-09-28; differs from README.
16. [HunspellStemFilter 10.3.1](https://lucene.apache.org/core/10_3_1/analysis/common/org/apache/lucene/analysis/hunspell/HunspellStemFilter.html) — dictionary API and multiple stems.
17. [LibreOffice ru_RU.dic, master](https://raw.githubusercontent.com/LibreOffice/dictionaries/master/ru_RU/ru_RU.dic) — entries `договор/K`, `письмо/K`, `писем`, `машина/I`.
18. [LibreOffice ru_RU.aff, master](https://raw.githubusercontent.com/LibreOffice/dictionaries/master/ru_RU/ru_RU.aff) — K and I rules.
19. [UnifiedHighlighter 10.4.0](https://lucene.apache.org/core/10_4_0/highlighter/org/apache/lucene/search/uhighlight/UnifiedHighlighter.html) — offset sources, fragments, Builder.
20. [UAX29URLEmailTokenizer 10.3.1](https://lucene.apache.org/core/10_3_1/analysis/common/org/apache/lucene/analysis/email/UAX29URLEmailTokenizer.html) and [WordDelimiterGraphFilter 10.3.1](https://lucene.apache.org/core/10_3_1/analysis/common/org/apache/lucene/analysis/miscellaneous/WordDelimiterGraphFilter.html).
21. [SQLite FTS5](https://www.sqlite.org/fts5.html) — ranking, tokenizers, snippets, C API, and external-content tables; mutable documentation.
22. [xerial NativeDB.java, master](https://raw.githubusercontent.com/xerial/sqlite-jdbc/master/src/main/java/org/sqlite/core/NativeDB.java) — JNI API inspection.
23. [xerial README](https://github.com/xerial/sqlite-jdbc) and [USAGE.md, master](https://raw.githubusercontent.com/xerial/sqlite-jdbc/master/USAGE.md) — support, native libraries, and extensions.
24. [sqlite-vec README, main](https://raw.githubusercontent.com/asg017/sqlite-vec/main/README.md) and [official documentation](https://alexgarcia.xyz/sqlite-vec/) — pre-1.0 status, formats, and integration list.
25. [sqlite-vec KNN queries](https://alexgarcia.xyz/sqlite-vec/features/knn.html) — `vec0` and ordinary tables.
26. [sqlite-vec site/features/vec0.md, main](https://raw.githubusercontent.com/asg017/sqlite-vec/main/site/features/vec0.md) — filters, full scan, partitioning by key.
27. [H2 Tutorial: Fulltext Search](https://h2database.com/html/tutorial.html#fulltext) — two options, updates, Lucene 9.7.0 dependency.
28. [Hibernate Search releases](https://hibernate.org/search/releases/), [8.4 series](https://hibernate.org/search/releases/8.4/) and [Reference 8.4.0.Final](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/) — 2026-05-27, Standalone Mapper, backend, license.
29. [SearcherManager 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/search/SearcherManager.html).
30. [IndexWriter 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/index/IndexWriter.html) — operations, commit, and DocValues.
31. [MMapDirectory 10.5.0](https://lucene.apache.org/core/10_5_0/core/org/apache/lucene/store/MMapDirectory.html).
32. [IndexWriterConfig 10.5.2](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexWriterConfig.html) — write-buffer management.
33. [Tantivy repository](https://github.com/quickwit-oss/tantivy) — BM25, updates, deletes, MIT; mutable README.
34. [Tantivy src/tokenizer/stemmer.rs, main](https://raw.githubusercontent.com/quickwit-oss/tantivy/main/src/tokenizer/stemmer.rs) — `Language::Russian`.
35. [tantivy4java repository](https://github.com/indextables/tantivy4java) — JNI, Linux classifier example, Apache-2.0; mutable README.
36. [Lucene LICENSE.txt, releases/lucene/10.5.2](https://raw.githubusercontent.com/apache/lucene/releases/lucene/10.5.2/LICENSE.txt) — Apache-2.0 and third-party licenses, including Snowball.
37. [LibreOffice ru_RU README](https://raw.githubusercontent.com/LibreOffice/dictionaries/master/ru_RU/README_ru_RU.txt) — dictionary license, change dated 2012-08-24.
38. [Solr website](https://solr.apache.org/), [OpenSearch documentation](https://docs.opensearch.org/latest/vector-search/optimizing-storage/lucene-scalar-quantization/) — server products; Elasticsearch/Quickwit are also described in [28, 33].
39. [SQLite Copyright](https://www.sqlite.org/copyright.html) — public domain.
40. [xerial LICENSE.zentus](https://raw.githubusercontent.com/xerial/sqlite-jdbc/master/LICENSE.zentus) — two-clause BSD.
41. [sqlite-vec LICENSE-MIT](https://raw.githubusercontent.com/asg017/sqlite-vec/main/LICENSE-MIT).
42. [H2 License](https://h2database.com/html/license.html) — MPL-2.0 / EPL-1.0.
43. [SQLite ext/fts5/fts5_tokenize.c, master](https://raw.githubusercontent.com/sqlite/sqlite/master/ext/fts5/fts5_tokenize.c) — built-in tokenizers and normalization.
44. [SQLite ext/fts5/fts5.h, master](https://raw.githubusercontent.com/sqlite/sqlite/master/ext/fts5/fts5.h) — tokenizer C API and token positions.
45. [EnglishAnalyzer.java, releases/lucene/10.5.2](https://raw.githubusercontent.com/apache/lucene/releases/lucene/10.5.2/lucene/analysis/common/src/java/org/apache/lucene/analysis/en/EnglishAnalyzer.java) — English chain with PorterStemFilter.

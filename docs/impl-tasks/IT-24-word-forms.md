# IT-24 — User word forms file (Д12)

Prepared: 2026-10-27. Baseline: `d725d93`, plus accepted IT-23. Implement after IT-23; before
IT-25. See the batch order in this directory's README.

## Goal

Snowball does not join irregular Russian forms (`письмо`/`писем`, `человек`/`люди`): the control
set finds 0/7 of them. Let the user, or an agent on the user's behalf, list such groups in an
optional plain-text file in the data directory. Listed words are indexed and searched as one
stem. Without the file nothing changes and no reindex is needed. There is no built-in list and
no Hunspell (user decisions, plan.md section 5).

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.5, 3.7 and Д12 in section 4,
  the Д12 row of section 5.
- `docs/build-config-agent.md` (Server settings, Reindexing), `docs/storage-agent.md`
  (Lucene catalog and reconciliation), `docs/tools-agent.md` (Search),
  `docs/architecture-agent.md` (Source layout).
- `src/main/java/mail/mcp/index/MailAnalyzers.java`, `MailIndex.java`, `MailIndexReader.java`,
  `SearchSnippets.java` in the same directory.
- `src/main/java/mail/mcp/config/MailMcpProperties.java`, `SyncConfig.java` in the same
  directory; `src/main/resources/application.yml`.
- `src/main/java/mail/mcp/sync/SyncService.java`, `src/main/java/mail/mcp/tools/SearchTools.java`.
- `src/main/java/mail/mcp/cli/MailReindexCommand.java`, `MailReprocessCommand.java`,
  `MailSyncCommand.java` in the same directory; `src/main/java/mail/mcp/MailMcpServerApplication.java`.
- `src/test/java/mail/mcp/index/SearchControlSetTest.java`, `MailIndexTest.java` in the same
  directory, `src/test/java/mail/mcp/tools/SearchToolsTest.java`,
  `src/test/java/mail/mcp/cli/MailReindexCommandTest.java`,
  `src/test/java/mail/mcp/config/MailMcpPropertiesTest.java`.
- `testdata/search/README.md` and the `ru-irregular` queries in `testdata/search/queries.json`.

## What to build

1. **Setting.** Add `wordFormsFile` to `MailMcpProperties` with environment variable
   `MAIL_MCP_WORD_FORMS_FILE`, `resolvedWordFormsFile()` defaulting to
   `<data-dir>/word-forms.txt`, a line under `mail-mcp:` in `application.yml`, and support in
   `fromEnvironment`. Keep the existing constructors working.
2. **File format, `index/WordForms`.** A record holding the parsed mapping, with
   `static WordForms none()`, `static WordForms load(Path file)` and `boolean empty()`.
   - A missing file gives `none()`. The file is UTF-8 (a leading BOM is ignored).
   - `#` starts a comment to the end of the line; blank lines are ignored.
   - Every other line is one group: words separated by whitespace. Normalize each word with
     `toLowerCase(Locale.ROOT)` and `ё`→`е`. A word must consist of letters only (`\p{L}+`).
   - The canonical stem of a group is the Snowball `RussianStemmer` stem of its first normalized
     word. Every word of the group maps to that stem.
   - Errors raise `IllegalArgumentException` with the message
     `word-forms.txt line <n>: <reason>`, using the actual file name: a word with non-letters,
     a group with fewer than two distinct words, a word already listed in another group (name
     both line numbers). Repeating a word inside one group is allowed.
   - `hash()`: lowercase hex SHA-256 of the UTF-8 lines `<word>\t<stem>\n` sorted by word;
     `null` for an empty mapping. Comments, order and spacing do not change it.
   - `source(String normalizedWord)`: the line number of the rule, for `mail-analyze`.
3. **Filter, `index/WordFormsFilter`.** A `TokenFilter` placed after `LowerCaseFilter` and
   `StopFilter`, before `SnowballFilter`. For a token that is not already a keyword, look up its
   `ё`→`е` normalized text; on a hit replace the term with the stem and set `KeywordAttribute`
   so Snowball leaves it unchanged. Offsets stay untouched.
4. **Analyzers.** `MailAnalyzers.analyzer(WordForms forms)`. For an empty mapping return the
   existing analyzer unchanged (keep `analyzer()` as that). Otherwise build the same per-field
   analyzer, except that the default Russian analyzer is replaced by an equivalent chain
   `StandardTokenizer` → `LowerCaseFilter` → `StopFilter(RussianAnalyzer.getDefaultStopSet())`
   → `WordFormsFilter` → `SnowballFilter(RussianStemmer)` with `normalize` lowercasing as
   `RussianAnalyzer` does, and the `attachmentName` parts analyzer also gets `WordFormsFilter`
   before Snowball. English and exact fields are unchanged. Cache the analyzer per hash if
   convenient; nothing else needs caching.
5. **Index commit data.** `MailIndex.openWriter(connectionDir, recreate, WordForms forms)`;
   keep the existing overloads with `WordForms.none()`. The writer uses
   `MailAnalyzers.analyzer(forms)`. Every commit writes `schema` and, when `forms.hash()` is not
   null, `wordForms=<hash>`. An existing index is current only when its schema matches and its
   `wordForms` entry equals `forms.hash()` (both absent counts as equal). A non-current index
   is recreated on open exactly like an old schema, so the existing reconciliation rebuilds it
   from disk.
6. **Reader.** `MailIndexReader.open(connectionDir, WordForms forms)`; keep `open(connectionDir)`
   with `none()`. Text queries and `SearchSnippets` use `MailAnalyzers.analyzer(forms)` of the
   reader. Add `boolean wordFormsCurrent()`: the commit's `wordForms` entry equals the reader's
   `forms.hash()`. `current()` keeps its schema-only meaning, so `listMessages`, `getThread`
   and date-only search keep working after the file changes.
7. **Server.** `SearchTools` and `SyncService` load the file **on every call** through a
   `Supplier<WordForms>`, so edits apply without a restart. Spring wiring passes
   `() -> WordForms.load(properties.resolvedWordFormsFile())`; the existing test constructors
   default to `WordForms::none`.
   - A text `search` loads the forms, opens the reader with them and, after the schema check,
     throws `IllegalStateException("The word forms list changed since the index was built; run
     'mail-reindex <connection>' in a terminal or start a sync to rebuild it")` when
     `!wordFormsCurrent()`. Date-only search does not load the file.
   - `SyncService.start` loads the forms before opening the writer; an invalid file is an
     `IllegalArgumentException` from the tool, before any sign-in.
8. **Commands.** `mail-sync`, `mail-reprocess` and `mail-reindex` load the forms from
   `MailMcpProperties.fromEnvironment` and pass them to `openWriter`. An invalid file prints
   `RESULT: FAILED (<the IllegalArgumentException message>)` and exits 2 before opening the
   index or signing in.
9. **`mail-analyze`.** New offline mode `cli/MailAnalyzeCommand`, dispatched in
   `MailMcpServerApplication` like the others: `mail-analyze <word>...`. It needs no
   connections, index, pandoc or network. It prints the file in use (or that none was found),
   then for every token the Russian body analyzer produces from each argument one line
   `<token> -> <stem> (<file name> line <n>)`, `<token> -> <stem> (Snowball)` or
   `<token> -> stop word`, then `RESULT: OK`. Usage errors exit 2, an invalid file prints the
   error and exits 2.

## Named tests

- `WordFormsTest.parsesGroupsCommentsBomAndYo`
- `WordFormsTest.rejectsNonLettersSingleWordGroupsAndCrossGroupRepeats`
- `WordFormsTest.hashIgnoresCommentsOrderAndSpacing`
- `WordFormsTest.missingFileIsEmptyWithoutHash`
- `MailIndexTest.emptyWordFormsKeepTheExistingAnalyzerAndCommitData`
- `MailIndexTest.wordFormsAnalyzerMatchesRussianAnalyzerForUnlistedWords`
- `MailIndexTest.changedWordFormsRecreateTheIndexOnOpen`
- `SearchControlSetTest.wordFormsFileFindsIrregularForms`
- `SearchToolsTest.changedWordFormsAskForReindexOnlyForTextSearch`
- `SearchToolsTest.wordFormsMatchAndHighlightBothDirections`
- `MailReindexCommandTest.reindexAppliesWordFormsAndRejectsAnInvalidFile`
- `MailAnalyzeCommandTest.printsStemsSourcesAndStopWords`
- `MailMcpPropertiesTest.wordFormsFileSetting`

`SearchControlSetTest.wordFormsFileFindsIrregularForms` indexes the corpus with
`src/test/resources/word-forms/control-set.txt` containing exactly the groups
`письмо писем`, `человек люди`, `ребёнок дети`, `год лет`. It requires `ru-irregular` 7/7 and
at most the current 7 false positives in total. The existing `controlSetMeetsBaseline` stays
unchanged and keeps passing without a file.

## Files

Create (repository-relative):

- `src/main/java/mail/mcp/index/WordForms.java`
- `src/main/java/mail/mcp/index/WordFormsFilter.java`
- `src/main/java/mail/mcp/cli/MailAnalyzeCommand.java`
- `src/test/java/mail/mcp/index/WordFormsTest.java`
- `src/test/java/mail/mcp/cli/MailAnalyzeCommandTest.java`
- `src/test/resources/word-forms/control-set.txt`

Change:

- `src/main/java/mail/mcp/index/MailAnalyzers.java`, `MailIndex.java`, `MailIndexReader.java`,
  `SearchSnippets.java` in the same directory
- `src/main/java/mail/mcp/config/MailMcpProperties.java`, `SyncConfig.java` in the same
  directory; `src/main/resources/application.yml`
- `src/main/java/mail/mcp/sync/SyncService.java`, `src/main/java/mail/mcp/tools/SearchTools.java`
- `src/main/java/mail/mcp/cli/MailReindexCommand.java`, `MailReprocessCommand.java`,
  `MailSyncCommand.java` in the same directory; `src/main/java/mail/mcp/MailMcpServerApplication.java`
- the test files named above
- the documents listed below

If another production file must change only to pass `WordForms` through, stop and ask.

## Documents

- `README.md`: the word forms file, a short example with general words, that synonyms widen
  results, the edit → `mail-analyze` → `mail-reindex` cycle, the new setting and mode.
- `docs/build-config-agent.md`: setting row, `mail-analyze` mode with output and exit codes,
  the forms check in `mail-sync`, `mail-reprocess`, `mail-reindex`.
- `docs/storage-agent.md`: the `wordForms` commit entry and the rebuild rule.
- `docs/tools-agent.md`: the search error when the list changed; date-only search unaffected.
- `docs/architecture-agent.md`: the new classes and mode.

Recording updates plan.md and AGENTS.md; implementation does not edit them.

## Do not

Do not add a built-in list, Hunspell or other dictionaries, multi-word synonyms
(`SynonymGraphFilter`), per-connection files, an MCP tool that edits or reads the file, file
watching, automatic reindexing from a tool call, a schema version change or new dependencies.
Do not change the `search` tool description (IT-25 does).

## Acceptance

Run `.\gradlew.bat build` and the shared checks. The user may create `word-forms.txt`, check it
with `mail-analyze`, run `mail-reindex` for each connection and search for an irregular form;
record that pending real-mail check in plan.md 2.4 without claiming it passed.

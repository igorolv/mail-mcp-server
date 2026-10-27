# IT-21 — Date selection in search and message lists (Д24)

Prepared: 2026-10-27. Baseline: `d3fd516`, plus accepted IT-22. Implement before IT-17.

## Goal

Add optional `since`/`until` to `search` and `listMessages`, and permit date-only search across
all folders of the required connection. Reuse the existing `date` LongPoint; no reindex.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.4–3.7 and Д24 in section 4.
- `docs/tools-agent.md`, `docs/storage-agent.md`, `docs/redaction-agent.md`,
  `docs/build-config-agent.md`.
- `src/main/java/mail/mcp/tools/SearchTools.java`, `MailTools.java` in the same directory.
- `src/main/java/mail/mcp/index/MailIndexReader.java`, `SearchSnippets.java`, `MailIndex.java`
  in the same directory; `src/main/java/mail/mcp/model/SearchResults.java`.
- `src/main/java/mail/mcp/api/SearchResult.java`, `ListMessagesResult.java` in the same directory.
- Existing `MailIndexReaderTest`, `SearchToolsTest`, `MailToolsTest`, `OutputRedactionTest`
  under their matching `src/test/java/mail/mcp/` packages, and `McpStdioServerTest`.

## What to build

1. Append optional string arguments `since`, `until` to both tool methods. Accept either
   strict `YYYY-MM-DD` (UTC midnight) or an ISO-8601 datetime with explicit `Z`/numeric offset.
   Reject blank strings, invalid dates, timezone-free datetimes, values outside epoch-millisecond
   range and sub-millisecond precision. No server-local timezone or natural-language parsing.
   Normalize valid values to Instant for querying/response. Put shared validation in a small
   `model/DateRange` record with a parsing factory, not a general time-expression subsystem.
2. The interval is **since inclusive, until exclusive**. Either bound can be absent. With both
   present require since < until. For October 26–27 UTC use since `2026-10-26`, until
   `2026-10-28`. Dates are the existing MIME header date, not arrival/download time. Undated
   documents match only when neither bound is supplied.
3. Apply `LongPoint.newRangeQuery` as FILTER and combine with the existing folder filter.
   Handle conversion of an exclusive upper bound to inclusive milliseconds without overflow;
   an upper bound of Long.MIN_VALUE yields no matches. Count and page using the **same** query
   and same reader. Preserve missing-date ordering without filters and tie-break by key.
4. Make `search.query` optional in the MCP schema. Null/blank query is allowed only with at
   least one date bound; otherwise retain the validation error. Enforce the 1000-character
   limit even on whitespace. Validate `field` and folder in both modes. A nonblank query made
   only of stop words remains a zero-hit text query; do not silently turn it into date-only search.
5. Text search retains BM25 score ordering, highlights and attachment attribution. Date FILTERs
   must not enter the highlighting query. Date-only search uses MatchAllDocsQuery plus filters,
   sorted newest first, undated excluded, then key; `score=0`, snippet and attachment attribution
   absent. It must not read MIME or sidecars. Omitted folder means all local folders of this
   connection; `listMessages` still requires a folder. Existing limits/defaults stay unchanged.
6. Add optional normalized `since`/`until` Instant fields to `SearchResult` and
   `ListMessagesResult`. Echo date-only query as null, omitted from JSON; keep nonblank query
   redaction. Preserve original Java overloads where useful for existing tests, without extra
   MCP annotations/tool names. Apply the current-schema check to filtered listMessages too;
   empty indexes return empty results after input validation.

## Named tests

- `DateRangeTest.parsesDatesAsUtcAndNormalizesExplicitOffsets`
- `DateRangeTest.rejectsInvalidBlankUnzonedReversedAndOverpreciseBounds`
- `DateRangeTest.handlesEpochMillisecondExtremesWithoutOverflow`
- `MailIndexReaderTest.dateFilterUsesInclusiveSinceExclusiveUntilAndExcludesUndated`
- `MailIndexReaderTest.dateFilterCombinesFolderAndTextWithExactPagedTotal`
- `MailIndexReaderTest.dateOnlySearchSortsByDateAndDoesNotReadSidecars`
- `SearchToolsTest.dateOnlyQueryRequiresAtLeastOneBound`
- `SearchToolsTest.stopWordsWithDatesRemainAnEmptyTextSearch`
- `MailToolsTest.listMessagesDateBoundsPreserveUnfilteredBehavior`
- `OutputRedactionTest.dateFiltersPreserveMatchingAndFilteredTextSnippets`
- `McpStdioServerTest.dateArgumentsAreOptionalAndDateOnlySearchWorks`

Include boundary instants, offset-equivalent times, one-sided bounds, equal dates with key
ties, a folder with no matching dates, offset beyond total, absent index and old schema.
Run the unchanged search control set as part of the full build.

## Files

Create `src/main/java/mail/mcp/model/DateRange.java` and
`src/test/java/mail/mcp/model/DateRangeTest.java`.
Change `src/main/java/mail/mcp/index/MailIndexReader.java`, the two tools and two API records
listed above, and the existing test classes named above. Existing overload callers need no
unrelated refactoring. Change only the documents below.

## Documents

Update `README.md`, `docs/tools-agent.md`, `docs/storage-agent.md`, `docs/redaction-agent.md`:
syntax, UTC date semantics, exclusive until with example, date-only mode ordering/score, query
optionality and unchanged schema. Recording updates plan.md 3.7; no changes to real-mail results.

## Do not

Do not add arrival-date fields, relative dates, a timezone setting, flags filters, sync depth,
cross-connection queries, a new tool or dependency. Do not change analyzers or snippet selection.

## Acceptance

Run `.\gradlew.bat build` and shared checks. Offline acceptance needs no sign-in. The user may
restart MCP and query a known period; document pending real-data verification separately.

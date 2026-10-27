# IT-23 — Copies of one message count once (Д4)

Prepared: 2026-10-27. Baseline: `d725d93`. Implement first in the batch; before IT-24 and IT-25.
See the batch order in this directory's README.

## Goal

The same message stored in several local folders of one connection (for example a message sent
to oneself, present in both Sent and Inbox) now appears as separate search hits and thread cards
and is counted several times. Show it once, with the other local copies listed on that card.
All work is local and read-time only: no new index field, no schema change, no reindex.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.6–3.7 and Д4 in section 4.
- `docs/tools-agent.md` (sections Search and Threads), `docs/storage-agent.md` (index fields and
  the threads section), `docs/redaction-agent.md`.
- `src/main/java/mail/mcp/index/MailIndexReader.java`, `MailIndex.java` in the same directory.
- `src/main/java/mail/mcp/tools/SearchTools.java`, `ThreadTools.java` in the same directory.
- `src/main/java/mail/mcp/model/SearchHit.java`, `SearchResults.java`, `ThreadResults.java`,
  `MessageCard.java`, `MessageFingerprint.java` in the same directory.
- `src/main/java/mail/mcp/api/SearchHitInfo.java`, `SearchResult.java`, `ThreadMessageInfo.java`,
  `GetThreadResult.java` in the same directory.
- `src/test/java/mail/mcp/index/MailIndexReaderTest.java`,
  `src/test/java/mail/mcp/tools/SearchToolsTest.java`, `ThreadToolsTest.java`,
  `OutputRedactionTest.java` in the same directory, `src/test/java/mail/mcp/McpStdioServerTest.java`.

## What to build

1. **Copy identity.** Two indexed documents of one connection are copies of one message when all
   of these hold: both have a stored `messageId` that, after `strip()`, is exactly one valid
   bracketed ID (the same `HEADER_ID` rule `thread` uses for a message's own ID); the stripped IDs
   are equal, case-sensitive; both have a stored `date` and the dates are equal; the stored `from`
   strings are equal and nonblank; the stored `subject` strings are equal and nonblank. Any
   missing or invalid part makes the document a group of its own. This mirrors the move heuristic
   (plan.md 3.6): duplicate Message-IDs with different metadata stay distinct. Failed messages
   have no headers and are never grouped. Do not compare bodies, sizes, folders or flags.
2. **Search.** In `MailIndexReader.search`, group the complete set of matching documents (after
   folder and date filters) before paging:
   - Collect all matching documents in the existing order: score descending then `keySort` for
     text queries; date descending (undated last) then `keySort` for date-only queries. Reading
     the small stored fields `key`, `messageId`, `date`, `from`, `subject` of every matching
     document once per call is acceptable, as `thread` already does; load full cards only for
     the returned page.
   - A group's position is that of its first member in this order; that member is the
     representative. Its card, score and snippet are returned. The other members, in the same
     order, become its copies.
   - `total` is the exact number of groups. `offset`, `limit` and `nextOffset` count groups.
   - A folder filter limits the groups to matching documents in that folder, so copies are
     listed only when they match the query and filters themselves.
3. **Threads.** In `MailIndexReader.thread`, after finding the connected component, group its
   members by the same identity. Order groups by the representative's date ascending (undated
   last) then key; the representative is the first member in that order (the smallest key, since
   copies share a date). `total` counts groups; paging counts groups.
4. **Model.** Add `model/MessageCopy(String key, String folderKey)`. Add a final component
   `List<MessageCopy> copies` to `SearchHit`, keeping a constructor with the current five
   arguments that passes `List.of()`. Change `ThreadResults` to
   `ThreadResults(int total, List<MessageCard> cards, Map<String, List<MessageCopy>> copies)`,
   keyed by representative key, keeping a two-argument constructor that passes `Map.of()`.
5. **Wire contract.** Add `api/MessageCopyInfo(String key, String folder)` with `@Schema`
   descriptions like the neighbouring records. Add a final component
   `List<MessageCopyInfo> copies` to `SearchHitInfo` and `ThreadMessageInfo`, annotated
   `@JsonInclude(JsonInclude.Include.NON_EMPTY)`, `requiredMode = NOT_REQUIRED`, described as
   "Other local copies of this message (same Message-ID, date, sender and subject) among the
   results; absent when none." Update the `total` descriptions of `SearchResult` and
   `GetThreadResult`: copies of one message count once. Keys and folder keys in copies are
   never passed through `SecretRedactor`.
6. **Tool descriptions.** Add one sentence to the `search` and `getThread` descriptions:
   copies of one message in several folders are returned once, with the others under `copies`,
   and totals count them once. Change nothing else in the descriptions (IT-25 rewrites the
   search hints).
7. `listMessages`, `listFolders` counts, `getMessage`, `getAttachment` and sync are unchanged.

## Named tests

- `MailIndexReaderTest.searchGroupsCopiesBeforePagingWithExactTotal`
- `MailIndexReaderTest.searchKeepsDistinctMessagesThatShareAMessageId`
- `MailIndexReaderTest.dateOnlySearchGroupsCopiesInDateOrder`
- `MailIndexReaderTest.folderFilteredSearchListsOnlyMatchingCopies`
- `MailIndexReaderTest.threadGroupsCopiesAndCountsThemOnce`
- `SearchToolsTest.searchReturnsCopiesWithKeysAndFolders`
- `ThreadToolsTest.threadReturnsCopiesWithKeysAndFolders`
- `OutputRedactionTest.copiesKeepKeysAndFolderKeysUnfiltered`
- `McpStdioServerTest.copiesAreOptionalInSearchAndThreadSchemas`

Use synthetic messages and temporary indexes. Cover: three copies in three folders whose
representative is on the second page while another group fills the first page; the same
Message-ID with a different date, sender or subject; missing and malformed Message-IDs; a failed
message; copies in a different connection staying separate; equal scores resolved by key.

## Files

Create (repository-relative):

- `src/main/java/mail/mcp/model/MessageCopy.java`
- `src/main/java/mail/mcp/api/MessageCopyInfo.java`

Change:

- `src/main/java/mail/mcp/index/MailIndexReader.java`
- `src/main/java/mail/mcp/model/SearchHit.java`, `ThreadResults.java` in the same directory
- `src/main/java/mail/mcp/api/SearchHitInfo.java`, `SearchResult.java`, `ThreadMessageInfo.java`,
  `GetThreadResult.java` in the same directory
- `src/main/java/mail/mcp/tools/SearchTools.java`, `ThreadTools.java` in the same directory
- the test files named above
- the documents listed below

## Documents

Update `README.md` (search and getThread), `docs/tools-agent.md` (Search, Threads: grouping rule,
representative, totals, copies field), `docs/storage-agent.md` (the threads paragraph that says
copies retain separate cards; no index change), `docs/redaction-agent.md` (copies stay
unfiltered). Recording updates plan.md; implementation does not edit it.

## Do not

Do not add an index field, doc values, a schema change, a grouping parameter or setting,
grouping across connections, grouping in `listMessages`, content or size comparison, Lucene
grouping/collapsing modules or new dependencies. Do not merge or delete stored copies.

## Acceptance

Run `.\gradlew.bat build` and the shared checks. Offline acceptance is enough; the user may later
search for a message they sent to themselves and see one hit with a copy. Record that pending
real-mail check in plan.md 2.4 without claiming it passed.

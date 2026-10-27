# IT-17 — Local message threads (Д5)

Prepared: 2026-10-27. Baseline: `d3fd516`, plus accepted IT-21 and IT-22.
Implement after IT-21; before IT-18 and IT-19. See the batch order in this directory's README.

## Goal

Add `getThread` to read a conversation across folders of one connection using the already
stored Message-ID, In-Reply-To and References. All work is local. No schema change or reindex.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.4–3.7 and Д5 in section 4.
- `docs/architecture-agent.md`, `docs/tools-agent.md`, `docs/storage-agent.md`,
  `docs/redaction-agent.md`, `docs/build-config-agent.md`.
- `src/main/java/mail/mcp/index/MailIndexReader.java`, `MailIndex.java` in the same directory.
- `src/main/java/mail/mcp/tools/MailTools.java`, `SearchTools.java` in the same directory.
- `src/main/java/mail/mcp/model/MailMessage.java`, `MessageCard.java` in the same directory.
- `src/main/java/mail/mcp/api/MessageSummary.java`, `SearchHitInfo.java` in the same directory.
- `src/main/java/mail/mcp/mime/MimeReader.java` (header extraction only).
- `src/test/java/mail/mcp/index/MailIndexReaderTest.java`,
  `src/test/java/mail/mcp/tools/OutputRedactionTest.java`,
  `src/test/java/mail/mcp/McpStdioServerTest.java`.

## What to build

1. Add `ThreadTools.getThread(connection, key, offset, limit)` with the normal read-only,
   non-destructive, idempotent annotations and output schema. `connection` and local UUID `key`
   are required. Offset defaults to 0; limit to 50, capped at 200. Reject negative offsets,
   nonpositive limits, invalid UUIDs and unknown connections before reading the index.
2. Anchor by local key, so callers can use list/search results without obtaining a raw header
   through the optional redactor. Look up the anchor in one committed `MailIndexReader` snapshot.
   Missing index/anchor: `IllegalArgumentException("Message is not indexed; use listMessages")`.
   Reject an old schema with the existing search rebuild message. A known card with no usable
   threading headers yields a singleton, including a failed message.
3. Build an undirected graph from **stored header fields only**, for live Lucene documents in
   this connection. It is acceptable to scan these small fields once per call; do not load
   bodies, attachment text, MIME or every complete stored Document. Select only key, messageId,
   inReplyTo and references, then fetch card fields for the returned page.
   Each local key is a node; each usable header ID is a separate node. Link the key to its own
   Message-ID and all IDs in In-Reply-To/References. Return every message key in the anchor's
   connected component. This connects descendants, ancestors and siblings even when the
   referenced parent itself is absent locally. Traverse with visited sets; cycles terminate.
4. ID matching is case-sensitive. Trim surrounding whitespace; extract angle-bracketed,
   nonempty IDs without whitespace or nested brackets. For Message-ID require exactly one
   such ID; malformed/missing values contribute no own ID. Extract all valid IDs from reply
   headers; deduplicate repeated IDs. Do not lowercase IDs or guess links from subject,
   participants, dates, Exchange ConversationId or Gmail identifiers.
5. Keep copies with different local keys as separate cards (Д4 is not this task). Sort the
   complete component by date ascending, undated last, then key ascending. Page **after** finding
   the component. `total` is exact for this snapshot, not the page length. Offset beyond the
   end returns an empty list and no nextOffset. Pagination across calls has the same concurrent
   sync limitations as listMessages; do not add snapshot tokens or caches.
6. Add `GetThreadResult(connection, key, total, offset, nextOffset, messages)` and
   `ThreadMessageInfo(key, folder, date, from, subject, hasAttachments, state, localPath)` in
   `api/`; `folder` is a local folder key. Use `model/ThreadResults` for internal total/cards.
   Include no bodies or raw header IDs in the wire result. Redact displayed subject/from with
   `SecretRedactor`; preserve keys, paths, dates and folder keys. Reading must work while the
   writer lock is held and without pandoc/source construction.

## Named tests

- `MailIndexReaderTest.threadFindsAncestorsDescendantsAndMissingParentSiblings`
- `MailIndexReaderTest.threadCyclesAndRepeatedIdsTerminate`
- `MailIndexReaderTest.threadKeepsCopiesAndDoesNotMatchSubjectOrIdCase`
- `MailIndexReaderTest.threadSingletonMalformedHeadersAndDeletedDocuments`
- `MailIndexReaderTest.threadPagesChronologicallyWithUndatedLast`
- `ThreadToolsTest.threadValidatesArgumentsAnchorAndSchema`
- `ThreadToolsTest.threadUsesOneConnectionAndCommittedLocalDataOnly`
- `OutputRedactionTest.threadRedactsDisplayFieldsAndPreservesLookupValues`
- `McpStdioServerTest.threadToolHasSchemaAndReturnsStructuredCards`

Use synthetic messages and temporary indexes. Include a chain whose connecting message would
fall outside the requested page, duplicate Message-IDs across folders, and an equal ID in a
different connection. Retain all existing stdio assertions and update the advertised tool count.

## Files

Create (paths below are repository-relative):

- `src/main/java/mail/mcp/tools/ThreadTools.java`
- `src/main/java/mail/mcp/api/GetThreadResult.java`
- `src/main/java/mail/mcp/api/ThreadMessageInfo.java`
- `src/main/java/mail/mcp/model/ThreadResults.java`
- `src/test/java/mail/mcp/tools/ThreadToolsTest.java`

Change `src/main/java/mail/mcp/index/MailIndexReader.java` and the existing test files named
above. Change only the documents listed below. No other production files need changing.

## Documents

Update `README.md`, `docs/tools-agent.md`, `docs/architecture-agent.md`,
`docs/storage-agent.md`, `docs/redaction-agent.md`: nine tools, graph rules, ordering,
local-only behavior and unchanged schema. Recording updates plan.md 3.7 and the tool count in
AGENTS.md; implementation does not edit those files.

## Do not

Do not add persisted threads, a new index field, subject heuristics, deduplication, cross-account
queries, remote EWS conversation operations, full thread bodies, new dependencies or settings.

## Acceptance

Run `.\gradlew.bat build` and the shared checks. No real-mail check is required for offline
acceptance; the user may inspect a known conversation after restarting the server. Record any
unperformed real-mail check in plan.md 2.4, without claiming it passed.

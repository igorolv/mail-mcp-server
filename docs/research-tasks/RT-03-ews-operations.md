# RT-03 — EWS operations reference for Exchange 2013

- **Result file:** `docs/research/RT-03-ews-operations.md`
- **Size:** M.
- **Fills:** `docs/plan.md`, sections 5.2 and 5.7 (sync and identifiers); input for the EWS source
  implementation.

## Goal

Produce a practical cheat sheet of the EWS operations our read-only server needs, as they work on
**Exchange 2013** (`RequestServerVersion` `Exchange2013_SP1`): what each does, a minimal request,
key response fields, limits and gotchas.

## What we already know

- Exchange 2013 CU23, EWS reachable, `GetFolder` for `inbox` works with
  `RequestServerVersion Exchange2013_SP1` (`docs/plan.md`, sections 3.1–3.2).
- Mail is mostly append-only; we want incremental sync and a stable message key
  (`docs/plan.md`, section 5.2). We suspect EWS `ItemId` changes when an item is moved — verify.
- Every source must deliver messages as MIME (`.eml`) (`docs/plan.md`, section 5.7).

## Questions

For each operation below give: purpose; minimal SOAP request (use placeholders, keep it short);
key response fields; paging and size limits; Exchange 2013 specifics; gotchas; link.

- **В1. Folders:** `GetFolder`, `FindFolder` (deep traversal), `SyncFolderHierarchy`. How to get
  the full folder tree with item and unread counts; distinguished folder IDs; folder classes
  (mail vs calendar vs contacts) and how to keep only mail folders.
- **В2. Listing:** `FindItem` with `IndexedPageItemView` — page size limits, sorting, the
  `EWSFindCountLimit` default, which properties are available without `GetItem`.
- **В3. Incremental sync:** `SyncFolderItems` — `SyncState`, `MaxChangesReturned` limit,
  `IncludesLastItemInRange`, change types (create, update, delete, read-flag change), what happens
  when the sync state is lost or too old.
- **В4. Message content:** `GetItem` with `IncludeMimeContent` — size limits, encoding of
  `MimeContent`, batching several IDs in one call, and **whether `GetItem` changes `IsRead`**
  (it must not mark mail as read; confirm).
- **В5. Attachments:** `GetAttachment` for file attachments and item attachments (attached
  messages); how to get MIME of an item attachment; large attachment limits.
- **В6. Threads:** `ConversationId`, `FindConversation`, `GetConversationItems` — what is
  available in Exchange 2013.
- **В7. Search:** `FindItem` with `Restriction` vs `QueryString` (AQS) on Exchange 2013; does it
  search attachment content; limits and indexing delays.
- **В8. Identifiers:** `ItemId` stability (move, copy, sync), `ConvertId`, `InternetMessageId`,
  `ChangeKey`; which key should our store use and why.
- **В9. Throttling and errors:** default throttling policy values relevant to EWS on Exchange 2013
  (`EWSMaxConcurrency`, `EWSPercentTimeInCAS`/`AD`/`MailboxRPC`, `EWSFindCountLimit`,
  `EWSMaxBatchSize` if it exists in 2013), `ErrorServerBusy` and `BackOffMilliseconds`,
  recommended retry behaviour.
- **В10. Read-only safety:** list every operation above and confirm it does not modify the mailbox.
  List common EWS operations we must **never** call in read-only mode.

## Where to look

- Microsoft Learn: "EWS reference for Exchange", "EWS operations", "Synchronize items by using
  EWS", "Throttling in Exchange", "EWS identifiers", "Paging search results".
- Prefer pages that state the Exchange version; note when a feature appeared after 2013.
- `ews-java-api` and DavMail sources can show real request shapes (see RT-02).

## Required tables

| Операция | Зачем нам | Только чтение? | Лимиты | Особенности Exchange 2013 | Источник |
|---|---|---|---|---|---|

Plus one short request example per operation in В1–В7 (placeholders only).

## Out of scope

- Sending requests to our server; signing in anywhere.
- Client library choice (RT-02).
- Write operations beyond listing them as forbidden in В10.

## Done when

- В1–В10 are answered and the table covers every operation named in them.
- В8 ends with a clear proposal for the message key, labelled as a recommendation.

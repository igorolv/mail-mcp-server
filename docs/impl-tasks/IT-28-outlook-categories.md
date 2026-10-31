# IT-28 — Outlook categories as labels

Plan item: Д31 (`docs/plan.md` section 4). Size S. One implementation session, no commit.

## Goal

Fill the existing provider-neutral `labels` field with the Outlook categories returned by EWS
as `item:Categories`. Read categories in the same metadata-only `GetItem` request already used
for added messages and for known messages reported by `SyncFolderItems` as `Update`.

The storage, index, search and MCP response layers already persist and expose `labels`. Reuse that
path unchanged. This task adds one EWS field to the existing observation; it does not add an EWS
operation, request, contract field, index field, schema version, command, setting or dependency.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, Д31 in `docs/plan.md` section 4 and the 2026-10-31
  scope decision in `docs/decisions.md` section 2;
- `docs/ews-source-agent.md`, especially "Shape", "Mail source" and "Flag snapshots";
- `docs/storage-agent.md`, "Disk layout" through the label observation rules, and
  `docs/tools-agent.md`, "Message labels";
- `docs/search.md`, "Labels";
- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/ews/EwsClient.java`, `EwsResponseReader.java` and
  `EwsMailSource.java`;
- `src/test/java/mail/mcp/source/ews/EwsRequestTest.java`, `EwsResponseReaderTest.java` and
  `EwsMailSourceTest.java`, plus the synthetic fixtures they use under `src/test/resources/ews/`;
- `SyncRunnerTest.labelsAreStoredAndRefreshedWithoutDownload` only to confirm that the shared
  label path is already covered. Do not duplicate or change that generic behavior.

## Fixed behavior

1. Add `item:Categories` to `EwsClient.metadata`'s `AdditionalProperties`, alongside `IsRead`
   and `Flag`. Keep `BaseShape=IdOnly`, batching at 100 item ids and the same `GetItem` call. Do
   not add categories to the MIME download request or to `SyncFolderItems`' `ItemShape`.
2. Parse `<t:Categories>` as the ordered list of its `<t:String>` children. Preserve each string
   exactly as EWS returned it: no trimming, sorting, case conversion or deduplication. In a
   successful response to this metadata request, an absent or present-empty `Categories` element
   maps to an empty requested collection (`labels.isEmpty()`). This is required so removing the
   last category clears the saved labels; the user-run removal check below verifies the real
   Exchange response. A duplicate top-level `Categories` field remains a protocol error like every
   duplicate metadata field; unknown nested elements follow the existing skip behavior.
3. Metadata for an added message carries the parsed list through `RemoteMessageInfo.labels()`.
   For a known message reported as `Update`, `EwsMailSource.observed` must copy the labels from
   the short metadata response while still dropping the fingerprint. A `ReadFlagChange` without
   `Update` carries `labels == null`, so it cannot clear stored categories.
4. The existing generic synchronization rules do the rest: a changed list atomically refreshes
   `meta.json` and the Lucene document without MIME download or conversion; an explicit empty
   list clears categories; a null list from an observation without metadata preserves the saved
   value. Counts, cursor handling, move matching, read/flag observations and retry/sign-in behavior
   remain unchanged.
5. Categories are labels, not folders and not the EWS category color definitions. Store only the
   strings attached to each message. Do not read the master category list or add a category color,
   id, filter or write operation.

## What to build

### EWS source

- `EwsClient.metadata`: request `item:Categories` in the existing metadata-only shape.
- `EwsResponseReader.readMetadataItem`: collect the category strings and pass them as `labels`
  in the existing `RemoteMessageInfo`. A successful metadata item always produces a non-null
  category list, empty when the property is absent or empty.
- `EwsMailSource.observed`: retain labels returned for known `Update` items. Continue to use null
  labels for observations supplied only by `ReadFlagChange`.
- `RemoteMessageInfo`: update only its Javadoc so it names EWS categories as another provider of
  `labels`; do not change the record or constructors.

### Tests

Use only synthetic XML and the existing scripted transport. Add or adjust a fixture with absent,
present-empty and multiple categories; it must contain no real mailbox values.

The following test names are exact:

- `EwsRequestTest.metadataRequestsReadAndFlagWithoutMime` — extend the existing test to require
  `FieldURI="item:Categories"` while still rejecting MIME, body and importance;
- `EwsResponseReaderTest.metadataReadsAbsentEmptyAndOrderedCategories` — prove absent and
  present-empty both produce an empty list, and multiple values preserve exact text and response
  order;
- `EwsMailSourceTest.knownItemCategoriesComeFromUpdatedItemsOnly` — prove an `Update` metadata
  response carries categories, while a ref observed only through `ReadFlagChange` has null labels
  and is not included in the metadata request.

The existing generic label tests remain the coverage for persistence, clearing, indexing, label
search and tool responses. Do not add an EWS-specific storage or tool path.

## Files

Change only:

- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/ews/EwsClient.java`;
- `src/main/java/mail/mcp/source/ews/EwsResponseReader.java`;
- `src/main/java/mail/mcp/source/ews/EwsMailSource.java`;
- `src/test/java/mail/mcp/source/ews/EwsRequestTest.java`;
- `src/test/java/mail/mcp/source/ews/EwsResponseReaderTest.java`;
- `src/test/java/mail/mcp/source/ews/EwsMailSourceTest.java`;
- synthetic files under `src/test/resources/ews/` that are created or changed for the named
  tests;
- the documents listed below.

If another production or test file appears necessary, stop and ask instead of expanding the task.

## Documents

Update these documents to describe the implemented current behavior:

- `README.md`: cards can carry Gmail labels or Outlook categories;
- `docs/ews-source-agent.md`: the metadata field, parsing, `Update` refresh and deferred
  real-server check;
- `docs/storage-agent.md`: EWS categories are the second current provider of neutral `labels`;
- `docs/tools-agent.md`: the same current providers and unchanged response/search contract;
- `docs/search.md`: Outlook categories are visible and searchable as labels, alongside Gmail
  labels.

Do not edit `docs/plan.md`, `docs/results.md`, `docs/decisions.md`, `AGENTS.md`, other briefs,
research files or `testdata/` during implementation. The recording session updates the plan and,
if needed, results after review.

## Do not

- Do not add EWS operations, network round trips, sign-ins, retries or mailbox writes.
- Do not change the MCP schema, Java API records, `MessageMeta`, `MessageCard`, Lucene fields,
  index schema version, search analyzer or label redaction.
- Do not request the category master list, category colors or extended MAPI properties.
- Do not add a full metadata pass over unchanged EWS messages. Only added refs and known refs in
  an `Update` use `GetItem`; `ReadFlagChange` remains local to the sync response.
- Do not normalize categories or rebuild the reverted shared MIME pipeline.

## Acceptance

Run `.\gradlew.bat build` and the shared real-values check. Offline tests are sufficient to
accept the implementation. In the recording session, remove Д31 from section 4, add it to the
completed list and add this deferred check to `docs/plan.md` section 2 with the acceptance date:

1. The user synchronizes an existing message in an included EWS folder, assigns a category in
   Outlook, synchronizes again and confirms that `labels` and `field: "label"` reflect it without
   a MIME download.
2. The user removes the category, synchronizes again and confirms that the category disappears.

This verifies that the user's Exchange server reports category edits as `Update` in
`SyncFolderItems`. Only the user runs this real-mail check. It is deferred and does not block
offline acceptance or publication of `0.1.0` under the current release decision.

## Primary references

Checked on 2026-10-31:

- [FieldURI](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/fielduri)
  lists `item:Categories` as the Categories property;
- [Categories](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/categories-ex15websvcsotherref)
  defines it as a collection of strings attached to an item;
- [Mailbox synchronization and EWS](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/mailbox-synchronization-and-ews-in-exchange)
  describes `Update` as the change type for a changed item and recommends `GetItem` for the
  properties needed after synchronization;
- [SyncFolderItems](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderitems-operation)
  documents the 512-change limit and the follow-up `GetItem` pattern for properties not supplied
  by synchronization.

The generic EWS documentation supports the design. Whether Outlook category edits produce
`Update` on the user's Exchange 2013 installation remains the explicit user-run check above.

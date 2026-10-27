# RT-03 — EWS Operations for Exchange 2013

- Date: 2026-10-24
- Author: Codex / RT-03 research session
- Brief: docs/research-tasks/RT-03-ews-operations.md
- Scope: Exchange 2013 CU23, SOAP EWS, `RequestServerVersion="Exchange2013_SP1"`.
- Method: official Microsoft documentation and RFCs; no requests were made to the mail server,
  and `.local/` was not opened. Project context was read in `docs/plan.md` and `docs/research.md`;
  those documents were not changed.

## Summary

1. [docs] All ten reviewed operations are available in Exchange 2013; `GetConversationItems`
   first appeared in 2013, and `FindConversation` in 2010 SP1. [30: operation versions][30]
2. [docs] The folder tree can be retrieved with `FindFolder Traversal="Deep"` and then maintained
   with `SyncFolderHierarchy`. This operation does not report changes limited to `TotalCount`/`UnreadCount`;
   refresh counts separately. [2: FindFolder][2], [3: SyncFolderHierarchy][3]
3. [docs] `FindItem` does not replace `GetItem`: results do not include the full body or recipient lists;
   streaming properties may be truncated. [6: FindItem][6]
4. [docs] `SyncFolderItems` runs separately for each folder, returns at most 512 changes
   and an opaque `SyncState`; `IncludesLastItemInRange` indicates completion of the traversal.
   [9: SyncFolderItems][9], [11: synchronization][11]
5. [docs] `GetItem` retrieves a message without setting `IsRead`; the official MIME example returns
   `IsRead=false`. Setting the flag and related notifications are separate write actions.
   [14: GetItem for an email message][14], [16: IsRead][16]
6. [docs] `MimeContent` is base64-encoded MIME bytes. Exchange constructs MIME from its store, so this
   is not a guarantee of a byte-for-byte original SMTP message or a complete snapshot of all EWS properties.
   [15: MimeContent][15], [17: export][17]
7. [docs] `GetAttachment` retrieves files and embedded items; MIME for an embedded message is requested
   through `AttachmentShape/IncludeMimeContent`. [18: GetAttachment][18], [20: IncludeMimeContent][20]
8. [docs] AQS `attachment:report` searches attachment names and indexed content; completeness
   depends on Exchange Search and supported formats. [24: QueryString][24], [27: formats][27]
9. [docs] `ItemId` may change on move, while `ChangeKey` represents a version;
   `ConvertId` changes identifier format. [28: identifiers][28], [29: ConvertId][29]
10. [assumption; recommendation] A stable key requires a local UUID within the
    connection namespace, with current EWS identifiers stored separately. `InternetMessageId` and a hash are useful
    for matching but do not prove instance identity; see В8 for details.

## Answers to the questions

### Common form of the SOAP examples

[docs] The content of `s:Body` is shown below. Each example uses one SOAP 1.1 envelope
with the specified namespaces and schema version. `ITEM_ID`, `FOLDER_ID`,
`ATTACHMENT_ID`, `CONVERSATION_ID`, and `SYNC_STATE` are placeholders to be replaced with values
from a server response; they are not usable identifiers. [30: EWS schema and versions][30]

```xml
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
            xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
  <s:Header>
    <t:RequestServerVersion Version="Exchange2013_SP1"/>
  </s:Header>
  <s:Body><!-- Insert one operation here. --></s:Body>
</s:Envelope>
```

[docs] Check `ResponseClass` and `ResponseCode` in every response, and on errors check
`MessageText`/`MessageXml`; HTTP 200 alone does not indicate success. A batch may contain
different results for different IDs. [31: verifying results][31]

### В1. Folders: GetFolder, FindFolder, SyncFolderHierarchy

**GetFolder.** [docs] Retrieves properties of specific folders, including by distinguished ID.
`Default` returns ID, name, and counts; explicitly add parent and class to build a tree.
Response fields include `Folders`, the concrete folder type, `FolderId/@Id`, `@ChangeKey`, `ParentFolderId`,
`DisplayName`, `FolderClass`, `TotalCount`, `UnreadCount`, and `ChildFolderCount`.
`FolderIds` can list several folders; `GetFolder` does not support paging.
In 2013, `AllProperties` does not include folder permissions unless `PermissionSet` is explicitly requested.
[1: GetFolder][1]

```xml
<m:GetFolder>
  <m:FolderShape>
    <t:BaseShape>Default</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="folder:ParentFolderId"/>
      <t:FieldURI FieldURI="folder:FolderClass"/>
    </t:AdditionalProperties>
  </m:FolderShape>
  <m:FolderIds><t:DistinguishedFolderId Id="inbox"/></m:FolderIds>
</m:GetFolder>
```

**FindFolder.** [docs] `Deep` returns descendants of the specified folder. The response contains
`RootFolder/@IncludesLastItemInRange`, `@IndexedPagingOffset`, `@TotalItemsInView`, `Folders`.
Request the next page using the returned offset; the requested size is not guaranteed.
`FindFolder` has no sort order; deep traversal is not supported for public folders.
Streaming-property limit is 512 bytes / first 255 Unicode characters.
[2: FindFolder][2], [7: paging][7]

```xml
<m:FindFolder Traversal="Deep">
  <m:FolderShape>
    <t:BaseShape>Default</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="folder:ParentFolderId"/>
      <t:FieldURI FieldURI="folder:FolderClass"/>
    </t:AdditionalProperties>
  </m:FolderShape>
  <m:IndexedPageFolderView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="msgfolderroot"/></m:ParentFolderIds>
</m:FindFolder>
```

[docs] Distinguished IDs do not depend on localized names: `inbox`, `sentitems`, `drafts`,
`deleteditems`, `junkemail`, `outbox`; `msgfolderroot` — root of the message tree, `root` —
the root of the entire mailbox. Other IDs include `calendar`, `contacts`, `tasks`, `searchfolders`,
`recoverableitemsroot`, and `archivemsgfolderroot`. The latter do not mean that an archive is enabled or
that every system folder is accessible. [4: DistinguishedFolderId][4]

[assumption; recommendation] For the MVP, “full tree” means the entire accessible user
tree under `msgfolderroot`, including the root retrieved with a separate `GetFolder`. This does not promise
to traverse public folders, Recoverable Items, or a separate archive mailbox. Preserve the tree structure
for all types, then select mail folders on the client so a parent of an excluded type is not lost.

[docs] The mail class is `IPF.Note`, calendar is `IPF.Appointment`, contacts are `IPF.Contact`,
and tasks are `IPF.Task`. Class extensions are allowed (`IPF.Note.Custom`); `SearchFolder` may also
have `IPF.Note` but represents a virtual selection of other items. Exchange treats an unknown prefix
as a generic mail folder. [5: folder types][5]

[assumption; recommendation] Sync physical mail folders by recognizing `IPF.Note`
and its subclasses; exclude `SearchFolder` regardless of class to avoid duplicates.
For a missing/unknown class, retain an explicit “verify” status, consider the XML type,
and allow manual selection. `TotalCount` counts folder items; it does not promise a count of ordinary
messages only. When items are filtered, the number of indexed messages may differ. Basis:
type and count semantics [1], [5].

**SyncFolderHierarchy.** [docs] The initial request without `SyncState` retrieves the hierarchy,
subsequent requests return `Changes/Create|Update|Delete`. The response includes a new `SyncState`,
`IncludesLastFolderInRange`, and properties of changed folders. Repeat with the new state until
`IncludesLastFolderInRange=true`. This operation has no `MaxChangesReturned`;
the reference used does not specify a numeric page maximum. Changes to counts alone
do not produce a hierarchy change. [3: SyncFolderHierarchy][3], [33: request form][33]

```xml
<m:SyncFolderHierarchy>
  <m:FolderShape><t:BaseShape>Default</t:BaseShape></m:FolderShape>
  <m:SyncFolderId><t:DistinguishedFolderId Id="msgfolderroot"/></m:SyncFolderId>
</m:SyncFolderHierarchy>
```

[docs] In subsequent requests, add after `SyncFolderId`
`<m:SyncState>SYNC_STATE</m:SyncState>`. Do not mix states for different roots or change
between requests. Do not switch from “root not specified” to `root`: these are different synchronization scopes.
[12: synchronization errors][12]

[assumption; recommendation] For new/changed folders, retrieve class and parent with
`GetFolder` if they are absent from the chosen response shape; refresh counts with it when displaying a list
or periodically. Do not rely on hierarchy deltas for counts.

### В2. Message lists: FindItem and IndexedPageItemView

[docs] `FindItem Traversal="Shallow"` lists direct items in a folder. Supported features include
`SortOrder`, `IndexedPageItemView`, and selected additional properties. The response includes:
`RootFolder/@IndexedPagingOffset`, `@IncludesLastItemInRange`, `@TotalItemsInView`,
`Items/Message/ItemId` and the requested available fields. [6: FindItem][6], [7: paging][7]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="item:Subject"/>
      <t:FieldURI FieldURI="item:DateTimeReceived"/>
      <t:FieldURI FieldURI="item:Size"/>
      <t:FieldURI FieldURI="item:HasAttachments"/>
      <t:FieldURI FieldURI="item:ItemClass"/>
      <t:FieldURI FieldURI="item:ConversationId"/>
      <t:FieldURI FieldURI="message:IsRead"/>
    </t:AdditionalProperties>
  </m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:SortOrder>
    <t:FieldOrder Order="Descending"><t:FieldURI FieldURI="item:DateTimeReceived"/></t:FieldOrder>
  </m:SortOrder>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
</m:FindItem>
```

[docs] Metadata is available, such as subject, time, size, importance, `IsRead`,
and `HasAttachments`; this is not a complete message object. The reference limits streaming properties to 512
bytes / 255 Unicode characters; bodies and full recipient lists are absent, with only recipient summaries.
For `From`/`Sender`, the `FindItem` reference promises a name, but not `EmailAddress`; retrieve SMTP addresses
using `GetItem`. [6: FindItem limitations][6], [25: example property set][25]

[docs] By default, an unrestricted query returns up to 1000 results per request; for AQS or
`Restriction` in Exchange 2013, up to 250. `EWSFindCountLimit` also accounts for the combined
results of simultaneously executing searches for a user. This is not a limit on total folder size.
[8: throttling][8]

[docs] `MaxEntriesReturned` is an upper request; the server may return fewer. Follow
`IndexedPagingOffset`, and stop based on `IncludesLastItemInRange`, not by comparing the number
of rows with page size. Offset paging does not create a snapshot: inserts and deletes can
shift pages; Microsoft describes checking an anchor item. [7: paging][7]

[assumption; recommendation] For the UI, use 100 rows and deduplicate IDs; to fully populate
the store, prefer `SyncFolderItems` starting with an empty state. Sorting by
time alone is not enough to guarantee traversal of a changing folder: timestamps may tie.

### В3. Incremental synchronization: SyncFolderItems

[docs] Synchronization is per folder. The initial request omits `SyncState`;
continuations contain exactly the blob from the previous successful response. `MaxChangesReturned`
allows **1–512 inclusive**. `NormalItems` excludes folder-associated information.
The server returns `SyncState`, `IncludesLastItemInRange`, and `Changes`.
[9: SyncFolderItems][9], [10: MaxChangesReturned][10], [11: synchronization][11]

```xml
<m:SyncFolderItems>
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:SyncFolderId><t:FolderId Id="FOLDER_ID"/></m:SyncFolderId>
  <m:MaxChangesReturned>100</m:MaxChangesReturned>
  <m:SyncScope>NormalItems</m:SyncScope>
</m:SyncFolderItems>
```

[docs] For a continuation, insert `<m:SyncState>SYNC_STATE</m:SyncState>` before
`MaxChangesReturned`. Repeat until `IncludesLastItemInRange=true`, then save the state
for the next cycle. `IdOnly` reduces requests to Exchange; retrieve MIME, `Body`, and the
`Attachments` collection with `GetItem`. [9: SyncFolderItems][9], [11: best practices][11]

| Change | Contains / means | Handling in local store |
|---|---|---|
| `Create` | [docs] New item with ID and selected fields | [assumption] Upsert locator; queue download |
| `Update` | [docs] Changed item | [assumption] Update metadata; do not treat every change as a reason to refetch MIME |
| `Delete` | [docs] ID of item that disappeared from folder | [assumption] Mark local instance absent; do not delete anything on server |
| `ReadFlagChange` | [docs] `ItemId` and new `IsRead` | [assumption] Update only the local flag |

[docs] Sources for the table: the SOAP example uses `ReadFlagChange`;
Microsoft's overview [11] calls this type `ReadStateChange` for EWS — a terminology discrepancy.
For XML, follow the operation's response shape, not the name in overview text.
[34: SyncFolderItems change example][34], [11: overview][11]

[docs] If state is lost, start a new initial sync without `SyncState`.
`ErrorInvalidSyncStateData` requires resynchronization without it. Hierarchy/item states,
different folders, and different scopes are not interchangeable. [12: errors][12], [13: ResponseCodeType][13]

[assumption; recommendation] Store the blob without parsing/truncation together with the connection,
folder, and scope. Apply a change page and commit the new state in one local
transaction or together with a reliable follow-up queue. Retry idempotently after failure;
do not advance the checkpoint past lost work. On `ErrorSyncFolderNotFound`,
refresh the hierarchy first; on `ErrorItemNotFound` during follow-up retrieval, reconcile the delta again.
The basis for distinguishing errors is [12], [13].

**State expiration.** [docs] The reviewed sources describe invalid state and recovery,
but do not specify a guaranteed TTL for Exchange 2013. Claims that it “expires after N days”
or “never expires” are not confirmed here. [12], [13]
[assumption; recommendation] If the server rejects state, use the same full-reconciliation path:
build a new local generation of presence, finish all pages, and only then
mark missing locators. Do not clear the working index until the initial sync completes successfully.

### В4. Message content: GetItem with IncludeMimeContent

[docs] `GetItem` retrieves selected properties for several IDs in one `ItemIds`; check each
`GetItemResponseMessage` separately. The response needs `Items/Message/ItemId`,
`MimeContent`, and explicitly requested `InternetMessageId`, `IsRead`, and `Attachments`.
The `Attachments` collection here contains metadata, not separately downloaded payloads.
[14: GetItem for an email message][14], [19: retrieving attachments][19], [31: results][31]

```xml
<m:GetItem>
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:IncludeMimeContent>true</t:IncludeMimeContent>
    <t:AdditionalProperties>
      <t:FieldURI FieldURI="message:InternetMessageId"/>
      <t:FieldURI FieldURI="message:IsRead"/>
      <t:FieldURI FieldURI="item:Attachments"/>
    </t:AdditionalProperties>
  </m:ItemShape>
  <m:ItemIds><t:ItemId Id="ITEM_ID"/><t:ItemId Id="ITEM_ID_2"/></m:ItemIds>
</m:GetItem>
```

[docs] `MimeContent` is XML base64Binary; the `CharacterSet` attribute does not replace MIME headers.
After decoding the outer base64, the result is a stream for `.eml`; the message may also contain
its own transfer encodings and different charsets. [15: encoding layers][15]
[assumption; recommendation] Save the decoded bytes directly; extract text with a MIME
parser instead of converting the whole stream using `new String(..., UTF_8)`.

**IsRead.** [docs] The operation retrieves properties; it does not set the flag. In the official
`GetItem` example with `IncludeMimeContent=true`, the response still has `IsRead=false`.
Thus, retrieving MIME does not itself mark a message as read. Changing
`IsRead` is a separate write; setting it to `true` may send a notification if a read receipt was requested.
[14: GetItem example][14], [16: IsRead][16]
[assumption; recommendation] Do not add “restore unread state” with `UpdateItem`:
that itself violates read-only. The user can check this on the specific CU by rereading
`IsRead`; the agent did not run that check.

**Size and cost.** [docs] MIME conversion is expensive and includes a limited set of properties;
Microsoft does not recommend frequent bulk MIME export. [17: export][17]
[docs] In Exchange 2013, EWS `maxAllowedContentLength` and
`maxReceivedMessageSize` are documented as 67,108,864 bytes (64 MiB); accounting for base64, usable size is smaller.
These are settings for accepted requests/IIS/bindings, **not proof of a 48 MiB limit for
`GetItem` responses or downloaded attachments**. These sources do not establish a universal MIME-response limit for CU23. [32: Exchange 2013 sizes][32]

[docs] Microsoft recommends `GetItem` batches of 10 items during synchronization.
[11: best practices][11]
[assumption; recommendation] For MIME, start with one large message or a small batch
of up to 10 small ones; limit total bytes and time locally. Do not treat 10 as a
protocol maximum. Do not download MIME again only because the read flag changed; store version
and state metadata separately from `.eml`.

### В5. Attachments: GetAttachment

[docs] First retrieve `item:Attachments` with `GetItem`, then call `GetAttachment`
with one or more attachment IDs. A file arrives as `FileAttachment/Content` (base64),
an embedded item arrives as `ItemAttachment/Message` or another item type. Relevant fields include `AttachmentId`,
`Name`, `ContentType`, `ContentId`, `IsInline`, and `Size`; metadata values may depend
on the source attachment. [18: GetAttachment][18], [19: retrieval process][19]

File, minimal request [docs; 18]:

```xml
<m:GetAttachment>
  <m:AttachmentShape/>
  <m:AttachmentIds><t:AttachmentId Id="ATTACHMENT_ID"/></m:AttachmentIds>
</m:GetAttachment>
```

Embedded message MIME [docs; 20]:

```xml
<m:GetAttachment>
  <m:AttachmentShape><t:IncludeMimeContent>true</t:IncludeMimeContent></m:AttachmentShape>
  <m:AttachmentIds><t:AttachmentId Id="ATTACHMENT_ID"/></m:AttachmentIds>
</m:GetAttachment>
```

[docs] In the second case, decode `ItemAttachment/Message/MimeContent` as `.eml`.
An empty `AttachmentShape` does not request MIME for an item attachment. `GetAttachment` has no pagination or byte-range:
it retrieves the full attachment, not a segment.
[18: GetAttachment][18], [20: IncludeMimeContent][20]

[docs] Attachments may be nested recursively. `HasAttachments` does not signal inline-only
attachments; for HTML containing `cid:`, inspect the collection. A `.msg` file is a file
attachment and does not automatically become an EWS item attachment. [21: attachment types][21]

[assumption; recommendation] For large attachments, use one ID per request, stream-decode base64
into a bounded local file, and enforce size and unpacking-depth limits. The numeric
download limit for CU23 is **not established**; the 64 MiB settings from В4 cannot automatically
be treated as a file limit. [32] describes transport settings, not range download.
If the complete parent MIME is already saved, downloading the file attachment again may be
unnecessary; choosing between the MIME part and EWS attachment requires matching metadata.

### В6. Conversations: ConversationId, FindConversation, GetConversationItems

[docs] `item:ConversationId` links an item to a conversation and can be requested among
the metadata. `FindConversation` lists conversations in a folder and has been available since 2010 SP1.
`GetConversationItems` first appeared in 2013 and supports primary/archive mailboxes.
[22: FindConversation][22], [23: GetConversationItems][23], [30: versions][30]

```xml
<m:FindConversation>
  <m:IndexedPageItemView MaxEntriesReturned="25" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderId><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderId>
</m:FindConversation>
```

[docs] The response includes `Conversations/Conversation`, `ConversationId`, `ConversationTopic`,
`LastDeliveryTime`, `MessageCount`, `UnreadCount`, `ItemIds`; `Global*` variants describe
conversation beyond the current folder. An indexed view is available; the response example and
`FindConversationResponse` reference do not show a `RootFolder` cursor like `FindItem` has.
These sources do not state the exact CU23 page ceiling. [22: operation][22],
[36: response shape][36]
[assumption; recommendation] Do not reuse the `FindItem` paging parser without checking it;
use small pages and verify traversal completion on CU23. For a complete local
index, rely on folder/item sync rather than conversation listing.

```xml
<m:GetConversationItems>
  <m:ItemShape>
    <t:BaseShape>IdOnly</t:BaseShape>
    <t:AdditionalProperties><t:FieldURI FieldURI="item:Subject"/></t:AdditionalProperties>
  </m:ItemShape>
  <m:MaxItemsToReturn>100</m:MaxItemsToReturn>
  <m:SortOrder>TreeOrderAscending</m:SortOrder>
  <m:MailboxScope>PrimaryOnly</m:MailboxScope>
  <m:Conversations>
    <t:Conversation><t:ConversationId Id="CONVERSATION_ID"/></t:Conversation>
  </m:Conversations>
</m:GetConversationItems>
```

[docs] The response contains `ConversationId`, its own `SyncState`, `ConversationNodes/ConversationNode`
with `InternetMessageId`, `ParentInternetMessageId`, and `Items`. Folders can be excluded with
`FoldersToIgnore`; passing conversation sync state limits results to new/changed
items. This state differs from `SyncFolderItems` state.
[23: GetConversationItems][23]
[docs] `MaxItemsToReturn` limits result size, but its reference does not establish
a numeric default/max; 100 in the example is a chosen size, not a confirmed maximum.
[35: MaxItemsToReturn][35]
[assumption; recommendation] Do not treat a limited response as complete history or use
conversation sync state as an offset cursor for the entire old thread. If needed, ensure thread
completeness using locally indexed items by conversation ID, fetching MIME
with `GetItem`. Do not automatically merge conversation IDs across different connections.

### В7. Search: Restriction versus QueryString (AQS)

[docs] `Restriction` defines field comparisons, `And`/`Or`/`Not`, `Exists`, `Contains`, ranges,
and extended properties; `Contains` controls case/containment. This is the preferred path
for precise searches over specific properties. [26: search filters][26]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:Restriction>
    <t:IsEqualTo>
      <t:FieldURI FieldURI="message:IsRead"/>
      <t:FieldURIOrConstant><t:Constant Value="false"/></t:FieldURIOrConstant>
    </t:IsEqualTo>
  </m:Restriction>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
</m:FindItem>
```

[docs] AQS searches the content index: without a field name, it searches indexed properties; with a field name,
for example `subject:`, `body:`, `from:`, or `attachment:`. Arbitrary custom properties are not in this
index. `QueryString` and `Restriction` are mutually exclusive in one request.
In 2013, `QueryString` gained the `QueryStringType` type with additional optional
attributes; AQS itself predates 2013. [24: QueryString][24], [25: AQS][25]

```xml
<m:FindItem Traversal="Shallow">
  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
  <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
  <m:ParentFolderIds><t:DistinguishedFolderId Id="inbox"/></m:ParentFolderIds>
  <m:QueryString>attachment:report</m:QueryString>
</m:FindItem>
```

[docs] `attachment:` covers the filename and file content, not only the name. However, Exchange Search
indexes only supported formats; in Exchange 2013, processing occurs in the transport pipeline
or soon after creation/delivery. This is not a numeric latency SLA. [24: attachment][24],
[27: supported formats][27], [37: Exchange Search 2013][37]

[docs] AQS uses case-insensitive matching and prefix matching by default;
quotes specify a word/phrase. Possible errors include `ErrorContentIndexingNotEnabled`,
`ErrorInvalidQueryString`, and `ErrorQueryFilterTooLong`; the latter limits a restriction
to 255 expressions. [24: syntax][24], [13: error codes][13]
[assumption; recommendation] Do not promise that content in all PDFs/archives/scans will be found:
corruption, encryption, missing text layers, and index state require local
verification. Use server search as an additional channel; build complete attachment search
over locally extracted text.

[docs] Apply the page limits and paging from В2 to both search types.
[assumption; recommendation] Do not create search folders for read-only search:
creating them changes the mailbox. Traverse subtrees through known physical folders;
`FindItem` with `Shallow` is not recursive. Basis: [6], [30].

### В8. Identifiers and message key

| Value | Verified behavior | Store implication |
|---|---|---|
| `ItemId/@Id` | [docs] Opaque, case-sensitive; may change on move because move is implemented as copy/delete. [28] | [assumption] Current instance locator, not a permanent key |
| Copy | [docs] Copy creates a new item; the original remains. [30], [28] | [assumption] Do not merge two instances based only on equal content |
| Sync IDs | [docs] Sync returns IDs of current items and deleted IDs; the four delta types have no full move event. [9], [34] | [assumption] Observe cross-folder move as disappearance/appearance; correlation is not always unambiguous |
| `ChangeKey` | [docs] Changes when an item changes; used for version control. [28] | [assumption] Store separately; a change does not prove MIME changed |
| `InternetMessageId` | [docs] EWS Internet message identifier; RFC `Message-ID` identifies a message version, not its mailbox location. [38], [39] | [assumption] Correlation/content-deduplication field, not a unique instance key |
| `ConvertId` | [docs] Converts between EwsId, EntryId/HexEntryId, and other formats; available since 2007 SP1. [29] | [assumption] Does not promise immutability after a move |

[docs] RFC 5322 requires a globally unique well-formed `Message-ID`, but its presence in a message is
`SHOULD`, not a universal `MUST`. Copies of one message do not become distinct message versions merely by copying. [39: RFC 5322, §3.6.4][39]
[assumption] Real data must allow for missing/invalid/duplicated IDs;
even a valid ID may appear in multiple instances and mailboxes. Therefore,
`connection + InternetMessageId` does not make an item unique.

[docs] `ConvertId` accepts an array of `SourceIds` and returns `AlternateId` with `Format`, `Id`,
and `Mailbox`; there is no paging. The operation validates SMTP address format, but not mailbox existence.
The reference does not define a universal batch size for CU23. [29: ConvertId][29]

```xml
<m:ConvertId DestinationFormat="EwsId">
  <m:SourceIds>
    <t:AlternateId Format="HexEntryId" Id="HEX_ENTRY_ID" Mailbox="user@example.com"/>
  </m:SourceIds>
</m:ConvertId>
```

**Key recommendation** [assumption, not an accepted decision]: a permanent record key
`(connectionId, localMessageUuid)`, where the local UUID is assigned to an instance and does not depend
on the server. Store separately:

- current locator `(connectionId, mailboxScope, ewsItemId)` with case-sensitive comparison,
  parent folder, `ChangeKey`, presence state, and alias history;
- `InternetMessageId`, dates, sender, size, and normalized-content fingerprint
  for finding move candidates;
- SHA-256 of downloaded MIME bytes for integrity/blob reuse, without requiring
  this hash to remain stable across repeated EWS conversions.

[assumption; recommendation] After delete/create, transfer the UUID to the new locator only
when the match in the same mailbox is sufficiently unambiguous; with several identical copies,
retain separate UUIDs and, if needed, a “same content” link. Removing a locator must not
delete a blob that has other references. This keeps local URIs stable
and preserves duplicates; the tradeoff is that these operations cannot guarantee definitive distinction between move and copy+delete.
Basis: ID mutability [28], MIME semantics [17], and `Message-ID` [39].

### В9. Throttling, limits, and request retries

[docs] Exchange 2013 uses budget/recharge and microdelays; an administrator can override
the global policy through Organization/Regular scopes. A documented value does not prove
the effective policy for a specific user. [40: workload management 2013][40]

| Parameter | What applies to Exchange 2013 | Value status / source |
|---|---|---|
| `EWSMaxConcurrency` | [docs] Simultaneous user requests | **27** in version-specific EWS table [8]; cmdlet reference says **10** without version distinction [41]. Source conflict; check effective policy |
| `EWSPercentTimeInCAS` | [docs] Exchange 2010 parameter | Not applicable as a documented percentage default for 2013; do not carry over old values [41] |
| `EWSPercentTimeInAD` | [docs] Exchange 2010 parameter | Same; do not set a percentage for 2013 [41] |
| `EWSPercentTimeInMailboxRPC` | [docs] Exchange 2010 parameter | Same; do not set a percentage for 2013 [41] |
| `EwsMaxBurst`, `EwsRechargeRate`, `EwsCutoffBalance` | [docs] Budget model since Exchange 2013 | Numeric defaults for CU23 not established here; administrator checks effective policy [40], [41] |
| `EWSFindCountLimit` | [docs] Counts and concurrent-search accounting — В2 | Since 2013, cannot be read/changed through the cmdlet [8] |
| `EWSMaxBatchSize` | [assumption] No public parameter with this name found | Absent from cmdlet parameter list [41]; do not claim “default 100” |

[docs] Reference [41] covers several versions and explicitly marks the three `PercentTimeIn*`
parameters as applying only to 2010. `Default value: None` in cmdlet metadata does not mean
“budget disabled” or represent the effective policy default. [41: New-ThrottlingPolicy][41]

[assumption; recommendation] The working hypothesis for 2013 is 27 based on the specialized table,
but start with one active request per connection. Do not plan load around
the maximum: the same user may access EWS through other clients.

[docs] EWS checks budget in batches of items; an oversized batch may exceed
the documented one-minute batch execution time. [8: batch throttling][8]
[assumption; recommendation] Choose batch size based on bytes/latency rather than seeking
a single “maximum items” value. The recommended 10 for sync is a guideline, not `EWSMaxBatchSize`.
[11: sync best practices][11]

| Error / response | Action |
|---|---|
| `ErrorServerBusy`, including SOAP fault / inner error | [docs] Honor `BackOffMilliseconds` when the server sends it [8] |
| `ErrorExceededConnectionCount` | [docs] Concurrency exceeded [13]; [assumption] reduce parallelism and queue the request |
| `ErrorExceededFindCountLimit` | [docs] Find budget exceeded [13]; [assumption] reduce page size and concurrent searches |
| HTTP 503 / 500 / 200 with EWS error | [docs] All may accompany throttling; parse both HTTP and SOAP [8], [31] |
| `ErrorInvalidSyncStateData` | [docs] Full sync without state, not an endless retry [13] |
| `ErrorContentConversionFailed`, `ErrorItemCorrupt` | [docs] MIME/property error [13]; [assumption] retain an explicit content error, and try metadata separately |
| `ErrorItemNotFound`, `ErrorAccessDenied`, 401/403 | [assumption] Do not retry sign-in automatically; check permissions/configuration or reconcile locator with delta. EWS error definitions: [13] |

[assumption; recommendation] On transient failure, retry only the failed part of a read-only
batch. Pause requests for the affected connection for at least the server backoff; if none is
provided, use bounded exponential backoff with jitter, for example 1, 2, 4, 8, 16, 32 seconds,
with an overall deadline and cancellation. Do not cap a server backoff to a shorter client
maximum. After several failures, return a “temporarily unavailable” state while retaining the checkpoint.
These are proposed client settings, not Exchange defaults; [11] supports pausing the request flow
when throttled.

### В10. Read-only mode safety

[docs] `GetFolder`, `FindFolder`, `FindItem`, `GetItem`, `GetAttachment`, `FindConversation`,
and `GetConversationItems` retrieve data; `SyncFolderHierarchy`/`SyncFolderItems` report
changes to the client; `ConvertId` converts IDs. These operations do not issue commands to change
a message, folder, or settings. In particular, `Create`/`Update`/`Delete` in a **sync response**
describe changes that have already occurred; they are not commands to write them back. Evidence and
limitations for each operation are collected in the comparison table below. [1], [2], [3], [6], [9], [14],
[18], [22], [23], [29]

[assumption; recommendation] Use a strict allowlist of these SOAP operations;
do not expose arbitrary SOAP to the model. Check SDK method names such as `Load`/`Bind`
against the wire request they send; a UI action to “open a message” must not trigger an additional
write. This recommendation is for a future implementation; this research contains no code.

Operations prohibited in read-only mode [docs: definitions in EWS operations catalog][30]:

- items: `CreateItem` (including draft, reply/forward, response objects, and `SuppressReadReceipt`),
  `UpdateItem` (including `IsRead`, flags, categories), `DeleteItem`, `MoveItem`, `CopyItem`,
  `SendItem`, `ArchiveItem`, `MarkAllItemsAsRead`, `MarkAsJunk`;
- attachments and folders: `CreateAttachment`, `DeleteAttachment`, `CreateFolder`, `CreateFolderPath`,
  `CreateManagedFolder`, `UpdateFolder`, `DeleteFolder`, `EmptyFolder`, `MoveFolder`, `CopyFolder`;
- conversation actions and settings: `ApplyConversationAction`, `UpdateInboxRules`,
  `SetUserOofSettings`, `AddDelegate`, `UpdateDelegate`, `RemoveDelegate`,
  `CreateUserConfiguration`, `UpdateUserConfiguration`, `DeleteUserConfiguration`, `UploadItems`.

[assumption; recommendation] This list of prohibited examples does not replace an allowlist: all other
operations, including subscriptions and search-folder management, are outside the MVP's allowed set.
Do not execute response actions returned in `ResponseObjects`. Do not toggle read state
“for testing” and then change it back.

## Comparison tables

All rows apply to `Exchange2013_SP1`. “Yes” means the operation itself does not change mailbox content or
settings; it does not mean the server incurs no load.

| Operation | Why we need it | Read-only? | Limits | Exchange 2013 specifics | Source |
|---|---|---|---|---|---|
| `GetFolder` | Folder properties and current counts | [docs] Yes | No paging; numeric batch maximum not established | `AllProperties` omits permissions unless explicitly requested | [1] |
| `FindFolder` | Folder tree | [docs] Yes | Paging; find budget; streamable-property truncation | Deep for private tree; not public folders | [2], [7], [8] |
| `SyncFolderHierarchy` | Tree changes | [docs] Yes | `IncludesLastFolderInRange`; no client max changes | Count-only changes are not reported | [3], [33] |
| `FindItem` | List and server-side search | [docs] Yes | 1000 without filter / 250 with filter or AQS; paging | No full body/recipient lists; AQS supported | [6], [8], [24] |
| `SyncFolderItems` | Delta for each physical folder | [docs] Yes | 1–512 changes; loop until last=true | No full MIME; do not use for search folders | [9], [10], [11] |
| `GetItem` | MIME and selected full properties | [docs] Yes, does not set `IsRead` | No paging; MIME response max not established; small batches recommended | MIME conversion is expensive; not a raw backup | [14], [17] |
| `GetAttachment` | Files and embedded messages | [docs] Yes | Whole attachment; no byte-range; download max not established | Item MIME requires `IncludeMimeContent` | [18], [20] |
| `FindConversation` | Conversation list | [docs] Yes | Indexed view; numeric max for CU23 not established | Since 2010 SP1; folder/global properties differ | [22], [36] |
| `GetConversationItems` | Conversation nodes and items | [docs] Yes | `MaxItemsToReturn`; default/max not established | Since 2013; separate conversation sync state | [23], [35] |
| `ConvertId` | ID format compatibility | [docs] Yes | ID array; no paging; numeric max not established | Does not guarantee stable ID after move | [29], [28] |

## Recommendation

[assumption; recommendation, decision belongs to user] For the MVP: one connection —
one queue of bounded read-only requests. Retrieve the tree under `msgfolderroot`, select
physical mail folders, run initial `SyncFolderItems IdOnly` for each, then
fetch metadata and MIME. Maintain hierarchy state, each folder's item state,
and, if needed, conversation state separately. Refresh counts with `GetFolder`.

[assumption; recommendation] Use a local UUID with a mutable EWS locator as in В8;
retain `InternetMessageId` and fingerprint for correlation. This is more complex than a single
`ItemId` or `Message-ID`, but it supports stored copies and moves without promising
unattainable perfect correlation.

[assumption; recommendation] Store `.eml` separately from EWS metadata. Pace initial MIME
downloads; on later syncs, do not download MIME because of a read-flag change. Keep server-side AQS/
restriction queries for live search and reconciliation; use a local index as the primary attachment
search. Basis and tradeoffs are in В2–В9; these are proposals, not changes to
the project's accepted decisions.

## What could not be verified

- [assumption / needs verification] The effective throttling policy for the CU23 user,
  actual batch maxima, and download response. The agent did not sign in to the mailbox. An administrator
  can **read only** `Get-ThrottlingPolicyAssociation` and the corresponding
  `Get-ThrottlingPolicy`, accounting for inheritance; separately check applicable EWS/IIS settings.
  Do not use 27 as guaranteed capacity.
- [docs / source gap] The source conflict about concurrency is described in В9;
  no `EWSMaxBatchSize` documented for CU23 was found. Checking policy does not replace
  adaptive throttling handling.
- [docs / source gap] No guaranteed lifetime for Exchange 2013 `SyncState` was found.
  Recovery from invalid state is confirmed; do not borrow a TTL from Graph or subscriptions.
- [docs / source gap] Numeric page limits for `SyncFolderHierarchy`,
  `FindConversation`, the default/max `MaxItemsToReturn`, and a universal MIME/attachment
  **response** limit for CU23 are not established. During implementation, check schema/client
  SDK documentation and then perform bounded reads run by the user. Do not transfer the 64 MiB incoming transport
  limit or 512 item-sync changes to other operations.
- [assumption / needs verification] Completion of `FindConversation` paging and completeness of a long
  conversation with `MaxItemsToReturn` need a separate check on CU23; until then, do
  not use these operations as the sole way to fully populate the store.
- [docs / not live-checked] The documented read-only semantics of `GetItem` and the `IsRead=false`
  example are confirmed; a specific mailbox was not checked. During implementation, the user
  can read `IsRead` twice around MIME retrieval for an unread message, without `UpdateItem`
  and without opening that message in another client at the same time.
- [assumption / needs verification] Actual user-folder classes, Exchange Search state,
  indexing delay, and coverage of the required Russian-language attachments. The user
  can compare server AQS results with already known messages/attachments;
  this research creates and sends no test messages.
- [assumption / model limitation] These deltas cannot reliably distinguish an external move from copy+delete
  of identical instances; this is a limitation of the key scheme,
  not a promised future test that is certain to resolve it.

## Further topics to study

- Outside RT-03: the specific Java client, NTLM, and control over the SOAP calls it creates
  belong to RT-02.
- Outside RT-03: preserving inline/TNEF, S/MIME, `.msg`, recursive MIME attachments,
  and matching MIME parts to attachment IDs belong to RT-09.
- Outside RT-03: notifications as a polling optimization, a separate archive mailbox,
  and public folders need a separate synchronization-scope specification.

## Sources

All sources were read on **2026-10-24**. Update dates are given below where they were
recorded during review; they do not indicate when an API appeared. For legacy Exchange 2013,
the operation version is more important than publication recency; modern properties without confirmed
compatibility were not carried over. Numbers in the text refer to these same URLs.

1. [GetFolder operation][1] (updated 2023-03-29).
2. [FindFolder operation][2] (EWS reference).
3. [SyncFolderHierarchy operation][3] (EWS reference).
4. [DistinguishedFolderId][4] (updated 2023-03-29).
5. [Folders and items in EWS in Exchange][5] (official type table; publication date not recorded).
6. [FindItem operation][6] (EWS reference).
7. [Perform paged searches by using EWS in Exchange][7] (official guide).
8. [EWS throttling in Exchange][8] (updated 2022-02-11; table distinguishes 2010/2013/Online).
9. [SyncFolderItems operation][9] (EWS reference).
10. [MaxChangesReturned][10] (updated 2023-03-28).
11. [Mailbox synchronization and EWS in Exchange][11] (updated 2022-02-10).
12. [Handling synchronization-related errors in EWS in Exchange][12] (updated 2021-09-15).
13. [[MS-OXWSCDATA]: m:ResponseCodeType Simple Type][13] (official protocol specification).
14. [GetItem operation (email message)][14] (updated 2023-03-29).
15. [MimeContent][15] (updated 2023-03-29).
16. [IsRead][16] (updated 2023-03-29).
17. [Export items by using EWS in Exchange][17] (official MIME export guide).
18. [GetAttachment operation][18] (EWS reference).
19. [Get attachments by using EWS in Exchange][19] (official guide).
20. [IncludeMimeContent][20] (updated 2023-03-29).
21. [Attachments and EWS in Exchange][21] (official overview; file/item/inline semantics used).
22. [FindConversation operation][22] (EWS reference).
23. [GetConversationItems operation][23] (EWS reference; explicitly states introduction in 2013).
24. [QueryString (QueryStringType)][24] (EWS reference; differences between 2010/2013 types).
25. [Perform an AQS search by using EWS in Exchange][25] (updated 2020-01-15).
26. [Use search filters with EWS in Exchange][26] (official guide).
27. [File formats indexed by Exchange Search: Exchange 2013 Help][27] (explicitly Exchange 2013 version).
28. [EWS Identifiers in Exchange][28] (updated 2020-01-15).
29. [ConvertId operation][29] (updated 2023-03-29).
30. [EWS operations in Exchange][30] (official catalog with operation introduction versions).
31. [Verifying the results of an EWS or EWS Managed API call][31] (official guide).
32. [Configure client-specific message size limits: Exchange 2013 Help][32] (explicitly Exchange 2013 version).
33. [SyncFolderHierarchy — request element][33] (updated 2024-09-06).
34. [Synchronize items by using EWS in Exchange][34] (SOAP change and continuation example).
35. [MaxItemsToReturn][35] (updated 2023-03-29).
36. [FindConversationResponse][36] (updated 2023-03-29).
37. [Exchange Search: Exchange 2013 Help][37] (updated 2023-01-26).
38. [InternetMessageId][38] (updated 2023-03-29).
39. [RFC 5322 — Internet Message Format, §3.6.4][39] (published 2008-10).
40. [Exchange workload management: Exchange 2013 Help][40] (explicitly Exchange 2013 version).
41. [New-ThrottlingPolicy — Exchange PowerShell][41] (reference with parameter applicability by version;
    concurrency value conflicts with [8], see В9).

[1]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getfolder-operation
[2]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findfolder-operation
[3]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderhierarchy-operation
[4]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/distinguishedfolderid
[5]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/folders-and-items-in-ews-in-exchange
[6]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/finditem-operation
[7]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-perform-paged-searches-by-using-ews-in-exchange
[8]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-throttling-in-exchange
[9]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderitems-operation
[10]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/maxchangesreturned
[11]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/mailbox-synchronization-and-ews-in-exchange
[12]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/handling-synchronization-related-errors-in-ews-in-exchange
[13]: https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxwscdata/53734587-8b80-4cd8-b0ea-92da6b960d9e
[14]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getitem-operation-email-message
[15]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/mimecontent
[16]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/isread
[17]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-export-items-by-using-ews-in-exchange
[18]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getattachment-operation
[19]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-get-attachments-by-using-ews-in-exchange
[20]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/includemimecontent
[21]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/attachments-and-ews-in-exchange
[22]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findconversation-operation
[23]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getconversationitems-operation
[24]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/querystring-querystringtype
[25]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-perform-an-aqs-search-by-using-ews-in-exchange
[26]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-use-search-filters-with-ews-in-exchange
[27]: https://learn.microsoft.com/en-us/exchange/file-formats-indexed-by-exchange-search-exchange-2013-help
[28]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-identifiers-in-exchange
[29]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/convertid-operation
[30]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/ews-operations-in-exchange
[31]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/verifying-the-results-of-an-ews-or-ews-managed-api-call
[32]: https://learn.microsoft.com/en-us/exchange/configure-client-specific-message-size-limits-exchange-2013-help
[33]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/syncfolderhierarchy
[34]: https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-synchronize-items-by-using-ews-in-exchange
[35]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/maxitemstoreturn
[36]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/findconversationresponse
[37]: https://learn.microsoft.com/en-us/exchange/exchange-search-exchange-2013-help
[38]: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/internetmessageid
[39]: https://www.rfc-editor.org/rfc/rfc5322#section-3.6.4
[40]: https://learn.microsoft.com/en-us/exchange/exchange-workload-management-exchange-2013-help
[41]: https://learn.microsoft.com/en-us/powershell/module/exchangepowershell/new-throttlingpolicy?view=exchange-ps

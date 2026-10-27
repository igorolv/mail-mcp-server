# docs/ews-source-agent.md — EWS client and NTLM transport

Read this before changing anything in `source/ews/` or adding an EWS operation.
Background: plan.md 2.1 (server facts), decisions П1 and П4,
research RT-02 (client and NTLM) and RT-03 (operations).

## Shape

```
EwsClient ──builds request (EwsXml.envelope)──> EwsTransport.exchange(op, bytes, handler)
    ^                                                 │ HttpClient5EwsTransport (NTLM)
    └──── EwsResponseReader (StAX, streaming) <───────┘
```

- `EwsOperation` is the allow-list: the 10 read operations of `docs/research/RT-03-ews-operations.md`. The envelope writer
  takes the request element name from this enum, so a write operation cannot be expressed.
  `EwsRequestTest` fails if a write operation from RT-03 appears in it. Adding an operation needs a
  decision in plan.md section 5.
- Every request carries `RequestServerVersion Exchange2013_SP1` (the server is Exchange 2013 CU23).
- `EwsClient` methods: `getFolder(DistinguishedFolder)`,
  `folderIds(List<DistinguishedFolder>)` (one IdOnly `GetFolder`, ids in request order; a count
  mismatch is a protocol error), `findFolders(root, offset)` (deep `FindFolder`, pages of 1000,
  names, classes and parent ids), `syncFolderItems(folderId, syncState)` (IdOnly,
  `MaxChangesReturned=512`, `SyncScope=NormalItems`; null state is omitted),
  `getItemMime(itemId, target)` (`GetItem` with `IncludeMimeContent`; writes `<target>.part`, moves
  it into place after the whole response was read, and returns size and SHA-256),
  `metadata(refs)` (one `GetItem` for 1–100 distinct refs, properties only).

## Response handling

`EwsResponseReader.read` applies the shared checks before any payload element: `s:Fault` →
`EwsSoapFaultException` (with the EWS code from `detail`, e.g. `ErrorServerBusy`);
`ResponseClass="Error"` → `EwsResponseException` (e.g. `ErrorItemNotFound`); `Warning` is logged
and the payload is still read; no response message at all → `EwsProtocolException`. HTTP 200 alone
is never success.

Parser settings (`EwsXml.inputFactory`): no DTD, no external entities, namespace-aware, not
coalescing. `MimeContent` is decoded chunk by chunk by `Base64ChunkDecoder` straight into the output
stream with a size cap (`EwsClient.DEFAULT_MAX_MIME_BYTES`, 64 MiB), so a large message is never one
string in memory.
Exceeding the decoded MIME limit throws `MessageUnavailableException(TOO_LARGE)`; the temporary
file is removed and the session remains usable.

## Mail source

`EwsMailSource` implements `MailSource` on one `EwsClient` and transport, owned and closed by its
caller. The production connection factory creates it lazily; opening the source contacts no
server. `mail-sync` uses the same `SyncRunner`, store and catalog as IMAP.

Folders are paged through a deep `FindFolder` under `MSG_FOLDER_ROOT`, advancing the offset by
the number returned. The listing ends at `IncludesLastItemInRange` or an empty page. Only
`Folder` nodes with class `IPF.Note` or `IPF.Note.*` are kept. Deleted Items, Junk Email, Drafts,
Outbox and every descendant of those folders are excluded by ids resolved in one `GetFolder`.
Search, calendar, contacts and tasks nodes are not kept. Paths join the display names of all
listed ancestors with `/`, including ancestors of other kinds; folder order stays as listed.
The folder id identifies a folder across renames; item refs are `ItemId` ids, ignoring `ChangeKey`.

`changes` reads `SyncFolderItems` until its last page, passing each page's `SyncState` into the
next request. `Create` entries of any item kind contribute ids in server order; `Delete` removes
an id created during this call or contributes a deletion. `Update` and `ReadFlagChange` are
consumed and ignored. The last page's opaque state is returned unchanged. Known refs are skipped
in the added list, and known deletions are returned in natural string order.

A first sync (null state) enumerates all items, so known refs absent from the created set are
also removed. A later sync applies only reported additions/deletions. If the first request with
a non-null state returns `ErrorInvalidSyncStateData`, the source starts once without a state
and applies first-sync rules. This is not a reset: EWS item ids remain valid, and known messages
must not be downloaded again. Successful recovery returns `rescanned=true` and `reset=false`;
an ordinary first sync has `rescanned=false`. Progress and status preserve this separate signal;
old status JSON without it reads as false. An invalid state on a later page
or after starting without a state propagates.

Downloads use one `GetItem` per message and return the decoded size and SHA-256.
`ErrorItemNotFound` becomes `MessageUnavailableException(GONE)`; the size decoder supplies
`TOO_LARGE`. Other errors propagate. There are no retries for authentication, SOAP faults,
HTTP or transport failures, including `ErrorServerBusy`. A long first run can therefore stop
when Exchange throttles it; a later user-run sync resumes using known refs and saved cursors.

`EwsMailSource.metadata(folder, expectedCursor, refs)` reads every resulting present ref,
including known ones, in batches of at most 100 on the same session. Its `IdOnly` ItemShape adds
`message:InternetMessageId`, `item:Size`, `item:DateTimeSent`, `message:From`, `item:Subject`.
It requests neither IncludeMimeContent nor Body. The cursor remains opaque; EWS has no IMAP
UIDVALIDITY check. A dedicated reader uses the shared safe parser and SOAP-fault checks,
validates each GetItemResponseMessage and the response count, omits ErrorItemNotFound, and
maps successes by returned ItemId rather than by successful-response position. Duplicate,
unrequested or missing success IDs and other errors abort without retry. Missing optional
identity properties disable matching. EWS item size is never replaced by local MIME byte size.

The sync runner uses these snapshots to seed old stores without repeat downloads and to count
retained copies before matching apparent moves. Unique complete Message-ID/metadata equality
can still hide different content: this is heuristic evidence, not remote byte identity. Reuse
preserves UUID, paths, original SHA-256/time and every content file, and changes metadata/index
before unmatched removals. All cursors advance only after planned changes commit; reconciliation
repairs a metadata/index folder/ref mismatch on restart. Reused moves contribute no downloaded,
failed or removed count. See `docs/storage-agent.md` for exact eligibility and recovery rules.
Synthetic protocol tests cover these requests; availability on the user's EWS server remains
to verify (проверить), not a real-mail result.

Primary references checked 2026-10-27: [ItemShape](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/itemshape)
and [FieldURI](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/fielduri).

## Transport and NTLM

`HttpClient5EwsTransport` (Apache HttpClient 5.6.5 classic):

- Its own auth scheme registry holds only the configured scheme — NTLM for `auth: ntlm`, Basic for
  `auth: basic`. NTLM is deprecated in HttpClient 5.3+ and absent from the default registry; it is
  registered explicitly with `NTLMSchemeFactory`. Negotiate/Kerberos is never tried.
- `NTCredentials(username, password, null, domain)`: without a configured domain the NTLM domain
  field stays empty and the server applies its own — the combination that signed in from .NET
  (plan.md 2.1). Credentials are scoped to the host and port of the URL. Basic with a domain sends
  `DOMAIN\login`.
- One connection, one `HttpClientContext` per mailbox: NTLM authenticates the TCP connection.
- Automatic retries and redirects are disabled. HTTP 401 after the handshake becomes
  `EwsAuthenticationException` — one sign-in attempt (one NTLM Type 3 message), never repeated. A
  401 where no credentials were sent (server does not offer the scheme) gets its own message.
- HTTP 500 with an XML body goes to the parser (SOAP fault); other statuses are `EwsHttpException`;
  I/O failures are `EwsTransportException`.
- An exec interceptor counts requests on the wire (`requestCount()`), including handshake legs: a
  first NTLM call costs 3 requests (anonymous, Type 1, Type 3).

Verified on 2026-10-25 by a user-run check against the real mailbox: `GetFolder`, `FindItem` and
`GetItem` with MIME succeeded, with 3 HTTP requests after the first operation and 5 total.
The authenticated connection was reused. `IsRead` was `true` both before and after the download;
this run did not test an unread message. See plan.md 2.1. The fallback is not needed for this
tested connection.

Known risk (RT-02): HttpClient's NTLM engine sends no channel binding. If the server enforces
Extended Protection for Authentication, the handshake fails with 401 although the password is
right. The fallback is a second `EwsTransport` on a buffered `HttpsURLConnection` (Windows SSPI) —
not implemented yet.

`HttpClient5EwsTransportTest` checks the registry, the credentials, and the full handshake against
a fake endpoint on `127.0.0.1` with a synthetic Type 2 challenge: the Type 3 message carries user
`user`, an empty domain and an NTLMv2 response; a rejected Type 3 is not retried. Re-run it after
every HttpClient upgrade.

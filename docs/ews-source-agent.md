# docs/ews-source-agent.md — EWS client, NTLM transport and the spike

Read this before changing anything in `source/ews/`, adding an EWS operation, or interpreting the
output of the EWS spike. Background: plan.md 3.1–3.2 (server facts), decisions П1 and П4,
research RT-02 (client and NTLM) and RT-03 (operations).

## Shape

```
EwsClient ──builds request (EwsXml.envelope)──> EwsTransport.exchange(op, bytes, handler)
    ^                                                 │ HttpClient5EwsTransport (NTLM)
    └──── EwsResponseReader (StAX, streaming) <───────┘
```

- `EwsOperation` is the allow-list: the 10 read operations of research.md 1.2. The envelope writer
  takes the request element name from this enum, so a write operation cannot be expressed.
  `EwsRequestTest` fails if a write operation from RT-03 appears in it. Adding an operation needs a
  decision in plan.md section 10.
- Every request carries `RequestServerVersion Exchange2013_SP1` (the server is Exchange 2013 CU23).
- `EwsClient` methods so far: `getFolder(DistinguishedFolder)`,
  `findNewestItems(folder, max)` (one `FindItem` page sorted by `DateTimeReceived` descending),
  `getItemMime(itemId, target)` (`GetItem` with `IncludeMimeContent`; writes `<target>.part`, moves
  it into place after the whole response was read, and returns size and SHA-256).

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

## Transport and NTLM

`HttpClient5EwsTransport` (Apache HttpClient 5.6.5 classic):

- Its own auth scheme registry holds only the configured scheme — NTLM for `auth: ntlm`, Basic for
  `auth: basic`. NTLM is deprecated in HttpClient 5.3+ and absent from the default registry; it is
  registered explicitly with `NTLMSchemeFactory`. Negotiate/Kerberos is never tried.
- `NTCredentials(username, password, null, domain)`: without a configured domain the NTLM domain
  field stays empty and the server applies its own — the combination that signed in from .NET
  (plan.md 3.2). Credentials are scoped to the host and port of the URL. Basic with a domain sends
  `DOMAIN\login`.
- One connection, one `HttpClientContext` per mailbox: NTLM authenticates the TCP connection.
- Automatic retries and redirects are disabled. HTTP 401 after the handshake becomes
  `EwsAuthenticationException` — one sign-in attempt (one NTLM Type 3 message), never repeated. A
  401 where no credentials were sent (server does not offer the scheme) gets its own message.
- HTTP 500 with an XML body goes to the parser (SOAP fault); other statuses are `EwsHttpException`;
  I/O failures are `EwsTransportException`.
- An exec interceptor counts requests on the wire (`requestCount()`), including handshake legs: a
  first NTLM call costs 3 requests (anonymous, Type 1, Type 3).

Verified on 2026-10-25 by a user-run spike against the real mailbox: `GetFolder`, `FindItem` and
`GetItem` with MIME succeeded, with 3 HTTP requests after the first operation and 5 total.
The authenticated connection was reused. `IsRead` was `true` both before and after the download;
this run did not test an unread message. See plan.md 3.3. The fallback is not needed for this
tested connection.

Known risk (RT-02): HttpClient's NTLM engine sends no channel binding. If the server enforces
Extended Protection for Authentication, the handshake fails with 401 although the password is
right. The fallback is a second `EwsTransport` on a buffered `HttpsURLConnection` (Windows SSPI) —
not implemented yet.

`HttpClient5EwsTransportTest` checks the registry, the credentials, and the full handshake against
a fake endpoint on `127.0.0.1` with a synthetic Type 2 challenge: the Type 3 message carries user
`user`, an empty domain and an NTLMv2 response; a rejected Type 3 is not retried. Re-run it after
every HttpClient upgrade.

## The spike: what it prints and how to read it

`spike/EwsSpikeCommand` (how to run: `docs/build-config-agent.md`) makes three requests and prints
only metadata — no subject, addresses, body, host, login or password:

```
Step 1/3 GetFolder inbox: OK in <ms> ms, HTTP requests so far: 3
  folder: "<localized inbox name>", class IPF.Note, total <n>, unread <n>, subfolders <n>
Step 2/3 FindItem newest inbox item: OK in <ms> ms, items in view: <n>
  newest item: received <ISO time>, IsRead <bool>
Step 3/3 GetItem with MIME: OK in <ms> ms
  MIME: <bytes> bytes, SHA-256 <hex>
  IsRead after GetItem: <bool> (before: <bool>)
  saved to: <data-dir>\<connection>\spike\inbox-newest-<time>.eml
HTTP requests in total: <n>
RESULT: OK
```

Reading the result: `requests so far: 3` after step 1 means the NTLM handshake ran once; a total of
5 means the authenticated connection was reused, larger numbers mean the server re-authenticated
per request. `IsRead` must not change from `false` to `true` (GetItem must not mark mail read).
`SIGN-IN REJECTED` with exit code 3 shows the offered schemes. If the password is known to be right
(the PowerShell check with the same login still works), suspect Extended Protection (see above)
before trying other login formats — each try is a sign-in attempt. The saved `.eml` is an original: it stays
in the data directory and is never given to a model (П11). Record anonymized results in plan.md
3.x; raw details go to `.local/`.

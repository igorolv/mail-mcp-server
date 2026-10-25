# docs/imap-source-agent.md — read-only IMAP source and spike

Read before changing `source/imap/`, authentication, wire commands or the IMAP spike.
Design: plan.md 5.2, 5.6, decisions П3, П4, П8; background: research/RT-04-imap-gmail-mailru.md.

## Implemented scope

Angus Mail 2.0.5 with Jakarta Mail API 2.1.5. `ImapMailSource` implements `MailSource` on top
of `ImapClient`: filtered mail folders, changes by UIDVALIDITY and folder UIDs, and original
MIME downloads by UID. One source owns one lazy session for any number of queries and downloads
across folders. Storage, sync, search and mail MCP tools are not implemented; only
`listConnections` remains exposed through MCP. Gmail labels/deduplication, CONDSTORE/QRESYNC
and paging are not implemented.

`MailConnectionFactory` creates a connection with a supplier; `openSource()` creates a fresh
`ImapMailSource` without contacting a server. The caller closes the source. EWS connections
have no `MailSource` until step 7.3, though their standalone client and spike remain available.
Gmail OAuth2/XOAUTH2 and the user-run `gmail-oauth` command are implemented; setup and token
lifecycle are in `docs/gmail-oauth-agent.md`. Angus objects stay private.

## TLS and authentication

- Production uses implicit TLS (`imaps`), certificate chain validation and
  `mail.imaps.ssl.checkserveridentity=true`. No trust-all setting, plain connection or StartTLS
  fallback is available in production. The package-local plain transport is only for fake-server tests.
- Properties consistently use the `mail.imaps.*` prefix: connect/write timeouts 15 s, read timeout
  120 s, partial fetch blocks 16 KiB, PEEK true, one pooled connection, no separate store socket.
- Angus chooses the first supported mechanism from PLAIN/LOGIN; otherwise it uses the IMAP LOGIN
  command if allowed by the server. All run inside TLS; a failed exchange does not fall back to
  another mechanism. SASL plugins are disabled. OAuth connections select XOAUTH2 only; LOGIN and
  password mechanism fallback are blocked before credentials can be sent.
- `ReadOnlyImapStore` shares an authentication-started flag across all sockets. Its protocol
  subclass checks `writeCommand` before bytes are written and refuses a second credential exchange,
  including an internal Angus reconnect. No provider object is exposed to callers.
- A rejected sign-in, broken connection or protocol error closes the client permanently; nothing
  retries. Successful downloads and `MessageUnavailableException` (GONE or TOO_LARGE) leave
  the session usable. At most one folder is open READ_ONLY; switching closes it with
  `close(false)`. LIST also closes the current folder first, returning its socket to the pool
  before Angus requests a store connection. Closing the source closes the client and store,
  including the selected folder.
- Provider exception messages and causes can contain credentials or mail data. `ImapException`
  returns only a fixed error category/message. The spike withholds raw loader errors too.
- Session debug is off and its debug stream discarded. Angus/Jakarta JUL categories and their
  Logback counterparts are disabled to prevent protocol/authentication logging.

## Read-only command guard

Allowed control commands: CAPABILITY, NOOP, LOGOUT, LOGIN, AUTHENTICATE (PLAIN/LOGIN through the
configured provider), LIST, LSUB, NAMESPACE, STATUS, EXAMINE, CLOSE, UNSELECT and ENABLE (UTF8 negotiation).
OAuth connections use AUTHENTICATE with XOAUTH2 arguments; the wrapper acknowledges OAuth error
challenges with an empty response instead of resending credentials (see gmail-oauth-agent.md).
FETCH/UID FETCH is allowed only with UID, FLAGS, INTERNALDATE, RFC822.SIZE and BODY.PEEK[]
(optionally a partial byte range). Command text containing CR/LF is rejected.

CLOSE is safe because SELECT is forbidden: RFC 3501 section 6.4.2 specifies that CLOSE cannot
remove messages from a mailbox opened with EXAMINE. Angus uses it to return the socket to the
pool when switching READ_ONLY folders.

SELECT, STORE, EXPUNGE, APPEND, COPY, MOVE, folder/subscription/ACL/quota mutations,
RFC822 body retrieval and non-PEEK BODY retrieval are rejected before they reach the socket.
The guard checks provider-generated commands as well as application calls; keep this seam on upgrades.
Folder names are passed as encoded Angus arguments, never interpolated into command text.

## MIME download

`folderUids(folderName)` opens the folder with EXAMINE, reads UIDVALIDITY and fetches all UIDs
with `FETCH 1:N (UID)` (no FETCH for an empty folder). Non-positive UIDVALIDITY or UID is a
protocol failure. UIDs are returned sorted and copied. `ImapMailSource.changes` compares those
refs with the known local refs. A changed UIDVALIDITY resets the folder: all known refs are
removed and all present refs added. Cursors and download refs are checked before any I/O.

`download(folderName, uid, target, maxBytes)`:

1. Validate the folder, positive UID and positive byte limit, then reuse or EXAMINE the folder.
2. Resolve the UID; a missing message raises `MessageUnavailableException(GONE)`.
3. Set PEEK and fetch `RFC822.SIZE` using `FetchProfile.Item.SIZE`. A declared size above the
   limit raises `MessageUnavailableException(TOO_LARGE)` before fetching the body. Do not call
   `IMAPMessage.getSize()`: it fetches ENVELOPE, which the guard forbids.
4. Stream `getMimeStream()` in partial BODY.PEEK blocks into a random local `.part` file next to
   the target, enforcing the byte limit (64 MiB by default) and computing SHA-256. Exceeding the
   limit despite the declared size removes the partial and raises TOO_LARGE. The next message
   can still be downloaded on the same socket. Zero bytes are a protocol failure.
5. Move to the target without replacement and return `Downloaded(size, sha256)`. Local I/O
   errors close the client with a sanitized LOCAL_IO failure. The partial file is cleaned up.
6. Keep the session open after success or message unavailability. Read-only behavior is proved
   by the command guard (BODY.PEEK only, no STORE) and offline tests; there is no per-download
   FLAGS comparison.

`folders()` preserves server order, keeps only folders holding messages and excludes attributes
`\Noselect`, `\NonExistent`, `\Trash`, `\Junk`, `\Drafts` ignoring case. No provider-specific
Gmail handling is added.

Original MIME is kept on disk as downloaded. Under the 2026-10-25 decisions mail content and
local paths may reach the model through tools, with an optional output filter later (plan.md 5.4,
6.8). The spike still prints counts rather than folder names or subjects, so its report can be
pasted into chat.

## User-run spike

See build-config-agent.md for connection examples and commands. `imap-spike [connection]` selects
the only IMAP connection when its name is omitted; multiple IMAP connections require a name.
Only the user runs credentialed probes. Never read the real connections file or saved original.

Expected report:

```text
IMAP spike (read-only: LIST, EXAMINE, BODY.PEEK; one sign-in attempt)
Connection: gmail (imap, app-password; address and credentials hidden)
Step 1/3 LIST: OK, folders to sync: <n> (names hidden)
Step 2/3 EXAMINE inbox: OK, messages: <n>, cursor (UIDVALIDITY): <cursor>
Step 3/3 BODY.PEEK last message: OK, <bytes> bytes, SHA-256 <hex>
  saved to: <data-dir>\gmail\spike\inbox-last-<time>.eml
RESULT: OK
```

Exit codes: 0 success (including an empty inbox), 2 configuration/selection, 3 sign-in rejected,
4 IMAP/network/message-unavailable/limit/local-save error, 1 unexpected error with details withheld.
An absent or empty inbox succeeds without a download. Record anonymized user results in plan.md; do not copy local
user paths, addresses or original contents into tracked files.

Verified on 2026-10-25 by a user-run Mail.ru spike: LIST returned 41 folders, EXAMINE and
BODY.PEEK succeeded, and one MIME original of 25831 bytes was saved. Seen was true both before
and after, so an unread message was not tested. See plan.md 3.4. An earlier user run rejected
sign-in for both Mail.ru and Gmail; the report did not include a provider reason. Gmail still
has no successful user-run result. Do not infer server extensions from the folder count.
After step 7.1 a user-run Mail.ru spike on `MailSource` succeeded on 2026-10-25: 39 folders to
sync, one `FETCH 1:N (UID)` for an inbox of about 13 thousand messages, and the highest UID
downloaded through `RFC822.SIZE` and BODY.PEEK in the same session (plan.md 3.4).
The user deferred Gmail setup on 2026-10-25; their local connections are `work` and `mailru`.
Gmail and OAuth2 support remain available for later use.

## Offline verification and primary references

`ImapClientTest` and `ImapMailSourceTest` share `FakeImapServer`, a strict single-socket
loopback endpoint with synthetic ASCII mail. Tests cover one LOGIN/PLAIN/XOAUTH2 exchange,
no fallback after rejection, EXAMINE/CLOSE folder switching, UID listing, filtering, new and
removed refs, UIDVALIDITY resets, input validation before commands, exact MIME and SHA-256,
BODY.PEEK only, missing UIDs, declared and streamed size limits, continued use after unavailable
messages, partial cleanup, existing-target preservation, command rejection and lazy factories.
`ImapSpikeCommandTest` verifies invalid configuration/selection stops before client creation and
raw configuration values do not enter the report. All tests are part of `gradlew.bat build`.

Angus 2.0.5 sources inspected on 2026-10-25:

- [IMAPStore](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPStore.java): mechanism selection, pool and newIMAPProtocol seam.
- [IMAPMessage](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPMessage.java): getMimeStream, PEEK and getSizeLong.
- [IMAPFolder](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPFolder.java): READ_ONLY/EXAMINE, UID/size fetch and close(false).
- [Protocol](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/iap/Protocol.java): command/writeCommand boundary.

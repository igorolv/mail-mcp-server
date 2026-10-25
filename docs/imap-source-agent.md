# docs/imap-source-agent.md — read-only IMAP source and spike

Read before changing `source/imap/`, authentication, wire commands or the IMAP spike.
Design: plan.md 5.2, 5.6, decisions П3, П4, П8; background: research/RT-04-imap-gmail-mailru.md.

## Implemented scope

Angus Mail 2.0.5 with Jakarta Mail API 2.1.5. `ImapClient` lists folders and downloads the last
message in a selected folder as original MIME. This is stage-1 connectivity code, not sync,
search or a mail MCP tool. Gmail labels/deduplication, CONDSTORE/QRESYNC and paging are
not implemented. Only `listConnections` remains exposed through MCP.

Construction is lazy: `MailConnectionFactory` creates an IMAP client without contacting a server.
`sourceAvailable` is true for both EWS and IMAP. Gmail OAuth2/XOAUTH2 and the user-run `gmail-oauth`
command are implemented; setup and token lifecycle are in `docs/gmail-oauth-agent.md`.
Angus objects stay private: source records contain folder metadata or saved-MIME metadata only.

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
- Failure closes the client permanently. A download also ends its single-socket session; create
  another client only as part of an explicit new request. Future sync must revisit this lifecycle
  with tests for reconnects and failed credentials; never silently reauthenticate.
- Provider exception messages and causes can contain credentials or mail data. `ImapException`
  returns only a fixed error category/message. The spike withholds raw loader errors too.
- Session debug is off and its debug stream discarded. Angus/Jakarta JUL categories and their
  Logback counterparts are disabled to prevent protocol/authentication logging.

## Read-only command guard

Allowed control commands: CAPABILITY, NOOP, LOGOUT, LOGIN, AUTHENTICATE (PLAIN/LOGIN through the
configured provider), LIST, LSUB, NAMESPACE, STATUS, EXAMINE, UNSELECT and ENABLE (UTF8 negotiation).
OAuth connections use AUTHENTICATE with XOAUTH2 arguments; the wrapper acknowledges OAuth error
challenges with an empty response instead of resending credentials (see gmail-oauth-agent.md).
FETCH/UID FETCH is allowed only with UID, FLAGS, INTERNALDATE, RFC822.SIZE and BODY.PEEK[]
(optionally a partial byte range). Command text containing CR/LF is rejected.

SELECT, STORE, EXPUNGE, CLOSE, APPEND, COPY, MOVE, folder/subscription/ACL/quota mutations,
RFC822 body retrieval and non-PEEK BODY retrieval are rejected before they reach the socket.
The guard checks provider-generated commands as well as application calls; keep this seam on upgrades.
Folder names are passed as encoded Angus arguments, never interpolated into command text.

## MIME download

`downloadLastMessage(folderName, target, maxBytes)`:

1. Open through `Folder.READ_ONLY` (EXAMINE). Empty folders return an empty result.
2. Pick the last mailbox sequence position, not the Date-header maximum. Fetch UID and flags;
   retain UIDVALIDITY with UID. Sequence numbers are not persistent message keys.
3. Set PEEK on the message; stream `getMimeStream()` into a random local `.part` file with a
   64 MiB default byte limit and SHA-256. Do not trust declared message size.
4. Call `invalidateHeaders()` to invalidate cached flags, then fetch FLAGS again. Comparing cached
   flags is not a read-only check. A flag change fails the operation; it may reflect concurrent activity.
5. Publish by moving to the destination without replacement only after success. An existing original
   is never overwritten. Errors delete the partial file where possible.
6. Force-close the selected folder's socket (no CLOSE/EXPUNGE), then close the client.

Original MIME is local data, never model output. The future output filter must mediate folder names
and all mail-derived strings. The spike prints counts rather than folder names or subjects.

## User-run spike

See build-config-agent.md for connection examples and commands. `imap-spike [connection]` selects
the only IMAP connection when its name is omitted; multiple IMAP connections require a name.
Only the user runs credentialed probes. Never read the real connections file or saved original.

Expected report:

```text
IMAP spike (read-only: LIST, EXAMINE, BODY.PEEK; one sign-in attempt)
Connection: gmail (imap, app-password; address and credentials hidden)
Step 1/2 LIST: OK, folders: <n> (names hidden)
Step 2/2 EXAMINE inbox and BODY.PEEK: OK
  UIDVALIDITY: <n>, UID: <n>
  MIME: <bytes> bytes, SHA-256 <hex>
  Seen after download: <bool> (before: <bool>)
  saved to: <data-dir>\gmail\spike\inbox-last-<time>.eml
RESULT: OK
```

Exit codes: 0 success (including an empty inbox), 2 configuration/selection, 3 sign-in rejected,
4 IMAP/network/limit/local-save error, 1 unexpected error with details withheld. A true→true result
does not verify an unread message. Record anonymized user results in plan.md; do not copy local
user paths, addresses or original contents into tracked files.

Verified on 2026-10-25 by a user-run Mail.ru spike: LIST returned 41 folders, EXAMINE and
BODY.PEEK succeeded, and one MIME original of 25831 bytes was saved. Seen was true both before
and after, so an unread message was not tested. See plan.md 3.4. An earlier user run rejected
sign-in for both Mail.ru and Gmail; the report did not include a provider reason. Gmail still
has no successful user-run result. Do not infer server extensions from the folder count.
The user deferred Gmail setup on 2026-10-25; their local connections are `work` and `mailru`.
Gmail and OAuth2 support remain available for later use.

## Offline verification and primary references

`ImapClientTest` uses only a loopback fake endpoint: one LOGIN or AUTHENTICATE PLAIN exchange,
no fallback to advertised LOGIN after a PLAIN rejection, LIST, EXAMINE, exact MIME bytes,
BODY.PEEK, separate FLAGS fetches, unchanged unread flags, changed-flags failure, empty inbox,
size limit, partial cleanup, existing-target preservation, command rejection and lazy factory behavior.
`ImapSpikeCommandTest` verifies invalid configuration/selection stops before client creation and
raw configuration values do not enter the report. All tests are part of `gradlew.bat build`.

Angus 2.0.5 sources inspected on 2026-10-25:

- [IMAPStore](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPStore.java): mechanism selection, pool and newIMAPProtocol seam.
- [IMAPMessage](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPMessage.java): getMimeStream, PEEK and invalidateHeaders.
- [IMAPFolder](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPFolder.java): READ_ONLY/EXAMINE, flags fetch and forceClose.
- [Protocol](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/iap/Protocol.java): command/writeCommand boundary.

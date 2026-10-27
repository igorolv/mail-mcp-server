# docs/imap-source-agent.md — read-only IMAP source

Read before changing `source/imap/`, authentication or wire commands.
Design: plan.md 3.1 and 3.6, decisions П3 and П4; background: research/RT-04-imap-gmail-mailru.md.

## Implemented scope

Angus Mail 2.0.5 with Jakarta Mail API 2.1.5. `ImapMailSource` implements `MailSource` on top
of `ImapClient`: filtered mail folders, changes by UIDVALIDITY and folder UIDs, metadata snapshots and original
MIME downloads by UID. One source owns one lazy session for any number of queries and downloads
across folders. `mail-sync` uses the same `SyncRunner`, store and catalog as EWS; MCP tools read
the local copy (`docs/tools-agent.md`). Gmail labels/deduplication, CONDSTORE/QRESYNC
and paging are not implemented.

`MailConnectionFactory` creates a connection with a supplier; `openSource()` creates a fresh
`ImapMailSource` without contacting a server. The caller closes the source. EWS connections get
an `EwsMailSource` the same way (`docs/ews-source-agent.md`).
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
  returns only a fixed error category/message. `mail-sync` withholds raw loader errors too.
- Session debug is off and its debug stream discarded. Angus/Jakarta JUL categories and their
  Logback counterparts are disabled to prevent protocol/authentication logging.

## Read-only command guard

Allowed control commands: CAPABILITY, NOOP, LOGOUT, LOGIN, AUTHENTICATE (PLAIN/LOGIN through the
configured provider), LIST, LSUB, NAMESPACE, STATUS, EXAMINE, CLOSE, UNSELECT and ENABLE (UTF8 negotiation).
OAuth connections use AUTHENTICATE with XOAUTH2 arguments; the wrapper acknowledges OAuth error
challenges with an empty response instead of resending credentials (see gmail-oauth-agent.md).
FETCH/UID FETCH is allowed only with UID, FLAGS, INTERNALDATE, ENVELOPE, RFC822.SIZE and BODY.PEEK[]
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
removed and all present refs added (`reset=true`, `rescanned=false`). EWS uses `rescanned`
separately for invalid SyncState recovery without invalidating known refs. Old status JSON without
`rescanned` reads as false. Cursors and download refs are checked before any I/O.

`download(folderName, uid, target, maxBytes)`:

1. Validate the folder, positive UID and positive byte limit, then reuse or EXAMINE the folder.
2. Resolve the UID; a missing message raises `MessageUnavailableException(GONE)`.
3. Set PEEK and fetch `RFC822.SIZE` using `FetchProfile.Item.SIZE`. A declared size above the
   limit raises `MessageUnavailableException(TOO_LARGE)` before fetching the body. Do not call
   `IMAPMessage.getSize()`: it fetches ENVELOPE unnecessarily on the download path.
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

## Remote identity metadata

`ImapMailSource.metadata(folder, expectedCursor, refs)` requires the UIDVALIDITY cursor just
returned by changes. `ImapClient.metadata` closes/reopens the folder with EXAMINE, checks that
UIDVALIDITY still matches before trusting reused UIDs, then resolves and fetches refs in batches
of at most 100. A mismatch closes the client and aborts the run without retry. Missing/expunged
items are omitted; other protocol/authentication/transport errors abort.

The Angus 2.0.5 profile uses ENVELOPE and UID. Offline fake-server tests verify the exact emitted
attributes `(ENVELOPE INTERNALDATE RFC822.SIZE UID)`; ENVELOPE already supplies the date and size
fetch attributes, so adding SIZE to the profile would duplicate RFC822.SIZE. Cached envelope
values provide Message-ID, sent date, decoded subject and From. Exactly one non-group sender
address is required; optional missing fields disable identity matching. No body or arbitrary
header fetch, CONDSTORE or Gmail extension is used. The exact attribute guard remains in place;
adding ENVELOPE permits no RFC822 body retrieval, non-PEEK BODY or write command.

Every present ref, including known messages, is read once per folder/run to seed old local
fingerprints and detect retained copies. Seeding needs neither reprocessing nor repeat MIME
downloads; a move before seeding uses normal download. Unique complete metadata equality remains
a heuristic: different bytes may share it. A matched move preserves UUID, directory, original
MIME SHA-256/time and all saved content; reused moves increase no downloaded, failed or removed
counter. Both ends of UIDVALIDITY resets are excluded. The runner discovers all folders before
removal, writes matches first and commits before saving all cursors; reconciliation repairs an
interrupted metadata/index reassignment. See `docs/storage-agent.md` for exact matching/recovery
rules. Availability on the user's servers remains to verify (проверить); only synthetic protocol
tests have been run for this metadata path.

Original MIME is kept on disk as downloaded. Under the 2026-10-25 decisions mail content and
local paths may reach the model through tools, with an optional output filter (plan.md 3.1,
`docs/redaction-agent.md`). `mail-sync` prints folder paths and counts; anonymize folder paths before sharing
its output. Only the user runs it; agents never open the real connections file or saved originals.

Verified by user-run Mail.ru checks on 2026-10-25: LIST returned 41 folders and a 25831-byte
MIME download succeeded with EXAMINE and BODY.PEEK. A later check through `MailSource` listed
39 sync folders, enumerated about 13 thousand inbox UIDs and downloaded the highest UID in
one session. The downloaded message was already read; unread behavior was not tested.
See plan.md 2.2. Gmail has no successful user-run result; the user deferred its setup on
2026-10-25. Gmail and OAuth2 support remain available for later use.

## Offline verification and primary references

`ImapClientTest` and `ImapMailSourceTest` share `FakeImapServer`, a strict single-socket
loopback endpoint with synthetic ASCII mail. Tests cover one LOGIN/PLAIN/XOAUTH2 exchange,
no fallback after rejection, EXAMINE/CLOSE folder switching, UID listing, filtering, new and
removed refs, UIDVALIDITY resets, input validation before commands, exact MIME and SHA-256,
BODY.PEEK only, missing UIDs, declared and streamed size limits, continued use after unavailable
messages, partial cleanup, existing-target preservation, command rejection and lazy factories.
All tests are part of `gradlew.bat build`.

Angus 2.0.5 sources inspected on 2026-10-25:

- [IMAPStore](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPStore.java): mechanism selection, pool and newIMAPProtocol seam.
- [IMAPMessage](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPMessage.java): getMimeStream, PEEK and getSizeLong.
- [IMAPFolder](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPFolder.java): READ_ONLY/EXAMINE, UID/size fetch and close(false).
- [Protocol](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/iap/Protocol.java): command/writeCommand boundary.

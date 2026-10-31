# IT-30 — RFC 8474 EMAILID as stableId

Plan item: Д33 (`docs/plan.md` section 4). Size S. One implementation session after accepted
IT-29, no commit. Re-read the accepted IT-28 and IT-29 results before editing: IT-29 changes the
same IMAP records, source classes, fake server and documents used here.

## Goal

When a non-Gmail IMAP server advertises the RFC 8474 `OBJECTID` capability, fetch each message's
`EMAILID` with the existing UID listing and metadata requests and expose it as the existing
provider-neutral `RemoteMessageInfo.stableId`.

The generic synchronization path already stores and indexes `stableId`, refreshes it without MIME
downloads and reuses an unambiguous removed message across moves and UIDVALIDITY resets. Reuse
that path unchanged. This task adds capability detection, one read-only FETCH item and its parser;
it adds no MCP field, index field, schema version, command, setting or dependency.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, this brief, Д33 in `docs/plan.md` section 4 and the
  2026-10-31 scope decision in `docs/decisions.md` section 2;
- the accepted IT-28 and IT-29 code and current documents, especially the final shape of
  `FolderUids`, the IMAP capability checks and `RemoteMessageInfo` Javadoc;
- `docs/imap-source-agent.md`, especially "Read-only command guard", "Gmail", "Remote identity
  metadata" and "Read/star observations";
- `docs/build-config-agent.md`, the logging and "User-run commands" parts: `logback-cli.xml` shows
  only WARN and above, so the INFO capability line below appears in the MCP server log file
  (`<data-dir>\logs\mail-mcp-server.log`), not in `mail-sync` output;
- `docs/storage-agent.md`, the `stableId` observation and uniqueness rules;
- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/imap/FolderUids.java`, `GmailMessageInfo.java`,
  `ImapClient.java`, `ImapMailSource.java` and `ReadOnlyImapStore.java`;
- `src/test/java/mail/mcp/source/imap/FakeImapServer.java`, `ImapClientTest.java` and
  `ImapMailSourceTest.java`;
- the existing generic tests
  `SyncRunnerTest.uidValidityResetReusesMessagesByStableId`,
  `SyncRunnerTest.uidValidityResetReusesMessagesWhoseUidsWereReassigned` and
  `SyncRunnerTest.stableIdMovesAcrossFoldersButARetainedCopyIsStoredSeparately`. Do not duplicate
  their storage/matching coverage.

## Fixed behavior

1. Detect RFC 8474 only by the case-insensitive `OBJECTID` capability, never by host name, login,
   folder name or a failed trial request. Cache the result for the IMAP session together with the
   existing `X-GM-EXT-1` check. `IMAPStore.hasCapability` reads the authenticated protocol's
   capability map; checking both values must not send another CAPABILITY command or sign in again.
   After the cached check, log exactly one safe INFO line per source session,
   `IMAP extensions: objectId={}`, with only the enabled boolean and no connection, host, login,
   folder or server text. Logs never go to `System.out`. At INFO the line reaches the MCP server
   log file; `mail-sync` hides it (`logback-cli.xml` root is WARN), and this task does not change
   either logging configuration.
2. Gmail keeps its current identity source. If `X-GM-EXT-1` is advertised, use only
   `X-GM-MSGID` as `stableId` and do not request `EMAILID`, even if the server also advertises
   `OBJECTID`. On other servers, request `EMAILID` only when `OBJECTID` is advertised.
3. Register one Angus extension FETCH item named `EMAILID`. Parse the RFC wire form
   `EMAILID (objectid)`: the value is 1–255 ASCII letters, digits, `_` or `-`. Preserve it exactly;
   ObjectID values are case-sensitive. A missing or syntactically invalid item is unknown and
   must not become a `stableId`, leak into diagnostics or trigger a replacement fetch.
4. Add `EMAILID` to both existing fetch profiles when enabled: the UID listing for known messages
   and the batched metadata fetch for added messages. This changes the attributes of those FETCH
   commands only; it adds no extra command, body/header retrieval, retry or authentication.
5. A known message observed without a valid EMAILID carries `stableId == null`, preserving its
   saved identity. An added message without one keeps the current fingerprint path. A valid
   EMAILID is stored even when labels or flags are absent; the extension item is an independent
   observation in the UID listing, as Gmail identity is now.
6. RFC 8474 permits several messages with identical immutable content to share one EMAILID and to
   have different flags/keywords. Do not assume global uniqueness or merge copies. The existing
   `SyncRunner` rule remains authoritative: reuse only when exactly one saved candidate and one
   current remote occurrence have that `stableId`; ambiguity falls back to ordinary remove and
   download behavior.
7. Do not implement RFC 8474 `MAILBOXID`, `THREADID`, EMAILID/THREADID SEARCH or consistency
   recovery for a misbehaving server. Do not change fingerprint matching or add a new safeguard;
   the task only supplies another provider value to the established `stableId` contract.

## What to build

### IMAP parser and guard

- `ReadOnlyImapStore`: add a package-visible `FetchProfile.Item` for RFC 8474 identity and a
  `FetchItem("EMAILID", ...)` parser. Register it alongside the Gmail items in
  `getFetchItems()`, but request it only through the capability-gated profiles.
- Parse and consume the parenthesized value, validate the RFC length/character set and retain the
  exact case. Record valid EMAILIDs per UID in a separate batch map, clear it in
  `clearMetadataObservations()` and expose a package-private lookup like `metadataGmail`.
- Extend `requireReadCommand` by exactly the `EMAILID` FETCH attribute. Continue to reject
  `THREADID`, arbitrary extensions, body reads other than `BODY.PEEK[]` and every mutation.

### IMAP source

- `ImapClient`: cache `X-GM-EXT-1` and `OBJECTID` capability results once per session. Add a
  package-visible `objectIdExtensions()` query for tests and source logic; it is false on Gmail so
  Gmail identity always wins. Emit the fixed safe capability INFO line once when this cached check
  is completed, without causing another protocol command.
- `FolderUids`: append a copied `Map<Long, String> emailIds` to the accepted IT-29 record shape.
  Preserve all shorter constructors and default the new component to an empty map.
- `ImapClient.folderUids`: when RFC 8474 identity is enabled, add the new profile item and copy
  every valid parsed EMAILID into `FolderUids.emailIds()`.
- `ImapClient.metadata`: add the same profile item when enabled and set `stableId` to the parsed
  EMAILID. On Gmail retain `X-GM-MSGID`; without either extension leave it null. Preserve IT-29's
  non-Gmail keyword labels independently.
- `ImapMailSource.changes`: include EMAILID-only UID observations and pass the value as
  `RemoteMessageInfo.stableId()`. Preserve IT-29 labels and the existing read/star fields.
- `RemoteMessageInfo`: update only its Javadoc to name RFC 8474 EMAILID as another current source
  of `stableId`; do not change the record or constructors.

No change to `SyncRunner`, `MessageMeta`, `MailIndex` or tools is needed. If the accepted IT-29
shape makes a mechanical signature different, adapt to it without changing this behavior.

### Tests

Extend the existing fake loopback server with synthetic `OBJECTID` capability and per-message
EMAILID responses. Use only RFC-valid fictional ids; never connect to a real server.

The following test names are exact:

- `ImapClientTest.objectIdEmailIdIsFetchedAsStableId` — prove capability detection, exact
  case-preserving parsing, UID-list and metadata propagation, the two FETCH shapes with EMAILID,
  one sign-in, the one sanitized capability INFO line and no body/write command;
- `ImapClientTest.emailIdIsNotRequestedWithoutObjectIdOrOnGmail` — prove absence on an ordinary
  server and Gmail precedence when both capabilities are advertised;
- `ImapClientTest.invalidEmailIdIsNotTrusted` — cover an invalid character and a value longer
  than 255 characters without exposing either value or issuing a replacement fetch;
- `ImapClientTest.guardAllowsEmailIdButRejectsOtherObjectIdItems` — allow EMAILID in otherwise
  valid FETCH shapes and continue rejecting THREADID and MAILBOXID;
- `ImapMailSourceTest.changesObserveEmailIdOfKnownRefs` — prove an EMAILID-only observation of a
  known UID reaches `stableId` with null fingerprint and preserves the IT-29 label behavior.

The existing generic stableId tests named under "Read first" remain the coverage for moves,
duplicate/current-copy ambiguity and UIDVALIDITY reset reuse. Do not add an IMAP-specific copy of
that synchronization logic.

## Files

Change only:

- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/imap/FolderUids.java`;
- `src/main/java/mail/mcp/source/imap/ImapClient.java`;
- `src/main/java/mail/mcp/source/imap/ImapMailSource.java`;
- `src/main/java/mail/mcp/source/imap/ReadOnlyImapStore.java`;
- `src/test/java/mail/mcp/source/imap/FakeImapServer.java`;
- `src/test/java/mail/mcp/source/imap/ImapClientTest.java`;
- `src/test/java/mail/mcp/source/imap/ImapMailSourceTest.java`;
- the documents listed below.

If another production or test file appears necessary, stop and ask instead of expanding the task.

## Documents

Update these documents to describe the implemented current behavior after IT-29:

- `README.md`: recognized IMAP moves and UID changes can reuse content through RFC 8474 EMAILID
  when the server advertises OBJECTID;
- `docs/imap-source-agent.md`: capability precedence, parser, exact FETCH shapes, allow-list,
  safe capability log (server log file only), stableId propagation and deferred Mail.ru check;
- `docs/storage-agent.md`: current `stableId` providers are Gmail `X-GM-MSGID` and non-Gmail
  RFC 8474 `EMAILID`; retain the existing uniqueness/ambiguity rules;
- `docs/tools-agent.md`: the provider-neutral reuse summary and the fact that stableId remains
  internal and is never returned.

Do not edit `docs/plan.md`, `docs/results.md`, `docs/decisions.md`, `AGENTS.md`, other briefs,
research files or `testdata/` during implementation. The recording session updates the plan and,
if needed, results after review.

## Do not

- Do not add `MAILBOXID`, `THREADID`, SEARCH, STATUS, LIST extensions, new operations or settings.
- Do not request EMAILID without `OBJECTID`, request it on Gmail, or combine it with
  `X-GM-MSGID`.
- Do not add sign-ins, retries, a separate CAPABILITY command, MIME/header downloads or mailbox
  writes.
- Do not change the MCP schema, Java API records, on-disk metadata, Lucene fields, index schema,
  matching rules, labels, redaction or user-visible tool arguments.
- Do not log ObjectID values, assume Mail.ru support or rebuild the reverted shared MIME pipeline.

## Acceptance

Run `.\gradlew.bat build` and the shared real-values check. Offline tests are sufficient to
accept the implementation. In the recording session, remove Д33 from section 4, add it to the
completed list and add this deferred check to `docs/plan.md` section 2 with the acceptance date:

1. Restart the MCP server, start a Mail.ru sync with the `sync` tool and read the fixed
   `IMAP extensions: objectId=<true|false>` line in `<data-dir>\logs\mail-mcp-server.log`.
   Never enable or paste raw protocol logs or credentials.
2. If advertised, synchronize a message, move it with the user's ordinary mail client and sync
   again. Confirm that the local key/path/content are reused with zero MIME download. A natural
   UIDVALIDITY reset may verify the same behavior later; do not trigger one deliberately.
3. If Mail.ru does not advertise `OBJECTID`, record that fact; no move test is required and the
   existing fingerprint heuristic remains in use.

Only the user runs this real-mail check. It is deferred and does not block offline acceptance or
publication of `0.1.0` under the current release decision.

## Primary reference

Checked on 2026-10-31: [RFC 8474](https://www.rfc-editor.org/rfc/rfc8474.html), especially
sections 3, 5.1, 5.3, 7 and 8.4. It defines the `OBJECTID` capability, the
`EMAILID (objectid)` FETCH response, case-sensitive 1–255 character ObjectIDs, identity across
COPY/MOVE, possible duplicate EMAILIDs for identical content and fallback advice for inconsistent
servers. The existing uniqueness rule safely refuses ambiguous duplicate IDs; broader recovery
from a server that changes IDs is intentionally outside this task.

Mail.ru support was not established by a primary source and remains the explicit user-run check
above.

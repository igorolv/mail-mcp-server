# IT-29 — Non-Gmail IMAP keywords as labels

Plan item: Д32 (`docs/plan.md` section 4). Size S. One implementation session after accepted
IT-28, no commit. Re-read the accepted IT-28 code and documents before editing: the EWS and IMAP
code changes are independent, but both briefs update the provider list for `labels`.

## Goal

Fill the existing provider-neutral `labels` field on non-Gmail IMAP servers from user-defined
keywords in the message's `FLAGS`. Angus already parses those flags for the UID listing and the
metadata fetch; use `jakarta.mail.Flags.getUserFlags()` without adding a command or round trip.

The storage, index, search and MCP response layers already persist and expose `labels`. Reuse that
path unchanged. Gmail continues to obtain labels only from `X-GM-LABELS`.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, this brief, Д32 in `docs/plan.md` section 4 and the
  2026-10-31 scope decision in `docs/decisions.md` section 2;
- the accepted IT-28 result and its current `README.md`, `docs/storage-agent.md`,
  `docs/tools-agent.md` and `docs/search.md` label descriptions;
- `docs/imap-source-agent.md`, especially "Read-only command guard", "MIME download",
  "Read/star observations" and "Gmail";
- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/imap/FolderUids.java`, `GmailMessageInfo.java`,
  `ImapClient.java`, `ImapMailSource.java` and `ReadOnlyImapStore.java`;
- `src/test/java/mail/mcp/source/imap/FakeImapServer.java`, `ImapClientTest.java` and
  `ImapMailSourceTest.java`;
- `SyncRunnerTest.labelsAreStoredAndRefreshedWithoutDownload` only to confirm that the shared
  label path already covers persistence, clearing and indexing. Do not duplicate it.

## Fixed behavior

1. On a server that does not advertise `X-GM-EXT-1`, derive labels from the parsed `Flags` object
   with `getUserFlags()`. This API already separates user-defined keywords from system flags such
   as `\Seen`, `\Flagged`, `\Answered`, `\Deleted` and `\Draft`; none of those system flags
   becomes a label.
2. Exclude every user flag whose returned name starts with `$`, including standardized/client
   bookkeeping keywords such as `$Forwarded`, `$MDNSent`, `$Junk` and `$NotJunk`. Also exclude the
   junk-filter keywords `Junk`, `NonJunk` and `NotJunk` without a `$`, compared case-insensitively,
   which some clients (for example Thunderbird, to verify) set on IMAP messages (user decision of
   2026-11-01). Consequently tags stored as `$label1`…`$label5` are not labels either. There is no
   setting or allow-list exception in this task. Preserve every other keyword exactly as returned,
   including case; sort the resulting strings by Java natural order for stable comparisons.
3. A parsed `FLAGS` value with no remaining keyword produces a known empty label list. Missing or
   unparsable `FLAGS` produces `labels == null`, so it does not clear a saved observation. Keyword
   names are case-independent in Jakarta Mail/IMAP, but the displayed spelling returned by the
   server is preserved; do not lowercase or otherwise normalize it.
4. Apply the same mapping in both existing paths: the UID listing supplies observations for known
   messages on every ordinary sync, and the metadata fetch supplies labels for added messages.
   Do not add FLAGS, FETCH, EXAMINE, CAPABILITY or any other protocol command: both fetch profiles
   already request FLAGS.
5. When `X-GM-EXT-1` is advertised, ignore IMAP user keywords for `labels` and retain the current
   Gmail behavior exactly: `X-GM-LABELS` is the sole label source and `X-GM-MSGID` remains the
   stable id. A missing Gmail extension item stays unknown; it must not fall back to user flags.
6. The existing generic synchronization rules do the rest: a changed or empty list refreshes
   `meta.json` and the Lucene document without MIME download or conversion; a null list preserves
   saved labels. Counts, cursor handling, UIDVALIDITY reset behavior, move matching, read/star
   observations and the one-sign-in/no-retry policy remain unchanged.

## What to build

### IMAP source

- `ImapClient`: add one small private keyword-normalization helper returning null for absent
  flags and an immutable sorted list otherwise. In `metadata`, use Gmail labels on Gmail and this
  helper on non-Gmail servers.
- `FolderUids`: append `Map<Long, List<String>> keywords`, copied like its other maps. Keep the
  existing short constructors and default the new component to an empty map.
- `ImapClient.folderUids`: on non-Gmail servers, populate the new map from every successfully
  parsed FLAGS item. Leave it empty on Gmail. Do not change the fetch profile or command text.
- `ImapMailSource.changes`: pass the per-UID keywords as `RemoteMessageInfo.labels()` for
  non-Gmail observations; continue to prefer the existing Gmail labels when a Gmail observation
  is present. A UID with parsed empty FLAGS must carry an empty list.
- `RemoteMessageInfo`: update only its Javadoc so it names non-Gmail IMAP keywords as another
  provider of `labels`; do not change the record or constructors.

`ReadOnlyImapStore` already records the actual FLAGS item independently for UID listings and
metadata responses. Read it to preserve its missing/malformed semantics, but no production change
there should be necessary.

### Tests

Use only the existing fake loopback IMAP server. Its synthetic per-message FLAGS string is enough
to return system flags, ordinary keywords and `$`-prefixed keywords; do not add a new protocol
command or external connection.

The following test names are exact:

- `ImapClientTest.nonGmailKeywordsBecomeSortedLabelsWithoutDollarKeywords` — cover system flags,
  at least two ordinary keywords, at least one `$` keyword, `Junk` and `nonjunk` (excluded in any
  case), stable sorted spelling, the UID listing and metadata paths, and unchanged FETCH command
  shapes;
- `ImapClientTest.gmailContinuesToUseOnlyGmailLabels` — return both IMAP user keywords and
  `X-GM-LABELS`, then prove only the Gmail labels are exposed in both paths;
- `ImapMailSourceTest.changesObserveNonGmailKeywordsAsLabels` — prove a known ref receives
  keywords from the UID listing, while parsed FLAGS containing only system/`$` flags produces an
  empty list rather than null.

Extend the existing missing/unparsable FLAGS assertions where they already exist to require null
labels. The existing generic label tests remain the coverage for persistence, clearing, index
updates, label search and tool responses.

## Files

Change only:

- `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/source/imap/FolderUids.java`;
- `src/main/java/mail/mcp/source/imap/ImapClient.java`;
- `src/main/java/mail/mcp/source/imap/ImapMailSource.java`;
- `src/test/java/mail/mcp/source/imap/FakeImapServer.java` only if its existing FLAGS fixture
  support cannot express a named test;
- `src/test/java/mail/mcp/source/imap/ImapClientTest.java`;
- `src/test/java/mail/mcp/source/imap/ImapMailSourceTest.java`;
- the documents listed below.

If `ReadOnlyImapStore` or another production/test file appears necessary, stop and ask instead of
expanding the task. A change to `ReadOnlyImapStore` would indicate that the existing FLAGS
observation is insufficient and must be reviewed separately.

## Documents

Update these documents to describe the implemented current behavior after IT-28:

- `README.md`: cards can carry Gmail labels, Outlook categories or non-Gmail IMAP keywords;
- `docs/imap-source-agent.md`: keyword source, filtering, null/empty behavior, no extra FETCH and
  the deferred Mail.ru check;
- `docs/storage-agent.md`: the current providers of neutral `labels` now include IMAP keywords;
- `docs/tools-agent.md`: the same provider list and unchanged response/search contract;
- `docs/search.md`: searchable labels include non-`$` IMAP keywords, with Mail.ru support subject
  to the server returning them.

Do not edit `docs/plan.md`, `docs/results.md`, `docs/decisions.md`, `AGENTS.md`, other briefs,
research files or `testdata/` during implementation. The recording session updates the plan and,
if needed, results after review.

## Do not

- Do not add IMAP commands, fetch items, sign-ins, retries, writes, `STORE`, `SELECT`, CONDSTORE or
  QRESYNC.
- Do not combine IMAP keywords with Gmail labels or change Gmail normalization.
- Do not expose system flags, any `$`-prefixed keyword or `Junk`/`NonJunk`/`NotJunk` as a label;
  do not add a setting to configure that policy or extend the excluded list.
- Do not change the MCP schema, Java API records, `MessageMeta`, `MessageCard`, Lucene fields,
  index schema version, search analyzer or label redaction.
- Do not assume that Mail.ru supports keywords, add provider host-name checks or rebuild the
  reverted shared MIME pipeline.

## Acceptance

Run `.\gradlew.bat build` and the shared real-values check. Offline tests are sufficient to
accept the implementation. In the recording session, remove Д32 from section 4, add it to the
completed list and add this deferred check to `docs/plan.md` section 2 with the acceptance date:

1. If the user's ordinary mail client can assign a custom tag/keyword to a message in an included
   Mail.ru folder, the user assigns one, runs `mail-sync mailru`, then confirms that `labels` and
   `field: "label"` reflect it without a MIME download.
2. The user removes the tag, synchronizes again and confirms that the label disappears. If the
   client or Mail.ru does not support custom keywords, record that fact instead; do not use this
   server or a script to write one.

This determines whether Mail.ru stores and returns IMAP keywords at all. Only the user performs
the real-mail actions and sign-in. The check is deferred and does not block offline acceptance or
publication of `0.1.0` under the current release decision.

## Primary references

Checked on 2026-10-31:

- [RFC 9051, section 2.3.2](https://www.rfc-editor.org/rfc/rfc9051.html#name-flags-message-attribute)
  distinguishes system flags (names beginning with `\`) from server-defined keywords and defines
  the `$` keywords used by clients;
- [Jakarta Mail `Flags`](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/flags)
  documents that user flags are case-independent and that `getUserFlags()` returns an empty array
  when none are set;
- [Angus Mail `Flags.Flag`](https://eclipse-ee4j.github.io/angus-mail/docs/api/jakarta.mail/jakarta/mail/Flags.Flag.html)
  documents the separate predefined system flags and the `USER` capability marker.

Mail.ru keyword support was not established by a primary source and remains the explicit
user-run check above.

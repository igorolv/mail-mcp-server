# IT-27 — Gmail: All Mail once with labels, deduplication by X-GM-MSGID

Plan item: Д7 (`docs/plan.md` section 4). Size M. One implementation session, no commit.

## Goal

For a Gmail mailbox (IMAP server advertising `X-GM-EXT-1` with a folder carrying the `\All`
attribute) synchronize that one All Mail folder instead of every label folder, store each
message's Gmail labels, and identify a Gmail message by `X-GM-MSGID` so a UID change does not
download its MIME again. Other IMAP servers and EWS keep their current behavior.

Revised on 2026-10-28 after review: the source, model, index and wire layers are
provider-neutral. `X-GM-MSGID` is stored as `stableId` and Gmail labels as `labels`, so EWS
categories, IMAP keywords and RFC 8474 `EMAILID` can fill them later (`docs/backlog.md`, Д31–Д33)
without a contract or schema change. Gmail-specific code stays in `source/imap/`.

The user chose this scope on 2026-10-28: one folder with labels (not several folders per
message), labels visible in cards and searchable through a `label` field group, and real
reuse by MSGID (not merely storing the identifier).

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`;
- `docs/imap-source-agent.md`, `docs/storage-agent.md`, `docs/tools-agent.md`, `docs/search.md`;
- `src/main/java/mail/mcp/source/imap/` (`ImapClient`, `ReadOnlyImapStore`, `ImapMailSource`,
  `ImapFolderInfo`, `FolderUids`), `src/main/java/mail/mcp/source/RemoteMessageInfo.java`;
- `src/main/java/mail/mcp/sync/SyncRunner.java`, `MessageProcessor.java`;
- `src/main/java/mail/mcp/index/MailIndex.java`, `MailIndexReader.java`, `MailAnalyzers.java`,
  `SearchField.java`;
- `src/main/java/mail/mcp/model/MessageMeta.java`, `MessageCard.java`;
- `src/main/java/mail/mcp/api/MessageSummary.java`, `SearchHitInfo.java`, `ThreadMessageInfo.java`,
  `GetMessageResult.java`;
- `src/main/java/mail/mcp/tools/MailTools.java`, `SearchTools.java`, `ThreadTools.java`;
- `src/test/java/mail/mcp/source/imap/FakeImapServer.java`, `ImapClientTest.java`,
  `ImapMailSourceTest.java`, `src/test/java/mail/mcp/sync/FakeMailSource.java`.

## Fixed decisions

1. Gmail is detected by the `X-GM-EXT-1` capability, not by host name, login or folder name.
2. Only the folder whose LIST attributes contain `\All` (case-insensitive) is synchronized when
   the capability is present and exactly one message-holding folder has it. Otherwise the current
   folder filtering applies unchanged.
3. Fetch `X-GM-MSGID` and `X-GM-LABELS` together with the existing UID listing and metadata
   fetches. No new round trips, no CONDSTORE/QRESYNC, no Gmail API, no label writes.
4. Stored label names: decode IMAP modified UTF-7 (Angus `BASE64MailboxDecoder`), drop `\All`,
   drop one leading backslash from other system labels (`\Inbox` becomes `Inbox`), sort
   naturally. An observation without the item leaves stored labels unchanged; an empty list means
   "no labels".
5. `X-GM-MSGID` is kept as a decimal string (`String`) in the neutral `stableId`, not a Java number.
6. A removed converted message with MIME and a `stableId` is reused for an added message with the
   same `stableId` when it is the only stored copy and the only current remote copy of it, in the
   same matching pass and under the same uniqueness rule as the Message-ID heuristic. This covers
   UIDVALIDITY resets (also with reused UID numbers) and moves within or across folders. A copy
   present in another included folder at the same time remains a separate stored message (one
   directory per remote ref), as for every provider.
7. Index schema becomes `5`: stored `labels` and unstored `label` fields plus stored `stableId`.
   Old indexes rebuild from disk as they already do; no new command, setting or migration.
8. Search gets one more field group, `label`, matching complete label words case-insensitively
   without word forms. It is part of `any`.
9. Labels are text in responses and pass through `SecretRedactor` like folder paths. `false` vs
   absent keeps the flag convention: absent means unknown.

## What to build

### Sources (`source/`)

- `RemoteMessageInfo`: append nullable `String stableId` and `List<String> labels`; keep the two
  existing constructors working.
- New package-private record `source/imap/GmailMessageInfo.java`: `(String msgId, List<String> labels)`,
  both nullable, one parsed FETCH response; its constructor normalizes labels (decision 4).
- `FolderUids`: add `Map<Long, GmailMessageInfo> gmail`; keep the short constructors.
- `ReadOnlyImapStore`:
  - a package-visible `FetchProfile.Item` for Gmail data and two `FetchItem` instances for
    `X-GM-MSGID` (atom to string) and `X-GM-LABELS` (string list decoded with
    `BASE64MailboxDecoder`), returned from `getFetchItems()` of the protocol subclass;
  - record the parsed extension items per UID in every FETCH response (UID listing and metadata),
    cleared by the existing `clearMetadataObservations`;
  - allow exactly those two additional FETCH items in `requireReadCommand`; everything else stays
    rejected.
- `ImapClient`:
  - `gmailExtensions()`: cached `X-GM-EXT-1` capability check, sanitized failure like the other
    operations;
  - `folderUids` and `metadata` add the Gmail fetch item only when the capability is present and
    attach its values to the returned observations;
- `ImapMailSource`:
  - `folders()` returns only the `\All` folder for Gmail, otherwise unchanged;
  - `changes()` puts MSGID and labels of known refs into `observed`, also for a UID whose FLAGS
    were not parsed;
  - `metadata()` passes both through.

### Store, index and search

- `MessageMeta`: append nullable `String stableId` and `List<String> labels` (copied); keep existing
  constructors; old `meta.json` reads both as null.
- `MailIndex`: `SCHEMA = "5"`; store `stableId` (`StringField`, stored), `labels` (`StoredField`,
  label names joined by `"\n"`, empty string when known empty) and one unstored `label` token per
  label; `keysByStableId(stableId)` is a term lookup like `keysByMessageId`;
  `folderObservations(folderKey)` replaces `folderFlags` and returns `IndexedObservation(key, read,
  flagged, labels, stableId)` records; `reconcile` compares stored labels and `stableId` with
  `meta.json` as it does for flags.
- `MailAnalyzers`: a `label` analyzer of `PatternTokenizer("[^\\p{L}\\p{N}]+")` and
  `LowerCaseFilter`, without stemming and without word forms.
- `SearchField`: add `LABEL("label", "label")` and include `"label"` in `ANY`.
- `MailIndexReader`: card fields include `labels`; `MessageCard.labels` is null when the field is
  absent, an empty list for the empty stored value, otherwise the split values.
- `MessageCard`: append nullable `List<String> labels`; keep the short constructor.

### Synchronization

- `MessageProcessor`: preserve `stableId` and `labels` when it rewrites state.
- `SyncRunner`:
  - downloaded and failed entries take `stableId`/`labels` from the source observation;
  - `identity(...)` refreshes MSGID only when the observation has one, and labels only when the
    observation has a list; fingerprints and flags keep their current rules;
  - `changed(...)` also compares the observed labels and `stableId` with `folderObservations`, so a
    label edit is a metadata and document update without download, conversion or content writes;
  - the existing move pass `matches(...)` identifies a candidate by its `stableId` when it has one,
    otherwise by its fingerprint, under the uniqueness rule of decision 6; a UIDVALIDITY reset and
    the same folder exclude only fingerprint matches. Reused entries
    increase no downloaded/failed/removed counter, keep UUID, directory and content, and take the
    destination ref, labels and flags.

### Tools and wire contract

- `MessageSummary`, `SearchHitInfo`, `ThreadMessageInfo` and `GetMessageResult`: optional
  `List<String> labels`, omitted when unknown (null), filtered with `redactor.texts`; descriptions
  name Gmail only as the example provider. `stableId` is never returned.
- `MailTools.getMessage`: labels from metadata; `listMessages` cards map the new field.
- `SearchTools`/`ThreadTools`: map card labels; the `field` parameter description lists `label`.
- The `search` tool description mentions labels only where the caller needs it: Gmail messages
  keep their labels here instead of folders.

## Tests (names are exact)

`ImapClientTest`:
- `gmailUidListingAndMetadataFetchMessageIdAndLabels`
- `gmailLabelsAreDecodedWithoutAllAndLeadingBackslash`
- `gmailExtensionIsAbsentForOtherServers`

`ImapMailSourceTest`:
- `gmailSyncsOnlyTheAllMailFolder`
- `gmailFallsBackToAllFoldersWithoutAnAllMailFolder`
- `changesObserveGmailMessageIdAndLabelsOfKnownRefs`

`SyncRunnerTest`:
- `labelsAreStoredAndRefreshedWithoutDownload`
- `uidValidityResetReusesMessagesByStableId`
- `uidValidityResetReusesMessagesWhoseUidsWereReassigned`
- `stableIdMovesAcrossFoldersButARetainedCopyIsStoredSeparately`
- `missingObservationKeepsStoredLabelsAndStableId`

`MailIndexTest`:
- `labelSearchMatchesLabelWordsAndCardsCarryLabels`

`SearchToolsTest`:
- `searchByLabelFieldReturnsLabelledMessages`

`MailToolsTest`:
- `getMessageReturnsStoredLabels`

`ThreadToolsTest`:
- `threadCardsCarryLabels`

Extend the two fake servers with synthetic Gmail values only: additional capabilities, extension
items in FETCH responses, labels encoded with `BASE64MailboxEncoder`, folder attributes `\All`.
No test contacts a network.

## Do not

- Do not add several folders or refs per stored message, a per-label folder model, the Gmail API,
  CONDSTORE/QRESYNC, label writes, new settings, new Maven dependencies or a migration command.
- Do not change EWS behavior, move/fingerprint rules, folder keys, the response filter semantics
  or existing tool arguments.
- Do not raise the index schema above 5 and do not add a new stored field beyond the named ones.
- Do not edit `docs/plan.md`, `docs/results.md`, `docs/decisions.md`, `AGENTS.md`, research
  results or `testdata/`.

## Documents

Update, describing the code as it now is:

- `docs/imap-source-agent.md` (capability, fetch items and guard, All Mail folder, labels, MSGID,
  what remains unimplemented);
- `docs/storage-agent.md` (meta.json example and fields, index fields, schema 5, MSGID reuse,
  label refresh);
- `docs/tools-agent.md` (cards, `getMessage`, search field table, schema 5);
- `docs/search.md` (the `label` field group, Gmail All Mail and labels for users);
- `README.md` only if it describes Gmail folders or labels.

## Report

Use the implementation report template of `docs/impl-tasks/README.md`. In "For the user to run",
name a Gmail `mail-sync` followed by `listFolders`, `search` with `field=label` and `getMessage`,
and say that the UIDVALIDITY-reset reuse needs a real reset to be observed.

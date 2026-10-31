# mail-mcp-server — plan

Working project document: status, checks pending on real mail, design principles and the scope of
version `0.1.0`. Related documents:

- [results.md](results.md) — verified environment facts and real-mailbox results;
- [decisions.md](decisions.md) — current user decisions and what requires a user decision;
- [backlog.md](backlog.md) — improvements after version `0.1.0`;
- `docs/*-agent.md` — engineering documents describing the code (listed in [AGENTS.md](../AGENTS.md));
- `docs/connections.md`, `clients.md`, `commands.md`, `search.md`, `security.md` and
  [README.md](../README.md) — user documentation;
- [research/](research/README.md) — research from 2026-10-24.

Discussion history is in git history.

**Status (2026-11-01): the draft is complete; the first published version, `0.1.0`, is in
preparation (Д18).** All three user mailboxes work: corporate Exchange (EWS), Mail.ru and Gmail
(IMAP). Implemented:

- full and incremental synchronization;
- messages and attachments on disk, with bodies and attachment text in Markdown;
- search with Russian and English morphology, including attachment text;
- an optional user word forms file and the offline `mail-analyze` command;
- date filters in search and message lists, and date-only search;
- local conversations across folders of one connection;
- message copies counted once in search and conversations, with other copies listed in `copies`;
- detection of unambiguous moves between folders without downloading MIME again;
- snapshots of read status and user flags/stars, without writing to the mailbox;
- Gmail synchronized once through All Mail, with its labels on messages and a `label` search field;
- Outlook categories exposed and searchable through the same provider-neutral labels;
- expansion of attached messages and archives (EML, ZIP, MSG, TNEF), and DOC/XLS/PPT text;
- an optional secret filter in responses;
- nine MCP tools.

Features implemented but not yet checked on real mail are in section 2. Next is version `0.1.0`
(section 4); other improvements are in [backlog.md](backlog.md).

## 1. Idea

A local stdio MCP server through which an AI agent (Claude Code, opencode, Codex, etc.) reads
several mailboxes: corporate Exchange over EWS, Mail.ru and Gmail over IMAP. The server:

- downloads messages unchanged (`.eml`);
- saves attachments as files and converts bodies and attachments to Markdown;
- builds a local Lucene index;
- answers the agent from local data only.

The server writes nothing to the mailbox. It follows the sibling projects:
`C:\git\redmine-mcp-server` (attachments, bounded responses) and `C:\git\jdbc-mcp-server`
(connections list).

Why build our own:

- the OWA web interface is inconvenient;
- existing mail MCP servers lack attachment search, a local index with Russian morphology and
  support for Exchange 2013 over NTLM.

## 2. Checks pending on real mail

Implemented and accepted through offline verification; the user runs the checks. Record results
in [results.md](results.md) and remove the corresponding row here once all its checks pass;
when only part passes, retain only the remaining checks. The EML/ZIP/MSG/TNEF and legacy Office
reprocessing check for `work` and `mailru` is the only real-mail check required before publishing
`0.1.0` (decision of 2026-10-28); the remaining checks happen during use.

| Feature | How to check | Accepted |
|---|---|---|
| **Before publication.** EML/ZIP/MSG/TNEF expansion and DOC/XLS/PPT text (`docs/mime-agent.md`) | Run `mail-reprocess work` and `mail-reprocess mailru`, then search text in files from archives and older Office formats | 2026-10-27; 582 tests, 1 expected skip, 0 failures |
| Search by full address without display names (`docs/search.md`) | Restart the MCP server and search `from`/`to` by a full address | 2026-10-26 |
| Background sync summary and a separate EWS rescan indicator (`docs/tools-agent.md`) | On the next user-requested `sync`, check for one summary line in the log; observe `rescanned` only during natural EWS recovery, without deliberately resetting the cursor | 2026-10-27; 592 tests, 1 expected skip, 0 failures |
| Date filters and date-only search (`docs/search.md`) | After rebuilding the index to schema 5, restart the MCP server; check a known period with `search` without words and `listMessages` with `since`/`until` on saved data; no separate sync is needed to check dates | 2026-10-27; 603 tests, 1 expected skip, 0 failures |
| Local conversations (`docs/search.md`) | After rebuilding the index to schema 5, restart the MCP server; call `getThread` with the key of a known conversation and check its membership, order and pages on saved data; no separate sync is needed to check conversations | 2026-10-27; 612 tests, 1 expected skip, 0 failures |
| Moves without downloading again (`docs/storage-agent.md`) | The user synchronizes a test message, records its local key and path, moves it between included folders in their mail client and syncs again; check that key, path and content are preserved and MIME is not downloaded again. Known messages are not given a bulk fingerprint pass; removed old messages can use saved headers, subject to the matching rules in `docs/storage-agent.md`. End-to-end move reuse remains to verify | 2026-10-27; 635 tests, 1 expected skip, 0 failures |
| 5 MiB XLSX text conversion threshold (`docs/mime-agent.md`) | An optional user-run `mail-reprocess` applies the threshold to saved messages: for XLSX files over 5 MiB, old text disappears from `.md` files and search, while originals remain. Until reprocessing, old text is available; reads and `mail-reindex` retain it for `converted` messages | 2026-10-27; 640 tests, 1 expected skip, 0 failures |
| read/flagged snapshots (`docs/storage-agent.md`, `docs/tools-agent.md`) | EWS and Mail.ru delivery of read/flag changes and selective metadata updates passed ([results.md](results.md), sections 1–2). Remaining: compare `getMessage`, `listMessages`, text search, date search and `getThread` after setting and clearing flags in the mail client and running sync; confirm no MIME download or content change | 2026-10-27; 655 tests, 1 expected skip, 0 failures |
| Message copy grouping (`docs/search.md`) | Restart the MCP server and find a saved message present in several folders, such as a message sent to yourself: `search` and `getThread` show one card, with other keys and folders in `copies`; totals and paging count the group once. No index rebuild or sync is needed for current schema 5 | 2026-10-27; 665 tests, 1 expected skip, 0 failures |
| User word forms (`docs/search.md`) | Optionally create `word-forms.txt`, check rules with `mail-analyze`, run `mail-reindex` for each connection and search for irregular forms on saved data. After editing, check that text search refuses until a rebuild; lists, conversations and date-only search should work. No rebuild is needed if the file is absent | 2026-10-27; 681 tests, 1 expected skip, 0 failures; synthetic baseline: `docs/tools-agent.md`, Search |
| 256 KiB IMAP MIME blocks on Mail.ru (`docs/imap-source-agent.md`) | On the next user-requested Mail.ru sync that downloads new messages, confirm successful downloads and conversion. Gmail already passed the user-run check ([results.md, section 2](results.md#2-mailru-and-gmail)); no reprocessing is needed | 2026-10-28; 709 tests, 1 expected skip, 0 failures |
| Gmail reuse by `stableId` across a UIDVALIDITY reset (`docs/storage-agent.md`) | Cannot be triggered deliberately; if a Gmail `mail-sync` reports a reset of All Mail, check that previously saved, uniquely identified messages retain their keys and paths without redownloading; genuinely new messages may still download. All Mail sync, labels and label search already passed ([results.md, section 2](results.md#2-mailru-and-gmail)) | 2026-10-28; offline tests |
| RFC 1556 directional charsets `iso-8859-6-e/-i`, `iso-8859-8-e/-i` (`docs/mime-agent.md`) | Run `mail-reprocess gmail`: the one Gmail message that failed with `MIME parsing failed (UnsupportedEncodingException)` should become `converted` with a readable Hebrew body and subject. Optionally run `mail-reprocess mailru` and compare its 7 charset failures ([results.md](results.md)) | 2026-10-31; 730 tests, 1 expected skip, 0 failures |
| Lenient `Content-Disposition` and the `cp-1251` label (`docs/mime-agent.md`) | After the current Mail.ru sync ends, rebuild the jar, run `mail-reprocess mailru` and count the `error` values of failed `meta.json` files again. Of the 24 failures counted on 2026-10-31, the 16 with an unparsable `Content-Disposition` and the 3 with `cp-1251` should become `converted`; 1 `ParseException` and 4 UTF-7 `UnsupportedEncodingException` are expected to remain | 2026-10-31; 732 tests, 1 expected skip, 0 failures |
| Sync stages in `mail-sync` and `syncStatus` (`docs/commands.md`) | On the next `mail-sync mailru`, check that the stage lines appear before folder progress, with `saved messages checked` counts only after a run that did not complete. Restart the MCP server, call `sync`, and check that `syncStatus` shows `phase` moving through the stages and the log file has `Sync phase:` lines | 2026-10-31; 734 tests, 1 expected skip, 0 failures |
| IMAP folder gone during a run, Д34 (`docs/storage-agent.md`) | During a long `mail-sync mailru`, delete or rename an included folder in the web interface: the run continues, the folder's line ends with `, gone from the server during the run`, and the result is `RESULT: OK`. The next run removes the folder's local messages; after a rename they reappear under the new name, recognized as moves where unique | 2026-11-01; 738 tests, 1 expected skip, 0 failures |
| Gmail OAuth2 sign-in (`docs/gmail-oauth-agent.md`) | Only if needed instead of the verified app password: run `gmail-oauth gmail`, then `mail-sync gmail`. Token encryption is in [backlog.md](backlog.md), Д27 | 2026-10-25 |
| Passwords from Windows Credential Manager and password managers (`docs/connections.md`) | `windowsCredential`: replace `passwordCommand` with `"windowsCredential": "mail-mcp/<name>"`, run `mail-sync` for `work` and `mailru`, then restart the MCP server and run `sync` and `syncStatus`. `passwordCommand` in `mail-sync` for EWS is already verified ([results.md, section 1](results.md#1-corporate-exchange)); server use and specific password managers: to verify | 2026-10-27; 702 tests, 1 expected skip, 0 failures |

## 3. Design

Principles and data flow. Details are in the engineering documents (section 3.3).

### 3.1. Principles

1. **The disk is the complete copy.** Each message has its own directory:
   - `message.eml` — exactly as delivered by the server;
   - `meta.json` — information absent from `.eml`: folder, remote reference, state;
   - `body.md` — the body;
   - attachments as files, each with its text in `<file>.md` alongside it.
2. **Lucene is a derived index.** One index per connection serves as both catalog and search.
   It can be rebuilt from disk without the network (`mail-reindex`). The schema version is stored
   in commit data; an index with an older schema or a different word forms hash is recreated when
   the writer opens.
3. **Sync cursors are in `folders.json`:** IMAP uses `UIDVALIDITY` and a UID list; EWS uses
   `SyncState` from `SyncFolderItems`.
4. **Everything is stored unchanged, without filtering.** `MAIL_MCP_REDACT_SECRETS=true` enables
   secret replacement only in text fields of tool responses; it is off by default. This protects
   against accidental context exposure, not intentional extraction. Original paths and filenames
   used to access attachments remain available.
5. **Read-only at protocol level:**
   - EWS: an allow-list of 10 read operations;
   - IMAP: `EXAMINE`, `BODY.PEEK`, `CLOSE`; the command guard forbids `SELECT` and all mutations.
6. **One sign-in per run, without retries.** A rejected sign-in stops sync for that connection:
   an outdated password can quickly lock a domain account.
7. **Pandoc is required** for sync, reprocessing and reindexing (which can finish processing
   `downloaded` messages). Read tools work without it.
8. **Simpler is better.** Thresholds: messages up to 64 MiB; attachments for text conversion up
   to 20 MiB, XLSX up to 5 MiB inclusive; tool responses are paged. Disk space is not a constraint.
   What requires a user decision: [decisions.md, section 1](decisions.md#1-what-requires-a-user-decision).

### 3.2. Data flow

```
MailSource (EWS | IMAP): folders() · changes() · metadata() · download()
        │
        ▼
SyncRunner — one run per connection, only in the process holding IndexWriter
  1. recovery: remove directories without meta.json and documents without directories;
     finish missing entries according to their state; after an incomplete run, also repair
     indexed folder/ref/observation mismatches from saved content (storage-agent.md)
  2. folders: match by remoteId; assign f<n> keys to new ones; collect changes and metadata
     from all included folders before deletions; save folders with old/null cursors,
     keeping missing folders until their deletions finish
  3. unambiguous moves: atomically update meta.json → load saved content into the index
     → commit; then process remaining deletions and downloads in batches of 100
       message.eml + meta.json (downloaded) → MIME parsing → attachments, body.md, <file>.md
       → meta.json (converted | failed) → index document; commit after each batch
  4. after all changes and commits, save all new cursors and the final folder list
     in one folders.json write; progress goes to sync-status.json
        │
        ▼
MCP tools: folders.json, sync-status.json, index and message directories; no network access
```

### 3.3. Detailed documentation

| Topic | Document |
|---|---|
| Packages, layers, stack, stdio transport | `docs/architecture-agent.md` |
| Message directories, `meta.json`, `folders.json`, index, write order, sync, moves, flags, reconciliation | `docs/storage-agent.md` |
| MIME parsing, attachments and their names, containers, pandoc, attachment text | `docs/mime-agent.md` |
| Tools, arguments and limits, search, conversations, copies, `sync-status.json`, single writer | `docs/tools-agent.md` |
| Sources: EWS, IMAP, Gmail OAuth2 | `docs/ews-source-agent.md`, `docs/imap-source-agent.md`, `docs/gmail-oauth-agent.md` |
| Secret filter | `docs/redaction-agent.md` |
| Build, settings, connections file and command implementation | `docs/build-config-agent.md` |
| User documentation: connections, clients, commands, search, security | `docs/connections.md`, `docs/clients.md`, `docs/commands.md`, `docs/search.md`, `docs/security.md` |

## 4. Version 0.1.0

The first published version, licensed under Apache 2.0 (decisions of 2026-10-28). The table lists
remaining work for this version; other improvements are in [backlog.md](backlog.md), rejected ideas
in [decisions.md](decisions.md).

Status: ⬜ not started · 🔨 in progress. Size: S — hours, M — a brief for one session, L — several
briefs. When work starts, mark the item 🔨 and write a brief in `docs/impl-tasks/`.
Remove accepted items from the table: completed behavior is described in engineering and user
documents, pending real-mail checks in section 2, history in git. Numbers are never reused;
completed: Д1–Д12, Д17, Д21, Д22, Д24, Д25, Д26, Д28, Д29, Д30, Д31, Д34.

| ID | Improvement | Scope | Source | Size | Status |
|---|---|---|---|---|---|
| Д18 | Publish `0.1.0` | Done: documents separated, user documentation extracted, README rewritten following jdbc-mcp-server. Remaining: `LICENSE` and `THIRD_PARTY_NOTICES.md` (then link `LICENSE` from README), build version, CI and jar releases through GitHub Actions on tags (then add release jar download and CI, Release, License badges to README) | polish | M | 🔨 |
| Д32 | IMAP keywords as labels | Fill the provider-neutral `labels` on non-Gmail IMAP servers from user keywords already returned in `FLAGS`; exclude system flags, every `$`-prefixed bookkeeping keyword and the junk-filter keywords `Junk`, `NonJunk`, `NotJunk` (decision of 2026-11-01). No new IMAP command, contract or schema. Verify whether Mail.ru stores and returns keywords | Д7 review of 2026-10-28; included in `0.1.0` by the decision of 2026-10-31 | S | 🔨 |
| Д33 | RFC 8474 `EMAILID` as `stableId` | On non-Gmail IMAP servers advertising `OBJECTID`, fetch `EMAILID` with the existing UID listing and metadata requests and store it as the provider-neutral `stableId`. Extend the read-only FETCH allow-list by `EMAILID`; no contract or schema change. Verify whether Mail.ru advertises `OBJECTID` | Д7 review of 2026-10-28; included in `0.1.0` by the decision of 2026-10-31 | S | 🔨 |

Before publication, the only required real-mail check is the EML/ZIP/MSG/TNEF and legacy Office
reprocessing check for `work` and `mailru` in section 2.

## 5. Workflow

- Version scope is in section 4; the user moves items there from [backlog.md](backlog.md).
- Write a brief for each improvement in [docs/impl-tasks/](impl-tasks/README.md); that directory
  also describes the three-session cycle (implementation, review, recording and commit), ready
  prompts and result-recording rules. A strong model (Claude) writes briefs for large improvements
  (L) and reviews risky changes. Any capable model (ChatGPT/Codex) can do the rest.
- Only the user runs checks on real mailboxes. Documents contain numbers only, without folder
  names, addresses or message content.
- Where to record each kind of information: `AGENTS.md`, "Working with the plan".

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

**Status (2026-10-28): the draft is complete; the first published version, `0.1.0`, is in
preparation (Д18).** Both user mailboxes work: corporate Exchange (EWS) and Mail.ru (IMAP).
Gmail app-password synchronization has also completed; Gmail label handling and deduplication
remain in Д7. Implemented:

- full and incremental synchronization;
- messages and attachments on disk, with bodies and attachment text in Markdown;
- search with Russian and English morphology, including attachment text;
- an optional user word forms file and the offline `mail-analyze` command;
- date filters in search and message lists, and date-only search;
- local conversations across folders of one connection;
- message copies counted once in search and conversations, with other copies listed in `copies`;
- detection of unambiguous moves between folders without downloading MIME again;
- snapshots of read status and user flags/stars, without writing to the mailbox;
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
in [results.md](results.md) and remove the corresponding row here. Only the first row is required
before publishing `0.1.0` (decision of 2026-10-28); the remaining checks happen during use.

| Feature | How to check | Accepted |
|---|---|---|
| **Before publication.** EML/ZIP/MSG/TNEF expansion and DOC/XLS/PPT text (`docs/mime-agent.md`) | Run `mail-reprocess` for each connection, then search text in files from archives and older Office formats | 2026-10-27; 582 tests, 1 expected skip, 0 failures |
| Search by full address without display names (`docs/search.md`) | Restart the MCP server and search `from`/`to` by a full address | 2026-10-26 |
| Background sync summary and a separate EWS rescan indicator (`docs/tools-agent.md`) | On the next user-requested `sync`, check for one summary line in the log; observe `rescanned` only during natural EWS recovery, without deliberately resetting the cursor | 2026-10-27; 592 tests, 1 expected skip, 0 failures |
| Date filters and date-only search (`docs/search.md`) | After rebuilding the index to schema 4, restart the MCP server; check a known period with `search` without words and `listMessages` with `since`/`until` on saved data; no separate sync is needed to check dates | 2026-10-27; 603 tests, 1 expected skip, 0 failures |
| Local conversations (`docs/search.md`) | After rebuilding the index to schema 4, restart the MCP server; call `getThread` with the key of a known conversation and check its membership, order and pages on saved data; no separate sync is needed to check conversations | 2026-10-27; 612 tests, 1 expected skip, 0 failures |
| Moves without downloading again (`docs/storage-agent.md`) | The user runs sync to populate fingerprints, manually moves a test message between included folders in a mail client and syncs again; check that UUID, path and content are preserved and MIME is not downloaded again. Availability of EWS/IMAP metadata requests on real servers: to verify | 2026-10-27; 635 tests, 1 expected skip, 0 failures |
| 5 MiB XLSX text conversion threshold (`docs/mime-agent.md`) | An optional user-run `mail-reprocess` applies the threshold to saved messages: for XLSX files over 5 MiB, old text disappears from `.md` files and search, while originals remain. Until reprocessing, old text is available; reads and `mail-reindex` retain it for `converted` messages | 2026-10-27; 640 tests, 1 expected skip, 0 failures |
| read/flagged snapshots (`docs/storage-agent.md`, `docs/tools-agent.md`) | The user changes read status and flag/star in their client, runs `sync` or `mail-sync` for EWS and IMAP, and compares `getMessage`, `listMessages`, text search, date search and `getThread`; repeat after clearing flags. Check that known messages are not downloaded again and content is preserved. Real flag requests: to verify | 2026-10-27; 655 tests, 1 expected skip, 0 failures |
| Message copy grouping (`docs/search.md`) | Restart the MCP server and find a saved message present in several folders, such as a message sent to yourself: `search` and `getThread` show one card, with other keys and folders in `copies`; totals and paging count the group once. No index rebuild or sync is needed for current schema 4 | 2026-10-27; 665 tests, 1 expected skip, 0 failures |
| User word forms (`docs/search.md`) | Optionally create `word-forms.txt`, check rules with `mail-analyze`, run `mail-reindex` for each connection and search for irregular forms on saved data. After editing, check that text search refuses until a rebuild; lists, conversations and date-only search should work. No rebuild is needed if the file is absent | 2026-10-27; 681 tests, 1 expected skip, 0 failures; control set with rules: 7/7 irregular forms, 86/86 overall, 7 false positives |
| 256 KiB IMAP MIME blocks on Mail.ru (`docs/imap-source-agent.md`) | On the next user-requested Mail.ru sync that downloads new messages, confirm successful downloads and conversion. Gmail already passed the user-run check ([results.md, section 2](results.md#2-mailru-and-gmail)); no reprocessing is needed | 2026-10-28; 709 tests, 1 expected skip, 0 failures |
| Passwords from Windows Credential Manager and password managers (`docs/connections.md`) | `windowsCredential`: replace `passwordCommand` with `"windowsCredential": "mail-mcp/<name>"`, run `mail-sync` for `work` and `mailru`, then restart the MCP server and run `sync` and `syncStatus`. `passwordCommand` in `mail-sync` for EWS is already verified ([results.md, section 1](results.md#1-corporate-exchange)); server use, specific password managers and `cmdkey /pass` without a value: to verify | 2026-10-27; 702 tests, 1 expected skip, 0 failures |

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
7. **Pandoc is required** for sync and reprocessing. Read tools work without it.
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
     finish directories without documents according to their state; if folder/ref/flags
     differ from meta.json, rebuild only affected documents from saved content
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

The first published version, licensed under Apache 2.0 (decisions of 2026-10-28). It includes
only the rows in this table; other improvements are in [backlog.md](backlog.md), rejected ideas
in [decisions.md](decisions.md).

Status: ⬜ not started · 🔨 in progress. Size: S — hours, M — a brief for one session, L — several
briefs. When work starts, mark the item 🔨 and write a brief in `docs/impl-tasks/`.
Remove accepted items from the table: completed behavior is described in engineering and user
documents, pending real-mail checks in section 2, history in git. Numbers are never reused;
completed: Д1–Д6, Д8, Д9, Д10, Д11, Д12, Д17, Д21, Д22, Д24, Д25, Д26, Д28, Д29, Д30.

Д7: Gmail app-password synchronization and 256 KiB IMAP blocks are verified on 2026-10-28
([results.md, section 2](results.md#2-mailru-and-gmail)). The row below describes the remaining work.

| ID | Improvement | Scope | Source | Size | Status |
|---|---|---|---|---|---|
| Д7 | Gmail | All Mail once with labels, deduplication by `X-GM-MSGID`. Verify OAuth2 sign-in only if needed instead of the checked app-password path; token encryption is in [backlog.md](backlog.md), Д27 | П3; included in 0.1.0 by the decision of 2026-10-28 | M | 🔨 |
| Д18 | Publish `0.1.0` | Done: documents separated, user documentation extracted, README rewritten following jdbc-mcp-server. Remaining: `LICENSE` and `THIRD_PARTY_NOTICES.md` (then link `LICENSE` from README), build version, CI and jar releases through GitHub Actions on tags (then add release jar download and CI, Release, License badges to README) | polish | M | 🔨 |

Before publication, in addition to these items, only the first check in section 2 is required
(`mail-reprocess` on both mailboxes).

## 5. Workflow

- Version scope is in section 4; the user moves items there from [backlog.md](backlog.md).
- Write a brief for each improvement in [docs/impl-tasks/](impl-tasks/README.md); that directory
  also describes the three-session cycle (implementation, review, recording and commit), ready
  prompts and result-recording rules. A strong model (Claude) writes briefs for large improvements
  (L) and reviews risky changes. Any capable model (ChatGPT/Codex) can do the rest.
- Only the user runs checks on real mailboxes. Documents contain numbers only, without folder
  names, addresses or message content.
- Where to record each kind of information: `AGENTS.md`, "Working with the plan".

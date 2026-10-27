# RT-01 — Existing MCP Servers for Email

- Date: 2026-10-24.
- Author: Codex, RT-01 research session.
- Brief: `docs/research-tasks/RT-01-mail-mcp-servers.md`.
- Method: primary documentation, READMEs, GitHub REST API, and selective source-code review; no server installation or execution, mailbox sign-in, or real credential checks.

## Summary

- [docs][code] 17 solutions were found: official Microsoft/Google servers, popular Graph/Gmail wrappers, EWS/NTLM, and local indexers. The full list is in Т1; this is an overview of ideas, not a proposal to replace our server. [3–22]
- [docs] Microsoft retained its former `mcp_MailTools` for compatibility; Work IQ MCP is the current direction. Graph servers cannot be treated as a ready way to access our EWS/NTLM. [3–6]
- [code] The closest EWS example with a local index is azizmazrou/ews-mcp: SQLite FTS5, `SyncFolderItems`, saved synchronization states; optionally pgvector + Ollama. Text from file attachments is not included in this index. [24–26]
- [code] For IMAP, codefuturist/email-mcp is useful: an index keyed by connection, folder, `UIDVALIDITY`, and UID; deletion reconciliation. Calling the strategy QRESYNC does not mean `VANISHED` handling is implemented. [35–36]
- [code] Attachment-text search is available in JonLaliberte/mail-semantic-search and marshalltech81/protonmail-local-ai. The former uses ChromaDB and local models; the latter uses FTS5 + sqlite-vec, but its embedding provider may be external. [20–21,31,40–41]
- [code] The best reviewed example of bounded attachment processing is ni-c/imap-mcp: separate response, disk, and parser limits, isolated extraction, and text continuation by offset. [17,27,29]
- [code] A “read-only” label needs checking: ni-c's `list_new_messages` tool sets a service flag by default; codefuturist's corresponding flag limits only mail changes. [15,27–28,42]
- [code][assumption] Useful ideas to borrow are compact search → detailed reading, explicit connections, index state, and resource limits. Protection from instructions embedded in mail and secret masking are different tasks; this sample did not confirm a universal content filter. [17,23,30,35]

## Answers to the questions

### В1. Which email MCP servers exist?

[docs][code] Table Т1 contains 17 distinct projects/services, including three official
services. The two Microsoft generations are listed separately: they are different interfaces, not two
names for one implementation. Row order is not a ranking. [3–22]

[code] The search began with the official registry and `modelcontextprotocol/servers`, then continued by searching
repositories by protocol and index. The registry alone does not prove a project is safe or
official; ownership was checked against the repository or vendor documentation. [1–2]

[docs] An important change from older reviews: Microsoft's article dated 2026-10-13
recommends Work IQ MCP in place of the former separate Mail/Calendar servers and others.
Even the Work IQ overview and general article differ on the number of tools: respectively,
12 listed names and the phrase “10 generic tools.” The actual set was not checked by connecting. [4–6]

[docs] Gmail MCP is an official remote service in Developer Preview. The general instructions
list nine tools, while the reference lists ten, including `get_message`; this is a difference
between documents, not evidence of different server versions. [7–8]

### В2. Where is there a local index or RAG, and how is it implemented?

| Т1 row | Engine and indexed data | Change retrieval and limitations | Evidence |
|---|---|---|---|
| P06 azizmazrou | SQLite FTS5: normalized message text and metadata; optional pgvector + Ollama, RRF rank fusion | `folder.sync_items`, per-folder states on disk; creates, updates, deletes; inbox/sent by default, 365-day window, 45 s interval; attachment text is not indexed | [code] `cache/store.py`, `cache/sync.py`, `tools/mail_read.py`, `config.py` [23–26] |
| P10 codefuturist | SQLite FTS5: subject, sender, body, **attachment names**; separate hash-keyed file store | `UIDVALIDITY`, UID, modseq with CONDSTORE, compare UID sets for deletions; background and lazy updates; full QRESYNC/VANISHED path explicitly marked unimplemented | [code] `src/cache/schema.ts`, `sync-engine.ts`; README [15,35–36] |
| P13 Tillman32 | SQLite FTS5 is claimed for the mail cache | Synchronization, deletion, and UID generation-reset behavior not checked in code; attachment-text indexing unconfirmed | [code] README; review was less in-depth than P10/P14 [18] |
| P14 DreamC0der-AI | SQLite FTS5: subject/from/to/body; no vectors or attachment-text extraction | Headers first; body fetched on read; `SEARCH ALL`, compare UIDs, delete vanished items, reset when `UIDVALIDITY` changes; body indexed only after fetching | [code] `db.py`, `server.py` [33–34] |
| P15 JonLaliberte | SQLite for metadata + ChromaDB for vectors; body, names, and extracted attachment text combined into embedding text | Source is local `.eml` files, not IMAP; traverse files, separate `index-file`, skip processed files, `prune`/`reextract` maintenance; correlate moves by Message-ID | [code] README, `index.py` [20,40] |
| P16 marshalltech81 | SQLite FTS5 + sqlite-vec: threads, message chunks, extracted attachments; RRF, optional reranking | Bridge → mbsync → Maildir → file watcher → indexer; collection/indexing states and reparsing; deleted-mail retention mode needs separate investigation | [code] README, `docs/architecture.md` [21,41] |

[assumption] P14 illustrates a risk of lazy loading: a message being in the database does not mean
its body is available for full-text search. Our search response should show body
and attachment coverage separately; this follows from P14's loading design. [33–34]

[code][assumption] P15 does extract attachments, but combines them with the body before
embedding and limits the length. This is not equivalent to independent search across every page
of every attachment: the end of a large document may not be represented in the message. [20,31,40]

[docs][code] P01–P05, P07–P09, P11–P12, and P17 provide vendor search or IMAP;
the reviewed materials for these rows did not confirm a local search index of their own.
Microsoft server-side attachment search does not mean local extraction. [3–22]

### В3. How are attachments handled?

| Т1 row | Returned result / extraction | Verified limits and gaps |
|---|---|---|
| P02 Work IQ | `fetch_blob`: base64 and metadata in `structuredContent`, without duplication in the text block | [docs] Default limit 4 MB, configurable by path; not a promise to support every email attachment [6] |
| P04 Softeria | Base64; optional EmbeddedResource, save to disk; separate plain-text email reading | [code] Blob mode limit is 5 MiB; PDF/Office extraction into a mail index unconfirmed [9] |
| P06 azizmazrou | `info`, UTF-8 `text`, `save`; binary files return metadata by default | [code] Text is 20,000 characters; `.content` is held entirely in memory before the limit; item attachments are unsupported by this handler; saving under a sanitized name may overwrite a same-named file [23] |
| P07 ShermanGu | Attachments when preparing and editing a draft | [code] Incoming-file download, text extraction, and limits not established [12] |
| P08 GongRzhe | Metadata on read, separate download to disk, files when sending | [code] The README's stated Gmail limit of 25 MB must not be treated as the server's own download/memory limit [13] |
| P09 Workspace MCP | Local files over stdio, download links over HTTP; optional GCS | [code] File reads and allowed directories are restricted; this is not an attachment-text index [14] |
| P10 codefuturist | Base64 up to 5 MB; optional save to a chosen directory, 25 MiB by default; file cache | [code] Separate response and disk limits; FTS stores names, not extracted file text [15,35] |
| P12 ni-c | Part list; content/image; `mode=text` for PDF/Office; `mode=save`; MCP resources | [code] Defaults: 1 MiB inline, 25 MiB to disk, 10 MiB for extraction; type and signature checks; child process with memory/time limits; no OCR [17,27] |
| P13 Tillman32 | Accepts local paths and HTTP(S) links when sending and creating drafts | [code] At most 10 files, 10 MB each, 25 MB total; HTTP timeout 15 s; retrieval of incoming attachment bytes unconfirmed [18] |
| P14 DreamC0der-AI | `download_attachment` saves the file and a download record | [code] MIME parsing exists; no PDF/Office extraction or hard download-size limit found in the reviewed handler [34] |
| P15 JonLaliberte | PDF, DOCX, XLSX, PPTX, text/CSV; copies attachments and `.eml` to `STAGING_DIR` | [code] Extraction input limited to 10 MiB; per-file text and total embedding-text limits; OCR unconfirmed [20,31,40] |
| P16 marshalltech81 | Attachment extraction with separate text and parse-state tables | [code] Indexing confirmed by architecture; numerical parser and download limits not checked [41] |
| P17 zayansalman | Saves all thread attachments in a directory; result includes path, size, MIME type, and original name | [code] Extension filter exists; text extraction and file-size limit unconfirmed [22] |

[assumption] For our stdio server, the most useful combination is P12 and P15:
keep the original on disk, store extracted text separately with continuation, and use separate limits for file size,
unpacking, time, and response. Base64 should be an explicit option only: it consumes context
even when the original file is within the download limit. [6,17,31]

### В4. What security measures exist?

| Measure | Found in | What it establishes and what it does not guarantee |
|---|---|---|
| Default prohibition on changes | P02 Work IQ | [docs] Tenant policy blocks change requests; an administrator can allow paths/methods. This is not a local flag in our server [6] |
| Capability levels and tool exclusion | P06 azizmazrou | [code] `read`/`draft`/`full`, checked both at registration and invocation; default is **draft**, not strict read-only [11,26,39] |
| Separate permission to send | P06 | [code] `SEND_ENABLED=false`; recipient checks, send limit, and confirmation tied to draft content [11,39] |
| Draft first | P03 Google, P07 ShermanGu | [docs][code] Google's published set has no send tool; ShermanGu prepares drafts. This does not prove there are no other side effects [8,12] |
| Read-only flag | P04 Softeria, P09 Workspace, P10 codefuturist | [code] Explicitly configurable; P10 defaults to false and limits only mail changes, leaving other writes possible [9,14–15] |
| File type and boundary checks | P12 ni-c, P09 Workspace | [code] P12 checks declared type, signature, and path, and creates files without overwriting; P09 restricts read directories and blocks sensitive paths [14,17,27] |
| Untrusted email content | P12 ni-c | [code] `untrusted` labels, per-call random delimiters, line-by-line marking, and suspicious-instruction signals; this limits risk but does not prove attacks are impossible [17,29–30] |
| Check prompts/responses for instruction injection | P03 Google | [docs] Instructions require checking content with Model Armor or a documented alternative; connecting MCP does not automatically enable this protection [7] |
| Secret masking in logs | P04 Softeria | [code] JWTs, Bearer values, token fields, and addresses are sanitized from logs by default; this is **not** sanitizing email bodies before they reach the model [9] |
| Separate user access | P11 RushB | [code] Mailbox/group permissions and AES-256-GCM encryption of stored mail credentials; this does not confirm email-content filtering [16] |

[code] A significant P12 exception: `list_new_messages` is registered when
`IMAP_SEEN_KEYWORD` (default `AiSeen`), selects a folder for writing and calls `tagSeen`,
unless `dry_run` is set. The general `IMAP_READ_ONLY` does not block this path; the tool annotation
correctly sets `readOnlyHint: false`. Strict reading requires an empty keyword or excluding the
tool. Do not carry this behavior into our MVP. [27–28,42]

[code][assumption] P12 has no SMTP, but the lack of sending in one server does not protect
an agent that has another network tool. Similarly, untrusted-text labels
do not hide a password that an email contains as ordinary data. Separate
action boundaries and a secret filter are needed for every output path, including resources and files. [17,30]

[code] The reviewed P01–P17 materials did not confirm a ready-made unified filter for passwords, tokens,
and Russian personal data in message bodies, search snippets, and extracted attachments.
This is a limitation of the review, not a claim that every version of every project lacks all protection. [3–42]

### В5. How are tools, identifiers, pages, and response limits implemented?

| Т1 row | Tool set and identification | Pages and limits |
|---|---|---|
| P01 Mail | [docs] 10 published tools; Graph message ID and separate draft ID; KQL search | `from`/`size` for search, `top` for sent; no local page state described [3] |
| P02 Work IQ | [docs] General verbs + `/me/messages` paths; schema requested separately | `fetch`: `$top` defaults to 25, max 100; `$skip`/`$skiptoken` prohibited. Traversing all pages through this interface unconfirmed [5–6] |
| P03 Google | [docs] Thread-first: `search_threads` → `get_thread`; separate IDs for messages, threads, and labels | Exact page schema and numeric limits not checked here; see count discrepancy in В1 [7–8] |
| P06 azizmazrou | [code] README claims 28 tools, plus optional `find_similar`; short aliases `m…`, `t…`, `f…` resolved by server | Search 20, max 50; `offset`/`next_offset`; body defaults to 4,000 characters; `source`, `as_of`, `fresh` [11,23,26,39] |
| P09 Workspace | [code] README: over 120 total, 15 for Gmail; core/extended/complete tiers, service selection | A compact set reduces the number of schemas in context; exact mail response limits not checked [14] |
| P10 codefuturist | [code] README: 52 tools; internal key account/mailbox/UIDVALIDITY/UID | Local database and cache limits exist; a full count of registered tools for this version was not made [15,35] |
| P12 ni-c | [code] Up to 11; UID + folder within one account; threads through Message-ID/References | `offset`/`next_offset`; character offset for extracted text; code sets total result limit at 200,000 [17,27,29] |
| P14 DreamC0der-AI | [code] **14** tool decorators although README says 13; UID + folder, one account | `page`/`page_size`, body up to 50,000 characters; FTS and IMAP search have different capabilities [19,33–34] |
| P15 JonLaliberte | [code] 6 general tools + 4 macOS-only; Message-ID/path/short hash when staging files | `query_emails` defaults to limit 1,000 and reports `truncated`; continue by narrowing date, not with a stable cursor [20,32] |
| P17 zayansalman | [code] 3 tools; Gmail thread ID, download entire thread | Search defaults to 10; combined tool returns the latest matching thread [22] |

[assumption] The P06/P12 approach suits us: first return cards without bodies or binary data,
then read details by ID. The identifier should include the connection; an IMAP UID
without folder and `UIDVALIDITY` is insufficient for a long-lived reference — P10's schema directly accounts for this.
P06 aliases are convenient for a model, but do not replace internal message identity. [23,35]

[assumption] Do not copy a single tool for arbitrary Graph/IMAP requests
just to reduce the number of tool names: P02 security depends on a separate server-side path policy.
A small set of mail operations is easier to verify for a bounded EWS/IMAP MVP. [5–6]

### В6. Multiple accounts and credential storage

| Т1 rows | Account model | Storage / trust boundary |
|---|---|---|
| P01–P03 | [docs] User/agent identity through OAuth; no custom list of EWS+IMAP connections described | Credentials handled by vendor/client; local cache details depend on MCP client [3–8] |
| P04 Softeria | [code] Several Microsoft accounts per process; `account` is added automatically when multiple; shared selected account and strict binding also exist | MSAL cache encrypted with AES-256-GCM, key in system storage; fallback is `.cache-key` file, so actual protection depends on mode [9] |
| P05 ryaker | [code] One documented user token; account list unconfirmed | App settings in env, tokens in `~/.outlook-mcp-tokens.json` [10] |
| P06 azizmazrou | [code] One set of EWS credentials in Settings; separate process and directory needed to isolate different logins | env/`.env`; data in `DATA_DIR`; do not carry author-specific assumptions about automatic auth selection into our project [11,26] |
| P07 ShermanGu | [code] Current-user configuration; simultaneous list of independent connections unconfirmed | Windows Credential Manager; credentials hidden from tool facade [12] |
| P08 GongRzhe | [code] One documented set of OAuth files per process | `~/.gmail-mcp/credentials.json` and OAuth client file; “local” storage alone does not prove encryption [13] |
| P09 Workspace | [code] Multiple users, OAuth 2.1 over HTTP; user identity selected in requests | Local credential directory; optional external stores and identity gateway; more complex than a local mailbox list [14] |
| P10 codefuturist | [code] Multiple named accounts in TOML, shared tool set | Literal values, `env:…`, `keychain:…`; described keychain mechanisms are macOS/Linux, not a claim of Windows support [15] |
| P11 RushB | [code] Many users and mailboxes, personal/shared organizational mailboxes | Application database; mail credential encryption, OAuth/static Bearer for MCP access [16] |
| P12 ni-c | [code] One IMAP account per process | env; password removed from environment after reading, which does not remove its copy in launch configuration [17] |
| P13 Tillman32 | [code] Multiple `ACCOUNT_N_*` accounts, selected by `account_name`; search may cover all | env/`.env`; encrypted system storage unconfirmed [18] |
| P14 DreamC0der-AI | [code] One Bridge account, multiple folders | env/`.env`, shared local database and attachment directory [19,34] |
| P15 JonLaliberte | [code] One local archive may contain multiple accounts; this is not managing sign-in to multiple servers | No mail passwords needed: reads `.eml`; incoming list has an `account` filter [20,32] |
| P16 marshalltech81 | [code] Documented path through local Bridge/Maildir; multiple independent logins not checked | `.secrets/` files with owner permissions; external model endpoints configured separately [21] |
| P17 zayansalman | [code] One documented OAuth set; separate processes/paths for isolation | Refresh-token file with mode 0600; paths reconfigured through env [22] |

[assumption] For our project, named accounts in P10/P13 and P06's directory isolation
are more useful than P04/P11's implicit default account: an explicit `connection` makes mailbox choice verifiable.
This is an interface recommendation, not a change to the accepted plan. [9,11,15–16,18]

### В7. What should be borrowed, and what approaches should be avoided?

[assumption] Specific proposals and their tradeoffs are collected in the “Recommendation” section:
eight useful techniques and eight undesirable patterns, with references to Т1 rows and primary sources.

## Comparison tables

### Т1. Main table

Notation: `?` means “not established in the reviewed materials,” not “no”; `n/a` means not applicable.
Each row inherits the label and sources in its first column. `[code]` is used for README claims:
these are repository information and do not mean a mailbox check was performed.
Commit dates and stars were obtained from the GitHub API on 2026-10-24; the main branch tip is shown,
not `pushed_at` or the latest release date. For closed services, the document update date
does **not** replace an unknown release date. Metadata and short SHAs follow the table. [37]

| Project | Link | Language | Protocols (IMAP/SMTP/EWS/Graph/Gmail API/…) | Sign-in (password/OAuth/…) | Multiple mailboxes | Attachments | Search (server/local index/vectors) | Read-only mode / sending | Data protection | License | Latest release or commit | Stars (≈) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P01 Microsoft Mail MCP [docs] [3–4] | [Microsoft](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-mail-tools) | ? closed | Graph | OAuth/Entra | User identity; connection list ? | Attachment search; file return ? | Server-side Graph Search/KQL | Create/send/delete; separate read-only mode ? | M365 permissions; content filter ? | Microsoft terms, not OSS | ?; reference 2026-09-30, legacy | n/a |
| P02 Microsoft Work IQ MCP [docs] [4–6] | [Microsoft](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/overview) | ? closed | Graph/Work IQ paths | OAuth/Entra | By identity; mixed mailbox configuration ? | Generic `fetch_blob`; mail types ? | Cloud search/context retrieval | Changes prohibited by default policy | Path/method policies; response PII filter ? | Microsoft terms | ?; overview 2026-10-13 | n/a |
| P03 Google Gmail MCP [docs] [7–8] | [Google](https://developers.google.com/workspace/gmail/api/reference/mcp) | ? closed | Gmail MCP/API | OAuth 2.0 | By OAuth user; account list ? | No separate download tool listed | Server-side thread search | Read, drafts, labels; send not listed | Google permissions; instruction-injection checks delegated to integration | Google terms | ?; Developer Preview | n/a |
| P04 Softeria/ms-365-mcp-server [code] [9] | [GitHub](https://github.com/Softeria/ms-365-mcp-server) | TypeScript | Graph | OAuth/MSAL, ready token | Yes; `account`, shared mailboxes | Base64/blob/file, MIME | Server-side | `--read-only`; sending available | Encrypted token cache, log sanitization | MIT | 2026-10-24 | 1075 |
| P05 ryaker/outlook-mcp [code] [10] | [GitHub](https://github.com/ryaker/outlook-mcp) | JavaScript | Graph | OAuth 2.0 | One documented; multiple ? | Mail files ? | Server-side | Read/send/mark/move; read-only ? | OAuth; PII/instructions ? | ? API did not determine | 2026-03-30 | 447 |
| P06 azizmazrou/ews-mcp, 4.5 line in `v5/` [code] [11,23–26] | [GitHub](https://github.com/azizmazrou/ews-mcp) | Python | EWS/exchangelib | Password, auto-negotiation; forced auth optional | One credential set/process | Metadata/UTF-8/file | FTS5; optional pgvector | Draft by default; send disabled | Tiers, recipients, confirmation, audit | MIT | 2026-07-12 | 36 |
| P07 ShermanGu/exchange-ews-mcp [code] [12] | [GitHub](https://github.com/ShermanGu/exchange-ews-mcp) | Python | EWS/NTLM | Password | Current user; list ? | When creating/editing drafts | EWS; local index ? | Draft first; strict read-only ? | Windows Credential Manager; recipient clarification | MIT | 2026-08-21 | 8 |
| P08 GongRzhe/Gmail-MCP-Server [code] [13,37] | [GitHub](https://github.com/GongRzhe/Gmail-MCP-Server) | JavaScript; TS files present | Gmail API | OAuth 2.0 | One file set; multiple ? | Download/send | Server-side Gmail | Direct send, delete, batch changes; read-only ? | Local OAuth files; response filter ? | MIT | 2025-08-06; **archived** | 1169 |
| P09 taylorwilsdon/google_workspace_mcp [code] [14] | [GitHub](https://github.com/taylorwilsdon/google_workspace_mcp) | Python | Gmail API, other Workspace APIs | OAuth; service accounts optional | Yes, multiple users | Files/links; optional GCS | Server-side Gmail | `--read-only`, tool tiers; send available | Path, permission, and identity restrictions | MIT | 2026-10-24 | 3407 |
| P10 codefuturist/email-mcp [code] [15,35–36] | [GitHub](https://github.com/codefuturist/email-mcp) | TypeScript | IMAP/SMTP | Password; experimental OAuth2 | Yes, TOML | Base64/disk/cache | Local FTS5, IMAP | `MCP_EMAIL_READ_ONLY=false` by default; send available | Directories, audit, secret references | LGPL-3.0 per API | 2026-10-16 | 136 |
| P11 RushB-fr/mcp_email [code] [16] | [GitHub](https://github.com/RushB-fr/mcp_email) | TypeScript | IMAP/SMTP | Mail password; MCP OAuth/Bearer | Yes, personal and shared | Separate tool ? | IMAP; local index ? | Send and mark available; read-only ? | AES-256-GCM credentials, organization permissions | PolyForm Shield 1.0.0; source-available | 2026-08-27 | 2 |
| P12 ni-c/imap-mcp [code] [17,27–30,42] | [GitHub](https://github.com/ni-c/imap-mcp) | TypeScript | IMAP | App password/password; no OAuth | One account/process | File/resource/extracted text | Server-side IMAP | Write tools off; **service-flag exception**, В4; no send | Untrusted text, parser isolation, limits | MIT | 2026-10-22 | 3 |
| P13 Tillman32/mcp-email [code] [18] | [GitHub](https://github.com/Tillman32/mcp-email) | Go | IMAP/SMTP | App password/password | Yes, env `ACCOUNT_N_*` | Send files/URLs; incoming bytes ? | FTS5 claimed; sync details ? | Send and draft operations; read-only ? | Outgoing file limits; response filter ? | MIT in README; API did not determine | 2026-09-14 | 1 |
| P14 DreamC0der-AI/proton-mail-mcp [code] [19,33–34] | [GitHub](https://github.com/DreamC0der-AI/proton-mail-mcp) | Python | IMAP/SMTP through Bridge | Bridge credentials | One account | Download to disk | Local FTS5 + IMAP | Send/reply/move/mark/delete; no general read-only flag found | Name sanitization; secret filter ? | MIT in README; API did not determine | 2026-03-09 | 4 |
| P15 JonLaliberte/mail-semantic-search [code] [20,31–32,40] | [GitHub](https://github.com/JonLaliberte/mail-semantic-search) | Python | Local `.eml`/MailMate | No mail sign-in needed | Multiple accounts in archive | Text extraction/file copies | SQLite + ChromaDB/local vectors | Search; on macOS, can change message state | Extraction limits; secret filter ? | MIT | 2026-10-04 | 2 |
| P16 marshalltech81/protonmail-local-ai [code] [21,41] | [GitHub](https://github.com/marshalltech81/protonmail-local-ai) | Python | Bridge IMAP → mbsync/Maildir | Bridge credentials; MCP Bearer | Multiple logins ? | Attachment text indexed | FTS5 + sqlite-vec/RRF | MCP read-only | File permissions, TLS certificate pinning; external model selection | MIT | 2026-10-24 | 1 |
| P17 zayansalman/gmail-attachments-mcp [code] [22] | [GitHub](https://github.com/zayansalman/gmail-attachments-mcp) | Python | Gmail API | OAuth 2.0 | One OAuth set/process | File downloads only | Server-side Gmail | `gmail.readonly`; no send | Token 0600; PII/instructions ? | MIT | 2026-06-09 | 3 |

[code] Main-branch snapshot for reproducible review: P04 `991aa5c`; P05 `95d6ff2`;
P06 `d34f69f`; P07 `859b275`; P08 `a890d19`; P09 `7573911`; P10 `7d31cd5`;
P11 `415e062`; P12 `255a23c`; P13 `de79bb1`; P14 `f8f540d`; P15 `9d642f7`;
P16 `1cf5859`; P17 `2f54ec5`. In P06, the directory name `v5` does not mean a 5.x release:
the root README calls this line 4.5, while `src/` is the older 4.0 line. [11,37]

### Т2. Tools in the four projects most useful for comparison

[code] Names were checked against definitions, not only the catalog. For P06, all mail
and operational tools are listed; calendar, people, and tasks are excluded per the brief.
P12 lists the maximum set; the available set depends on configuration.
For P14 and P15, the table is complete, including file and message-state changes. [23,27–28,32,34,38–39]

| Project | Tool → purpose |
|---|---|
| P06 [23] | `list_folders` → folder tree |
| P06 [23] | `search_messages` → cards by filters/query; optional hybrid search |
| P06 [23] | `get_message` → message in brief or full format |
| P06 [23] | `get_thread` → thread for a message with history continuation |
| P06 [23] | `get_attachment` → metadata, UTF-8 text, or save attachment |
| P06 [23] | `get_mailbox_overview` → mailbox summary; also includes upcoming events |
| P06 [23] | `find_similar` → similar messages; only when vectors are enabled |
| P06 [38] | `create_draft` → create draft/reply/forward |
| P06 [38] | `update_draft` → update draft |
| P06 [38] | `delete_draft` → move draft to trash |
| P06 [38] | `update_messages` → change properties of selected messages |
| P06 [38] | `move_messages` → move selected messages |
| P06 [38] | `send_draft` → send confirmed draft |
| P06 [38] | `delete_messages` → delete selected messages |
| P06 [38] | `set_oof` → change automatic reply; outside our MVP |
| P06 [11] | `get_server_status` → connection, mode, and local mirror state |
| P12 [27] | `get_server_info` → capabilities, limits, and flags |
| P12 [27] | `list_mailboxes` → folders and counts |
| P12 [27] | `list_messages` → list/search with filters |
| P12 [27] | `list_new_messages` → messages not yet returned, then service flag; see В4 |
| P12 [27] | `get_message` → body, headers, and suspicious-content assessment |
| P12 [27] | `get_attachments` → list, read, extract, or save a part |
| P12 [17,28] | `set_message_flags` → reversible flags, without setting `\Deleted` |
| P12 [17,28] | `move_messages` → move or copy |
| P12 [17,28] | `delete_messages` → delete with confirmation |
| P12 [17,28] | `manage_mailbox` → create/rename/delete folder |
| P12 [17,28] | `save_draft` → save draft without sending |
| P14 [34] | `list_mailboxes` → folders |
| P14 [34] | `list_emails` → list from database, otherwise IMAP |
| P14 [34] | `read_email` → full message with lazy body caching |
| P14 [34] | `search_emails` → FTS5 in synced folder, otherwise IMAP |
| P14 [34] | `send_email` → send via SMTP |
| P14 [34] | `reply_email` → reply with thread headers |
| P14 [34] | `move_email` → move between folders |
| P14 [34] | `mark_email` → read state/flag |
| P14 [34] | `delete_email` → move to trash |
| P14 [34] | `download_attachment` → save attachment |
| P14 [34] | `list_downloads` → list previously downloaded files |
| P14 [34] | `sync_emails` → sync headers |
| P14 [34] | `sync_status` → folder synchronization state |
| P14 [34] | `local_search` → direct FTS5 query |
| P15 [32] | `search_emails` → vector search with metadata |
| P15 [32] | `query_emails` → exact filters without vector ranking |
| P15 [32] | `get_status` → index availability and configuration |
| P15 [32] | `list_inbox_emails` → inbox, optionally by account |
| P15 [32] | `stage_email_attachments` → copy attachments and `.eml` to client-accessible directory |
| P15 [32] | `clear_staged_emails` → delete created temporary copies |
| P15 [32] | `open_email` → open message in MailMate; macOS only |
| P15 [32] | `mark_email_read` → mark as read; macOS only |
| P15 [32] | `archive_email` → archive; macOS only |
| P15 [32] | `mark_read_and_archive` → mark read and archive; macOS only |

## Recommendation

[assumption] Use P06 as an example of an EWS mirror, P10 for correct IMAP identification,
P12 for output and parsing limits, and P15/P16 for attachment search. This recommends studying
specific techniques, not copying code or choosing dependencies; the decision remains with the user.

| No. | What to borrow | Project and evidence | Tradeoff for our server |
|---|---|---|---|
| 1 | On-disk sync state, response source, and freshness time | P06 [23–25] | Show staleness and the option to request fresh data separately |
| 2 | Composite IMAP key and deletion reconciliation | P10 [35–36] | More state, but UIDs will not be confused after a folder is recreated |
| 3 | Search cards separate from full bodies and files | P06/P12 [23,27] | An extra read call reduces the size of ordinary responses |
| 4 | Separate response, disk, and extraction limits; text continuation | P12 [17,27,29] | Requires explicit reporting of truncation and parse errors |
| 5 | Signature checks, destination directory, create without overwrite, parser isolation | P12 [17,27] | More refusals for rare formats; needs a clear fallback path |
| 6 | Separate metadata search alongside semantic search | P15 [32] | Exact message lists must not depend on vector similarity |
| 7 | Untrusted-text labels and separate write controls | P12/P06 [30,39] | Do not replace secret filtering or limits on other agent tools |
| 8 | Extraction states and ability to reparse attachments | P15/P16 [20,40–41] | The index must know extraction version and incomplete coverage |

| No. | What to avoid | Concrete example | Why and what to do instead |
|---|---|---|---|
| 1 | Calling a mode strict read-only when it performs a service write | P12 `list_new_messages` [27,42] | Track already-returned messages locally, not with an IMAP flag |
| 2 | Treating a flag as a ban on all environment changes | P10 mailbox-only guard; P15 macOS operations [15,32] | Check every operation, including files, clipboard, and resources |
| 3 | Treating a two-step token as proof of human consent | P06 preview/token protocol [39] | A model can make both calls; human approval requires a separate trusted boundary |
| 4 | Returning binary data in an ordinary search response | P04/P10 base64, P02 blob [6,9,15] | Prefer a path/resource and separate explicit download |
| 5 | Treating synced headers as a complete index | P14 lazy bodies [33–34] | Mark coverage; do not promise search over text not yet fetched |
| 6 | Embedding an entire document in one limited message vector | P15 combined text [20,40] | Long attachments need independent chunks and links to the original |
| 7 | Saving a file only under its original name | P06 `attachments/<safe-name>` [23] | Add message/attachment key, validate the path, and prohibit overwrites |
| 8 | Assuming “local” means no external transfer | P16 configurable embedder [21,41] | Account separately for embeddings, reranking, internal model, and MCP client model |

## What could not be verified

- [assumption] Real mail operations, speed, and compatibility with our Exchange 2013, Gmail, and Mail.ru were not checked: the task prohibited installation and sign-in. The user can later check selected scenarios using test data and minimal permissions.
- [code] Every `?` in Т1 refers to a specific unchecked field: closed-source language/release, multi-user model, read-only mode, attachment return, or filter. P05's license was not established; P13/P14 claim MIT in the README, but the GitHub API did not recognize it. This is not a legal review of copying terms. [10,18–19,37]
- [code] README/API is a snapshot of the main branch, not an audit of the released package. Actual tools for P04/P09/P10 were not recounted through `tools/list`; P02/P03/P14 discrepancies are listed in В1/В5. [4–9,14–15,19,34]
- [code] Review depth varied: tool definitions were read for P06/P12/P14/P15; schema and synchronization were also reviewed for P10. P13 is included as a direction claimed by its README, without asserting that its index is correct. [18,23–36]
- [code] After metadata was retrieved, further GitHub API requests began returning anonymous-request rate limits. Additional tree checks for P13/P16 were not completed; P16 was also reviewed through its public `docs/architecture.md`. Dates and stars obtained before the limit were not extrapolated. [37,41]
- [assumption] The presence of defensive text/regex does not measure model resilience to instruction injection. Testing would require a separate set of malicious emails, attachments, and scenarios involving other agent tools; README statistical claims were not accepted as our results.
- [docs] All available Work IQ attachment/page paths and changes to the Gmail Preview tool set were not established without signing in. Check the current reference, tenant policy, and `tools/list`; do not carry over old names. [4–8]
- [code] The following queries were used for reproducible search: `mcp server email IMAP EWS local search`, `gmail mcp server attachment read only`, `Microsoft official Outlook mail MCP server`, `MCP EWS exchangelib`, `email mcp local FTS5`, `email mcp ChromaDB`, `imap-mcp`, `microsoft-365-mcp-server`. The threshold of 15 was met; completeness of the entire market is not claimed. [1–22]

## Further topics to study

- [assumption] Leave EWS/NTLM and operation schemas to RT-02/RT-03, and IMAP sign-in methods to RT-04; this project survey does not replace them.
- [assumption] Study Russian morphology, attachment chunks, and model quality for P15/P16 in RT-06/RT-07; P15's English-language model does not establish quality on Russian mail. [20]
- [assumption] Relate bypasses through original files and MCP resources to RT-08/RT-09/RT-10.
- [code] P01/P02/P04–P07/P09/P10 also cover calendars, contacts, tasks, or other applications; those features were not reviewed. [3–6,9–12,14–15]

## Sources

All links below were read on 2026-10-24. Repository files have no stated publication date;
latest commit dates and branch snapshots are given in Т1. Links to `main` can change; for
rechecking, replace `main` with the corresponding SHA from the snapshot after Т1.

1. [Official MCP Registry](https://registry.modelcontextprotocol.io/) — starting point for the search; read 2026-10-24.
2. [modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers) — catalog and links to the registry; read 2026-10-24.
3. [Microsoft Mail reference](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-mail-tools) — updated 2026-09-30; read 2026-10-24.
4. [Microsoft: MCP options in Agent 365](https://learn.microsoft.com/en-us/microsoft-agent-365/tooling-servers-overview) — updated 2026-10-13; read 2026-10-24.
5. [Work IQ MCP overview](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/overview) — updated 2026-10-13; read 2026-10-24.
6. [Work IQ MCP tool reference](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/tool-reference) — change policy, `fetch`, `fetch_blob`; read 2026-10-24.
7. [Google: Configure the Gmail MCP server](https://developers.google.com/workspace/gmail/api/guides/configure-mcp-server) — Developer Preview, OAuth, security; read 2026-10-24.
8. [Google: Gmail MCP reference](https://developers.google.com/workspace/gmail/api/reference/mcp) — published tool set; read 2026-10-24.
9. [Softeria README](https://github.com/Softeria/ms-365-mcp-server/blob/main/README.md) — accounts, limits, modes, tokens; read 2026-10-24.
10. [ryaker README](https://github.com/ryaker/outlook-mcp/blob/main/README.md) — tools and OAuth; read 2026-10-24.
11. azizmazrou: [root README](https://github.com/azizmazrou/ews-mcp/blob/main/README.md), [4.5 line](https://github.com/azizmazrou/ews-mcp/blob/main/v5/README.md) — read 2026-10-24.
12. [ShermanGu README](https://github.com/ShermanGu/exchange-ews-mcp/blob/main/README.md) — EWS/NTLM, drafts, Windows Credential Manager; read 2026-10-24.
13. [GongRzhe README](https://github.com/GongRzhe/Gmail-MCP-Server/blob/main/README.md) — tools, attachments, OAuth files; read 2026-10-24.
14. [Google Workspace MCP README](https://github.com/taylorwilsdon/google_workspace_mcp/blob/main/README.md) — modes, accounts, file limits; read 2026-10-24.
15. [codefuturist README](https://github.com/codefuturist/email-mcp/blob/main/README.md) — accounts, read mode, cache, secrets; read 2026-10-24.
16. [RushB-fr README](https://github.com/RushB-fr/mcp_email/blob/main/README.md) — six tools, organizations, encryption, license; read 2026-10-24.
17. [ni-c README](https://github.com/ni-c/imap-mcp/blob/main/README.md) — modes, attachments, limits, untrusted text; read 2026-10-24.
18. [Tillman32 README](https://github.com/Tillman32/mcp-email/blob/main/README.md) — claimed FTS5, multiple accounts, attachments; read 2026-10-24.
19. [DreamC0der-AI README](https://github.com/DreamC0der-AI/proton-mail-mcp/blob/main/README.md) — local database and Bridge; read 2026-10-24.
20. [JonLaliberte README](https://github.com/JonLaliberte/mail-semantic-search/blob/main/README.md) — local models, indexing, maintenance; read 2026-10-24.
21. [marshalltech81 README](https://github.com/marshalltech81/protonmail-local-ai/blob/main/README.md) — current model modes and text transfer; read 2026-10-24. Current `INFERENCE_MODE=none` differs from an older search snippet with `anthropic` as the default; the current file was used.
22. [zayansalman README](https://github.com/zayansalman/gmail-attachments-mcp/blob/main/README.md) — three tools and read-only OAuth; read 2026-10-24.
23. [EWS: tools/mail_read.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/tools/mail_read.py) — definitions, pagination, attachment saving; read 2026-10-24.
24. [EWS: cache/store.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/cache/store.py) — SQLite/FTS5; read 2026-10-24.
25. [EWS: cache/sync.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/cache/sync.py) — change states, index fields, and vectors; read 2026-10-24.
26. [EWS: config.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/config.py) — default values; read 2026-10-24.
27. [ni-c: tools/read.ts](https://github.com/ni-c/imap-mcp/blob/main/src/tools/read.ts) — definitions and read handlers; read 2026-10-24.
28. [ni-c: server.ts](https://github.com/ni-c/imap-mcp/blob/main/src/server.ts) — conditional registration of write tools; read 2026-10-24.
29. [ni-c: result.ts](https://github.com/ni-c/imap-mcp/blob/main/src/result.ts) — response limit and untrusted-text formatting; read 2026-10-24.
30. [ni-c: SECURITY.md](https://github.com/ni-c/imap-mcp/blob/main/SECURITY.md) — trust model and protection limitations; read 2026-10-24.
31. [JonLaliberte: attachment_extractor.py](https://github.com/JonLaliberte/mail-semantic-search/blob/main/mail_semantic_search/attachment_extractor.py) — formats and 10 MiB limit; read 2026-10-24.
32. [JonLaliberte: mcp_server.py](https://github.com/JonLaliberte/mail-semantic-search/blob/main/mail_semantic_search/mcp_server.py) — complete tool set; read 2026-10-24.
33. [DreamC0der-AI: db.py](https://github.com/DreamC0der-AI/proton-mail-mcp/blob/main/db.py) — FTS5, synchronization, and lazy bodies; read 2026-10-24.
34. [DreamC0der-AI: server.py](https://github.com/DreamC0der-AI/proton-mail-mcp/blob/main/server.py) — 14 tools, body limit, files; read 2026-10-24.
35. [codefuturist: cache/schema.ts](https://github.com/codefuturist/email-mcp/blob/main/src/cache/schema.ts) — message keys and FTS5 fields; read 2026-10-24.
36. [codefuturist: cache/sync-engine.ts](https://github.com/codefuturist/email-mcp/blob/main/src/cache/sync-engine.ts) — UIDVALIDITY, CONDSTORE, QRESYNC limitations; read 2026-10-24.
37. GitHub REST API, metadata and branch tips — read 2026-10-24; for each repository, `GET /repos/{owner}/{repo}/commits?per_page=1` was also issued:
    - [P04 Softeria](https://api.github.com/repos/Softeria/ms-365-mcp-server), [commit](https://github.com/Softeria/ms-365-mcp-server/commit/991aa5c63726470019d6c7a01ba03bc8c7cbf678).
    - [P05 ryaker](https://api.github.com/repos/ryaker/outlook-mcp), [commit](https://github.com/ryaker/outlook-mcp/commit/95d6ff2d248903c086f17782c2de28e16016e6aa).
    - [P06 azizmazrou](https://api.github.com/repos/azizmazrou/ews-mcp), [commit](https://github.com/azizmazrou/ews-mcp/commit/d34f69f148c9e67f8324f84add0f803c516fecf6).
    - [P07 ShermanGu](https://api.github.com/repos/ShermanGu/exchange-ews-mcp), [commit](https://github.com/ShermanGu/exchange-ews-mcp/commit/859b275db83184c9125ae50551c8d0fe89ad1c39).
    - [P08 GongRzhe](https://api.github.com/repos/GongRzhe/Gmail-MCP-Server), [commit](https://github.com/GongRzhe/Gmail-MCP-Server/commit/a890d19189bbc1325b8728fab830fc278cfd8804).
    - [P09 taylorwilsdon](https://api.github.com/repos/taylorwilsdon/google_workspace_mcp), [commit](https://github.com/taylorwilsdon/google_workspace_mcp/commit/75739112ceced23d8c8514216a774c6313de9685).
    - [P10 codefuturist](https://api.github.com/repos/codefuturist/email-mcp), [commit](https://github.com/codefuturist/email-mcp/commit/7d31cd5eac8788bd69237c18e3ee1e1fe2816ab7).
    - [P11 RushB-fr](https://api.github.com/repos/RushB-fr/mcp_email), [commit](https://github.com/RushB-fr/mcp_email/commit/415e062f39649f658703a98345662135cb8f5260).
    - [P12 ni-c](https://api.github.com/repos/ni-c/imap-mcp), [commit](https://github.com/ni-c/imap-mcp/commit/255a23c65c6e62eb41b5846b38015b2951e3ed7e).
    - [P13 Tillman32](https://api.github.com/repos/Tillman32/mcp-email), [commit](https://github.com/Tillman32/mcp-email/commit/de79bb1521cd7783985c9a3fc664d1fba1043427).
    - [P14 DreamC0der-AI](https://api.github.com/repos/DreamC0der-AI/proton-mail-mcp), [commit](https://github.com/DreamC0der-AI/proton-mail-mcp/commit/f8f540da9bc416ad668a1a80f2a1d2516290c711).
    - [P15 JonLaliberte](https://api.github.com/repos/JonLaliberte/mail-semantic-search), [commit](https://github.com/JonLaliberte/mail-semantic-search/commit/9d642f7cf8b52b20f66b2277fd0c156c4966ad36).
    - [P16 marshalltech81](https://api.github.com/repos/marshalltech81/protonmail-local-ai), [commit](https://github.com/marshalltech81/protonmail-local-ai/commit/1cf5859c0693331bd330985cd81505599ce9b21a).
    - [P17 zayansalman](https://api.github.com/repos/zayansalman/gmail-attachments-mcp), [commit](https://github.com/zayansalman/gmail-attachments-mcp/commit/2f54ec5677fd04d351fe2796c56be058ebabad8a).
38. [EWS: tools/writes.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/tools/writes.py) — write-tool names; read 2026-10-24.
39. [EWS: tools/base.py](https://github.com/azizmazrou/ews-mcp/blob/main/v5/ewsmcp/tools/base.py) — dispatcher, tiers, and content confirmation; read 2026-10-24.
40. [JonLaliberte: index.py](https://github.com/JonLaliberte/mail-semantic-search/blob/main/mail_semantic_search/index.py) — combining attachment text and deduplication; read 2026-10-24.
41. [marshalltech81: docs/architecture.md](https://github.com/marshalltech81/protonmail-local-ai/blob/main/docs/architecture.md) — synchronization, tables, and search passes; read 2026-10-24.
42. [ni-c: config.ts](https://github.com/ni-c/imap-mcp/blob/main/src/config.ts) — read-only and default service keyword; read 2026-10-24.

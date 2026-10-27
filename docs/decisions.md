# mail-mcp-server — decisions

Current user decisions and boundaries that a change cannot cross without a user decision.
Revised decisions are replaced by their final versions; history is in git. The server design is
in [plan.md, section 3](plan.md#3-design) and `docs/*-agent.md`, version scope in
[plan.md, section 4](plan.md#4-version-010), and later improvements in [backlog.md](backlog.md).

## 1. What requires a user decision

Do not add:

- shared parsing budgets, counters or deadlines, or a separate parsing process. Exception:
  approved container expansion limits (`docs/mime-agent.md`, decision Д10 in section 2):
  per-message constants, without settings or a shared budget object;
- SQLite or another database alongside the index;
- states beyond `downloaded`/`converted`/`failed` with a reason;
- custom HTML-to-text conversion or charset guessing;
- a fallback for missing pandoc;
- automatic sign-in retries or mailbox writes;
- speculative safeguards that the task does not require.

## 2. Current decisions

User decisions, except those marked "Proposed". Rows are in date order.

| Date | Decision | Reason |
|---|---|---|
| 2026-10-24 | Build our own server following redmine-mcp-server | Need attachment search, a local index and, later, a secret filter |
| 2026-10-24 | Keep real hosts, domain and login out of the repository; use placeholders in documents | The repository is public on GitHub |
| 2026-10-24 | Agent instructions are in `AGENTS.md` and `docs/*-agent.md`; all project documentation is in English (language scope updated on 2026-10-28) | Other models also carry out tasks; the user requested English project documentation |
| 2026-10-24 | Several mailboxes: a connections list in a separate file, following jdbc-mcp-server; EWS and IMAP sources | Corporate Exchange, Gmail and Mail.ru; credentials outside the project |
| 2026-10-24 | Every tool, including search, requires `connection`; no cross-mailbox search in one call | As in jdbc-mcp-server |
| 2026-10-24 | П1. EWS client: our own thin SOAP layer on StAX; transport: Apache HttpClient 5 with NTLM explicitly enabled | `ews-java-api` is abandoned; 10 read operations are needed (RT-02, RT-03) |
| 2026-10-24 | П2. Message key: a local UUID; remote reference, `Message-ID` and SHA-256 stored separately | `ItemId` changes on moves; `Message-ID` is not unique |
| 2026-10-24 | П3. IMAP through Angus Mail: Mail.ru uses an app password; Gmail uses an app password or OAuth2 (since 2026-10-25) | One source for both mailboxes |
| 2026-10-24 | П4. Read-only at protocol level; IMAP `CLOSE` is allowed (2026-10-25) | Other servers' "read-only" implementations sometimes write flags; `CLOSE` after `EXAMINE` deletes nothing |
| 2026-10-24 | П5. Copy and adapt sibling project code, citing the project and commit | A shared library is premature |
| 2026-10-24 | П6. Java 25, Spring Boot `4.2.0-RC1`, Spring AI `2.1.0-RC1`; move to `2.1.0` when released | The 2.1 line was chosen deliberately |
| 2026-10-24 | П7. Short cards in lists and search; message and attachment content through separate, chunked calls | An approach from the best servers studied (RT-01) |
| 2026-10-24 | П9. Search: Lucene 10.x, `RussianAnalyzer`, a separate English field, unstemmed fields for addresses and filenames | SQLite FTS5 has no Russian stemmer (RT-06) |
| 2026-10-24 | П12. Embeddings are outside the MVP; deferred until real semantic search misses (2026-10-27) | BM25 is enough to start (RT-07); the agent can rephrase queries itself and vectors are costly |
| 2026-10-24 | П13. Mail access through tools only; MCP resources `mail://<connection>/message/<key>` and `resource_link` in responses (Д14) rejected (2026-10-27) | Client resource support is incomplete (RT-10), and `resource_link` support is unconfirmed; tools already read messages and attachments in chunks with secret filtering; users do not know message keys, making manual attachment inconvenient; `resources/read` returns the whole object and would need its own limits and filter |
| 2026-10-25 | Implement Gmail OAuth2 | User decision |
| 2026-10-25 | No SQLite: metadata in `meta.json`, cursors in `folders.json`, Lucene for both catalog and search | A database is unnecessary |
| 2026-10-25 | A directory per message with `message.eml`, `meta.json`, `body.md`, attachments and their `.md` files; file paths may be returned to the model | Disk space is not a constraint; file links are convenient for the agent |
| 2026-10-25 | Sync only on request, without a depth parameter: everything first, then incremental | User decision |
| 2026-10-25 | MIME parsing through Angus Mail; no separate parsing process or shared limits across the nesting chain; offline parsing from `d6534ca` reverted | Unnecessary complexity |
| 2026-10-25 | Pandoc is required; PDF, XLSX and PPTX use PDFBox and POI from redmine-mcp-server | Pandoc cannot read PDF or Excel |
| 2026-10-25 | Proposed: `pandoc --sandbox` for each conversion instead of `pandoc server` | `pandoc server` listens on `0.0.0.0` |
| 2026-10-25 | Proposed: an invalid EWS `SyncState` triggers a folder rescan without downloading known messages again, rather than a reset | `ItemId` values remain valid |
| 2026-10-25 | Proposed: after a rejected sign-in, `sync` refuses in all processes until `mail-sync` is run from a terminal | Repeated agent calls must not lock the domain account |
| 2026-10-26 | Д6: the optional filter replaces secrets only in tool responses; downloads, disk and index remain unchanged, and original paths remain available | Protection against accidental context exposure, not intentional extraction; the user authorized implementation and detail choices without questions |
| 2026-10-26 | The draft is complete; next: polish and improvements | User decision |
| 2026-10-26 | No filename substring search | Searching filename parts is enough |
| 2026-10-26 | Д10: files from attached messages and archives are ordinary message attachments (a flat list in `attachments/`, with a `parent` field). Expansion limits: container depth up to 5, archive entry up to 20 MiB, up to 1000 extracted files and 256 MiB per message | Documents inside archives must be searchable; limits protect against ZIP bombs and self-nested archives |
| 2026-10-27 | Д8: detect moves without downloading using a `Message-ID` and metadata heuristic; no strict SHA-256 match against remote MIME is required | The user chose avoiding downloads and accepted a heuristic; current sources do not obtain remote SHA-256 |
| 2026-10-27 | Д12: irregular forms only through an optional user `word-forms.txt` in the data directory, shared by all connections; no built-in list in the jar, no Hunspell; single-word synonyms in groups are allowed; include `mail-analyze` immediately | The user or agent edits the list; personal words stay out of the public repository; behavior is unchanged without the file |
| 2026-10-27 | Д22: optional `passwordCommand` (password managers) instead of a password in the file; obtain its result on first sync and keep it in process memory; plaintext passwords and `${VAR}` remain supported. Gmail refresh token is separate (Д27); an app password is the default for personal Gmail | Some users prefer a password in a file, others need a password manager; non-Windows platforms may be supported later; frequent syncs must not prompt for unlocking every time |
| 2026-10-27 | Д28: Windows Credential Manager through a built-in `windowsCredential` key (Java foreign function API), replacing a long `passwordCommand` with a script; remove the script | The long PowerShell invocation is inconvenient; built-in reading depends on neither repository path nor PowerShell |
| 2026-10-27 | Accept IT-14/IT-15 (EML/ZIP/MSG/TNEF) and IT-16 (DOC/XLS/PPT text) through offline verification after review fixes; do not record reprocessing or real-data check results yet | The user accepted the improvements; existing-data checks have not run yet |
| 2026-10-27 | Skip unparsed IMAP metadata responses: message metadata stays unknown, sync continues, log a counter | The first Mail.ru run with a metadata pass failed on the first batch; one unusual message must not stop mailbox sync |
| 2026-10-28 | Д29: identity metadata only for new messages; flags of known messages come with folder changes (IMAP `FLAGS` in the UID listing, EWS `ReadFlagChange`/`Update`); obtain missing fingerprints of removed messages from `message.eml` headers, without a one-time server pass | A full pass cost 48.6 s per EWS run and over an hour for Mail.ru; Д8 was not supposed to slow ordinary sync |
| 2026-10-28 | Д30: a run without changes does not read every `meta.json`: full index reconciliation only after an incomplete run; flags and move uniqueness use the index. Target: a couple of seconds for a run without changes | After Д8 and flags, every run read all `meta.json` files 2–3 times; otherwise revert Д8 and flags (user decision) |
| 2026-10-28 | First published version: `0.1.0`, Apache 2.0 license (Д18) | As in jdbc-mcp-server; dependencies are release candidates and some real-mail checks are still pending |
| 2026-10-28 | Separate documents: `plan.md` for status, pending checks, principles and version scope; `backlog.md` for later improvements; `results.md` for real-mail checks; `decisions.md` for decisions; user documents: `docs/connections.md`, `clients.md`, `commands.md`, `search.md`, `security.md`; `docs/*-agent.md` describes internals and links to user documents | The plan had grown and duplicated engineering documents; the first release needs user documentation |
| 2026-10-28 | Scope of `0.1.0`: Д18 (publication) and Д7 (Gmail, checked on a real mailbox); other improvements go to `docs/backlog.md`. Of the pending checks, only `mail-reprocess` on both mailboxes is required before publication | User decision |
| 2026-10-28 | Translate project documentation into English and use English for future documentation changes | User request |

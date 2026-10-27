# mail-mcp-server — improvements after 0.1.0

Possible improvements outside version `0.1.0` (decision of 2026-10-28). Current version scope is
in [plan.md, section 4](plan.md#4-version-010); rejected ideas and reasons are in
[decisions.md](decisions.md).

Status: ⬜ not started · 🔨 in progress. Size: S — hours, M — a brief for one session, L — several
briefs. When the user includes an item in a version, move its row to section 4 of the plan. A new
idea gets a new row with the next number; numbers are never reused (next available: Д31).

| ID | Improvement | Scope | Source | Size | Status |
|---|---|---|---|---|---|
| Д13 | Embeddings and hybrid search | `multilingual-e5` through ONNX Runtime in the JVM, combined with BM25 (RRF). Deferred until real semantic search misses: the search client is an agent that can rephrase queries itself. Cost: a model in every server process's memory, hours of initial processing, custom chunking, kNN without exact `total` or highlighting, and quality assessed only by the user. If taken up: a flag, E5-small, a separate `search` mode, BM25 by default | П12, RT-07, discussion of 2026-10-27 | L | ⬜ |
| Д15 | Drafts behind a flag | A `createDraft` tool (new message or reply with conversation headers and local attachments), enabled by a connection flag, off by default. Shared MIME construction; EWS: `CreateItem` with `MimeContent` and `SaveOnly`; IMAP: `APPEND` to Drafts with `\Draft`, without `SELECT`. No editing or deleting drafts; sending is not planned (an exfiltration channel under prompt injection). A separate project extension: changes the read-only invariant and adds mailbox sign-in on agent calls. To verify: Mail.ru's `\Drafts` attribute, draft appearance in the web interface, app password write permissions | MVP goals, discussion of 2026-10-27 | L | ⬜ |
| Д16 | 26 unparsed Mail.ru messages | A command that prints the error type and `Content-Type` header without content; decide which issues to fix | reprocessing on 2026-10-25 | S | ⬜ |
| Д19 | Spring AI 2.1.0 | Move from `2.1.0-RC1` to `2.1.0` when released | П6 | S | ⬜ |
| Д20 | Retry on `ErrorServerBusy` | Wait `BackOffMilliseconds` and retry once. Only if throttling becomes a problem: none occurred with 8345 messages | EWS risk | S | ⬜ |
| Д23 | Fallback EWS transport | `HttpsURLConnection` with SSPI in case of Extended Protection on another server | П1, RT-02 | M | ⬜ |
| Д27 | Gmail refresh token encryption | On Windows, store the token value in `google-tokens.json` under DPAPI (CurrentUser), with bindings in plaintext; on other OSes, keep the current owner-only file. Needed only for Gmail over OAuth2; for a personal mailbox, an app password through `windowsCredential` (Д28) or `passwordCommand` (Д22) is simpler | Д22 discussion of 2026-10-27 | M | ⬜ |

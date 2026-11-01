# mail-mcp-server — real-mailbox checks

Verified environment facts and results of runs on the user's real mailboxes. Numbers only:
no folder names, addresses, filenames or message content. Features implemented but still awaiting
real-mail checks are in [plan.md, section 2](plan.md#2-checks-pending-on-real-mail).

Real hosts, domain and login stay out of the repository. Documents use these placeholders:

- `example.com` — domain;
- `webmail.example.com` — OWA and EWS;
- `mail.example.com` — ActiveSync, the same server;
- `user` — login.

The mapping between placeholders and real values exists only in `.local/environment.md`, which
git ignores.

## 1. Corporate Exchange

| Item | Fact | Verified on |
|---|---|---|
| Server | Exchange 2013 CU23 (build 15.0.1497.x, IIS 8.5); `webmail.` and `mail.example.com` are the same server | 2026-10-24, anonymous requests |
| EWS | `https://webmail.example.com/EWS/Exchange.asmx` is accessible from the internet; without sign-in it returns `401` and offers `Negotiate`, `NTLM`, `Basic` | 2026-10-24 |
| Autodiscover | Only at `/autodiscover/…` on the main hosts; no `autodiscover.` host | 2026-10-24 |
| Sign-in | **NTLM with a bare login** (`user`) works; Basic with that login returns `401`; Basic with a domain was not checked | 2026-10-24, PowerShell sign-in probe (removed in IT-11) |
| Schema version | `RequestServerVersion Exchange2013_SP1` is accepted; Cyrillic arrives in UTF-8 | 2026-10-24 |
| NTLM from Java | Apache HttpClient 5: 3-request handshake, then connection reuse; no fallback transport needed | 2026-10-25, `ews-spike` |
| Sync | 11 folders, 8345 messages downloaded, 0 errors, no `ErrorServerBusy`. Repeat run: 0 new messages, 102 user deletions applied. `ItemId` values are stable and `SyncState` is accepted | 2026-10-25, `mail-sync work` |
| Reprocessing | 8243 messages, all `converted` | 2026-10-26, `mail-reprocess work` |
| EML/ZIP/MSG/TNEF expansion and DOC/XLS/PPT text | Passed by the user's report; message counts were not recorded | 2026-11-01, `mail-reprocess work` and search |
| Sync without changes (Д29, Д30) | 2.4 s instead of 48.6 s. A run with 1916 deletions and 2195 messages with changed read status took 112.8 s: time grows with the number of changes, not mailbox size | 2026-10-28, `mail-sync work` |
| Flag through `Update` (Д29) | A flag set on one message in the mail client arrived as `Update`: 3.2 s run, one `meta.json` rewritten with `flagged: true`, others untouched | 2026-10-28, `mail-sync work` |
| Read status through `ReadFlagChange` (Д29) | After marking all messages in a folder as read in the mail client, the run took 7 s: 735 messages in the folder had `read: true`, 112 `meta.json` files were rewritten (previously unread), other folders untouched | 2026-10-28, `mail-sync work` |
| Outlook category addition and removal (Д31) | 1 existing message: after adding 1 category, label search returned 1 hit and `search`, `listMessages` and `getMessage` showed the category. After removal and sync, label search returned 0 hits and the same message had an empty label list. The removal sync completed in 3.7 s over 11 folders: 0 downloaded, 0 failed, 0 removed | 2026-11-01, user category edits, user-requested MCP `sync` and read tools |
| Password from Windows Credential Manager | NTLM sign-in with a password from a generic Credential Manager entry, obtained through `passwordCommand` and a PowerShell script (before Д28): 11 folders, 49 new messages, 0 errors. The Add Generic Credentials dialog rejects a username containing `@` (error `0x80070057`) | 2026-10-27, `mail-sync work` |

The folder list includes service folders of class `IPF.Note*` (RSS feeds, Sync Issues); this is
harmless.

## 2. Mail.ru and Gmail

| Item | Fact | Verified on |
|---|---|---|
| Mail.ru sign-in | IMAP with an app password through Angus Mail; downloading does not change `\Seen` (checked on a read message) | 2026-10-25, `imap-spike mailru` |
| Mail.ru sync | 39 of 41 folders after folder filtering, 38327 messages, 0 errors. Repeat run: 0 new messages, 4296 user deletions applied | 2026-10-25, `mail-sync mailru` |
| Mail.ru reprocessing | 34031 messages, 34005 `converted`, 26 `failed`: 19 Angus `ParseException`, 7 charsets unavailable in Java. About 35 minutes including attachments | 2026-10-26, `mail-reprocess mailru` |
| Mail.ru EML/ZIP/MSG/TNEF expansion and DOC/XLS/PPT text | Passed by the user's report; message counts were not recorded | 2026-11-01, `mail-reprocess mailru` and search |
| Mail.ru first run with a metadata pass (Д8, read/star flags) | Sign-in succeeded; index rebuilt from schema 4 (over 20 thousand messages from disk), then `RESULT: FAILED (IMAP operation failed; no automatic retry)`, kind `PROTOCOL`, no folder completed, 1115 s run. After sanitized diagnostics were added, a repeat failed in 25 s on batch 1 of 132 in the first folder: Angus could not parse the `FETCH (ENVELOPE … FLAGS UID)` response for at least one message, and strict IT-19 validation aborted the entire run. Fixed: skip such messages with unknown metadata (`docs/imap-source-agent.md`). Next run: 1–2 unparsed responses in about every third batch of 100, a full pass at about 13.5 s per batch (over an hour per mailbox); interrupted by the user, after which the full pass was removed (Д29) | 2026-10-27, `mail-sync mailru` |
| Mail.ru sync without changes (Д29, Д30) | 14 s for 39 folders and 34 thousand messages: network `EXAMINE` and `FETCH (FLAGS UID)`; before Д30: 297 s, before Д29: over an hour. The first run after Д29 wrote all message flags once: about 12.5 minutes, without network load | 2026-10-28, `mail-sync mailru` |
| Mail.ru read status and flag (Д29) | Changes from the web interface arrive through `FETCH (FLAGS UID)`: three messages marked as read rewrote three `meta.json` files with `read: true`; flagging one message rewrote one `meta.json` with `flagged: true`. Runs took 15 s, other messages untouched | 2026-10-28, `mail-sync mailru` |
| Mail.ru initial labels refresh (Д32) | First run: 28 folders, 866.8 s, 0 downloaded, 0 failed, 0 removed. Repeat run: 28 folders, 12.3 s, 0 downloaded, 0 failed, 0 removed | 2026-11-01, user-run `mail-sync mailru`, local process inspection and saved sync status |
| Mail.ru web category and IMAP labels (Д32) | 1 existing message assigned 1 built-in web category: 0 raw IMAP user flags before filtering, 0 labels after filtering, 0 label-search hits; `search` and `getMessage` returned empty labels. Across 27439 live index documents: 0 nonempty label lists, 27439 empty lists, 0 unknown lists. General custom-keyword addition/removal was not checked | 2026-11-01, user-run read-only FLAGS probe, MCP read tools and offline index summary |
| Mail.ru OBJECTID capability (Д33) | After the server restart, the capability log reported `IMAP extensions: objectId=false`: Mail.ru did not advertise OBJECTID in this session. Sync completed over 28 folders: 0 downloaded, 0 failed, 0 removed. No EMAILID move test was required; the fingerprint heuristic remains in use | 2026-11-01, user-requested MCP `sync`, `syncStatus` and the fixed capability log line |
| Mail.ru sender summary (Д37) | 5 `summarizeSenders` calls before dates per folder and `from` were added. One folder: 13211 stored, 13190 after copy grouping, 1351 senders. Whole mailbox by domain: 27359 messages, 755 domains, about 0.84 s including the MCP call (one measurement); 3676 JSON characters for the first 20 domains. Counts equal to `listMessages` or `search` in 3 of 3 comparisons: 156, 523 and 241 messages | 2026-11-01, an agent's MCP calls on the user's request, on saved data |
| Mail.ru dates per folder and `from` (Д37) | One sender split 235/6 across 2 folders: dates per folder showed the larger part ending about three years before the smaller part began. Sender, folder and subject words: `summarizeSenders` and `search` both returned 24 messages, 7 within one month. An uppercase address, `from` alone (newest first), sender with folder and dates, and a query without matches (zero totals) behaved as documented | 2026-11-01, an agent's MCP calls on the user's request, on saved data |
| Mail.ru sync after sorting by mail rules | 43 downloaded, 0 failed. Two new folders went from 0 to 411 and 84 messages while one folder fell from 13211 to 12720. At most 43 of those 495 messages can have been downloaded, so at least 452 reused saved content as moves. Keys and paths were not compared | 2026-11-01, an agent's MCP `sync` on the user's request, as reported by the agent |
| Gmail app-password synchronization | A resumed initial run with 256 KiB blocks completed: 8 folders, 1344 downloaded, 2 failed, 0 removed. These are the resumed run's counters; messages saved before the restart are not included in its download count | 2026-10-28, user-run `mail-sync gmail` |
| Gmail IMAP block size (IT-26) | 16 KiB: 20 messages and bodies in 54.831 s (21.9/min). 256 KiB: 81 messages and bodies in 128.329 s (37.9/min), about 1.73 times faster. The intervals contain different messages and are not a controlled benchmark | 2026-10-28, user-run `mail-sync gmail` |
| Gmail All Mail with labels (Д7) | First run after the change: the index rebuilt to schema 5 from disk; 7 folders reported (6 vanished label folders and All Mail), 0 downloaded, 0 failed, 1147 label-folder copies removed; the All Mail copies were kept without downloading MIME again. Repeat run: 1 folder, 0 downloaded, 0 failed, 0 removed. Tools: `listFolders` shows one folder with 734 messages; `search` with `field=label` for `Inbox` returns 670 messages; `getMessage` returns `labels` for a converted message. A UIDVALIDITY reset was not observed, so reuse by `stableId` across a reset is checked offline only | 2026-10-28, user-run `mail-sync gmail` and MCP tools |
| Gmail OAuth2 | Desktop PKCE and XOAUTH2 are implemented; real OAuth2 sign-in is unverified | 2026-10-25 |

## 3. Tools from an MCP client (opencode)

On 2026-10-26, the eight original tools were checked on both mailboxes:

- **`listFolders`:** counts match downloaded data.
- **`listMessages`, `getMessage`, `getAttachment`:** return pages and chunks with `nextOffset`;
  file paths are absolute.
- **`sync` and `syncStatus`:** `sync` responds immediately; `syncStatus` shows progress and result.
- **`search`:** finds other Russian word forms, English words, a full sender address, a filename
  part and a word present only in a DOCX attachment.

Repeat checks on 2026-10-27:

- **`sync` on `work`:** before the run, `syncStatus` showed `never`: `work` data had been downloaded
  by a build that did not yet write `sync-status.json`. The first tool run took 10 s: 90 new
  messages, 0 errors, 357 deletions, all from one folder; that folder is now empty on the server
  (user cleanup or a retention policy: to verify). Repeat run: 1.8 s, all zeroes.
- **Speed:** `listMessages`: 3–24 ms per page; `getMessage`: 29–49 ms; `search` with three results:
  23–102 ms; a page of 100 results: 0.1–2 s.
- **Word forms:** singular and plural queries return the same result count (362).
- **Missing features:** date selection: for "messages from two days", the agent paged through
  `listMessages` in every folder (Д24); a background run summary in the log (Д25).

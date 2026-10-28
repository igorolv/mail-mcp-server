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
| Sync without changes (Д29, Д30) | 2.4 s instead of 48.6 s. A run with 1916 deletions and 2195 messages with changed read status took 112.8 s: time grows with the number of changes, not mailbox size | 2026-10-28, `mail-sync work` |
| Flag through `Update` (Д29) | A flag set on one message in the mail client arrived as `Update`: 3.2 s run, one `meta.json` rewritten with `flagged: true`, others untouched | 2026-10-28, `mail-sync work` |
| Read status through `ReadFlagChange` (Д29) | After marking all messages in a folder as read in the mail client, the run took 7 s: 735 messages in the folder had `read: true`, 112 `meta.json` files were rewritten (previously unread), other folders untouched | 2026-10-28, `mail-sync work` |
| Password from Windows Credential Manager | NTLM sign-in with a password from a generic Credential Manager entry, obtained through `passwordCommand` and a PowerShell script (before Д28): 11 folders, 49 new messages, 0 errors. The Add Generic Credentials dialog rejects a username containing `@` (error `0x80070057`) | 2026-10-27, `mail-sync work` |

The folder list includes service folders of class `IPF.Note*` (RSS feeds, Sync Issues); this is
harmless.

## 2. Mail.ru and Gmail

| Item | Fact | Verified on |
|---|---|---|
| Mail.ru sign-in | IMAP with an app password through Angus Mail; downloading does not change `\Seen` (checked on a read message) | 2026-10-25, `imap-spike mailru` |
| Mail.ru sync | 39 of 41 folders after folder filtering, 38327 messages, 0 errors. Repeat run: 0 new messages, 4296 user deletions applied | 2026-10-25, `mail-sync mailru` |
| Mail.ru reprocessing | 34031 messages, 34005 `converted`, 26 `failed`: 19 Angus `ParseException`, 7 charsets unavailable in Java. About 35 minutes including attachments | 2026-10-26, `mail-reprocess mailru` |
| Mail.ru first run with a metadata pass (Д8, read/star flags) | Sign-in succeeded; index rebuilt from schema 4 (over 20 thousand messages from disk), then `RESULT: FAILED (IMAP operation failed; no automatic retry)`, kind `PROTOCOL`, no folder completed, 1115 s run. After sanitized diagnostics were added, a repeat failed in 25 s on batch 1 of 132 in the first folder: Angus could not parse the `FETCH (ENVELOPE … FLAGS UID)` response for at least one message, and strict IT-19 validation aborted the entire run. Fixed: skip such messages with unknown metadata (`docs/imap-source-agent.md`). Next run: 1–2 unparsed responses in about every third batch of 100, a full pass at about 13.5 s per batch (over an hour per mailbox); interrupted by the user, after which the full pass was removed (Д29) | 2026-10-27, `mail-sync mailru` |
| Mail.ru sync without changes (Д29, Д30) | 14 s for 39 folders and 34 thousand messages: network `EXAMINE` and `FETCH (FLAGS UID)`; before Д30: 297 s, before Д29: over an hour. The first run after Д29 wrote all message flags once: about 12.5 minutes, without network load | 2026-10-28, `mail-sync mailru` |
| Mail.ru read status and flag (Д29) | Changes from the web interface arrive through `FETCH (FLAGS UID)`: three messages marked as read rewrote three `meta.json` files with `read: true`; flagging one message rewrote one `meta.json` with `flagged: true`. Runs took 15 s, other messages untouched | 2026-10-28, `mail-sync mailru` |
| Gmail app-password sign-in and downloads | Sign-in and downloads succeeded. After the user restarted with 256 KiB blocks, one checkpoint reported 100 downloaded and 0 failed; full synchronization verification is pending (`Д7` in `0.1.0`) | 2026-10-28, user-run `mail-sync gmail` |
| Gmail IMAP block size (IT-26) | 16 KiB: 20 messages and bodies in 54.831 s (21.9/min). 256 KiB: 81 messages and bodies in 128.329 s (37.9/min), about 1.73 times faster. The intervals contain different messages and are not a controlled benchmark | 2026-10-28, user-run `mail-sync gmail` |
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

## 4. Search control set

Synthetic `testdata/search`, not real mail; recorded here because it sets the search quality
baseline. Without a word forms file (48 messages, 80 queries): recall 79/86 = 0.919, 7 false
positives. The 7 missed irregular forms, such as `письмо`/`писем`, are a Snowball limitation
without user rules. False positives: 3 name matches (`Тестову`→`Тестова`), 2 precision cases and
2 due to an old expectation of secret filtering before indexing. Under the current decision,
the index holds original text and the response filter does not change search matches. Test
threshold: recall ≥ 0.91, false positives ≤ 7.

With the four-group test word forms file (2026-10-27): 7/7 irregular forms and 86/86 matches
overall, with the same 7 false positives. Category details are in `docs/tools-agent.md`, "Search".

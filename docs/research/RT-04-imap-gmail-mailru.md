# RT-04 — Access to Gmail and Mail.ru over IMAP

- Date: 2026-10-24
- Author: Codex, RT-04 research session
- Brief: `docs/research-tasks/RT-04-imap-gmail-mailru.md` (historical path)
- Method: public documentation, RFCs, and source code; no connections to mail servers were made.

## Summary

- [docs] Gmail accepts an app password when two-step verification is enabled, or OAuth2 via XOAUTH2. App passwords have not been discontinued, but are not available to every account [1–3].
- [docs] IMAP has been permanently enabled for personal Gmail since January 2025. In Workspace, access is controlled by the administrator; the ordinary account password no longer works [2, 8, 9].
- [docs] IMAP OAuth requires the broad `https://mail.google.com/` scope. Personal-use apps are exempt from Google verification; Testing mode still limits the refresh token to seven days [3, 5–7, 33].
- [docs] Gmail labels are represented as folders. [assumption] Store MIME once by `(connection, X-GM-MSGID)` and track folder membership separately [10, 34].
- [docs] Google publishes an IMAP download limit of 2500 MB/day for Workspace; for personal Gmail this is a reference point, not a confirmed quota. Also account for the 15-client limit [2, 12].
- [docs] Mail.ru: `imap.mail.ru:993`, TLS, external-app password, and a linked phone. [code] OAuth for IMAP exists in Thunderbird; eligibility for a custom client has not been verified [16–19].
- [secondary] Mail.ru historically supports MOVE and UIDPLUS, but not IDLE; current CONDSTORE, QRESYNC, and SPECIAL-USE support is unconfirmed. Polling and CAPABILITY checks are needed [20].
- [docs][code] Angus Mail 2.0.5, released 2026-02-27, covers the required operations. Read with `Folder.READ_ONLY` and `BODY.PEEK`, and explicitly forbid mutating commands [22–27].
- [assumption] Recommendation for a personal MVP: use an app password with both providers if available; provide OAuth2 for Gmail. Synchronization must work without extensions, using CONDSTORE/QRESYNC only as accelerations after detection [1, 16, 29, 30].

## Answers to the questions

### В1. Gmail: sign-in methods, OAuth, and restrictions

**App password.** [docs] 2-Step Verification is required. The option may be unavailable if only hardware security keys are used, for an organizational account, or with Advanced Protection. Google recommends Sign in with Google; changing the main password revokes app passwords. Current help does not announce their general discontinuation [1].

**OAuth2.** [docs] IMAP requires `https://mail.google.com/` and the `AUTHENTICATE XOAUTH2` mechanism. An access token is passed; the refresh token is used separately to renew it. `gmail.readonly` is for the Gmail API and does not replace the IMAP permission [3, 33].

Preparation steps for a custom local application:

| Step | Requirement | Basis |
|---|---|---|
| Registration | Google Cloud project, Desktop app OAuth client, consent and data-access settings | [docs] [4] |
| Personal account | External audience; Internal is for an organization | [docs] [7] |
| Development | Testing, user added to test users; maximum 100 testers | [docs] [7] |
| Sign-in | System browser, authorization code, PKCE; local loopback callback supported for Desktop apps | [docs] [4] |
| Long-running use | Request offline access and store the refresh token outside the repository; allow for renewed consent | [docs] [4, 5] |
| Leaving Testing | For sustained personal use, use Published / In production; verification and publication status are separate properties | [docs] [6, 7] |

[docs] External + Testing issues a refresh token valid for **7 days**; the exception for access to basic profile data does not apply to mail. In production, a refresh token can still stop working because access was revoked, it was unused for six months, the password changed with Gmail scopes, an administrator imposed restrictions, or the token limit was reached [5].

[docs] `https://mail.google.com/` is a restricted scope. Apps used only by their owner or a few personally known users are exempt from verification. The unverified-app warning and user-count limits still apply [6, 7, 33].

[docs] For public distribution, the personal-use exception must not be treated as general permission. Restricted-scope verification and, when data is handled through a third-party server, a security assessment are considered separately. Google requires justification for the full-mail scope and recommends the Gmail API when narrower permissions suffice [3, 6].

[assumption] For a local server owned by one person, an app password reduces setup. A public OAuth release needs a separate assessment, including the transfer of message content through MCP to an external client or model; the personal-use exception should not automatically be carried over to such a release [3, 6].

**Workspace.** [docs] The administrator permits IMAP and can restrict OAuth clients. Internal apps in an organization have a different verification mode, unavailable to personal Gmail. App passwords depend on account policy and are not guaranteed [1, 7, 8].

[docs] Two official pages give different dates for ending ordinary password access: 2025-03-14 [9] and 2025-05-01 [8]. The first explicitly retains an exception for app passwords. For design as of 2026-10-24, the conclusion is the same: do not use the main password; this is not an announcement that special app passwords are being discontinued [8, 9].

### В2. Whether IMAP must be enabled in Gmail

[docs] For **personal** accounts, the Enable/Disable IMAP toggle was removed in January 2025: IMAP is always enabled. This does not remove sign-in requirements [2].

[docs] In Workspace, access is enabled in the Admin console [8]. The remaining `enabled` field in the Gmail API reference [14] does not prove that personal accounts have a UI toggle; follow the direct personal-account instructions [2].

### В3. Gmail extensions

[docs] `X-GM-EXT-1` advertises the set of Gmail extensions [10]:

| Element | Purpose |
|---|---|
| `X-GM-MSGID` | Shared message identifier across all folders; unsigned 64-bit number |
| `X-GM-THRID` | Thread identifier, also 64-bit; not an individual message identifier |
| `X-GM-LABELS` | Message labels in FETCH; changes via STORE are forbidden |
| `X-GM-RAW` | Gmail-syntax search through SEARCH / UID SEARCH |

[assumption] Store both Gmail IDs as decimal strings to avoid losing precision in JSON clients and depending on signed `long` [10].

| Extension | What is confirmed | Confidence |
|---|---|---|
| CONDSTORE | Present in a published Gmail CAPABILITY response | [secondary] Primary protocol material from a developer; not a 2026 measurement [11] |
| QRESYNC | Absent from that response; current support is not established | [secondary] Plan to work without it [11] |
| IDLE | Shown in an official Gmail example | [docs] [10] |
| MOVE | Present in a published server response | [secondary] [11] |
| UIDPLUS | Present in a published server response | [secondary] [11] |
| SPECIAL-USE | Roles are returned by LIST; do not rely only on a separate capability | [docs] [10] |

[docs] CAPABILITY can change after sign-in; request the current set during the working session [28]. [assumption] Do not treat an old documentation example as an exhaustive 2026 server matrix. Do not send QRESYNC or CONDSTORE based only on the host name [11, 29].

### В4. Gmail labels, folders, and deduplication

[docs] A message can have multiple labels [34]. IMAP represents them as folders; a message can occur in multiple folders with the same `X-GM-MSGID`, but folder UIDs differ [10, 28].

Recommended model [assumption] based on these properties:

| Entity | Key / contents |
|---|---|
| Gmail message | `(connection, X-GM-MSGID)`; one stored MIME copy |
| Folder membership | `(connection, folder, UIDVALIDITY, UID)` → message and observed flags |
| Labels and thread | Separate mutable metadata: labels, `X-GM-THRID` |
| Other IMAP server | Folder key is required; matching `Message-ID` alone is not grounds for merging |

[docs] The RFC permits Spam and Trash to be excluded from `\All` [32]. [assumption] Use this folder for the initial collection, check `\Drafts` separately, and include `\Junk` and `\Trash` at the user's choice. Compare coverage with the UI [10, 32].

[assumption] To represent folders accurately, retrieve their membership and flags separately, but do not download MIME again for a known `X-GM-MSGID`. A single pass over All Mail does not replace observing all selected folders and changes to their membership [10, 30].

[docs] Detect roles from LIST: `\All`, `\Sent`, `\Drafts`, `\Junk`, `\Trash`, `\Flagged`; Gmail also uses `\Important`. A `[GoogleMail]` prefix is possible. Do not hard-code `[Gmail]/All Mail` [10, 32].

[docs] IMAP folder-size limits allow 1000/2000/5000/10000 messages; 0 means no limit [14]. Check label visibility and folder limits before a full import; a hidden folder does not mean its messages were deleted [3, 14].

### В5. Gmail limits

| Limit | Confirmed value and scope |
|---|---|
| IMAP download | [docs] **2500 MB/day**, published for all Workspace editions [12] |
| IMAP upload | [docs] 500 MB/day for Workspace; the MVP uploads nothing [12] |
| Simultaneous access | [docs] Personal Gmail help: up to **15 mail clients**, then “Too many simultaneous connections” [2] |
| Connection duration | [docs] About 24 hours; with OAuth, usually around the access-token lifetime, roughly an hour [13] |
| Message size when reading | Verify: the sources read do not specify a universal upper bound for IMAP FETCH |
| Effects of exceeding traffic | [docs] Workspace: temporary suspension, usually one hour, possibly 24 hours [12] |

[assumption] Until the personal quota is clarified, use the Workspace number as a guide, account for other clients, limit concurrency, and save progress [12].

[secondary] `APPENDLIMIT=35651584` appears in protocol material [11]. This limits **appending**, not maximum read size. [docs] The RFC defines a separate `RFC822.SIZE` for message size [28]. [assumption] Check size before downloading, read as a stream, and return an explicit status if the local limit is exceeded instead of silently skipping the message.

[assumption] For network errors or server throttling, retry with an increasing delay and jitter; stop automatic attempts on sign-in errors. Do not confuse scheduled OAuth-session expiry with account blocking [12, 13].

### В6. Gmail API as an alternative

[docs] `history.list` returns changes after `historyId`; there are separate methods for messages, threads, and labels. An expired history requires full synchronization [15, 34]. [docs] `gmail.readonly` can be requested; this narrow scope is not suitable for IMAP [3, 33]. [docs] A Google Cloud project, enabled Gmail API, and OAuth client are required [4, 33]. [assumption] This is a separate source with additional quotas, but has a simpler label model; keep it as an alternative rather than substituting it for general IMAP in this study.

### В7. Mail.ru: connection and sign-in

[docs] `imap.mail.ru:993`, SSL/TLS. The ordinary email password does not work; a special password is required. A linked phone is needed to create one [16].

[docs] Path: Settings → All settings → Security → Passwords for external apps → Create → name → Continue → access type → Continue. For IMAP select **“Full access to Mail”**, not “Sending only”. Enter the generated value locally only; it can be revoked, but cannot be viewed again [16].

[docs] The external-services section has a toggle for IMAP, POP, and SMTP access; check its state [17]. The toggle's exact effect on a particular sign-in method and account UI is for the user to verify, not something to probe with agent attempts.

[code] In Thunderbird, `OAuth2Providers.sys.mjs` maps `imap.mail.ru` to `o2.mail.ru` and the `mail.imap` scope; it sets `/login` and `/token` endpoints [19]. [docs] Mail publishes an OAuth-for-developers section and describes OAuth sign-in for third-party programs [17, 18].

[assumption] Thus OAuth IMAP exists, but this does not prove that a new local client can freely obtain access. The registration documentation at `o2.mail.ru/docs/` was unavailable when read. Do not use Thunderbird's client ID or other credentials [19].

### В8. Mail.ru extensions

No current official matrix was found. Source [20] is an observation by the Aqua Mail developer dated **2017-04-28**, with no guarantee for 2026.

| Extension | What is known | Implementation implication |
|---|---|---|
| CONDSTORE | [secondary] Not in the list [20]; current state **?** | Do not require it; use only when CAPABILITY advertises it |
| QRESYNC | [secondary] Not in the list [20]; current state **?** | A UID reconciliation path is needed |
| IDLE | [secondary] Developer explicitly reported it absent [20]; check for 2026 | Support periodic polling |
| MOVE | [secondary] Present in the list [20]; check for 2026 | Do not call in read-only mode |
| UIDPLUS | [secondary] Present in the list [20]; check for 2026 | Not required for ordinary UID FETCH |
| SPECIAL-USE | [secondary] XLIST is listed, SPECIAL-USE is not [20]; state **?** | Check LIST attributes; allow role configuration |

[assumption] Store capabilities per session. Lack of found documentation does not mean lack of a feature; do not transfer properties of other Mail services here [20, 28].

### В9. Mail.ru: folders, limits, and peculiarities

[docs] `INBOX` is the standard special name in IMAP; the separator and other folder names are obtained through LIST. IMAP4rev1 uses modified UTF-7 for non-Latin names [28]. [assumption] Do not assume the web UI names “Sent”, “Trash”, and “Spam” are the exact IMAP folder names. The user should separately check localization and attributes for the current mailbox.

[assumption] First read roles from LIST, use XLIST if the server supports it, and if roles remain undefined use an explicit mapping in connection settings. Do not create folders to correct an unknown name [20, 32].

**Limits:** no confirmed numeric limits were found for Mail.ru IMAP connections, daily traffic, request frequency, or FETCH size. These are unknown values, not evidence of no limits. Do not apply Gmail's 15/2500 MB here.

[code] `MailKit/Net/Imap/ImapEngine.cs`, method `ParseNumber`, works around malformed Mail.ru numeric values containing a decimal point/exponent, particularly in BODYSTRUCTURE [21]. [assumption] Check Angus compatibility with such messages; for general downloading, use the MIME stream rather than making BODYSTRUCTURE a required step.

[secondary] The Aqua Mail developer also reported text search not working in 2017 [20]. [assumption] Treat current BODY/TEXT SEARCH operation as unverified; a local index removes dependence on the quality of that search.

### В10. Angus Mail: version and required APIs

[docs] The latest stable release on the project page is **2.0.5**; the GitHub release date **2026-02-27** was also checked using `relative-time datetime` in the HTML. It uses Jakarta Mail API 2.1.5. The home page contains an old news feed; use the release page as the reference [22].

| Task | Support / entry point | Source |
|---|---|---|
| XOAUTH2 | `mail.imap.auth.mechanisms=XOAUTH2`; pass the access token as the password argument to connect; token acquisition is separate | [docs] [23] |
| CONDSTORE | `open(Folder.READ_ONLY, ResyncData.CONDSTORE)`, `getHighestModSeq()`, `IMAPMessage.getModSeq()` | [docs] [23–25] |
| Changed messages | `getMessagesByUIDChangedSince(start, end, modseq)`, also fetches flags first | [docs] [24] |
| QRESYNC | `open(int, ResyncData)` returns change events; ResyncData contains previous UIDVALIDITY, MODSEQ, and known UIDs | [docs][code] [23, 26] |
| IDLE | `IMAPFolder.idle()`; use `IdleManager` to observe multiple folders | [docs] [23, 24] |
| Original MIME | `IMAPMessage.getMimeStream()`, `setPeek(true)`; copy the stream to a local `.eml` | [docs][code] [25, 27] |
| Folder roles | `IMAPFolder.getAttributes()` | [docs] [24] |
| Missing command | `IMAPFolder.doCommand(ProtocolCommand)`; requires custom parsing and control of allowed operations | [code] [26] |

[docs] For protocol `imaps`, properties use the **`mail.imaps.*`** prefix; `mail.imap.peek` defaults to false. [assumption] Choose one implementation option, `imap` with TLS or `imaps`, to avoid configuring properties for the wrong provider [23].

[code] In 2.0.5, READ_ONLY mode calls `protocol.examine`, and `getMimeStream()` honors PEEK, including for partial downloads [26, 27].

[assumption] No missing basic functionality for this task was found. A custom `doCommand` handler is justified only for an extension that is actually missing; it cannot solve the server's lack of an extension. For a server peculiarity, use ordinary UID operations; for Gmail, use the separate Gmail API. There is no reason to change libraries in advance.

### В11. Short incremental synchronization algorithm

[docs] Base key: `(connection, folder, UIDVALIDITY, UID)`; message sequence numbers are not persistent. A UID is valid within a generation of a particular folder [28, 30].

The following algorithm is a recommendation [assumption] based on [28–30]:

1. After TLS and sign-in, refresh CAPABILITY, retrieve folders with LIST, and open the selected folder with EXAMINE.
2. Save UIDVALIDITY, UIDNEXT, known UIDs, and flags; if supported, save HIGHESTMODSEQ.
3. First pass: UID SEARCH ALL, then UID FETCH metadata and BODY.PEEK[] in batches; use the Gmail ID for deduplication, see В4.
4. If UIDVALIDITY changed, discard all old UID and MODSEQ mappings for that folder and do a full pass. Old UIDs cannot be matched to new ones by number [30].
5. If QRESYNC is supported, enable it and open the folder with saved UIDVALIDITY, MODSEQ, and UIDs; apply changed FETCH and VANISHED, and retrieve new messages separately [29].
6. If only CONDSTORE is available, retrieve FLAGS/MODSEQ via CHANGEDSINCE; find deleted UIDs by comparing current UID SEARCH ALL with the local set [29, 30].
7. Without these extensions, reconcile UIDs and flags in batches; retrieve MIME only for new UIDs [30].
8. Use IDLE as a signal to begin reconciliation, not as a change log. Without IDLE, poll; after a disconnect, always resume ordinary synchronization [23, 30].

[docs] On NOMODSEQ, discard the saved MODSEQ for that folder [29]. [assumption] Commit progress atomically after the corresponding data is written; use the value from before the pass as the starting point, not a later value whose changes may not have been processed yet. A repeated pass must be safe.

[docs] An IMAP range `n:*`, when n exceeds the last UID, can include the last UID because ranges permit reverse order [28]. [assumption] Filter results by actual UID rather than treating them as unconditionally new; handle empty folders.

### В12. How to guarantee reading without changing messages

[docs] EXAMINE prohibits changes to the selected folder's persistent state, including user state; `SELECT` with a READ-ONLY response has weaker semantics [31]. [docs] `BODY.PEEK[]` does not set `\Seen`, unlike ordinary BODY[] [28].

Recommended restrictions [assumption] based on [23, 26–28, 31]:

- Open only with `Folder.READ_ONLY`; an EXAMINE error must not fall back to SELECT READ_WRITE.
- Set `mail.imaps.peek=true` for `imaps`, and also call `setPeek(true)` for the MIME stream.
- Close the folder with `close(false)`; do not call `expunge()` or `close(true)`.
- Allow only specific read operations; do not expose arbitrary `doCommand` calls.
- Do not call `setFlags`; do not try to “restore unread status” after ordinary FETCH.

| What the server can change | What read-only mode forbids |
|---|---|
| Flags and labels | [docs] STORE / UID STORE, including X-GM-LABELS [10, 28] |
| Messages | [docs] APPEND, COPY, MOVE, EXPUNGE, UID EXPUNGE; CLOSE after a write can delete messages [23, 28, 31] |
| Folders and subscriptions | [docs] CREATE, DELETE, RENAME, SUBSCRIBE, UNSUBSCRIBE [28] |
| Rights and quotas | [docs] SETACL, DELETEACL, SETQUOTA, and corresponding Angus methods [23] |

[assumption] EXAMINE protects the selected folder, but does not prevent the application from creating another folder or issuing APPEND, for example. It supplements, rather than replaces, the operation ban. App passwords and IMAP OAuth scopes also do not provide a separate read-only guarantee [3, 16, 31].

## Comparison tables

### Providers

`?` means no current answer was obtained; “observed” is not a guarantee as of 2026-10-24.

| Provider | Host:port | Sign-in methods | What to enable in account | CONDSTORE | QRESYNC | IDLE | MOVE | Limits | Source |
|---|---|---|---|---|---|---|---|---|---|
| Gmail, personal | `imap.gmail.com:993`, TLS | [docs] App password / OAuth2 XOAUTH2 | [docs] 2-Step Verification for password; own client and consent for OAuth; IMAP permanently enabled | [secondary] Observed; verify | [secondary] Not advertised in source; verify | [docs] Yes | [secondary] Observed | [docs] Up to 15 clients; 2500 MB/day is only a Workspace reference; FETCH size ? | [1–4, 10–13] |
| Mail.ru | `imap.mail.ru:993`, TLS | [docs] External-app password; [code] OAuth in Thunderbird, custom-client availability ? | [docs] Phone, password with Mail access; check external-access toggle | ? | ? | [secondary] Absent in 2017; verify | [secondary] Observed in 2017 | Daily traffic, connections, FETCH size, and request frequency ? | [16–20], see В8–В9 |

### What users should check in their accounts

The user performs all actions; secret values are not sent in chat.

- [ ] Gmail: check 2-Step Verification and whether App passwords is available [1].
- [ ] Gmail: if using an app password, create a separate one for the local server and store it outside the repository. Do not enable the obsolete less secure apps mode [1, 9].
- [ ] Gmail: check visibility of required labels in IMAP and ensure there is no message-count limit during full import [3, 14].
- [ ] Gmail OAuth, if selected: prepare a Desktop client and consent screen, add your account to test users; check publication status and the seven-day Testing limit [4–7].
- [ ] Workspace, if applicable: ask the administrator about IMAP permission and OAuth [7, 8].
- [ ] Mail.ru: check the linked phone and external IMAP access [16, 17].
- [ ] Mail.ru: create a separate external-app password with “Full access to Mail”; do not choose Cloud/Calendar access unnecessarily [16].
- [ ] During a later user-run client check, retain only anonymized post-login CAPABILITY, LIST attributes, and UIDVALIDITY/HIGHESTMODSEQ values; no AUTH lines, tokens, addresses, or full protocol logs in a public report.
- [ ] On a selected unread message, verify flags remain unchanged after MIME retrieval; the future client should use EXAMINE and PEEK, see В12.

## Recommendation

[assumption] For **this personal MVP**, plan Gmail with an app password if available and Mail.ru with an external-app password. This keeps one IMAP source and avoids registering OAuth clients for first launch [1, 16].

[assumption] Distinguish password and XOAUTH2 in the connection model from the outset: Gmail OAuth is a fallback if an app password is unavailable and a separate future option. The trade-off is more complex project setup, consent, token renewal, and publication rules [3–7].

[assumption] Use Angus Mail 2.0.5 behind the internal source interface; base synchronization uses UIDs with a full membership reconciliation, with extensions as accelerations. Do not promise instant notifications for Mail.ru until IDLE is checked. Separate Gmail messages from their folder representations. Enforce read-only mode through an operation ban, EXAMINE, and PEEK [20, 22–32].

## What could not be verified

- Availability of app passwords and settings in specific accounts: unknown without sign-in; the user checks the UI using the list above. No sign-in was attempted during this research.
- CAPABILITY for either account as of 2026-10-24: published observations are older. The user checks after sign-in with the future client; the agent does not make even anonymous IMAP connections.
- Mail.ru: current extensions, folder-name localization and special-use roles, numeric limits. An anonymized LIST/CAPABILITY and, for traffic quotas, a response from Mail support are needed.
- Personal Gmail: exact traffic quota and maximum FETCH size. Do not transfer Workspace figures, APPEND limits, or attachment limits to reading without provider confirmation.
- Mail.ru OAuth: current process for allowing a custom Desktop client, availability of `mail.imap`, token lifetime and renewal. `o2.mail.ru/docs/` could not be read; the user can check the documentation or contact developer support [18].
- Angus on Java 25, real Gmail/Mail.ru accounts, and nonstandard Mail.ru responses: APIs and source were checked, but no execution or build was done. Behavioral verification is a separate engineering task.
- Completeness of Gmail labels via MODSEQ: CONDSTORE guarantees standard state, but changes to X-GM-LABELS need separate verification; until then, periodically reconcile labels [10, 29].
- Google's rules for a future public product using an external model: depends on actual data flow, terms of use, and whether exceptions apply; see В1.

## What else to study

- [assumption] A separate OAuth setup and token-renewal procedure that does not expose secrets in MCP, logs, or stdout; storage outside the repository as provided in the plan.

## Sources

All sources were read on **2026-10-24**. If no publication date is listed below, it was not established on the page consulted; the search engine's crawl date is not a substitute for an update date. The [secondary] labels mark observations without a provider guarantee; primary records by the developers themselves are used, not summaries from third-party articles.

1. Google Account Help, Sign in with app passwords — <https://support.google.com/accounts/answer/185833?hl=en> (read 2026-10-24).
2. Gmail Help, Add Gmail to another email client — <https://support.google.com/mail/answer/7126229?hl=en> (read 2026-10-24; IMAP change from January 2025).
3. Google, OAuth 2.0 mechanism — <https://developers.google.com/workspace/gmail/imap/xoauth2-protocol> (read 2026-10-24).
4. Google, OAuth 2.0 for Desktop Apps — <https://developers.google.com/identity/protocols/oauth2/native-app> (read 2026-10-24).
5. Google, Using OAuth 2.0 to Access Google APIs, Refresh token expiration — <https://developers.google.com/identity/protocols/oauth2/> (read 2026-10-24).
6. Google, Restricted scope verification, Exceptions to verification requirements — <https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification> (read 2026-10-24).
7. Google, OAuth app state overview — <https://developers.google.com/identity/protocols/oauth2/production-readiness/overview> (read 2026-10-24).
8. Google Workspace, Set up Gmail with a third-party email client — <https://knowledge.workspace.google.com/admin/sync/set-up-gmail-with-a-third-party-email-client> (read 2026-10-24; transition date listed as 2025-05-01).
9. Google Workspace, Transition from less secure apps to OAuth — <https://knowledge.workspace.google.com/admin/sync/transition-from-less-secure-apps-to-oauth> (read 2026-10-24; date listed as 2025-03-14 and app-password exception).
10. Google, IMAP Extensions — <https://developers.google.com/workspace/gmail/imap/imap-extensions> (read 2026-10-24).
11. emersion, Common IMAP servers CAPABILITY responses, Gmail section — <https://gist.github.com/emersion/2c769bc1ed60a7b7945910d35b606801> (read 2026-10-24; comment addition dated 2024-08-14).
12. Google Workspace, Gmail bandwidth limits — <https://knowledge.workspace.google.com/admin/gmail/gmail-bandwidth-limits?hl=en> (read 2026-10-24).
13. Google, IMAP, POP, and SMTP — <https://developers.google.com/workspace/gmail/imap/imap-smtp> (read 2026-10-24; updated 2026-09-15).
14. Google, ImapSettings — <https://developers.google.com/workspace/gmail/api/reference/rest/v1/ImapSettings> (read 2026-10-24).
15. Google, Synchronize clients with Gmail — <https://developers.google.com/workspace/gmail/api/guides/sync> (read 2026-10-24).
16. Mail, How to sign in to Mail in another email program — <https://help.mail.ru/mail/login/mailer/> (read 2026-10-24; updated 2026-02-25).
17. Mail, Security settings in Mail — <https://help.mail.ru/mail/security/protection/settings/> (read 2026-10-24).
18. Mail, OAuth authorization for developers — <https://help.mail.ru/developers/oauth/> (read 2026-10-24; related `o2.mail.ru/docs/` unavailable during research).
19. Thunderbird, `mailnews/base/src/OAuth2Providers.sys.mjs` — <https://raw.githubusercontent.com/mozilla/releases-comm-central/master/mailnews/base/src/OAuth2Providers.sys.mjs> (read 2026-10-24; master branch, `imap.mail.ru` and `o2.mail.ru` entries).
20. Kostya Vasilyev, Aqua Mail developer, own Mail.ru CAPABILITY response — <https://forum.aqua-mail.com/index.php?topic=2084.0> (read 2026-10-24; post No. 4 from 2017-04-28).
21. MailKit, `MailKit/Net/Imap/ImapEngine.cs`, `ParseNumber` — <https://github.com/jstedfast/MailKit/blob/master/MailKit/Net/Imap/ImapEngine.cs> (read 2026-10-24; master branch, comment about Mail.ru responses).
22. Eclipse Angus Mail, release 2.0.5 — <https://github.com/eclipse-ee4j/angus-mail/releases/tag/2.0.5> (read 2026-10-24; published 2026-02-27); current releases — <https://github.com/eclipse-ee4j/angus-mail/releases>.
23. Eclipse Angus Mail, package `org.eclipse.angus.mail.imap`, provider properties — <https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/imap/package-summary.html> (read 2026-10-24; living documentation).
24. Eclipse Angus Mail, `IMAPFolder` Javadoc — <https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/imap/IMAPFolder.html> (read 2026-10-24).
25. Eclipse Angus Mail, `IMAPMessage` Javadoc — <https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/imap/IMAPMessage.html> (read 2026-10-24).
26. Eclipse Angus Mail 2.0.5, `providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPFolder.java` — <https://raw.githubusercontent.com/eclipse-ee4j/angus-mail/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPFolder.java> (read 2026-10-24; tag 2.0.5).
27. Eclipse Angus Mail 2.0.5, `providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPMessage.java` — <https://raw.githubusercontent.com/eclipse-ee4j/angus-mail/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/IMAPMessage.java> (read 2026-10-24; `getMimeStream`, `setPeek`).
28. RFC 3501, IMAP4rev1 — <https://datatracker.ietf.org/doc/html/rfc3501> (read 2026-10-24; published 2003-03; UID — § 2.3.1.1, FETCH/PEEK — § 6.4.5, ranges — § 9).
29. RFC 7162, CONDSTORE/QRESYNC — <https://datatracker.ietf.org/doc/html/rfc7162> (read 2026-10-24; published 2014-05; NOMODSEQ — § 3.1.2.2, VANISHED — § 3.2.10, synchronization — § 6).
30. RFC 4549, Synchronization Operations for Disconnected IMAP4 Clients — <https://datatracker.ietf.org/doc/html/rfc4549> (read 2026-10-24; published 2006-06; UIDVALIDITY — § 4.1, fetching changes — § 4.3).
31. RFC 9051, IMAP4rev2 — <https://datatracker.ietf.org/doc/html/rfc9051> (read 2026-10-24; published 2021-08; SELECT/EXAMINE — § 6.3.2–6.3.3, CLOSE — § 6.4.1).
32. RFC 6154, IMAP LIST Extension for Special-Use Mailboxes — <https://www.rfc-editor.org/rfc/rfc6154.html> (read 2026-10-24; published 2011-03).
33. Google, Choose Gmail API scopes — <https://developers.google.com/workspace/gmail/api/auth/scopes> (read 2026-10-24).
34. Google, Manage labels — <https://developers.google.com/workspace/gmail/api/guides/labels> (read 2026-10-24).

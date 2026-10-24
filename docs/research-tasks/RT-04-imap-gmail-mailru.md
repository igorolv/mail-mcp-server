# RT-04 — IMAP access to Gmail and Mail.ru

- **Result file:** `docs/research/RT-04-imap-gmail-mailru.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 1.1; `docs/plan.md`, open questions 13 and 14.

## Goal

Find out how a local Java application can read Gmail and Mail.ru mailboxes over IMAP in 2026:
how to sign in, which IMAP extensions are available, what limits apply, and how Gmail labels map
to folders.

## What we already know

- The user has a personal Gmail and a personal Mail.ru mailbox (`docs/plan.md`, section 3).
- We plan one IMAP implementation for both, configured per connection (`docs/plan.md`, 5.7).
- Notes from memory, **not verified**: Gmail allows IMAP with an app password (requires 2-Step
  Verification) or OAuth2 (`XOAUTH2`); Mail.ru requires a "пароль для внешнего приложения";
  Gmail has `X-GM-MSGID`, `X-GM-THRID`, `X-GM-LABELS` (`docs/research.md`, section 1.1).
- Java library candidate: Jakarta Mail / Eclipse Angus Mail.

## Questions

### Gmail

- **В1. Sign-in options today:** app passwords (who can create them, are they being restricted),
  OAuth2 for IMAP (`https://mail.google.com/` scope). For a personal, single-user app: what does
  Google require (OAuth consent screen, "testing" mode, refresh-token lifetime in testing mode,
  verification of restricted scopes)? Differences for Google Workspace accounts.
- **В2.** Is IMAP access still a setting the user must enable, or always on?
- **В3. Gmail IMAP extensions:** `X-GM-EXT-1` (`X-GM-MSGID`, `X-GM-THRID`, `X-GM-LABELS`,
  `X-GM-RAW` search), support for `CONDSTORE`, `QRESYNC`, `IDLE`, `MOVE`, `UIDPLUS`,
  `SPECIAL-USE`.
- **В4. Labels vs folders:** how labels appear as IMAP folders, "[Gmail]/All Mail", duplicates
  across folders, how to deduplicate and which folders to sync.
- **В5. Limits:** IMAP bandwidth per day, simultaneous connections, message size, any throttling
  behaviour.
- **В6. Gmail API as an alternative:** in 3–5 lines, what it offers over IMAP (history-based sync,
  threads, labels) and what it costs (Google Cloud project, OAuth client). No deep dive.

### Mail.ru

- **В7. Sign-in:** IMAP host and port, app password ("пароль для внешнего приложения") — how to
  create it, any OAuth option for third-party apps.
- **В8. Extensions:** which of `CONDSTORE`, `QRESYNC`, `IDLE`, `MOVE`, `UIDPLUS`, `SPECIAL-USE`
  Mail.ru supports (from documentation or reliable reports; mark the certainty).
- **В9.** Folder names (localized? special-use flags?), limits, known quirks.

### Java and sync

- **В10. Angus Mail:** current version; support for `XOAUTH2`
  (`mail.imap.auth.mechanisms`), `CONDSTORE`/`QRESYNC` (`IMAPFolder` methods such as
  `getMessagesByUIDChangedSince`), `IDLE`, fetching raw MIME (`BODY.PEEK[]`) without setting
  `\Seen`. Alternatives if Angus Mail lacks something.
- **В11. Incremental sync algorithm** for IMAP in short: `UIDVALIDITY` + `UID`, `MODSEQ` with
  `CONDSTORE`/`QRESYNC` (RFC 7162), what to do when `UIDVALIDITY` changes. Reference RFC 4549
  if useful.
- **В12. Read-only safety:** how to make sure our client never sets `\Seen` or other flags
  (`EXAMINE` instead of `SELECT`, `BODY.PEEK`), and what else in IMAP can modify the mailbox.

## Where to look

- Google: Gmail IMAP developer guide (`developers.google.com/gmail/imap`), Gmail IMAP extensions,
  OAuth2 for IMAP/SMTP, app passwords help pages, Google account security announcements
  (2024–2026).
- Mail.ru help (`help.mail.ru`) pages about IMAP and external-app passwords.
- Eclipse Angus Mail docs and Javadoc (`com.sun.mail.imap` / `org.eclipse.angus.mail.imap`).
- RFC 3501 / RFC 9051 (IMAP), RFC 7162 (CONDSTORE/QRESYNC), RFC 6154 (SPECIAL-USE).

## Required tables

| Провайдер | Хост:порт | Способы входа | Что нужно включить в аккаунте | CONDSTORE | QRESYNC | IDLE | MOVE | Лимиты | Источник |
|---|---|---|---|---|---|---|---|---|---|

Plus a checklist "Что пользователю проверить в своих аккаунтах" (settings to look at, what to
enable, how to create an app password) — without asking for any secret.

## Out of scope

- Connecting to Gmail or Mail.ru, even anonymously; creating OAuth clients or app passwords.
- SMTP and sending (later feature).
- MIME parsing (RT-09).

## Done when

- В1–В12 answered; the provider table is filled for Gmail and Mail.ru.
- The recommendation says which sign-in method to plan for each provider and why.

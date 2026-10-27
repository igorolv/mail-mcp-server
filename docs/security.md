# Security

What the server does and does not do with your mailboxes, your credentials and your mail.

- [Read-only protection](#read-only-protection)
- [Sign-in policy](#sign-in-policy)
- [Credentials](#credentials)
- [Local data](#local-data)
- [What the model sees](#what-the-model-sees)
- [Secret filter](#secret-filter)

## Read-only protection

The server never sends, moves, deletes or flags mail and never changes mailbox settings. This is
enforced at the protocol level, not only by the absence of such tools:

- **EWS:** the client can build requests only for an allow-list of ten read operations. Messages
  are downloaded with `GetItem`, which does not mark them as read.
- **IMAP:** a command guard lets through `EXAMINE`, `BODY.PEEK` fetches and `CLOSE`, and rejects
  `SELECT`, `STORE`, `EXPUNGE`, `APPEND`, `COPY`, `MOVE` and every other change before it is sent.
  Folders are opened read-only, and the `\Seen` flag does not change when a message is downloaded.
- **Tools:** all tools except `sync` read local data only. `sync` downloads; it does not write to
  the mailbox.

The Gmail OAuth2 scope `https://mail.google.com/` permits more than reading; the IMAP guard above
still applies.

## Sign-in policy

Stale passwords lock domain accounts, so the server is careful with sign-ins:

- **One sign-in per synchronization, never retried.** No tool signs in except `sync`.
- **Synchronization runs only on request:** the `sync` tool or the `mail-sync` command. There is
  no schedule.
- **After a rejected sign-in** the `sync` tool refuses that connection in every server process.
  Fix the password or login in [`connections.json`](connections.md), then run
  [`mail-sync`](commands.md#mail-sync) in a terminal: a successful run clears the state. An agent
  therefore cannot lock your account by calling `sync` repeatedly.
- A password that could not be obtained from Windows Credential Manager or a password command is
  not a sign-in attempt: nothing is sent.

## Credentials

- Credentials live only in `connections.json`, in environment variables it names, in Windows
  Credential Manager or in a password manager ([Passwords](connections.md#passwords)) — never in
  the MCP client configuration.
- Passwords are never logged, never printed by commands and never returned by a tool.
  `listConnections` returns names, types, sign-in methods and descriptions, not hosts or logins.
- Passwords from Credential Manager or a password command stay in the server's memory until it
  exits, so frequent syncs do not unlock the store each time.
- Keep `connections.json`, the Gmail OAuth client file and the data directory readable only by you.
  The Gmail refresh token file is created that way.
- None of this protects against another program running as your user: it can read the same
  files, credentials and password manager.

## Local data

Everything is stored locally and **unfiltered** under the data directory
(`%USERPROFILE%\.mail-mcp-server\<connection>\` by default):

- every message as downloaded (`message.eml`), its metadata, body text and attachments;
- the Markdown text of bodies and attachments;
- the search index;
- logs in `<data-dir>\logs\`; passwords are never logged.

Expect roughly the size of the mailbox plus its text. Messages deleted on the server are deleted
locally at the next synchronization. To remove a mailbox's local copy, stop the server and delete
its directory.

Attachments are converted by pandoc with `--sandbox`, one process per file, and by PDFBox and
Apache POI inside the server. Macros and formulas are not executed, and external links are not
opened.

## What the model sees

The point of the server is to let a model read your mail. Tool results contain message headers,
bodies, attachment text and absolute paths to the local files. An agent with file access can open
those files directly. Treat a mailbox connected to an agent like a mailbox opened in front of
that agent's provider, and mind instructions hidden in mail (prompt injection): the server cannot
send mail, but an agent with other tools could act on what it reads.

## Secret filter

Mail often contains passwords, one-time codes and tokens. Set `MAIL_MCP_REDACT_SECRETS=true` in
the server's environment (see [Clients](clients.md#environment-variables)) and restart it to
replace recognized secrets in tool responses with markers such as `[redacted:password]`,
`[redacted:code]`, `[redacted:token]` or `[redacted:private-key]`. The default is off.

Recognized:

- labeled passwords in Russian and English, Wi-Fi keys, password fields in connection strings;
- labeled one-time and verification codes;
- passwords inside URIs (`user:password@host`);
- HTTP Basic and Bearer authorization headers;
- JWT/JWE tokens, private PEM keys, GitHub tokens;
- labeled API keys, access and refresh tokens, client secrets.

Limits:

- It reduces accidental exposure of secrets in the model's context. It does not stop an agent
  from reading the unfiltered files through `localPath`, and it is not meant to.
- Downloads, local files and the index keep the original content. Search still matches the
  original text, but snippets show filtered text.
- Detection is best effort: it can miss secrets (unlabeled short passwords, unusual formats,
  images) and can hide ordinary text next to a label.
- Personal data (names, phone numbers, addresses) is not anonymized.
- Commands such as `mail-sync` do not filter their output; they print no message content anyway.

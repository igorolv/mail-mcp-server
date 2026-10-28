# Connections

Every mailbox the server reads is an entry in one `connections.json` file outside the project.
This page describes the file, every field, the ways to supply a password and the setup of each
mail provider. The [README Quickstart](../README.md#quickstart) has the short version.

- [The file](#the-file)
- [Connection fields](#connection-fields)
- [Passwords](#passwords)
  - [Environment variables](#environment-variables)
  - [Windows Credential Manager](#windows-credential-manager)
  - [Password commands](#password-commands)
- [Exchange (EWS)](#exchange-ews)
- [Mail.ru](#mailru)
- [Gmail](#gmail)
  - [App passwords](#app-passwords)
  - [OAuth2](#oauth2)
- [Configuration errors](#configuration-errors)
- [Checking a configuration](#checking-a-configuration)

## The file

The default location is `%USERPROFILE%\.mail-mcp-server\connections.json` on Windows and
`~/.mail-mcp-server/connections.json` elsewhere. `MAIL_MCP_CONNECTIONS_FILE` points to another
file; `MAIL_MCP_DATA_DIR` moves the whole data directory, including the default file location.

The file holds one object, `connections`, keyed by the connection name:

```json
{
  "connections": {
    "work": {
      "type": "ews",
      "url": "https://webmail.example.com/EWS/Exchange.asmx",
      "auth": "ntlm",
      "username": "user",
      "password": "${MAIL_WORK_PASSWORD}",
      "description": "Work mail, Exchange 2013"
    },
    "mailru": {
      "type": "imap",
      "host": "imap.mail.ru",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@mail.ru",
      "passwordCommand": ["op", "read", "op://Private/Mailru/password"],
      "description": "Personal Mail.ru"
    },
    "gmail": {
      "type": "imap",
      "host": "imap.gmail.com",
      "port": 993,
      "tls": true,
      "auth": "app-password",
      "username": "user@gmail.com",
      "windowsCredential": "mail-mcp/gmail",
      "description": "Personal Gmail"
    }
  }
}
```

Replace the example usernames with your own and keep only the entries you need. The examples
demonstrate an [environment variable](#environment-variables), a [password command](#password-commands)
and [Windows Credential Manager](#windows-credential-manager). These are interchangeable password
sources for EWS and app-password IMAP; they are not tied to the provider. Use exactly one per
entry. Windows Credential Manager requires 64-bit Windows; the other sources work on other
platforms too.

- The server reads the file once at startup. After editing it, restart the server in your MCP
  client; the user-run [commands](commands.md) read it on every run.
- Every tool takes the connection name as its `connection` argument. There is no default
  connection and no call that spans mailboxes.
- `description` is returned by `listConnections`, so an agent can pick a mailbox by meaning.
- The name is also the name of the connection's data directory: `<data-dir>/<name>/`. Renaming a
  connection therefore starts a new local copy; rename the directory too to keep the old one.
- Connection names match `[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?`, have at most 64 characters and
  cannot be `.` or `..`.

Keep the file readable only by its owner. In Windows PowerShell:

```powershell
icacls "$HOME\.mail-mcp-server\connections.json" /inheritance:r /grant:r "${env:USERNAME}:F"
```

This protects against accidents, not against another program running as your user. Passwords
are never logged and never returned by a tool. More in [Security](security.md).

## Connection fields

| Key | For | Meaning |
|---|---|---|
| `type` | all | `ews` or `imap`; required |
| `description` | all | What the mailbox is for; shown by `listConnections` |
| `auth` | all | EWS: `ntlm` (default) or `basic`. IMAP: `app-password` (default) or, for Gmail only, `oauth2` |
| `username` | all | Required. EWS: the login as the server expects it (see [Exchange](#exchange-ews)); IMAP: the full mailbox address |
| `password` | all except OAuth2 | The password, or `${VAR}` to take it from an environment variable |
| `windowsCredential` | instead of `password` | Target name of a generic credential in Windows Credential Manager, e.g. `mail-mcp/work`; 64-bit Windows only. See [Windows Credential Manager](#windows-credential-manager) |
| `passwordCommand` | instead of `password` | A JSON array, a program and its arguments, that prints the password. See [Password commands](#password-commands) |
| `url` | ews | Absolute `https://` EWS endpoint, usually `https://<owa-host>/EWS/Exchange.asmx`; plain `http` is refused |
| `domain` | ews | NTLM domain. Leave it out for a bare login |
| `connectTimeoutSeconds`, `readTimeoutSeconds` | ews | Defaults 15 and 120 |
| `host` | imap | IMAP server, e.g. `imap.mail.ru`, `imap.gmail.com` |
| `port` | imap | Default 993 |
| `tls` | imap | Must be `true` (implicit TLS); `false` is refused |
| `oauthClientFile` | imap with `oauth2` | Path to the downloaded Google Desktop OAuth client JSON; see [Gmail](#gmail) |

- Any string value may be `${VAR}`; so may every element of `passwordCommand` and the
  `oauthClientFile` path, which also accepts a leading `~`. An unset variable stops startup rather
  than becoming an empty value.
- Exactly one of `password`, `windowsCredential` and `passwordCommand` is required, except with
  `auth: oauth2`, which uses none of them.
- Unknown keys and keys that belong to the other type are logged as warnings and ignored.

## Passwords

A password can come from four places. Pick the one that suits you; the server treats them the
same way.

| Source | Example | Where the secret lives |
|---|---|---|
| Literal | `"password": "..."` | In `connections.json` itself |
| Environment variable | `"password": "${MAIL_WORK_PASSWORD}"` | In the environment of the process that starts the server |
| Windows Credential Manager | `"windowsCredential": "mail-mcp/work"` | In the current user's Credential Manager |
| Password command | `"passwordCommand": ["op", "read", "op://Private/Work mail/password"]` | In a password manager |

`windowsCredential` and `passwordCommand` are read right before the first sign-in of a
synchronization — never at startup and never for reading tools, which use local data only. The
value then stays in the server's memory until it exits, so frequent syncs do not unlock the
password manager each time. A rejected sign-in drops it. After you change the password in the
store, restart the MCP server.

If the password cannot be obtained (missing credential, failed command), nothing is sent to the
mail server: this is not a rejected sign-in, and the next `sync` may start. `mail-sync` reports it
and exits with code 2.

Credential Manager and password commands keep the password out of configuration files and
environment variables that agents read routinely. They do not stop a program running as your
user from reading the credential or running the command itself.

### Environment variables

With `"password": "${MAIL_WORK_PASSWORD}"`, the server reads `MAIL_WORK_PASSWORD` from its own
environment at startup. The variable name is your choice; it must match the placeholder.
For an interactive Windows PowerShell session:

```powershell
$workPassword = Read-Host 'Exchange password' -AsSecureString
$env:MAIL_WORK_PASSWORD = [System.Net.NetworkCredential]::new('', $workPassword).Password
```

This asks for the password without echoing it or putting it in command history. The environment
variable holds the resolved password for this process and child processes. Start `mail-sync` and
the MCP client from this terminal so they inherit it. It does not update an already running
client or a client launched from the desktop. For those, supply the variable in their launch
environment or choose Credential Manager or a password command instead. An unset placeholder
stops startup even if you planned to synchronize another connection.

After changing the variable, restart the MCP server with the new environment. See
[client environment variables](clients.md#environment-variables) for how clients start the server.

### Windows Credential Manager

```json
"work": {
  "type": "ews",
  "url": "https://webmail.example.com/EWS/Exchange.asmx",
  "username": "user",
  "windowsCredential": "mail-mcp/work"
}
```

The JSON stores a credential's name; you enter its password separately in Windows. Create one
generic credential per mailbox, under the **same Windows user that runs the MCP client and
`mail-sync`**:

1. Open Start, search for **Credential Manager** and open the Control Panel result. You can also
   open **Control Panel → User Accounts → Credential Manager**.
2. Select **Windows Credentials**, then **Add a generic credential**. Use the generic category,
   rather than Web Credentials or Add a Windows credential.
3. Fill in the dialog:

   | Field | What to enter |
   |---|---|
   | Internet or network address | Exactly the name in `windowsCredential`, such as `mail-mcp/work`. It is a lookup name you choose, not the mail server address. |
   | User name | A plain label such as `work`, `mailru` or `gmail`. The server ignores this field and uses `username` from `connections.json`. A name containing `@` was rejected by the tested Windows dialog (`0x80070057`). |
   | Password | The Exchange mailbox password, or the app password created for Mail.ru or Gmail. |

4. Save with **OK**. Repeat for the other connections:

   | Connection | Credential name in JSON and Windows | Password to store |
   |---|---|---|
   | `work` | `mail-mcp/work` | Exchange mailbox password |
   | `mailru` | `mail-mcp/mailru` | Mail.ru app password |
   | `gmail` | `mail-mcp/gmail` | [Google app password](#app-passwords) |

5. In each connection, set `windowsCredential` to the matching name. Remove `password` and
   `passwordCommand` from that entry: use exactly one password source.
6. Restart the MCP server after editing `connections.json`. Run
   [`mail-sync <connection>`](commands.md#mail-sync) yourself in a terminal to check the setup.

To replace a password, expand its entry under **Generic Credentials**, choose **Edit**, enter the
new password and save. Restart every MCP server process using that credential to clear its cached
password, then run `mail-sync <connection>` yourself. If a sign-in was already rejected, a
successful terminal run is also needed to clear the sync refusal. Removing a Windows entry only
removes the local credential; revoke an app password separately in the provider's settings.

If the server reports that the password is unavailable, check the Windows user, the generic
credential category and the exact name. No sign-in was attempted in that case. Never put a
password on a command line or send it in chat.

The server reads the credential itself through the Windows API, without PowerShell or another
program. It works only on 64-bit Windows; elsewhere the key makes the entry unusable, so use
`passwordCommand` there.

Windows navigation reference: [Microsoft: Credential Manager in Windows](https://support.microsoft.com/en-us/windows/security/credential-manager-in-windows).

### Password commands

`passwordCommand` runs a program and takes what it prints as the password:

```json
"work": {
  "type": "ews",
  "url": "https://webmail.example.com/EWS/Exchange.asmx",
  "username": "user",
  "passwordCommand": ["op", "read", "op://Private/Work mail/password"]
}
```

- **No shell.** The array is the program and its arguments, passed as is. On Windows a `.exe` on
  `PATH` is found by name; `.cmd` and `.bat` wrappers need `["cmd", "/c", "name", ...]`. The
  program inherits the server's environment and working directory.
- **Output:** UTF-8, exactly one non-empty line. One trailing line break and a leading byte order
  mark are removed; other whitespace is kept. Several lines, empty output, invalid UTF-8 or more
  than 64 KiB is a failure.
- **In the MCP server** the program gets no input and 60 seconds; its error output goes to the
  server log only. It cannot ask anything in a terminal: unlock the password manager beforehand,
  or use one that asks in its own window.
- **In `mail-sync`** the program uses your terminal and gets 5 minutes, so a master-password
  prompt works there.

Recipes (check each tool's own documentation; each must print only the password):

| Store | `passwordCommand` |
|---|---|
| 1Password CLI | `["op", "read", "op://<vault>/<item>/password"]` |
| PowerShell SecretManagement | `["powershell", "-NoProfile", "-NonInteractive", "-Command", "$b = [Text.UTF8Encoding]::new($false).GetBytes((Get-Secret -Name mail-work -AsPlainText)); [Console]::OpenStandardOutput().Write($b, 0, $b.Length)"]`; a SecretStore vault must be unlocked or configured without a prompt |
| macOS Keychain | `["security", "find-generic-password", "-s", "mail-mcp", "-a", "work", "-w"]` |
| Linux Secret Service | `["secret-tool", "lookup", "service", "mail-mcp", "account", "work"]` |
| pass / gopass | `["pass", "show", "mail/work"]`; the first line must be the only line |

Windows PowerShell 5.1 does not write UTF-8 to a pipe by default, which is why the
SecretManagement recipe writes bytes. Managers that need a master password on standard input for
every call (`keepassxc-cli`, Bitwarden CLI without a session) work only in `mail-sync`.

## Exchange (EWS)

```json
"work": {
  "type": "ews",
  "url": "https://webmail.example.com/EWS/Exchange.asmx",
  "auth": "ntlm",
  "username": "user",
  "windowsCredential": "mail-mcp/work",
  "description": "Work mail, Exchange 2013"
}
```

- Tested with Exchange 2013. The EWS endpoint is usually the Outlook on the web host followed by
  `/EWS/Exchange.asmx`.
- **NTLM with a bare login** (no domain part) is the combination known to work: the server fills
  in the domain. Basic with a bare login was rejected by the tested server. Set `domain` only if
  your server requires it.
- Every failed sign-in counts toward the domain's lockout policy. The server signs in once per
  run and never retries; see [Security](security.md#sign-in-policy).

## Mail.ru

```json
"mailru": {
  "type": "imap",
  "host": "imap.mail.ru",
  "port": 993,
  "tls": true,
  "auth": "app-password",
  "username": "user@mail.ru",
  "windowsCredential": "mail-mcp/mailru",
  "description": "Personal Mail.ru"
}
```

Mail.ru requires an app password (a password for external applications) for IMAP; create one in
the mailbox's security settings and use it instead of the account password.

## Gmail

Gmail app-password sign-in and downloads are verified; **full synchronization and OAuth2
verification are pending**. Both authentication methods use
`imap.gmail.com:993` with TLS. For personal Gmail, IMAP is always enabled; there is no Enable IMAP
switch to turn on ([Google: Add Gmail to another email client](https://support.google.com/mail/answer/7126229?hl=en)).

### App passwords

This is the simpler setup for a personal mailbox. It requires **2-Step Verification**; use a
separate app password for this server instead of your normal Google account password.

1. In Gmail, click your profile picture at the top right → **Manage your Google Account**.
2. Open **Security & sign-in** (called **Security** in some layouts) → **2-Step Verification**.
3. Open **App passwords** within that page; Google may ask you to sign in again. If you cannot
   find it, use the account settings search for `App passwords`, or open
   [App passwords directly](https://myaccount.google.com/apppasswords).
4. Enter the app name `mail-mcp-server` and create the password. Google shows it only once.
5. Save the generated 16-character password, without the visual grouping spaces, in a generic
   Windows credential named `mail-mcp/gmail`; follow [Windows Credential Manager](#windows-credential-manager).
   Enter it only in the local password dialog, never in chat or a command line. On other platforms
   use a [password source](#passwords) available there.
6. Add the following entry inside the `connections` object in `connections.json`. Replace the
   example address with yours; put a comma between it and the preceding connection:

   ```json
   "gmail": {
     "type": "imap",
     "host": "imap.gmail.com",
     "port": 993,
     "tls": true,
     "auth": "app-password",
     "username": "user@gmail.com",
     "windowsCredential": "mail-mcp/gmail",
     "description": "Personal Gmail"
   }
   ```

7. Run the first synchronization yourself:

   ```powershell
   java -jar mail-mcp-server.jar mail-sync gmail
   ```

   After a local build, the jar is `build\libs\mail-mcp-server.jar`. Restart the MCP server to load
   the new connection. App-password setup does not need a Google Cloud project or `gmail-oauth`.

If **App passwords** is unavailable, check that 2-Step Verification is enabled. Google also lists
restrictions for security-key-only verification, organization accounts and Advanced Protection.
Use OAuth2 below if your account permits it. Changing your Google account password revokes app
passwords: create a replacement, update Credential Manager and restart the MCP server.
See [Google: Sign in with app passwords](https://support.google.com/accounts/answer/185833?hl=en).

Gmail labels currently appear as IMAP folders, so the same message may be downloaded in several
folders. Search and conversations [group matching copies](search.md#copies-of-one-message). Gmail-specific
All Mail storage with labels and deduplication by `X-GM-MSGID` is still pending (`Д7` in
[the version plan](plan.md#4-version-010)).

### OAuth2

Use OAuth2 when app passwords are unavailable, for example when a Workspace administrator
disallows them. It needs your own Google Cloud project:

1. Open [Google Cloud Console](https://console.cloud.google.com/) and choose or create a project.
   The Cloud Console requires 2-Step Verification for the account that manages the project; that
   can be a different account of yours.
2. Configure the OAuth consent screen (Google Auth Platform): app name `mail-mcp-server`, a
   contact email, **External** audience for personal Gmail, **Testing** status, and your Gmail
   address as a test user.
3. In Data Access add the scope `https://mail.google.com/`. It is the scope IMAP requires;
   `gmail.readonly` belongs to the Gmail REST API and does not authorize IMAP.
4. In Clients create an OAuth client of type **Desktop app**. Download its JSON and save it as
   `%USERPROFILE%\.mail-mcp-server\google-oauth-client.json`, readable only by you (`icacls` as
   above).
5. Change the Gmail entry:

   ```json
   "gmail": {
     "type": "imap",
     "host": "imap.gmail.com",
     "port": 993,
     "tls": true,
     "auth": "oauth2",
     "username": "user@gmail.com",
     "oauthClientFile": "${USERPROFILE}/.mail-mcp-server/google-oauth-client.json",
     "description": "Personal Gmail"
   }
   ```

6. Run [`gmail-oauth gmail`](commands.md#gmail-oauth). It opens the browser; choose the same
   mailbox as `username` and approve the request. Then run `mail-sync gmail`.

Notes:

- OAuth2 is accepted only for `imap.gmail.com:993` with TLS. `password` is not used; remove it.
- The refresh token is saved in `<data-dir>/<connection>/oauth/google-tokens.json`, readable only
  by you; access tokens stay in memory.
- While the app is in Testing, Google usually expires the refresh token after seven days. A sync
  then fails, and you run `gmail-oauth` again; the server never opens a browser on its own.
- The `https://mail.google.com/` scope allows more than reading. The server still allows only
  read commands at the IMAP protocol level ([Security](security.md#read-only-protection)).

## Configuration errors

What stops the server at startup (it reports the reason on stderr and in its log):

- invalid JSON, an entry that is not an object, or a value of the wrong JSON type (including a
  `passwordCommand` that is not an array of strings);
- an invalid connection name;
- an unset `${VAR}`.

What makes only one entry unusable — it appears with `configError` in `listConnections`, and the
other connections keep working:

- a missing or unknown `type` or `auth`;
- a missing `url`, `host` or `username`;
- not exactly one of `password`, `windowsCredential` and `passwordCommand`;
- an empty `passwordCommand` or an empty element in it;
- `windowsCredential` on anything but 64-bit Windows;
- `http://` EWS URL, `tls: false`, out-of-range values.

A missing file starts the server with no connections.

## Checking a configuration

1. Ask the agent which mailboxes it can read: it calls `listConnections`, which lists every entry
   with its type, sign-in method, description and any `configError`, without contacting a server.
2. Run the first synchronization in a terminal, where you see its output:

   ```powershell
   java -jar mail-mcp-server.jar mail-sync work
   ```

   See [`mail-sync`](commands.md#mail-sync) for the output and exit codes.
3. If the MCP client only says the server failed to start, run `java -jar mail-mcp-server.jar` in
   a terminal: a malformed file or an unset `${VAR}` is reported there. Stop it with `Ctrl+C`.

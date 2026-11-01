# Troubleshooting

Common problems and what to check first. Setup is in the [README Quickstart](../README.md#quickstart);
command output and exit codes are in [commands.md](commands.md).

- [The MCP client says the server failed to start](#the-mcp-client-says-the-server-failed-to-start)
- [A connection shows `configError`](#a-connection-shows-configerror)
- [Sign-in rejected, exit code 3](#sign-in-rejected-exit-code-3)
- [EWS connection failed, exit code 4](#ews-connection-failed-exit-code-4)
- [Password unavailable](#password-unavailable)
- [Gmail does not show App passwords](#gmail-does-not-show-app-passwords)
- [pandoc not found](#pandoc-not-found)
- [Connection is being synchronized by another process, exit code 5](#connection-is-being-synchronized-by-another-process-exit-code-5)
- [Search asks for a reindex](#search-asks-for-a-reindex)
- [Search misses a message](#search-misses-a-message)
- [The build cannot overwrite the jar](#the-build-cannot-overwrite-the-jar)

## The MCP client says the server failed to start

Run `java -jar <path>\mail-mcp-server.jar` in a terminal: a malformed `connections.json` or an
unset `${VAR}` is reported there. Check that `java` on the client's `PATH` is version 25. See
[Checking a configuration](connections.md#checking-a-configuration).

## A connection shows `configError`

`listConnections` reports the entry as unusable; the others keep working.
[Configuration errors](connections.md#configuration-errors) lists the causes. The file is read
only at startup: restart the server after editing it.

## Sign-in rejected, exit code 3

`RESULT: FAILED (... sign-in rejected ...)`. Do not run it again blindly: every attempt with a
wrong password counts toward the account lockout policy. Check the login and password first. For
Exchange, a bare login with `auth: ntlm` is the combination known to work.

## EWS connection failed, exit code 4

`RESULT: FAILED (EWS connection failed: ...)`. The Exchange server was not reached, so no sign-in
was rejected. `server name not found` usually means a wrong or placeholder host in `url`, or a
name known only inside the company network: connect to it or its VPN. Other reasons:
[`mail-sync`](commands.md#mail-sync).

## Password unavailable

`Password of connection '...' is unavailable: ...; no sign-in was attempted`. Nothing was sent to
the mail server.

- `windowsCredential`: check that a *generic* credential with exactly that target name exists
  under the Windows user running the server (`cmdkey /list:<name>` prints `* NONE *` if not); see
  [Windows Credential Manager](connections.md#windows-credential-manager).
- `passwordCommand`: run the program in a terminal and check that it prints only the password;
  unlock the password manager, or run `mail-sync` in a terminal where it can ask.
- `${VAR}`: the variable must be set in the environment of the process that starts the server; see
  [Environment variables](connections.md#environment-variables).

## Gmail does not show App passwords

Open **2-Step Verification** first, then **App passwords**, or use the direct link in
[Gmail app passwords](connections.md#app-passwords). That section also explains account
restrictions and the OAuth2 alternative.

## pandoc not found

`pandoc not found; install pandoc and put it on PATH`. Run `pandoc --version` in a new terminal.
If it is not found, install pandoc and add the directory containing its executable to `PATH`. A
successful terminal check does not update an already running MCP client's environment: fully
close and reopen the client so that the server inherits the new `PATH`.

## Connection is being synchronized by another process, exit code 5

A sync of the same mailbox or an offline data command holds the local write lock. Wait for that
operation to finish. `syncStatus` reports sync runs only, not progress of offline commands.

## Search asks for a reindex

If search says the index was built by an older version, or that the word forms changed, run
`mail-reindex <connection>` (offline) or start a sync.

## Search misses a message

Check `listFolders` (`synced`) and `syncStatus`: results are incomplete during a synchronization.
Irregular Russian forms and synonyms do not match without a
[word forms file](search.md#word-forms); try another form or the other language.

## The build cannot overwrite the jar

A running MCP client keeps `build\libs\mail-mcp-server.jar` open on Windows. Stop the server in
the client, build, then reconnect.

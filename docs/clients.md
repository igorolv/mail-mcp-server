# Connecting an AI Client

How to register the server with an MCP client, set its environment variables and run it by hand.
The [README Quickstart](../README.md#quickstart) has the short version.

- [Claude Code](#claude-code)
- [Codex CLI](#codex-cli)
- [OpenCode](#opencode)
- [VS Code with GitHub Copilot](#vs-code-with-github-copilot)
- [Other clients](#other-clients)
- [Environment variables](#environment-variables)
- [Tips for every client](#tips-for-every-client)
- [Running by hand](#running-by-hand)

The server is a local stdio process, so every MCP client registers it the same way: the command is
`java`, the arguments are `-jar <absolute-path>/mail-mcp-server.jar`. No port is opened. The
mailboxes come from [`connections.json`](connections.md); credentials are kept out of the client
configuration.

| Client | Where the server is registered |
|---|---|
| [Claude Code](#claude-code) | `claude mcp add` → `~/.claude.json` (user) or `.mcp.json` (project) |
| [Codex CLI](#codex-cli) | `~/.codex/config.toml` |
| [OpenCode](#opencode) | `~/.config/opencode/opencode.json` (global) or `opencode.json` (project) |
| [VS Code with GitHub Copilot](#vs-code-with-github-copilot) | `.vscode/mcp.json` (workspace) or the user `mcp.json` |
| [Cursor, Claude Desktop, others](#other-clients) | the client's `mcpServers` JSON |

Once registered, ask the agent which mailboxes it can read; it calls `listConnections` and lists
the entries of your `connections.json`.

## Claude Code

```powershell
claude mcp add --scope user mail -- java -jar C:/tools/mail-mcp-server.jar
```

`--scope user` makes the server available in every project (stored in `~/.claude.json`); without
it the server is added to the current project only. The `--` separates Claude Code's own options
from the server command. `claude mcp list` shows whether the server started; inside a session,
`/mcp` shows its status and reconnects it.

## Codex CLI

```powershell
codex mcp add mail -- java -jar C:/tools/mail-mcp-server.jar
```

or directly in `~/.codex/config.toml`:

```toml
[mcp_servers.mail]
command = "java"
args = ["-jar", 'C:\tools\mail-mcp-server.jar']
```

A TOML literal string (single quotes) needs no escaping of backslashes. Codex gives a server 10 s
to start (`startup_timeout_sec`); raise it on a slow machine, since a server that misses the
deadline is left out.

## OpenCode

In `~/.config/opencode/opencode.json` (or `opencode.jsonc`), or a project's `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "mail": {
      "type": "local",
      "command": ["java", "-jar", "C:/tools/mail-mcp-server.jar"],
      "enabled": true
    }
  }
}
```

`command` is one array holding the program and its arguments. Add `"timeout": 15000`
(milliseconds) if the server shows up without tools on a slow start.

## VS Code with GitHub Copilot

In `.vscode/mcp.json` for one workspace, or in the user-level `mcp.json` opened with the
**MCP: Open User Configuration** command:

```json
{
  "servers": {
    "mail": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:/tools/mail-mcp-server.jar"]
    }
  }
}
```

The top-level key is `servers`, not `mcpServers`. **MCP: List Servers** starts, stops and
restarts the server and shows its output.

## Other clients

Cursor (`.cursor/mcp.json` or `~/.cursor/mcp.json`), Claude Desktop
(`claude_desktop_config.json`) and most other clients use the common `mcpServers` shape:

```json
{
  "mcpServers": {
    "mail": {
      "command": "java",
      "args": ["-jar", "C:/tools/mail-mcp-server.jar"]
    }
  }
}
```

## Environment variables

The server needs none. These change its defaults:

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_MCP_DATA_DIR` | `%USERPROFILE%\.mail-mcp-server` | Root of local data and logs |
| `MAIL_MCP_CONNECTIONS_FILE` | `<data-dir>\connections.json` | The [connections file](connections.md) |
| `MAIL_MCP_WORD_FORMS_FILE` | `<data-dir>\word-forms.txt` | Optional [word forms](search.md#word-forms) |
| `MAIL_MCP_REDACT_SECRETS` | `false` | Replace recognized secrets in tool responses ([Secret filter](security.md#secret-filter)) |

Set them where the client starts the server, then restart it. Claude Code:

```powershell
claude mcp add --scope user --env MAIL_MCP_REDACT_SECRETS=true mail -- java -jar C:/tools/mail-mcp-server.jar
```

In JSON configurations add an `env` object next to `command` (`environment` in OpenCode):

```json
"env": { "MAIL_MCP_REDACT_SECRETS": "true" }
```

In Codex, add `env = { MAIL_MCP_REDACT_SECRETS = "true" }` to the server's table. The
[commands](commands.md) you run in a terminal read the same variables from your shell, so set
them there too if you change the data directory or the connections file.

## Tips for every client

- **Use absolute paths.** The client picks the working directory, so a relative jar path breaks.
  In JSON, write Windows paths with forward slashes (`C:/tools/mail-mcp-server.jar`) or doubled
  backslashes.
- **Java 25.** If `java` on the `PATH` is older, put the full path of a JDK 25 `java` binary in
  `command`.
- **Pandoc** must be on the `PATH` the client passes to the server, or `sync` refuses to start.
  After installing pandoc, restart the client so that it sees the new `PATH`.
- **Restart after editing `connections.json`**: the server reads it once at startup.
- **Several clients at once** may run their own server processes over the same data directory.
  All of them read; only one synchronizes a connection at a time, and the others answer `sync`
  with `lockedByOtherProcess`.
- **Synchronization runs in the background.** `sync` returns at once; the agent follows it with
  `syncStatus`, so no long tool timeout is needed. Run the first, full synchronization of a large
  mailbox with [`mail-sync`](commands.md#mail-sync) in a terminal.
- **Replacing the jar** fails on Windows while a client keeps the server running. Stop the server
  in the client first, then reconnect.

## Running by hand

```powershell
java -jar C:/tools/mail-mcp-server.jar
```

The server waits for MCP JSON-RPC on stdin; it writes nothing else to stdout. Logs go to stderr
and to `<data-dir>\logs\mail-mcp-server.log`. Running it by hand is mostly useful to see why a
client reports that the server failed to start: a malformed `connections.json` or an unset `${VAR}`
is reported there. Stop it with `Ctrl+C`.

# docs/gmail-oauth-agent.md — Gmail Desktop OAuth and IMAP XOAUTH2

Read before changing `source/oauth/google/`, Gmail OAuth configuration or the `gmail-oauth` command.
Design: plan.md 5.7 and the 2026-10-25 decision to try OAuth2 for Gmail (section 10).
No real Gmail OAuth sign-in is verified yet; all agent checks use synthetic inputs and loopback servers.
The user deferred connecting their Gmail on 2026-10-25 and uses only `work` and `mailru` locally.
Keep this optional capability; the setup below is for a future explicit Gmail connection request.

## User setup

These steps are performed by the user, in their browser and local editor. Never ask for a password,
authorization code, client JSON or token file in chat, and never read the real credential files.

1. Open [Google Cloud Console](https://console.cloud.google.com/) and choose/create a project.
   Cloud Console requires 2-Step Verification for the account managing the project. That can be
   a different account you own with 2SV enabled; add the target Gmail as a test user in step 2.
   OAuth removes the app-password requirement for the target mailbox; it does not remove Cloud
   Console MFA or any verification Google requires during the mailbox's own login.
2. Configure Google Auth Platform / OAuth consent: app name `mail-mcp-server`, support/contact
   email, External audience for personal Gmail, Testing status, and your Gmail address as a test user.
3. In Data Access add the scope `https://mail.google.com/`. This is the scope for IMAP;
   `gmail.readonly` belongs to the Gmail REST API and does not authorize IMAP.
4. In Clients create an OAuth client of type **Desktop app**, named `mail-mcp-server`.
   Download its JSON and save it as `%USERPROFILE%\.mail-mcp-server\google-oauth-client.json`.
   Do not use a Web application client, OOB/manual-code flow, service account, or someone else's client ID.
5. Change only the Gmail entry in the existing external `connections.json`, using the schema below.
   Keep the existing EWS/Mail.ru entries. Remove Gmail's old password field.
6. Run `gmail-oauth gmail` using the built jar. The command opens the system browser; select the
   same mailbox as the configured username and review/approve the consent request.
7. After `RESULT: OK`, run `imap-spike gmail`. No rebuild is required when only credentials change.

Google requires a broad mail scope for IMAP, including permissions beyond reading. The server still
enforces read-only commands at the IMAP protocol boundary; authorization itself does not narrow the scope.
Google may show an unverified/testing-app warning or block authorization under account/admin policies.
Use only the project/client you created. Workspace policy can override the setup.
The Testing refresh token normally expires in seven days for this scope. `invalid_grant` requires
running `gmail-oauth` again; there is no automatic browser or repeated sign-in fallback.
OAuth does not require an app password, but Google can still request verification for the account.

```json
{
  "connections": {
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
  }
}
```

```powershell
.\gradlew.bat bootJar
java -jar build\libs\mail-mcp-server.jar gmail-oauth gmail
java -jar build\libs\mail-mcp-server.jar imap-spike gmail
```

The downloaded client file should also be readable only by the owner, like connections.json:

```powershell
icacls "$env:USERPROFILE\.mail-mcp-server\google-oauth-client.json" /inheritance:r /grant:r "${env:USERNAME}:F"
```

Only `gmail-oauth --help` is safe for agents to run against the normal environment. Never launch the
real authorization command, browser flow, token refresh or IMAP spike on behalf of the user.

## Configuration and lifecycle

Google-specific classes live in `mail.mcp.source.oauth.google`, including the package-private
JSON helper. Shared `mail.mcp.source.oauth` contains only `OAuthLoopbackReceiver` and `OAuthException`;
it has no Google dependency or provider-specific messages. Google code may use the shared package,
while the shared package must not import a provider implementation. Future providers get sibling
packages when implemented.

- `auth: oauth2` is supported only for `imap.gmail.com:993`, TLS true, with a full mailbox username
  and `oauthClientFile`. The path supports `${VAR}` and a leading `~`. Loading config is lazy:
  no file existence check, credential reads, browser or network activity occurs at startup.
- `password` is not required or used for OAuth. A present password is discarded after placeholder
  resolution; remove it to avoid stale/unset placeholder errors. Mail.ru stays on app-password.
- Downloaded JSON must have an `installed` object with `client_id`; `client_secret` is optional.
  URLs supplied by that JSON are ignored. Code uses only Google's fixed authorization and token endpoints.
- Refresh tokens are stored at `<data-dir>/<connection>/oauth/google-tokens.json`. The file binds
  provider, client ID, username, scope and refresh token. Wrong client/account/scope requires new consent.
- `GoogleTokenStore` creates owner-only temporary files (Windows ACL or POSIX 0600) before writing
  any secret bytes and atomically replaces the final file. Unsupported filesystems fail closed.
  An OS file lock prevents concurrent authorization/refresh writes across server processes.
- Only refresh tokens persist. Access tokens are cached in memory until 60 seconds before expiry;
  they are obtained lazily before IMAP connects. Rotated refresh tokens are saved under the same lock.
- OAuth failures become fixed messages, with no raw provider body or nested exception. A failed token
  provider is terminal for that client; new attempts require an explicit new request. The token HTTP
  client disables retries and redirects, uses connect/request/read timeouts and a 64 KiB response cap.
- Credential files remain plaintext protected by owner permissions; encryption/DPAPI is future work.

## Authorization flow

`GoogleOAuthFlow` implements authorization-code exchange with PKCE S256: a random 32-byte verifier
and state, loopback receiver on `127.0.0.1` with an ephemeral port, exact `/oauth2/callback` path,
GET only, state validation, duplicate-query rejection, one response and a five-minute deadline.
Invalid callbacks do not complete the flow. Denial, timeout, missing refresh token or wrong granted
scope never replaces an existing token. Browser pages contain fixed text and no codes/tokens.
The browser URL is not printed. It contains no login hint, and the user chooses the configured account.
No token request is made until the callback has been validated. No mailbox data is requested by this command.

## IMAP behavior

OAuth config selects only XOAUTH2. The wire guard forbids LOGIN and password mechanisms even when
Angus tries its fallback for a missing XOAUTH2 capability. One credential exchange per client remains.
The protocol wrapper implements the XOAUTH2 exchange because Angus 2.0.5's built-in loop resends
credentials on error continuation; Gmail requires an empty response to its JSON error challenge.
Tests cover SASL-IR and continuation-based authentication. Error challenges get one empty reply,
never a repeated token; additional challenges fail. The response loop is bounded to 32 responses.
LIST/EXAMINE/BODY.PEEK and the existing no-write guard are unchanged.

## Offline verification

`source.oauth.google.GoogleOAuthTest`: browser substitute drives a real loopback callback; checks
PKCE, scopes, denial, token-file ACL, refresh caching/rotation, client/account
binding, process-lock contention, sticky invalid_grant and no-redirect/no-retry HTTP behavior.
`source.oauth.OAuthLoopbackReceiverTest`: provider-independent state validation, duplicate query
parameters, exact callback path, replay rejection and timeout.
`ImapClientTest`: synthetic XOAUTH2 with/without SASL-IR, error challenge acknowledgement, no
credential resend and no LOGIN fallback when the server lacks XOAUTH2.
`ConnectionsLoaderTest`: passwordless/lazy OAuth configuration and Gmail-only validation.
The built jar's `gmail-oauth --help` must dispatch before Spring/stdio starts.

## Primary sources

Checked 2026-10-25:

- [Google Desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app): PKCE, loopback, code exchange and refresh.
- [Gmail XOAUTH2](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol): mail scope, SASL-IR and empty error response.
- [OAuth app states](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview): Testing, External users and admin policy.
- [Cloud Console 2SV](https://docs.cloud.google.com/docs/authentication/mfa-requirement): MFA is required for project administrators using the console, independently of Gmail OAuth consent.
- [Token expiry](https://developers.google.com/identity/protocols/oauth2#expiration): refresh-token lifetime and Testing exception.
- [Angus 2.0.5 IMAPProtocol](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/imap/src/main/java/org/eclipse/angus/mail/imap/protocol/IMAPProtocol.java): authoauth2 continuation loop.

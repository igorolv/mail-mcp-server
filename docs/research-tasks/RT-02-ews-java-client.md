# RT-02 — EWS client and NTLM in Java

- **Result file:** `docs/research/RT-02-ews-java-client.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 2; `docs/plan.md`, section 3.2 (choice of HTTP client).

## Goal

Find the best way to call Exchange Web Services (EWS) from a Java 25 / Spring Boot 4 application,
including NTLM authentication.

## What we already know

- The server is **on-premise Exchange 2013 CU23**. EWS is at `https://webmail.example.com/EWS/Exchange.asmx`.
  It offers `Negotiate`, `NTLM` and `Basic` (`docs/plan.md`, section 3.1).
- Sign-in works with **NTLM and a bare login** (no domain part) from Windows PowerShell /
  .NET. Basic is not verified yet (`docs/plan.md`, section 3.2).
- The client machine is **not** joined to the domain, so Kerberos is not an option.
- Sibling projects use Spring's `RestClient`. Java's `java.net.http.HttpClient` has no NTLM
  support as far as we know — verify this.
- We need: folder list, incremental sync (`SyncFolderItems`), items with MIME content,
  attachments, search. Read-only for now.

## Questions

- **В1. `ews-java-api`** (Microsoft OfficeDev): last release and last commit; official
  maintenance status; does it work on Java 17/21/25 (look for issues about `javax` vs `jakarta`,
  removed JDK modules, JAXB, Apache HttpClient 4.x); known maintained forks. Give links to the
  issues you base the answer on.
- **В2.** Other Java EWS libraries or forks; are any maintained in 2025–2026?
- **В3. Own thin SOAP client.** How would we build one: XML templates + StAX, Jackson XML, JAXB
  classes generated from the EWS schema (`Services.wsdl`, `messages.xsd`, `types.xsd`), or
  Spring Web Services? Where can the schema files be obtained **without signing in** to our
  server (for example, from the `ews-java-api` repository or Microsoft documentation)? Rough
  effort for the ~10 operations listed in RT-03.
- **В4. NTLM in Java HTTP clients.** For each client, can it do NTLM (specifically NTLMv2), and
  how? Cover at least: `java.net.HttpURLConnection` with `java.net.Authenticator` (the JDK has
  NTLM support there — check on non-Windows and on Windows, and whether NTLMv2 is used),
  `java.net.http.HttpClient`, Apache HttpClient 5.x (`NTLMScheme` — deprecated? removed in which
  version?), Apache HttpClient 4.x, OkHttp (third-party authenticators), JCIFS-ng as an NTLM
  engine, Spring `RestClient` on top of each.
- **В5.** How does **DavMail** (Java, open source) talk to EWS and do NTLM? Point to the relevant
  source files. Is its approach reusable?
- **В6.** Any other caveats for EWS from Java: TLS settings, connection reuse with NTLM
  (connection-bound auth), timeouts, compression, cookies / `X-BackEndCookie`.

## Where to look

- `https://github.com/OfficeDev/ews-java-api` — README, issues, releases, forks network.
- Microsoft Learn: EWS reference, "EWS Managed API" deprecation notes, EWS schema docs.
- JDK documentation: "HTTP authentication" in `java.net` docs, `Authenticator`, `jdk.http.ntlm.transparentAuth`.
- Apache HttpComponents release notes and Javadoc for `NTLMScheme`.
- DavMail source: `https://github.com/mguessan/davmail` (look for the EWS and NTLM classes).

## Required tables

| Вариант клиента EWS | Поддерживается (дата последнего релиза) | Java 25 | NTLM | Объём работы | Риски |
|---|---|---|---|---|---|

| HTTP-клиент | NTLM есть? | NTLMv2? | Как подключить | Статус (устарел / удалён / жив) | Источник |
|---|---|---|---|---|---|

## Out of scope

- Writing or running code against our server; signing in anywhere.
- Microsoft Graph and Exchange Online specifics (mention only if relevant to on-prem).
- IMAP (RT-04), MIME parsing (RT-09).

## Done when

- Both tables are filled for every option named in В1–В4.
- В5 points to concrete DavMail source files.
- The recommendation names one primary and one fallback approach for "EWS client + NTLM", with
  reasons.

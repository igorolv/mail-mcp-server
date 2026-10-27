# RT-02 — EWS and NTLM Client in Java

- Date: 2026-10-24.
- Author: Codex, RT-02 research session.
- Brief: `docs/research-tasks/RT-02-ews-java-client.md` (historical path).
- Method: documentation, source code, and metadata from public repositories; no builds or mailbox sign-ins.

## Summary

1. [code] The original `OfficeDev/ews-java-api` was archived on 2024-06-18;
   artifact 2.0 was published on 2015-09-09, and the last `master` commit was 2018-07-25. [1–3]
2. [code] Forks with changes in 2025–2026 exist: `eischet`, `jcetech`, and `SpanningCloudApps`.
   No confirmed Java 25 check was found for the reviewed forks. [7–9]
3. [docs][code] `HttpURLConnection` supports NTLM; `java.net.http.HttpClient` in JDK 25
   automatically handles only Basic. Using the same `Authenticator` does not make them equivalent. [15–19]
4. [code] On Unix, the JDK uses a Java implementation with NTLMv2 by default;
   on Windows, it uses the system mechanism, whose version depends on Windows policy. [16–18]
5. [docs][code] Apache HttpClient disabled NTLM by default and deprecated it starting with 5.3.
   It is not removed in the reviewed 5.6.5 and 5.7-beta1; the scheme must be registered explicitly. [20–23]
6. [code][assumption] I recommend a thin SOAP client using StAX + HttpClient 5 classic
   with explicit NTLM registration; the fallback is the same SOAP layer + buffered `HttpsURLConnection`. [19–24]
7. [code] DavMail 7.1.0 was released on 2026-10-17: its own SOAP/StAX implementation runs over HttpClient 4,
   and it chooses between JCIFS and its own TLS-bound scheme. This is an example, not a ready-made SDK. [33–38]
8. [assumption] About 15–25 working days, including checks, are needed for roughly 10 operations.
   Successful NTLM from .NET does not yet prove Java will work: domain, NTLMv2, and TLS binding differ.

## Answers to the questions

### В1. Status of OfficeDev/ews-java-api and Java 17/21/25

[code] The latest version in Maven Central is `com.microsoft.ews-java-api:ews-java-api:2.0`;
the artifact directory is dated 2015-09-09. GitHub Releases is empty: this is the Maven publication date,
not a GitHub Release date. The last commit on the main branch is
`33235f37d97871f17c496526aad0b8c458f904c3`, 2018-07-25, a README change. [2, 3]

[code] The README states that the project would maintain existing functionality and stop adding new features
as of 2018-07-19; the repository was archived on 2024-06-18. This cannot be considered active SDK support.
The Exchange Online limitations in the README do not by themselves rule out EWS on our on-premises server. [1]

[code] The main branch `pom.xml` specifies HttpClient/HttpCore 4.4.1 and old dependencies;
this is not a current compatibility matrix. The source contains two significant imports:
`ServiceRequestBase` → `javax.xml.ws.http.HTTPException`,
`TimeChange` → `javax.xml.bind.DatatypeConverter`. [4, 5]

[docs] JDK 11 removed JAX-WS, JAXB, and their tools from the JDK distribution.
Therefore, on 17/21/25 these Java EE APIs must be added separately or their use replaced.
The standard `java.xml` module, including StAX, was not removed. [6]

| JDK | What is confirmed | Conclusion for the original SDK |
|---|---|---|
| 17 | [code] The old imports remain; `eischet` separately moved its build to 17. [5, 7] | [assumption] It may run after dependency fixes; there is no official guarantee. |
| 21 | [docs][code] The removed Java EE APIs have not been restored; no verified project report for 21 was found. [5, 6] | [assumption] The same fixes and a separate behavior check are needed. |
| 25 | [code] No confirmation of SDK CI/release support for 25 was found. [1, 4, 7–9] | [assumption] Do not consider it compatible until it is built and the required operations are checked. |

[code][secondary] Issue [#773](https://github.com/OfficeDev/ews-java-api/issues/773),
dated 2023-12-20, names these two classes directly and reports the author's local migration to Jakarta.
This is evidence of a problem and a local fix, not a released Jakarta version. [5]

[assumption] The issue's claim that it cannot work in a Jakarta application is too broad:
in a standalone Boot application, the old `javax` APIs may coexist with Jakarta in separate packages.
However, Jakarta XML Binding 3+/XML Web Services 3+ do not provide the old `javax` classes;
changing dependency coordinates alone does not fix the imports. The basis is the specific imports [5].

[code][secondary] Issue [#692](https://github.com/OfficeDev/ews-java-api/issues/692),
dated 2019-03-01, discusses creating a community fork. This does not confirm that
a single supported successor appeared. The reviewed candidates are listed in В2. [10]

### В2. Other Java libraries and forks active in 2025–2026

[code] `eischet/ews-java-api` is the most substantial modernization reviewed:
separate API and Apache 4/5 transport modules, Jakarta JAXB, Java 17;
packages were renamed to `com.eischet.ews`. The README describes 2.3.2 dated 2025-12-29,
but also says that ready-made packages are unavailable. The POM still uses HttpClient 5.2.1. [7]

[assumption] This fork is useful if a broad object-oriented EWS API is needed.
Its HttpClient cannot simply be upgraded with the assumption that NTLM will remain enabled:
authentication settings changed between 5.2.1 and the current line; see В4.

[code] `jcetech/ews-java-api`, `dev` branch: last commit 2025-08-17;
POM 2.2.0, target Java 17 and Jakarta dependencies, but HttpClient/HttpCore are still 4.4.1.
No GitHub Release was found. A version in the POM does not prove that an artifact was published. [8]

[code] `SpanningCloudApps/ews-java-api`: last commit 2026-06-09,
adds standard request configuration; the POM uses HttpClient 4.5.13.
No GitHub Release was found; a recent commit confirms targeted work, not Java 25 support. [9]

[docs] The commercial `Independentsoft JWebServices for Exchange` offers version 2.2.150,
Exchange 2013, MIME, synchronization, search, folders, and attachments. The page claims Java 5+,
but gives no release date, separate Java 25 guarantee, or verifiable NTLMv2 details. [11]

[docs] TuxCare publishes `2.0-tuxcare.1`, dated 2026-05-11, as a security fix
for `ews-java-api` (PF-2697, CVE-2025-48924). This is an example of a vendor maintaining an old artifact,
not evidence of a new open SDK or compatibility with Boot 4. [12]

[assumption] In short, the answer “there are no active Java options at all” is incorrect.
There are small forks, a commercial product, and actively released DavMail; no common open-source
successor with confirmed Java 25, NTLMv2, and our full operation set has been identified.

### В3. A custom thin SOAP client, schemas, and effort

[docs][code] EWS supports direct SOAP/XML; DavMail demonstrates this approach without SDK generation.
For our task, transport and XML handling can be separated: NTLM does not depend on how
XML is represented. Set the request version explicitly: `Exchange2013_SP1`. [13, 14, 34]

| Approach | What must be done | Applicability assessment |
|---|---|---|
| XML templates + StAX | Write the envelope, namespaces, and parameters; stream-parse only the required fields | [assumption] Primary candidate: a small explicit contract and controlled memory use. Example approach — [34]. |
| Jackson XML | Custom classes for envelope, header, requests, and responses; separate error handling | [docs][assumption] Convenient for simple DTOs, but it maps objects to XML; it is not a ready-made SOAP/EWS SDK and the XML model has limitations. [29] |
| JAXB from XSD, JAX-WS from WSDL | External generators, Jakarta API/runtime, import fixes, and possibly adding a WSDL service | [docs][assumption] Full-schema typing is useful for a broad API; usually excessive for 10 operations. [6, 13] |
| Spring Web Services | `WebServiceTemplate`, SOAP message factory, `Source` or JAXB, configured HTTP sender | [docs][assumption] A good SOAP wrapper, but it does not implement EWS or NTLM; transport configuration remains ours. [30] |

[docs] `Services.wsdl` describes operations, `messages.xsd` describes messages,
and `types.xsd` describes common types and headers. Microsoft warns that a WSDL may lack
`wsdl:service`, so some generators require it to be added locally. [13]

[code] The schemas can indeed be obtained without signing in to our server:

- `php-ews/src/assets/` publicly contains a co-located set of
  [`services.wsdl`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/services.wsdl),
  [`messages.xsd`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/messages.xsd),
  [`types.xsd`](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/types.xsd).
  `types.xsd` includes `Exchange2013_SP1`. These are copies in a third-party project,
  not an authenticated exact schema for our CU23. [31]
- [docs] Microsoft Open Specifications `[MS-OXWSCDATA]`, appendix Full WSDL,
  provides common schemas and links to their definitions. This protocol itself does not define WSDL;
  the relevant specifications are needed for operations, for example `[MS-OXWSSRCH]`.
  This is a primary source for the contract, not a ready-made unified `Services.wsdl` set. [32]
- [code] Genivia publishes all three files for its gSOAP example, but the reviewed XSDs are
  version `Exchange2010_SP2`. They must not be used to generate a complete Exchange 2013 client. [40]
- [code] No WSDL/XSD files were found in the `OfficeDev/ews-java-api/master` tree:
  the library contains a hand-written XML implementation, not the promised schema set for a generator. [3, 4]

[assumption] Before future generation, pin the schema revision, check all relative
imports/includes, add an XML catalog for external schemas, and separately map required operations
to Exchange 2013. A version value in an XSD alone does not validate the entire contract. [13, 14, 31]

[assumption] The following scope is proposed for a thin client; no code was written in this session:

1. A common SOAP 1.1 envelope, `SOAPAction`, UTF-8, version header, and safe parameter writing.
2. Parse `SOAP Fault` independently of HTTP status and `ResponseClass`/`ResponseCode`
   separately for each batched request result; limit XML and Base64 sizes.
3. Ten operations from the companion brief:
   `GetFolder`, `FindFolder`, `SyncFolderHierarchy`, `FindItem`, `SyncFolderItems`,
   `GetItem`, `GetAttachment`, `FindConversation`, `GetConversationItems`, `ConvertId`.
   Search via `Restriction`/`QueryString` are variants of `FindItem`, not new operations.
4. Stream MIME and file retrieval, handle changes and pages;
   save local response examples for errors, Cyrillic, and large attachments.

| Work | Estimate, working days for one developer |
|---|---|
| NTLM transport, TLS, timeouts, mailbox isolation | [assumption] 3–5 |
| Envelope, safe XML, errors, and repeatable request body | [assumption] 2–3 |
| Folders, lists, and synchronization | [assumption] 3–5 |
| MIME, attachments, conversations, and ConvertId | [assumption] 3–5 |
| Checks against saved responses and user verification on Exchange | [assumption] 4–7 |
| Total for the StAX option | [assumption] 15–25; complex NTLM-to-TLS binding may increase the estimate |

[assumption] This estimates implementation of the EWS source, not the entire MCP server, index, or MIME parser.
Jackson XML or Spring-WS may reduce boilerplate if the team knows them,
but will not eliminate the need to handle EWS-specific behavior; JAXB adds the risk of separately debugging the generator.

### В4. NTLM and NTLMv2 in HTTP clients

[docs][code] It is important to distinguish NTLMv2 from NTLM2 Session: they are different modes.
The presence of “NTLM” in an API does not prove NTLMv2 or support for TLS binding. [18, 24]

**`HttpURLConnection` / `HttpsURLConnection`.** [docs] It works through
`java.net.Authenticator`; the domain may be absent, included in the username, or set with
`http.auth.ntlm.domain`. Transparent sign-in with the current Windows account is controlled by
`jdk.http.ntlm.transparentAuth`; we do not need it, so leave it `disabled`. [15]

[code] On Unix, the implementation reads `ntlm.version` and creates `com.sun.security.ntlm.Client`;
its default mode is `LMv2/NTLMv2`, and `NTLMv2` can be selected explicitly.
This conclusion comes from checking the HTTP source, not from extrapolating SASL documentation. [16, 18]

[code][assumption] On Windows, `NTLMAuthSequence` and system authentication are used;
the Unix-branch `ntlm.version` property does not switch Windows to NTLMv2.
The actual mode is determined by Windows policy and negotiation with the server.
The Windows code falls back to the domain `domain` if the property is unset: for a bare login,
an explicitly empty domain must be checked separately; do not automatically substitute `example.com`. [17]

[docs] A limitation for SOAP POST: `setFixedLengthStreamingMode` and
`setChunkedStreamingMode` disable automatic authentication and redirect handling;
`HttpRetryException` may be thrown instead. Therefore, the fallback buffers the small
XML request inside URLConnection; this does not prevent streaming a large response. [19]

**`java.net.http.HttpClient`.** [code] In JDK 25, `AuthenticationFilter` selects Basic;
the NTLM handshake is not implemented. Simply adding an `Authenticator` will not help,
and manually sending Type1/Type3 over an opaque connection pool is not a ready solution. [39]

**Apache HttpClient 5 classic.** [docs][code] Since 5.3, NTLM is deprecated and disabled by default;
in 5.6.5, the standard scheme registry does not include NTLM. However, `NTLMScheme`,
`NTLMSchemeFactory`, and `NTCredentials` still exist; `NTLMScheme` is also present in 5.7-beta1.
It would be incorrect to say “removed in 5.4/5.5.” [20–23]

[code][assumption] Configuration: a custom `Registry<AuthSchemeFactory>` with
`NTLMSchemeFactory`, a `BasicCredentialsProvider` with `NTCredentials`, scope limited to
the target HTTPS host/port, only the NTLM scheme allowed, and a repeatable XML body.
Pass an empty domain separately for `user`; the user must verify the exact format. [21, 22]

[code] The engine supports NTLMv2 but also includes older modes and transitions between them.
Supporting NTLMv2 is not the same as enforcing an “NTLMv2 only” policy. In the reviewed source, the scheme
calls the ordinary `generateType3Msg` without passing the TLS certificate, although the engine contains
channel-binding code. Therefore, Extended Protection support cannot be promised based on the engine alone. [21, 24]

**Apache HttpClient 4.5.14.** [docs] NTLMv1, NTLMv2, and NTLM2 Session have been
supported out of the box since 4.1; use `NTCredentials` and `CredentialsProvider`.
The 4.x line receives only significant and security fixes;
the official site does not declare all of classic HttpClient 4.5 EOL. [25, 26]

**OkHttp.** [code] It has an `Authenticator` interface for 401/407 responses,
but does not provide a ready-made NTLM engine. A third-party example is
`netcosports/Android_Ntlm:1.0`, `NTLMAuthenticator`, and its own `NTLMEngineImpl`.
The engine contains NTLMv2 but depends on `android.util.Base64`: it is not a ready-made Java 25 library. [27, 28]

**JCIFS-ng.** [code] Provides `Type1Message`/`Type2Message`/`Type3Message`,
NTLMv2, and a configuration context; the default `lanManCompatibility` is 3.
An `NTLMEngine` adapter for Apache or an `Authenticator` for OkHttp can be written,
but the HTTP client handles connection persistence, TLS, timeouts, and retries. [41]

[assumption] Old JCIFS 1.x examples cannot be copied for JCIFS-ng without adaptation:
the API and context differ. The presence of a Type3 generator alone also does not prove
correct binding of HTTPS authentication to the channel.

**Spring `RestClient`.** [docs][code] Support is determined by `ClientHttpRequestFactory`:
the Apache factory uses HttpClient 5; the JDK factory uses the new JDK HttpClient;
the Simple factory uses URLConnection. Spring 7 removed built-in OkHttp3 integration. [42, 43]

[code][assumption] The Simple factory requires special care: Spring 7 sends
the body in fixed-length/chunked streaming mode. Therefore, the standard factory does not solve NTLM SOAP POST,
even with a configured `Authenticator`. A direct buffered URLConnection or a custom factory is needed;
an outer buffering wrapper does not guarantee streaming is disabled in the inner request. [19, 44]

[code][assumption] With the Apache factory, repeatability depends on `Body.repeatable()`:
the interface's default is false. For RestClient, verify the specific way XML is sent;
the initial implementation is simpler if done directly with a repeatable Apache entity. [48]

### В5. How DavMail works and what can be reused

[code] The latest described release is 7.1.0 dated 2026-10-17.
The `master` source was read on 2026-10-24; this is the branch state, not necessarily the released archive. [33]

| File in `src/java/davmail/` | What to study |
|---|---|
| `exchange/ews/EwsExchangeSession.java` | [code] EWS coordination, retrieving folders, MIME, and attachments; claims Exchange 2013 compatibility. [35] |
| `exchange/ews/EWSMethod.java` | [code] SOAP envelope, HTTP POST, repeatable body, StAX, gzip, and EWS errors. [34] |
| `http/HttpClientAdapter.java` | [code] HttpClient 4, cookie store, connections, `NTCredentials`, scheme registry, timeouts. [36] |
| `http/JCIFSNTLMSchemeFactory.java`, `http/JCIFSEngine.java` | [code] JCIFS branch: adapts Type1/Type2/Type3 to Apache `NTLMEngine`. [37] |
| `http/DavMailNTLMSchemeFactory.java`, `http/DavMailNTLMScheme.java` | [code] Alternative scheme that extracts the TLS certificate from the current connection. [38] |
| `http/DavMailNTLMEngineImpl.java` | [code] NTLMv2, adding channel-binding data and MIC to the response. [38] |

[code] The history matters: 6.3.0 (2025-02-26) removed JCIFS in favor of its own channel-binding implementation;
6.4.0 (2025-08-31) restored JCIFS; 6.5.0 (2025-10-23) restored
`davmail.enableJcifs=true` as the default. The current POM contains JCIFS **1.3.19**, not JCIFS-ng.
Therefore, the statement “DavMail no longer uses JCIFS” is outdated. [33, 36, 37]

[code][assumption] In the current code, `enableJcifs=false` selects its own TLS-bound scheme.
Issue #352 shows why this is needed: with Extended Protection enabled, the previous
handshake ended in 401 even though browser sign-in worked. This does not prove that our server uses this setting. [38, 45]

[code][assumption] Reusable architectural ideas include a limited set of SOAP operations,
a repeatable request, a streamed response, and HTTP-session isolation. The files have GPL-2.0-or-later headers;
these terms must be considered when porting code. I do not recommend embedding the entire gateway as an SDK:
it is tied to Settings, protocols, UI, and its own logging, and it also contains write operations. [34–38]

### В6. TLS, connections, timeouts, compression, and cookies

| Area | Verified fact and implementation implication |
|---|---|
| TLS | [assumption] Start with the system trust store, hostname verification, and TLS 1.2/1.3; TLS 1.2 availability on our server was not checked separately. Do not treat DavMail's TLS 1.0/1.1 protocol list as a recommended setting. [36] |
| Extended Protection | [code][assumption] Check that the certificate is passed to the NTLM engine, not merely that NTLMv2 is present. .NET and pure Java may behave differently. [21, 24, 38, 45] |
| Connections | [docs] NTLM authenticates the connection; one socket is needed throughout Type1 → Type2 → Type3. Authenticated connections must not be shared with another user. [25] |
| Multiple mailboxes | [assumption] Use a separate HTTP client/pool, credentials, and cookies for each connection entry; start with one concurrent request per mailbox. URLConnection needs a separate Authenticator instance per mailbox. [19, 25] |
| POST repeatability | [docs][code] During NTLM, a request may be sent again. Buffer the small XML; do not use a one-shot InputStream as the body. DavMail explicitly marks the SOAP entity repeatable. [19, 34] |
| Timeouts | [assumption] Set limits for waiting for a free connection, TCP/TLS setup, reading, and the overall operation; initial estimate: 10–15 s for connecting, 120 s for reading. DavMail's 10/120 s defaults are a reference, not Exchange limits. [36] |
| Retries | [assumption] Distinguish expected 401 responses within the handshake from final rejection. Do not automatically cycle through logins/methods; limit network retries and EWS delays. [21, 34] |
| gzip | [code][assumption] DavMail sends `Accept-Encoding: gzip` and reads gzip. When configuring Apache/JDK, choose one decompression layer; also limit the decompressed size and decode Base64 as a stream. [34] |
| Cookies | [code][assumption] Store `Set-Cookie` per mailbox according to host/path/secure rules. Treat `X-BackEndCookie` as opaque; do not construct or log it. A cookie does not replace NTLM socket state. [36, 46] |
| Server affinity | [docs] For subscription groups, Microsoft describes `X-AnchorMailbox`, `X-PreferServerAffinity`, and **`X-BackEndOverrideCookie`**; this is not the same name as `X-BackEndCookie`. Do not claim this subscription mechanism is required for ordinary SyncFolderItems. [46] |
| Redirects | [assumption] The URL is fixed in configuration; prohibit automatically sending credentials to another host. This is simpler than Autodiscover and matches the original task constraints. |
| XML and memory | [docs][assumption] Prohibit DTD/external entities and network schema resolution; set depth and size limits. Do not build MIME using a DOM of the entire response. Basis: JAXP guidance and the model of large EWS responses. [34, 47] |

## Comparison tables

In the first table, effort is an [assumption], and Java 25 compatibility without running it is “verify.”
Transport options are summarized in the second table so SOAP SDKs are not mixed with HTTP clients.

| EWS client option | Supported (last release date) | Java 25 | NTLM | Effort | Risks |
|---|---|---|---|---|---|
| OfficeDev 2.0 | [code] Archived; Maven 2015-09-09 [1–3] | Verify; old external Java EE APIs are needed | [code] HttpClient 4 [4] | Medium: restore dependencies | Old SDK, broad write API |
| eischet | [code] Version in source is 2.3.2, 2025-12-29; published package not confirmed [7] | Verify; baseline 17 | [code] Apache 4/5; JDK module has issues [7] | Medium | Renamed packages, HTTP 5.2.1, NTLM upgrades |
| jcetech | [code] Commit 2025-08-17; no release found [8] | Verify; target 17 | [code] HTTP 4.4.1 | Medium/high | Old transport dependency |
| SpanningCloudApps | [code] Commit 2026-06-09; no release found [9] | Verify | [code] HTTP 4.5.13 | Medium/high | Targeted fixes, no Java 25 guarantee |
| TuxCare 2.0-tuxcare.1 | [docs] Fix dated 2026-05-11 [12] | Not established | Not established for this build | Depends on vendor | Availability and full contract unconfirmed |
| Independentsoft JWebServices | [docs] 2.2.150; release date unpublished [11] | Verify with vendor | NTLMv2 not established | Low/medium after verification | Commercial terms, closed implementation |
| Custom SOAP: templates + StAX | Maintained by us | [docs] StAX remains in the JDK [6] | Depends on transport | 15–25 days | Custom error handling, synchronization, and limits |
| Custom SOAP: Jackson XML | [code] Active XML module [29] | Verify selected version | Depends on transport | Comparable to StAX | Manual SOAP modeling and XML limitations |
| Custom SOAP: JAXB/JAX-WS | [docs] External APIs and generators [6, 13] | Verify toolchain | Depends on transport | Medium/high | Schema and generation complexity, number of classes |
| Spring Web Services | [docs] Documentation 5.0.2, 2026-06-10 [30] | Verify toolchain with Boot 4 | Through sender/HTTP client | Medium | Not a ready EWS SDK, SOAP message factory memory use |
| DavMail as library/example | [code] 7.1.0, 2026-10-17 [33] | Embedding not established | [code] JCIFS or its own engine [36–38] | High to embed | Gateway coupling, GPL, write operations |

| HTTP client | NTLM available? | NTLMv2? | Configuration | Status (deprecated / removed / active) | Source |
|---|---|---|---|---|---|
| HttpURLConnection, Unix | [code] Yes | [code] Yes; default LMv2/NTLMv2 | Authenticator; optionally `ntlm.version=NTLMv2`; POST without output streaming | [docs] Public JDK 25 API | [15, 16, 18, 19] |
| HttpURLConnection, Windows | [code] Yes | [assumption] Through system mechanism; depends on Windows policy | Authenticator, `transparentAuth=disabled`, verify empty domain | [docs] Public JDK 25 API | [15, 17, 19] |
| java.net.http.HttpClient | [code] No built-in support | No built-in support | Authenticator handles Basic; NTLM would require a separate implementation | [code] Active client without NTLM | [39] |
| Apache HttpClient 5.6.5 classic | [code] Yes, enable explicitly | [code] Yes, not necessarily v2 only | NTLMSchemeFactory + NTCredentials + CredentialsProvider + registry and NTLM preference | [docs][code] Client active; NTLM deprecated since 5.3, not removed in 5.7-beta1 | [20–24] |
| Apache HttpClient 4.5.14 classic | [docs] Yes | [docs] Yes since 4.1 | NTCredentials + CredentialsProvider | [docs] Significant and security fixes only | [25, 26] |
| OkHttp | [code] Only through extension | [code] Present in Android_Ntlm engine; adapt for Java | Authenticator + engine such as JCIFS-ng; HTTP/1.1, verify a single socket | [code][assumption] Main API active; ready Java 25 adapter not confirmed | [27, 28, 41] |
| JCIFS-ng as an engine | [code] Message generator, not HTTP transport | [code] Yes | CIFSContext + Type1/2/3, custom HTTP adapter | [code] Last reviewed commit 2025-11-18; we maintain HTTP integration ourselves | [41] |
| RestClient + JdkClientHttpRequestFactory | [code] No | No | Factory does not add NTLM to JDK HttpClient | [docs] Active Spring API | [39, 42] |
| RestClient + HttpComponentsClientHttpRequestFactory | [docs][code] Yes with Apache 5 configured | [code] Same as Apache 5 | Pass a preconfigured HttpClient 5; verify body repeatability | [docs] Active Spring API; transport NTLM deprecated | [20–24, 42] |
| RestClient + SimpleClientHttpRequestFactory | [code] Standard factory interferes with SOAP POST handshake | JDK engine has it; verify this combination | Custom factory without output streaming or direct URLConnection | [code] Active API with streaming POST limitation | [19, 44] |
| RestClient + Apache 4 / OkHttp / JCIFS-ng | No built-in Spring 7 factory | Depends on adapter | Custom ClientHttpRequestFactory; connect JCIFS-ng at lower HTTP layer | [docs][assumption] We maintain integration ourselves; OkHttp3 factory removed in 7.0 | [42, 43] |

## Recommendation

[assumption] **Primary option: custom SOAP/StAX + Apache HttpClient 5 classic**,
with NTLM registered explicitly and a separate HTTP session per mailbox. It provides the required connection
control; integration with Spring RestClient is possible after verifying body repeatability,
see В4. The SOAP contract is limited to ten operations.
The tradeoff is significant: Apache developers no longer support NTLM.
Pin the verified version and check this area on every upgrade. [20–24, 42, 48]

[assumption] **Fallback: the same SOAP/StAX + direct buffered HttpsURLConnection**,
with a separate `Authenticator` and explicit credentials. On target Windows this is a path
independent of Apache, using the system NTLM implementation; bare login/empty domain,
NTLMv2, no streaming POST, and connection reuse must be checked. This does not promise
that the system mechanism will handle Extended Protection without additional changes. [15–19]

[assumption] If a ready-made broad EWS model matters more, the next candidate is the `eischet` fork
after a dependency audit. If the user separately confirms Basic over HTTPS, the same SOAP layer
could use a modern supported transport without NTLM; for now, this is conditional.
The decision remains with the user; the plan documents were not changed in this session.

## What could not be verified

- [assumption] No selected combination was run on Java 25 + Boot 4 and our Exchange:
  the code was not built and no requests were made. The user should check one read-only GetFolder,
  then MIME, an attachment, synchronization, and reconnection; without cycling through logins.
- [assumption] The server's NTLMv2, Extended Protection, and TLS policies are unknown.
  The user/administrator should check the configuration and safe diagnostics for the selected client;
  evidence that “.NET signed in” does not replace this check.
- [code] Latest fork releases and official Java 21/25 CI were not established.
  GitHub API metadata and source were reviewed; later the anonymous API returned a rate limit.
  The stated commit dates are not presented as package publication dates. [7–9]
- [docs] The date of Independentsoft 2.2.150, NTLMv2, and Java 25 support were not established;
  request this information from the vendor before choosing. Check TuxCare access terms separately. [11, 12]
- [assumption] A complete JAXB/JAX-WS client was not generated from public XSD copies;
  import compatibility, operation coverage, and memory use for large responses still need checking. [13, 31]
- [code] Genivia schemas were verified as Exchange2010_SP2; an exact match between public
  php-ews copies and the installed CU23 schema is unconfirmed. [31, 40]

## Further topics to study

- [assumption] Errors, operation limits, and synchronization-state recovery are covered in
  separate research [RT-03](RT-03-ews-operations.md).
- [assumption] Streaming parsing of MIME and embedded messages is covered in
  [RT-09](RT-09-mime-attachments.md); this report covers only the EWS → MIME boundary.

## Sources

All sources below were read on **2026-10-24**. For pages without a publication date,
“undated” is stated; commit and version dates are not treated as a support guarantee.
Source files that the web viewer did not display in full were read into memory with an anonymous HTTP GET.

1. [OfficeDev/ews-java-api: README and archive status](https://github.com/OfficeDev/ews-java-api) — archived 2024-06-18, statement dated 2018-07-19.
2. [Maven Central, 2.0 directory](https://repo.maven.apache.org/maven2/com/microsoft/ews-java-api/ews-java-api/2.0/) — published 2015-09-09; [GitHub Releases](https://github.com/OfficeDev/ews-java-api/releases) — no releases.
3. [Latest OfficeDev commit](https://github.com/OfficeDev/ews-java-api/commit/33235f37d97871f17c496526aad0b8c458f904c3) — 2018-07-25; [tree API](https://api.github.com/repos/OfficeDev/ews-java-api/git/trees/master?recursive=1) — undated.
4. [OfficeDev pom.xml](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/pom.xml) — main branch, undated.
5. [Issue #773](https://github.com/OfficeDev/ews-java-api/issues/773) — 2023-12-20; [ServiceRequestBase.java](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/src/main/java/microsoft/exchange/webservices/data/core/request/ServiceRequestBase.java), [TimeChange.java](https://raw.githubusercontent.com/OfficeDev/ews-java-api/master/src/main/java/microsoft/exchange/webservices/data/property/complex/TimeChange.java) — main branch, undated.
6. [Oracle JDK 11 Release Notes: JEP 320 and removed modules](https://www.oracle.com/java/technologies/javase/11-relnote-issues.html) — JDK 11 changes, 2018; [JAXP JDK 25](https://docs.oracle.com/en/java/javase/25/security/java-api-xml-processing-jaxp-security-guide.html) — JDK 25 edition.
7. [eischet/ews-java-api](https://github.com/eischet/ews-java-api), [readme.md](https://raw.githubusercontent.com/eischet/ews-java-api/main/readme.md), [pom.xml](https://raw.githubusercontent.com/eischet/ews-java-api/main/pom.xml) — 2.3.2 dated 2025-12-29; [commits API](https://api.github.com/repos/eischet/ews-java-api/commits?per_page=2).
8. [jcetech/ews-java-api](https://github.com/jcetech/ews-java-api), [pom.xml dev](https://raw.githubusercontent.com/jcetech/ews-java-api/dev/pom.xml), [commits API](https://api.github.com/repos/jcetech/ews-java-api/commits?per_page=2) — last commit 2025-08-17.
9. [SpanningCloudApps/ews-java-api](https://github.com/SpanningCloudApps/ews-java-api), [pom.xml](https://raw.githubusercontent.com/SpanningCloudApps/ews-java-api/master/pom.xml), [commits API](https://api.github.com/repos/SpanningCloudApps/ews-java-api/commits?per_page=2) — last commit 2026-06-09.
10. [OfficeDev issue #692: EWS Community Project](https://github.com/OfficeDev/ews-java-api/issues/692) — 2019-03-01.
11. [Independentsoft JWebServices for Exchange](https://independentsoft.de/jwebservices/index.html) — 2.2.150, version date not stated.
12. [TuxCare PF-2697, ews-java-api 2.0-tuxcare.1](https://tuxcare.com/cve-tracker/fixes/details/pf-2697/) — 2026-05-11.
13. [Microsoft: EWS XML elements](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/ews-xml-elements-in-exchange) — 2023-03-29.
14. [Microsoft: EWS schema versions](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/ews-schema-versions-in-exchange) — publication date not established in the reviewed excerpt.
15. [Oracle Java Networking, JDK 25](https://docs.oracle.com/en/java/javase/25/core/java-networking.html) — JDK 25 edition.
16. [OpenJDK 25 GA: Unix NTLMAuthentication.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/unix/classes/sun/net/www/protocol/http/ntlm/NTLMAuthentication.java) — jdk-25-ga tag, 2025.
17. [OpenJDK 25 GA: Windows NTLMAuthentication.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/windows/classes/sun/net/www/protocol/http/ntlm/NTLMAuthentication.java) — jdk-25-ga tag, 2025.
18. [OpenJDK 25 GA: com.sun.security.ntlm.Client](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/com/sun/security/ntlm/Client.java) — jdk-25-ga tag, 2025.
19. [Oracle HttpURLConnection, JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/HttpURLConnection.html) — JDK 25 edition; setAuthenticator and streaming modes.
20. [Apache HttpComponents News](https://hc.apache.org/news.html) — 5.2 announcement dated 2022-11-10, 5.3 dated 2023-12-07; [distributions](https://downloads.apache.org/httpcomponents/httpclient/binary/) — 5.6.5 dated 2026-10-10, 5.7-beta1 dated 2026-10-22.
21. [Apache NTLMScheme 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMScheme.java) — 5.6.5 tag.
22. [Apache HttpClientBuilder 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java) — 5.6.5 tag, scheme registry and configuration methods.
23. [Apache NTLMScheme 5.7-beta1](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.7-beta1/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMScheme.java) — 5.7-beta1 tag.
24. [Apache NTLMEngineImpl 5.6.5](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.6.5/httpclient5/src/main/java/org/apache/hc/client5/http/impl/auth/NTLMEngineImpl.java) — 5.6.5 tag.
25. [Apache HttpClient 4.5 Tutorial, §4.7](https://hc.apache.org/httpcomponents-client-4.5.x/current/tutorial/pdf/httpclient-tutorial.pdf) — 4.5 branch guide, PDF version date not separately established.
26. [Apache Project Status](https://hc.apache.org/status.html) — undated; distinguishes HttpClient 4.5, HttpCore classic/NIO, and HttpAsyncClient.
27. [OkHttp Authenticator.kt](https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Authenticator.kt) — main branch, undated.
28. [Android_Ntlm README](https://github.com/netcosports/Android_Ntlm), [NTLMAuthenticator.java](https://raw.githubusercontent.com/netcosports/Android_Ntlm/master/library/src/main/java/com/netcosports/ntlm/NTLMAuthenticator.java), [NTLMEngineImpl.java](https://raw.githubusercontent.com/netcosports/Android_Ntlm/master/library/src/main/java/com/netcosports/ntlm/NTLMEngineImpl.java) — version 1.0, date not established.
29. [FasterXML jackson-dataformat-xml](https://github.com/FasterXML/jackson-dataformat-xml) — 3.x branch, undated.
30. [Spring Web Services Reference](https://docs.spring.io/spring-ws/docs/current/reference/html/) — 5.0.2, 2026-06-10, Client-side API section.
31. [php-ews services.wsdl](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/services.wsdl), [messages.xsd](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/messages.xsd), [types.xsd](https://raw.githubusercontent.com/jamesiarmes/php-ews/master/src/assets/types.xsd) — master, copy dates not established.
32. [Microsoft MS-OXWSCDATA, Full WSDL](https://learn.microsoft.com/th-th/openspecs/exchange_server_protocols/ms-oxwscdata/9328dc18-72e8-4f7c-bb9d-c6f9d5f0217c) — English specification text, updated 2020-10-14; [MS-OXWSSRCH, 2010 revision](https://officeprotocoldoc.z19.web.core.windows.net/files/MS-OXWSSRCH/%5BMS-OXWSSRCH%5D-100210.pdf) — 2010-02-05, example of a separate operations WSDL.
33. [DavMail RELEASE-NOTES.md](https://raw.githubusercontent.com/mguessan/davmail/master/RELEASE-NOTES.md) — 7.1.0 dated 2026-10-17 and 6.3–6.5 history.
34. [DavMail EWSMethod.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/exchange/ews/EWSMethod.java) — master, no separate date.
35. [DavMail EwsExchangeSession.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/exchange/ews/EwsExchangeSession.java) — master, no separate date.
36. [DavMail HttpClientAdapter.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/HttpClientAdapter.java), [pom.xml](https://raw.githubusercontent.com/mguessan/davmail/master/pom.xml) — master, no separate date; source read directly because the search cache was outdated.
37. [DavMail JCIFSNTLMSchemeFactory.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/JCIFSNTLMSchemeFactory.java), [JCIFSEngine.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/JCIFSEngine.java) — master, no separate date.
38. [DavMailNTLMScheme.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/DavMailNTLMScheme.java), [DavMailNTLMEngineImpl.java](https://raw.githubusercontent.com/mguessan/davmail/master/src/java/davmail/http/DavMailNTLMEngineImpl.java) — master, no separate date.
39. [OpenJDK 25 GA: AuthenticationFilter.java](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.net.http/share/classes/jdk/internal/net/http/AuthenticationFilter.java) — jdk-25-ga tag, 2025.
40. [Genivia gSOAP EWS example](https://www.genivia.com/examples/ews/index.html), [Services.wsdl](https://www.genivia.com/examples/ews/Services.wsdl), [messages.xsd](https://www.genivia.com/examples/ews/messages.xsd), [types.xsd](https://www.genivia.com/examples/ews/types.xsd) — date not stated, XSD Exchange2010_SP2.
41. [JCIFS-ng](https://github.com/AgNO3/jcifs-ng), [Type3Message.java](https://raw.githubusercontent.com/AgNO3/jcifs-ng/master/src/main/java/jcifs/ntlmssp/Type3Message.java), [BaseConfiguration.java](https://raw.githubusercontent.com/AgNO3/jcifs-ng/master/src/main/java/jcifs/config/BaseConfiguration.java) — latest master commit per API, 2025-11-18, `8df7abd7fbba839f7654e028853c5903be5e17d0`.
42. [Spring Framework: REST Clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) — documentation 7.0.10 reviewed; page publication date not stated.
43. [Spring Framework 7.0 Release Notes: Other removals](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/373c55a01235603a2a2ce8e9a5a67f5de6b6a70f) — document version describing removal of OkHttp3 support.
44. [Spring 7.0.10 SimpleClientHttpRequest.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/client/SimpleClientHttpRequest.java) — v7.0.10 tag, lines setting streaming mode.
45. [DavMail issue #352, Extended Protection](https://github.com/mguessan/davmail/issues/352) — closed 2024-05-10; user report, mechanism checked against code [38].
46. [Microsoft: Maintain affinity between subscriptions and Mailbox server](https://learn.microsoft.com/en-us/exchange/client-developer/exchange-web-services/how-to-maintain-affinity-between-group-of-subscriptions-and-mailbox-server) — publication date not established in the reviewed excerpt.
47. [Oracle JAXP Security Guide, JDK 25](https://docs.oracle.com/en/java/javase/25/security/java-api-xml-processing-jaxp-security-guide.html) — JDK 25 edition.
48. [Spring 7.0.10 HttpComponentsClientHttpRequest.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequest.java), [StreamingHttpOutputMessage.java](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-web/src/main/java/org/springframework/http/StreamingHttpOutputMessage.java) — v7.0.10 tag, repeatable-body contract.

# Tool-output secret replacement

Read this when changing `redaction/`, its startup flag or a tool's output filtering.
The purpose is to reduce accidental inclusion of secrets in model context, not prevent
intentional extraction. See plan.md 3.1 and the user's decision in section 5.

## Boundary and configuration

`MAIL_MCP_REDACT_SECRETS=true` (Spring property `mail-mcp.redact-secrets`) enables
`redaction/SecretRedactor` through `config/RedactionConfig`. Default false; restart the MCP
server after changing it. There is no per-call override. `MailMcpProperties.fromEnvironment`
also reads the flag; CLI commands do not apply output filtering.

Downloads, `.eml`, metadata, Markdown, attachments and the index always keep original content.
No migration, reprocessing or reindexing is needed. Do not wire the redactor into writers,
MIME parsing, conversion or synchronization. It has no network, file or logging operations.

The four tool adapters filter human-readable response values: connection descriptions and
configuration errors, folder paths, status messages, message subjects and displayed addresses,
message header values, failure descriptions, attachment display names, body/attachment text,
search subjects/senders, snippets and the query echo. Numeric values and dates are unchanged.

Connection names, local keys, stored attachment filenames (`file`), MIME content types,
`localPath`, `path` and `textPath` remain unchanged and usable. In particular, paths and stored
filenames can contain text that a detector would otherwise replace. The user explicitly allows
direct access to the unfiltered originals. Do not add opaque file handles or suppress paths.
Thrown tool errors retain existing handling; this is not a general log/exception sanitizer.

## Text views and search

`getMessage` and `getAttachment` filter the full saved text in memory, then calculate its length
and slice it. `offset`, `nextOffset`, `bodyLength` and `textLength` describe that returned view,
in UTF-16 code units. Clients should use `nextOffset` and restart paging if the server setting
changes. A tiny chunk may split a marker, but cannot reveal the original matched value.

Search still queries the raw index and keeps its ranking, score, total and pagination.
`MailIndexReader.search` accepts an optional `UnaryOperator<String>` used only to render
output. With filtering, it transforms the **complete stored body** and uses
`UnifiedHighlighter.highlightWithoutSearcher` to reanalyze the changed text. Do not filter
already shortened/highlighted snippets: their labels may be outside the passage, and `**`
can split a token. The highlighting query excludes the folder filter, which is irrelevant to
the in-memory body. Without filtering, the original highlighter path is unchanged.
Searching for a secret can therefore still find the message while its snippet hides the value.

## Rules and replacement

Own Java rules, informed by `research/RT-08-redaction-detectors.md`; no imported Gitleaks,
detect-secrets or Presidio implementation, process or dependency. The supported RT-08 families:

| Research ID | Behavior |
|---|---|
| D01, D03, D20 | RU/EN password labels, Wi-Fi PSK, environment-style keys and password fields in connection strings |
| D05 | Labeled OTP/verification codes, 4–10 ASCII digits |
| D08 | URI `user:password` userinfo, replaced as a whole; ordinary addresses/URIs retained |
| D09, D10 | Basic/Bearer Authorization headers, including Proxy-Authorization; Basic must decode to a value containing `:` |
| D11 | Compact JWS/JWE shape, Base64url segments and JSON header with `alg` (and `enc` for JWE); no signature, expiry or service validation |
| D12 | PEM private-key blocks, including RSA/EC/encrypted/OpenSSH; incomplete block hidden through end of text |
| D14 | GitHub token shapes (`gh[pousr]_` and `github_pat_`); no checksum/service verification |
| D18 | Labeled API keys, tokens and secrets, including access/refresh tokens and client secrets |

Labeled values support quotes (including escaped/doubled quotes and Russian quotes), tabs,
simple pipe tables, JSON-style assignments and XML leaves. Unquoted ambiguous values extend
to the field boundary; this can hide nearby prose. A semicolon/ampersand/comma followed by an
assignment starts another field; semicolons inside a quoted value are preserved as part of it.
No entropy threshold rejects short passwords. `${VAR}`, masked stars and existing replacement
markers are left alone. Labels without a field separator do not trigger password replacement.

Markers are `[redacted:password]`, `[redacted:code]`, `[redacted:credentials]`,
`[redacted:token]`, `[redacted:private-key]` and `[redacted:secret]`. They contain no source
value, suffix, hash or original length. Overlapping findings are replaced once using the union
of ranges; the largest finding supplies the type. Adjacent findings remain separate.

Two disputed research policies are implemented explicitly: an indented next line may contain a
labeled password; a JWT may continue onto indented lines only if the joined candidate passes
structural validation. There is no arbitrary mail/token unfolding or full JSON/XML/YAML parser.
Quoted multiline passphrases, unusual provider formats, images, encoded/obfuscated secrets and
unlabeled short passwords are not comprehensively covered. False positives and misses remain
possible; this is best-effort context hygiene, not a guarantee that text contains no secrets.

PII detectors D22/D23/D25/D28, phone/address anonymization, entropy-only detection, OCR,
secret verification and filtering raw files are outside Д6.

## Verification

`SecretRedactorTest` reads every one of the 256 detector cases and eight mixed documents in
the unchanged `testdata/redaction` set. It compares exact replaced text using the secrets-only
subset of expected ranges, merging overlaps. PII-only findings must remain unchanged.
Intentional expectation differences: D12-N03 and D12-N05 are hidden because their private-key
blocks are incomplete/mismatched. Tests also cover long values, boundaries and idempotence.

`OutputRedactionTest` uses synthetic MIME and local data: three-character paging, headers,
metadata, discovery, sync status, search/highlighting and on/off behavior. It compares all
local files (including the index) byte for byte and verifies original paths still work.
`McpStdioServerTest` runs the real Spring/stdio server with the flag both false and true and
checks both structured and text content. All checks are offline; no real-mailbox validation
has been performed for this filter.

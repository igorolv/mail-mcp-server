# IT-12 — Optional secret redaction in tool output (Д6)

## Goal and fixed decisions

Reduce accidental inclusion of secrets in model context. The user's clarification on
2026-10-26 explicitly keeps downloads, disk files and the index unfiltered. This is not an
access-control boundary: preserve original paths, stored filenames and lookup identifiers.
Do not contact mailboxes or commit. Implementation choices are delegated to this session.

## Read first

`AGENTS.md`, `docs/plan.md`, `docs/impl-tasks/README.md`, `docs/architecture-agent.md`,
`docs/build-config-agent.md`, `docs/tools-agent.md`, `docs/storage-agent.md`,
`docs/research/RT-08-redaction-detectors.md`, `docs/research/RT-12-redaction-samples.md`,
`testdata/redaction/README.md`, the tool classes and their existing tests.

## What to build

- One startup setting, `MAIL_MCP_REDACT_SECRETS`, default false, shared properties/environment
  binding. No per-call bypass or new tool.
- Local Java detectors based on RT-08: labeled RU/EN passwords (including Wi-Fi and connection
  strings), OTP, URI userinfo, Basic/Bearer, JWT/JWE, PEM private keys, GitHub tokens and labeled
  API secrets. No new dependencies or copied third-party rule implementation.
- Full replacement with typed markers; merge overlapping ranges. Do not expose matched values
  in logs or diagnostics. Keep placeholders and ordinary business prose intact.
- Apply to human-readable tool response fields, including subjects, displayed addresses,
  folder names, attachment display names, search query echo, status and descriptions.
  IDs, original file paths and stored attachment filenames remain usable and unchanged.
- Filter complete bodies/attachment Markdown in memory before chunking. Offsets and lengths
  refer to the filtered view when enabled. Filter the complete stored body before search
  passage selection/highlighting; search matching, ranking and counts remain raw.
- Keep all existing behavior when disabled. Do not filter storage, indexing, sync or CLI output.
- Decide disputed fixtures explicitly: accept an indented next line after a password label;
  accept indented JWT continuation only when it yields a structurally valid token. Hide an
  incomplete private-key block through the end of the available text (two intentional differences
  from the old research expectations). No PII detectors D22/D23/D25/D28 in this secrets-only task.

## Tests

- `SecretRedactorTest.controlSet`, `mixedDocuments`: compare exact replaced ranges against the
  secrets subset of the unchanged research fixtures; document intentional PEM differences.
- `SecretRedactorTest.boundariesAndLongValues`, `overlapsAndIdempotence`: quotes, structured
  fields, long inputs, short passwords, malformed tokens, overlapping rules and placeholders.
- `OutputRedactionTest.chunksUseFilteredOffsetsAndLeaveFilesUntouched`,
  `searchFiltersBeforeHighlightingAndKeepsRawMatches`, `metadataAndDiscoveryAreFiltered`:
  synthetic local data only, on/off behavior and unchanged disk/index/paths.
- `MailMcpPropertiesTest.redactionSetting`: default and environment binding.
- MCP stdio coverage with filtering enabled and disabled, including text/structured output.
- Run `gradlew.bat build`, count tests/skips/failures, check diff for real environment values.

## Files and documents

Create `src/main/java/mail/mcp/redaction/SecretRedactor.java`,
`src/main/java/mail/mcp/config/RedactionConfig.java`, `docs/redaction-agent.md`, and the three
test classes named above (under `redaction/`, `tools/`, `config/`). Change `MailMcpProperties`,
`application.yml`, the four `tools/*Tools.java` classes, `index/MailIndexReader.java`,
`api/GetMessageResult.java`, `api/GetAttachmentResult.java`, and `McpStdioServerTest.java`.
Update `README.md`, `docs/{architecture,build-config,tools,storage}-agent.md`.
Brief preparation/recording also updates `AGENTS.md`, `docs/plan.md` and the task list here.
Keep Д6 in progress pending separate review; retain this brief. Do not edit research or fixtures.

## Do not

No PII anonymization, OCR, entropy-only scanner, external process/service, secret verification,
original-file restrictions, new storage/schema, MIME changes, mailbox access or commits.

## Implementation result (2026-10-26)

Implemented with no scope deviations or unresolved questions. `gradlew.bat build` passes:
525 tests, 1 skipped by design (`McpStdioConcurrencyTest.simultaneousToolCompletionsDeliverEveryResponse`),
0 failures/errors. Includes all 256 research cases, eight mixed documents, synthetic output
tests and real stdio transport tests with filtering on/off. Local data and index remained
byte-identical in the output tests. Diff checks found no real environment values or whitespace
errors; document links exist. No scripts changed. No real-mailbox check or commit performed.

To enable: set `MAIL_MCP_REDACT_SECRETS=true` in the MCP server environment and restart it.
Default false. Reindexing/reprocessing is unnecessary. Separate review is pending; retain this
brief until acceptance. The two PEM expectation differences and secrets-only scope are intentional
policies above, not fixture edits.

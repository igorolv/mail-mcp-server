# Research

Research results from 2026-10-24. These are reference materials as of the research date: resulting
decisions are in [decisions.md](../decisions.md), and the current design is in `docs/*-agent.md`.
Some conclusions have been revised: SQLite, Mime4j, filtering before indexing. References to
`plan.md` sections inside the reports refer to its structure at the time. Brief paths are
historical references; those files are no longer in the current tree.

| File | Topic | Useful for |
|---|---|---|
| [RT-01](RT-01-mail-mcp-servers.md) | Existing mail MCP servers | Д15 |
| [RT-02](RT-02-ews-java-client.md) | EWS client and NTLM in Java | Д23 |
| [RT-03](RT-03-ews-operations.md) | EWS operations, allow-list, throttling | Д20 |
| [RT-04](RT-04-imap-gmail-mailru.md) | Gmail and Mail.ru IMAP, sign-in, extensions | Д7 |
| [RT-05](RT-05-sibling-code-reuse.md) | What to reuse from sibling projects | — |
| [RT-06](RT-06-search-engine.md) | Search engine and morphology | word forms (`docs/search.md`), Д13 |
| [RT-07](RT-07-embeddings.md) | Embedding models | Д13 |
| [RT-08](RT-08-redaction-detectors.md) | Secret detectors | response filter (`docs/redaction-agent.md`) |
| [RT-09](RT-09-mime-attachments.md) | MIME and attachment parsing | attachments and containers (`docs/mime-agent.md`) |
| [RT-10](RT-10-mcp-resources.md) | MCP resources in clients | Д14 (rejected) |
| [RT-11](RT-11-synthetic-mime.md), [RT-12](RT-12-redaction-samples.md), [RT-13](RT-13-search-queries.md) | How the `testdata/mime`, `testdata/redaction` and `testdata/search` sets were created | parsing and filtering tests |

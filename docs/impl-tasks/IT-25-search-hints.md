# IT-25 — Search hints for the agent (Д26)

Prepared: 2026-10-27. Baseline: `d725d93`, plus accepted IT-23 and IT-24. Implement last in the
batch. See the batch order in this directory's README.

## Goal

The client of `search` is an AI agent. It can recover from vocabulary misses by itself if the
tool description tells it what the analyzer cannot do. Rewrite the hints in the `search`
description; no behavior changes.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.5, 3.7 and Д26 in section 4.
- `docs/tools-agent.md` (Search), `README.md` (the word forms section added by IT-24).
- `src/main/java/mail/mcp/tools/SearchTools.java`.
- `src/test/java/mail/mcp/tools/SearchToolsTest.java`, `src/test/java/mail/mcp/McpStdioServerTest.java`.

## What to build

1. In the `@McpTool` description of `SearchTools.search`, replace the sentence
   "Russian and English word forms are matched;" with text that says, in this order:
   - regular Russian and English word forms are matched;
   - irregular Russian forms (for example `письмо`/`писем`, `человек`/`люди`, `год`/`лет`) and
     synonyms match only when listed in the user's optional `word-forms.txt`;
   - when results are empty or look incomplete, retry with other word forms, synonyms, an English
     variant, fewer words, another `field` or date bounds before concluding that the mail is
     absent;
   - when a missing form is likely to recur, suggest that the user adds a group to
     `word-forms.txt` and runs `mail-reindex`; never edit the file without the user's consent.
2. Keep every other statement of the description, including the IT-23 sentence about copies and
   the existing order of the remaining text. Keep it plain prose without Markdown. Cyrillic
   examples are allowed (sources are UTF-8).
3. Do not change parameter descriptions, the output schema or any other tool.

## Named tests

- `SearchToolsTest.searchDescriptionSuggestsRetriesAndWordForms`: reads the `@McpTool`
  description by reflection and checks the irregular-form examples, `word-forms.txt`,
  `mail-reindex`, and the retry advice.

Keep the existing `McpStdioServerTest` assertions passing; extend one only if it compares the
whole description.

## Files

Change only:

- `src/main/java/mail/mcp/tools/SearchTools.java`
- `src/test/java/mail/mcp/tools/SearchToolsTest.java`
- `src/test/java/mail/mcp/McpStdioServerTest.java`, only if needed as described above
- `docs/tools-agent.md`

## Documents

`docs/tools-agent.md` (Search): one sentence that the description tells the agent how to retry and
when to suggest a word forms entry. Recording updates plan.md.

## Do not

Do not add query expansion, synonym handling in code, a retry loop, new parameters, new tools or
any behavior change. Do not translate or reword unrelated parts of the description.

## Acceptance

Run `.\gradlew.bat build` and the shared checks. No real-mail check is needed; the user may
observe in an MCP client that the agent retries after an empty result.

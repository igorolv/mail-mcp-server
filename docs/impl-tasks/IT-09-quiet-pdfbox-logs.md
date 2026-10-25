# IT-09 — quieter PDFBox logs

Improvement: `docs/plan.md` section 4, Д1. Size: S, a short session.

## Goal

`mail-reprocess` and `mail-sync` print many `WARN` lines from PDFBox and FontBox about damaged
but readable PDFs, for example:
- `found wrong object number`;
- `Using fallback font`;
- `Invalid ToUnicode CMap`;
- `Format 14 cmap table is not supported`.

The text is still extracted, and the lines only bury the command's own report. Raise both
libraries to `ERROR` in both logging configurations.

## Read first

1. `src/main/resources/logback-spike.xml`: used by the user-run commands; logs to stderr only.
2. `src/main/resources/logback-spring.xml`: the MCP server; logs to a file and to stderr.
3. `docs/architecture-agent.md`, the paragraph about `logback-spike.xml`.

## What to build

In each of the two files, next to the existing `<logger … level="OFF"/>` lines, add:

```xml
<logger name="org.apache.pdfbox" level="ERROR"/>
<logger name="org.apache.fontbox" level="ERROR"/>
```

Add one XML comment above them: damaged but readable PDFs produce warnings, and the text is still
extracted.

## Tests

No new test. `.\gradlew.bat build` passes with the same number of tests as before.

## Do not

- Change any other logger, level, appender or pattern.
- Touch Java code.

## Files

Change:
- `src/main/resources/logback-spike.xml`
- `src/main/resources/logback-spring.xml`
- `docs/architecture-agent.md`

## Documents

`docs/architecture-agent.md`: in the sentence that lists what the logging configurations switch
off, mention that PDFBox and FontBox log only errors.

## Done when

- `.\gradlew.bat build` passes.
- `git status` shows only the three files.
- The report lists under "For the user to run": nothing required. Optionally run
  `java -jar build\libs\mail-mcp-server.jar mail-reprocess work`; it prints no PDFBox `WARN`
  lines.

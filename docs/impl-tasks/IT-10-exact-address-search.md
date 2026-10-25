# IT-10 — exact address search

Improvement: `docs/plan.md` section 4, Д2. Size: S, a short session.

## Goal

`search` with `field: from` and the query `alice@example.org` also finds messages whose sender's
display name is `alice@example.org` while the real address is different. Some senders put an
address into their display name. On the user's mailbox this produced 2 wrong hits out of 100.

When the whole query is one full e-mail address, the search must not look at display-name fields.

## Read first

1. `src/main/java/mail/mcp/index/MailIndexReader.java`, method `search`.
2. `src/main/java/mail/mcp/index/SearchField.java`, `MailAnalyzers.java`.
3. `src/main/java/mail/mcp/tools/SearchTools.java`, the tool description.
4. `src/test/java/mail/mcp/index/MailIndexReaderTest.java` (its helper `put`),
   `SearchControlSetTest.java`.
5. `docs/tools-agent.md`, the section about `search` and its field groups.

## Current code facts

- `search` builds a `SimpleQueryParser` over `field.fields()`, with equal weights.
- The name fields are `fromName`, `toName` and `ccName`. They use the Russian analyzer, which
  splits `alice@example.org` into `alice` and `example.org`. That is why a display name
  containing the address matches.
- The address fields `fromAddress`, `toAddress` and `ccAddress` hold the whole lowercased address
  as one term.

## What to build

In `MailIndexReader.search`:

1. Decide whether the query is a full address. It is when `query.strip()` matches
   `[^\s"@]+@[^\s"@]+\.[^\s"@]+` as a whole: one token, no quotes, no spaces.
2. If it is, build the parser over `field.fields()` **without** the three name fields `fromName`,
   `toName` and `ccName`. All other fields stay, including `body` for `any`: an address written in
   a body still matches through the body field.
3. Otherwise nothing changes.

Keep the rule inside `MailIndexReader`: a private static method plus a constant `Pattern`. No new
type, no new setting.

`SearchTools` description: after "full addresses must be complete", add "; a query that is a
single full address is matched against addresses only, not display names".

## Tests

`index/MailIndexReaderTest` (add):
- `fullAddressQueryIgnoresDisplayNames`. Index two messages:
  - one with sender `Address("alice@example.org", "mallory@example.net")`;
  - one with sender `Address("Alice", "alice@example.org")`.

  `from` and `any` for `alice@example.org` find only the second. `from` for `alice` still finds
  both: a plain word searches names.
- `fullAddressQueryStillFindsTheAddressInTheBody`: `any` with a full address finds a message that
  mentions it only in its body.

The control set must still give recall 79/86 with 7 false positives (`SearchControlSetTest`).

## Do not

- Change the analyzers, the index fields or the schema version (no reindex is needed).
- Add query syntax, field boosts or a setting.

## Stop and ask when

The control-set numbers change.

## Files

Change:
- `src/main/java/mail/mcp/index/MailIndexReader.java`
- `src/main/java/mail/mcp/tools/SearchTools.java`
- `src/test/java/mail/mcp/index/MailIndexReaderTest.java`
- `docs/tools-agent.md`

## Documents

`docs/tools-agent.md`: next to the field-group table, one sentence on the full-address rule.

## Done when

- The named tests exist and pass; `.\gradlew.bat build` passes; the control-set numbers are
  unchanged.
- `git status` shows only the four files.
- The report lists under "For the user to run": restart the `mail` server in the MCP client, then
  `search` with `field: from` and the user's own full address on `mailru`. The first page no longer
  contains messages whose sender address is different.

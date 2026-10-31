# Synthetic search control set

All 48 messages are invented. Addresses use reserved example domains. No mail was sent.
The corpus is independent of RT-11 and RT-12. EML bytes use CRLF; JSON uses UTF-8/LF.
Ideal relevance was authored separately from Snowball predictions. The current production
control-set results and accepted differences are maintained in
[docs/tools-agent.md](../../docs/tools-agent.md#search).

## Files and fields

`manifest.json` lists every file including itself (its own hash is intentionally omitted).
`corpus.json`: messages with id, file, subject, from, to, body, attachments, sha256, terms, note.
Attachments have name and decoded text. `terms` gives topic hints; full text is in the other fields.
The two redaction cases retain redactions (match, detector) and redactedBody from the original
fixture proposal. These fields do not prescribe the production indexing policy.
These values are intentionally fictitious and cannot be used to sign in.
`queries.json`: id, query, lang, category, field, expected, mustNotMatch, rankFirst,
snowball, basis, note, evidence and nearMissEvidence.
`evidence` maps each expected message to a literal form in the requested field.
`nearMissEvidence` does the same for excluded messages. These are data checks, not a search engine.
`snowball` is hit/miss/unknown; unknown means no analyzer behavior is claimed.
`rankFirst` is null unless a unique ranking expectation is justified.

## Proposed query contract

Fields are a test proposal, not the final tool API: any, subject, body, from, to,
attachmentName, attachmentText. Production indexes original text; `any` searches the configured
field groups. The current tool API, including labels, is in [docs/search.md](../../docs/search.md).
Quoted queries require adjacent words in order (phrase semantics).
Mixed language multiword queries require all terms; no cross-language translation is expected.
Stopword-only queries return nothing. Words are case-insensitive; Russian ё and е are equivalent.
Full addresses are exact terms. Local-part-only and domain-only queries do not match a full address;
display names are analyzed separately. Wildcards and fuzzy matching are outside this set.
File names are indexed whole and as component tokens, including extension and year.
Name cases should match; diminutives are separate names (Анна vs Аня).
The contextual precision query about вина uses its second word to disambiguate the sense.

## Scoring and validation

Expected is the ideal required set, independent of analyzer limitations. Missing expected IDs are misses.
Returned mustNotMatch IDs are false positives. Unlisted IDs are not scored.
Recall = returned expected IDs / expected IDs, aggregated across nonempty expected lists.
False positives = count of returned IDs in mustNotMatch. Empty-expected cases test only exclusions.
Keep scores per category and separately report phrase, address and redaction contract violations.
Production indexes raw synthetic text; optional secret replacement applies only to tool output.
Q079/Q080 retain their superseded redaction-before-index expectations in the byte-exact fixture
data. Their raw matches are counted in the accepted false-positive baseline; do not change the
indexing policy to satisfy those expectations.
Generator checks decoded bodies, attachment names/text, evidence, references, hashes and category minima.
This validates fixtures; it does not demonstrate RussianAnalyzer quality or final detector behavior.

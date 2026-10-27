# RT-12 — Synthetic redaction samples

- Date: 2026-10-25
- Author: Codex
- Brief: docs/research-tasks/RT-12-redaction-samples.md
- Data: testdata/redaction/

## Summary

- [code] Created 15 detector files, 256 cases and 8 mixed documents.
- [code] Every file has at least 8 positive and 8 negative cases.
- [code] Checksums, occurrences and merged replacement results were verified with Python.
- [code] All values are invented; no real secrets, messages or people were used.
- [assumption] The full expected-findings list is manually annotated; detectors are not yet implemented.

## Set contents

| Detector | Positive | Negative | Variants | Gaps |
|---|---:|---:|---|---|
| D01 Passwords | 11 | 8 | backticks, boundary, card, config, disputed, env, escaped-quote, json, line-break, look-alike, next-field, overlap, pipe, quoted-reply, quotes, tab, table | Arbitrary line-break recovery is unspecified |
| D03 Wi-Fi passwords | 8 | 8 | boundary, field, look-alike, network-context, quotes, table | Arbitrary line-break recovery is unspecified |
| D05 Verification codes | 8 | 8 | boundary, label, look-alike, quotes, table | Arbitrary line-break recovery is unspecified |
| D08 URI credentials | 8 | 8 | boundary, look-alike, percent-encoding, uri | Arbitrary line-break recovery is unspecified |
| D09 HTTP Basic | 8 | 8 | base64, boundary, header, look-alike | Arbitrary line-break recovery is unspecified |
| D10 HTTP Bearer | 9 | 8 | boundary, header, jwt, look-alike, overlap, quotes, unquoted | Arbitrary line-break recovery is unspecified |
| D11 JWT and JWE | 9 | 8 | base64url, boundary, disputed, jwe, jws, line-break, look-alike, wrapped-token | Arbitrary line-break recovery is unspecified |
| D12 Private key blocks | 8 | 8 | boundary, look-alike, multiline, pem | Arbitrary line-break recovery is unspecified |
| D14 GitHub tokens | 8 | 8 | boundary, deliberately-invalid-checksum, look-alike, provider-shape | Arbitrary line-break recovery is unspecified |
| D18 Generic API keys | 11 | 8 | boundary, config, ini, json, label, look-alike, table, yaml | Arbitrary line-break recovery is unspecified |
| D20 Connection strings | 9 | 8 | ado-net, boundary, jdbc, look-alike, next-field, overlap, quotes, uri | Arbitrary line-break recovery is unspecified |
| D22 Russian passport | 8 | 8 | boundary, label, look-alike, no-checksum | Arbitrary line-break recovery is unspecified |
| D23 SNILS | 10 | 9 | boundary, checksum, disputed, historical-range, label, look-alike, unlabelled | Arbitrary line-break recovery is unspecified |
| D25 Individual INN | 9 | 9 | boundary, checksum, disputed, label, look-alike, unlabelled | Arbitrary line-break recovery is unspecified |
| D28 Card number | 9 | 9 | boundary, checksum, disputed, label, look-alike, unlabelled | Arbitrary line-break recovery is unspecified |

### Mixed documents

- `X01`: D01, D05, D08.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X02`: D03, D18, D10.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X03`: D22, D23, D25.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X04`: D09, D11, D20.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X05`: D14, D12, D18.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X06`: D28, D01, D20.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X07`: D23, D25, D08.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.
- `X08`: D10, D05, D03.
  Two copies of the first finding test occurrence; a quote, table and safe order number are added.

## File generation

- Python 3.14.3, using only json, random, re, base64, hashlib, zlib.
- Generator: `testdata/.work/RT-12/generate.py`, not tracked by git.
- A fixed random seed makes the invented values reproducible.
- SNILS: a weighted sum of nine digits and `(S % 101) % 100`.
- INN-12: two check digits using the weights from RT-08 В3.
- Card number: a random suffix after 999999, with the final digit selected using Luhn.
- The prefix is not claimed to be an official test BIN: the number is explicitly educational.
- PEM blocks contain Base64 of NOT_A_DER_KEY text, not ASN.1 DER.
- JWT and JWE contain invented JSON and bytes, not a valid signature or ciphertext.
- For ghp_, the checksum suffix is zero, and the random part's CRC32 was confirmed nonzero.
- The fine-grained token checksum was not independently confirmed: to verify; the token was never issued.
- No network token validation and no third-party package installation.

## Verification

- [code] Every JSON file was parsed again by a shared validator.
- [code] Each finding exists in text at least as many times as its occurrence number specifies.
- [code] All checksum fields agree with calculations on the original digits.
- [code] Invalid checksums were made by changing a valid value's final digit.
- [code] INN-12 values were also checked to exclude accidental Luhn matches and avoid D28.
- [code] Every mixed text has 10 to 40 lines.
- [code] redactedText was produced by merging overlaps and replacing in reverse order.
- [code] Per-detector minimums were checked; SHA-256 and sizes were recorded in the manifest.
- [code] Each mixed document has a matching expected.json.
- Detector behavior was not measured: implementation is outside the task.
- No external secret scanners were run.

## Disputed cases

- `D01-P10`: label and value are separated by a newline: stricter refusal is possible; policy is undecided.
- `D11-P09`: token boundaries after a line break are ambiguous. Redacting the entire folded string is a proposal pending a policy choice.
- `D23-P09`: explicit label with invalid checksum: redaction is proposed, not approved.
- `D25-P09`: explicit label with invalid checksum: redaction is proposed, not approved.
- `D28-P09`: explicit label with invalid checksum: redaction is proposed, not approved.

- Several matching rules do not mean several replacements: redact the union of ranges.
- For equal lengths, use the lowest ID; this is a fixture convention, not a product decision.
- D01 and D20 deliberately overlap in connection string Password fields.
- Labeled SNILS/INN pairs with invalid checksums are marked as low-confidence proposals.
- The historical-range SNILS example is not falsely labeled valid.

## What could not be done

- Check the current internal github_pat_ checksum: mark "to verify".
- Run actual detectors: they do not yet exist in the project.
- Broken-token recovery is represented by a disputed case; no general rule is specified yet.
- Such breaks must not be joined without a rule distinguishing wrapping from the end of a value.
- The set covers XML, JSON, .env, connection strings, tables and quotes, not every configuration grammar.
- The matrix of all quote and format combinations is not exhaustive.

## Further research

- During implementation, compare misses and false positives separately by detector and variants.
- Agree on policy before adding disputed cases to mandatory tests.
- Define INI/YAML parsing and long mail field folding rules separately.

## Sources

- docs/research/RT-08-redaction-detectors.md, В2–В4 and catalog; read on 2026-10-25.
- GitHub, Behind GitHub’s new authentication token formats — https://github.blog/engineering/platform-security/behind-githubs-new-authentication-token-formats/ (read on 2026-10-25).

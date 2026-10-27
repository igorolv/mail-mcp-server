# RT-08 — Secret and personal-data detectors

- Date: 2026-10-24
- Author: Codex / session RT-08
- Brief: `docs/research-tasks/RT-08-redaction-detectors.md`

## Summary

- [code] Gitleaks provides rules for tokens, keys, and general secret assignments; detect-secrets provides plugins, entropy checks, and exclusions. These are sources of rules, not a ready-made email filter [1–7].
- [docs] MIT for Gitleaks and Presidio and Apache-2.0 for detect-secrets allow reuse subject to their conditions; individual detect-secrets files contain additional MIT notices [3, 5, 8, 9].
- [docs][code] Presidio separates recognition and replacement; English is configured by default. No ready-made Russian passport, SNILS, INN, or OMS recognizers were found in the reviewed list [10–13].
- [assumption] Email requires custom Russian and English labels and extraction of the value only: quotation marks, delimiter, and next-field boundary; entropy must not reject short passwords.
- [docs][code] Check digits are available for SNILS, INN, OGRN/OGRNIP, cards, and OMS; an account number is checked together with BIC. They validate structure, not the owner or whether the item was issued [17–23].
- [assumption] The catalog below contains 34 detectors. The MVP recommendation is 15 specific rules listed in the “Recommendation” section; enable the rest after evaluating errors.
- [assumption] Default replacement: `[redacted: password; message=m-001; date=2026-03-12]`. Type and reference preserve meaning; the original value, its length, and its last characters are not passed through.
- [docs][assumption] Phileas is an existing Java library; Commons Validator and libphonenumber are suitable for specific checks. A complete ready-made set for Russian email is unconfirmed [14–16].
- [docs][assumption] Tables require relationships between cells; scanned documents require OCR. The absence of extracted text must not be treated as proof that sensitive data is absent [24, 25].

## Answers to the questions

### В1. Existing rule sets, Java, and licenses

**Gitleaks.** [docs] Rules are described in TOML: `[[rules]]`, `id`, `description`, `regex`, `secretGroup`, `entropy`, `keywords`; path restrictions and exclusions are supported. `secretGroup` specifies the captured secret group, and entropy is evaluated for that group [1]. [code] The `v8.30.1` tag was checked in `config/gitleaks.toml`, not only the mutable branch [2].

[code] Useful families include `github-pat`, `github-fine-grained-pat`, `gitlab-pat`, `aws-access-token`, `generic-api-key`, `jwt`, `private-key`, and provider-specific rules. The generic rule requires a sufficiently long value and entropy; it does not replace finding a short human password by a Russian label [2, 6]. [assumption] Parse connection strings separately: one generic API-key pattern does not guarantee extraction of every URI, JDBC, or ADO.NET field.

[docs] As of the access date, the Gitleaks README states that feature development is complete and future releases are limited to security fixes; `v8.30.1` is visible on the releases page [1, 26]. [assumption] This is a reason to pin the rule version and track token-format changes, not to import the entire configuration of a repository scanner automatically.

**detect-secrets.** [docs] Python plugins detect candidates and filters reduce noise; available plugins include `KeywordDetector`, `BasicAuthDetector`, `PrivateKeyDetector`, `JwtTokenDetector`, `AWSKeyDetector`, `GitHubTokenDetector`, `GitLabTokenDetector`, and others [4]. [code] `Base64HighEntropyString` uses a threshold of 4.5, `HexHighEntropyString` uses 3.0; ordinary scanning targets quoted strings, with an adjustment for numeric strings [7]. [code] The `KeywordDetector` dictionary includes `password`, `passwd`, `pwd`, `secret`, `api_?key`, and other labels, but no Russian `пароль` or `код подтверждения` [6]. [docs] Release 1.5.0 was published on 2024-05-06; `master` sources may differ from it [27].

[docs] detect-secrets provides a network check for discovered secrets [4]. [assumption] For a future local filter, borrow only offline checks; do not test discovered credentials by signing in to a service. No tools were run in this session.

**Presidio.** [docs] `EntityRecognizer` is a Python object that returns an entity type, boundaries, and confidence score. `PatternRecognizer` supports patterns and value lists; context, result validation, and custom or external recognizers can be added [10]. [docs] Entities include `CREDIT_CARD`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `IBAN_CODE`, `DATE_TIME`, and `IP_ADDRESS`, as well as names and locations detected using language models [11]. ML-based name recognition is noted here only; comparing models is outside scope.

[docs][code] `en` is selected by default; adding a language requires adapting the language module and recognizers, especially context words. The mere availability of a Russian language model does not add check digits for Russian documents [12, 13]. [code] The reviewed `default_recognizers.yaml` and entity list contain no Russian passports, SNILS, INN, OGRN, or OMS [11, 13]. [assumption] Russian is possible with configuration and custom rules, but there is no basis to treat it as fully ready for our task.

**What is available in Java.**

| Solution | Confirmed capability | Limitation for this task |
|---|---|---|
| Phileas | [docs] Java library for PII detection and replacement; policies, custom identifiers and validators, including Luhn [14] | [assumption] Candidate for interface study; ready-made Russian rules and Java 25 compatibility unverified |
| Apache Commons Validator | [docs] `LuhnCheckDigit`, `CreditCardValidator`, and separate format validators [15] | [assumption] Building block; does not extract secrets from Russian email |
| Google libphonenumber | [docs] Java, `findNumbers`, `isPossibleNumber`, `isValidNumber`, length and prefix metadata [16] | [assumption] Checks phone format, not whether it belongs to a person |
| Gitleaks / detect-secrets / Presidio | [code][docs] Go / Python / Python respectively [1, 4, 10] | [assumption] Reusing ideas and rules is simpler for an MVP than requiring an additional process |

**Reuse conditions.** [docs] The conditions below come from the licenses themselves; when reusing code, preserve notices for the specific file, not only the project name [3, 5, 8, 9, 28].

| Source | License | Practical conclusion |
|---|---|---|
| Gitleaks, including reviewed configuration | [code] MIT [3] | [docs] Modification and redistribution permitted if copyright and permission text are retained |
| detect-secrets | [code] Apache-2.0 [5] | [docs] Retain license and notices, mark changes, and account for `NOTICE` if it is present in reused material |
| `plugins/keyword.py`, `plugins/private_key.py` | [code] Contain embedded MIT notices for original reuse [6, 8] | [assumption] Preserve these notices too if copying the files directly |
| Presidio | [code] MIT [9] | [docs] Same notice-retention conditions as above |
| Phileas | [code][docs] Apache-2.0 starting with 2.2.1; earlier versions had a different license [14, 28] | [assumption] Check the license of the selected tag and any additional models separately |

[assumption] The patterns below are original sketches based on source ideas, not ready-made verbatim copies. Before implementation, pin the versions of reused material and record the provenance of each rule.

### В2. Secrets in email and value boundaries

[assumption] Label dictionary: `пароль`, `временный пароль`, `пароль от Wi-Fi`, `логин`, `пин-код`, `код подтверждения`, `одноразовый код`; English equivalents: `password`, `temporary password`, `passwd`, `pwd`, `PIN`, `OTP`, `verification code`. Access labels: `VPN`, `RDP`, `удалённый доступ`, `remote desktop`. The assignment-search idea comes from `KeywordDetector`; Russian extensions are proposed here [6].

[assumption] Recommended extraction order:

1. Find the label as a separate word/field, without crossing a paragraph or cell.
2. After `:` or `=`, allow horizontal whitespace only.
3. If the value is quoted, capture through the matching quote, accounting for escaping.
4. If unquoted, capture one bounded fragment up to whitespace, a delimiter, or the next label.
5. An unquoted password containing spaces is ambiguous: look for the next field boundary; if absent, redact the entire bounded field fragment and mark the ambiguity.
6. Replace only the value range, preserving `логин:`, `пароль:`, and surrounding business text.

[assumption] Sketch for the simple case, with separate rules for quoted and unquoted values:

```text
(?iu)(?:пароль|password|passwd|pwd)[ \t]*[:=][ \t]*"(?<value>[^"\r\n]{1,256})"
(?iu)(?:пароль|password|passwd|pwd)[ \t]*[:=][ \t]*(?<value>[^\s;,"<>]{1,128})
```

[assumption] These are not ready-to-use regular expressions: add left boundaries for labels, stop before the next field, handle escaping and single quotes. Reaching the length limit means the boundary is uncertain; it does not authorize leaving the remainder visible. Such a field needs a fallback redaction; do not simply truncate the match at 128 characters.

[assumption] `;` can be part of a password. Delimiters are more reliably identified from the format grammar: field pairs, quotes, JSON, XML, URI. There is no universally safe pattern for an arbitrary sentence; `пароль:.*` consumes unrelated text. In `логин: user; пароль: [значение]; срок: 2026-03-12`, redact only the password field; `[значение]` is a placeholder, and no actual password is shown.

[docs] A URI permits `userinfo`; the `user:password` format is deprecated but still occurs [29]. [docs] HTTP Basic encodes the username/password pair in Base64 [30]. [assumption] In a URI, redact all of `userinfo`; in Basic, redact the entire encoded fragment. Decoding is allowed only inside validation; do not show the decoded value in a report.

[docs] A JWT can represent JWS or JWE [31]. [assumption] Look for three JWS segments and separately five JWE segments; validate Base64url/header JSON with limits. Do not verify the signature; expiration does not exempt a token from redaction, since its contents and old tokens may still be sensitive. Redact a PEM key as a whole block, not just the `BEGIN` line [8].

### В3. Russian personal data: formats and checks

[assumption] General normalization: preserve original positions; remove only permitted spaces/hyphens inside a candidate; prohibit extracting a substring from a longer number. Use ASCII digits and integer arithmetic for check digits. Do not silently convert OCR `О→0`: treat it as a separate, lower-confidence candidate.

**Russian passport.** [docs] The form description specifies four series digits and six number digits [17]. [assumption] Look for a contiguous value or groups `2+2+6` near `паспорт`, `серия`, `номер`. A check digit for this printed combination is not established in the description; do not substitute validation of the document's machine-readable line. No strict check that “series year equals issue year” is proposed.

**SNILS.** [docs] For nine digits `d1…d9`, calculate `S = Σ(di × (10−i))`. The two check digits are `(S mod 101) mod 100`, with a leading zero. Sums of 100 and 101 produce `00`; the historical description limits validation to numbers above `001-001-998` [18]. [assumption] For earlier numbers, the result is “check digit not validated,” not automatic filter bypass: still redact when labelled SNILS.

**INN.** [docs] The Federal Tax Service confirms 10 digits for an organization and 12 for an individual; its 2025-11-07 explanation says the check method does not change as of 2026-01-01 [19]. [code] The following weights were checked in `stdnum/ru/inn.py` [20]:

- INN-10: first 9 digits × `[2,4,10,3,5,9,4,6,8]`; `(sum mod 11) mod 10` = digit 10.
- INN-12: first 10 × `[7,2,4,10,3,5,9,4,6,8]` produce digit 11 by the same method; first 11 × `[3,7,2,4,10,3,5,9,4,6,8]` produce digit 12.

[assumption] An organization's INN is useful in business correspondence; make it and OGRN separately configurable for redaction. The INN-12 check is stronger than merely counting digits, but technically repeated values require separate consideration.

**OGRN / OGRNIP.** [docs] Under paragraph 7 of the Ministry of Finance procedure: 13 / 15 digits, check digit = `(first 12 mod 11) mod 10` / `(first 14 mod 13) mod 10`. Initial OGRN indicators are `1` or `5`, OGRNIP is `3`; other GRN numbers are another type [21]. [code] In the reviewed `stdnum/ru/ogrn.py`, the 15-digit case lacks a final `mod 10`: remainders 10–12 cannot match a single check digit [32]. [assumption] Do not reuse this implementation without correction; follow the formula in the regulatory text. The current status of the full procedure edition has not been established separately.

**Card.** [docs] Luhn doubles every second digit from the right, starting with the penultimate digit; subtract 9 from a result greater than 9; the sum of all digits must be divisible by 10 [15]. [assumption] Candidates are 12–19 digits, with spaces/hyphens; require card context for ambiguous numbers. A known-prefix list improves precision but may miss new ranges; Luhn alone does not prove that a card exists.

**Account + BIC.** [docs] For an ordinary account at a credit institution, prepend the last 3 digits of the BIC to the 20 account digits, then apply repeating weights `7,1,3` across 23 positions. The sum of products must be divisible by 10. An account at an RCC uses a different prefix: `0` and BIC digits 5–6; this is a separate validation branch [22]. [assumption] Link the details within one record/row of a table; do not match an arbitrary BIC from another email. Without a BIC, detect contextually but do not mark “validated.” Treasury and other special accounts need separate research.

**OMS.** [docs] The regional TFOMS describes the unified policy number as 16 digits: 15 main digits and one check digit; Mod10 under ISO/HL7 27931:2009 [23]. Select odd-positioned digits among the first 15 from the right and double the resulting number; add the digits of the other positions to the digit sum. Check digit = `(10 − sum mod 10) mod 10`. [assumption] This is equivalent to Luhn with the correct parity; the ordinary “number remainder mod 10” does not match it. This rule does not cover old-form policies or temporary certificates.

**Phone / date of birth.** [docs] libphonenumber distinguishes possible length from valid regional prefixes [16]. [assumption] `+7` alone is insufficient to determine a country; parse local numbers with a specified region and context. A date requires calendar validation and a label such as `дата рождения` / `DOB` / `born`; do not redact every contract or email date. A date being calendar-valid does not establish its meaning.

**Synthetic examples, calculated by hand.** [assumption] These are invented strings, not data from sources; the check below means only the stated arithmetic property. They are not claimed to be issued documents. Detector code was not run.

| Candidate | Expected check | Negative variant |
|---|---|---|
| SNILS `987-654-320 82` | `S=284`, remainder 82 | Ending `83` is invalid |
| INN-10 `9900123403` | Sum 113, check digit 3 | Last digit `4` is invalid |
| INN-12 `990012340050` | Sums 137 and 197, check `50` | Ending `51` is invalid |
| Sequence `9000000000000001` | Luhn: `9+1=10`; card system unconfirmed | Ending `2` is invalid |
| Unified policy `7000000000000005` | Doubling selected digits gives sum 5; check digit 5 | Ending `6` is invalid |
| Passport `99 99 000001` | Length and grouping only; issuance unconfirmed | `99 99 00001` is one digit short |
| Date `2000-02-29` | Valid calendar date; redaction depends on label | `2001-02-29` is invalid |

### В4. False positives

[code] Gitleaks combines format, entropy, and exclusions; detect-secrets uses heuristic filters, while Presidio can raise confidence using context [1, 4, 7, 10]. [assumption] Business email requires the following constraints; catalog risk levels are expert estimates, and numerical rates on an email corpus have not been measured.

| Error source | Mitigation | Cost of constraint |
|---|---|---|
| Order numbers, invoices, delivery notes | Check digit + document label + numeric-field boundaries | A typo in a sensitive number may fail the check digit |
| Versions, delivery dates, amounts, SKUs | Do not join digits across punctuation/lines; require a semantic label for DOB | A document split by line breaks may be missed |
| Build hashes, UUIDs, Base64 attachments | Do not rely on entropy alone; account for `sha256`, `checksum`, MIME structure | An unlabelled secret without a prefix is harder to detect |
| Company BIC, INN, and OGRN in signatures | Configure by entity type; scope exclusions to a specific rule | Less redaction of corporate details |
| Instruction “change password,” “password not required” | Require assignment/field; validate explicitly empty values | A genuine secret in free text is harder to detect |
| OTP versus request number | Confirmation label and bounded length; link to one field | An unlabeled code may go unnoticed |

[assumption] A failed check digit beside an explicit label `паспорт`, `СНИЛС`, or `ИНН` is a reason to redact it as a suspicious value with lower confidence, not reveal it. Exclusion lists should describe specific technical values and rule types. Repository baselines and `allowlist secret` comments in an email must not be treated as permission to disclose: an email is not trusted filter configuration.

### В5. Replacement style

[docs] Gitleaks `--redact` hides secrets in diagnostics and reports, including partial redaction by percentage; it is not an API for rewriting arbitrary email [1]. [code] detect-secrets stores a hash of the value in a finding record for comparison [33]. [docs] Presidio has `replace`, `redact`, `mask`, `hash`, `encrypt`, and `custom`; its standard `replace` emits a type label, such as `<PHONE_NUMBER>` [34].

| Method | Usefulness for an LLM | Limitation / recommendation |
|---|---|---|
| Fixed `[redacted]` | [assumption] Makes an omission visible | Loses distinction between a password and a document number |
| Type label + email reference | [assumption] Preserves meaning and provenance | Recommended default |
| Same alias for identical values | [docs][assumption] Presidio shows mapping to `<PERSON_14>`; helps matching [35] | Limit scope to one response/thread; do not expose mapping table |
| Last digits / preserve length | [assumption] May help a human | Reveals part of a secret; disabled by default |
| Hash | [assumption] Allows values to be compared | Little model value without type; short codes can be brute-forced; not recommended externally |
| Realistic fabricated replacement | [assumption] Text looks natural | Model may mistake fabricated details for facts |

[docs] Presidio documentation states that starting in 2.2.361, `hash` uses a random salt by default; a salt must be specified separately for stable matching [34]. [assumption] Our simple version needs neither hashes nor global persistent aliases. The reference should use an opaque internal ID and known date, without login, sender address, or any portion of the original secret.

### В6. Attachments and limits of text detection

[docs] POI distinguishes cell types and formatted representation (`DataFormatter`), formulas, and comments [24]. [assumption] A “Password” heading can be in one cell and its value in a neighboring cell; preserve coordinates and heading–value relationship. Do not assume hidden sheets, comments, hyperlinks, and formulas have been inspected automatically. Values with leading zeroes and values displayed in scientific notation require separate handling; a digit lost when saving the file cannot be restored by a detector.

[assumption] For `.env`, INI, YAML, JSON, XML, VPN/RDP configurations, account for quotes, escaping, multiline values, key–value pairs, and `${VAR}` references. Searching only the displayed formula result or only the first line of a key is incomplete. Do not execute formulas, macros, configuration commands, or URL navigation to validate them.

[docs] Presidio Image Redactor uses OCR, with Tesseract by default [25]. [assumption] An ordinary text detector cannot see text inside an image, a signature, barcode/QR code, hidden image region, or encrypted archive contents. OCR can lose a digit or confuse a character; a failed check digit does not guarantee safety. Mark “text not extracted” / “partially extracted” separately; cleaned text does not mean the original PDF, image, or spreadsheet is also cleaned.

## Comparison tables

### Catalog: 34 detectors

[assumption] These are sketches, not a tested Java configuration. `K` is a label, `V` is a bounded value under the rules in В2, and `N(n)` is exactly n digits with permitted separators. Numbers need outer boundaries; labels need case and Cyrillic handling. For Markdown, the alternative symbol is written `\|`; remove only the table escaping during implementation. “Low/medium/high” estimates false-positive risk after the stated check. Format facts are source-backed; adaptation to email and risk ratings are proposals.

| Detector | What it finds | Pattern (regex sketch) | Validation (check digit, context) | False-positive risk | Source of idea |
|---|---|---|---|---|---|
| D01 Password RU/EN | Human password value | `(?:пароль\|password\|passwd\|pwd)[ \t]*[:=][ \t]*V` | Value only; quoting and boundaries from В2, no entropy threshold | Medium: instructions | [code] [6]; [assumption] Russian vocabulary |
| D02 Temporary password | Password issued for access | `(?:временный пароль\|temporary password)[ \t]*[:=][ \t]*V` | Do not capture validity period | Medium | [assumption], extension of [6] |
| D03 Wi-Fi | Network password/PSK | `(?:пароль от Wi-?Fi\|Wi-?Fi password\|psk)[ \t]*[:=][ \t]*V` | Link to network; do not treat SSID alone as password | Medium | [assumption], principle [6] |
| D04 PIN | PIN code | `(?:пин[- ]?код\|PIN)[ \t]*[:=][ \t]*[0-9]{4,8}` | Label required; length configurable | Medium: SKU | [assumption], principle [6] |
| D05 OTP | Confirmation code | `(?:код подтверждения\|одноразовый код\|OTP\|verification code)[ \t]*[:=]?[ \t]*[0-9]{4,10}` | One field; sign-in/confirmation text; no expiry validation | Medium: request number | [assumption], principle [6] |
| D06 Login–password | Pair of fields or table record | `(?:логин\|login\|username)...(?:пароль\|password)[:=]V` | No `.*` across paragraphs; link fields locally and redact secret | Medium | [assumption], [6] |
| D07 VPN/RDP | Remote-system access | `(?:VPN\|RDP\|удалённый доступ)...K[:=]V` | Context of one block + password/PSK label; not any server address | Medium | [assumption], [6] |
| D08 URI userinfo | Credentials before `@` | `[A-Za-z][A-Za-z0-9+.-]*://[^/@\s]+:[^/@\s]+@` | Parse URI, percent-encoding; replace all userinfo | Low/medium: sample URIs | [docs][code] [29, 36] |
| D09 HTTP Basic | Encoded pair | `(?i)Authorization:[ \t]*Basic[ \t]+[A-Za-z0-9+/]+={0,2}` | Valid Base64 and pair separator; redact entirely | Low | [docs] [30] |
| D10 Bearer | Access token | `(?i)Authorization:[ \t]*Bearer[ \t]+[^\s,;]+` | Authorization header/field; do not require JWT | Low/medium | [assumption], general tokens [2] |
| D11 JWT/JWE | Three/five token segments | `[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*){2}`; separate `{4}` | Base64url, header JSON, allowed empty segments; redact full token | Medium before parsing | [docs][code] [31, 37] |
| D12 PEM private key | Private key | `-----BEGIN (?:RSA \|EC \|DSA \|ENCRYPTED )?PRIVATE KEY-----` | Matching END of same type, including OPENSSH as separate branch; whole block | Low; certificate is not a key | [code] [8] |
| D13 PGP/PuTTY | Other private-key containers | `BEGIN PGP PRIVATE KEY BLOCK\|PuTTY-User-Key-File-[23]` | For PuTTY, find `Private-Lines`; redact whole container | Low | [code] [8]; [assumption] version 3 |
| D14 GitHub PAT | GitHub tokens | `ghp_[A-Za-z0-9]{36}\|github_pat_[A-Za-z0-9_]{82}` | Boundaries and length; other families as separate variants | Low: sample strings | [code] [38] |
| D15 GitLab PAT | GitLab token | `glpat-[A-Za-z0-9_-]{20}` | One known format, not the full set of GitLab formats | Low; new formats may be missed | [code] [2] |
| D16 AWS access key ID | AWS key identifier | `(?:AKIA\|ASIA)[A-Z2-7]{16}` | Known prefix; ID is not the secret half, find adjacent secret | Low | [code] [39] |
| D17 AWS secret/session | Secret half and session token | `(?:aws_secret_access_key\|aws_session_token)[ \t]*[:=][ \t]*V` | Label and field, offline, no AWS requests | Medium | [assumption], [6, 39] |
| D18 Generic API key | Keys without stable prefix | `(?:api[_-]?key\|token\|secret)[ \t]*[:=][ \t]*V` | Context; entropy as an additional signal, not a mandatory rejection | Medium/high | [code] [2, 6, 7] |
| D19 OAuth | Client secret, refresh/access token | `(?:client_secret\|refresh_token\|access_token)[ \t]*[:=][ \t]*V` | Field key; do not treat `client_id` alone as secret | Low/medium | [assumption], [6] |
| D20 Connection string | Password and connection parameters | `(?:Password\|Pwd)[ \t]*=V`; URI branch D08 | JDBC/ADO.NET grammar; multiple secret fields, quotes, and `;` | Medium | [assumption], [6, 29] |
| D21 `.env` and configuration | Secret assignments | `^[ \t]*(?:export[ \t]+)?[A-Z0-9_]*(?:PASSWORD\|TOKEN\|SECRET)[A-Z0-9_]*[ \t]*=V` | Quote/continuation parser; `${VAR}` is a reference, not a found value | Medium | [assumption], [6] |
| D22 Russian passport | Series and number | `[0-9]{2}[ \t]?[0-9]{2}[ \t]?[0-9]{6}` | Passport/series/number labels; check digit not established | High without context | [docs] [17] |
| D23 SNILS | 9 digits + check digits | `[0-9]{3}[- ]?[0-9]{3}[- ]?[0-9]{3}[ ]?[0-9]{2}` | Algorithm in В3 and historical exception | Medium | [docs] [18] |
| D24 INN-10 | Organization identifier | `N(10)` | Weights from В3 + `ИНН`; separate setting | Medium: phone/order number | [docs][code] [19, 20] |
| D25 INN-12 | Individual's INN | `N(12)` | Two check digits in В3 | Low/medium | [docs][code] [19, 20] |
| D26 OGRN | Organization number | `[15][0-9]{12}` | mod 11 then mod 10; OGRN label | Medium: corporate detail | [docs] [21] |
| D27 OGRNIP | Individual entrepreneur number | `3[0-9]{14}` | mod 13 then mod 10; OGRNIP label | Medium | [docs] [21] |
| D28 Card number | Full PAN | `(?:[0-9][ -]?){11,18}[0-9]` | Luhn, length, context; do not extract a piece of a longer number | Medium: 16-digit OMS | [docs] [11, 15] |
| D29 Account + BIC | Payment account | `N(20)` and `N(9)` | Weights 7/1/3 and matching BIC in one record | Medium without field linkage | [docs] [22] |
| D30 BIC | Bank identifier | `(?:БИК\|BIC)[ \t]*[:=]?[ \t]*[0-9]{9}` | No proposed standalone check digit; link to D29 | High without label; often public | [docs] [22]; [assumption] redact separately |
| D31 OMS / unified policy | Unified-form policy | `N(16)` | Mod10 in В3 and labels `ОМС`, `ЕНП`, `полис` | High without label: overlaps card | [docs] [23] |
| D32 Phone | International/Russian number | `(?:\+7\|8)[ ()-]*[0-9]{3}[ ()-]*[0-9]{3}[ -]*[0-9]{2}[ -]*[0-9]{2}` | libphonenumber, region/context; international separately via `findNumbers` | Medium: delivery note | [docs] [16] |
| D33 Date of birth | Date following DOB label | `(?:дата рождения\|DOB\|born)[ \t:]+(?:[0-9]{2}\.[0-9]{2}\.[0-9]{4}\|[0-9]{4}-[0-9]{2}-[0-9]{2})` | Calendar and label semantics; not every date | Medium | [assumption], context approach [10] |
| D34 Email | Person/organization address | `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` | Sketch does not cover all syntax; parse headers with MIME parser | Medium: service address | [docs] [11]; [assumption] redaction profile |

## Recommendation

[assumption] For the MVP, I recommend exactly 15 detectors: **D01, D03, D05, D08, D09, D10, D11, D12, D14, D18, D20, D22, D23, D25, D28**. They cover human passwords, one-time codes, common machine secrets, and major personal identifiers; all can be implemented offline. Apply D01 to password labels inside VPN/RDP blocks too, and D18 to structured fields.

[assumption] Add PIN, provider-specific formats, OMS, and bank accounts later, after validating structural extraction. Put phones, email, and organization details in explicitly configurable categories: redacting them often interferes with understanding a business request. This choice reduces the MVP scope but leaves the listed categories outside its coverage.

[assumption] Use the typed full replacement from the “Summary” section; if needed, assign the same number to matching values only within one response. Do not include the source fragment in the explanation of a detection. Preserve internal boundaries, type, rule, confidence, and source reference. For overlaps, first merge covered ranges, then replace from right to left: a smaller finding must not leave the tail of a larger one visible.

[assumption] Before implementation, prepare a synthetic corpus of positive and negative examples: RU/EN, quotes, line breaks, tables, invalid check digits and OCR, overlapping rules, and long values. Measure false negatives and false positives by type; do not carry thresholds from source-code analysis over without evaluating on emails. This is a proposed next check, not completed testing. Where the filter belongs relative to the index and storage remains a user decision.

## What could not be verified

- [assumption] Accuracy and recall on real Russian/English email: no corpus is available, and the code was not run under this task's scope. Check later with synthetic data, then locally with the user on an approved sample, without sending original values.
- [assumption] Compatibility of Phileas and specific dependency versions with Java 25 requires a separate technical experiment. The README gives different numbers in the dependency example and schema table; do not treat either as confirmation of the latest release [14].
- [assumption] The complete current regulatory chain for SNILS, OGRN, and old policy formats: verified algorithms from dated documents are cited, but their status as of 2026-10-24 was not established in every case. Check current editions before implementing a formal validator.
- [assumption] Required prefixes/internal structure of all unified policies and old OMS formats: an open source confirms 16 digits and a check digit, but not every issuance restriction [23].
- [assumption] Exact release years for Gitleaks `v8.30.1` and Presidio `2.2.364` were not reliably retrieved; tags are visible in official release lists [26, 40]. Rule conclusions refer to the stated tag or branch state on the access date.
- [assumption] Portability of each Go/Python expression to Java and resistance to pathological backtracking were not tested. Check boundaries, Unicode flags, and limits; the sketches are not a ready configuration.

## What else to investigate

- [assumption] Safe delivery of original files and cleaned copies is a separate issue in `docs/plan.md`, section 5.4; text detection does not solve it.
- [assumption] Redaction of QR/barcodes, images, and document metadata is a separate study after choosing text extraction.
- [docs] Gitleaks identifies Betterleaks as its successor; its rules were not compared here [1].

## Sources

All URLs below were read on **2026-10-24**. For mutable pages without a stated publication date, the access date is recorded; it is not the release date. Regulatory texts in reference systems are marked as reproductions of primary documents, not commentary by their editors.

1. Gitleaks README: TOML format, exclusions, diagnostics, project status — <https://raw.githubusercontent.com/gitleaks/gitleaks/master/README.md> (read 2026-10-24; `master`, publication date not stated).
2. Gitleaks `config/gitleaks.toml`, tag v8.30.1 — <https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/config/gitleaks.toml> (read 2026-10-24; pinned tag).
3. Gitleaks MIT LICENSE — <https://raw.githubusercontent.com/gitleaks/gitleaks/master/LICENSE> (read 2026-10-24; copyright notice 2019).
4. Yelp detect-secrets README: plugins, filters, network check — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/README.md> (read 2026-10-24; `master`).
5. detect-secrets Apache-2.0 LICENSE — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/LICENSE> (read 2026-10-24; license text dated 2004-01).
6. detect-secrets `plugins/keyword.py` — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/detect_secrets/plugins/keyword.py> (read 2026-10-24; code and embedded MIT notice, copyright 2014).
7. detect-secrets `plugins/high_entropy_strings.py` — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/detect_secrets/plugins/high_entropy_strings.py> (read 2026-10-24; `master`).
8. detect-secrets `plugins/private_key.py` — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/detect_secrets/plugins/private_key.py> (read 2026-10-24; code and embedded MIT notice, copyright 2014).
9. Presidio MIT LICENSE — <https://raw.githubusercontent.com/microsoft/presidio/main/LICENSE> (read 2026-10-24; current Presidio Contributors text).
10. Presidio: Developing PII recognizers — <https://presidio.dataprivacystack.org/analyzer/adding_recognizers/> (read 2026-10-24; publication date not stated).
11. Presidio: Supported entities — <https://presidio.dataprivacystack.org/supported_entities/> (read 2026-10-24; former microsoft.github.io address redirects to this site).
12. Presidio: PII detection in different languages — <https://raw.githubusercontent.com/data-privacy-stack/presidio/main/docs/analyzer/languages.md> (read 2026-10-24; `main`).
13. Presidio `conf/default_recognizers.yaml` — <https://raw.githubusercontent.com/microsoft/presidio/main/presidio-analyzer/presidio_analyzer/conf/default_recognizers.yaml> (read 2026-10-24; `main`).
14. Phileas README — <https://raw.githubusercontent.com/philterd/phileas/master/README.md> (read 2026-10-24; `master`, notices 2024–2026, single release date not established).
15. Apache Commons Validator: LuhnCheckDigit — <https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/checkdigit/LuhnCheckDigit.html> and CreditCardValidator — <https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/routines/CreditCardValidator.html> (read 2026-10-24; documentation 1.11.0).
16. Google libphonenumber README — <https://raw.githubusercontent.com/google/libphonenumber/master/README.md> (read 2026-10-24; `master`).
17. Russian Government Resolution No. 2267, passport form description, paragraph 3 — <https://www.consultant.ru/document/cons_doc_LAW_466454/51d3a2d43a726e22847dd3be33f75c45c756b8a7/> (read 2026-10-24; act dated 2023-12-23, reproduction of regulatory text).
18. Pension Fund Board Order No. 323r, paragraph 2.7 — <https://legalacts.ru/doc/rasporjazhenie-pravlenija-pf-rf-ot-31072014-n/> (read 2026-10-24; act dated 2014-07-31, reproduction of regulatory text).
19. Federal Tax Service: INN — <https://www.nalog.gov.ru/rn77/fl/interest/inn/>; explanation of structure as of 2026-01-01 — <https://www.nalog.gov.ru/rn77/news/activities_fts/16575267/> (read 2026-10-24; explanation published 2025-11-07, site marks it archived).
20. python-stdnum `stdnum/ru/inn.py` — <https://raw.githubusercontent.com/arthurdejong/python-stdnum/master/stdnum/ru/inn.py> (read 2026-10-24; source code, LGPL-2.1-or-later; code was not ported).
21. Ministry of Finance Order No. 165n, paragraph 7 of the register-keeping procedure — <https://www.consultant.ru/document/cons_doc_LAW_288080/bfda7a84013d0ad8a3d6e57b229b1bbcbf923868/> (read 2026-10-24; act dated 2017-10-30, revision 2022-12-19, reproduction of regulatory text).
22. Bank of Russia No. 515: procedure for calculating check key — <https://www.consultant.ru/document/cons_doc_LAW_16053/08c1d0eacf880db80ef56f68c3469e2ea24502d7/> (read 2026-10-24; act dated 1997-09-08, reproduction of primary text with weights).
23. Rostov Region TFOMS: unified policy structure and check digit — <https://rostov-tfoms.ru/services/zasedaniya-rabochih-grupp/erp/enp-struct> (read 2026-10-24; primary fund description, publication date not stated); FFOMS No. 54, Appendix Г.2 — <https://sudact.ru/law/prikaz-ffoms-ot-23032018-n-54-o/prilozhenie-13/g.2-algoritm-rascheta-kontrolnogo-chisla/> (read 2026-10-24; act dated 2018-03-23, reproduction of regulatory text).
24. Apache POI: Busy Developers’ Guide to HSSF and XSSF Features — <https://poi.apache.org/components/spreadsheet/quick-guide.html> (read 2026-10-24; publication date not stated).
25. Presidio Image Redactor — <https://presidio.dataprivacystack.org/image-redactor/> (read 2026-10-24; publication date not stated).
26. Gitleaks releases — <https://github.com/gitleaks/gitleaks/releases> (read 2026-10-24; visible tag v8.30.1).
27. detect-secrets 1.5.0, author's PyPI release — <https://pypi.org/project/detect-secrets/1.5.0/> (read 2026-10-24; published 2024-05-06).
28. Phileas Apache-2.0 LICENSE — <https://raw.githubusercontent.com/philterd/phileas/master/LICENSE> (read 2026-10-24).
29. RFC 3986, § 3.2.1 User Information — <https://www.rfc-editor.org/rfc/rfc3986> (read 2026-10-24; published 2005-01).
30. RFC 7617: Basic HTTP Authentication — <https://www.rfc-editor.org/rfc/rfc7617> (read 2026-10-24; published 2015-09).
31. RFC 7519: JSON Web Token — <https://www.rfc-editor.org/rfc/rfc7519> (read 2026-10-24; published 2015-05).
32. python-stdnum `stdnum/ru/ogrn.py`, `calc_check_digit` — <https://raw.githubusercontent.com/arthurdejong/python-stdnum/master/stdnum/ru/ogrn.py> (read 2026-10-24; `master`, copyright 2024–2025, discrepancy described in В3).
33. detect-secrets `core/potential_secret.py` — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/detect_secrets/core/potential_secret.py> (read 2026-10-24; `master`).
34. Presidio Anonymizer: operators and hash salt — <https://presidio.dataprivacystack.org/anonymizer/> (read 2026-10-24; hash change noted from 2.2.361).
35. Presidio: Pseudonymization notebook — <https://raw.githubusercontent.com/data-privacy-stack/presidio/main/docs/samples/python/pseudonymization.ipynb> (read 2026-10-24; `main`).
36. detect-secrets `plugins/basic_auth.py` — <https://raw.githubusercontent.com/Yelp/detect-secrets/master/detect_secrets/plugins/basic_auth.py> (read 2026-10-24; `master`).
37. Gitleaks `cmd/generate/config/rules/jwt.go` — <https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/cmd/generate/config/rules/jwt.go> (read 2026-10-24; tag v8.30.1).
38. Gitleaks `cmd/generate/config/rules/github.go` — <https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/cmd/generate/config/rules/github.go> (read 2026-10-24; tag v8.30.1).
39. Gitleaks `cmd/generate/config/rules/aws.go` — <https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/cmd/generate/config/rules/aws.go> (read 2026-10-24; tag v8.30.1).
40. Presidio releases — <https://github.com/data-privacy-stack/presidio/releases> (read 2026-10-24; visible tag 2.2.364).

# Synthetic detector samples

All values are generated fiction. No provider API was called, and no real key or credential is included.
UTF-8 without BOM, LF. Helpers stay in ignored testdata/.work/RT-12/.

## Files and fields

Fifteen Dnn JSON files contain detector, title, source and cases.
Each case has id, kind (positive/negative), lang, variants, text, expected, note;
checksum, when present, is valid/invalid. Lang describes the surrounding test context;
standard labels such as ИНН or СНИЛС can appear in both language groups.
Mixed TXT files have a paired expected JSON with id, file, expected, redactedText and note.
manifest.json lists every file including itself (without its own hash), sizes and SHA-256,
and positive/negative counts per detector.

## Expected matching contract

Expected is the COMPLETE list of all 15 MVP findings in each text, including overlaps.
Match is an exact source substring; occurrence is the 1-based non-overlapping occurrence.
Confidence is high/low. Low confidence is reserved for unresolved disputed cases.
Unlisted findings are false positives; missing expected findings are misses.
Negative means negative for the file's detector; other findings, if present, must be listed.
D01 also recognizes password fields inside Wi-Fi and connection-string contexts.
D20 marks secret values inside connection strings; URI userinfo is marked whole with D08 too.
Bearer containing JWT has both D10 and D11. Numeric Luhn-valid passwords have D01 and D28.
Merge overlapping ranges by union; marker uses the detector of the largest finding;
equal lengths choose the lowest detector ID. Adjacent ranges are not merged.
Markers [скрыто:Dnn] are fixture conventions, not a final production replacement style.
Emails, phones, company INN-10 and other non-MVP fields are not expected findings.

## Proposed or disputed behavior

Explicit numeric label + invalid checksum is low confidence/disputed: no policy decision is implied.
Newline after a password label is also low confidence/disputed; future parser policy may reject it.
Structured XML leaves and table cells define value boundaries, and escaped quotes remain source text.
Placeholders, masked values and prose saying no password is required are negative.
Historical SNILS below 001-001-998 is labeled and hidden, without declaring its checksum valid.
Long token wrapping has a low-confidence disputed fixture; add explicit structured
mail-unfolding policy before treating arbitrary line breaks as token continuation.

## Generation and checks

Python standard library seeded Random generated values from scratch and checked every match occurrence.
SNILS and INN-12 use the weights in RT-08 В3; cards use Luhn on generated non-payment prefix 999999.
999999 is a deliberately non-payment fixture prefix, not a claimed official reserved test BIN.
Provider token shapes contain deliberately invalid CRC32 suffix 000000 for ghp_ payloads with nonzero CRC32.
Fine-grained github_pat_ provider checksum is marked to verify; the generated value was never issued.
PEM bodies start with NOT_A_DER_KEY after base64 decoding and cannot be DER private keys.
JWT/JWE are shaped synthetic envelopes; signatures and encryption are not valid.
No third-party detector or secret scanner was run. Expectations are authored data for future implementations.
All checksums and merged redactedText are computed; final detector precision remains unmeasured.

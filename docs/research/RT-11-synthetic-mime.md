# RT-11 — Synthetic MIME messages

- Date: 2026-10-25
- Author: Codex
- Brief: docs/research-tasks/RT-11-synthetic-mime.md
- Data: testdata/mime/

## Summary

- [code] Created all required M01–M14 and additional M15–M20: 20 messages with expected results.
- [code] The M09 chain contains a real MSG: EML → MSG → ZIP → TXT.
- [code] TNEF contains RTF and an attachment with a long Russian name; read with POI HMEF.
- [code] S/MIME contains real CMS structures created using a test certificate only.
- [code] All messages are under 64 KiB, use CRLF and have calculated sizes and SHA-256 hashes.
- [assumption] Warning rules and disputed recovery behavior are proposals for a future parser.

## Set contents

| ID | File | Coverage | Created | Verified with |
|---|---|---|---|---|
| M01 | `M01-rfc2231-continuations.eml` | rfc2231-continuations | yes | Python email |
| M02 | `M02-encoded-word-filename.eml` | encoded-word-filename | yes | Python email |
| M03 | `M03-koi8r.eml` | koi8r | yes | Python email |
| M04 | `M04-windows-1251.eml` | windows-1251 | yes | Python email |
| M05 | `M05-mislabelled-charset.eml` | mislabelled-charset | yes | Python email |
| M06 | `M06-plain-and-html.eml` | plain-and-html | yes | Python email |
| M07 | `M07-html-only.eml` | html-only | yes | Python email |
| M08 | `M08-cid-inline-images.eml` | cid-inline-images | yes | Python email |
| M09 | `M09-eml-msg-zip-chain.eml` | eml-msg-zip-chain | yes | Python email + POI HSMF 5.4.1 |
| M10 | `M10-tnef-winmail.eml` | tnef-winmail | yes | Python email + POI HMEF 5.4.1 |
| M11 | `M11-ics-tzid.eml` | ics-tzid | yes | Python email |
| M12 | `M12-smime-signed.eml` | smime-signed | yes | Python email + OpenSSL 3.0.3 |
| M13 | `M13-smime-encrypted.eml` | smime-encrypted | yes | Python email + OpenSSL 3.0.3 |
| M14 | `M14-broken-boundary.eml` | broken-boundary | yes | Python email |
| M15 | `M15-smime-opaque-signed.eml` | smime-opaque-signed | yes | Python email + OpenSSL 3.0.3 |
| M16 | `M16-unsafe-filenames.eml` | unsafe-filenames | yes | Python email |
| M17 | `M17-no-filename.eml` | no-filename | yes | Python email |
| M18 | `M18-split-encoded-word.eml` | split-encoded-word | yes | Python email |
| M19 | `M19-nesting-over-limit.eml` | nesting-over-limit | yes | Python email |
| M20 | `M20-bad-headers.eml` | bad-headers | yes | Python email |

## File generation

- Python 3.14.3; email, base64, quopri, struct, zlib, zipfile, hashlib.
- Generator: `testdata/.work/RT-11/generate.py`, excluded from git.
- BinaryFixtures.java is in the same directory; its classpath uses the existing Gradle cache.
- PNG: custom IHDR/IDAT/IEND with CRC32, one RGB pixel.
- ZIP: one layout.txt with a fixed timestamp, without unsafe extraction.
- MSG: CFB through POIFSFileSystem, MAPI streams for subject, body, properties and one attachment.
- MSG was read with MAPIMessage; the package.zip name and bytes were compared with the original.
- TNEF: signature, key, levels 1/2, attributes and 16-bit checksums.
- RTF: MELA wrapper with plain text, without macros or external objects.
- The long TNEF filename is stored in Unicode property PR_ATTACH_LONG_FILENAME.
- HMEFMessage confirmed the RTF, Russian name and attachment content.
- OpenSSL created a new self-signed test@example.com certificate; no real keys were used.
- `openssl cms -sign -binary`: detached signature; `-nodetach`: opaque signature.
- `openssl cms -encrypt -aes256`: encrypted envelope.
- `cms -verify -noverify` and `cms -decrypt` returned the original MIME entity byte for byte.
- The private key and separate certificate are excluded from the set, in an ignored directory.
- No new packages, documents or samples were downloaded.

## Verification

- [code] Every EML was parsed with Python email; the selected part was located by path.
- [code] Every body.textContains string occurs in the decoded body.
- [code] HTML uses visible text without style/script; no network access.
- [code] Size and SHA-256 were calculated from each part's decoded bytes.
- [code] MIME containers use serialized entity bytes, under the rule described in the README.
- [code] A shared validator checked PNG signatures, CRC and IDAT decompression.
- [code] M09 and M10 were also read with the independent POI 5.4.1 library.
- [code] M12/M13/M15 were checked with OpenSSL; cmsValid=true.
- [code] CRLF and size limits were checked for all 20 messages.
- [code] The manifest includes every file and hash; the manifest has no hash of itself.
- [code] JSON was validated by reading it back.
- The production Mime4j pipeline was not run: it has not been implemented yet.

## Disputed cases

- M05: the bytes are known to originate from CP1251, but automatic label correction is undecided.
- M06: plain text is selected; HTML is not added again to the same text.
- M07: links must be preserved; URLs must not be fetched.
- M08: Content-IDs map to two inline parts; the third image is an attachment.
- M12: independent signature verification by the generator does not add signature verification
  to the future product.
- M14: bounded lenient parsing is acceptable; a damaged part may be skipped with a warning.
- M15: opaque signatures are recognized separately from encryption.
- M16: display names are preserved; disk writes use internal names only.
- M18: the correct string is ground truth; refusing recovery on an obvious defect is acceptable.
- M19: child expansion stops before the sixth container transition.
- Paths after ZIP/MSG/TNEF are logical; they were not supplied by the IMAP server.

## What could not be done

- Independent Mime4j parsing of the entire set has not been performed.
- Python email behavior cannot be treated as normative for every edge case.
- The synthetic MSG is minimal; real MAPI property variety is much broader.
- S/MIME does not model certificate chain trust or revocation.
- No malicious large archives were created: limits are tested with the small M19.

## Further research

- When implementing, connect the set to streaming Mime4j and a shared derived-byte counter.
- Independently compare warnings for incorrect charsets and damaged boundaries.

## Sources

- docs/research/RT-09-mime-attachments.md, В2/В4/В5; read on 2026-10-25.
- Microsoft MS-OXTNEF — https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxtnef/ (read on 2026-10-25).
- Local Apache POI 5.4.1 sources: hmef, hsmf, poifs, Gradle cache; read on 2026-10-25.
- OpenSSL 3.0.3, installed `openssl cms -help` command; checked on 2026-10-25.

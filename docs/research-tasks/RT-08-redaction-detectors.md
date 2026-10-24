# RT-08 — Sensitive-data detectors

- **Result file:** `docs/research/RT-08-redaction-detectors.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 5; input for `docs/plan.md`, section 5.4.

## Goal

Build a catalogue of detectors for secrets and personal data that may appear in Russian and
English business mail, so that our filter can replace them before text reaches the model
(for example: a real password becomes «[скрыто: пароль — см. письмо от 2026-03-12]»).

## What we already know

- The filter sits between stored mail and MCP responses (`docs/plan.md`, 5.4). Where exactly it
  is applied (output only, before indexing, both) is the user's design decision — not this task.
- Detectors are planned as rules (regular expressions + validation), configurable by the user.

## Questions

- **В1. Existing rule sets:** gitleaks (rule format, which rules matter for mail: API keys,
  tokens, private keys, connection strings), detect-secrets (plugins, entropy detectors),
  Microsoft Presidio (recognizer model, which recognizers exist, is there Russian support), any
  Java library for secret or PII detection. Licences of the rule sets (can we reuse the regexes?).
- **В2. Secrets in mail text:** patterns for passwords written by humans in Russian and English:
  «логин: … пароль: …», «пароль от Wi-Fi», «пин-код», «код подтверждения», «временный пароль»,
  credentials in URLs (`user:pass@host`), `Authorization: Basic …` headers, PEM private keys,
  JWT, OTP / one-time codes, VPN or RDP credentials. How to find the **value** next to the keyword
  without swallowing the whole line.
- **В3. Russian personal data:** паспорт РФ (серия и номер), СНИЛС (with checksum), ИНН 10 and 12
  digits (with checksum), ОГРН / ОГРНИП, банковская карта (Luhn), расчётный счёт + БИК, полис ОМС,
  телефон, дата рождения. Format, validation algorithm, false-positive risks.
- **В4. False positives:** typical sources in business mail (order numbers, invoice numbers,
  phone-like numbers, version strings, hashes in technical mail) and how existing tools reduce
  them (checksums, context words, entropy thresholds, allow-lists).
- **В5. Replacement style:** how existing tools render redactions (fixed tags, typed
  placeholders, consistent pseudonyms, hashes) and which style keeps the text useful for an LLM.
- **В6. Attachments:** any special cases for detecting secrets in spreadsheets, config files,
  `.env`-like text, scanned documents (only mention what text-based detection cannot cover).

## Where to look

- gitleaks repository and its default config (`config/gitleaks.toml`).
- detect-secrets repository (Yelp).
- Microsoft Presidio documentation (supported entities, recognizers, language support).
- Official descriptions of Russian document number formats and checksum algorithms (government or
  well-established reference pages; mark the source type).

## Required tables

| Детектор | Что ищет | Шаблон (набросок регулярного выражения) | Проверка (контрольная сумма, контекст) | Риск ложных срабатываний | Источник идеи |
|---|---|---|---|---|---|

Use **synthetic examples only** (invented values that pass or fail the validation). Never copy a
real secret, a real document number or a real person's data from any source.

## Out of scope

- Implementing detectors or running code.
- Deciding where the filter is applied, and the prompt-injection topic (RT-01 covers what others
  do about it).
- ML-based NER beyond noting which tools offer it.

## Done when

- The table has at least 25 detectors covering В2 and В3.
- В1, В4, В5, В6 answered.
- The recommendation proposes a first detector set for the MVP (10–15 detectors) and a default
  replacement style.

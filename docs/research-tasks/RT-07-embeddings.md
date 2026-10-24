# RT-07 — Embedding models for Russian mail

- **Result file:** `docs/research/RT-07-embeddings.md`
- **Size:** M.
- **Fills:** `docs/research.md`, section 4; `docs/plan.md`, open question 6.

## Goal

Shortlist embedding models and Java runtimes for the **optional** vector search over mail and
attachments. The default stays BM25; embeddings are an opt-in feature (`docs/plan.md`, 5.3).

## What we already know

- Text is mostly Russian, partly English; business mail plus attachment text (contracts, specs,
  spreadsheets).
- Runs on the user's Windows laptop, CPU-only should be assumed; GPU is a bonus.
- Privacy: by default text must not leave the machine. An external API is acceptable only as an
  explicit option, and only after the sensitive-data filter (`docs/plan.md`, 5.4).
- Scale: up to ~100 000 messages plus attachments, chunked.

## Questions

- **В1. Candidate local models** with good Russian quality. Cover at least: multilingual E5
  (small / base / large, and instruct variants), BGE-M3, Russian-specific models (for example
  from ai-forever or deepvk), LaBSE, and newer multilingual embedding models released in 2025–2026
  (for example Qwen3-Embedding, EmbeddingGemma — check what exists). For each: parameters,
  vector size, max input tokens, licence (watch for non-commercial licences), availability as
  ONNX, quality on Russian benchmarks (ruMTEB / encodechka or similar), CPU speed if reported.
- **В2. Java runtimes:** ONNX Runtime Java, Deep Java Library (DJL), LangChain4j in-process
  embedding modules, Spring AI ONNX transformers embedding model. For each: how a model is loaded,
  tokenizer support, Windows support, size of native dependencies, maintenance status.
- **В3. Ollama** as a local alternative: which embedding models it offers for Russian, HTTP API,
  pros and cons versus in-process ONNX.
- **В4. External APIs** in one table (OpenAI, Voyage, Cohere, Google, Yandex, GigaChat or
  others with Russian support): model, price per million tokens, data-retention terms. Short.
- **В5. Chunking for mail:** recommended chunk size for the shortlisted models, how to treat
  quoted replies and signatures, metadata to keep per chunk.
- **В6. Cost estimate:** time to embed 100 000 chunks of ~300 tokens on a typical laptop CPU
  for the top 2–3 local models (from published numbers; mark estimates as `[предположение]`).

## Where to look

- Hugging Face model cards; MTEB leaderboard (multilingual and Russian tracks); encodechka
  (`https://github.com/avidale/encodechka`).
- ONNX Runtime, DJL, LangChain4j and Spring AI documentation.
- Ollama model library.
- Vendor pricing and data-usage pages for В4.

## Required tables

| Модель | Параметры | Размерность | Макс. токенов | Лицензия | ONNX | Качество на русском | Скорость CPU | Источник |
|---|---|---|---|---|---|---|---|---|

| Runtime для Java | Загрузка модели | Токенизатор | Windows | Размер нативных библиотек | Активность |
|---|---|---|---|---|---|

## Out of scope

- Downloading models, running code, benchmarks.
- Search engine choice (RT-06).
- Where exactly the sensitive-data filter is applied (design decision for the user).

## Done when

- At least 8 models in the first table, all runtimes from В2 in the second.
- The recommendation names a default optional local model, a smaller fallback, and the runtime.

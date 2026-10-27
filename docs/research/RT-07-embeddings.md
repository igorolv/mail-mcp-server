# RT-07 — Embeddings for Russian-language email

- Date: 2026-10-24
- Author: Codex / research session RT-07
- Brief: `docs/research-tasks/RT-07-embeddings.md`

## Summary

- [assumption] Recommendation: an optional local model, `intfloat/multilingual-e5-base`, with the lighter `multilingual-e5-small`; BM25 remains the primary search. Rationale: Russian retrieval was evaluated in ruMTEB, ONNX files are available, and input handling rules are clear [1, 4–8].
- [docs] In the original ruMTEB results, E5-base scores 67.1, small 65.8, and BGE-M3 74.8 for Retrieval. These are average nDCG@10 scores across three tasks, not email-search accuracy [1].
- [assumption] Runtime: ONNX Runtime Java CPU plus DJL HuggingFace Tokenizers; Spring AI needs a wrapper that applies the correct prefixes, pooling, and normalization [20–26].
- [code] Spring AI 2.0.2 averages token vectors and does not perform L2 normalization. It should not automatically be treated as a ready adapter for any model in the table [26].
- [docs] Newer options exist: Qwen3-Embedding was released in 2025, EmbeddingGemma 300M on 2025-09-04, and EmbeddingGemma 2 on 2026-10-06 [14, 16, 18].
- [assumption] A conditional estimate for 100,000 chunks of 300 tokens is about 3–13 hours for E5-small, 10–41 hours for base, and 41–164 hours for BGE-M3. This extrapolates older measurements with assumptions about length and CPU; it is not a published laptop benchmark. See В6 [2, 3].
- [docs] Ollama offers BGE-M3, Qwen3-Embedding, and EmbeddingGemma 300M over HTTP. Specify the model size and local mode: `qwen3-embedding:latest` refers to 8B [28–33].
- [assumption] Start with 250–350 tokens of useful text, preserve paragraph boundaries, remove repeated quotations from vector representations, and store links to source locations; see В5.
- [docs] External APIs have different data-retention rules: OpenAI keeps ordinary logs for up to 30 days; Voyage offers zero retention after an explicit opt-out from training; Google's paid mode excludes training but allows temporary logging [35, 37, 41].

## Answers to the questions

### В1. Which local models are suitable for Russian text?

[assumption] Compare retrieval quality first, then general text-representation scores. Strong sentiment classification does not by itself demonstrate good retrieval of a contract clause or an answer in an email thread. The distinction is supported by ruMTEB's separate task groups and the absence of retrieval tasks in encodechka [1, 2].

[docs] The ruMTEB paper dated 2024-08-22 contains 23 tasks across seven types. `R/A` below means Retrieval / Avg from Table 5 of the paper: average nDCG@10 across three retrieval tasks / average across seven groups. The scale is 0–100. These results are comparable within this experiment [1].

[code] `S` below is Mean S from the encodechka README, on a 0–1 scale. It uses a different task set and must not be combined with `R/A` into one ranking. The CPU methodology is discussed in В6 [2, 3].

[docs] Qwen and both EmbeddingGemma models publish multilingual MTEB results, but the model cards reviewed did not provide a separate comparable Russian result. Overall MTEB is not described as a Russian evaluation; this is an explicit evidence gap, not a score of zero [14, 16, 18].

[assumption] Practical shortlist for later testing: E5-base, E5-small, and BGE-M3. Qwen3-Embedding-0.6B and EmbeddingGemma 2 are interesting newer alternatives, but do not replace testing with Russian queries and the intended Java runtime.

Vector-generation rules:

| Family | Query / document representation | Final vector generation | Basis |
|---|---|---|---|
| multilingual E5 small/base/large | `query: …` / `passage: …`, including for Russian text | Masked mean, then L2 | [docs] [4–6] |
| multilingual E5 large-instruct | `Instruct: …\nQuery: …` / document without an instruction | Masked mean, then L2 | [docs] [7] |
| e5-mistral-7b-instruct | Instruction for query / document without it | Last significant token, then L2 | [docs] [47] |
| BGE-M3 / USER-bge-m3 | No prefixes needed for ordinary dense retrieval | CLS, then L2 | [docs] [9, 11] |
| ru-en-RoSBERTa | `search_query: …` / `search_document: …` | CLS recommended, then L2 | [docs] [10] |
| LaBSE in Sentence Transformers | No E5 prefixes | CLS, trained Dense with tanh, normalization | [code] [13] |
| Qwen3-Embedding | Instruction for query; document without instruction | Last significant token, L2; dimension selected separately | [docs] [14] |
| EmbeddingGemma 300M / 2 | `task: search result \| query: …` / `title: … \| text: …` | Mean and trained projection; after dimension reduction, repeat L2 | [docs] [16, 18] |

[assumption] For E5-instruct, a reasonable initial instruction is `Retrieve relevant emails and attachment passages for the query.` It describes the task, but its quality has not been measured here. Qwen's authors recommend English instructions for multilingual use as well [14].

[docs] The reviewed E5-instruct model is specifically `multilingual-e5-large-instruct`. English-language `e5-small-v2` and `e5-base-v2` are different models; seeing such a name in a Java module does not mean it contains multilingual E5. E5-Mistral is included as a large instruct model, not as a candidate for a typical CPU laptop [7, 24, 47].

[assumption] A ready-made ONNX file means the file exists, not that it has been confirmed to match the reference implementation. The tokenizer, mask, final-vector method, and normalization must match. Community exports of Qwen/Gemma need especially careful validation: they are not Java integrations guaranteed by the model authors [15, 17].

[code] The official E5 repositories contain ONNX files. Small/base have a `model_qint8_avx512_vnni.onnx` variant: its name indicates AVX-512 VNNI, not an arbitrary CPU. [assumption] Start with FP32 on an unknown laptop; select INT8 according to its instruction set [8].

[docs] None of the reviewed models has an explicit license limited to non-commercial use. However, Gemma 300M uses its own terms, not MIT/Apache: they include use restrictions and redistribution requirements. The Gemma 2 model card states Apache-2.0; terms for one version cannot be carried over to another [16, 18, 19].

### В2. How can the models be run from Java?

[docs] ONNX Runtime Java loads a graph with `OrtEnvironment.createSession(...)`, accepts tensors, and returns graph outputs. The official CPU artifact supports Windows x64; it does not implement tokenization or derive a single vector from transformer outputs [20].

[docs] DJL loads a model through `Criteria`, a file path, and a selected engine; the tokenizer is loaded through `HuggingFaceTokenizer` with `tokenizer.json`. The latter uses Rust through JNI. Native dependencies must be bundled in advance for offline operation [21–23].

[docs] LangChain4j packages several models in Maven modules, including `-q` variants. The reviewed list includes English MiniLM/BGE/E5 and Chinese BGE; it does not include a ready `multilingual-e5-base`. The old repository moved the modules into the main `langchain4j` repository on 2025-12-04: its archival status does not mean support for the modules ended [24].

[docs] Spring AI accepts graph and tokenizer URIs through `spring.ai.embedding.transformer.onnx.model-uri` and `…tokenizer.uri`. Local files can be specified. The documentation describes configuring the output name, padding input lengths, and the ONNX external-data issue for graphs larger than 2 GB [25].

[code] `TransformersEmbeddingModel` version 2.0.2 expects a three-dimensional output, `float[batch][tokens][dimensions]`, then calls `meanPooling`, without L2. [assumption] Therefore a ready two-dimensional `sentence_embedding`, CLS models, and Qwen with last-token pooling cannot be connected merely by changing a URI or output name [26].

[assumption] For E5, it is simpler to use ORT Java directly and DJL only for the tokenizer, then perform masked mean pooling and L2 with ordinary arrays. This avoids depending on an additional DJL engine to produce the vector. Spring AI can remain the interface. Consider a ready Spring wrapper after verifying that its results match [20–26].

[code] As of 2026-10-24, Maven Central has published ORT Java 1.31.0 on 2026-10-09 and DJL Tokenizers 0.39.0 on 2026-10-20. DJL documentation still shows 0.38.0. This confirms recent releases, not compatibility between these versions [21, 27].

[docs] DJL warns of a Windows VC runtime conflict between ORT 1.21+ and some JDKs; the page names JDK 17 as tested. [assumption] Java 25 specifically needs a separate check: general Windows support does not substitute for that check [22].

### В3. What does Ollama offer?

| Model in library | Download size and catalog context | Applicability |
|---|---|---|
| `bge-m3:567m` | 1.2 GB, 8K | [docs] Multilingual; Russian retrieval evaluated separately in [1] [28] |
| `qwen3-embedding:0.6b` | 639 MB, 32K | [docs] Smallest Qwen3-Embedding; a large context does not eliminate the need for chunking [29] |
| `qwen3-embedding:4b`, `:8b` | 2.5 / 4.7 GB, 40K in catalog | [docs] Larger variants; `latest` currently corresponds to 8B [29] |
| `embeddinggemma:300m` | 622 MB, 2K; Ollama ≥0.11.10 | [docs] This is the 2025 version; support for Gemma 2 under this tag is unconfirmed [30] |

[docs] The 40K context for larger Qwen models in Ollama differs from 32K in the original model card. [assumption] This describes a specific build and is not a reason to expand the input of every Qwen implementation to 40K; use identical input limits for comparisons [14, 29].

[docs] The current method is `POST /api/embed`; the `input` field accepts a string or array. It provides `keep_alive`, `dimensions`, and `truncate`; with `truncate:false`, exceeding the context returns an error. The response contains an `embeddings` array and time/token counters [31].

[assumption] Example local request body for BGE-M3:

```json
{
  "model": "bge-m3:567m",
  "input": ["Условия продления договора и сроки уведомления"],
  "truncate": false,
  "keep_alive": "10m"
}
```

[docs] Ollama runs as a native Windows application; current documentation requires Windows 10 22H2 or newer and at least 4 GB for installation without models [33]. [assumption] For Java, this is a simple HTTP client with no JNI in the MCP process; computation failures can be isolated in a separate process, but that introduces a service with its own lifecycle.

[docs] The default bind address is loopback, port 11434. Cloud features can be disabled with `OLLAMA_NO_CLOUD=1` or `disable_ollama_cloud` in the configuration [32]. [assumption] To meet the requirement that “text does not leave the laptop,” use local models, an explicit tag/build identifier, and this mode; do not assume every Ollama name is local.

[assumption] Compared with ONNX, Ollama is easier to install and switch models and makes GPU use simpler; ORT offers more control over inputs, threads, and distribution contents. HTTP adds serialization, while final speed also depends on quantization and model implementation. There is no evidence here that one runtime is superior.

### В4. Which external APIs are available?

[assumption] Prices below are per 1 million input tokens for an ordinary text request, excluding discounts, free quotas, and currency conversion. This is a snapshot as of 2026-10-24. Russian-language support does not prove better performance on our email.

| Provider / model | Russian language | Price / 1 million tokens | Retention and training | Source |
|---|---|---|---|---|
| OpenAI `text-embedding-3-small` / `-large` | [docs] Multilingual | [docs] $0.02 / $0.13 | [docs] `/v1/embeddings`: no training, no application state; abuse-monitoring logs usually retained for up to 30 days, with exceptions. ZDR is available to eligible organizations after approval | [34, 35] |
| Voyage `voyage-4-lite` / `voyage-4` / `voyage-4-large` | [docs] Multilingual | [docs] $0.02 / $0.06 / $0.12 | [docs] Zero retention after opting out of data use for training; payment method and administrator required. Opt-out applies to subsequent data and does not undo prior use | [36, 37] |
| Cohere `embed-v5.0-fast` / `-pro` | [docs] Russian listed among languages | [docs] $0.08 / $0.12 | [docs] For paid SaaS: opt out of training in settings; logs usually retained for 30 days, with exceptions; ZDR by approval. Trial keys have different terms | [38, 39] |
| Google `gemini-embedding-2` | [docs] Multilingual model | [docs] $0.20; Batch $0.10 | [docs] Paid mode: requests are not used to improve models; temporary logs for abuse monitoring, exact retention period not specified here. Free mode permits training and human review | [40, 41] |
| Yandex `text-search-doc` / `text-search-query` | [docs] Russian and English | [docs] ₽10.10 including VAT | [docs] By default, data may be retained to improve the service; disable this. A separate retention period for embeddings was not found; disabling logging does not disable other retention mechanisms | [42–44] |
| GigaChat `Embeddings-2` | [docs] Russian-language provider; comparable Russian result not checked here | [docs] ₽14; asynchronous ₽7 under published tariffs for eligible legal entities | [assumption] Retention period and training terms for this method have not been established; do not assume ZDR. From 2026-09-01, new customers are directed to cloud.ru under separate terms | [45, 46] |

[docs] Voyage 3.5 series is now in the legacy-models section; series 4 prices were checked on the current page. Cohere released Embed 5 on 2026-09-30 [36, 38]. [docs] At release on 2025-07-14, Google `gemini-embedding-001` cost $0.15; the current table above describes a different model, `gemini-embedding-2` [40, 48].

[assumption] Any external option should require explicit configuration and be used only after sensitive-data filtering. This applies to documents, queries, and chunk headers. This report does not choose where filtering belongs in other local operations; see `docs/plan.md`, 5.4.

### В5. How should email and attachments be chunked?

[assumption] The initial parameters below are a design recommendation, not the result of an email experiment. Count tokens with the selected model's tokenizer, including subject, prefix, and special tokens; equal character counts do not mean equal token counts.

| Model | Useful text per chunk | Overlap | Limit |
|---|---|---|---|
| E5 small/base/large/instruct | 250–350 tokens | 30–50 | [assumption] Keep the full input ≤512; count instruction and subject separately [4–7] |
| BGE-M3 / USER-bge-m3 | 300–500 | 40–60 | [assumption] Use 300 for an overall comparison with E5; reserve 8K for separate tests [9, 12] |
| Qwen3-0.6B / Gemma 300M / Gemma 2 | 300–500 | 40–60 | [assumption] Do not fill the large window automatically; 300 tokens is a common starting point [14, 16, 18] |
| LaBSE in standard configuration | 160–210 | 20–30 | [assumption] Sentence Transformers limit is 256; split an initial 300-token chunk [13] |

[assumption] Proposed processing order for email and attachments:

1. Extract readable text from HTML, preserving lists, paragraphs, and table boundaries. Do not include service MIME headers or HTML markup in the vector.
2. Separate new reply text, quotations, and signature. Do not compute again for quotations already present in another email; retain a link to the original message. If the original is missing or the quotation contains edits, keep it as a separate chunk.
3. Exclude signatures from semantic text using conservative rules, but preserve them in the original email. Do not automatically remove everything after the first `С уважением`.
4. Keep a short email as one chunk. Split long email by paragraph and sentence; add the subject as brief context without repeating the full thread history.
5. In a contract, preserve section heading and clause number. In XLSX, repeat column names for a group of rows and store sheet and range; a row without its header loses meaning.
6. Do not combine different attachments or emails in one vector. In search results, merge adjacent chunks from the same source so overlap does not produce duplicates.

Chunk metadata — [assumption], for reproducibility and returning to the source:

| Group | What to store |
|---|---|
| Ownership | `connection`, stable internal `messageKey`, `threadKey`; `InternetMessageId` as an additional attribute |
| Source type | Body/quote/attachment, `attachmentKey`, name and MIME type; path of an attached email |
| Location | `chunkId`, chunk number, offsets in extracted text; PDF page, section, XLSX sheet/range |
| Context | Subject, email date, language, relationship to parent email and original quotation; addresses as separate attributes |
| Reproducibility | Hash of text actually submitted, versions of extraction, quote cleanup, chunking, and applied filter |
| Vector | Model ID and revision, tokenizer, prefixes, vector-generation method, normalization, dimension, quantization |

[assumption] Changing the model or text-preparation rules requires a separate vector set or recomputation. Equal dimensions do not make vector spaces from different models compatible. A text hash avoids repeating work for identical attachments while retaining separate links to emails and mailboxes.

### В6. How long would 100,000 chunks of 300 tokens take?

[code] In encodechka, `SpeedTask.eval` calls the model sequentially for each text in `data/sentences_sample.txt` and returns `(elapsed / count) × 1000`, milliseconds per text. This is not batch processing. The README reports 40.86 ms/text for small, 130.61 for base, and 523.4 for BGE-M3; CPU parameters and average text length are not established [2, 3].

[assumption] A reliable forecast for a particular laptop cannot be derived from these numbers. The conditional estimate below is for planning on a Windows laptop with 6–8 physical cores, 16 GB RAM, plugged in, and a CPU with AVX2; suitability of that CPU was not measured.

Calculation assumptions — [assumption], not characteristics of the published experiment:

- Assume an unknown average of 64 tokens for the original short text. Target input length is 300 tokens after adding context.
- Time is assumed to scale proportionally to length: multiplier `300 / 64 = 4.6875`. This is a simplification: attention, fixed overhead, and different tokenizers violate linear scaling.
- The laptop/source-system speed ratio is unknown: time multiplier `0.5–2`. This is a chosen sensitivity scenario, not a confidence interval.
- Tokenization/storage overhead adds a multiplier of `1.2`; no speedup from INT8, batching, or ONNX over the original runtime is assumed in advance.

Formula — [assumption]: `T = 100000 × t_ms / 1000 / 3600 × 4.6875 × (0.5…2) × 1.2`.

| Model | Original t, ms/short text [code] | Simple multiplication by 100,000, no adjustments | Conditional for 300 tokens [assumption] |
|---|---|---|---|
| E5-small | 40.86 [2, 3] | 1.14 h | 3–13 h |
| E5-base | 130.61 [2, 3] | 3.63 h | 10–41 h |
| BGE-M3 | 523.4 [2, 3] | 14.54 h | 41–164 h |

[assumption] Actual results may fall outside the range: CPU, text length, threads, warm-up, and equivalence to a modern export are unknown for the source. The user needs a separate benchmark on the selected laptop to decide timing; that is outside this task. If the original length is assumed to be 128 instead of 64, the estimated time is halved.

[assumption] 100,000 chunks represent 30 million tokens, not 100,000 emails with attachments. If the average number of chunks per email is `k`, the volume is roughly `k` times greater. FP32 vector storage alone: dimensions 384 / 768 / 1024 require 153.6 / 307.2 / 409.6 MB for 100,000 records. This is `N × d × 4` arithmetic, excluding metadata and search structures.

## Comparison tables

### Models

`M/B` means millions/billions of parameters. Speed is ms/short text in encodechka, not the speed for a 300-token input in Java. `?` marks a specific gap explained below. In the quality column, `R/A` and `S` are defined in В1; `MM` is multilingual MTEB, not a Russian score.

| Model | Parameters | Dimensions | Max tokens | License | ONNX | Russian quality | CPU speed | Source |
|---|---|---|---|---|---|---|---|---|
| multilingual-e5-small | [docs] 118M | [docs] 384 | [docs] 512 | [docs] MIT | [code] In original repository | [docs] R/A 65.8/56.4 | [code] 40.86 ms | [1–4, 8] |
| multilingual-e5-base | [docs] 278M | [docs] 768 | [docs] 512 | [docs] MIT | [code] In original repository | [docs] R/A 67.1/57.1 | [code] 130.61 ms | [1–3, 5, 8] |
| multilingual-e5-large | [docs] 560M | [docs] 1024 | [docs] 512 | [docs] MIT | [code] Available, FP32 with external weights | [docs] R/A 74.0/60.4 | [code] 506.8 ms | [1–3, 6, 8] |
| multilingual-e5-large-instruct | [docs] 560M | [docs] 1024 | [docs] 512 | [docs] MIT | [code] Available, with external weights | [docs] R/A 75.1/64.7 | [code] 501.5 ms | [1–3, 7, 8] |
| e5-mistral-7b-instruct | [docs] 7.11B | [docs] 4096 | [docs] ≤4096 recommended | [docs] MIT | ? Ready author export unconfirmed | [docs] R/A 74.1/64.9 | ? No comparable benchmark found | [1, 47] |
| BAAI/bge-m3 | [docs] ≈567M | [docs] 1024 | [docs] 8192 | [docs] MIT | [code] In original repository | [docs] R/A 74.8/60.8 | [code] 523.4 ms | [1–3, 9] |
| ai-forever/ru-en-RoSBERTa | [docs] 404M | [docs] 1024 | [docs] 512 | [docs] MIT | ? Not in reviewed author tree; check export | [docs] R/A 66.5/60.4 | ? No benchmark found | [1, 10] |
| deepvk/USER-bge-m3 | [code] 359M | [docs] 1024 | [code] 8192 | [docs] Apache-2.0 | ? Author ONNX not found; check export | [code] S 0.799; author Russian evaluation on 30 tasks: 0.706 | [code] 523.4 ms; independent benchmark? | [2, 3, 11, 12] |
| sentence-transformers/LaBSE | [code] 471M | [docs] 768 | [code] 256 in ST configuration | [docs] Apache-2.0 | [code] Available; check Dense after transformer | [code] S 0.739 | [code] 135.1 ms | [2, 3, 13] |
| Qwen3-Embedding-0.6B | [docs] ≈0.6B | [docs] 1024, reduced 32–1024 | [docs] 32768 | [docs] Apache-2.0 | [code] `onnx-community`, not author-provided | ? Separate Russian result not found; [docs] MM 64.33 | ? No comparable CPU benchmark found | [14, 15] |
| EmbeddingGemma 300M | [docs] ≈308M, per authors | [docs] 768 / 512 / 256 / 128 | [docs] 2048 | [docs] Gemma Terms | [code] `onnx-community`, not author-provided | ? Separate Russian result not found; [docs] MM 61.15 | ? No Windows CPU result; EdgeTPU is a different device | [16, 17, 19] |
| EmbeddingGemma 2 | [docs] 740M total, 270M text component | [docs] 768 / 512 / 256 / 128 | [docs] 8192 | [docs] Apache-2.0 per model card | ? Not in author tree; Java export unconfirmed | ? Separate Russian result not found; [docs] MM 61.36 | ? No text-only Windows CPU result found | [18] |

[docs] In the authors' comparison, USER-bge-m3 improves the overall average over BGE-M3, but its Retrieval Average is lower: 0.934 versus 0.945. [assumption] Therefore, the higher Mean S is not sufficient reason to replace BGE-M3 for email retrieval [11].

### Java runtimes

[code] Sizes below are compressed JAR sizes from Maven Central, not in-memory DLL sizes. For ORT 1.31.0, 50,942,147 bytes (50.9 MB); Tokenizers 0.39.0, 18,136,686 (18.1 MB). Combined baseline: 69.1 MB, plus Java wrappers and weights. This is an example composition, not a dependency set approved for Spring AI or LangChain4j. PyTorch native CPU 2.7.1 Windows JAR is 75.5 MB, plus separate JNI and tokenizer; the original artifact was also checked [27].

| Java runtime | Model loading | Tokenizer | Windows | Native library size | Activity |
|---|---|---|---|---|---|
| ONNX Runtime Java | [docs] Local ONNX through `createSession`, external weights separately | [docs] Not included; add DJL Tokenizers | [docs] CPU x64 | [code] ORT JAR 50.9 MB; 69.1 MB baseline with Tokenizers; unpacked size ? | [code] Maven 1.31.0, 2026-10-09 [20, 27] |
| DJL | [docs] `Criteria` + path + engine + Translator | [docs] HF `tokenizer.json` through Rust/JNI; separate SentencePiece available | [docs] x64 for the ORT/PyTorch options considered; verify Java 25 | [code] ORT composition as above; alternatively PyTorch native 75.5 MB + JNI + Tokenizers, excluding weights | [code] Tokenizers 0.39.0, 2026-10-20; engine pages show 0.38.0 [21–23, 27] |
| LangChain4j in-process | [docs] Model constructor from Maven module; multilingual E5 needs a custom module/wrapper | [code] Included in ONNX modules, prepared resources | [assumption] Through platform ORT/JNI dependencies; check selected release | [assumption] ORT + Tokenizers + bundled weights; size depends on module and pinned versions | [docs] Moved to main repository 2025-12-04; current page contains 1.22.0-beta32 [24] |
| Spring AI ONNX Transformers | [docs] Model/tokenizer URI, output name; local files allowed | [code] DJL HuggingFaceTokenizer | [assumption] Through ORT/DJL; JDK 25 unverified | [assumption] ORT + Tokenizers and selected NDArray engine dependencies; 69.1 MB is only a baseline | [code] Implementation 2.0.2 reviewed, documentation available; check BOM compatibility [25–27] |

## Recommendation

[assumption] I recommend E5-base as the initial optional model and E5-small as the lighter variant. Their advantage for an initial trial is a reproducible, simple vector-generation method, published Russian scores, and ONNX availability without a custom export. The cost of base is larger weights and longer initial processing [1–8].

[assumption] Runtime: ONNX Runtime Java CPU with DJL HuggingFace Tokenizers, local pinned files, and an adapter to the Spring AI interface. Agree on specific dependency versions during implementation; versions from this study are not a ready BOM. Tokenization and normalization accuracy matter more than the Java wrapper choice [20–27].

[assumption] BGE-M3 is the next model to compare if E5 does not find enough Russian passages; E5-large-instruct is another option if its processing time is acceptable. Qwen3-0.6B is convenient to investigate through Ollama. EmbeddingGemma 2 merits a separate check after Russian quality and Java execution are confirmed.

[assumption] Choose using a small user-provided set of queries over emails, contracts, and spreadsheets: compare BM25 with vector augmentation, the share of relevant passages retrieved, query latency, and processing time. Do not switch models automatically on failure: recomputation or a separate vector set is needed. The decision remains with the user.

## What could not be verified

- [assumption] No benchmarks were run on the user's laptop: CPU, RAM, threads, cooling, and the effects of INT8 and batching are unknown. В6 is a scenario; a future separate experiment should measure 300-token chunks on the selected runtime.
- [code] The CPU and average text length of encodechka input lines could not be recovered; identical 523.4 ms results for USER-bge-m3 and BGE-M3 do not prove that each model had an independent benchmark [2, 3].
- [docs] The reviewed model cards provide no comparable Russian score for Qwen3 and the two Gemma models. A Russian evaluation in one MTEB version or retrieval tests on local examples are needed [14, 16, 18].
- [assumption] ONNX graphs were not executed: equivalence to reference vectors, external-weight support, CLS/Dense/projections, quantized operators, and Windows/JDK 25 remain unverified. Models, native JARs, and libraries were not downloaded; documentation, code, and metadata were read.
- [code] Author ONNX files for RoSBERTa, USER-bge-m3, and Gemma 2 were absent from the reviewed trees; availability of a ready author ONNX for E5-Mistral was not established. This does not claim that export is impossible or third-party builds do not exist [10–13, 18, 47].
- [assumption] Unpacked DLL size and the full dependency set of a specific Java module were not measured: JAR sizes in the table are not RAM usage [27].
- [assumption] Exact embedding-retention periods for Google, Yandex, and GigaChat, and terms for the new GigaChat payment channel, were not established. Check the selected contract and provider response; do not carry over terms for chat or another endpoint [41, 44, 46].
- [assumption] Availability of foreign APIs for a particular account/region, limits, and actual training opt-out settings were not checked; no inputs or API calls were made.

## What else to investigate

- [assumption] Reranking retrieved chunks and its additional CPU cost.
- [assumption] Separate evaluation on contracts and spreadsheets: ordinary multilingual rankings do not replace this corpus; search-engine selection belongs to RT-06.
- [assumption] Protection of local vectors and backups; detailed architecture for sensitive-data filtering remains a separate project question.

## Sources

All access dates below are 2026-10-24. If a release date is not given, the page does not state it explicitly. Maven catalogs and individual JSON/source files unavailable to the web viewer were read into memory with anonymous HTTP GET, without saving files or executing received code.

1. ruMTEB, original paper, Tables 4–5 — https://arxiv.org/html/2408.12503v1 (read 2026-10-24; published 2024-08-22).
2. encodechka, README with scores and timings — https://github.com/avidale/encodechka (read 2026-10-24; mutable table, dates of individual measurements not stated).
3. encodechka, `SpeedTask` in `encodechka_eval/tasks.py` — https://raw.githubusercontent.com/avidale/encodechka/master/encodechka_eval/tasks.py (read 2026-10-24; methodology code).
4. multilingual-e5-small, model card — https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md and config https://huggingface.co/intfloat/multilingual-e5-small/raw/main/config.json (read 2026-10-24; 2023 model).
5. multilingual-e5-base — https://huggingface.co/intfloat/multilingual-e5-base (read 2026-10-24; 2023 model).
6. multilingual-e5-large — https://huggingface.co/intfloat/multilingual-e5-large (read 2026-10-24; 2023 model).
7. multilingual-e5-large-instruct — https://huggingface.co/intfloat/multilingual-e5-large-instruct (read 2026-10-24; 2024 model).
8. Author E5 ONNX: https://huggingface.co/intfloat/multilingual-e5-small/tree/main/onnx ; https://huggingface.co/intfloat/multilingual-e5-base/tree/main/onnx ; https://huggingface.co/intfloat/multilingual-e5-large/tree/main/onnx ; https://huggingface.co/intfloat/multilingual-e5-large-instruct/tree/main/onnx (read 2026-10-24; file composition additionally checked through https://huggingface.co/api/models/intfloat/multilingual-e5-small and https://huggingface.co/api/models/intfloat/multilingual-e5-base).
9. BGE-M3 — https://huggingface.co/BAAI/bge-m3 and https://huggingface.co/BAAI/bge-m3/tree/main/onnx (read 2026-10-24; 2024 model).
10. ru-en-RoSBERTa — https://huggingface.co/ai-forever/ru-en-RoSBERTa and https://huggingface.co/ai-forever/ru-en-RoSBERTa/tree/main (read 2026-10-24; repository created 2024-07-29, paper [1]).
11. USER-bge-m3, model card and author results — https://huggingface.co/deepvk/USER-bge-m3 (read 2026-10-24; repository created 2024-07-05).
12. USER-bge-m3, metadata and context — https://huggingface.co/api/models/deepvk/USER-bge-m3 ; https://huggingface.co/deepvk/USER-bge-m3/raw/main/config.json ; https://huggingface.co/deepvk/USER-bge-m3/raw/main/sentence_bert_config.json (read 2026-10-24; revision `0cc6cfe48e260fb0474c753087a69369e88709ae`).
13. LaBSE — https://huggingface.co/sentence-transformers/LaBSE ; https://huggingface.co/sentence-transformers/LaBSE/raw/main/sentence_bert_config.json ; https://huggingface.co/sentence-transformers/LaBSE/raw/main/modules.json ; https://huggingface.co/sentence-transformers/LaBSE/tree/main/onnx ; https://huggingface.co/api/models/sentence-transformers/LaBSE (read 2026-10-24; model architecture from 2020, ST packaging updated).
14. Qwen3-Embedding-0.6B, model card, family, and results — https://huggingface.co/Qwen/Qwen3-Embedding-0.6B (read 2026-10-24; paper published 2025-06-05; authors recorded MTEB comparison in 2025).
15. Community Qwen3 export — https://huggingface.co/onnx-community/Qwen3-Embedding-0.6B-ONNX (read 2026-10-24).
16. EmbeddingGemma 300M — https://huggingface.co/google/embeddinggemma-300m ; https://ai.google.dev/gemma/docs/embeddinggemma/model_card ; https://developers.googleblog.com/introducing-embeddinggemma/ (read 2026-10-24; released 2025-09-04).
17. Community EmbeddingGemma export — https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX (read 2026-10-24).
18. EmbeddingGemma 2 — https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2 ; https://developers.googleblog.com/en/google-ai-edge-with-embeddinggemma-2/ ; https://huggingface.co/google/embeddinggemma-2/tree/main (read 2026-10-24; released 2026-10-06).
19. Gemma Terms — https://ai.google.dev/gemma/terms (read 2026-10-24; revision 2026-04-01).
20. ONNX Runtime Java — https://onnxruntime.ai/docs/get-started/with-java.html (read 2026-10-24).
21. DJL HuggingFace Tokenizers — https://docs.djl.ai/master/extensions/tokenizers/index.html (read 2026-10-24; example 0.38.0).
22. DJL ONNX Runtime engine — https://docs.djl.ai/master/engines/onnxruntime/onnxruntime-engine/index.html (read 2026-10-24; warning about VC runtime and JDK on Windows).
23. DJL dependency management — https://docs.djl.ai/master/docs/development/dependency_management.html (read 2026-10-24; native dependency composition and offline distribution).
24. LangChain4j in-process — https://docs.langchain4j.dev/integrations/embedding-models/in-process/ ; module-move announcement https://raw.githubusercontent.com/langchain4j/langchain4j-embeddings/main/README.md (read 2026-10-24; move on 2025-12-04).
25. Spring AI ONNX embeddings — https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html (read 2026-10-24; page shows 2.0.2).
26. Spring AI 2.0.2, `TransformersEmbeddingModel.java` — https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.2/models/spring-ai-transformers/src/main/java/org/springframework/ai/transformers/TransformersEmbeddingModel.java (read 2026-10-24; reviewed `call`, `meanPooling`, tokenizer/graph loading).
27. Maven Central, sizes and releases: https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.0/ ; https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/maven-metadata.xml ; https://repo.maven.apache.org/maven2/ai/djl/huggingface/tokenizers/0.39.0/ ; https://repo.maven.apache.org/maven2/ai/djl/huggingface/tokenizers/maven-metadata.xml ; https://repo.maven.apache.org/maven2/ai/djl/pytorch/pytorch-native-cpu/2.7.1/ (read 2026-10-24; release dates 2026-10-09, 2026-10-20, and 2025-08-12 respectively).
28. Ollama BGE-M3 — https://ollama.com/library/bge-m3 (read 2026-10-24).
29. Ollama Qwen3-Embedding — https://ollama.com/library/qwen3-embedding (read 2026-10-24).
30. Ollama EmbeddingGemma — https://ollama.com/library/embeddinggemma (read 2026-10-24).
31. Ollama `/api/embed` — https://docs.ollama.com/api/embed (read 2026-10-24).
32. Ollama FAQ — https://docs.ollama.com/faq (read 2026-10-24; local mode and bind address).
33. Ollama Windows — https://docs.ollama.com/windows (read 2026-10-24).
34. OpenAI embeddings and pricing — https://developers.openai.com/api/docs/models/text-embedding-3-small ; https://developers.openai.com/api/docs/models/text-embedding-3-large ; https://developers.openai.com/api/docs/guides/embeddings (read 2026-10-24).
35. OpenAI data controls, `/v1/embeddings` row — https://developers.openai.com/api/docs/guides/your-data (read 2026-10-24).
36. Voyage pricing and models — https://docs.voyageai.com/docs/pricing ; https://docs.voyageai.com/docs/embeddings (read 2026-10-24; pricing page shows an update around 2026-10-12).
37. Voyage FAQ and Terms — https://docs.voyageai.com/docs/faq ; https://www.voyageai.com/tos (read 2026-10-24; opt-out from training and retention).
38. Cohere Embed 5 — https://docs.cohere.com/docs/cohere-embed ; https://cohere.com/blog/embed-5 (read 2026-10-24; release and prices 2026-09-30).
39. Cohere Enterprise Data Commitments — https://cohere.com/enterprise-data-commitments (read 2026-10-24; revision 2025-12-05).
40. Gemini API pricing — https://ai.google.dev/gemini-api/docs/pricing (read 2026-10-24; Gemini Embedding 2 section).
41. Gemini API Additional Terms — https://ai.google.dev/gemini-api/terms (read 2026-10-24; effective 2026-03-23, page updated 2026-04-28).
42. Yandex, vectorization models — https://aistudio.yandex.ru/ru/docs/ai-studio/concepts/embeddings (read 2026-10-24).
43. Yandex, vectorization pricing — https://aistudio.yandex.ru/ru/docs/ai-studio/pricing (read 2026-10-24; ₽0.0101 per 1,000 tokens including VAT).
44. Yandex, retention policy — https://aistudio.yandex.ru/en/docs/ai-studio/concepts/security/data-storage (read 2026-10-24; text available in search view of official page, direct repeat request returned an error).
45. GigaChat Embeddings-2 — https://developers.sber.ru/docs/ru/gigachat/models/embeddings-2 (read 2026-10-24).
46. GigaChat, legal-entity tariffs — https://developers.sber.ru/docs/ru/gigachat/tariffs/legal-tariffs (read 2026-10-24; updated 2026-10-07).
47. E5-Mistral — https://huggingface.co/intfloat/e5-mistral-7b-instruct (read 2026-10-24; input limits and vector-generation method).
48. Google, Gemini Embedding 001 release — https://developers.googleblog.com/en/gemini-embedding-available-gemini-api/ (read 2026-10-24; published 2025-07-14).

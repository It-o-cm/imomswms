# Local product embeddings

The similar-products API uses `Product.characteristics`, not a first-word name heuristic.
`intfloat/multilingual-e5-small` runs in the Java process through ONNX Runtime (CPU).
DJL performs local tokenization. Both DJL offline mode and telemetry opt-out are enabled.
No descriptions or inference requests are sent to an external service.

## Installation

From the backend project directory:

    python3 scripts/install-embedding-model.py

The installer downloads the official model and tokenizer from the revision pinned in the script,
records their SHA-256 hashes, and saves the model card under `models/multilingual-e5-small/`.
Weights are intentionally Git-ignored. The download needs Internet; subsequent inference does not.
The FP32 model and tokenizer occupy approximately 470 MiB combined. Allow additional JVM/native memory.
The model's declared license is MIT; retain its model card and applicable notices when distributing it.
ONNX Runtime and DJL dependencies have their own licenses/notices.

To deploy elsewhere, copy this directory with the application and configure
`EMBEDDING_MODEL_DIRECTORY` to its absolute path. The default is relative to the backend working directory.
Set `imomswms.embeddings.enabled=false` to explicitly disable the feature.
A missing/failed model is reported in the panel; no lexical or remote fallback is silently used.

## Indexing and matching

* Symmetric E5 `query: ` prefix, 512-token truncation, attention-masked mean pooling, L2 normalization.
  Long descriptions are limited to the model's first 512 tokens. Put distinguishing characteristics first.
* A background worker incrementally scans active products approximately every 30 seconds, with
  inference outside database transactions. The first index may take time on a large catalog.
* Vectors are persisted in `product_embeddings` and cached in memory for search. Existing unchanged
  descriptions reuse the saved vector after restart. The cache stores roughly 59 MiB of vector values
  for 40,000 products (384 float32 values each), plus product/JVM overhead.
* A normalized-characteristics SHA-256 and a model/tokenizer/pooling version prevent reuse after changes.
* Missing characteristics are explicitly excluded. An absent statement is not treated as an absence of allergens.
* Candidates must still have the same sale type and temperature. Semantic proximity never authorizes a replacement.
* Distance is `1 - cosine_similarity`, in [0, 2]. It is **not** a probability or a safety score.
  E5 scores are often compressed; calibrate thresholds on real catalog descriptions.
* Results are distance-sorted and paginated, 40 at a time. Slider changes never save substitution rules.

`GET /product-substitutions/{ean}/similar?storeCode=...&distance=0.20&page=0&q=...`
returns candidates with their distances, total count, and explicit indexing/error metadata.
The existing store/role authorization and explicit substitution save API remain in force.

## Validation

Fast pooling/math tests run normally. Real inference/database integration tests require the installed assets:

    ./mvnw -Dimomswms.embeddings.integration=true -Dtest=LocalEmbeddingModelTest,ProductSemanticIndexTest test

These tests execute actual local inference and verify ranking, thresholds, persistence, pagination,
compatibility filtering, and reindexing of changed descriptions.

If JUnit reports `System property [imomswms.embeddings.integration] does not exist`,
the real-model tests are **skipped**, not failed. Set the flag above explicitly
(in the IDE VM options as well) to execute them. This opt-in is retained so a
checkout without the model assets can still run the ordinary unit tests.

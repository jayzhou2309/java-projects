# Cross-encoder model download (RAG-1, plan 2026-09-13-reranker, Milestone 2)

- Date: 2026-09-13 (about 10:25 SGT), by the Milestone 2 Worker, under the download approval recorded in the plan ("Decision" and "Amendment 1", download boundary).
- Destination: `models/cross-encoder-ms-marco-MiniLM-L-6-v2/` at the project root; `models/` is listed in `.gitignore`, and neither file is committed.
- Pre-download check: a HEAD request on each URL (following redirects) reported the sizes below before anything was fetched; both were within 1.5 times the expected size (about 90 MB and under 1 MB), and both paths exist in the repository.
- Redirect observed: `huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2` answers 307 to the repository's current name `cross-encoder/ms-marco-MiniLM-L6-v2` (same organisation and model, renamed without the hyphen); the model file is then served through Hugging Face's own storage CDN (`us.aws.cdn.hf.co`, xet bridge). No other host was contacted.
- Command: `curl -sSfL -o <file> <url>` for each, then `ls -l` and `shasum -a 256`.

| File | URL requested | Bytes | SHA-256 |
|---|---|---|---|
| `model.onnx` | https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx | 91,011,230 | `5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a` |
| `tokenizer.json` | https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json | 711,396 | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |

- Cross-checks against Hugging Face's own headers: the model's `x-linked-etag` is `5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a`, equal to the SHA-256 above, and `x-linked-size` is 91011230; the tokenizer's etag `688882a79f44442ddc1f60d70334a7ff5df0fb47` equals `git hash-object` of the downloaded file. The tokenizer resolved at repository commit `233902d25c440f23af6f7d6e94d2946bac0bee0a`.
- The checksums are pinned in `application.yaml` (`rag.retrieval.cross-encoder.model-sha256`, `tokenizer-sha256`); startup with the cross-encoder enabled refuses any other file. The model version recorded in evaluation snapshots is `5d3e70fd0c9f`.
- Maven dependencies added the same day from Maven Central (approved in the same decision): `com.microsoft.onnxruntime:onnxruntime:1.29.0` and `ai.djl.huggingface:tokenizers:0.38.0` (transitively `ai.djl:api:0.38.0`, `com.google.code.gson:gson:2.13.2`, `net.java.dev.jna:jna:5.17.0`). Both jars bundle macOS arm64 native libraries (`ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib`, `native/lib/osx-aarch64/cpu/libtokenizers.dylib`).
- Runtime downloads: none. DJL extracted the bundled tokenizer library to `~/.djl.ai/tokenizers/0.21.0-0.38.0-cpu-osx-aarch64/` (log line `Extracting native/lib/osx-aarch64/cpu/libtokenizers.dylib to cache`), and a run with every HTTP(S) proxy pointed at a closed local port passed (`live-test-network-blocked.log`). DJL's code path to `https://publish.djl.ai/tokenizers/` (CUDA builds) is closed off by pinning `RUST_FLAVOR=cpu` and failing if the jar has no library for the platform.

To reproduce on another machine, from the project root:

```
mkdir -p models/cross-encoder-ms-marco-MiniLM-L-6-v2 && cd models/cross-encoder-ms-marco-MiniLM-L-6-v2
curl -sSfL -o model.onnx https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx
curl -sSfL -o tokenizer.json https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/tokenizer.json
shasum -a 256 model.onnx tokenizer.json
```

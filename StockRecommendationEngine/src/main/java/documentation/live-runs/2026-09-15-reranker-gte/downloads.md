# Second reranker model download (plan 2026-09-15-reranker-ettin, Milestone 1 with amendment 1)

- Date: 2026-09-15 (about 11:22 SGT), by the Milestone 1 Worker, under the download approval recorded in the plan's Status, amendment 1 (Jay: `Alibaba-NLP/gte-reranker-modernbert-base` at commit f7481e6055501a30fb19d090657df9ec1f79ab2c, three files, sizes and model SHA-256 listed there).
- Destination: `models/gte-reranker-modernbert-base/` at the project root; `models/` is listed in `.gitignore`, and no file is committed. The earlier, unused `models/cross-encoder-ettin-reranker-32m-v1/` files (amendment 1) were left as they were.
- Pre-download check: a HEAD request on each URL below (following redirects) before anything was fetched. Every response carried `x-repo-commit: f7481e6055501a30fb19d090657df9ec1f79ab2c`; the final sizes equal the approved sizes exactly (598,803,940; 3,583,499; 1,333 bytes); the model's `x-linked-etag` equals the approved LFS SHA-256 and its `x-linked-size` is 598803940.
- Redirects observed: `onnx/model.onnx` answers 302 to Hugging Face's storage CDN `us.aws.cdn.hf.co` (xet bridge); `tokenizer.json` and `config.json` answer 307 to `/api/resolve-cache/...` on `huggingface.co`. No other host was contacted.
- Command: `curl -sSfL -o <file> <url>` for each, then `ls -l`, `shasum -a 256`, and `git hash-object` on the two small files.

| File (saved as) | URL requested | Bytes | SHA-256 |
|---|---|---|---|
| `model.onnx` | https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/f7481e6055501a30fb19d090657df9ec1f79ab2c/onnx/model.onnx | 598,803,940 | `c6d3226502addbcd4d2cf273802957ebf8a2a6bf94037dcb9b1d95bfc01e5d93` |
| `tokenizer.json` | https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/f7481e6055501a30fb19d090657df9ec1f79ab2c/tokenizer.json | 3,583,499 | `2aea6ff4701d063e7e029b6be695a1659f2caaa2ae4fb0e8b18285818271becd` |
| `config.json` (reference only) | https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/f7481e6055501a30fb19d090657df9ec1f79ab2c/config.json | 1,333 | `c9316ff715158502dad782f35454eee18de984160618dc30afe7508feb46b7ce` |

- Cross-checks against Hugging Face's own headers: the model's SHA-256 above equals its `x-linked-etag` and the plan's approved value; `git hash-object` of the tokenizer is `f418c5fa0465aa7f506bf17c4533239f162ab8e9` and of the config `cde62268ea0c57b0ad2fe75c2ca4cad55ed6d0f5`, each equal to its response `etag`.
- The model and tokenizer checksums are pinned in `src/main/resources/application-reranker-gte.yaml` (`rag.retrieval.gte-reranker.model-sha256`, `tokenizer-sha256`); startup with that profile refuses any other file. The model version recorded in evaluation snapshots is `c6d3226502ad`.
- `config.json` facts read for reference: `ModernBertForSequenceClassification`, 22 layers, hidden size 768, one label, `classifier_pooling` mean, `pad_token_id` 50283, `max_position_embeddings` 8192.
- No Maven dependency was added: the model runs on the existing `com.microsoft.onnxruntime:onnxruntime:1.29.0` and `ai.djl.huggingface:tokenizers:0.38.0` (live-runs/2026-09-13-reranker/downloads.md). Runtime downloads: none; the same DJL defaults apply (RAG.md, Cross-encoder reranker, Network), because the model is scored by the unchanged OnnxCrossEncoderScorer.
- The current model's files were hashed before this download and again at the end of the milestone; both hashes are in `current-model-sha256.txt`.

To reproduce on another machine, from the project root:

```
mkdir -p models/gte-reranker-modernbert-base && cd models/gte-reranker-modernbert-base
B=https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base/resolve/f7481e6055501a30fb19d090657df9ec1f79ab2c
curl -sSfL -o model.onnx $B/onnx/model.onnx
curl -sSfL -o tokenizer.json $B/tokenizer.json
curl -sSfL -o config.json $B/config.json
shasum -a 256 model.onnx tokenizer.json config.json
```

# Plan: second reranker model beside the current one (cross-encoder/ettin-reranker-32m-v1), compared at identical settings

Orchestrated build per CLAUDE.md. Branch `reranker-ettin` stacked on `rerank-blend` (PR #20, itself on PR #19). Build:
`set -a && source .env && set +a && ./mvnw -q -o verify`; never export the application enable flags or `RAG_*` overrides in
a shell that runs tests. Validators run one at a time. No chat model call; evaluation runs make embedding calls only.

## Why

- In the traced reranked runs, the current model moves some accepted chunks up and others down (evidence
  `live-runs/2026-09-14-recall-one-factor/evidence-947.json`), and a blend of its order with the fused order equalled the
  default reference without exceeding it (plan `2026-09-14-rerank-blend.md`, Status). Jay asked (2026-09-15) to add a
  different reranker model in new files and test it against the current model, without deleting or modifying the
  current model.
- Model choice, from a Hugging Face search on 2026-09-15 (research notes committed with Milestone 1): no finance-tuned
  cross-encoder with a licence and a published evaluation was found. Among general rerankers with an Apache-2.0 licence
  and official ONNX weights, `cross-encoder/ettin-reranker-32m-v1` (ModernBERT, 10 layers x 384) reports a higher MTEB
  English retrieval score than the current model in its author's blog (self-reported; FiQA is one of the averaged
  tasks, not broken out) at an architecture-derived CPU cost close to the current model. Larger candidates
  (`Alibaba-NLP/gte-reranker-modernbert-base`, `BAAI/bge-reranker-v2-m3`) are estimated at about 10x to 27x the cost and do
  not fit the current windows and timeout; they are not in this plan. Benchmarks elsewhere are not evidence for SEC
  filings; only this project's evaluation set decides.

## Boundaries (Jay, 2026-09-15)

- The current model is untouched: no file under `models/cross-encoder-ms-marco-MiniLM-L-6-v2/` is modified or deleted;
  `application.yaml`'s `rag.retrieval.cross-encoder` keys, `CrossEncoderProperties`, `CrossEncoderConfiguration`,
  `OnnxCrossEncoderScorer`, and `CrossEncoderPairAssembler` behave exactly as before (a validator diffs them: no change,
  or a change proven behaviour-neutral by the existing tests and a reproduction run).
- The second model lives in new files: `models/cross-encoder-ettin-reranker-32m-v1/` (gitignored like `models/`), a new
  profile file `src/main/resources/application-reranker-ettin.yaml`, and new classes where the current ones cannot
  serve it. Findings from the file check (2026-09-15, repository commit b33e5ceb5110773ea9cf5e00c9bedc83a8c2afdd):
  the tokenizer is BPE with a five-piece `TemplateProcessing` pair template (`[CLS] A [SEP] B [SEP]`, three specials) but
  every special has type id 0, which `CrossEncoderPairAssembler` rejects (it requires A, A, B); `[PAD]` is id 50283;
  the tokenizer file carries a truncation block; ONNX input and output shapes are not yet checked.
- Only one reranker is active per application start: the new model's configuration is mutually exclusive with the
  current one (enabling both fails at startup with a clear message).
- Defaults unchanged: the current model stays the configured reranker, reranking stays off by default.
- Downloads: only the files listed in Milestone 1, from `huggingface.co/cross-encoder/ettin-reranker-32m-v1` at commit
  b33e5ceb5110773ea9cf5e00c9bedc83a8c2afdd, recorded in a `downloads.md` like the current model's.

## Frozen comparison (before any run with the new model)

- One factor: the model. Everything else equals the current-model reference run: `max-length` 512,
  `passage-scoring` max-window, `window-overlap-tokens` 64, `max-windows` 4, batch size 20, set v2, window 10, fusion
  defaults, trace on.
- Row A: `candidate-count` 40, `rerank-candidates` 20, timeout 2,000 ms, against current-model snapshot 613.
- Row B: `candidate-count` 200, `rerank-candidates` 40, timeout 4,000 ms, against current-model snapshot 947.
- Before the new-model rows, in the same session, one current-model reproduction of 947's settings must reproduce 947
  per question (rank, matched chunk); otherwise stop.
- Reported per row: per-question rank and reranked position of the best accepted chunk against the paired current-model
  run; hit@1, hit@3, hit@5, MRR, slices, per-ticker hit@5; questions entering and leaving the top 5 against the paired
  run; scoring time per question (median and maximum, from the scoring logs); fallbacks.
- Selection rule against the default reference 598 (as in plan `2026-09-14-retrieval-recall.md`, amendment 3), per row.
- A row with any rerank fallback gives no selection outcome; it may be repeated once after the CPU-sharing wait.
- No tuning: no other model file (quantized variants included), window, candidate, or timeout setting is tried in this
  plan. If neither row meets the rule against 598, that is the result.
- A row that meets the rule is a DECISION item for Jay; no default changes.

## Milestone 1: second model in new files

Scope: download the listed files; the profile file; new classes for the new model (pair assembler accepting its
template, scorer or configuration as needed) with unit tests; mutual exclusion; `downloads.md` and the research notes under
`live-runs/2026-09-15-reranker-ettin/`; RAG.md section for the second model and a change-log bullet.

Files to download (sizes from the Hugging Face API, 2026-09-15):
- `onnx/model.onnx`, 127,737,036 bytes, LFS SHA-256 31061d9f54e8303f5d95cf3433dcf99f50d5f6de283c3b8357452be8c824142f
- `tokenizer.json`, 3,583,327 bytes
- `config.json`, 1,730 bytes (reference only)

Correctness contract:
- E1. The current model's files are byte-identical before and after (SHA-256 recorded before and after), and the Boundaries
  list of current classes and keys is unchanged or proven behaviour-neutral.
- E2. With the profile active and the current model disabled, the application starts, verifies the new files' SHA-256,
  and scores pairs; the snapshot records a reranker name and version that tell the two models apart.
- E3. Enabling both models fails at startup naming both properties; with neither enabled the application starts as today.
- E4. The new assembler's rows equal the tokenizer's own pair encoding for the new model (ids, three specials, window
  arithmetic) on real text in an opt-in live test, including a phrase near a window boundary and a query that fills most
  of the window; rows never exceed `max-length`.
- E5. ONNX input names and output shape are checked at startup; an unexpected shape fails with a clear message.
- E6. Unit tests for the assembler and configuration pass without the model files; `verify` passes.
- Out of scope: any evaluation run, default change, other models.
- User-facing flow (UT): start with the profile; `POST /api/rag/retrieve` with `"rerank": true` returns a reranked result
  (strategy HYBRID_RRF_RERANKED, no fallback); start without the profile and confirm the current model is loaded as
  before.

## Milestone 2: comparison runs

Scope: the reproduction run, rows A and B with the new model (traced), evidence reports, comparison script, run log,
claims (ids C-901 to C-999), generated RAG.md block, Follow_Ups entry. Contract written in full before Milestone 2 starts,
from the frozen comparison above.

## Status

- 2026-09-15: plan written; approved by Jay, including the downloads listed in Milestone 1.
- 2026-09-15, amendment 1 (Milestone 1 stopped before code): the approved `cross-encoder/ettin-reranker-32m-v1`
  `onnx/model.onnx` loads under ONNX Runtime 1.29.0 but outputs `last_hidden_state` [batch, sequence, 384]; its scoring
  head is in separate Sentence-Transformers module files, so it cannot score pairs as approved (the plan's premise that
  three files suffice was wrong). Its three downloaded files stay unused under `models/cross-encoder-ettin-reranker-32m-v1/`
  (gitignored); the current model's files were checked byte-identical before and after. Jay chose (2026-09-15) to test
  `Alibaba-NLP/gte-reranker-modernbert-base` instead (Apache-2.0; `ModernBertForSequenceClassification`, 22 layers x 768;
  checked at repository commit f7481e6055501a30fb19d090657df9ec1f79ab2c: BPE tokenizer with the same five-piece pair
  template, every type id 0, `padding.pad_id` 50283, a truncation block). This amendment replaces, for the rest of the plan:
  - the model: `Alibaba-NLP/gte-reranker-modernbert-base` at that commit, in `models/gte-reranker-modernbert-base/`, profile
    file `src/main/resources/application-reranker-gte.yaml`, evidence under `live-runs/2026-09-15-reranker-gte/`; the
    plan file keeps its name;
  - the downloads (approved by Jay): `onnx/model.onnx` 598,803,940 bytes, LFS SHA-256
    c6d3226502addbcd4d2cf273802957ebf8a2a6bf94037dcb9b1d95bfc01e5d93; `tokenizer.json` 3,583,499 bytes; `config.json`
    1,333 bytes (reference only);
  - the timeout for the new model's rows only: 120,000 ms in both rows, to avoid fallbacks. The architecture-derived cost
    estimate is about ten times the current model per window, so the current timeouts would make most questions fall
    back. The timeout decides only whether a question falls back, never the order of a completed reranking, so the
    ranking comparison stays one factor (the model); scoring time is reported, and no row is judged fit for production
    use by this plan. The reference rows keep their recorded timeouts.
  - E5 now requires the output to be one score per pair ([batch, 1]); a startup check fails otherwise.
- 2026-09-15, amendment 2 (Milestone 1): the Boundaries finding that `CrossEncoderPairAssembler` rejects an all-type-0
  template, carried into amendment 1, is wrong: its reader requires the first two specials to match sequence A's type and
  the last to match B's, which 0, 0, 0 satisfies (checked on the downloaded gte tokenizer). The second model therefore runs
  through the unchanged `OnnxCrossEncoderScorer` and `CrossEncoderPairAssembler`; the new files are its properties, file
  checks, startup signature check, configuration with mutual exclusion, reranker subclass, passage tokenizer, and profile.
  Two existing classes outside the Boundaries list changed: `FilingRetrievalProperties`' upper bound for
  `rerank-timeout-ms` (default unchanged) so the amended timeout is accepted, and a default method on `PassageTokenizer`
  so the evidence report uses the loaded model's max-length. Implemented in 336696b.
- 2026-09-15, Milestone 1 closed: Scrutiny and UT passed on the first round (336696b). Low findings carried into
  Milestone 2's scope: `GteRerankerModelInspector`'s Javadoc points to RAG.md for session timings that are in
  `live-runs/2026-09-15-reranker-gte/onnx-probe.txt`; RAG.md quotes uncommitted earlier timings; the exclusion guard's
  binder reads `yes`/`on`/`1` as true where the current model's condition does not (fails safe); the live test checks only
  row 0 against native truncation.
- 2026-09-15, Milestone 2 contract (written before any comparison run; the Frozen comparison above and amendment 1's
  timeout are binding):
  - Runs, each its own application start on 8081 with `trace=true&rerank=true`, settings through `RAG_*` variables and
    the profile only in the run shell, after the CPU-sharing wait: (R) current model, `RAG_CROSS_ENCODER_ENABLED=true`,
    `candidate-count` 200, `rerank-candidates` 40, timeout 4,000 ms; (A) gte profile, `candidate-count` 40,
    `rerank-candidates` 20; (B) gte profile, `candidate-count` 200, `rerank-candidates` 40. Run R first; if R does not
    reproduce 947's per-question rank and matched chunk, stop without running A and B.
  - G1. Property diffs (committed, generated by a script): A against 613 and B against 947 differ only in `reranker`,
    `rerankerVersion`, and outcome counts; the unrecorded timeout (120,000 ms against 2,000 and 4,000) is named with its
    source. Any other difference is named and no claim compares that pair.
  - G2. Per row against its paired current-model run: every question's rank, matched chunk, best accepted chunk's fused
    and reranked position; questions entering and leaving the top 5 (named); hit@1, hit@3, hit@5, MRR, figure and
    non-figure slices, per-ticker hit@5; fallbacks; scoring time per question (median and maximum) from the committed
    scoring log lines of each run, the paired current-model values from R's and 613's committed logs where recorded,
    otherwise stated unknown.
  - G3. Selection rule against the default reference 598 per row (A, B) as `ruleRow` claims; a row with any fallback
    has no selection claim and may be repeated once after the CPU-sharing wait.
  - G4. Claims C-901 to C-999 checked by `verify`; a generated RAG.md block; prose outside it has no numbers and no
    cause (nothing says why a model ranks a chunk differently); no wording that a model is better beyond the checked rows.
  - G5. Follow_Ups RAG-22 updated (pointer style); if a row meets the rule against 598, a DECISION item for Jay with no
    default change. Milestone 1's low findings above are fixed (pointer, uncommitted timings removed or committed).
  - G6. The current model's files byte-identical before and after (SHA-256 recorded); exported snapshots and evidence are
    unaltered endpoint exports.
  - Commands: `./mvnw -q -o verify` exit 0; comparison script re-run byte identical; 8081 free after each stop.
  - Out of scope: quantized variants, other windows, candidate counts, or timeouts; any default change.
  - User-facing flow (UT): `GET /api/rag/evaluate/{id}` and `/evidence` for R, A, B equal the committed exports (evidence
    token fields with the matching model loaded); the rule rows and entering/leaving lists recomputed from live responses.
- 2026-09-15, amendment 3 (Milestone 2, Scrutiny round 1): the pairs' fused candidate lists were not checked for identity.
  Row B's (1368) fused lists, rerank inputs, and removals equal run R's (1366), and R's reranked order equals 947's, but
  the documents pair B with 947 and do not say so; row A's (1367) fused order differs from 613's in five questions and its
  rerank input set differs for nvda-14, with no same-session current-model run at row A's settings. No cause is stated for
  the differences. Changes to Milestone 2:
  - Run R_A: one more application start with the current model at row A's settings (`candidate-count` 40,
    `rerank-candidates` 20, timeout 2,000 ms), traced, after the CPU-sharing wait. Row A is paired with R_A and row B
    with R for every per-question, metric, entering/leaving, and scoring-time comparison; 613 and 947 stay as the recorded
    earlier references (R's reproduction of 947 stays).
  - G7. Per pair, a generated section and checked claims: fused-order identity per question, rerank input set identity
    per question, and the input chunks' fused positions, naming every question that differs. A comparison sentence about
    a question whose rerank input set differs within its pair is not written as a model comparison; such questions are
    named and excluded from entering/leaving statements, and metrics are reported both over all questions and over the
    questions with identical input sets.
  - The two Milestone 1 low findings not fixed (the exclusion guard reads `yes`/`on`/`1` as true; the live test checks
    only row 0 against native truncation) are logged in Follow_Ups rather than fixed.
  - The selection rows against 598 stay per gte row (A, B).

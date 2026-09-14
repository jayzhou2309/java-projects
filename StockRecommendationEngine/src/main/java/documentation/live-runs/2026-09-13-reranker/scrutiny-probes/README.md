# Scrutiny probe logs (2026-09-13)

Independent measurements by the Scrutiny Validator of reranker Milestone 2 (commit eaeed7a), run through the production
`OnnxCrossEncoderScorer` in child JVMs with `-Xmx1g` under `/usr/bin/time -l`. The grid and width cases made two scoring
calls per JVM; `churn_*` made 40 rounds, and `vpar`, `valign`, `valign2`, and `vtemplate` made many calls and loaded the scorer
several times (4, 12, 4, and 3 loads), so their peak footprints are not per-call figures.

- `grid.txt`: one line for each grid, width, and heavy case (not the `sweep_*`, `churn_*`, `w181_bs1_n64`, `w181_bs20_n64`,
  `w228_bs20_n40`, `smoke`, or `v*` cases, whose results are only in their logs), with exit code, elapsed time, `peakFootprintMB` (the `/usr/bin/time -l` "peak memory
  footprint" in bytes divided by 1,048,576 and rounded down, so MiB), sampled RSS, and per-call milliseconds.
- One `.log` per case with the full output, including the "Cross-encoder loaded" line that states max-length and batch size,
  and the raw "peak memory footprint" in bytes.

File-name conventions (the shapes are not repeated inside the logs):
- `g_ml<max-length>_bs<batch-size>_<a|cjk>_<characters>`: a query and 40 chunks, each `"a "` repeated or CJK, of that many
  characters.
- `widest<width>_bs<batch-size>_n<chunks>` and `w<width>_bs<batch-size>_n<chunks>`: chunks whose query-plus-chunk pair is
  `<width>` tokens, the shape the per-call attention cap makes worst.
- `sweep_n<chunks>_w<width>`: the same, varying width at batch-size 64.
- `_t2` suffix: two threads scoring at once, the most the retrieval pool runs.
- `churn_bs<batch-size>_40calls`: 40 rounds of two concurrent calls with random shapes.
- `bang20000_bs64`, `hangul20000_bs64`: 20,000 characters of `!` or Hangul at batch-size 64.
- `baseline_empty`: an empty query and empty chunks (process floor, 256 MiB). `smoke`: a first end-to-end check at the defaults.
- `vtemplate.log`: variants of `tokenizer.json` (a changed `[SEP]` id is used; unsupported templates fail construction).
- `vpar.log`: Java pair assembly against native pair encoding (320 pairs, 0 mismatches). `valign.log`, `valign2.log`: each
  passage's score when scored in a batch against scored alone (4,224 comparisons, max difference 2.4e-6).

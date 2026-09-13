# Scrutiny probe logs (2026-09-13)

Independent measurements by the Scrutiny Validator of reranker Milestone 2 (commit eaeed7a), run through the production
`OnnxCrossEncoderScorer` in child JVMs with `-Xmx1g` under `/usr/bin/time -l`. Each probe made two scoring calls per JVM.

- `grid.txt`: one line per case with exit code, elapsed time, `peakFootprintMB` (the `/usr/bin/time -l` "peak memory
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
- `vpar.log`: Java pair assembly against native pair encoding (320 pairs, 0 mismatches). `valign.log`, `valign2.log`: each
  passage's score when scored in a batch against scored alone (4,224 comparisons, max difference 2.4e-6).

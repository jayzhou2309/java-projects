# Claims check fixture document

Written by the generator from ../true-claims.json (GeneratedBlocksTests; regenerate with -Dclaims.fixture.write=true).

* Ranks, selected by block name
    <!-- generated:../true-claims.json#ranks start -->
    * The reference row (snapshot 1) ranks aapl-01 1st. (C-001, observed)
    * Row A (snapshot 2) ranks msft-04 outside its window of 10 results (no matching chunk). (C-002, observed)
    <!-- generated:../true-claims.json#ranks end -->
* Claims without a block selection
    <!-- generated:../true-claims.json start -->
    * The reference row (snapshot 1) ranks aapl-01 1st. (C-001, observed)
    * Row A (snapshot 2) ranks msft-04 outside its window of 10 results (no matching chunk). (C-002, observed)
    * msft-04 is inside the top 5 in the reference row (snapshot 1, rank 4), outside the top 5 in row A (snapshot 2, no matching chunk in the window) and row B (snapshot 3, rank 8), and inside the top 5 in row C (snapshot 4, rank 1). (C-003, derived)
    * The hit@5 of the reference row (snapshot 1) is 0.750000. (C-004, observed)
    * The figure-slice hit@5 of row C (snapshot 4) is 0.666667. (C-005, observed)
    * The NVDA hit@5 of row B (snapshot 3) is 0.500000. (C-006, observed)
    * Against the reference row (snapshot 1), row C (snapshot 4) meets the aggregate hit@5 criterion (hit@5 0.750000 against 0.750000), meets the non-figure hit@5 criterion (non-figure-slice hit@5 1.000000 against 0.000000), meets the per-ticker hit@5 criterion (at or above the reference's for 3 tickers: AAPL 1.000000 against 1.000000, MSFT 1.000000 against 1.000000, NVDA 0.500000 against 0.500000), and does not meet the FIGURE top-5 criterion (left the top 5: nvda-01 (no rank, was 3)). (C-007, derived)
    * In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q1's accepted phrase "w012 w013 w014 w015" in chunk 101 spans tokens [12, 16) and characters [60, 79). (C-008, derived)
    * In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q1's accepted phrase "w012 w013 w014 w015" in chunk 101 lies partly inside the head window, and of the 3 rows scored for this chunk, no row holds it wholly. (C-009, derived)
    * In the traced scripted report (the evidence report of snapshot 459), chunk 101, which holds an accepted phrase of q1, was at fused position 1, was a rerank input, and was reranked 3rd. (C-010, observed)
    * In the traced scripted report (the evidence report of snapshot 459), chunk 201, which holds an accepted phrase of q2, was not in the fused list and was not a rerank input. (C-011, derived)
    * The untraced report does not record whether chunk 101 was a rerank input for q1. (not recorded in the untraced scripted report (the evidence report of snapshot 459): no trace) (C-012, unknown)
    * The traced report does not record q3's rerank outcome. (not recorded in the traced scripted report (the evidence report of snapshot 459): no trace for this question (its retrieval failed)) (C-013, unknown)
    * msft-04 left the top 5 in rows A and B while q1's chunk 101 was reranked in the traced report. (inferred from C-003 and C-010) (C-014, inferred)
    * Raising the window overlap from 64 to 224 lifted msft-04 from rank 8 into the top 5. (experiment: rerankerScoring max-window/overlap=64/maxWindows=4 in snapshot 3 against max-window/overlap=224/maxWindows=4 in snapshot 4; checked: msft-04 is outside the top 5 in row B (snapshot 3, rank 8), and inside the top 5 in row C (snapshot 4, rank 1)) (C-015, experiment)
    * In the untraced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q2's accepted phrase "w030 w031" in chunk 201 lies outside the head window, and of the 3 rows the recorded scoring would score for this chunk (not rows that were scored: rerank outcome unknown, no trace), row 3 holds it wholly. (C-016, derived)
    * In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q2's accepted phrase "w030 w031" in chunk 201: of the 3 rows the recorded scoring would score for this chunk (not rows that were scored: reranking fell back), row 3 holds it wholly. (C-017, derived)
    * Row B (snapshot 3) ranks msft-04 8th. (C-018, observed)
    <!-- generated:../true-claims.json end -->
* Prose outside a block, such as this line, is not checked, so it may not cite a claim; a sentence stating a claim's fact again belongs in a block selecting that claim.

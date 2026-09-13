# Claims check fixture document

Written by the generator from ../true-claims.json (GeneratedBlocksTests; regenerate with -Dclaims.fixture.write=true).

* Ranks, selected by block name
    <!-- generated:../true-claims.json#ranks start -->
    * The reference row ranks aapl-01 first. (C-001, observed)
    * Row A has no chunk matching msft-04 in its window. (C-002, observed)
    <!-- generated:../true-claims.json#ranks end -->
* Every claim
    <!-- generated:../true-claims.json start -->
    * The reference row ranks aapl-01 first. (C-001, observed)
    * Row A has no chunk matching msft-04 in its window. (C-002, observed)
    * msft-04 is inside the top 5 in the reference row and row C, and outside it in rows A and B. (C-003, derived)
    * The reference row's hit@5 is 0.750000. (C-004, observed)
    * Row C's figure-slice hit@5 is 0.666667. (C-005, observed)
    * Row B's NVDA hit@5 is 0.500000. (C-006, observed)
    * Against the reference, row C keeps aggregate, non-figure, and every ticker's hit@5 but drops a FIGURE question from the top 5. (C-007, derived)
    * q1's phrase occupies tokens [12, 16) and characters [60, 79) of chunk 101. (C-008, derived)
    * The head cut splits that occurrence and no row of the recorded scoring holds it wholly. (C-009, derived)
    * In the traced report chunk 101 was a rerank input at fused position 1, reranked 3. (C-010, observed)
    * q2 fell back, chunk 201 is not in its fused list, and by the fused position it was not a rerank input. (C-011, derived)
    * The untraced report does not record whether chunk 101 was a rerank input for q1. (C-012, unknown)
    * The traced report does not record q3's rerank outcome. (C-013, unknown)
    * msft-04 left the top 5 in rows A and B although q1's chunk 101 was still reranked in the traced report. (C-014, inferred)
    * Raising the window overlap from 64 to 224 lifted msft-04 from rank 8 into the top 5. (C-015, experiment)
    <!-- generated:../true-claims.json end -->
* A later sentence may cite a claim instead of restating it: row C drops a FIGURE question from the top 5 (C-007, derived).

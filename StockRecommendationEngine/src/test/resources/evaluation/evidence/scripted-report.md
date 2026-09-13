# Retrieval evidence report: snapshot 459

Each value is followed by its basis: observed (read from the numbered source), derived (computed by the numbered rule), or unknown with the reason. Sources and rules are listed at the end. Offsets are [start, end), end exclusive; window numbers start at 1. windowStarts and windowsHoldingWholly are arithmetic on the snapshot's recorded scoring: they are rows that were scored only for a chunk that is a rerank input of a RERANKED trace (rerankInput observed true); for reranking off, a fallback, a question without a trace, or a chunk outside the rerank input they are the rows the recorded scoring would score, not rows that were scored. W uses max-length from the current configuration, which snapshots do not record.

## Snapshot

| Field | Value |
|---|---|
| setVersion | v-test (observed [1]) |
| set | evaluation/scripted-set.json (observed [2]) |
| traced | true (observed [3]) |
| rerank | true (observed [4]) |
| rerankCandidates | 3 (observed [5]) |
| reranker | CrossEncoderReranker (observed [6]) |
| rerankerVersion | 5d3e70fd0c9f (observed [7]) |
| loadedModelVersion | 5d3e70fd0c9f (observed [8]) |
| rerankerScoring | max-window/overlap=0/maxWindows=4 (observed [9]) |
| passageScoring | max-window (derived [10]) |
| windowOverlapTokens | 0 (derived [10]) |
| maxWindows | 4 (derived [10]) |
| maxLength | 20 (observed [11]) |

## Question q1 (AAPL, FIGURE)

Question: q1 alpha beta

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 102 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 4 (observed [19]) |
| rerankInputCount | 3 (observed [20]) |
| queryTokens | 3 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0000000001-26-000001 ITEM_7: "w012 w013 w014 w015"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 101 | 40 (derived [24]) | 14 (derived [25]) | 0, 14, 26 (derived [26]) | [60, 79) (derived [27]) | [12, 16) (derived [28]) | partly (derived [29]) | none (derived [30]) |
| 102 | 6 (derived [24]) | 14 (derived [25]) | 0 (derived [26]) | [6, 27) (derived [27]) | [1, 5) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 101 | 1 (observed [31]) | true (observed [32]) | 3 (observed [33]) | -0.5 (observed [34]) | 3 (observed [35]) | -0.5, -3.0, -2.0 (observed [36]) | not returned (observed [37]) |
| 102 | 3 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 1.25 (observed [34]) | 1 (observed [35]) | 1.25 (observed [36]) | 2 (observed [37]) |

### Accepted phrase 2 of 2

0000000001-26-000001 ITEM_1A: "a phrase no stored chunk holds"

heldByStoredChunk: false (observed [23])

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 102 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 301 | 1 (observed [33]) | 2 (observed [41]) | 2.5 (observed [34]) | 2.5, -1.0 (observed [36]) |

## Question q2 (MSFT, NARRATIVE)

Question: q2 gamma

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | FALLBACK (observed [16]) |
| fallbackReason | timeout (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 2 (observed [19]) |
| rerankInputCount | 2 (observed [20]) |
| queryTokens | 2 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000000002-26-000002 ITEM_1: "w030 w031"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 201 | 40 (derived [24]) | 15 (derived [25]) | 0, 15, 25 (derived [26]) | [150, 159) (derived [27]) | [30, 32) (derived [28]) | not (derived [29]) | 3 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 201 | not in the fused list (observed [31]) | false (derived [42]) | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | fused order (observed [43]) |
| bestAcceptedChunk | none (observed [44]) |
| bestAcceptedPosition | none (observed [44]) |

rankedAbove: 2 chunks (observed [45])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 202 | 1 (observed [46]) | 1 (observed [19]) | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | unknown: rerank fell back (timeout): the trace records no reranked positions or scores |
| 203 | 2 (observed [46]) | 2 (observed [19]) | unknown: rerank fell back (timeout): the trace records no reranked positions or scores | unknown: rerank fell back (timeout): the trace records no reranked positions or scores |

## Question q3 (NVDA, FIGURE)

Question: q3 delta epsilon

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | none (observed [14]) |
| error | IllegalStateException: Embedding request failed (observed [15]) |
| rerankOutcome | unknown: no trace for this question (its retrieval failed) |
| fallbackReason | unknown: no trace for this question (its retrieval failed) |
| scoresNotRecorded | unknown: no trace for this question (its retrieval failed) |
| fusedCount | unknown: no trace for this question (its retrieval failed) |
| rerankInputCount | unknown: no trace for this question (its retrieval failed) |
| queryTokens | 3 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000000002-26-000002 ITEM_1: "w030 w031"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 201 | 40 (derived [24]) | 14 (derived [25]) | 0, 14, 26 (derived [26]) | [150, 159) (derived [27]) | [30, 32) (derived [28]) | not (derived [29]) | 3 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 201 | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) | unknown: no trace for this question (its retrieval failed) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace for this question (its retrieval failed) |
| bestAcceptedChunk | unknown: no trace for this question (its retrieval failed) |
| bestAcceptedPosition | unknown: no trace for this question (its retrieval failed) |

rankedAbove: unknown: no trace for this question (its retrieval failed)

## Sources and rules

1. observed: snapshot set_version
2. observed: snapshot properties.set
3. observed: snapshot traces (properties.trace true)
4. observed: snapshot properties.rerank
5. observed: snapshot properties.rerankCandidates
6. observed: snapshot properties.reranker
7. observed: snapshot properties.rerankerVersion
8. observed: loaded cross-encoder model files
9. observed: snapshot properties.rerankerScoring
10. derived: parsed from snapshot properties.rerankerScoring
11. observed: current configuration rag.retrieval.cross-encoder.max-length (snapshots do not record it)
12. observed: snapshot results rank (null: no matching chunk in the window)
13. observed: snapshot results matchedChunkId (null: no matching chunk in the window)
14. observed: snapshot results retrievalStrategy (null: retrieval error, or stored before the field)
15. observed: snapshot results error (null: none)
16. observed: trace rerank outcome
17. observed: trace rerank fallbackReason (null: not a fallback)
18. observed: trace rerank scoresNotRecorded (null: every rerank input carries its position and score, or not reranked)
19. observed: trace fused
20. observed: trace rerank inputCount (null: reranking off)
21. derived: tokens of the question text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer
22. observed: bundled set evaluation/scripted-set.json
23. observed: sec_filing_chunks at report time: chunks of the phrase's accession and section whose text contains the phrase (RetrievalEvaluationService.matches)
24. derived: tokens of the stored chunk text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer
25. derived: W = max-length (from the current configuration, not recorded in the snapshot) - 3 - the query tokens kept against the whole chunk, longest first (CrossEncoderPairAssembler.windowLength)
26. derived: start token of each row under the snapshot's rerankerScoring: head one row at 0; max-window CrossEncoderPairAssembler.windowStarts(chunk tokens, W, overlap, max-windows); these are the rows the recorded scoring scores for this chunk beside this question only when the chunk is a rerank input of a RERANKED trace (rerankInput observed true); for reranking off, a fallback, a question without a trace, or a chunk outside the rerank input they are the rows the recorded scoring would score, not rows that were scored
27. derived: occurrence of the normalised phrase in the normalised chunk text (whitespace runs collapsed, trimmed, lower-cased), mapped to UTF-16 offsets of the stored text, end exclusive
28. derived: tokens of the whole-chunk tokenization whose character span overlaps the occurrence, end exclusive
29. derived: wholly: token span end <= W; partly: start < W < end; not: start >= W
30. derived: 1-based rows whose tokens [start, start + min(W, chunk tokens)) contain the whole token span (empty: no row holds it wholly); these are the rows the recorded scoring scores for this chunk beside this question only when the chunk is a rerank input of a RERANKED trace (rerankInput observed true); for reranking off, a fallback, a question without a trace, or a chunk outside the rerank input they are the rows the recorded scoring would score, not rows that were scored
31. observed: trace fused (null: not in the fused list)
32. observed: trace rerank candidates (true: a rerank input; false: not a rerank input)
33. observed: trace rerank candidates rerankedPosition
34. observed: trace rerank candidates score
35. observed: trace rerank candidates windowCount
36. observed: trace rerank candidates windowScores
37. observed: trace returnedChunkIds (null: not returned)
38. observed: trace rerank outcome RERANKED
39. observed: trace reranked order: first chunk holding an accepted phrase
40. observed: trace reranked order: every chunk ranked above the best accepted chunk
41. observed: trace rerank candidates fusedPosition
42. derived: fused position <= trace rerank inputCount (the reranker receives the first inputCount fused chunks)
43. observed: trace rerank outcome FALLBACK
44. observed: trace fused order: no chunk holding an accepted phrase is in it
45. observed: trace fused order: every chunk (no chunk holding an accepted phrase is in it)
46. observed: trace fused fusedPosition

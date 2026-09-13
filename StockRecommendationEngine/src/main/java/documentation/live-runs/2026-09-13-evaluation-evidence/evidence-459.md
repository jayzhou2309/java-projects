# Retrieval evidence report: snapshot 459

Each value is followed by its basis: observed (read from the numbered source), derived (computed by the numbered rule), or unknown with the reason. Sources and rules are listed at the end. Offsets are [start, end), end exclusive; window numbers start at 1.

## Snapshot

| Field | Value |
|---|---|
| setVersion | v2 (observed [1]) |
| set | evaluation/retrieval-set-v2.json (observed [2]) |
| traced | true (observed [3]) |
| rerank | true (observed [4]) |
| rerankCandidates | 20 (observed [5]) |
| reranker | CrossEncoderReranker (observed [6]) |
| rerankerVersion | 5d3e70fd0c9f (observed [7]) |
| loadedModelVersion | 5d3e70fd0c9f (observed [8]) |
| rerankerScoring | max-window/overlap=64/maxWindows=4 (observed [9]) |
| passageScoring | max-window (derived [10]) |
| windowOverlapTokens | 64 (derived [10]) |
| maxWindows | 4 (derived [10]) |
| maxLength | 512 (observed [11]) |

## Question aapl-01 (AAPL, FIGURE)

Question: What were Apple's Greater China net sales in fiscal 2025, and how did they compare with 2024?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 225 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 45 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 23 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_7: "Greater China 64,377 (4) % 66,952 (8) % 72,559"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 225 | 815 (derived [24]) | 486 (derived [25]) | 0, 329 (derived [26]) | [1080, 1126) (derived [27]) | [218, 240) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 225 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 7.460206 (observed [34]) | 2 (observed [35]) | 5.7417903, 7.460206 (observed [36]) | 1 (observed [37]) |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "China (1) 64,377 66,952 72,559"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 246 | 1131 (derived [24]) | 486 (derived [25]) | 0, 422, 645 (derived [26]) | [3537, 3567) (derived [27]) | [1013, 1029) (derived [28]) | not (derived [29]) | 3 (derived [30]) |
| 247 | 763 (derived [24]) | 486 (derived [25]) | 0, 277 (derived [26]) | [189, 219) (derived [27]) | [54, 70) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 246 | 2 (observed [31]) | true (observed [32]) | 8 (observed [33]) | 3.657081 (observed [34]) | 3 (observed [35]) | 3.657081, -3.4629865, 2.6295302 (observed [36]) | 8 (observed [37]) |
| 247 | 7 (observed [31]) | true (observed [32]) | 7 (observed [33]) | 4.0078707 (observed [34]) | 2 (observed [35]) | 4.0078707, -5.7062416 (observed [36]) | 7 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 225 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-02 (AAPL, FIGURE)

Question: What was Apple's effective tax rate for fiscal 2025 versus the prior year?

| Field | Value |
|---|---|
| rank | 3 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 53 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 17 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_7: "Effective tax rate 15.6 % 24.1 % 14.7 %"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [24]) | 492 (derived [25]) | 0, 278 (derived [26]) | [3015, 3054) (derived [27]) | [691, 706) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 227 | 833 (derived [24]) | 492 (derived [25]) | 0, 341 (derived [26]) | [166, 205) (derived [27]) | [48, 63) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | 3 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 2.9547844 (observed [34]) | 2 (observed [35]) | -2.0162652, 2.9547844 (observed [36]) | 3 (observed [37]) |
| 227 | 5 (observed [31]) | true (observed [32]) | 5 (observed [33]) | 2.4617052 (observed [34]) | 2 (observed [35]) | 2.4617052, -2.9874918 (observed [36]) | 5 (observed [37]) |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "Provision for income taxes $ 20,719 $ 29,749 $ 16,741 Effective tax rate 15.6 %"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 241 | 1075 (derived [24]) | 492 (derived [25]) | 0, 428, 583 (derived [26]) | [1070, 1149) (derived [27]) | [360, 386) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 241 | 1 (observed [31]) | true (observed [32]) | 6 (observed [33]) | 2.3630126 (observed [34]) | 3 (observed [35]) | -0.024510147, 2.3630126, 2.1135697 (observed [36]) | 6 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 226 (observed [39]) |
| bestAcceptedPosition | 3 (observed [33]) |

rankedAbove: 2 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 280 | 1 (observed [33]) | 8 (observed [41]) | 4.029431 (observed [34]) | 4.029431, -4.1758156 (observed [36]) |
| 279 | 2 (observed [33]) | 2 (observed [41]) | 3.6817648 (observed [34]) | -3.2192793, 3.6817648 (observed [36]) |

## Question aapl-03 (AAPL, NARRATIVE)

Question: Why did Apple's products gross margin percentage fall in fiscal 2025?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 49 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 15 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Products gross margin percentage decreased during 2025 compared to 2024 primarily due to a different mix of products and tariff costs"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [24]) | 494 (derived [25]) | 0, 276 (derived [26]) | [1128, 1261) (derived [27]) | [269, 292) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | 2 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.9877796 (observed [34]) | 2 (observed [35]) | 4.9877796, 4.590573 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 226 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-04 (AAPL, NARRATIVE)

Question: What has Apple changed in the EU to comply with the Digital Markets Act?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 210 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 61 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 15 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_1A: "in the EU as it seeks to comply with the Digital Markets Act"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 210 | 754 (derived [24]) | 494 (derived [25]) | 0, 260 (derived [26]) | [592, 652) (derived [27]) | [106, 119) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 210 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.4398804 (observed [34]) | 2 (observed [35]) | 4.4398804, -4.1854234 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 210 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-05 (AAPL, NARRATIVE)

Question: How does Apple organise its reportable segments?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 246 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 53 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 9 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_1: "reportable segments consist of the Americas, Europe, Greater China, Japan and Rest of Asia Pacific"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 190 | 808 (derived [24]) | 500 (derived [25]) | 0, 308 (derived [26]) | [3121, 3219) (derived [27]) | [655, 674) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 190 | 1 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 2.389638 (observed [34]) | 2 (observed [35]) | -6.626579, 2.389638 (observed [36]) | 3 (observed [37]) |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "The Company manages its business primarily on a geographic basis"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 245 | 751 (derived [24]) | 500 (derived [25]) | 0, 251 (derived [26]) | [3568, 3632) (derived [27]) | [671, 681) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 246 | 1131 (derived [24]) | 500 (derived [25]) | 0, 436, 631 (derived [26]) | [99, 163) (derived [27]) | [24, 34) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 245 | 3 (observed [31]) | true (observed [32]) | 10 (observed [33]) | -4.7263193 (observed [34]) | 2 (observed [35]) | -7.630216, -4.7263193 (observed [36]) | 10 (observed [37]) |
| 246 | 2 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 3.3651216 (observed [34]) | 3 (observed [35]) | 3.3651216, -10.626926, -10.538217 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 246 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 191 | 1 (observed [33]) | 7 (observed [41]) | 3.6848865 (observed [34]) | 3.6848865, -6.252741 (observed [36]) |

## Question aapl-06 (AAPL, FIGURE)

Question: How much total deferred revenue did Apple carry at the end of fiscal 2025?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 269 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 46 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 18 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_8: "the Company had total deferred revenue of $13.7 billion and $12.8 billion, respectively"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 236 | 897 (derived [24]) | 491 (derived [25]) | 0, 406 (derived [26]) | [1524, 1611) (derived [27]) | [375, 397) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 236 | 4 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 3.7628353 (observed [34]) | 2 (observed [35]) | 3.7628353, 3.338471 (observed [36]) | 3 (observed [37]) |

### Accepted phrase 2 of 2

0000320193-26-000020 ITEM_1: "total deferred revenue of $14.9 billion and $13.7 billion, respectively"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 269 | 1044 (derived [24]) | 491 (derived [25]) | 0, 427, 553 (derived [26]) | [1886, 1957) (derived [27]) | [484, 503) (derived [28]) | partly (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 269 | 2 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 6.457254 (observed [34]) | 3 (observed [35]) | 5.419166, 6.457254, 0.3308045 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 269 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-07 (AAPL, FIGURE)

Question: What one-time income tax charge did Apple record after the European Court of Justice upheld the State Aid Decision?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 240 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 51 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 22 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_8: "recorded a one-time income tax charge of $10.2 billion, net, which represented $15.8 billion payable to Ireland"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 240 | 819 (derived [24]) | 487 (derived [25]) | 0, 332 (derived [26]) | [2240, 2351) (derived [27]) | [529, 557) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 240 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 2.7225766 (observed [34]) | 2 (observed [35]) | 1.8266516, 2.7225766 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 240 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-08 (AAPL, NARRATIVE)

Question: Which quarter's results did Apple's most recent earnings press release cover?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 297 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 62 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 16 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-26-000018 ITEM_2: "financial results for its third fiscal quarter ended June 27, 2026"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 297 | 130 (derived [24]) | 493 (derived [25]) | 0 (derived [26]) | [80, 146) (derived [27]) | [23, 36) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 297 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 5.122165 (observed [34]) | 1 (observed [35]) | 5.122165 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 297 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-09 (AAPL, FIGURE)

Question: How much of its own stock did Apple buy back in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 56 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 19 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "During the third quarter of 2026, the Company repurchased $25.8 billion of its common stock"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 280 | 818 (derived [24]) | 490 (derived [25]) | 0, 328 (derived [26]) | [2531, 2622) (derived [27]) | [509, 532) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 280 | 8 (observed [31]) | true (observed [32]) | 11 (observed [33]) | 1.7319342 (observed [34]) | 2 (observed [35]) | 1.7319342, 1.0511013 (observed [36]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 280 (observed [39]) |
| bestAcceptedPosition | 11 (observed [33]) |

rankedAbove: 10 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 267 | 1 (observed [33]) | 5 (observed [41]) | 3.9696763 (observed [34]) | -3.3961766, -1.8092098, 3.9696763 (observed [36]) |
| 222 | 2 (observed [33]) | 3 (observed [41]) | 3.2079797 (observed [34]) | 3.2079797, 3.11427 (observed [36]) |
| 233 | 3 (observed [33]) | 9 (observed [41]) | 2.8982544 (observed [34]) | -1.1695645, -2.2598417, 2.8982544 (observed [36]) |
| 244 | 4 (observed [33]) | 1 (observed [41]) | 2.803193 (observed [34]) | 2.803193, -1.8622274, -1.0285839 (observed [36]) |
| 297 | 5 (observed [33]) | 14 (observed [41]) | 2.715664 (observed [34]) | 2.715664 (observed [36]) |
| 279 | 6 (observed [33]) | 6 (observed [41]) | 2.652268 (observed [34]) | 1.5650501, 2.652268 (observed [36]) |
| 277 | 7 (observed [33]) | 19 (observed [41]) | 2.3268209 (observed [34]) | -0.26789123, 2.3268209 (observed [36]) |
| 273 | 8 (observed [33]) | 4 (observed [41]) | 2.3085253 (observed [34]) | 2.3085253, 0.9524692 (observed [36]) |
| 278 | 9 (observed [33]) | 18 (observed [41]) | 2.0869365 (observed [34]) | 1.4536399, 2.0869365 (observed [36]) |
| 269 | 10 (observed [33]) | 10 (observed [41]) | 1.9362142 (observed [34]) | 0.43837363, 1.9362142, 1.286996 (observed [36]) |

## Question aapl-10 (AAPL, NARRATIVE)

Question: What drove the increase in Apple's Greater China net sales in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 277 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 49 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 21 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "Greater China net sales increased during the third quarter and first nine months of 2026 compared to the same periods in 2025 primarily due to higher net sales of iPhone"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 277 | 680 (derived [24]) | 488 (derived [25]) | 0, 192 (derived [26]) | [1989, 2158) (derived [27]) | [462, 494) (derived [28]) | partly (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 277 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 6.052266 (observed [34]) | 2 (observed [35]) | 5.7372904, 6.052266 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 277 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-11 (AAPL, FIGURE)

Question: Apple's Services net sales grew 14% to $109,158 million in fiscal 2025. What drove the increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 225 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 46 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 25 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Services net sales increased during 2025 compared to 2024 primarily due to higher net sales from advertising, the App Store and cloud services"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 225 | 815 (derived [24]) | 484 (derived [25]) | 0, 331 (derived [26]) | [3276, 3418) (derived [27]) | [785, 811) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 226 | 770 (derived [24]) | 484 (derived [25]) | 0, 286 (derived [26]) | [346, 488) (derived [27]) | [72, 98) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 225 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 5.1011834 (observed [34]) | 2 (observed [35]) | 3.2754211, 5.1011834 (observed [36]) | 1 (observed [37]) |
| 226 | 5 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 4.9883604 (observed [34]) | 2 (observed [35]) | 4.9883604, 3.6320145 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 225 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-12 (AAPL, FIGURE)

Question: Apple's Services gross margin percentage reached 75.4% in fiscal 2025, up from 73.9%. Why did it rise?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 49 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 29 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Services gross margin percentage increased during 2025 compared to 2024 primarily due to a different mix of services"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [24]) | 480 (derived [25]) | 0, 290 (derived [26]) | [1465, 1581) (derived [27]) | [328, 348) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.5944085 (observed [34]) | 2 (observed [35]) | 4.4388857, 4.5944085 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 226 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-13 (AAPL, FIGURE)

Question: Apple's iPhone net sales came to $54,252 million in the third quarter of fiscal 2026. What drove the year-over-year increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 278 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 49 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 32 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "iPhone net sales increased during the third quarter and first nine months of 2026 compared to the same periods in 2025 primarily due to higher net sales of Pro models"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 278 | 882 (derived [24]) | 477 (derived [25]) | 0, 405 (derived [26]) | [1216, 1382) (derived [27]) | [342, 374) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 278 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.721117 (observed [34]) | 2 (observed [35]) | 4.721117, 4.52647 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 278 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question aapl-14 (AAPL, FIGURE)

Question: Apple's research and development expense rose 32% to $11,729 million in the third quarter of fiscal 2026. What was behind the increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 279 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 50 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 32 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "primarily due to higher infrastructure-related costs, including investments in artificial intelligence, and headcount-related expenses"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 279 | 886 (derived [24]) | 477 (derived [25]) | 0, 409 (derived [26]) | [1835, 1969) (derived [27]) | [437, 459) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 279 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.421527 (observed [34]) | 2 (observed [35]) | 4.421527, 2.5583715 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 279 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-01 (MSFT, FIGURE)

Question: How much did Microsoft Cloud revenue grow in fiscal 2026 and what did it reach?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 573 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 49 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 17 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_7: "Microsoft Cloud revenue increased 27% to $214.4 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 514 | 660 (derived [24]) | 492 (derived [25]) | 0, 168 (derived [26]) | [1845, 1900) (derived [27]) | [358, 370) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 514 | 6 (observed [31]) | true (observed [32]) | 6 (observed [33]) | 5.1250453 (observed [34]) | 2 (observed [35]) | 5.1250453, 4.446792 (observed [36]) | 6 (observed [37]) |

### Accepted phrase 2 of 2

0001193125-26-323660 ITEM_8: "was $214.4 billion, $168.9 billion, and $137.7 billion in fiscal years 2026, 2025, and 2024"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 573 | 449 (derived [24]) | 492 (derived [25]) | 0 (derived [26]) | [794, 885) (derived [27]) | [215, 246) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 573 | 8 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 5.845943 (observed [34]) | 1 (observed [35]) | 5.845943 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 573 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 518 | 1 (observed [33]) | 3 (observed [41]) | 6.6750083 (observed [34]) | 6.452346, 6.6750083 (observed [36]) |

## Question msft-02 (MSFT, FIGURE)

Question: What was Microsoft's commercial remaining performance obligation at the end of fiscal 2026?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 514 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 55 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 17 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_7: "Commercial remaining performance obligation increased 84% to $678 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 514 | 660 (derived [24]) | 492 (derived [25]) | 0, 168 (derived [26]) | [1904, 1977) (derived [27]) | [372, 384) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 514 | 2 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 4.873058 (observed [34]) | 2 (observed [35]) | 4.873058, 2.0796976 (observed [36]) | 2 (observed [37]) |

### Accepted phrase 2 of 2

0001193125-26-323660 ITEM_8: "Revenue allocated to remaining performance obligations related to the commercial portion of revenue was $678 billion as of June 30, 2026"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 565 | 480 (derived [24]) | 492 (derived [25]) | 0 (derived [26]) | [1012, 1148) (derived [27]) | [236, 261) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 565 | 18 (observed [31]) | true (observed [32]) | 9 (observed [33]) | 0.059116095 (observed [34]) | 1 (observed [35]) | 0.059116095 (observed [36]) | 9 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 514 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 516 | 1 (observed [33]) | 1 (observed [41]) | 5.524701 (observed [34]) | 2.53835, 5.524701 (observed [36]) |

## Question msft-03 (MSFT, NARRATIVE)

Question: Why did Microsoft Cloud's gross margin percentage decline in fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 517 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 52 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 15 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "Microsoft Cloud gross margin percentage decreased to 66% driven by continued investments in AI infrastructure and growing AI product usage"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 517 | 610 (derived [24]) | 494 (derived [25]) | 0, 116 (derived [26]) | [2283, 2421) (derived [27]) | [473, 494) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 517 | 2 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 4.60925 (observed [34]) | 2 (observed [35]) | 4.60925, 3.800384 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 517 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-04 (MSFT, FIGURE)

Question: How many people did Microsoft employ at the end of fiscal 2026, and how were they split between the U.S. and other countries?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 58 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 29 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_1: "we employed approximately 223,000 people on a full-time basis, 121,000 in the U.S. and 102,000 internationally"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 466 | 693 (derived [24]) | 480 (derived [25]) | 0, 213 (derived [26]) | [3503, 3613) (derived [27]) | [606, 634) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 467 | 282 (derived [24]) | 480 (derived [25]) | 0 (derived [26]) | [96, 206) (derived [27]) | [20, 48) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 466 | 4 (observed [31]) | true (observed [32]) | 11 (observed [33]) | -5.90612 (observed [34]) | 2 (observed [35]) | -10.674508, -5.90612 (observed [36]) | not returned (observed [37]) |
| 467 | 21 (observed [31]) | false (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 466 (observed [39]) |
| bestAcceptedPosition | 11 (observed [33]) |

rankedAbove: 10 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 514 | 1 (observed [33]) | 1 (observed [41]) | -0.78539616 (observed [34]) | -0.78539616, -1.7681398 (observed [36]) |
| 516 | 2 (observed [33]) | 9 (observed [41]) | -1.2658135 (observed [34]) | -4.973708, -1.2658135 (observed [36]) |
| 573 | 3 (observed [33]) | 2 (observed [41]) | -1.7733854 (observed [34]) | -1.7733854 (observed [36]) |
| 637 | 4 (observed [33]) | 8 (observed [41]) | -2.157766 (observed [34]) | -9.817317, -2.157766 (observed [36]) |
| 635 | 5 (observed [33]) | 7 (observed [41]) | -3.0140505 (observed [34]) | -3.0140505 (observed [36]) |
| 642 | 6 (observed [33]) | 13 (observed [41]) | -4.1945243 (observed [34]) | -4.1945243, -8.02978 (observed [36]) |
| 518 | 7 (observed [33]) | 3 (observed [41]) | -4.6119695 (observed [34]) | -4.6119695, -4.641548 (observed [36]) |
| 645 | 8 (observed [33]) | 6 (observed [41]) | -4.6940756 (observed [34]) | -7.6097846, -4.6940756 (observed [36]) |
| 512 | 9 (observed [33]) | 14 (observed [41]) | -4.8037405 (observed [34]) | -4.8037405 (observed [36]) |
| 641 | 10 (observed [33]) | 11 (observed [41]) | -5.5665355 (observed [34]) | -6.2698307, -5.5665355 (observed [36]) |

## Question msft-05 (MSFT, NARRATIVE)

Question: What are Microsoft's three reportable segments in its fiscal 2026 annual report?

| Field | Value |
|---|---|
| rank | 10 (observed [12]) |
| matchedChunkId | 460 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 58 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 17 (derived [21]) |
| acceptedPhraseCount | 3 (observed [22]) |

### Accepted phrase 1 of 3

0001193125-26-323660 ITEM_1: "using three segments: Productivity and Business Processes, Intelligent Cloud, and More Personal Computing"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 460 | 650 (derived [24]) | 492 (derived [25]) | 0, 158 (derived [26]) | [624, 729) (derived [27]) | [105, 121) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 460 | 3 (observed [31]) | true (observed [32]) | 10 (observed [33]) | 1.2978278 (observed [34]) | 2 (observed [35]) | 1.2978278, -1.3803954 (observed [36]) | 10 (observed [37]) |

### Accepted phrase 2 of 3

0001193125-26-323660 ITEM_7: "We report our financial performance based on the following three segments"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 515 | 700 (derived [24]) | 492 (derived [25]) | 0, 208 (derived [26]) | [3437, 3510) (derived [27]) | [614, 625) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 515 | 14 (observed [31]) | true (observed [32]) | 12 (observed [33]) | 0.81250834 (observed [34]) | 2 (observed [35]) | -4.609346, 0.81250834 (observed [36]) | not returned (observed [37]) |

### Accepted phrase 3 of 3

0001193125-26-323660 ITEM_8: "we reported our financial performance based on the following three segments"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 571 | 655 (derived [24]) | 492 (derived [25]) | 0, 163 (derived [26]) | [680, 755) (derived [27]) | [121, 132) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 571 | 2 (observed [31]) | true (observed [32]) | 14 (observed [33]) | -0.032027252 (observed [34]) | 2 (observed [35]) | -0.032027252, -5.345564 (observed [36]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 460 (observed [39]) |
| bestAcceptedPosition | 10 (observed [33]) |

rankedAbove: 9 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 1064 | 1 (observed [33]) | 11 (observed [41]) | 6.64879 (observed [34]) | 6.64879 (observed [36]) |
| 640 | 2 (observed [33]) | 4 (observed [41]) | 3.8237033 (observed [34]) | 3.8237033 (observed [36]) |
| 635 | 3 (observed [33]) | 5 (observed [41]) | 2.6522598 (observed [34]) | 2.6522598 (observed [36]) |
| 637 | 4 (observed [33]) | 12 (observed [41]) | 2.6085997 (observed [34]) | -3.7228048, 2.6085997 (observed [36]) |
| 573 | 5 (observed [33]) | 9 (observed [41]) | 2.5032017 (observed [34]) | 2.5032017 (observed [36]) |
| 516 | 6 (observed [33]) | 8 (observed [41]) | 2.1336968 (observed [34]) | -2.4237833, 2.1336968 (observed [36]) |
| 518 | 7 (observed [33]) | 7 (observed [41]) | 1.8330514 (observed [34]) | 1.7344925, 1.8330514 (observed [36]) |
| 514 | 8 (observed [33]) | 15 (observed [41]) | 1.770236 (observed [34]) | 1.770236, 1.1678165 (observed [36]) |
| 574 | 9 (observed [33]) | 20 (observed [41]) | 1.4960477 (observed [34]) | 1.4960477, -4.147095 (observed [36]) |

## Question msft-06 (MSFT, NARRATIVE)

Question: How could power and energy constraints limit Microsoft's datacenter expansion?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 489 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 61 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 15 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_1A: "requirements imposed by utilities, regulators, or other market participants could restrict our ability to develop or expand datacenter capacity"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 489 | 642 (derived [24]) | 494 (derived [25]) | 0, 148 (derived [26]) | [1944, 2087) (derived [27]) | [326, 349) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 489 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 0.66302514 (observed [34]) | 2 (observed [35]) | 0.66302514, -1.5597464 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 489 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-07 (MSFT, NARRATIVE)

Question: What sustainability goals has Microsoft committed to by 2030 and why are they harder to meet?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 680 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 52 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 18 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_1A: "in 2020 we announced goals to become carbon negative, water positive, and zero waste by 2030"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 495 | 695 (derived [24]) | 491 (derived [25]) | 0, 204 (derived [26]) | [2163, 2255) (derived [27]) | [374, 393) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 495 | 7 (observed [31]) | true (observed [32]) | 5 (observed [33]) | -2.68742 (observed [34]) | 2 (observed [35]) | -2.68742, -5.9141035 (observed [36]) | 5 (observed [37]) |

### Accepted phrase 2 of 2

0001193125-26-191507 ITEM_1A: "AI development and deployment has and may continue to raise energy use and emissions, making it harder to meet these goals"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 680 | 701 (derived [24]) | 491 (derived [25]) | 0, 210 (derived [26]) | [1431, 1553) (derived [27]) | [259, 281) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 680 | 18 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 3.5438929 (observed [34]) | 2 (observed [35]) | -2.3212807, 3.5438929 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 680 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 460 | 1 (observed [33]) | 1 (observed [41]) | 5.461506 (observed [34]) | 5.461506, -9.10196 (observed [36]) |

## Question msft-08 (MSFT, FIGURE)

Question: How much revenue did Microsoft record from its commercial arrangements with OpenAI in fiscal 2026?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 547 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 53 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 18 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_8: "we recorded revenue from commercial arrangements with OpenAI, inclusive of revenue-sharing payments, of $24.1 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 547 | 780 (derived [24]) | 491 (derived [25]) | 0, 289 (derived [26]) | [576, 693) (derived [27]) | [122, 145) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 547 | 16 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 4.9817414 (observed [34]) | 2 (observed [35]) | 4.9817414, 0.42798257 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 547 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 518 | 1 (observed [33]) | 5 (observed [41]) | 5.777027 (observed [34]) | 5.777027, 2.549787 (observed [36]) |

## Question msft-09 (MSFT, NARRATIVE)

Question: What change to its reportable segments did Microsoft announce for fiscal 2027?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 1064 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 57 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 15 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-380280 ITEM_7_01: "two reportable segments: (1) Agents and Infra and (2) Devices and Consumer"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 1064 | 263 (derived [24]) | 494 (derived [25]) | 0 (derived [26]) | [388, 462) (derived [27]) | [71, 90) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 1064 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 8.118209 (observed [34]) | 1 (observed [35]) | 8.118209 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 1064 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-10 (MSFT, FIGURE)

Question: What was Microsoft Cloud revenue in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 637 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 50 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 14 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001193125-26-191507 ITEM_2: "Microsoft Cloud revenue increased 29% to $54.5 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 637 | 737 (derived [24]) | 495 (derived [25]) | 0, 242 (derived [26]) | [3081, 3135) (derived [27]) | [620, 632) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 637 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 6.891818 (observed [34]) | 2 (observed [35]) | -9.521887, 6.891818 (observed [36]) | 1 (observed [37]) |

### Accepted phrase 2 of 2

0001193125-26-191507 ITEM_1: "was $54.5 billion and $155.1 billion for the three and nine months ended March 31, 2026"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 635 | 426 (derived [24]) | 495 (derived [25]) | 0 (derived [26]) | [899, 986) (derived [27]) | [266, 290) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 635 | 4 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 5.5854006 (observed [34]) | 1 (observed [35]) | 5.5854006 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 637 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-11 (MSFT, FIGURE)

Question: Microsoft's cloud services and server products line gained $31.0 billion, a 31% increase, in its most recent fiscal year; which offerings accounted for it?

| Field | Value |
|---|---|
| rank | 3 (observed [12]) |
| matchedChunkId | 519 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 55 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 34 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "Server products and cloud services revenue increased $31.0 billion or 31% driven by Azure and other cloud services"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 519 | 605 (derived [24]) | 475 (derived [25]) | 0, 130 (derived [26]) | [981, 1095) (derived [27]) | [189, 211) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 519 | 1 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 4.881146 (observed [34]) | 2 (observed [35]) | 3.9830523, 4.881146 (observed [36]) | 3 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 519 (observed [39]) |
| bestAcceptedPosition | 3 (observed [33]) |

rankedAbove: 2 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 646 | 1 (observed [33]) | 9 (observed [41]) | 6.1194153 (observed [34]) | 6.1194153, 2.9384181 (observed [36]) |
| 518 | 2 (observed [33]) | 2 (observed [41]) | 5.0276384 (observed [34]) | 5.0276384, 4.062461 (observed [36]) |

## Question msft-12 (MSFT, FIGURE)

Question: Why did XBOX hardware sales at Microsoft come in 29% lower in its most recent fiscal year?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 519 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 72 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 19 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "XBOX hardware revenue decreased 29% driven by lower volume of consoles sold"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 519 | 605 (derived [24]) | 490 (derived [25]) | 0, 115 (derived [26]) | [2827, 2902) (derived [27]) | [544, 557) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 519 | 2 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 1.1868914 (observed [34]) | 2 (observed [35]) | -1.8426992, 1.1868914 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 519 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 645 | 1 (observed [33]) | 1 (observed [41]) | 4.0742044 (observed [34]) | 4.0742044, -5.01648 (observed [36]) |

## Question msft-13 (MSFT, FIGURE)

Question: Microsoft generated $182.9 billion of cash from operations in fiscal 2026. What explains the $46.8 billion increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 524 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 56 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 26 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "primarily due to an increase in cash received from customers and a decrease in cash used to pay income taxes"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 523 | 734 (derived [24]) | 483 (derived [25]) | 0, 251 (derived [26]) | [3377, 3485) (derived [27]) | [630, 650) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 524 | 344 (derived [24]) | 483 (derived [25]) | 0 (derived [26]) | [54, 162) (derived [27]) | [17, 37) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 523 | 1 (observed [31]) | true (observed [32]) | 4 (observed [33]) | 2.214139 (observed [34]) | 2 (observed [35]) | -1.6594167, 2.214139 (observed [36]) | 4 (observed [37]) |
| 524 | 2 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 6.64564 (observed [34]) | 1 (observed [35]) | 6.64564 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 524 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question msft-14 (MSFT, FIGURE)

Question: What was behind the 40% increase in sales of Azure and Microsoft's other cloud offerings in its fiscal third quarter?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 646 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 60 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 24 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001193125-26-191507 ITEM_2: "Azure and other cloud services revenue grew 40% driven by demand for services across the platform"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 644 | 533 (derived [24]) | 485 (derived [25]) | 0, 48 (derived [26]) | [1113, 1210) (derived [27]) | [215, 232) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |
| 646 | 538 (derived [24]) | 485 (derived [25]) | 0, 53 (derived [26]) | [176, 273) (derived [27]) | [37, 54) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 644 | 1 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 1.897407 (observed [34]) | 2 (observed [35]) | 0.25481835, 1.897407 (observed [36]) | 3 (observed [37]) |
| 646 | 2 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 3.3037806 (observed [34]) | 2 (observed [35]) | 3.3037806, -1.4110056 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 646 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 637 | 1 (observed [33]) | 3 (observed [41]) | 4.0646267 (observed [34]) | -10.553976, 4.0646267 (observed [36]) |

## Question nvda-01 (NVDA, FIGURE)

Question: What was NVIDIA's total revenue for fiscal 2026 and how much did it grow?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 55 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 20 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001045810-26-000021 ITEM_7: "Revenue $ 215,938 $ 130,497 Up 65%"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [24]) | 489 (derived [25]) | 0, 209 (derived [26]) | [772, 806) (derived [27]) | [151, 165) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | 42 (observed [31]) | false (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | not returned (observed [37]) |

### Accepted phrase 2 of 2

0001045810-26-000021 ITEM_7: "Total $ 215,938 $ 130,497 $ 85,441 65 %"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 805 | 854 (derived [24]) | 489 (derived [25]) | 0, 365 (derived [26]) | [2131, 2170) (derived [27]) | [483, 500) (derived [28]) | partly (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 805 | 3 (observed [31]) | true (observed [32]) | 11 (observed [33]) | 3.0229416 (observed [34]) | 2 (observed [35]) | -1.7458092, 3.0229416 (observed [36]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 805 (observed [39]) |
| bestAcceptedPosition | 11 (observed [33]) |

rankedAbove: 10 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 852 | 1 (observed [33]) | 2 (observed [41]) | 5.752569 (observed [34]) | 5.752569, 2.935733 (observed [36]) |
| 873 | 2 (observed [33]) | 6 (observed [41]) | 5.6226697 (observed [34]) | 5.6226697, 3.8747222 (observed [36]) |
| 864 | 3 (observed [33]) | 15 (observed [41]) | 4.5474877 (observed [34]) | 4.5474877, 3.496665 (observed [36]) |
| 851 | 4 (observed [33]) | 5 (observed [41]) | 4.520654 (observed [34]) | 2.2799497, 4.520654, 3.3260403 (observed [36]) |
| 842 | 5 (observed [33]) | 11 (observed [41]) | 4.3455462 (observed [34]) | 2.8326404, 4.3455462, 3.12772 (observed [36]) |
| 881 | 6 (observed [33]) | 4 (observed [41]) | 4.258242 (observed [34]) | 4.258242, 1.0428793 (observed [36]) |
| 872 | 7 (observed [33]) | 7 (observed [41]) | 4.214299 (observed [34]) | -2.2946634, 3.6153176, 4.214299 (observed [36]) |
| 857 | 8 (observed [33]) | 20 (observed [41]) | 4.1587377 (observed [34]) | 4.1587377, -1.0906353, -5.4814005 (observed [36]) |
| 800 | 9 (observed [33]) | 8 (observed [41]) | 3.7331426 (observed [34]) | -0.18771727, 3.7331426 (observed [36]) |
| 863 | 10 (observed [33]) | 10 (observed [41]) | 3.0975375 (observed [34]) | 3.0975375, 2.7360106 (observed [36]) |

## Question nvda-02 (NVDA, FIGURE)

Question: By what percentage did NVIDIA's Data Center revenue grow in fiscal 2026?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 55 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 18 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "Data Center revenue for fiscal year 2026 was up 68% from a year ago"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [24]) | 491 (derived [25]) | 0, 207 (derived [26]) | [1090, 1157) (derived [27]) | [261, 277) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | 41 (observed [31]) | false (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | none (observed [42]) |
| bestAcceptedPosition | none (observed [42]) |

rankedAbove: 20 chunks (observed [43])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 852 | 1 (observed [33]) | 2 (observed [41]) | 6.633114 (observed [34]) | 6.633114, 4.0947595 (observed [36]) |
| 873 | 2 (observed [33]) | 6 (observed [41]) | 6.472639 (observed [34]) | 6.472639, 4.7060885 (observed [36]) |
| 851 | 3 (observed [33]) | 4 (observed [41]) | 5.426531 (observed [34]) | 0.0878997, 5.426531, 5.3031325 (observed [36]) |
| 881 | 4 (observed [33]) | 5 (observed [41]) | 5.011983 (observed [34]) | 5.011983, 1.8107069 (observed [36]) |
| 805 | 5 (observed [33]) | 3 (observed [41]) | 4.645706 (observed [34]) | -1.24808, 4.645706 (observed [36]) |
| 800 | 6 (observed [33]) | 9 (observed [41]) | 4.6122894 (observed [34]) | 1.9921097, 4.6122894 (observed [36]) |
| 863 | 7 (observed [33]) | 14 (observed [41]) | 3.833008 (observed [34]) | 3.833008, 3.2019277 (observed [36]) |
| 872 | 8 (observed [33]) | 11 (observed [41]) | 3.7129743 (observed [34]) | -4.5718975, 2.9366715, 3.7129743 (observed [36]) |
| 880 | 9 (observed [33]) | 12 (observed [41]) | 3.6941943 (observed [34]) | 3.6941943, -1.0211705, 0.5090763 (observed [36]) |
| 879 | 10 (observed [33]) | 13 (observed [41]) | 3.641285 (observed [34]) | -4.310202, 3.641285 (observed [36]) |
| 842 | 11 (observed [33]) | 15 (observed [41]) | 3.629381 (observed [34]) | 0.178181, 3.629381, 2.4345913 (observed [36]) |
| 806 | 12 (observed [33]) | 1 (observed [41]) | 3.5993948 (observed [34]) | 3.5993948, 0.51455486 (observed [36]) |
| 876 | 13 (observed [33]) | 8 (observed [41]) | 2.6679301 (observed [34]) | 1.7751839, 2.6679301 (observed [36]) |
| 850 | 14 (observed [33]) | 17 (observed [41]) | 1.8427204 (observed [34]) | 0.07739641, 1.8427204 (observed [36]) |
| 745 | 15 (observed [33]) | 7 (observed [41]) | 0.47356513 (observed [34]) | 0.47356513, -0.76055825 (observed [36]) |
| 839 | 16 (observed [33]) | 19 (observed [41]) | 0.13770263 (observed [34]) | -0.9153405, 0.13770263 (observed [36]) |
| 861 | 17 (observed [33]) | 20 (observed [41]) | 0.070556864 (observed [34]) | 0.070556864, -1.3268969, -2.6083248 (observed [36]) |
| 877 | 18 (observed [33]) | 18 (observed [41]) | 0.07051529 (observed [34]) | -3.433707, 0.07051529 (observed [36]) |
| 743 | 19 (observed [33]) | 16 (observed [41]) | -0.06614593 (observed [34]) | -1.7713928, -0.06614593 (observed [36]) |
| 744 | 20 (observed [33]) | 10 (observed [41]) | -1.2369828 (observed [34]) | -1.2369828, -3.8613353 (observed [36]) |

## Question nvda-03 (NVDA, FIGURE)

Question: How many shares did NVIDIA repurchase in fiscal 2026 and at what total cost?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 850 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 52 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 20 (derived [21]) |
| acceptedPhraseCount | 3 (observed [22]) |

### Accepted phrase 1 of 3

0001045810-26-000021 ITEM_7: "we repurchased 282 million shares of our common stock for $40.4 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 809 | 841 (derived [24]) | 489 (derived [25]) | 0, 352 (derived [26]) | [976, 1047) (derived [27]) | [210, 228) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 809 | 42 (observed [31]) | false (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | not returned (observed [37]) |

### Accepted phrase 2 of 3

0001045810-26-000021 ITEM_5: "In fiscal year 2026, we repurchased 282 million shares of our common stock for $40.4 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 797 | 762 (derived [24]) | 489 (derived [25]) | 0, 273 (derived [26]) | [1284, 1376) (derived [27]) | [272, 296) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 797 | 1 (observed [31]) | true (observed [32]) | 4 (observed [33]) | 5.5744867 (observed [34]) | 2 (observed [35]) | 3.8765335, 5.5744867 (observed [36]) | 4 (observed [37]) |

### Accepted phrase 3 of 3

0001045810-26-000021 ITEM_15: "we repurchased 282 million and 310 million shares of our common stock for $40.4 billion and $34.0 billion"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 850 | 661 (derived [24]) | 489 (derived [25]) | 0, 172 (derived [26]) | [532, 637) (derived [27]) | [116, 143) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 850 | 7 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 6.6721816 (observed [34]) | 2 (observed [35]) | 5.459317, 6.6721816 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 850 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question nvda-04 (NVDA, FIGURE)

Question: How many employees did NVIDIA have at the end of fiscal 2026 and how many worked in research and development?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 56 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 24 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1: "we had approximately 42,000 employees in 38 countries; 31,000 were engaged in research and development"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 754 | 721 (derived [24]) | 485 (derived [25]) | 0, 236 (derived [26]) | [3057, 3159) (derived [27]) | [547, 567) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 754 | 54 (observed [31]) | false (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | none (observed [32]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | none (observed [42]) |
| bestAcceptedPosition | none (observed [42]) |

rankedAbove: 20 chunks (observed [43])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 755 | 1 (observed [33]) | 12 (observed [41]) | 0.7350045 (observed [34]) | 0.7350045, -5.8810334 (observed [36]) |
| 844 | 2 (observed [33]) | 16 (observed [41]) | 0.3645653 (observed [34]) | -5.749834, 0.3645653 (observed [36]) |
| 864 | 3 (observed [33]) | 14 (observed [41]) | 0.30962372 (observed [34]) | 0.30962372, -2.5323696 (observed [36]) |
| 872 | 4 (observed [33]) | 4 (observed [41]) | -0.009499551 (observed [34]) | -6.3270845, -1.2999177, -0.009499551 (observed [36]) |
| 853 | 5 (observed [33]) | 18 (observed [41]) | -0.02813741 (observed [34]) | -0.02813741, -4.556562 (observed [36]) |
| 852 | 6 (observed [33]) | 1 (observed [41]) | -0.22731502 (observed [34]) | -0.22731502, -1.2705895 (observed [36]) |
| 851 | 7 (observed [33]) | 2 (observed [41]) | -0.2584022 (observed [34]) | -1.812946, -0.2584022, -1.6140448 (observed [36]) |
| 873 | 8 (observed [33]) | 6 (observed [41]) | -0.348092 (observed [34]) | -0.348092, -0.5932515 (observed [36]) |
| 842 | 9 (observed [33]) | 11 (observed [41]) | -0.8147357 (observed [34]) | -3.0852463, -0.8147357, -1.1825566 (observed [36]) |
| 863 | 10 (observed [33]) | 5 (observed [41]) | -1.0796305 (observed [34]) | -1.0868273, -1.0796305 (observed [36]) |
| 862 | 11 (observed [33]) | 9 (observed [41]) | -1.3427128 (observed [34]) | -1.3427128, -4.446053, -5.470529 (observed [36]) |
| 806 | 12 (observed [33]) | 7 (observed [41]) | -1.5773528 (observed [34]) | -1.5773528, -5.251176 (observed [36]) |
| 746 | 13 (observed [33]) | 13 (observed [41]) | -1.9536417 (observed [34]) | -3.4460442, -1.9536417 (observed [36]) |
| 881 | 14 (observed [33]) | 19 (observed [41]) | -2.4374158 (observed [34]) | -2.4374158, -3.921367 (observed [36]) |
| 745 | 15 (observed [33]) | 8 (observed [41]) | -2.4525726 (observed [34]) | -2.4525726, -2.5188797 (observed [36]) |
| 839 | 16 (observed [33]) | 15 (observed [41]) | -2.7513485 (observed [34]) | -4.565421, -2.7513485 (observed [36]) |
| 861 | 17 (observed [33]) | 10 (observed [41]) | -3.0456245 (observed [34]) | -3.7836301, -3.0456245, -6.2132545 (observed [36]) |
| 859 | 18 (observed [33]) | 17 (observed [41]) | -3.4376917 (observed [34]) | -3.4376917, -4.099603 (observed [36]) |
| 805 | 19 (observed [33]) | 3 (observed [41]) | -3.635834 (observed [34]) | -5.879581, -3.635834 (observed [36]) |
| 742 | 20 (observed [33]) | 20 (observed [41]) | -5.992328 (observed [34]) | -5.992328, -7.6157184 (observed [36]) |

## Question nvda-05 (NVDA, NARRATIVE)

Question: Does NVIDIA manufacture its own chips, or how is its manufacturing organised?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 53 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 16 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1: "We utilize a fabless and contracting manufacturing strategy, whereby we employ and partner with key suppliers for all phases of the manufacturing process"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 749 | 700 (derived [24]) | 493 (derived [25]) | 0, 207 (derived [26]) | [2401, 2554) (derived [27]) | [433, 458) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 749 | 10 (observed [31]) | true (observed [32]) | 20 (observed [33]) | -8.927582 (observed [34]) | 2 (observed [35]) | -9.51824, -8.927582 (observed [36]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 749 (observed [39]) |
| bestAcceptedPosition | 20 (observed [33]) |

rankedAbove: 19 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 745 | 1 (observed [33]) | 2 (observed [41]) | -0.08763391 (observed [34]) | -2.1435957, -0.08763391 (observed [36]) |
| 742 | 2 (observed [33]) | 1 (observed [41]) | -0.82564175 (observed [34]) | -0.82564175, -5.102226 (observed [36]) |
| 876 | 3 (observed [33]) | 7 (observed [41]) | -1.0741127 (observed [34]) | -1.0741127, -5.4180374 (observed [36]) |
| 806 | 4 (observed [33]) | 20 (observed [41]) | -2.0800035 (observed [34]) | -2.0800035, -10.637027 (observed [36]) |
| 744 | 5 (observed [33]) | 4 (observed [41]) | -2.7406425 (observed [34]) | -3.4110684, -2.7406425 (observed [36]) |
| 800 | 6 (observed [33]) | 8 (observed [41]) | -3.4410684 (observed [34]) | -3.4410684, -4.5369587 (observed [36]) |
| 783 | 7 (observed [33]) | 19 (observed [41]) | -3.7147164 (observed [34]) | -8.311266, -3.7147164 (observed [36]) |
| 747 | 8 (observed [33]) | 5 (observed [41]) | -3.9215257 (observed [34]) | -3.9215257, -5.543222 (observed [36]) |
| 743 | 9 (observed [33]) | 3 (observed [41]) | -4.2028246 (observed [34]) | -4.2028246, -5.501903 (observed [36]) |
| 875 | 10 (observed [33]) | 18 (observed [41]) | -5.212839 (observed [34]) | -9.216861, -5.212839 (observed [36]) |
| 750 | 11 (observed [33]) | 9 (observed [41]) | -5.2451897 (observed [34]) | -7.0998397, -5.2451897 (observed [36]) |
| 755 | 12 (observed [33]) | 11 (observed [41]) | -5.527222 (observed [34]) | -6.4621224, -5.527222 (observed [36]) |
| 746 | 13 (observed [33]) | 6 (observed [41]) | -5.755439 (observed [34]) | -7.0888615, -5.755439 (observed [36]) |
| 828 | 14 (observed [33]) | 15 (observed [41]) | -6.177638 (observed [34]) | -8.027894, -6.177638 (observed [36]) |
| 868 | 15 (observed [33]) | 13 (observed [41]) | -6.1801186 (observed [34]) | -8.598624, -6.1801186 (observed [36]) |
| 756 | 16 (observed [33]) | 14 (observed [41]) | -6.3999815 (observed [34]) | -6.6401057, -6.3999815 (observed [36]) |
| 869 | 17 (observed [33]) | 17 (observed [41]) | -6.455841 (observed [34]) | -6.950676, -6.455841 (observed [36]) |
| 866 | 18 (observed [33]) | 16 (observed [41]) | -7.4764686 (observed [34]) | -11.173187, -7.4764686 (observed [36]) |
| 748 | 19 (observed [33]) | 12 (observed [41]) | -8.017606 (observed [34]) | -8.017606, -10.462522 (observed [36]) |

## Question nvda-06 (NVDA, NARRATIVE)

Question: How do export controls on AI chips affect NVIDIA's supply chain and its ability to serve demand?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 894 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 51 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 22 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001045810-26-000021 ITEM_1A: "Export controls have and could in the future disrupt our supply chain and distribution channels, negatively impacting our ability to serve demand"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 779 | 696 (derived [24]) | 487 (derived [25]) | 0, 209 (derived [26]) | [3435, 3580) (derived [27]) | [621, 645) (derived [28]) | not (derived [29]) | 2 (derived [30]) |
| 780 | 744 (derived [24]) | 487 (derived [25]) | 0, 257 (derived [26]) | [49, 194) (derived [27]) | [11, 35) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 779 | 2 (observed [31]) | true (observed [32]) | 3 (observed [33]) | 4.4593525 (observed [34]) | 2 (observed [35]) | -0.029959977, 4.4593525 (observed [36]) | 3 (observed [37]) |
| 780 | 7 (observed [31]) | true (observed [32]) | 5 (observed [33]) | 2.5936837 (observed [34]) | 2 (observed [35]) | 2.5936837, -3.49114 (observed [36]) | 5 (observed [37]) |

### Accepted phrase 2 of 2

0001045810-26-000075 ITEM_1A: "Export controls have and could in the future disrupt our supply chain and distribution channels, negatively impacting our ability to serve demand, including in markets outside China"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 894 | 691 (derived [24]) | 487 (derived [25]) | 0, 204 (derived [26]) | [743, 924) (derived [27]) | [137, 167) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 894 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 5.2965107 (observed [34]) | 2 (observed [35]) | 5.2965107, -0.2538098 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 894 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question nvda-07 (NVDA, NARRATIVE)

Question: In which regions are NVIDIA's component manufacturing and final assembly concentrated, and what geopolitical risk does that create?

| Field | Value |
|---|---|
| rank | 8 (observed [12]) |
| matchedChunkId | 770 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 58 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 26 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1A: "China, Hong Kong, Israel, Korea and Taiwan where the manufacture of our product components and final assembly of our products are concentrated"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 770 | 685 (derived [24]) | 483 (derived [25]) | 0, 202 (derived [26]) | [2414, 2556) (derived [27]) | [416, 441) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 770 | 20 (observed [31]) | true (observed [32]) | 8 (observed [33]) | -5.372522 (observed [34]) | 2 (observed [35]) | -5.372522, -6.2818437 (observed [36]) | 8 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 770 (observed [39]) |
| bestAcceptedPosition | 8 (observed [33]) |

rankedAbove: 7 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 898 | 1 (observed [33]) | 3 (observed [41]) | -3.073237 (observed [34]) | -3.073237, -4.409566 (observed [36]) |
| 783 | 2 (observed [33]) | 2 (observed [41]) | -3.367956 (observed [34]) | -9.950336, -3.367956 (observed [36]) |
| 800 | 3 (observed [33]) | 1 (observed [41]) | -3.586166 (observed [34]) | -3.586166, -5.326608 (observed [36]) |
| 744 | 4 (observed [33]) | 10 (observed [41]) | -4.034582 (observed [34]) | -4.034582, -6.617652 (observed [36]) |
| 876 | 5 (observed [33]) | 5 (observed [41]) | -4.135933 (observed [34]) | -4.135933, -7.2172093 (observed [36]) |
| 875 | 6 (observed [33]) | 16 (observed [41]) | -4.95624 (observed [34]) | -8.561779, -4.95624 (observed [36]) |
| 761 | 7 (observed [33]) | 4 (observed [41]) | -5.0713964 (observed [34]) | -5.0713964, -6.8555813 (observed [36]) |

## Question nvda-08 (NVDA, FIGURE)

Question: How concentrated was NVIDIA's fiscal 2026 revenue among its largest direct customers?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 852 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 57 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 18 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_15: "sales to one direct customer represented 22% of total revenue and sales to another direct customer represented 14% of total revenue"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 852 | 822 (derived [24]) | 491 (derived [25]) | 0, 331 (derived [26]) | [975, 1106) (derived [27]) | [206, 229) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 852 | 5 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 5.9573913 (observed [34]) | 2 (observed [35]) | 5.9573913, -0.22314033 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 852 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question nvda-09 (NVDA, FIGURE)

Question: What was NVIDIA's Data Center revenue in the second quarter of fiscal 2027?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 53 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 19 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_2: "Data Center revenue was $89.0 billion, up 117% from a year ago and up 18% sequentially"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 879 | 889 (derived [24]) | 490 (derived [25]) | 0, 399 (derived [26]) | [3149, 3235) (derived [27]) | [740, 763) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 879 | 11 (observed [31]) | true (observed [32]) | 12 (observed [33]) | 3.4706917 (observed [34]) | 2 (observed [35]) | -5.17135, 3.4706917 (observed [36]) | not returned (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 879 (observed [39]) |
| bestAcceptedPosition | 12 (observed [33]) |

rankedAbove: 11 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 873 | 1 (observed [33]) | 1 (observed [41]) | 7.202895 (observed [34]) | 7.202895, 6.40621 (observed [36]) |
| 872 | 2 (observed [33]) | 8 (observed [41]) | 5.423826 (observed [34]) | -0.84332705, 5.2825027, 5.423826 (observed [36]) |
| 881 | 3 (observed [33]) | 2 (observed [41]) | 5.3099732 (observed [34]) | 5.3099732, 3.3081412 (observed [36]) |
| 863 | 4 (observed [33]) | 15 (observed [41]) | 4.752713 (observed [34]) | 4.1551843, 4.752713 (observed [36]) |
| 851 | 5 (observed [33]) | 10 (observed [41]) | 4.7330065 (observed [34]) | -1.0034841, 4.7330065, 4.212405 (observed [36]) |
| 864 | 6 (observed [33]) | 14 (observed [41]) | 4.437373 (observed [34]) | 4.437373, 2.162728 (observed [36]) |
| 800 | 7 (observed [33]) | 6 (observed [41]) | 4.1495304 (observed [34]) | 4.142661, 4.1495304 (observed [36]) |
| 871 | 8 (observed [33]) | 16 (observed [41]) | 4.001212 (observed [34]) | -1.4970369, 4.001212 (observed [36]) |
| 852 | 9 (observed [33]) | 4 (observed [41]) | 3.960417 (observed [34]) | 3.960417, 3.4100316 (observed [36]) |
| 880 | 10 (observed [33]) | 13 (observed [41]) | 3.7068186 (observed [34]) | 3.7068186, 0.060858797, 0.66639626 (observed [36]) |
| 876 | 11 (observed [33]) | 3 (observed [41]) | 3.6979165 (observed [34]) | 2.7223034, 3.6979165 (observed [36]) |

## Question nvda-10 (NVDA, NARRATIVE)

Question: Has NVIDIA been able to sell H200 products to customers in China under the new U.S. licenses?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 878 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 55 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 24 (derived [21]) |
| acceptedPhraseCount | 2 (observed [22]) |

### Accepted phrase 1 of 2

0001045810-26-000075 ITEM_2: "ship small amounts of H200 products to specific China-based customers, but such sales were restricted by the PRC government"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 878 | 711 (derived [24]) | 485 (derived [25]) | 0, 226 (derived [26]) | [2479, 2602) (derived [27]) | [450, 473) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 878 | 5 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 4.436578 (observed [34]) | 2 (observed [35]) | 4.436578, 3.4051178 (observed [36]) | 2 (observed [37]) |

### Accepted phrase 2 of 2

0001045810-26-000075 ITEM_1A: "Beginning in February 2026, the USG granted licenses that would allow us to ship small amounts of H200 products to specific China-based customers"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 895 | 804 (derived [24]) | 485 (derived [25]) | 0, 319 (derived [26]) | [2497, 2642) (derived [27]) | [521, 550) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 895 | 1 (observed [31]) | true (observed [32]) | 5 (observed [33]) | 4.226551 (observed [34]) | 2 (observed [35]) | 2.3640492, 4.226551 (observed [36]) | 5 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 878 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 781 | 1 (observed [33]) | 2 (observed [41]) | 5.511688 (observed [34]) | 4.3728867, 5.511688 (observed [36]) |

## Question nvda-11 (NVDA, FIGURE)

Question: NVIDIA's Compute & Networking segment revenue was $193,479 million in fiscal 2026. What drove the year-over-year increase?

| Field | Value |
|---|---|
| rank | 5 (observed [12]) |
| matchedChunkId | 805 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 54 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 32 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "Revenue from Data Center computing grew 59% driven by demand for our Blackwell computing platform"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 805 | 854 (derived [24]) | 477 (derived [25]) | 0, 377 (derived [26]) | [2538, 2635) (derived [27]) | [596, 612) (derived [28]) | not (derived [29]) | 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 805 | 1 (observed [31]) | true (observed [32]) | 5 (observed [33]) | 5.494438 (observed [34]) | 2 (observed [35]) | 2.6233287, 5.494438 (observed [36]) | 5 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 805 (observed [39]) |
| bestAcceptedPosition | 5 (observed [33]) |

rankedAbove: 4 chunks (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 852 | 1 (observed [33]) | 6 (observed [41]) | 6.142663 (observed [34]) | 6.142663, 4.4243083 (observed [36]) |
| 881 | 2 (observed [33]) | 3 (observed [41]) | 5.8459706 (observed [34]) | 5.8459706, 3.173798 (observed [36]) |
| 873 | 3 (observed [33]) | 9 (observed [41]) | 5.6685553 (observed [34]) | 5.6685553, 2.9199445 (observed [36]) |
| 851 | 4 (observed [33]) | 2 (observed [41]) | 5.52351 (observed [34]) | 5.52351, 4.9328527, 4.58624 (observed [36]) |

## Question nvda-12 (NVDA, FIGURE)

Question: NVIDIA's Professional Visualization revenue climbed 70% in fiscal 2026. What was behind the jump?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 802 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 59 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 23 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "driven by exceptional demand for Blackwell as well as the launch of our new DGX Spark"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [24]) | 486 (derived [25]) | 0, 212 (derived [26]) | [1549, 1634) (derived [27]) | [361, 379) (derived [28]) | wholly (derived [29]) | 1, 2 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | 5 (observed [31]) | true (observed [32]) | 2 (observed [33]) | 1.452688 (observed [34]) | 2 (observed [35]) | 0.7231179, 1.452688 (observed [36]) | 2 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 802 (observed [39]) |
| bestAcceptedPosition | 2 (observed [33]) |

rankedAbove: 1 chunk (observed [40])

| Chunk | position | fusedPosition | score | windowScores |
|---|---|---|---|---|
| 852 | 1 (observed [33]) | 8 (observed [41]) | 3.08356 (observed [34]) | 3.08356, 0.041324746 (observed [36]) |

## Question nvda-13 (NVDA, FIGURE)

Question: How did the $7.2 billion NVIDIA booked from Edge Computing in the second quarter of fiscal 2027 compare with the same quarter last year and with the quarter before?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 879 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 51 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 36 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_2: "Edge Computing revenue was $7.2 billion, up 27% from a year ago and up 13% sequentially"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 879 | 889 (derived [24]) | 473 (derived [25]) | 0, 409, 416 (derived [26]) | [3739, 3826) (derived [27]) | [865, 888) (derived [28]) | not (derived [29]) | 3 (derived [30]) |
| 880 | 980 (derived [24]) | 473 (derived [25]) | 0, 409, 507 (derived [26]) | [412, 499) (derived [27]) | [85, 108) (derived [28]) | wholly (derived [29]) | 1 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 879 | 2 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 2.636987 (observed [34]) | 3 (observed [35]) | -5.335017, 2.6059003, 2.636987 (observed [36]) | 1 (observed [37]) |
| 880 | 1 (observed [31]) | true (observed [32]) | 5 (observed [33]) | 1.5419891 (observed [34]) | 3 (observed [35]) | 1.5419891, -4.0701585, -1.7052798 (observed [36]) | 5 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 879 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

## Question nvda-14 (NVDA, FIGURE)

Question: NVIDIA's revenue reached $96,221 million in the second quarter of fiscal 2027. How much of it came from customers headquartered in Taiwan?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 872 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | RERANKED (observed [16]) |
| fallbackReason | none (observed [17]) |
| scoresNotRecorded | none (observed [18]) |
| fusedCount | 56 (observed [19]) |
| rerankInputCount | 20 (observed [20]) |
| queryTokens | 32 (derived [21]) |
| acceptedPhraseCount | 1 (observed [22]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_1: "Taiwan 26,985 8,902 38,991 16,550"

heldByStoredChunk: true (observed [23])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 872 | 996 (derived [24]) | 477 (derived [25]) | 0, 413, 519 (derived [26]) | [3289, 3322) (derived [27]) | [820, 836) (derived [28]) | not (derived [29]) | 2, 3 (derived [30]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 872 | 1 (observed [31]) | true (observed [32]) | 1 (observed [33]) | 5.9464593 (observed [34]) | 3 (observed [35]) | -0.26110324, 5.9464593, 4.7705812 (observed [36]) | 1 (observed [37]) |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | reranked order (observed [38]) |
| bestAcceptedChunk | 872 (observed [39]) |
| bestAcceptedPosition | 1 (observed [33]) |

rankedAbove: 0 chunks (observed [40])

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
22. observed: bundled set evaluation/retrieval-set-v2.json
23. observed: sec_filing_chunks at report time: chunks of the phrase's accession and section whose text contains the phrase (RetrievalEvaluationService.matches)
24. derived: tokens of the stored chunk text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer
25. derived: W = max-length - 3 - the query tokens kept against the whole chunk, longest first (CrossEncoderPairAssembler.windowLength)
26. derived: start token of each scored row under the snapshot's rerankerScoring: head one row at 0; max-window CrossEncoderPairAssembler.windowStarts(chunk tokens, W, overlap, max-windows)
27. derived: occurrence of the normalised phrase in the normalised chunk text (whitespace runs collapsed, trimmed, lower-cased), mapped to UTF-16 offsets of the stored text, end exclusive
28. derived: tokens of the whole-chunk tokenization whose character span overlaps the occurrence, end exclusive
29. derived: wholly: token span end <= W; partly: start < W < end; not: start >= W
30. derived: 1-based rows whose tokens [start, start + min(W, chunk tokens)) contain the whole token span (empty: no row holds it wholly)
31. observed: trace fused (null: not in the fused list)
32. observed: trace rerank candidates (null: not a rerank input)
33. observed: trace rerank candidates rerankedPosition
34. observed: trace rerank candidates score
35. observed: trace rerank candidates windowCount
36. observed: trace rerank candidates windowScores
37. observed: trace returnedChunkIds (null: not returned)
38. observed: trace rerank outcome RERANKED
39. observed: trace reranked order: first chunk holding an accepted phrase
40. observed: trace reranked order: every chunk ranked above the best accepted chunk
41. observed: trace rerank candidates fusedPosition
42. observed: trace reranked order: no chunk holding an accepted phrase is in it
43. observed: trace reranked order: every chunk (no chunk holding an accepted phrase is in it)

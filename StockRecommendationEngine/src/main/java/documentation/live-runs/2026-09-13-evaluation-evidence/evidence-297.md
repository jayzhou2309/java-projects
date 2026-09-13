# Retrieval evidence report: snapshot 297

Each value is followed by its basis: observed (read from the numbered source), derived (computed by the numbered rule), or unknown with the reason. Sources and rules are listed at the end. Offsets are [start, end), end exclusive; window numbers start at 1.

## Snapshot

| Field | Value |
|---|---|
| setVersion | v2 (observed [1]) |
| set | evaluation/retrieval-set-v2.json (observed [2]) |
| traced | false (observed [3]) |
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
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 23 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_7: "Greater China 64,377 (4) % 66,952 (8) % 72,559"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 225 | 815 (derived [19]) | 486 (derived [20]) | 0, 329 (derived [21]) | [1080, 1126) (derived [22]) | [218, 240) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 225 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "China (1) 64,377 66,952 72,559"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 246 | 1131 (derived [19]) | 486 (derived [20]) | 0, 422, 645 (derived [21]) | [3537, 3567) (derived [22]) | [1013, 1029) (derived [23]) | not (derived [24]) | 3 (derived [25]) |
| 247 | 763 (derived [19]) | 486 (derived [20]) | 0, 277 (derived [21]) | [189, 219) (derived [22]) | [54, 70) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 246 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 247 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-02 (AAPL, FIGURE)

Question: What was Apple's effective tax rate for fiscal 2025 versus the prior year?

| Field | Value |
|---|---|
| rank | 3 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 17 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_7: "Effective tax rate 15.6 % 24.1 % 14.7 %"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [19]) | 492 (derived [20]) | 0, 278 (derived [21]) | [3015, 3054) (derived [22]) | [691, 706) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 227 | 833 (derived [19]) | 492 (derived [20]) | 0, 341 (derived [21]) | [166, 205) (derived [22]) | [48, 63) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 227 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "Provision for income taxes $ 20,719 $ 29,749 $ 16,741 Effective tax rate 15.6 %"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 241 | 1075 (derived [19]) | 492 (derived [20]) | 0, 428, 583 (derived [21]) | [1070, 1149) (derived [22]) | [360, 386) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 241 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-03 (AAPL, NARRATIVE)

Question: Why did Apple's products gross margin percentage fall in fiscal 2025?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 15 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Products gross margin percentage decreased during 2025 compared to 2024 primarily due to a different mix of products and tariff costs"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [19]) | 494 (derived [20]) | 0, 276 (derived [21]) | [1128, 1261) (derived [22]) | [269, 292) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-04 (AAPL, NARRATIVE)

Question: What has Apple changed in the EU to comply with the Digital Markets Act?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 210 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 15 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_1A: "in the EU as it seeks to comply with the Digital Markets Act"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 210 | 754 (derived [19]) | 494 (derived [20]) | 0, 260 (derived [21]) | [592, 652) (derived [22]) | [106, 119) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 210 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-05 (AAPL, NARRATIVE)

Question: How does Apple organise its reportable segments?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 246 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 9 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_1: "reportable segments consist of the Americas, Europe, Greater China, Japan and Rest of Asia Pacific"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 190 | 808 (derived [19]) | 500 (derived [20]) | 0, 308 (derived [21]) | [3121, 3219) (derived [22]) | [655, 674) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 190 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0000320193-25-000079 ITEM_8: "The Company manages its business primarily on a geographic basis"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 245 | 751 (derived [19]) | 500 (derived [20]) | 0, 251 (derived [21]) | [3568, 3632) (derived [22]) | [671, 681) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 246 | 1131 (derived [19]) | 500 (derived [20]) | 0, 436, 631 (derived [21]) | [99, 163) (derived [22]) | [24, 34) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 245 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 246 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-06 (AAPL, FIGURE)

Question: How much total deferred revenue did Apple carry at the end of fiscal 2025?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 269 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 18 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0000320193-25-000079 ITEM_8: "the Company had total deferred revenue of $13.7 billion and $12.8 billion, respectively"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 236 | 897 (derived [19]) | 491 (derived [20]) | 0, 406 (derived [21]) | [1524, 1611) (derived [22]) | [375, 397) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 236 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0000320193-26-000020 ITEM_1: "total deferred revenue of $14.9 billion and $13.7 billion, respectively"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 269 | 1044 (derived [19]) | 491 (derived [20]) | 0, 427, 553 (derived [21]) | [1886, 1957) (derived [22]) | [484, 503) (derived [23]) | partly (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 269 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-07 (AAPL, FIGURE)

Question: What one-time income tax charge did Apple record after the European Court of Justice upheld the State Aid Decision?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 240 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 22 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_8: "recorded a one-time income tax charge of $10.2 billion, net, which represented $15.8 billion payable to Ireland"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 240 | 819 (derived [19]) | 487 (derived [20]) | 0, 332 (derived [21]) | [2240, 2351) (derived [22]) | [529, 557) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 240 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-08 (AAPL, NARRATIVE)

Question: Which quarter's results did Apple's most recent earnings press release cover?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 297 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 16 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-26-000018 ITEM_2: "financial results for its third fiscal quarter ended June 27, 2026"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 297 | 130 (derived [19]) | 493 (derived [20]) | 0 (derived [21]) | [80, 146) (derived [22]) | [23, 36) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 297 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-09 (AAPL, FIGURE)

Question: How much of its own stock did Apple buy back in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 19 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "During the third quarter of 2026, the Company repurchased $25.8 billion of its common stock"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 280 | 818 (derived [19]) | 490 (derived [20]) | 0, 328 (derived [21]) | [2531, 2622) (derived [22]) | [509, 532) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 280 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-10 (AAPL, NARRATIVE)

Question: What drove the increase in Apple's Greater China net sales in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 277 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 21 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "Greater China net sales increased during the third quarter and first nine months of 2026 compared to the same periods in 2025 primarily due to higher net sales of iPhone"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 277 | 680 (derived [19]) | 488 (derived [20]) | 0, 192 (derived [21]) | [1989, 2158) (derived [22]) | [462, 494) (derived [23]) | partly (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 277 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-11 (AAPL, FIGURE)

Question: Apple's Services net sales grew 14% to $109,158 million in fiscal 2025. What drove the increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 225 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 25 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Services net sales increased during 2025 compared to 2024 primarily due to higher net sales from advertising, the App Store and cloud services"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 225 | 815 (derived [19]) | 484 (derived [20]) | 0, 331 (derived [21]) | [3276, 3418) (derived [22]) | [785, 811) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 226 | 770 (derived [19]) | 484 (derived [20]) | 0, 286 (derived [21]) | [346, 488) (derived [22]) | [72, 98) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 225 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 226 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-12 (AAPL, FIGURE)

Question: Apple's Services gross margin percentage reached 75.4% in fiscal 2025, up from 73.9%. Why did it rise?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 226 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 29 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-25-000079 ITEM_7: "Services gross margin percentage increased during 2025 compared to 2024 primarily due to a different mix of services"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 226 | 770 (derived [19]) | 480 (derived [20]) | 0, 290 (derived [21]) | [1465, 1581) (derived [22]) | [328, 348) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 226 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-13 (AAPL, FIGURE)

Question: Apple's iPhone net sales came to $54,252 million in the third quarter of fiscal 2026. What drove the year-over-year increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 278 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 32 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "iPhone net sales increased during the third quarter and first nine months of 2026 compared to the same periods in 2025 primarily due to higher net sales of Pro models"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 278 | 882 (derived [19]) | 477 (derived [20]) | 0, 405 (derived [21]) | [1216, 1382) (derived [22]) | [342, 374) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 278 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question aapl-14 (AAPL, FIGURE)

Question: Apple's research and development expense rose 32% to $11,729 million in the third quarter of fiscal 2026. What was behind the increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 279 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 32 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0000320193-26-000020 ITEM_2: "primarily due to higher infrastructure-related costs, including investments in artificial intelligence, and headcount-related expenses"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 279 | 886 (derived [19]) | 477 (derived [20]) | 0, 409 (derived [21]) | [1835, 1969) (derived [22]) | [437, 459) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 279 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-01 (MSFT, FIGURE)

Question: How much did Microsoft Cloud revenue grow in fiscal 2026 and what did it reach?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 573 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 17 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_7: "Microsoft Cloud revenue increased 27% to $214.4 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 514 | 660 (derived [19]) | 492 (derived [20]) | 0, 168 (derived [21]) | [1845, 1900) (derived [22]) | [358, 370) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 514 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001193125-26-323660 ITEM_8: "was $214.4 billion, $168.9 billion, and $137.7 billion in fiscal years 2026, 2025, and 2024"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 573 | 449 (derived [19]) | 492 (derived [20]) | 0 (derived [21]) | [794, 885) (derived [22]) | [215, 246) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 573 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-02 (MSFT, FIGURE)

Question: What was Microsoft's commercial remaining performance obligation at the end of fiscal 2026?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 514 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 17 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_7: "Commercial remaining performance obligation increased 84% to $678 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 514 | 660 (derived [19]) | 492 (derived [20]) | 0, 168 (derived [21]) | [1904, 1977) (derived [22]) | [372, 384) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 514 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001193125-26-323660 ITEM_8: "Revenue allocated to remaining performance obligations related to the commercial portion of revenue was $678 billion as of June 30, 2026"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 565 | 480 (derived [19]) | 492 (derived [20]) | 0 (derived [21]) | [1012, 1148) (derived [22]) | [236, 261) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 565 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-03 (MSFT, NARRATIVE)

Question: Why did Microsoft Cloud's gross margin percentage decline in fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 517 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 15 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "Microsoft Cloud gross margin percentage decreased to 66% driven by continued investments in AI infrastructure and growing AI product usage"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 517 | 610 (derived [19]) | 494 (derived [20]) | 0, 116 (derived [21]) | [2283, 2421) (derived [22]) | [473, 494) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 517 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-04 (MSFT, FIGURE)

Question: How many people did Microsoft employ at the end of fiscal 2026, and how were they split between the U.S. and other countries?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 29 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_1: "we employed approximately 223,000 people on a full-time basis, 121,000 in the U.S. and 102,000 internationally"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 466 | 693 (derived [19]) | 480 (derived [20]) | 0, 213 (derived [21]) | [3503, 3613) (derived [22]) | [606, 634) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 467 | 282 (derived [19]) | 480 (derived [20]) | 0 (derived [21]) | [96, 206) (derived [22]) | [20, 48) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 466 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 467 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-05 (MSFT, NARRATIVE)

Question: What are Microsoft's three reportable segments in its fiscal 2026 annual report?

| Field | Value |
|---|---|
| rank | 10 (observed [12]) |
| matchedChunkId | 460 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 17 (derived [16]) |
| acceptedPhraseCount | 3 (observed [17]) |

### Accepted phrase 1 of 3

0001193125-26-323660 ITEM_1: "using three segments: Productivity and Business Processes, Intelligent Cloud, and More Personal Computing"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 460 | 650 (derived [19]) | 492 (derived [20]) | 0, 158 (derived [21]) | [624, 729) (derived [22]) | [105, 121) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 460 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 3

0001193125-26-323660 ITEM_7: "We report our financial performance based on the following three segments"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 515 | 700 (derived [19]) | 492 (derived [20]) | 0, 208 (derived [21]) | [3437, 3510) (derived [22]) | [614, 625) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 515 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 3 of 3

0001193125-26-323660 ITEM_8: "we reported our financial performance based on the following three segments"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 571 | 655 (derived [19]) | 492 (derived [20]) | 0, 163 (derived [21]) | [680, 755) (derived [22]) | [121, 132) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 571 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-06 (MSFT, NARRATIVE)

Question: How could power and energy constraints limit Microsoft's datacenter expansion?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 489 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 15 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_1A: "requirements imposed by utilities, regulators, or other market participants could restrict our ability to develop or expand datacenter capacity"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 489 | 642 (derived [19]) | 494 (derived [20]) | 0, 148 (derived [21]) | [1944, 2087) (derived [22]) | [326, 349) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 489 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-07 (MSFT, NARRATIVE)

Question: What sustainability goals has Microsoft committed to by 2030 and why are they harder to meet?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 680 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 18 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001193125-26-323660 ITEM_1A: "in 2020 we announced goals to become carbon negative, water positive, and zero waste by 2030"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 495 | 695 (derived [19]) | 491 (derived [20]) | 0, 204 (derived [21]) | [2163, 2255) (derived [22]) | [374, 393) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 495 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001193125-26-191507 ITEM_1A: "AI development and deployment has and may continue to raise energy use and emissions, making it harder to meet these goals"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 680 | 701 (derived [19]) | 491 (derived [20]) | 0, 210 (derived [21]) | [1431, 1553) (derived [22]) | [259, 281) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 680 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-08 (MSFT, FIGURE)

Question: How much revenue did Microsoft record from its commercial arrangements with OpenAI in fiscal 2026?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 547 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 18 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_8: "we recorded revenue from commercial arrangements with OpenAI, inclusive of revenue-sharing payments, of $24.1 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 547 | 780 (derived [19]) | 491 (derived [20]) | 0, 289 (derived [21]) | [576, 693) (derived [22]) | [122, 145) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 547 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-09 (MSFT, NARRATIVE)

Question: What change to its reportable segments did Microsoft announce for fiscal 2027?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 1064 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 15 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-380280 ITEM_7_01: "two reportable segments: (1) Agents and Infra and (2) Devices and Consumer"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 1064 | 263 (derived [19]) | 494 (derived [20]) | 0 (derived [21]) | [388, 462) (derived [22]) | [71, 90) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 1064 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-10 (MSFT, FIGURE)

Question: What was Microsoft Cloud revenue in the third quarter of fiscal 2026?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 637 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 14 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001193125-26-191507 ITEM_2: "Microsoft Cloud revenue increased 29% to $54.5 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 637 | 737 (derived [19]) | 495 (derived [20]) | 0, 242 (derived [21]) | [3081, 3135) (derived [22]) | [620, 632) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 637 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001193125-26-191507 ITEM_1: "was $54.5 billion and $155.1 billion for the three and nine months ended March 31, 2026"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 635 | 426 (derived [19]) | 495 (derived [20]) | 0 (derived [21]) | [899, 986) (derived [22]) | [266, 290) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 635 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-11 (MSFT, FIGURE)

Question: Microsoft's cloud services and server products line gained $31.0 billion, a 31% increase, in its most recent fiscal year; which offerings accounted for it?

| Field | Value |
|---|---|
| rank | 3 (observed [12]) |
| matchedChunkId | 519 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 34 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "Server products and cloud services revenue increased $31.0 billion or 31% driven by Azure and other cloud services"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 519 | 605 (derived [19]) | 475 (derived [20]) | 0, 130 (derived [21]) | [981, 1095) (derived [22]) | [189, 211) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 519 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-12 (MSFT, FIGURE)

Question: Why did XBOX hardware sales at Microsoft come in 29% lower in its most recent fiscal year?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 519 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 19 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "XBOX hardware revenue decreased 29% driven by lower volume of consoles sold"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 519 | 605 (derived [19]) | 490 (derived [20]) | 0, 115 (derived [21]) | [2827, 2902) (derived [22]) | [544, 557) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 519 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-13 (MSFT, FIGURE)

Question: Microsoft generated $182.9 billion of cash from operations in fiscal 2026. What explains the $46.8 billion increase?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 524 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 26 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-323660 ITEM_7: "primarily due to an increase in cash received from customers and a decrease in cash used to pay income taxes"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 523 | 734 (derived [19]) | 483 (derived [20]) | 0, 251 (derived [21]) | [3377, 3485) (derived [22]) | [630, 650) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 524 | 344 (derived [19]) | 483 (derived [20]) | 0 (derived [21]) | [54, 162) (derived [22]) | [17, 37) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 523 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 524 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question msft-14 (MSFT, FIGURE)

Question: What was behind the 40% increase in sales of Azure and Microsoft's other cloud offerings in its fiscal third quarter?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 646 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 24 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001193125-26-191507 ITEM_2: "Azure and other cloud services revenue grew 40% driven by demand for services across the platform"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 644 | 533 (derived [19]) | 485 (derived [20]) | 0, 48 (derived [21]) | [1113, 1210) (derived [22]) | [215, 232) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |
| 646 | 538 (derived [19]) | 485 (derived [20]) | 0, 53 (derived [21]) | [176, 273) (derived [22]) | [37, 54) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 644 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 646 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-01 (NVDA, FIGURE)

Question: What was NVIDIA's total revenue for fiscal 2026 and how much did it grow?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 20 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001045810-26-000021 ITEM_7: "Revenue $ 215,938 $ 130,497 Up 65%"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [19]) | 489 (derived [20]) | 0, 209 (derived [21]) | [772, 806) (derived [22]) | [151, 165) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001045810-26-000021 ITEM_7: "Total $ 215,938 $ 130,497 $ 85,441 65 %"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 805 | 854 (derived [19]) | 489 (derived [20]) | 0, 365 (derived [21]) | [2131, 2170) (derived [22]) | [483, 500) (derived [23]) | partly (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 805 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-02 (NVDA, FIGURE)

Question: By what percentage did NVIDIA's Data Center revenue grow in fiscal 2026?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 18 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "Data Center revenue for fiscal year 2026 was up 68% from a year ago"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [19]) | 491 (derived [20]) | 0, 207 (derived [21]) | [1090, 1157) (derived [22]) | [261, 277) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-03 (NVDA, FIGURE)

Question: How many shares did NVIDIA repurchase in fiscal 2026 and at what total cost?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 850 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 20 (derived [16]) |
| acceptedPhraseCount | 3 (observed [17]) |

### Accepted phrase 1 of 3

0001045810-26-000021 ITEM_7: "we repurchased 282 million shares of our common stock for $40.4 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 809 | 841 (derived [19]) | 489 (derived [20]) | 0, 352 (derived [21]) | [976, 1047) (derived [22]) | [210, 228) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 809 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 3

0001045810-26-000021 ITEM_5: "In fiscal year 2026, we repurchased 282 million shares of our common stock for $40.4 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 797 | 762 (derived [19]) | 489 (derived [20]) | 0, 273 (derived [21]) | [1284, 1376) (derived [22]) | [272, 296) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 797 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 3 of 3

0001045810-26-000021 ITEM_15: "we repurchased 282 million and 310 million shares of our common stock for $40.4 billion and $34.0 billion"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 850 | 661 (derived [19]) | 489 (derived [20]) | 0, 172 (derived [21]) | [532, 637) (derived [22]) | [116, 143) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 850 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-04 (NVDA, FIGURE)

Question: How many employees did NVIDIA have at the end of fiscal 2026 and how many worked in research and development?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 24 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1: "we had approximately 42,000 employees in 38 countries; 31,000 were engaged in research and development"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 754 | 721 (derived [19]) | 485 (derived [20]) | 0, 236 (derived [21]) | [3057, 3159) (derived [22]) | [547, 567) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 754 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-05 (NVDA, NARRATIVE)

Question: Does NVIDIA manufacture its own chips, or how is its manufacturing organised?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 16 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1: "We utilize a fabless and contracting manufacturing strategy, whereby we employ and partner with key suppliers for all phases of the manufacturing process"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 749 | 700 (derived [19]) | 493 (derived [20]) | 0, 207 (derived [21]) | [2401, 2554) (derived [22]) | [433, 458) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 749 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-06 (NVDA, NARRATIVE)

Question: How do export controls on AI chips affect NVIDIA's supply chain and its ability to serve demand?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 894 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 22 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001045810-26-000021 ITEM_1A: "Export controls have and could in the future disrupt our supply chain and distribution channels, negatively impacting our ability to serve demand"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 779 | 696 (derived [19]) | 487 (derived [20]) | 0, 209 (derived [21]) | [3435, 3580) (derived [22]) | [621, 645) (derived [23]) | not (derived [24]) | 2 (derived [25]) |
| 780 | 744 (derived [19]) | 487 (derived [20]) | 0, 257 (derived [21]) | [49, 194) (derived [22]) | [11, 35) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 779 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 780 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001045810-26-000075 ITEM_1A: "Export controls have and could in the future disrupt our supply chain and distribution channels, negatively impacting our ability to serve demand, including in markets outside China"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 894 | 691 (derived [19]) | 487 (derived [20]) | 0, 204 (derived [21]) | [743, 924) (derived [22]) | [137, 167) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 894 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-07 (NVDA, NARRATIVE)

Question: In which regions are NVIDIA's component manufacturing and final assembly concentrated, and what geopolitical risk does that create?

| Field | Value |
|---|---|
| rank | 8 (observed [12]) |
| matchedChunkId | 770 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 26 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_1A: "China, Hong Kong, Israel, Korea and Taiwan where the manufacture of our product components and final assembly of our products are concentrated"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 770 | 685 (derived [19]) | 483 (derived [20]) | 0, 202 (derived [21]) | [2414, 2556) (derived [22]) | [416, 441) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 770 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-08 (NVDA, FIGURE)

Question: How concentrated was NVIDIA's fiscal 2026 revenue among its largest direct customers?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 852 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 18 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_15: "sales to one direct customer represented 22% of total revenue and sales to another direct customer represented 14% of total revenue"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 852 | 822 (derived [19]) | 491 (derived [20]) | 0, 331 (derived [21]) | [975, 1106) (derived [22]) | [206, 229) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 852 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-09 (NVDA, FIGURE)

Question: What was NVIDIA's Data Center revenue in the second quarter of fiscal 2027?

| Field | Value |
|---|---|
| rank | none (observed [12]) |
| matchedChunkId | none (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 19 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_2: "Data Center revenue was $89.0 billion, up 117% from a year ago and up 18% sequentially"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 879 | 889 (derived [19]) | 490 (derived [20]) | 0, 399 (derived [21]) | [3149, 3235) (derived [22]) | [740, 763) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 879 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-10 (NVDA, NARRATIVE)

Question: Has NVIDIA been able to sell H200 products to customers in China under the new U.S. licenses?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 878 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 24 (derived [16]) |
| acceptedPhraseCount | 2 (observed [17]) |

### Accepted phrase 1 of 2

0001045810-26-000075 ITEM_2: "ship small amounts of H200 products to specific China-based customers, but such sales were restricted by the PRC government"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 878 | 711 (derived [19]) | 485 (derived [20]) | 0, 226 (derived [21]) | [2479, 2602) (derived [22]) | [450, 473) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 878 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Accepted phrase 2 of 2

0001045810-26-000075 ITEM_1A: "Beginning in February 2026, the USG granted licenses that would allow us to ship small amounts of H200 products to specific China-based customers"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 895 | 804 (derived [19]) | 485 (derived [20]) | 0, 319 (derived [21]) | [2497, 2642) (derived [22]) | [521, 550) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 895 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-11 (NVDA, FIGURE)

Question: NVIDIA's Compute & Networking segment revenue was $193,479 million in fiscal 2026. What drove the year-over-year increase?

| Field | Value |
|---|---|
| rank | 5 (observed [12]) |
| matchedChunkId | 805 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 32 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "Revenue from Data Center computing grew 59% driven by demand for our Blackwell computing platform"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 805 | 854 (derived [19]) | 477 (derived [20]) | 0, 377 (derived [21]) | [2538, 2635) (derived [22]) | [596, 612) (derived [23]) | not (derived [24]) | 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 805 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-12 (NVDA, FIGURE)

Question: NVIDIA's Professional Visualization revenue climbed 70% in fiscal 2026. What was behind the jump?

| Field | Value |
|---|---|
| rank | 2 (observed [12]) |
| matchedChunkId | 802 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 23 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000021 ITEM_7: "driven by exceptional demand for Blackwell as well as the launch of our new DGX Spark"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 802 | 698 (derived [19]) | 486 (derived [20]) | 0, 212 (derived [21]) | [1549, 1634) (derived [22]) | [361, 379) (derived [23]) | wholly (derived [24]) | 1, 2 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 802 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-13 (NVDA, FIGURE)

Question: How did the $7.2 billion NVIDIA booked from Edge Computing in the second quarter of fiscal 2027 compare with the same quarter last year and with the quarter before?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 879 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 36 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_2: "Edge Computing revenue was $7.2 billion, up 27% from a year ago and up 13% sequentially"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 879 | 889 (derived [19]) | 473 (derived [20]) | 0, 409, 416 (derived [21]) | [3739, 3826) (derived [22]) | [865, 888) (derived [23]) | not (derived [24]) | 3 (derived [25]) |
| 880 | 980 (derived [19]) | 473 (derived [20]) | 0, 409, 507 (derived [21]) | [412, 499) (derived [22]) | [85, 108) (derived [23]) | wholly (derived [24]) | 1 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 879 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |
| 880 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Question nvda-14 (NVDA, FIGURE)

Question: NVIDIA's revenue reached $96,221 million in the second quarter of fiscal 2027. How much of it came from customers headquartered in Taiwan?

| Field | Value |
|---|---|
| rank | 1 (observed [12]) |
| matchedChunkId | 872 (observed [13]) |
| retrievalStrategy | HYBRID_RRF_RERANKED (observed [14]) |
| error | none (observed [15]) |
| rerankOutcome | unknown: no trace |
| fallbackReason | unknown: no trace |
| scoresNotRecorded | unknown: no trace |
| fusedCount | unknown: no trace |
| rerankInputCount | unknown: no trace |
| queryTokens | 32 (derived [16]) |
| acceptedPhraseCount | 1 (observed [17]) |

### Accepted phrase 1 of 1

0001045810-26-000075 ITEM_1: "Taiwan 26,985 8,902 38,991 16,550"

heldByStoredChunk: true (observed [18])

| Chunk | chunkTokens | windowLength | windowStarts | characterSpan | tokenSpan | head | windowsHoldingWholly |
|---|---|---|---|---|---|---|---|
| 872 | 996 (derived [19]) | 477 (derived [20]) | 0, 413, 519 (derived [21]) | [3289, 3322) (derived [22]) | [820, 836) (derived [23]) | not (derived [24]) | 2, 3 (derived [25]) |

| Chunk | fusedPosition | rerankInput | rerankedPosition | score | windowCount | windowScores | returnedPosition |
|---|---|---|---|---|---|---|---|
| 872 | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace | unknown: no trace |

### Ranked above the best accepted chunk

| Field | Value |
|---|---|
| ranking | unknown: no trace |
| bestAcceptedChunk | unknown: no trace |
| bestAcceptedPosition | unknown: no trace |

rankedAbove: unknown: no trace

## Sources and rules

1. observed: snapshot set_version
2. observed: snapshot properties.set
3. observed: snapshot traces (properties.trace absent)
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
16. derived: tokens of the question text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer
17. observed: bundled set evaluation/retrieval-set-v2.json
18. observed: sec_filing_chunks at report time: chunks of the phrase's accession and section whose text contains the phrase (RetrievalEvaluationService.matches)
19. derived: tokens of the stored chunk text bounded to 20,000 characters, tokenized alone by the loaded cross-encoder tokenizer
20. derived: W = max-length - 3 - the query tokens kept against the whole chunk, longest first (CrossEncoderPairAssembler.windowLength)
21. derived: start token of each scored row under the snapshot's rerankerScoring: head one row at 0; max-window CrossEncoderPairAssembler.windowStarts(chunk tokens, W, overlap, max-windows)
22. derived: occurrence of the normalised phrase in the normalised chunk text (whitespace runs collapsed, trimmed, lower-cased), mapped to UTF-16 offsets of the stored text, end exclusive
23. derived: tokens of the whole-chunk tokenization whose character span overlaps the occurrence, end exclusive
24. derived: wholly: token span end <= W; partly: start < W < end; not: start >= W
25. derived: 1-based rows whose tokens [start, start + min(W, chunk tokens)) contain the whole token span (empty: no row holds it wholly)

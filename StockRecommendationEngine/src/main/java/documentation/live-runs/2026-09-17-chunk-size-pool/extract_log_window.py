#!/usr/bin/env python3
"""Extract one evaluation's log window from an application log (plan plans/2026-09-17-chunk-size.md, Milestone 3, F8).

usage: extract_log_window.py <app.log> <snapshot id> <out file>
Writes the lines from the "Evaluating retrieval:" line that precedes "Retrieval evaluation stored: id=<id>" up to and including that stored
line, unchanged. The evaluation runs its questions one after another (RetrievalEvaluationService.evaluate loops over the set), so the k-th
"Retrieval completed" line inside the window belongs to the k-th question of the set. Nothing is computed here; latency.py reads the window.
"""
import sys
log, snapshot, out = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(log, encoding='utf-8', errors='replace').read().split('\n')
end = next(i for i, l in enumerate(lines) if 'Retrieval evaluation stored: id=' + snapshot + ',' in l)
start = max(i for i in range(end) if 'Evaluating retrieval:' in lines[i])
window = lines[start:end + 1]
open(out, 'w', encoding='utf-8').write('\n'.join(window) + '\n')
print('window lines=%d retrievalCompleted=%d rerankingCompleted=%d queryEmbeddings=%d rerankFailed=%d' % (
    len(window), sum('Retrieval completed:' in l for l in window), sum('Reranking completed:' in l for l in window),
    sum('Query embedding completed:' in l for l in window), sum('Reranking failed' in l for l in window)))

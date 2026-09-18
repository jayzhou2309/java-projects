#!/usr/bin/env python3
"""Per-question latency of one evaluation run from its application-log window (plan plans/2026-09-17-chunk-size.md, Milestone 3, F8).

usage: latency.py <app-log-<id>.txt> <snapshot row export .json> <wall seconds of the evaluate call> <out latency-<id>.json>
The window (extract_log_window.py) runs from "Evaluating retrieval:" to "Retrieval evaluation stored: id=<id>". The evaluation retrieves
its questions one after another in set order (RetrievalEvaluationService.evaluate), so the k-th "Retrieval completed: ticker=..., elapsedMs=..."
line is the k-th question of the snapshot's results, and a "Reranking completed: ... elapsedMs=..." line between two retrievals belongs to
the retrieval it precedes; the ticker of each line is checked against the question's. Values are the elapsedMs the application logged
(retrieval: from the start of the retrieval to its response, embedding and search and reranking included; rerank: the reranker call alone).
Summary rule, stated once here: median = the mean of the two middle values of the sorted list when the count is even (the middle value when odd);
p95 = the value at position ceil(0.95 * count) of the sorted list (nearest rank); max and min as sorted. Wall seconds is the curl time_total of the
POST /api/rag/evaluate call, passed in. Nothing here states a cause.
"""
import json, math, re, sys
from decimal import Decimal

log, snapshot_file, wall, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
snapshot = json.load(open(snapshot_file))
questions = snapshot['results']['questions']
lines = open(log, encoding='utf-8').read().split('\n')
retrieval = re.compile(r'Retrieval completed: ticker=(\S+), results=(\d+), strategy=(\S+), elapsedMs=(\d+)')
rerank = re.compile(r'Reranking completed: ticker=(\S+), results=(\d+), elapsedMs=(\d+)')
failed = re.compile(r'Reranking failed, keeping the retrieval order: ticker=(\S+), reason=(\S+), error=(\S+), elapsedMs=(\d+)')
rows = []
pending_rerank = None
fallbacks = 0
for line in lines:
    m = rerank.search(line)
    if m:
        pending_rerank = (m.group(1), int(m.group(3)))
        continue
    m = failed.search(line)
    if m:
        fallbacks += 1
        pending_rerank = (m.group(1), None)
        continue
    m = retrieval.search(line)
    if m:
        k = len(rows)
        q = questions[k]
        if q['ticker'] != m.group(1):
            raise SystemExit('line %d: retrieval ticker %s but question %s is %s' % (k + 1, m.group(1), q['id'], q['ticker']))
        rerank_ms = None
        if pending_rerank is not None:
            if pending_rerank[0] != m.group(1):
                raise SystemExit('rerank line ticker %s precedes a retrieval of %s' % (pending_rerank[0], m.group(1)))
            rerank_ms = pending_rerank[1]
        rows.append({'id': q['id'], 'ticker': q['ticker'], 'kind': q.get('kind'), 'retrievalMs': int(m.group(4)), 'rerankMs': rerank_ms,
                     'strategy': m.group(3), 'results': int(m.group(2))})
        pending_rerank = None
if len(rows) != len(questions):
    raise SystemExit('found %d retrieval lines for %d questions' % (len(rows), len(questions)))

def summary(values):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    n = len(values)
    median = Decimal(values[n // 2]) if n % 2 else (Decimal(values[n // 2 - 1]) + Decimal(values[n // 2])) / 2
    p95 = values[math.ceil(Decimal('0.95') * n) - 1]
    return {'count': n, 'median': float(median) if median != median.to_integral_value() else int(median), 'p95': p95, 'max': values[-1],
            'min': values[0], 'sum': sum(values)}

result = {
    'snapshotId': snapshot['id'],
    'source': log.split('/')[-1],
    'rule': 'median: mean of the two middle values (even count); p95: sorted value at position ceil(0.95 * count); values are the logged elapsedMs',
    'wallSeconds': float(wall),
    'questions': len(rows),
    'rerankFallbacks': fallbacks,
    'retrievalMs': summary([r['retrievalMs'] for r in rows]),
    'rerankMs': summary([r['rerankMs'] for r in rows]),
    'perQuestion': rows,
}
json.dump(result, open(out, 'w'), indent=1)
open(out, 'a').write('\n')
print('latency', out, 'questions', len(rows), 'retrievalMs', result['retrievalMs'], 'rerankMs', result['rerankMs'], 'fallbacks', fallbacks)

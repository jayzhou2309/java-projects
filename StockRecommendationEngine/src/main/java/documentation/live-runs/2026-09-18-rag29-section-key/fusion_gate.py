#!/usr/bin/env python3
"""The fusion-gate record of Follow_Ups RAG-31 (plan plans/2026-09-17-chunk-size.md, Milestone 4), recomputed from the committed traces of
snapshots 1777 (store A, 4,000 / 500) and 1786 (store B, 1,650 / 250), both pool 40, reranking off, weights 1.0 / 0.5 / 1.0, k 60.
Writes fusion-gate.txt. Run from the repository root: python3 <this dir>/fusion_gate.py

For each snapshot, over the 42 questions' fused lists: a chunk is keyword-only when its trace entry has a keywordRank and neither a
vectorRank nor a figureRank (the legs that returned it; the fusion rule counts a chunk once per leg at its first position). Reported: the
number of keyword-only chunks in the fused lists, the smallest (best) fused position any of them reached, and nvda-04's accepted chunk
(the chunk the evidence report lists under its accepted phrase) with its fused position and leg ranks. The score bound is arithmetic on
the recorded weights and k: a keyword-only chunk at keyword rank r scores keywordWeight / (k + r), at most keywordWeight / (k + 1); the
vector candidate at rank 40 scores vectorWeight / (k + 40). Nothing here states why a chunk sat where it did.
"""
import json, os
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
POOL = os.path.join(HERE, '..', '2026-09-17-chunk-size-pool')
RUNS = [(1777, 'snapshot-1777-a-pool-40-rerank-off.json', 'evidence-1777.json', 'store A (4,000 / 500)'),
        (1786, 'snapshot-1786-b-pool-40-rerank-off.json', 'evidence-1786.json', 'store B (1,650 / 250)')]
out = []
say = out.append
for sid, sf, ef, store in RUNS:
    s = json.load(open(os.path.join(POOL, sf)))
    e = json.load(open(os.path.join(POOL, ef)))
    assert s['id'] == sid and e['snapshotId'] == sid
    p = s['properties']
    k, wv, wk, wf = p['rrfK'], Fraction(str(p['rrfVectorWeight'])), Fraction(str(p['rrfKeywordWeight'])), Fraction(str(p['rrfFigureWeight']))
    traces = {t['id']: t['trace'] for t in s['results']['traces']}
    assert len(traces) == 42 and all(t is not None for t in traces.values())
    keyword_only = []
    for qid, t in traces.items():
        for f in t['fused']:
            if f['keywordRank'] is not None and f['vectorRank'] is None and f['figureRank'] is None:
                keyword_only.append((qid, f['chunkId'], f['fusedPosition'], f['keywordRank']))
    best = min(keyword_only, key=lambda x: x[2]) if keyword_only else None
    positions = sorted(x[2] for x in keyword_only)
    say('Snapshot %d, %s: candidateCount %d, keywordCandidateCount %d, rrfK %d, weights vector %s keyword %s figure %s (recorded properties)' % (
        sid, store, p['candidateCount'], p['keywordCandidateCount'], k, p['rrfVectorWeight'], p['rrfKeywordWeight'], p['rrfFigureWeight']))
    say('  keyword-only chunks in the fused lists of the 42 questions: %d (in %d questions); their fused positions range %s to %s; the best is %s' % (
        len(keyword_only), len({x[0] for x in keyword_only}), positions[0] if positions else 'none', positions[-1] if positions else 'none',
        'none' if best is None else 'position %d (question %s, chunk %d, keyword rank %d)' % (best[2], best[0], best[1], best[3])))
    say('  questions whose fused list has a keyword-only chunk at a position of 40 or better: %s' % (
        ', '.join('%s (position %d)' % (x[0], x[2]) for x in sorted(keyword_only, key=lambda x: x[2]) if x[2] <= 40) or 'none'))
    q = [q for q in e['questions'] if q['id'] == 'nvda-04'][0]
    holding = [c['chunkId'] for ph in q['phrases'] for c in ph['chunks']]
    entries = [f for f in traces['nvda-04']['fused'] if f['chunkId'] in holding]
    removed = [r for r in (traces['nvda-04'].get('removed') or []) if r['chunkId'] in holding]
    for f in entries:
        say('  nvda-04: accepted chunk %d (evidence report) at fused position %d with vector rank %s, keyword rank %s, figure rank %s; fused list length %d; removed by diversification: %s' % (
            f['chunkId'], f['fusedPosition'], f['vectorRank'], f['keywordRank'], f['figureRank'], len(traces['nvda-04']['fused']), [r['chunkId'] for r in removed] or 'none'))
    if not entries:
        say('  nvda-04: accepted chunks %s not in the fused list; removed by diversification: %s' % (holding, [r['chunkId'] for r in removed] or 'none'))
    bound_keyword = wk / (k + 1)
    vector_40 = wv / (k + p['candidateCount'])
    say('  score arithmetic from the recorded weights and k: a keyword-only chunk scores at most %s / (%d + 1) = %.6f; the vector candidate at rank %d scores %s / (%d + %d) = %.6f; %s' % (
        float(wk), k, float(bound_keyword), p['candidateCount'], float(wv), k, p['candidateCount'], float(vector_40),
        'the bound is below the rank-%d vector score' % p['candidateCount'] if bound_keyword < vector_40 else 'the bound is not below the rank-%d vector score' % p['candidateCount']))
text = '\n'.join(out) + '\n'
open(os.path.join(HERE, 'fusion-gate.txt'), 'w').write(text)
print(text, end='')

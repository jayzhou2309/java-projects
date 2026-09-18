#!/usr/bin/env python3
"""Per-question comparison of the fresh default snapshot (1891, the corrected set) with the default reference 1777 (the uncorrected set,
same store content, same settings), with the post-rollback run 1794 (same store, same chunk ids, uncorrected set), with the 2026-09-13
reference 598 (rerank off, the store of that date), and of the traced run at snapshot 297's settings (1892) with 297 and with 613
(plan plans/2026-09-17-chunk-size.md, Milestone 4, contract H3). Writes comparison.txt. Every line is computed from the committed exports;
nothing here states why a rank differs. Run from the repository root: python3 <this dir>/compare_section_key.py

Matched chunks of two snapshots on stores with different chunk ids are compared through the chunk-hash exports (filing, chunk index,
length, content md5), the way the questionEquality check with rankAndMatchedContent compares them; 598, 297, and 613 have no committed
chunk-hash export (their store predates the exports), so those comparisons are of ranks only.
"""
import json, os
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
L = os.path.join(HERE, '..')
def load(p): return json.load(open(os.path.join(L, p)), parse_float=Decimal)
F = 'snapshot-1891-f-defaults-rerank-off.json'
R = 'snapshot-1892-r-traced-297-settings-rerank-candidates-20.json'
files = {
    1891: '2026-09-18-rag29-section-key/' + F,
    1892: '2026-09-18-rag29-section-key/' + R,
    1777: '2026-09-17-chunk-size-pool/snapshot-1777-a-pool-40-rerank-off.json',
    1794: '2026-09-17-chunk-size-pool/snapshot-1794-a2-post-rollback-pool-40-rerank-off.json',
    598: '2026-09-13-evaluation-evidence/measurement/snapshot-598-traced-snapshot-295-reference-rerank-off.json',
    297: '2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json',
    613: '2026-09-13-evaluation-evidence/measurement/snapshot-613-traced-snapshot-297-rerank-candidates-20.json',
}
snaps = {k: load(v) for k, v in files.items()}
for k, s in snaps.items(): assert s['id'] == k, (k, s['id'])
hashes = {1891: load('2026-09-18-rag29-section-key/chunk-hashes.json'), 1892: None, 1777: load('2026-09-17-chunk-size-pool/chunk-hashes-A.json'),
          1794: load('2026-09-17-chunk-size-pool/chunk-hashes-A2.json')}
hashes[1892] = hashes[1891]
hindex = {k: {c['id']: c for c in v} for k, v in hashes.items() if v}
chunks = {c['id']: c for c in load('2026-09-18-rag29-section-key/chunks.json')}
evidence = load('2026-09-18-rag29-section-key/evidence-1891.json')
out = []
say = out.append

def questions(s): return {q['id']: q for q in s['results']['questions']}
def metrics(s): return 'hit@1 %s hit@3 %s hit@5 %s MRR %s' % (s['hit_at_1'], s['hit_at_3'], s['hit_at_5'], s['mrr'])
def identity(k, chunk_id):
    if chunk_id is None: return None
    c = hindex[k][chunk_id]
    return (c['filingId'], c['chunkIndex'], c['chars'], c['contentMd5'])

def compare(a, b, content):
    qa, qb = questions(snaps[a]), questions(snaps[b])
    assert list(qa) == list(qb), 'question order differs'
    diffs = []
    for i in qa:
        ra, rb = qa[i]['rank'], qb[i]['rank']
        ca, cb = qa[i]['matchedChunkId'], qb[i]['matchedChunkId']
        if content == 'md5':
            same = ra == rb and identity(a, ca) == identity(b, cb)
            detail = 'rank %s chunk %s %s against rank %s chunk %s %s' % (ra, ca, identity(a, ca), rb, cb, identity(b, cb))
        elif content == 'id':
            same = ra == rb and ca == cb
            detail = 'rank %s chunk %s against rank %s chunk %s' % (ra, ca, rb, cb)
        else:
            same = ra == rb
            detail = 'rank %s against rank %s' % (ra, rb)
        if not same: diffs.append('%s (%s)' % (i, detail))
    what = {'md5': 'rank and matched chunk content (filing, chunk index, length, md5 from the chunk-hash exports)', 'id': 'rank and matched chunk id', 'rank': 'rank only'}[content]
    say('%d against %d, %s: %d of %d questions equal; differing: %s' % (a, b, what, len(qa) - len(diffs), len(qa), ', '.join(diffs) or 'none'))
    say('  metrics %d: %s; metrics %d: %s' % (a, metrics(snaps[a]), b, metrics(snaps[b])))
    pa, pb = snaps[a]['properties'], snaps[b]['properties']
    keys = sorted(set(pa) | set(pb))
    pdiff = ['%s: %s against %s' % (k, pa.get(k, '<absent>'), pb.get(k, '<absent>')) for k in keys if pa.get(k, '<absent>') != pb.get(k, '<absent>')]
    say('  recorded properties (%d keys on either side): %s' % (len(keys), ('differ in ' + '; '.join(pdiff)) if pdiff else 'equal on every key'))
    for c in ('set_version', 'question_count', 'window_size'):
        if snaps[a][c] != snaps[b][c]: say('  column %s differs: %s against %s' % (c, snaps[a][c], snaps[b][c]))

say('Fresh default snapshot 1891 (corrected set: aapl-08 sectionKey ITEM_2_02) against the references')
compare(1891, 1777, 'md5')
say('  note: properties.set is the same classpath path in both (evaluation/retrieval-set-v2.json) while the file content differs (aapl-08 sectionKey), which no recorded property shows; the set change is read from the two evidence reports: '
    + 'evidence-1777.json questions[id=aapl-08].phrases[0].sectionKey %s; evidence-1891.json %s' % (
        [q for q in load('2026-09-17-chunk-size-pool/evidence-1777.json')['questions'] if q['id'] == 'aapl-08'][0]['phrases'][0]['sectionKey'],
        [q for q in evidence['questions'] if q['id'] == 'aapl-08'][0]['phrases'][0]['sectionKey']))
compare(1891, 1794, 'id')
compare(1891, 598, 'rank')
say('')
say('Traced run at snapshot 297 settings, 1892, against the 2026-09-13 references')
compare(1892, 297, 'rank')
compare(1892, 613, 'rank')
say('')
q = questions(snaps[1891])['aapl-08']
c = chunks[q['matchedChunkId']]
say('aapl-08 in 1891: rank %s, matched chunk %s (filingId %s, sectionKey %s, sectionChunkIndex %s, chars %s, head "%s")' % (
    q['rank'], q['matchedChunkId'], c['filingId'], c['sectionKey'], c['sectionChunkIndex'], c['chars'], c['head']))
e = [x for x in evidence['questions'] if x['id'] == 'aapl-08'][0]
for p in e['phrases']:
    say('aapl-08 accepted phrase in evidence-1891.json: accession %s section %s "%s": heldByStoredChunk %s (%s), holding chunks %s' % (
        p['accessionNo'], p['sectionKey'], p['phrase'], p['heldByStoredChunk']['value'], p['heldByStoredChunk']['basis'], [ch['chunkId'] for ch in p['chunks']]))
q2 = questions(snaps[1892])['aapl-08']
say('aapl-08 in 1892: rank %s, matched chunk %s' % (q2['rank'], q2['matchedChunkId']))
phrases = sum(len(x['phrases']) for x in evidence['questions'])
held = sum(1 for x in evidence['questions'] for p in x['phrases'] if p['heldByStoredChunk']['value'] is True)
say('evidence-1891.json: %d accepted phrases, %d held by a stored chunk (heldByStoredChunk observed true)' % (phrases, held))
for k in (1891, 1892):
    s = snaps[k]
    hist = {}
    for x in s['results']['questions']:
        hist[x['rank'] if x['rank'] is not None else 'notInWindow'] = hist.get(x['rank'] if x['rank'] is not None else 'notInWindow', 0) + 1
    say('rank histogram %d: %s' % (k, ', '.join('%s: %d' % (r, hist[r]) for r in sorted(hist, key=lambda r: (r == 'notInWindow', r if r != 'notInWindow' else 0)))))
text = '\n'.join(out) + '\n'
open(os.path.join(HERE, 'comparison.txt'), 'w').write(text)
print(text, end='')

#!/usr/bin/env python3
"""R1 direct check against the store (plan plans/2026-09-17-chunk-size.md, Milestone 3): for every accepted phrase of the bundled set,
the stored chunks of the phrase's accession and section whose text contains the phrase under RetrievalEvaluationService.matches
(whitespace runs collapsed, trimmed, lower-cased). Reads the set file given and the shared database through docker exec (read only);
prints one line per phrase and a TOTALS line. The evidence report of a run (heldByStoredChunk) is the same rule applied by the application.

usage: held_phrases.py <set json> <point label>   (POSTGRES_USER and POSTGRES_DB from the environment)
"""
import json, os, re, subprocess, sys
set_file, point = sys.argv[1], sys.argv[2]
questions = json.load(open(set_file))['questions']
sql = ("select json_agg(json_build_object('id', c.id, 'accession', f.accession_no, 'section', c.section_key, 'version', f.processing_version, 'content', c.content))"
       " from sec_filing_chunks c join sec_filings f on f.id = c.filing_id")
raw = subprocess.run(['docker', 'exec', '-i', 'trading-postgres', 'psql', '-U', os.environ['POSTGRES_USER'], '-d', os.environ['POSTGRES_DB'], '-tA', '-c', sql],
                     capture_output=True, text=True, check=True).stdout
chunks = json.loads(raw)
def normalise(text):
    return re.sub(r'\s+', ' ', text).strip().lower()
by_key = {}
for c in chunks:
    by_key.setdefault((c['accession'], c['section']), []).append(c)
total = 0; held = 0; unheld = []
for q in questions:
    for i, p in enumerate(q['expected']):
        total += 1
        phrase = normalise(p['phrase'])
        holders = [c['id'] for c in by_key.get((p['accessionNo'], p['sectionKey']), []) if phrase in normalise(c['content'])]
        holders.sort()
        if holders: held += 1
        else: unheld.append('%s/%d' % (q['id'], i))
        print('HELD_PHRASE point=%s question=%s phrase=%d accession=%s section=%s heldByStoredChunk=%s chunks=%s' % (
            point, q['id'], i, p['accessionNo'], p['sectionKey'], str(bool(holders)).lower(), holders))
versions = sorted({c['version'] for c in chunks})
print('HELD_PHRASE TOTALS point=%s storedChunks=%d storeVersions=%s phrases=%d held=%d notHeld=%d%s' % (point, len(chunks), versions, total, held, len(unheld), unheld))

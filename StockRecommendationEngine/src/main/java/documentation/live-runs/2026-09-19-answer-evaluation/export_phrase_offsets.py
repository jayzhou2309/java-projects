#!/usr/bin/env python3
"""Where each accepted phrase sits in the chunks of the answer-evaluation passes (read-only store export).

Reads the committed answer-evaluation exports given on the command line (get-<id>.json), the evaluation set
(src/main/resources/evaluation/retrieval-set-v2.json) and the retrieval reference snapshot 1891, then reads the content of
the chunks they name from the store with one read-only SELECT (docker exec trading-postgres psql; POSTGRES_USER and
POSTGRES_DB from the environment) and writes phrase-offsets.json beside this file.

Chunks read: every `expectedChunkIds` chunk of every question of the exports (the chunks a run retrieved that hold an
accepted phrase), and the matched chunk of each question in snapshot 1891.

For a chunk and an accepted phrase of its question with the chunk's accession number and section key, the phrase is
located in the raw content as the application's rule does (RetrievalEvaluationService.normalise: ASCII whitespace runs
collapsed, lower case): the phrase's words joined by runs of ASCII whitespace, case-insensitive. Offsets are 0-based
character offsets in the stored content, end exclusive. `insideCut` is true when some occurrence ends at or before the
cut (the export's modelPassageChars; RecommendationTools.forModel keeps content[0:cut]). This file is the only output;
answer_tables.py reads it and never the database.

Usage (from the repository root, .env exported):
  python3 -B src/main/java/documentation/live-runs/2026-09-19-answer-evaluation/export_phrase_offsets.py get-37.json get-38.json
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[5]
SET = ROOT / "src/main/resources/evaluation/retrieval-set-v2.json"
REFERENCE = HERE.parent / "2026-09-18-rag29-section-key" / "snapshot-1891-f-defaults-rerank-off.json"
WS = "[ \\t\\n\\x0b\\f\\r]"


def phrase_pattern(phrase):
    words = [w for w in re.split(WS + "+", phrase.strip()) if w]
    return re.compile((WS + "+").join(re.escape(w) for w in words), re.IGNORECASE)


def main(argv):
    exports = [json.loads((HERE / name).read_text(encoding="utf-8")) for name in argv]
    cuts = {e["properties"]["modelPassageChars"] for e in exports}
    if len(cuts) != 1:
        raise SystemExit("the exports record different modelPassageChars: %s" % sorted(cuts))
    cut = cuts.pop()
    questions = {q["id"]: q for q in json.loads(SET.read_text(encoding="utf-8"))["questions"]}
    reference = json.loads(REFERENCE.read_text(encoding="utf-8"))

    wanted = {}  # chunk id -> set of question ids
    for export in exports:
        for result in export["results"]:
            for chunk_id in result.get("expectedChunkIds") or []:
                wanted.setdefault(chunk_id, set()).add(result["id"])
    for row in reference["results"]["questions"]:
        if row.get("matchedChunkId") is not None:
            wanted.setdefault(row["matchedChunkId"], set()).add(row["id"])

    ids = ",".join(str(i) for i in sorted(wanted))
    sql = ("select json_agg(json_build_object('id', c.id, 'accessionNo', f.accession_no, 'sectionKey', c.section_key, "
           "'chunkIndex', c.chunk_index, 'content', c.content) order by c.id) "
           "from sec_filing_chunks c join sec_filings f on f.id = c.filing_id where c.id in (" + ids + ")")
    out = subprocess.run(["docker", "exec", "-i", "trading-postgres", "psql", "-U", os.environ["POSTGRES_USER"], "-d",
                          os.environ["POSTGRES_DB"], "-tA", "-c", sql], check=True, capture_output=True, text=True).stdout
    rows = {row["id"]: row for row in json.loads(out)}

    chunks = []
    for chunk_id in sorted(wanted):
        row = rows.get(chunk_id)
        if row is None:
            chunks.append({"chunkId": chunk_id, "questions": sorted(wanted[chunk_id]), "inStore": False})
            continue
        content = row["content"]
        entry = {"chunkId": chunk_id, "inStore": True, "accessionNo": row["accessionNo"], "sectionKey": row["sectionKey"],
                 "chunkIndex": row["chunkIndex"], "contentChars": len(content),
                 "contentMd5": hashlib.md5(content.encode("utf-8")).hexdigest(), "phrases": []}
        for question_id in sorted(wanted[chunk_id]):
            for expected in questions[question_id]["expected"]:
                if expected["accessionNo"] != row["accessionNo"] or expected["sectionKey"] != row["sectionKey"]:
                    continue
                spans = [[m.start(), m.end()] for m in phrase_pattern(expected["phrase"]).finditer(content)]
                if not spans:
                    continue
                entry["phrases"].append({"question": question_id, "phrase": expected["phrase"], "occurrences": spans,
                                         "firstStart": spans[0][0], "firstEnd": spans[0][1],
                                         "insideCut": any(end <= cut for _, end in spans)})
        chunks.append(entry)

    result = {"description": "Character offsets of accepted phrases in stored chunk content, written by export_phrase_offsets.py "
                             "from a read-only SELECT on sec_filing_chunks; offsets 0-based, end exclusive; insideCut: some "
                             "occurrence ends at or before modelPassageChars.",
              "exports": [e["id"] for e in exports], "retrievalReference": reference["id"], "modelPassageChars": cut,
              "chunks": chunks}
    (HERE / "phrase-offsets.json").write_text(json.dumps(result, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print("chunks=%d missing=%d" % (len(chunks), sum(1 for c in chunks if not c["inStore"])))


if __name__ == "__main__":
    main(sys.argv[1:])

#!/usr/bin/env python3
"""Candidate recall from committed traced evidence (plan documentation/plans/2026-09-14-retrieval-recall.md, Milestone 1; Follow_Ups RAG-18).

Reads only the twelve committed JSON files named in RUNS (paths relative to this script's directory): the evidence reports and snapshots of the
traced runs 598, 599, 613, 627, 641 and 694. Writes two files next to this script, replacing them:
  candidate-recall.txt  the recall table, every question's best fused position, the classes of the questions outside hit@5, and the R1, R2
                        and R4 checks of the plan;
  claims.json           the claims (ids from C-501) whose generated block stands in RAG.md, Retrieval Evaluation, Candidate recall.
No database, no application, no model. Numbers are parsed as written (Decimal); the output is deterministic, so a re-run reproduces both files
byte for byte. The script states where accepted chunks sit in the fused list; it states no cause.

Definitions (plan, Milestone 1, fixed before this script was written):
  best fused position  the smallest fusedPosition over every chunk the evidence report lists under any accepted phrase of the question; null when
                       none of those chunks is in the fused list (or no stored chunk holds a phrase). Every fusedPosition read must be observed;
                       an unknown one stops the script naming the question and chunk.
  candidate recall@K   the share of the report's questions whose best fused position is at most K, for K in 10, 20, 30, 40, and the whole
                       fused list (best fused position not null); rounded half up to six decimal places (the claims check computes the same).
  outside hit@5        the snapshot's stored rank is null or greater than 5.
  class                each question outside hit@5 is in exactly one of: fused 1-5 (in the first five of the fused list but not ranked 1 to 5,
                       possible only when reranking reorders the list; this class is not in the plan's list and is reported as a deviation),
                       fused 6-20, fused 21-40, fused >40, not fused.

Run: python3 candidate_recall.py   (then: git diff --exit-code candidate-recall.txt claims.json)
"""
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
M = "../2026-09-13-evaluation-evidence/measurement/"
E1 = "../2026-09-13-rag15-recall/"
RUNS = [
    ("598", M + "evidence-598.json", M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json"),
    ("599", M + "evidence-599.json", M + "snapshot-599-traced-snapshot-296-rerank-candidates-10.json"),
    ("613", M + "evidence-613.json", M + "snapshot-613-traced-snapshot-297-rerank-candidates-20.json"),
    ("627", M + "evidence-627.json", M + "snapshot-627-traced-snapshot-298-rerank-candidates-40-timeout-4000.json"),
    ("641", M + "evidence-641.json", M + "snapshot-641-traced-snapshot-299-rerank-candidates-20-overlap-224.json"),
    ("694", E1 + "evidence-694.json", E1 + "snapshot-694-candidate-count-200-rerank-off.json"),
]
KS = [10, 20, 30, 40]
CLASSES = ["fused 1-5", "fused 6-20", "fused 21-40", "fused >40", "not fused"]
# R4: the positions Follow_Ups RAG-15 records for nvda-02 (chunk 802) and nvda-04 (chunk 754).
RAG15 = {"598": {"nvda-02": (41, 802), "nvda-04": (54, 754)}, "694": {"nvda-02": (25, 802), "nvda-04": (37, 754)}}
PROPERTIES = ["candidateCount", "keywordCandidateCount", "rerank", "rerankCandidates", "reranker", "rerankerScoring", "rerankedQuestions",
              "rerankFallbackQuestions"]
FIRST_ID = 501


class Stop(Exception):
    pass


def load(path):
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def fmt(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def share(n, total):
    return (Decimal(n) / Decimal(total)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)


def best(question):
    """(position, chunk id, holding chunk count) under the definition above."""
    position, chunk, holding = None, None, []
    for phrase in question["phrases"]:
        for c in phrase["chunks"]:
            if c["chunkId"] not in holding:
                holding.append(c["chunkId"])
            fp = c.get("fusedPosition")
            if not isinstance(fp, dict) or fp.get("basis") != "observed":
                raise Stop(f"question {question['id']} chunk {c['chunkId']}: fusedPosition is not observed: {fp}")
            value = fp["value"]
            if value is None:
                continue
            if position is None or value < position:
                position, chunk = value, c["chunkId"]
    return position, chunk, len(holding)


def klass(position):
    if position is None:
        return "not fused"
    if position <= 5:
        return "fused 1-5"
    if position <= 20:
        return "fused 6-20"
    if position <= 40:
        return "fused 21-40"
    return "fused >40"


def main():
    out = []
    p = out.append
    claims = []
    failures = []

    def claim(check):
        claims.append({"id": f"C-{FIRST_ID + len(claims)}", "basis": "derived", "check": check})

    p("Candidate recall from committed traced evidence (plan documentation/plans/2026-09-14-retrieval-recall.md, Milestone 1)")
    p("Generated by candidate_recall.py from committed files only (paths relative to the script's directory):")
    for run, e, s in RUNS:
        p(f"  {run}: {e} ({os.path.getsize(os.path.join(HERE, e))} bytes); {s} ({os.path.getsize(os.path.join(HERE, s))} bytes)")
    p("Labels: [observed] a value read as stored (file and field named); [derived] computed by this script (computation named).")
    p("Definitions: see the script's docstring. It states where accepted chunks sit in the fused list; it states no cause.")
    p("")

    data = []
    for run, e, s in RUNS:
        E, S = load(e), load(s)
        if E["snapshotId"] != S["id"] or str(S["id"]) != run:
            raise Stop(f"run {run}: evidence snapshotId {E['snapshotId']} and snapshot id {S['id']} do not both equal {run}")
        data.append((run, e, s, E, S))

    p("1. Runs [observed: snapshot id, set_version, question_count, window_size, retrieval_strategy, properties.<key>]")
    for run, e, s, E, S in data:
        props = ", ".join(f"{k} {fmt(S['properties'].get(k))}" for k in PROPERTIES)
        p(f"  {run}: set {S['set_version']}, questions {S['question_count']}, window {S['window_size']}, strategy {S['retrieval_strategy']}, {props}")
    p("")

    rows = {}
    for run, e, s, E, S in data:
        ranks = {q["id"]: q["rank"] for q in S["results"]["questions"]}
        questions = []
        for q in E["questions"]:
            position, chunk, holding = best(q)
            if q["id"] not in ranks:
                raise Stop(f"run {run}: question {q['id']} is not in the snapshot")
            if q["rank"]["value"] != ranks[q["id"]]:
                failures.append(f"run {run} {q['id']}: evidence rank {fmt(q['rank']['value'])} differs from snapshot rank {fmt(ranks[q['id']])}")
            outcomes = q["rerankOutcome"]["value"]
            questions.append({"id": q["id"], "rank": ranks[q["id"]], "best": position, "chunk": chunk, "holding": holding,
                              "fusedCount": q["fusedCount"]["value"], "outcome": outcomes})
        if len(questions) != len(ranks):
            raise Stop(f"run {run}: evidence lists {len(questions)} questions, snapshot {len(ranks)}")
        rows[run] = questions

    p("2. Candidate recall [derived: questions with best fused position <= K (or not null for 'whole') / questions, half up to 6 places]")
    header = "  run  questions  " + "  ".join(f"recall@{k:<2} (n)" for k in KS) + "  whole list (n)"
    p(header)
    for run, e, s, E, S in data:
        qs = rows[run]
        total = len(qs)
        cells = []
        for k in KS:
            n = sum(1 for q in qs if q["best"] is not None and q["best"] <= k)
            cells.append(f"{share(n, total)} ({n:>2})")
            claim({"type": "candidateRecall", "report": e, "k": k, "expected": share(n, total)})
        n = sum(1 for q in qs if q["best"] is not None)
        cells.append(f"{share(n, total)} ({n:>2})")
        claim({"type": "candidateRecall", "report": e, "k": "all", "expected": share(n, total)})
        p(f"  {run}  {total:>9}  " + "  ".join(cells))
        for q in qs:
            if q["rank"] is None or q["rank"] > 5:
                claim({"type": "bestFusedPosition", "report": e, "question": q["id"], "expected": q["best"]})
    p("")

    p("3. Questions outside hit@5 [observed: snapshot results rank; derived: best fused position and class as defined]")
    for run, e, s, E, S in data:
        qs = rows[run]
        outside = [q for q in qs if q["rank"] is None or q["rank"] > 5]
        p(f"  {run}:")
        for q in outside:
            where = f"{q['best']} (chunk {q['chunk']})" if q["best"] is not None else f"null ({q['holding']} holding chunks)"
            p(f"    {q['id']:<8} rank {fmt(q['rank']):>4}  best fused position {where:<22} fused list length {q['fusedCount']:>3}  "
              f"rerank outcome {q['outcome']:<8}  class {klass(q['best'])}")
        counts = {c: sum(1 for q in outside if klass(q["best"]) == c) for c in CLASSES}
        p("    class counts: " + ", ".join(f"{c} {counts[c]}" for c in CLASSES) + f"; sum {sum(counts.values())}")
        # R1: the class counts sum to the questions outside hit@5 recorded in the snapshot, by its ranks and by its stored hit_at_5.
        n = S["question_count"]
        from_metric = n - int((Decimal(S["hit_at_5"]) * n).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        ok = sum(counts.values()) == len(outside) == from_metric
        p(f"    R1: outside hit@5 by snapshot ranks {len(outside)}; by stored hit_at_5 {S['hit_at_5']} x {n} {from_metric}; class sum "
          f"{sum(counts.values())} -> {'equal' if ok else 'NOT EQUAL'}")
        if not ok:
            failures.append(f"R1 run {run}: class sum {sum(counts.values())}, outside by ranks {len(outside)}, by hit_at_5 {from_metric}")
    p("")

    p("4. R2: runs whose every question records rerank outcome OFF [observed: evidence rerankOutcome], returned window = first <window> of the fused list")
    for run, e, s, E, S in data:
        qs = rows[run]
        if any(q["outcome"] != "OFF" for q in qs):
            p(f"  {run}: not applicable (a question records rerank outcome other than OFF)")
            continue
        window = S["window_size"]
        recall = share(sum(1 for q in qs if q["best"] is not None and q["best"] <= window), len(qs))
        ranked = share(sum(1 for q in qs if q["rank"] is not None), len(qs))
        contradictions = [f"{q['id']} (rank {fmt(q['rank'])}, best fused position {fmt(q['best'])})" for q in qs
                          if (q["rank"] is not None) != (q["best"] is not None and q["best"] <= window)
                          or (q["rank"] is not None and q["rank"] != q["best"])]
        p(f"  {run}: recall@{window} {recall}; share of questions with a non-null snapshot rank {ranked} -> {'equal' if recall == ranked else 'NOT EQUAL'}; "
          f"contradictions (non-null rank without best fused position <= {window}, or rank not equal to best fused position): "
          + (", ".join(contradictions) if contradictions else "none found"))
        if recall != ranked or contradictions:
            failures.append(f"R2 run {run}: recall {recall}, ranked share {ranked}, contradictions {contradictions}")
    p("")

    p("5. R4: nvda-02 and nvda-04 against the positions Follow_Ups RAG-15 records")
    for run in sorted(RAG15):
        for qid, (position, chunk) in sorted(RAG15[run].items()):
            q = next(x for x in rows[run] if x["id"] == qid)
            same = q["best"] == position and q["chunk"] == chunk
            p(f"  {run} {qid}: RAG-15 fused position {position} (chunk {chunk}); best fused position {fmt(q['best'])} (chunk {fmt(q['chunk'])}) -> "
              + ("same" if same else "DIFFERENT"))
            if not same:
                failures.append(f"R4 run {run} {qid}: best {q['best']} chunk {q['chunk']}, RAG-15 {position} chunk {chunk}")
    p("")

    p("6. Every question's best fused position [derived], as position/rank [observed: snapshot rank]; '-' null")
    p("  question  " + "  ".join(f"{run:>9}" for run, *_ in data))
    for i, q in enumerate(rows[data[0][0]]):
        cells = []
        for run, *_ in data:
            x = rows[run][i]
            if x["id"] != q["id"]:
                raise Stop(f"run {run}: question order differs at {i}")
            cells.append(f"{fmt(x['best']) if x['best'] is not None else '-'}/{fmt(x['rank']) if x['rank'] is not None else '-'}".rjust(9))
        p(f"  {q['id']:<8}  " + "  ".join(cells))
    p("")
    p("Checks: " + ("R1, R2 and R4 hold as computed above." if not failures else "FAILED: " + "; ".join(failures)))

    with open(os.path.join(HERE, "candidate-recall.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    def value(v):
        if isinstance(v, Decimal):
            return str(v)
        return json.dumps(v, ensure_ascii=False)

    lines = ["{",
             '  "description": "Candidate recall of the traced runs 598, 599, 613, 627, 641 and 694 (plan plans/2026-09-14-retrieval-recall.md, Milestone 1),'
             ' written by candidate_recall.py: per run, candidate recall at fused positions 10, 20, 30, 40 and the whole fused list, then the best fused'
             ' position of every question outside hit@5 in that run.",',
             '  "claims": [']
    for i, c in enumerate(claims):
        check = ", ".join(f'"{k}": {value(v)}' for k, v in c["check"].items())
        lines.append(f'    {{"id": "{c["id"]}", "basis": "{c["basis"]}", "check": {{{check}}}}}' + ("," if i < len(claims) - 1 else ""))
    lines += ["  ]", "}"]
    with open(os.path.join(HERE, "claims.json"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Stop as stop:
        print(f"stopped: {stop}", file=sys.stderr)
        sys.exit(2)

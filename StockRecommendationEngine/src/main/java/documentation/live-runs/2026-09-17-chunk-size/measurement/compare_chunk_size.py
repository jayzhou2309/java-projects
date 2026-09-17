#!/usr/bin/env python3
"""Chunk-size measurement, Milestone 3 of plan documentation/plans/2026-09-17-chunk-size.md (Follow_Ups RAG-15, RAG-26, RAG-27).

Reads only committed files in this directory (and the two reference snapshots under ../2026-09-13-evaluation-evidence/measurement/): the
row_to_json snapshot exports, the evidence reports, the chunk exports (chunks-S0/S1/S2.json, written by chunks.sql at each store point), the
storage snapshots (storage-S0/S1/S2.json, written by storage.sql), and the latency extracts (latency-<id>.json, written by latency.py from the
committed application-log windows). Writes, replacing them:
  property-diff.txt   every recorded property of each pair (F3), and the rerank-timeout-ms of each run with its source (snapshots do not record it);
  rank-table.txt      per question the rank and matched chunk in every run (F7), and for every question not at rank 1 the chunks returned above
                      its matched chunk (id, section, first characters) from the trace joined to the chunk export of the store the run read;
  rank-histogram.txt  how many questions at each rank and outside the window, per run (F7);
  comparison.txt      settings, R1 by the application's own rule, the rule rows for the record, top-5 membership changes, nvda-02 and nvda-04,
                      the post-rollback comparison with B0 (F6), latency (F8) and storage (F9) tables;
  experiment-chunk-size-1611-1613.json, experiment-chunk-size-1612-1614.json   the experiment files (factor chunkMaxChars, coupled
                      chunkOverlapChars and storeVersions, which the rebuild changes together with the size);
  claims.json         the claims (ids from C-1201) whose generated blocks stand in RAG.md, Retrieval Evaluation, Chunk size measurement.
No database, no application, no model. Numbers are parsed as written (Decimal). Output is deterministic: a re-run reproduces the files byte for
byte. The script states ranks, positions, counts and sizes; it states no cause.

Runs: B0 1611 and B1 1612 (stored size 4000 / 500, reranking off and on, before the rebuild; they reproduce 598 and 613, reproduction-1611.txt and
reproduction-1612.txt); D0 1613 and D1 1614 (the rebuilt store at 1000 / 125, reranking off and on; diagnostic records, not selection candidates:
R1 failed, so under the plan N0 did not run for the rule); P0 1615 (after the rollback rebuild at 4000 / 500, B0's settings).
"""
import json
import os
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
M = "../../2026-09-13-evaluation-evidence/measurement/"
RUNS = {
    "598": (M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json", M + "evidence-598.json", None, None),
    "613": (M + "snapshot-613-traced-snapshot-297-rerank-candidates-20.json", M + "evidence-613.json", None, None),
    "1611": ("snapshot-1611-b0-stored-size-4000-500-rerank-off.json", "evidence-1611.json", "chunks-S0.json", "latency-1611.json"),
    "1612": ("snapshot-1612-b1-stored-size-4000-500-rerank-candidates-20.json", "evidence-1612.json", "chunks-S0.json", "latency-1612.json"),
    "1613": ("snapshot-1613-d0-diagnostic-rebuilt-1000-125-rerank-off.json", "evidence-1613.json", "chunks-S1.json", "latency-1613.json"),
    "1614": ("snapshot-1614-d1-diagnostic-rebuilt-1000-125-rerank-candidates-20.json", "evidence-1614.json", "chunks-S1.json", "latency-1614.json"),
    "1615": ("snapshot-1615-p0-post-rollback-4000-500-rerank-off.json", "evidence-1615.json", "chunks-S2.json", "latency-1615.json"),
}
NAMES = {"1611": "B0", "1612": "B1", "1613": "D0", "1614": "D1", "1615": "P0"}
LABELS = {
    "1611": "the baseline with reranking off", "1612": "the baseline with reranking on",
    "1613": "the diagnostic run on the rebuilt store with reranking off", "1614": "the diagnostic run on the rebuilt store with reranking on",
    "1615": "the post-rollback run",
}
CHUNK_LABELS = {"chunks-S0.json": "the chunk export before the rebuild", "chunks-S1.json": "the chunk export of the rebuilt store",
                "chunks-S2.json": "the chunk export after the rollback"}
STORAGE_LABELS = {"storage-S0.json": "storage before the rebuild", "storage-S1.json": "storage after the rebuild", "storage-S2.json": "storage after the rollback"}
LATENCY_LABELS = {"latency-1611.json": "the latency of the baseline with reranking off", "latency-1612.json": "the latency of the baseline with reranking on",
                  "latency-1613.json": "the latency of the diagnostic run with reranking off", "latency-1614.json": "the latency of the diagnostic run with reranking on",
                  "latency-1615.json": "the latency of the post-rollback run"}
AGAINST_S1 = "evidence-1611-against-S1.json"
ORDER = ["1611", "1613", "1612", "1614", "1615"]
# rerank-timeout-ms per run, not recorded in snapshots: application.yaml default 2000 at ec560cc (no RAG_RETRIEVAL_RERANK_TIMEOUT_MS override in
# run.log for 1611 to 1615); 598 and 613 as ../2026-09-14-recall-one-factor/compare_one_factor.py sources them.
TIMEOUT_MS = {"598": 2000, "613": 2000, "1611": 2000, "1612": 2000, "1613": 2000, "1614": 2000, "1615": 2000}
TIMEOUT_SOURCE = {"598": "application.yaml default at 432f456, no override recorded", "613": "2026-09-13 measurement run.log (timeout default)",
                  "1611": "run.log", "1612": "run.log", "1613": "run.log", "1614": "run.log", "1615": "run.log"}
COLUMNS = ["set_version", "question_count", "window_size", "retrieval_strategy"]
RERANK_OUTCOMES = ["rerankedQuestions", "retrieval_strategy"]
# (reference, candidate, what the plan varies, coupled properties the rebuild changes with the size, outcome keys that differ with reranking)
PAIRS = [("598", "1611", [], [], []), ("613", "1612", [], [], []), ("1611", "1613", ["chunkMaxChars"], ["chunkOverlapChars", "storeVersions"], []),
         ("1612", "1614", ["chunkMaxChars"], ["chunkOverlapChars", "storeVersions"], []), ("1611", "1615", [], [], [])]
FOCUS = ["nvda-02", "nvda-04"]
FIRST_ID = 1201
STORAGE_FIELDS = ["chunks", "contentChars", "meanChunkChars", "maxChunkChars", "tokenCountSum", "totalRelationBytes", "heapBytes", "toastBytes", "indexesBytes",
                  "indexes[name=idx_sec_filing_chunks_content_tsv].bytes", "storeVersions"]
FILINGS = [3, 4, 5, 6, 7, 8, 109, 110, 161, 162, 288, 324, 325]


def load(relative):
    with open(os.path.join(HERE, relative), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def questions(snapshot):
    return snapshot["results"]["questions"]


def traces(snapshot):
    return {t["id"]: t["trace"] for t in (snapshot["results"].get("traces") or [])}


def fmt(value):
    if isinstance(value, bool) or value is None:
        return "null" if value is None else str(value).lower()
    if isinstance(value, list):
        return "[" + ", ".join(fmt(v) for v in value) + "]"
    return str(value)


def path_value(node, path):
    """ReportPath semantics: segments by dots, [n] or [key=value] selectors."""
    for segment in path.split("."):
        name, selector = (segment.split("[", 1) + [None])[:2]
        if name:
            node = node[name]
        if selector is not None:
            selector = selector[:-1]
            if "=" in selector:
                key, value = selector.split("=", 1)
                node = next(e for e in node if str(e.get(key)) == value)
            else:
                node = node[int(selector)]
    return node


snapshots = {k: load(v[0]) for k, v in RUNS.items()}
evidence = {k: load(v[1]) for k, v in RUNS.items()}
exports = {name: {c["id"]: c for c in load(name)} for name in ("chunks-S0.json", "chunks-S1.json", "chunks-S2.json")}
storage = {name: load(name) for name in STORAGE_LABELS}
latency = {k: load(v[3]) for k, v in RUNS.items() if v[3]}
against_s1 = load(AGAINST_S1)
held_texts = {"S1": open(os.path.join(HERE, "held-phrases-S1.txt")).read(), "S2": open(os.path.join(HERE, "held-phrases-S2.txt")).read()}

out = {}


def w(name, text):
    out[name] = text


# ---------------------------------------------------------------- property diff (F3)
def recorded(snapshot):
    rec = {c: snapshot.get(c) for c in COLUMNS}
    for k, v in snapshot["properties"].items():
        rec["properties." + k] = v
    return rec


lines = ["Recorded properties per pair (plan Milestone 3, F3), generated by compare_chunk_size.py from the committed snapshots",
         "Compared: the snapshot columns set_version, question_count, window_size, retrieval_strategy and every key of properties (trace included).",
         "[observed: the values as stored; derived: equality]. rerank-timeout-ms and max-length are not recorded in any snapshot; the rerank-timeout-ms of",
         "each run is printed with its source. Outcome counts and columns (rerankedQuestions, rerankFallbackQuestions, retrieval_strategy) are named apart",
         "from settings. The three properties recorded since 2026-09-17 (chunkMaxChars, chunkOverlapChars, storeVersions) are absent from 598 and 613,",
         "which predate them; a pair against those references names them as absent, and TraceReproductionFilesTests exempted them (reproduction-<id>.txt).", ""]
pair_summary = {}
for ref, cand, varies, coupled, outcomes in PAIRS:
    a, b = recorded(snapshots[ref]), recorded(snapshots[cand])
    keys = sorted(set(a) | set(b))
    differing, absent, others, outs, equal = [], [], [], [], []
    for k in keys:
        if k not in a:
            absent.append("%s: absent from %s, %s %s" % (k, ref, cand, fmt(b[k])))
        elif k not in b:
            absent.append("%s: %s %s, absent from %s" % (k, ref, fmt(a[k]), cand))
        elif a[k] != b[k]:
            differing.append("%s: %s %s, %s %s" % (k, ref, fmt(a[k]), cand, fmt(b[k])))
            short = k.replace("properties.", "")
            if short in varies or short in coupled:
                pass
            elif short in RERANK_OUTCOMES or short in ("rerankFallbackQuestions",) or k in RERANK_OUTCOMES:
                outs.append(k)
            else:
                others.append(k)
        else:
            equal.append("%s %s" % (k.replace("properties.", ""), fmt(a[k])))
    lines.append("%s (%s) against %s%s; the plan varies: %s%s" % (cand, NAMES.get(cand, "snapshot " + cand), ref, "" if ref not in NAMES else " (%s)" % NAMES[ref],
                                                                   ", ".join(varies) if varies else "nothing (reproduction or rollback)",
                                                                   "; coupled with it: " + ", ".join(coupled) if coupled else ""))
    lines.append("  differing: " + ("; ".join(differing) if differing else "none found"))
    lines.append("  absent on one side: " + ("; ".join(absent) if absent else "none"))
    lines.append("  other than the factor and its coupled properties, settings: " + (", ".join(others) if others else "none found")
                 + "; outcome counts and columns: " + (", ".join(outs) if outs else "none found"))
    lines.append("  rerank-timeout-ms (not recorded in the snapshots): %s %d (%s), %s %d (%s)" % (ref, TIMEOUT_MS[ref], TIMEOUT_SOURCE[ref], cand, TIMEOUT_MS[cand], TIMEOUT_SOURCE[cand]))
    if not differing and not absent:
        verdict = "the recorded properties are equal"
    elif not others and not outs and not absent:
        verdict = "the recorded properties differ only in the factor and the properties coupled with it"
    elif not others and not outs:
        verdict = "the recorded properties differ only in what the plan varies, and in the properties the reference predates (named above)"
    elif not others:
        verdict = "the recorded settings differ only in what the plan varies; the outcome values %s also differ" % ", ".join(outs)
    else:
        verdict = "other settings differ (%s), so no claim compares this pair" % ", ".join(others)
    pair_summary[(ref, cand)] = verdict
    lines.append("  F3: " + verdict)
    lines.append("  equal: " + ", ".join(equal))
    lines.append("")
w("property-diff.txt", "\n".join(lines))

# ---------------------------------------------------------------- rank table and histogram (F7)
ids = [q["id"] for q in questions(snapshots["1611"])]
for k in ORDER:
    assert [q["id"] for q in questions(snapshots[k])] == ids, k
by_run = {k: {q["id"]: q for q in questions(snapshots[k])} for k in ORDER}
tr_by_run = {k: traces(snapshots[k]) for k in ORDER}


def rank_text(q):
    return "not in window" if q["rank"] is None else str(q["rank"])


def above(run, qid):
    q = by_run[run][qid]
    returned = tr_by_run[run][qid]["returnedChunkIds"]
    export = exports[RUNS[run][2]]
    n = len(returned) if q["rank"] is None else q["rank"] - 1
    if q["rank"] is not None:
        assert returned[q["rank"] - 1] == q["matchedChunkId"], (run, qid)
    return [(cid, export[cid]["sectionKey"], export[cid]["head"]) for cid in returned[:n]]


lines = ["Rank table (plan Milestone 3, F7), generated by compare_chunk_size.py from the committed snapshots [observed: results.questions rank and matchedChunkId]",
         "Runs: B0 1611 (stored size 4000 / 500, reranking off), D0 1613 (rebuilt store 1000 / 125, reranking off; diagnostic record), B1 1612 (stored size, reranking on),",
         "D1 1614 (rebuilt store, reranking on; diagnostic record), P0 1615 (after the rollback rebuild at 4000 / 500, reranking off). rank/matched chunk id; '-' no matched chunk.",
         "A '*' marks a rank that differs from B0 (or from B1 for D1). Chunk ids differ between stores (each rebuild replaces them).", ""]
header = "question  kind       ticker  " + "  ".join("%-12s" % NAMES[k] for k in ORDER)
lines.append(header)
for qid in ids:
    cells = []
    for k in ORDER:
        q = by_run[k][qid]
        ref = by_run["1612"][qid] if k == "1614" else by_run["1611"][qid]
        mark = "*" if k not in ("1611",) and q["rank"] != ref["rank"] else " "
        cells.append("%-12s" % ("%s/%s%s" % (rank_text(q) if q["rank"] is not None else "-", q["matchedChunkId"] if q["matchedChunkId"] is not None else "-", mark)))
    q0 = by_run["1611"][qid]
    lines.append("%-9s %-10s %-7s %s" % (qid, q0["kind"], q0["ticker"], "  ".join(cells)))
lines.append("")
lines.append("Questions not at rank 1, per run, with the chunks returned above the matched chunk (or the whole window when none is matched) [derived: trace")
lines.append("returnedChunkIds before the stored rank, joined by id to the chunk export of the store the run read: chunks-S0.json for B0 and B1, chunks-S1.json for D0 and D1,")
lines.append("chunks-S2.json for P0; each chunk: id (section, first characters with whitespace collapsed)]")
non_first = {}
for k in ORDER:
    lines.append("")
    lines.append("%s (snapshot %s, %s):" % (NAMES[k], k, RUNS[k][2]))
    non_first[k] = []
    for qid in ids:
        q = by_run[k][qid]
        if q["rank"] == 1:
            continue
        non_first[k].append(qid)
        lines.append("  %s: rank %s, matched chunk %s" % (qid, rank_text(q), q["matchedChunkId"] if q["matchedChunkId"] is not None else "none"))
        for cid, section, head in above(k, qid):
            lines.append("      above: %d (%s, \"%s\")" % (cid, section, head))
w("rank-table.txt", "\n".join(lines) + "\n")

hist = {}
lines = ["Rank histogram per run (plan Milestone 3, F7), generated by compare_chunk_size.py [derived: count of results.questions per rank; 'not in window' is a null rank]",
         "The window is 10 results in every run (window_size).", ""]
lines.append("rank            " + "".join("%6s" % NAMES[k] for k in ORDER))
window = snapshots["1611"]["window_size"]
for k in ORDER:
    h = {}
    for q in by_run[k].values():
        key = "notInWindow" if q["rank"] is None else str(q["rank"])
        h[key] = h.get(key, 0) + 1
    hist[k] = h
for r in list(range(1, window + 1)) + ["notInWindow"]:
    lines.append("%-16s" % ("not in window" if r == "notInWindow" else "rank %d" % r) + "".join("%6d" % hist[k].get(str(r), 0) for k in ORDER))
lines.append("%-16s" % "questions" + "".join("%6d" % sum(hist[k].values()) for k in ORDER))
lines.append("%-16s" % "at rank 1" + "".join("%6d" % hist[k].get("1", 0) for k in ORDER))
lines.append("%-16s" % "not at rank 1" + "".join("%6d" % (sum(hist[k].values()) - hist[k].get("1", 0)) for k in ORDER))
w("rank-histogram.txt", "\n".join(lines) + "\n")

# ---------------------------------------------------------------- comparison
def metric(k, name):
    return snapshots[k][name]


def slice_metric(k, s, name):
    return snapshots[k]["results"]["slices"][s][name]


def held(report):
    total, held_n, unheld = 0, 0, []
    for q in report["questions"]:
        for i, p in enumerate(q["phrases"]):
            total += 1
            if p["heldByStoredChunk"]["value"] is True:
                held_n += 1
            else:
                unheld.append("%s/%d (%s, %s, \"%s\")" % (q["id"], i, p["accessionNo"], p["sectionKey"], p["phrase"]))
    return total, held_n, unheld


def top5(k, qid):
    r = by_run[k][qid]["rank"]
    return r is not None and r <= 5


def membership(ref, cand):
    entering = [q for q in ids if not top5(ref, q) and top5(cand, q)]
    leaving = [q for q in ids if top5(ref, q) and not top5(cand, q)]
    return entering, leaving


def focus_rows(k):
    rows = []
    ev = {q["id"]: q for q in evidence[k]["questions"]}
    for qid in FOCUS:
        q = ev[qid]
        holders = [(c["chunkId"], c["fusedPosition"]["value"], c["rerankInput"]["value"], c["rerankedPosition"]["value"], c["returnedPosition"]["value"])
                   for p in q["phrases"] for c in p["chunks"]]
        rows.append((qid, q["rank"]["value"], q["matchedChunkId"]["value"], q["rerankOutcome"]["value"], q["bestAcceptedChunk"]["value"], q["bestAcceptedPosition"]["value"], holders))
    return rows


lines = ["Chunk-size measurement, Milestone 3 of plan documentation/plans/2026-09-17-chunk-size.md",
         "Generated by compare_chunk_size.py from committed files only (paths relative to this directory):"]
for k in ORDER:
    lines.append("  %s (%s): %s; %s; %s; %s" % (k, NAMES[k], RUNS[k][0], RUNS[k][1], RUNS[k][2], RUNS[k][3]))
lines.append("  598, 613: the references of B0 and B1 under " + M)
lines.append("Labels: [observed] a value as stored (file and field named); [derived] computed by this script (computation named). No cause is stated.")
lines.append("")
lines.append("1. Settings and outcomes [observed: properties; derived: trace outcome counts]")
for k in ORDER:
    p = snapshots[k]["properties"]
    outcomes = {}
    for t in tr_by_run[k].values():
        o = t["rerank"]["outcome"]
        outcomes[o] = outcomes.get(o, 0) + 1
    lines.append("  %s (%s): chunkMaxChars %s, chunkOverlapChars %s, storeVersions %s, rerank %s, rerankCandidates %s, candidateCount %s, rerankedQuestions %s, rerankFallbackQuestions %s; trace outcomes %s"
                 % (k, NAMES[k], p["chunkMaxChars"], p["chunkOverlapChars"], fmt(p["storeVersions"]), fmt(p["rerank"]), p["rerankCandidates"], p["candidateCount"],
                    p["rerankedQuestions"], p["rerankFallbackQuestions"], ", ".join("%s %d" % (o, n) for o, n in sorted(outcomes.items()))))
lines.append("  F3 (property-diff.txt): " + "; ".join("%s against %s: %s" % (c, r, pair_summary[(r, c)]) for r, c, *_ in PAIRS))
lines.append("")
lines.append("2. F1: B0 against 598 and B1 against 613, rank and matched chunk per question [observed: results.questions; derived: equality]; the committed")
lines.append("   TraceReproductionCheck outputs are reproduction-1611.txt and reproduction-1612.txt")
for ref, cand in (("598", "1611"), ("613", "1612")):
    a = {q["id"]: q for q in questions(snapshots[ref])}
    diffs = [q for q in ids if (a[q]["rank"], a[q]["matchedChunkId"]) != (by_run[cand][q]["rank"], by_run[cand][q]["matchedChunkId"])]
    lines.append("  %s against %s: questions compared %d; differing: %s; hit_at_5 %s / %s; mrr %s / %s" % (cand, ref, len(ids), ", ".join(diffs) if diffs else "none found",
                 metric(ref, "hit_at_5"), metric(cand, "hit_at_5"), metric(ref, "mrr"), metric(cand, "mrr")))
lines.append("")
lines.append("3. R1 (hard gate): accepted phrases held by a stored chunk [observed: evidence report heldByStoredChunk, evaluated by the application against the store at report time]")
for label, report in (("B0's report before the rebuild (evidence-1611.json, store S0)", evidence["1611"]), ("B0's report read against the rebuilt store (%s, store S1)" % AGAINST_S1, against_s1),
                      ("D0's report (evidence-1613.json, store S1)", evidence["1613"]), ("P0's report after the rollback (evidence-1615.json, store S2)", evidence["1615"])):
    total, h, unheld = held(report)
    lines.append("  %s: %d of %d held; not held: %s" % (label, h, total, "; ".join(unheld) if unheld else "none"))
for point in ("S1", "S2"):
    totals = [l for l in held_texts[point].splitlines() if l.startswith("HELD_PHRASE TOTALS")][0]
    lines.append("  held_phrases.py direct query at %s: %s" % (point, totals[len("HELD_PHRASE TOTALS "):]))
lines.append("  R1 outcome (derived): FAILED at the rebuilt store (two phrases not held), so under the plan N0 did not run for the rule and the store was rebuilt back at 4000 / 500;")
lines.append("  D0 and D1 are diagnostic records of the rebuilt store, not selection candidates, and no rule row below is a selection outcome.")
lines.append("")
lines.append("4. Metrics per run [observed: snapshot columns and slices]")
lines.append("  run   hit@1     hit@3     hit@5     mrr       figure hit@5  non-figure hit@5  AAPL      MSFT      NVDA")
for k in ORDER:
    t = snapshots[k]["results"]["tickerHitAt5"]
    lines.append("  %-5s %-9s %-9s %-9s %-9s %-13s %-17s %-9s %-9s %-9s" % (NAMES[k], metric(k, "hit_at_1"), metric(k, "hit_at_3"), metric(k, "hit_at_5"), metric(k, "mrr"),
                 slice_metric(k, "figure", "hitAt5"), slice_metric(k, "nonFigure", "hitAt5"), t["AAPL"], t["MSFT"], t["NVDA"]))
lines.append("")
lines.append("5. Rule rows R2 to R5 for the record only (not a selection outcome: R1 failed) [derived from the values in section 4; R5 from floors-post-rollback.txt]")
for ref, cand in (("1611", "1613"), ("1612", "1614")):
    entering, leaving = membership(ref, cand)
    lines.append("  %s against %s:" % (NAMES[cand], NAMES[ref]))
    lines.append("    R2 hit@5 not below: %s (%s against %s)" % ("holds" if metric(cand, "hit_at_5") >= metric(ref, "hit_at_5") else "does not hold", metric(cand, "hit_at_5"), metric(ref, "hit_at_5")))
    lines.append("    R3 MRR not below: %s (%s against %s)" % ("holds" if metric(cand, "mrr") >= metric(ref, "mrr") else "does not hold", metric(cand, "mrr"), metric(ref, "mrr")))
    lines.append("    R4 leaving the top 5: %s (%d); entering: %s (%d); count of leavers at most entrants: %s" % (", ".join(leaving) if leaving else "none", len(leaving),
                 ", ".join(entering) if entering else "none", len(entering), "holds" if len(leaving) <= len(entering) else "does not hold"))
    lines.append("    R5 floors by RetrievalEvaluationLiveTests: not run on the rebuilt store (R1 failed); values for the record: hit@5 %s against floor 0.65, non-figure hit@5 %s against floor 0.60"
                 % (metric(cand, "hit_at_5"), slice_metric(cand, "nonFigure", "hitAt5")))
lines.append("  Floors on the post-rollback store (floors-post-rollback.txt, RetrievalEvaluationLiveTests -Drag.evaluation.live=true, exit 0): the RETRIEVAL_EVAL lines there.")
lines.append("")
lines.append("6. Top-5 membership per question between the pairs [derived: rank at most 5]")
for ref, cand in (("1611", "1613"), ("1612", "1614"), ("1611", "1615")):
    entering, leaving = membership(ref, cand)
    lines.append("  %s against %s: entering %s; leaving %s" % (NAMES[cand], NAMES[ref], ", ".join("%s (rank %s to %s)" % (q, rank_text(by_run[ref][q]), rank_text(by_run[cand][q])) for q in entering) or "none",
                 ", ".join("%s (rank %s to %s)" % (q, rank_text(by_run[ref][q]), rank_text(by_run[cand][q])) for q in leaving) or "none"))
lines.append("")
lines.append("7. nvda-02 and nvda-04 per run [observed: evidence report rank, matchedChunkId, rerankOutcome, bestAcceptedChunk, bestAcceptedPosition; per holding chunk")
lines.append("   (chunkId, fusedPosition, rerankInput, rerankedPosition, returnedPosition)]")
for k in ORDER:
    for qid, rank, matched, outcome, best, best_pos, holders in focus_rows(k):
        lines.append("  %s %s: rank %s, matched %s, outcome %s, bestAcceptedChunk %s at %s; holders %s" % (NAMES[k], qid, fmt(rank), fmt(matched), outcome, fmt(best), fmt(best_pos),
                     "; ".join("(%s, %s, %s, %s, %s)" % tuple(fmt(x) for x in h) for h in holders)))
lines.append("")
lines.append("8. F6: P0 (after the rollback) against B0 per question [observed: rank, matchedChunkId; derived: the matched chunks' content compared by filing, chunk index, length,")
lines.append("   and first characters between chunks-S0.json and chunks-S2.json (ids differ after a rebuild)]")
diff_rank, diff_content, same = [], [], 0
for q in ids:
    a, b = by_run["1611"][q], by_run["1615"][q]
    if a["rank"] != b["rank"]:
        diff_rank.append("%s (B0 rank %s chunk %s; P0 rank %s chunk %s)" % (q, rank_text(a), fmt(a["matchedChunkId"]), rank_text(b), fmt(b["matchedChunkId"])))
    elif a["matchedChunkId"] is not None:
        ca, cb = exports["chunks-S0.json"][a["matchedChunkId"]], exports["chunks-S2.json"][b["matchedChunkId"]]
        if (ca["filingId"], ca["chunkIndex"], ca["chars"], ca["head"]) != (cb["filingId"], cb["chunkIndex"], cb["chars"], cb["head"]):
            diff_content.append(q)
        else:
            same += 1
    else:
        same += 1
lines.append("  questions compared %d; rank differing: %s; matched content differing at an equal rank: %s; equal in rank and matched content: %d" % (len(ids), "; ".join(diff_rank) or "none", ", ".join(diff_content) or "none", same))
s0, s2 = load("chunks-S0.json"), load("chunks-S2.json")
key_only = [(a["id"], a["sectionKey"], b["id"], b["sectionKey"]) for a, b in zip(s0, s2) if (a["filingId"], a["chunkIndex"], a["chars"], a["head"]) == (b["filingId"], b["chunkIndex"], b["chars"], b["head"]) and a["sectionKey"] != b["sectionKey"]]
text_diff = [(a["id"], b["id"]) for a, b in zip(s0, s2) if (a["filingId"], a["chunkIndex"], a["chars"], a["head"]) != (b["filingId"], b["chunkIndex"], b["chars"], b["head"])]
lines.append("  store S2 against S0 (chunks-S2.json against chunks-S0.json, %d and %d chunks, paired in order): pairs with equal filing, chunk index, length and first characters and an equal section key %d;"
             % (len(s2), len(s0), len(s0) - len(key_only) - len(text_diff)))
lines.append("  section key differing only: %s; text differing: %s" % ("; ".join("S0 %d %s -> S2 %d %s" % x for x in key_only) or "none", ", ".join("S0 %d / S2 %d" % x for x in text_diff) or "none"))
lines.append("  F6 outcome (derived): %s" % ("holds" if not diff_rank and not diff_content else "does not hold for " + ", ".join(q.split(" ")[0] for q in diff_rank) + ("" if not diff_content else " and " + ", ".join(diff_content))))
lines.append("")
lines.append("9. F8 latency per run [observed: latency-<id>.json, extracted by latency.py from the committed application-log windows app-log-<id>.txt; wall seconds is the")
lines.append("   curl time_total of the evaluate call recorded in run.log]. Retrieval elapsed includes the query embedding, the searches, fusion, and reranking when on.")
lines.append("  run   retrieval ms: median  p95   max   min   sum      rerank ms: median  p95   max   min   sum      wall s   fallbacks")
for k in ORDER:
    l = latency[k]
    r, rr = l["retrievalMs"], l["rerankMs"]
    rerank_cells = "%-7s %-5s %-5s %-5s %-8s" % ((rr["median"], rr["p95"], rr["max"], rr["min"], rr["sum"]) if rr else ("-", "-", "-", "-", "-"))
    lines.append("  %-5s %21s  %-5s %-5s %-5s %-8s %17s  %-8s %d" % (NAMES[k], r["median"], r["p95"], r["max"], r["min"], r["sum"], rerank_cells, l["wallSeconds"], l["rerankFallbacks"]))
lines.append("")
lines.append("10. F9 storage per point [observed: storage-S<n>.json, written by storage.sql; sizes as pg_* functions reported them at run time, dead tuples included]")
fields = ["chunks", "contentChars", "meanChunkChars", "maxChunkChars", "minChunkChars", "tokenCountSum", "maxTokenCount", "totalRelationBytes", "heapBytes", "toastBytes", "indexesBytes",
          "liveTuples", "deadTuples", "storeVersions"]
lines.append("  %-20s %-40s %-40s %-40s" % ("field", "S0 (before the rebuild)", "S1 (rebuilt at 1000 / 125)", "S2 (after the rollback)"))
for f in fields:
    lines.append("  %-20s %-40s %-40s %-40s" % (f, fmt(storage["storage-S0.json"][f]), fmt(storage["storage-S1.json"][f]), fmt(storage["storage-S2.json"][f])))
for idx in storage["storage-S0.json"]["indexes"]:
    name = idx["name"]
    lines.append("  %-20s %-40s %-40s %-40s" % ("index " + name[:14], *[fmt(next(i["bytes"] for i in storage[s]["indexes"] if i["name"] == name)) for s in ("storage-S0.json", "storage-S1.json", "storage-S2.json")]))
lines.append("  chunks per filing (filingId: S0 / S1 / S2): " + ", ".join("%d: %s / %s / %s" % (fid, *[fmt(path_value(storage[s], "filingsDetail[filingId=%d].chunks" % fid)) for s in ("storage-S0.json", "storage-S1.json", "storage-S2.json")]) for fid in FILINGS))
lines.append("  no index exists on the embedding column (pg_indexes at run time: the listed indexes are all of sec_filing_chunks); the content_tsv GIN index is idx_sec_filing_chunks_content_tsv.")
w("comparison.txt", "\n".join(lines) + "\n")

# ---------------------------------------------------------------- experiment files
for ref, cand in (("1611", "1613"), ("1612", "1614")):
    doc = {"description": "Chunk size 4000 / 500 (%s, %s) against 1000 / 125 (%s, %s) on the rebuilt store, reranking %s in both; the rebuild changes chunkOverlapChars and storeVersions together with chunkMaxChars, so they are coupled. %s is a diagnostic record, not a selection candidate (R1 failed; comparison.txt section 3). rerank-timeout-ms, not recorded in snapshots, was 2000 in both (property-diff.txt)."
                          % (NAMES[ref], ref, NAMES[cand], cand, "on" if ref == "1612" else "off", NAMES[cand]),
           "experiment": {"factor": "chunkMaxChars", "settings": [4000, 1000], "coupled": ["chunkOverlapChars", "storeVersions"], "snapshots": [RUNS[ref][0], RUNS[cand][0]]}}
    w("experiment-chunk-size-%s-%s.json" % (ref, cand), json.dumps(doc, indent=1) + "\n")

# ---------------------------------------------------------------- claims
claims = []
next_id = [FIRST_ID]


def claim(basis, block, check=None, text=None, frm=None, experiment=None):
    c = {"id": "C-%d" % next_id[0], "basis": basis, "block": block}
    next_id[0] += 1
    if text is not None:
        c["text"] = text
    if frm is not None:
        c["from"] = frm
    if experiment is not None:
        c["experiment"] = experiment
    if check is not None:
        c["check"] = check
    claims.append(c)
    return c["id"]


def dec(v):
    return float(v) if isinstance(v, Decimal) and v != v.to_integral_value() else (int(v) if isinstance(v, Decimal) else v)


def num(v):
    return str(v)


# baselines
b = "baselines"
ids_base = []
for k in ("1611", "1612"):
    ids_base.append(claim("observed", b, {"type": "metric", "snapshot": RUNS[k][0], "metric": "hitAt5", "expected": num(metric(k, "hit_at_5"))}))
    ids_base.append(claim("observed", b, {"type": "metric", "snapshot": RUNS[k][0], "metric": "mrr", "expected": num(metric(k, "mrr"))}))
claim("inferred", b, text="The baseline with reranking off reproduces snapshot 598 and the baseline with reranking on reproduces snapshot 613 per question in rank and matched chunk (TraceReproductionFilesTests, problems=0 in reproduction-1611.txt and reproduction-1612.txt, with the three properties those references predate exempt), so F1 holds and the rebuild followed", frm=ids_base)

# store and rebuild (F2, F9)
b = "store"
for sfile in ("storage-S0.json", "storage-S1.json", "storage-S2.json"):
    for field in STORAGE_FIELDS:
        claim("observed", b, {"type": "fileValue", "file": sfile, "path": field, "expected": dec(path_value(storage[sfile], field)) if not isinstance(path_value(storage[sfile], field), list) else path_value(storage[sfile], field)})
b = "filings"
for sfile in ("storage-S0.json", "storage-S1.json"):  # S2's per-filing counts equal S0's (comparison.txt, section 10) and are not repeated as claims
    for fid in FILINGS:
        claim("observed", b, {"type": "fileValue", "file": sfile, "path": "filingsDetail[filingId=%d].chunks" % fid, "expected": dec(path_value(storage[sfile], "filingsDetail[filingId=%d].chunks" % fid))})
    claim("observed", b, {"type": "fileValue", "file": sfile, "path": "embeddedFilings", "expected": dec(storage[sfile]["embeddedFilings"])})

# R1
b = "r1"
r1_ids = []
for rfile in ("evidence-1611.json", AGAINST_S1, "evidence-1613.json", "evidence-1615.json"):
    rep = load(rfile)
    total, h, _ = held(rep)
    r1_ids.append(claim("derived", b, {"type": "heldPhrases", "report": rfile, "expected": {"phrases": total, "held": h}}))
claim("inferred", b, text="R1 does not hold on the rebuilt store: two accepted phrases of set v2 are held by a stored chunk in the baseline's report before the rebuild and not held after it, under the application's own rule; so under the plan the candidate run N0 did not run for the rule, the store was rebuilt back at the stored size, and the diagnostic runs on the rebuilt store are records without a selection outcome", frm=r1_ids)

# diagnostic runs and rule rows for the record
b = "diagnostic"
diag_ids = []
for k in ("1613", "1614"):
    for m, col in (("hitAt1", "hit_at_1"), ("hitAt5", "hit_at_5"), ("mrr", "mrr")):
        diag_ids.append(claim("observed", b, {"type": "metric", "snapshot": RUNS[k][0], "metric": m, "expected": num(metric(k, col))}))
    diag_ids.append(claim("observed", b, {"type": "metric", "snapshot": RUNS[k][0], "metric": "slices.nonFigure.hitAt5", "expected": num(slice_metric(k, "nonFigure", "hitAt5"))}))
    for t in ("AAPL", "MSFT", "NVDA"):
        diag_ids.append(claim("observed", b, {"type": "metric", "snapshot": RUNS[k][0], "metric": "tickerHitAt5." + t, "expected": num(snapshots[k]["results"]["tickerHitAt5"][t])}))
for ref, cand in (("1611", "1613"), ("1612", "1614")):
    exp = {"file": "experiment-chunk-size-%s-%s.json" % (ref, cand), "factor": "chunkMaxChars"}
    rerank = "on" if ref == "1612" else "off"
    for m, col, name in (("hitAt5", "hit_at_5", "hit@5"), ("mrr", "mrr", "MRR")):
        direction = "below" if metric(cand, col) < metric(ref, col) else ("equal to" if metric(cand, col) == metric(ref, col) else "above")
        claim("experiment", b, {"type": "metric", "snapshot": RUNS[cand][0], "metric": m, "expected": num(metric(cand, col))},
              text="With the rebuilt store at chunk size 1000 and overlap 125 against the stored 4000 and 500, reranking %s in both and the other recorded settings equal, the %s of %s is %s the %s of %s (a record, not a selection outcome)" % (rerank, name, LABELS[cand], direction, name, LABELS[ref]), experiment=exp)
    entering, leaving = membership(ref, cand)
    for q in leaving:
        claim("experiment", b, {"type": "topK", "question": q, "k": 5, "rows": [{"snapshot": RUNS[ref][0], "expected": "inside"}, {"snapshot": RUNS[cand][0], "expected": "outside"}]},
              text="With the rebuilt store at chunk size 1000 and overlap 125 against the stored 4000 and 500, reranking %s in both and the other recorded settings equal, %s left the top 5" % (rerank, q), experiment=exp)
    for q in entering:
        claim("experiment", b, {"type": "topK", "question": q, "k": 5, "rows": [{"snapshot": RUNS[ref][0], "expected": "outside"}, {"snapshot": RUNS[cand][0], "expected": "inside"}]},
              text="With the rebuilt store at chunk size 1000 and overlap 125 against the stored 4000 and 500, reranking %s in both and the other recorded settings equal, %s entered the top 5" % (rerank, q), experiment=exp)
claim("inferred", b, text="For the record and not as a selection outcome (R1 failed): against the baseline with reranking off, the diagnostic run with reranking off has a lower hit@5 and a lower MRR, and more questions left the top 5 than entered it; the floors test did not run on the rebuilt store", frm=diag_ids[:8] + ids_base[:2])

# ranks (F7)
b = "ranks"
for k in ORDER:
    claim("derived", b, {"type": "rankHistogram", "snapshot": RUNS[k][0], "expected": hist[k]})
for k in ORDER[:4]:  # P0's chunks above are in rank-table.txt; it repeats B0 except for aapl-08 (block rollback)
    for q in non_first[k]:
        claim("derived", b, {"type": "rankedAbove", "snapshot": RUNS[k][0], "question": q, "chunks": RUNS[k][2], "expected": [cid for cid, _, _ in above(k, q)]})

# nvda-02 and nvda-04 (F4)
b = "nvda"
nvda_ids = []
for k in ("1611", "1613", "1612", "1614"):
    ev = {q["id"]: q for q in evidence[k]["questions"]}
    for qid in FOCUS:
        nvda_ids.append(claim("observed", b, {"type": "rank", "snapshot": RUNS[k][0], "question": qid, "expected": by_run[k][qid]["rank"]}))
        nvda_ids.append(claim("observed", b, {"type": "matchedChunk", "snapshot": RUNS[k][0], "question": qid, "expected": by_run[k][qid]["matchedChunkId"]}))
        for p in ev[qid]["phrases"]:
            for c in p["chunks"]:
                expected = {"fusedPosition": dec(c["fusedPosition"]["value"]) if c["fusedPosition"]["value"] is not None else None, "rerankInput": c["rerankInput"]["value"]}
                if k in ("1612", "1614"):
                    expected["rerankedPosition"] = dec(c["rerankedPosition"]["value"]) if c["rerankedPosition"]["value"] is not None else None
                nvda_ids.append(claim("observed", b, {"type": "candidate", "report": RUNS[k][1], "question": qid, "chunk": c["chunkId"], "expected": expected}))
for ref, cand in (("1611", "1613"), ("1612", "1614")):
    exp = {"file": "experiment-chunk-size-%s-%s.json" % (ref, cand), "factor": "chunkMaxChars"}
    evc = {q["id"]: q for q in evidence[cand]["questions"]}
    for qid in FOCUS:
        fused = [c["fusedPosition"]["value"] for p in evc[qid]["phrases"] for c in p["chunks"] if c["fusedPosition"]["value"] is not None]
        best = min(fused) if fused else None
        claim("experiment", b, {"type": "bestFusedPosition", "report": RUNS[cand][1], "question": qid, "expected": dec(best) if best is not None else None},
              text="With the rebuilt store at chunk size 1000 and overlap 125 against the stored 4000 and 500, reranking %s in both and the other recorded settings equal, the best fused position of a chunk holding an accepted phrase of %s in %s is stated by the check beside the position of the same question's holding chunk in %s" % ("on" if ref == "1612" else "off", qid, LABELS[cand], LABELS[ref]), experiment=exp)

# latency (F8)
b = "latency"
for k in ORDER:
    lf = RUNS[k][3]
    for path in ("retrievalMs.median", "retrievalMs.p95", "retrievalMs.max", "wallSeconds"):
        claim("observed", b, {"type": "fileValue", "file": lf, "path": path, "expected": dec(path_value(latency[k], path))})
    if latency[k]["rerankMs"]:
        for path in ("rerankMs.median", "rerankMs.p95", "rerankMs.max"):
            claim("observed", b, {"type": "fileValue", "file": lf, "path": path, "expected": dec(path_value(latency[k], path))})
    claim("observed", b, {"type": "fileValue", "file": lf, "path": "rerankFallbacks", "expected": latency[k]["rerankFallbacks"]})

# rollback (F6)
b = "rollback"
roll_ids = []
for m, col in (("hitAt5", "hit_at_5"), ("mrr", "mrr")):
    roll_ids.append(claim("observed", b, {"type": "metric", "snapshot": RUNS["1615"][0], "metric": m, "expected": num(metric("1615", col))}))
for k in ("1611", "1615"):
    roll_ids.append(claim("observed", b, {"type": "rank", "snapshot": RUNS[k][0], "question": "aapl-08", "expected": by_run[k]["aapl-08"]["rank"]}))
    roll_ids.append(claim("observed", b, {"type": "matchedChunk", "snapshot": RUNS[k][0], "question": "aapl-08", "expected": by_run[k]["aapl-08"]["matchedChunkId"]}))
roll_ids.append(claim("observed", b, {"type": "candidate", "report": RUNS["1611"][1], "question": "aapl-08", "chunk": by_run["1611"]["aapl-08"]["matchedChunkId"], "expected": {"fusedPosition": 1}}))
roll_ids.append(claim("derived", b, {"type": "rankedAbove", "snapshot": RUNS["1615"][0], "question": "aapl-08", "chunks": RUNS["1615"][2], "expected": [cid for cid, _, _ in above("1615", "aapl-08")]}))
claim("inferred", b, text="After the rollback rebuild the post-rollback run equals the baseline with reranking off in rank and matched chunk content for the other questions of the set (comparison.txt, section 8) and differs for aapl-08, whose accepted phrase is held by a chunk whose section key the re-parse now records as ITEM_2_02 where the set names ITEM_2; so F6 does not hold for that one question, and the store stays at the stored size with the code defaults unchanged", frm=roll_ids)

# section keys of the AAPL 8-Ks (F6; Orchestrator finding folded in 2026-09-17)
b = "keys"
key_ids = []
KEY_PAIRS = [(297, 11513), (298, 11514), (331, 11547), (332, 11548), (333, 11549)]
for old, new in KEY_PAIRS:
    for export, cid in (("chunks-S0.json", old), ("chunks-S2.json", new)):
        c = exports[export][cid]
        key_ids.append(claim("observed", b, {"type": "fileValue", "file": export, "path": "[id=%d].sectionKey" % cid, "expected": c["sectionKey"]}))
        key_ids.append(claim("observed", b, {"type": "fileValue", "file": export, "path": "[id=%d].chars" % cid, "expected": dec(c["chars"])}))
    key_ids.append(claim("observed", b, {"type": "fileValue", "file": "chunks-S2.json", "path": "[id=%d].head" % new, "expected": exports["chunks-S0.json"][old]["head"]}))
for fid in (5, 7, 8, 288):
    key_ids.append(claim("observed", b, {"type": "fileValue", "file": "filings-S2.json", "path": "[filingId=%d].createdAt" % fid, "expected": path_value(load("filings-S2.json"), "[filingId=%d].createdAt" % fid)}))
for cid in (11970, 11972, 11974):
    key_ids.append(claim("observed", b, {"type": "fileValue", "file": "chunks-S2.json", "path": "[id=%d].sectionKey" % cid, "expected": exports["chunks-S2.json"][cid]["sectionKey"]}))
claim("inferred", b, text="The five AAPL 8-K chunks of filings 5, 7, and 8 (ingested 2026-09-10) have the same text before the rebuild and after the rollback and a section key with the sub-item after it (ITEM_2_02 for ITEM_2, ITEM_9_01 for ITEM_9, ITEM_5_02 for ITEM_5), while the MSFT 8-Ks ingested 2026-09-12 carried sub-item keys before the rebuild; so the parser in the tree at rebuild time keys 8-K items with the sub-item, and the 2026-09-10 ingestion did not (derived from the exports on identical text)", frm=key_ids)
claim("inferred", b, text="A parser change between 2026-09-10 and 2026-09-12 (FilingHtmlParser commits 2ed3275 and 19876f2 in that interval, its comment saying the sub-item is kept in the key) is the inferred and untested reading of the key change: the isolating experiment, re-parsing filing 5 at commit 6e4719a, was not run", frm=key_ids)
claim("inferred", b, text="aapl-08's not-held phrase is independent of the chunk size: the phrase is held by a stored chunk under the matching rule neither at the rebuilt size nor at the stored size after a rebuild with this parser, since the set names section ITEM_2 and the store keys it ITEM_2_02, and the post-rollback report at the stored size records it not held (the held-phrases claims); aapl-13's not-held phrase at the rebuilt store lies across a chunk boundary of the stored cut at the smaller size, and Milestone 1's diagnostic recorded aapl-13 as the one split phrase at its smallest re-split size (its sizeTable claim at that size)", frm=key_ids + r1_ids)

# decision
claim("inferred", "decision", text="The selection rule was not applied: R1 failed, the rebuilt store was rolled back, the defaults stay at chunk size 4000 and overlap 125 times 4, and the decision on a further size or on the aapl-08 section key is recorded as DECISION RAG-27 for Jay", frm=r1_ids + roll_ids[:1])

labels = {}
for k in ORDER:
    labels[RUNS[k][0]] = LABELS[k]
    labels[RUNS[k][1]] = LABELS[k]
labels[AGAINST_S1] = "the baseline's report read against the rebuilt store"
labels["filings-S2.json"] = "the filings export after the rollback"
labels.update(CHUNK_LABELS)
labels.update(STORAGE_LABELS)
labels.update(LATENCY_LABELS)
doc = {"description": "Chunk-size measurement (plan plans/2026-09-17-chunk-size.md, Milestone 3), written by compare_chunk_size.py: the baselines B0 (1611) and B1 (1612) at the stored size; storage and per-filing chunk counts before the rebuild (S0), after it (S1, 1000 / 125) and after the rollback (S2, 4000 / 500); R1 by the evidence reports; the diagnostic runs D0 (1613) and D1 (1614) on the rebuilt store, their metrics and top-5 membership against the baselines as experiment claims for the record (R1 failed, so no run is a selection candidate); the rank histogram of every run and the chunks returned above every question not at rank 1; nvda-02 and nvda-04; latency per run; the post-rollback run P0 (1615) against B0; and the decision. No claim states why a rank, a time, or a size changed.",
       "labels": labels, "claims": claims}
w("claims.json", json.dumps(doc, indent=1) + "\n")

for name, text in out.items():
    with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
        f.write(text if text.endswith("\n") else text + "\n")
print("written: %s; claims %d (C-%d to C-%d)" % (", ".join(out), len(claims), FIRST_ID, next_id[0] - 1))

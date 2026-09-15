#!/usr/bin/env python3
"""Second reranker model against the current model (plan documentation/plans/2026-09-15-reranker-ettin.md, Milestone 2 contract; Follow_Ups RAG-22).

Reads only committed files (paths relative to this script's directory): the snapshots (row_to_json exports), evidence reports and scoring log
extracts of the runs R (current model, reproduction of 947), A and B (gte-reranker-modernbert-base) of this directory, and of the committed
current-model runs 598 and 613 (../2026-09-13-evaluation-evidence/measurement/) and 947 (../2026-09-14-recall-one-factor/). Writes three files
next to this script, replacing them:
  property-diff.txt  G1: every recorded property of R against 947, A against 613, B against 947, A and B against 598, and each run's
                     rerank-timeout-ms with its source (snapshots do not record it);
  comparison.txt     G2 and G3: settings and fallbacks, R against 947 per question, per row every question's rank, matched chunk and best
                     accepted chunk with its fused and reranked position in both runs, questions entering and leaving the top 5, metrics,
                     slices, per-ticker hit@5, scoring time per question from the scoring logs, and the selection rule against 598;
  claims.json        the claims (ids from C-901) whose generated block stands in RAG.md, Second reranker model.
No database, no application, no model. Numbers are parsed as written (Decimal). Output is deterministic: a re-run reproduces the three files byte
for byte. The selection rule is the function rule of ../2026-09-14-recall-one-factor/compare_one_factor.py (imported, not copied).
The script states ranks, positions, metrics, rule criteria and times; it states no cause and does not say why a model ranks a chunk differently.

Definitions:
  best accepted chunk  the evidence report's bestAcceptedChunk (first chunk holding an accepted phrase in the report's ranking order: the reranked
                       order for a RERANKED question); its fused and reranked positions are read from the report's chunk entries.
  top 5                snapshot rank not null and at most 5; entering: outside in the paired current-model run, inside in the second model's row;
                       leaving: the reverse.
  scoring time         elapsedMs of the "Cross-encoder scoring completed" lines with topK=10 (the evaluation's calls; the topK=5 line is the
                       warm-up) of each run's scoring log extract; median over the lines (mean of the middle two for an even count) and maximum.
  X5                   a row whose snapshot records a rerank fallback gets no ruleRow claim (plan, Frozen comparison).

Run: python3 compare_gte.py   (then: git diff --exit-code property-diff.txt comparison.txt claims.json)
"""
import importlib.util
import json
import os
import re
import sys
from decimal import Decimal

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))


def _module(name, path):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ONE = _module("compare_one_factor", "../2026-09-14-recall-one-factor/compare_one_factor.py")
cr = ONE.cr

M = "../2026-09-13-evaluation-evidence/measurement/"
F = "../2026-09-14-recall-one-factor/"
R_ID, A_ID, B_ID = "1366", "1367", "1368"
RUNS = {
    "598": (M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json", M + "evidence-598.json", M + "scoring-598.txt"),
    "613": (M + "snapshot-613-traced-snapshot-297-rerank-candidates-20.json", M + "evidence-613.json", M + "scoring-613.txt"),
    "947": (F + "snapshot-947-c-repeat-candidate-count-200-rerank-candidates-40-timeout-4000.json", F + "evidence-947.json", F + "scoring-947.txt"),
    R_ID: (f"snapshot-{R_ID}-r-current-model-reproduction-of-947.json", f"evidence-{R_ID}.json", f"scoring-{R_ID}.txt"),
    A_ID: (f"snapshot-{A_ID}-a-gte-candidate-count-40-rerank-candidates-20.json", f"evidence-{A_ID}.json", f"scoring-{A_ID}.txt"),
    B_ID: (f"snapshot-{B_ID}-b-gte-candidate-count-200-rerank-candidates-40.json", f"evidence-{B_ID}.json", f"scoring-{B_ID}.txt"),
}
NAMES = {"598": "default reference, reranking off", "613": "current model, row A settings", "947": "current model, row B settings",
         R_ID: "run R, current model reproduction of 947", A_ID: "row A, gte model", B_ID: "row B, gte model"}
# rerank-timeout-ms per run, not recorded in snapshots, with its source.
TIMEOUT_MS = {"598": 2000, "613": 2000, "947": 4000, R_ID: 4000, A_ID: 120000, B_ID: 120000}
TIMEOUT_SOURCE = {"598": "application.yaml default at 432f456, no override recorded (../2026-09-14-recall-one-factor/run.log, L1)",
                  "613": "application.yaml default at 432f456 ('timeout default', ../2026-09-13-evaluation-evidence/measurement/run.log, Run 297)",
                  "947": "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000 (../2026-09-14-recall-one-factor/run.log, Run c repeat)",
                  R_ID: "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000 and the timeoutMs=4000 log lines (run.log, run R)",
                  A_ID: "application-reranker-gte.yaml rerank-timeout-ms 120000 and the timeoutMs=120000 log lines (run.log, run A)",
                  B_ID: "application-reranker-gte.yaml rerank-timeout-ms 120000 and the timeoutMs=120000 log lines (run.log, run B)"}
# (reference, candidate, recorded keys allowed to differ as the factor, pair kind)
MODEL = ["reranker", "rerankerVersion"]
OUTCOMES = ["rerankedQuestions", "rerankFallbackQuestions", "retrieval_strategy"]
PAIRS = [("947", R_ID, [], "reproduction"), ("613", A_ID, MODEL, "row A against its paired current-model run"),
         ("947", B_ID, MODEL, "row B against its paired current-model run"),
         ("598", A_ID, MODEL + ["rerank"], "selection reference for row A"),
         ("598", B_ID, MODEL + ["rerank", "candidateCount", "rerankCandidates"], "selection reference for row B")]
ROWS = [("A", "613", A_ID), ("B", "947", B_ID)]
SCORING_PAIR = {A_ID: "613", B_ID: R_ID}
COLUMNS = ["set_version", "question_count", "window_size", "retrieval_strategy"]
METRICS = ["hitAt1", "hitAt3", "hitAt5", "mrr"]
FIRST_ID = 901
LAST_ID = 999


class Stop(Exception):
    pass


def load(path):
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def fmt(v):
    return cr.fmt(v)


def stored(S, metric):
    return {"hitAt1": S["hit_at_1"], "hitAt3": S["hit_at_3"], "hitAt5": S["hit_at_5"], "mrr": S["mrr"]}[metric]


def scoring_times(path):
    times = []
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        for line in f:
            m = re.search(r"Cross-encoder scoring completed: candidates=(\d+), windows=(\d+), topK=10, elapsedMs=(\d+)", line)
            if m:
                times.append(int(m.group(3)))
    if not times:
        raise Stop(f"{path}: no evaluation scoring line")
    s = sorted(times)
    n = len(s)
    median = Decimal(s[n // 2]) if n % 2 else (Decimal(s[n // 2 - 1]) + Decimal(s[n // 2])) / 2
    return n, median, s[-1]


def chunk_entry(ev, chunk):
    for ph in ev["phrases"]:
        for c in ph["chunks"]:
            if c["chunkId"] == chunk:
                return c
    return None


def inside(rank):
    return rank is not None and rank <= 5


def main():
    data = {}
    for run, (s, e, log) in RUNS.items():
        S, E = load(s), load(e)
        if str(S["id"]) != run or str(E["snapshotId"]) != run:
            raise Stop(f"{s} or {e} does not record id {run}")
        qs = {}
        ranks = {q["id"]: q for q in S["results"]["questions"]}
        for q in E["questions"]:
            if q["rank"]["value"] != ranks[q["id"]]["rank"]:
                raise Stop(f"run {run} {q['id']}: evidence rank differs from snapshot rank")
            best_chunk = q["bestAcceptedChunk"]["value"]
            c = chunk_entry(q, best_chunk) if best_chunk is not None else None
            qs[q["id"]] = {"rank": ranks[q["id"]]["rank"], "chunk": ranks[q["id"]]["matchedChunkId"], "kind": ranks[q["id"]]["kind"],
                           "ticker": ranks[q["id"]]["ticker"], "outcome": q["rerankOutcome"]["value"], "bestChunk": best_chunk,
                           "bestBasis": q["bestAcceptedChunk"]["basis"],
                           "fused": c["fusedPosition"]["value"] if c else None, "input": c["rerankInput"]["value"] if c else None,
                           "reranked": c["rerankedPosition"]["value"] if c else None, "entry": c, "ev": q, "bestFused": cr.best(q)[0]}
        data[run] = (s, e, log, S, E, qs)
    order = [q["id"] for q in data["598"][3]["results"]["questions"]]

    def fallbacks(run):
        return ONE.fallbacks(data[run][3])

    # property-diff.txt (G1)
    d = ["Recorded properties per pair (plan 2026-09-15-reranker-ettin.md, Milestone 2 contract, G1), generated by compare_gte.py from the committed snapshots",
         "Compared: the snapshot columns set_version, question_count, window_size, retrieval_strategy and every key of properties (trace included).",
         "[observed: the values as stored; derived: equality]. rerank-timeout-ms and max-length are not recorded in any snapshot; each run's rerank-timeout-ms",
         "is printed with its source and named when it differs; max-length is 512 in every run's 'Cross-encoder loaded' line (run.log) and is not compared here.",
         "Outcome counts and columns (rerankedQuestions, rerankFallbackQuestions, retrieval_strategy) are named apart from settings.", ""]
    pair_ok = {}
    for ref, cand, factors, kind in PAIRS:
        R, C = data[ref][3], data[cand][3]
        diffs = [f"{col}: {ref} {fmt(R[col])}, {cand} {fmt(C[col])}" for col in COLUMNS if R[col] != C[col]]
        equal = []
        for k in sorted(set(R["properties"]) | set(C["properties"])):
            rv, cv = R["properties"].get(k, "<absent>"), C["properties"].get(k, "<absent>")
            if rv != cv:
                diffs.append(f"properties.{k}: {ref} {fmt(rv)}, {cand} {fmt(cv)}")
            else:
                equal.append(f"{k} {fmt(rv)}")

        def key(x):
            return x.split(":")[0].replace("properties.", "")

        other = [x for x in diffs if key(x) not in factors and key(x) not in OUTCOMES]
        outcome = [x for x in diffs if key(x) in OUTCOMES]
        pair_ok[(ref, cand)] = not other
        d.append(f"{cand} ({NAMES[cand]}) against {ref} ({NAMES[ref]}); {kind}; allowed to differ: {', '.join(factors) or 'nothing (reproduction)'}")
        d.append("  differing: " + ("; ".join(diffs) if diffs else "none found"))
        d.append("  other than the allowed keys, settings: " + ("; ".join(other) if other else "none found")
                 + "; outcome counts and columns: " + ("; ".join(outcome) if outcome else "none found"))
        d.append(f"  rerank-timeout-ms (not recorded in the snapshots): {ref} {TIMEOUT_MS[ref]} ({TIMEOUT_SOURCE[ref]}); "
                 f"{cand} {TIMEOUT_MS[cand]} ({TIMEOUT_SOURCE[cand]})")
        verdict = ("the recorded settings differ only in the allowed keys" if pair_ok[(ref, cand)]
                   else "recorded settings other than the allowed keys differ (named above), so no claim compares this pair")
        if TIMEOUT_MS[ref] != TIMEOUT_MS[cand]:
            verdict += f"; rerank-timeout-ms, not recorded in the snapshots, also differs ({TIMEOUT_MS[ref]}, {TIMEOUT_MS[cand]}; plan amendment 1)"
        d.append("  G1: " + verdict)
        d.append("  equal: " + ", ".join(equal) + "; " + ", ".join(f"{c} {fmt(R[c])}" for c in COLUMNS if R[c] == C[c]))
        d.append("")
    with open(os.path.join(HERE, "property-diff.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(d) + "\n")

    out = []
    p = out.append
    p("Second reranker model against the current model (plan documentation/plans/2026-09-15-reranker-ettin.md, Milestone 2 contract)")
    p("Generated by compare_gte.py from committed files only (paths relative to the script's directory):")
    for run, (s, e, log) in RUNS.items():
        p(f"  {run} ({NAMES[run]}): {s}; {e}; {log}")
    p("Labels: [observed] a value as stored (file and field named); [derived] computed by this script (computation named). No cause is stated.")
    p("")

    p("1. Settings and outcomes [observed: properties.reranker, rerankerVersion, candidateCount, rerank, rerankCandidates, rerankedQuestions, "
      "rerankFallbackQuestions; derived: trace outcome counts]")
    for run in RUNS:
        S = data[run][3]
        pr = S["properties"]
        counts = {}
        for t in S["results"]["traces"]:
            counts[t["trace"]["rerank"]["outcome"]] = counts.get(t["trace"]["rerank"]["outcome"], 0) + 1
        p(f"  {run}: reranker {pr['reranker']} {pr['rerankerVersion']}, candidateCount {pr['candidateCount']}, rerank {fmt(pr['rerank'])}, "
          f"rerankCandidates {pr['rerankCandidates']}, rerankedQuestions {pr['rerankedQuestions']}, rerankFallbackQuestions "
          f"{pr['rerankFallbackQuestions']}; trace outcomes " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    p("  G1 (property-diff.txt): " + "; ".join(f"{c} against {r}: {'only the allowed keys differ' if pair_ok[(r, c)] else 'other settings differ'}"
                                               for r, c, _, _ in PAIRS))
    p("")

    p("2. Rerank fallbacks per run [observed: results.traces[].trace.rerank.outcome FALLBACK and fallbackReason]")
    fell = {}
    for run in RUNS:
        fell[run] = fallbacks(run)
        p(f"  {run}: {len(fell[run])}" + (": " + ", ".join(f"{q} ({r})" for q, r in fell[run]) if fell[run] else ""))
    p("")

    p(f"3. Run R ({R_ID}) against 947, rank and matched chunk per question [observed: results.questions rank, matchedChunkId; derived: equality]"
      f" (beside reproduction-{R_ID}.txt)")
    diffs = [f"{q}: rank {fmt(data['947'][5][q]['rank'])} -> {fmt(data[R_ID][5][q]['rank'])}, chunk {fmt(data['947'][5][q]['chunk'])} -> "
             f"{fmt(data[R_ID][5][q]['chunk'])}" for q in order
             if (data["947"][5][q]["rank"], data["947"][5][q]["chunk"]) != (data[R_ID][5][q]["rank"], data[R_ID][5][q]["chunk"])]
    reproduced = not diffs
    p(f"  questions compared {len(order)}; differing: " + ("; ".join(diffs) if diffs else "none found"))
    for m in METRICS:
        p(f"  {m}: 947 {stored(data['947'][3], m)}, {R_ID} {stored(data[R_ID][3], m)}")
    if not reproduced:
        raise Stop("run R does not reproduce 947; the plan stops before rows A and B")
    p("")

    section = 4
    entering_leaving = {}
    for label, ref, cand in ROWS:
        rq, cq = data[ref][5], data[cand][5]
        p(f"{section}. Row {label}: {cand} (gte) against {ref} (current model), per question [observed: snapshot rank, matchedChunkId; evidence "
          "bestAcceptedChunk and its fusedPosition, rerankedPosition; derived: best fused position]")
        p("   columns: rank / matched chunk / best accepted chunk (its fused position -> its reranked position) / best fused position "
          "(candidate_recall.best); '-' null; '*' rank or matched chunk differs")
        p(f"  question  kind       {ref:>42} | {cand:>42}")
        for q in order:
            def cell(x):
                return (f"{fmt(x['rank']) if x['rank'] is not None else '-'} / {fmt(x['chunk']) if x['chunk'] is not None else '-'} / "
                        f"{fmt(x['bestChunk']) if x['bestChunk'] is not None else '-'} "
                        f"({fmt(x['fused']) if x['fused'] is not None else '-'} -> {fmt(x['reranked']) if x['reranked'] is not None else '-'}) / "
                        f"{fmt(x['bestFused']) if x['bestFused'] is not None else '-'}")
            mark = "*" if (rq[q]["rank"], rq[q]["chunk"]) != (cq[q]["rank"], cq[q]["chunk"]) else " "
            p(f"  {q:<8}  {rq[q]['kind']:<9} {cell(rq[q]):>42} | {cell(cq[q]):>42} {mark}")
        entering = [q for q in order if not inside(rq[q]["rank"]) and inside(cq[q]["rank"])]
        leaving = [q for q in order if inside(rq[q]["rank"]) and not inside(cq[q]["rank"])]
        entering_leaving[label] = (entering, leaving)
        changed = [q for q in order if (rq[q]["rank"], rq[q]["chunk"]) != (cq[q]["rank"], cq[q]["chunk"])]
        p(f"  rank or matched chunk differs [derived]: {len(changed)} of {len(order)}")
        p("  entering the top 5 [derived]: " + (", ".join(f"{q} ({fmt(rq[q]['rank'])} -> {fmt(cq[q]['rank'])})" for q in entering) or "none found"))
        p("  leaving the top 5 [derived]: " + (", ".join(f"{q} ({fmt(rq[q]['rank'])} -> {fmt(cq[q]['rank'])})" for q in leaving) or "none found"))
        p("  metrics [observed: hit_at_1, hit_at_3, hit_at_5, mrr; results.slices; results.tickerHitAt5]")
        for m in METRICS:
            p(f"    {m}: {ref} {stored(data[ref][3], m)}, {cand} {stored(data[cand][3], m)}")
        for sl in ("figure", "nonFigure"):
            a, b = data[ref][3]["results"]["slices"][sl], data[cand][3]["results"]["slices"][sl]
            p(f"    slices.{sl} (questions {a['questionCount']}): " + ", ".join(f"{m} {a[m]} -> {b[m]}" for m in METRICS))
        rt, ct = data[ref][3]["results"]["tickerHitAt5"], data[cand][3]["results"]["tickerHitAt5"]
        p("    tickerHitAt5: " + ", ".join(f"{t} {rt[t]} -> {fmt(ct.get(t))}" for t in sorted(rt)))
        p(f"  rerank fallbacks [observed]: {ref} {len(fell[ref])}, {cand} {len(fell[cand])}")
        pair = SCORING_PAIR[cand]
        n1, med1, max1 = scoring_times(data[cand][2])
        n0, med0, max0 = scoring_times(data[pair][2])
        p(f"  scoring time per question [derived: elapsedMs of the topK=10 scoring lines]: {cand} {data[cand][2]}: lines {n1}, median {med1} ms, "
          f"maximum {max1} ms; {pair} {data[pair][2]}: lines {n0}, median {med0} ms, maximum {max0} ms")
        p("")
        section += 1

    p(f"{section}. Selection rule against the default reference 598 per row [derived: as the ruleRow check computes; values observed in each snapshot]")
    rule_rows = {}
    for label, ref, cand in ROWS:
        ok_row = not fell[cand] and pair_ok[("598", cand)]
        tag = "selection outcome" if ok_row else (f"NOT a selection outcome (X5): {cand} records {len(fell[cand])} rerank fallback(s)" if fell[cand]
                                                   else "NOT a selection outcome: recorded settings other than the allowed keys differ")
        criteria = ONE.rule(data["598"][3], data[cand][3])
        rule_rows[label] = (ok_row, criteria)
        p(f"  row {label}, {cand} against 598 ({tag}): " + ("meets the rule" if all(ok for _, ok, _ in criteria) else "does not meet the rule"))
        for name, ok, values in criteria:
            p(f"    {name}: {'pass' if ok else 'fail'} ({values})")
    p("")
    with open(os.path.join(HERE, "comparison.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    # claims.json
    claims = []

    def claim(check, basis="derived", **extra):
        c = {"id": f"C-{FIRST_ID + len(claims)}", "basis": basis}
        c.update(extra)
        c["check"] = check
        claims.append(c)

    for label, ref, cand in ROWS:
        if not pair_ok[(ref, cand)]:
            continue
        for run in (ref, cand):
            S = data[run][3]
            for m in METRICS:
                claim({"type": "metric", "snapshot": RUNS[run][0], "metric": m, "expected": stored(S, m)}, basis="observed")
            for sl in ("figure", "nonFigure"):
                claim({"type": "metric", "snapshot": RUNS[run][0], "metric": f"slices.{sl}.hitAt5",
                       "expected": S["results"]["slices"][sl]["hitAt5"]}, basis="observed")
            for t in sorted(S["results"]["tickerHitAt5"]):
                claim({"type": "metric", "snapshot": RUNS[run][0], "metric": f"tickerHitAt5.{t}", "expected": S["results"]["tickerHitAt5"][t]},
                      basis="observed")
        entering, leaving = entering_leaving[label]
        for q in entering + leaving:
            claim({"type": "topK", "question": q, "k": 5, "rows": [
                {"snapshot": RUNS[ref][0], "expected": "inside" if inside(data[ref][5][q]["rank"]) else "outside"},
                {"snapshot": RUNS[cand][0], "expected": "inside" if inside(data[cand][5][q]["rank"]) else "outside"}]})
            for run in (ref, cand):
                x = data[run][5][q]
                if x["bestChunk"] is None or x["entry"] is None:
                    continue
                c = x["entry"]
                exp = {"fusedPosition": c["fusedPosition"]["value"], "rerankInput": c["rerankInput"]["value"],
                       "rerankedPosition": c["rerankedPosition"]["value"]}
                bases = [c[f]["basis"] for f in exp]
                if "unknown" in bases:
                    continue
                claim({"type": "candidate", "report": RUNS[run][1], "question": q, "chunk": x["bestChunk"], "expected": exp},
                      basis="derived" if "derived" in bases else "observed")
        ok_row, criteria = rule_rows[label]
        if ok_row:
            claim({"type": "ruleRow", "reference": RUNS["598"][0], "candidate": RUNS[cand][0],
                   "criteria": {n: ("pass" if ok else "fail") for n, ok, _ in criteria}})
        for q, reason in fell[cand]:
            ev = data[cand][5][q]["ev"]
            chunk = ev["phrases"][0]["chunks"][0]
            if chunk["rerankedPosition"]["basis"] != "unknown":
                raise Stop(f"{q} chunk {chunk['chunkId']} rerankedPosition is not unknown")
            claim({"type": "notRecorded", "report": RUNS[cand][1],
                   "path": f"questions[id={q}].phrases[0].chunks[chunkId={chunk['chunkId']}].rerankedPosition",
                   "reason": chunk["rerankedPosition"]["reason"]}, basis="unknown",
                  text=f"Row {label} does not record a reranked position for chunk {chunk['chunkId']} of {q}, whose rerank outcome is FALLBACK ({reason}).")
    if FIRST_ID + len(claims) - 1 > LAST_ID:
        raise Stop(f"{len(claims)} claims exceed the id range C-{FIRST_ID} to C-{LAST_ID}")

    def value(v):
        if isinstance(v, Decimal):
            return str(v)
        if isinstance(v, dict):
            return "{" + ", ".join(f'"{k}": {value(x)}' for k, x in v.items()) + "}"
        if isinstance(v, list):
            return "[" + ", ".join(value(x) for x in v) + "]"
        return json.dumps(v, ensure_ascii=False)

    label_names = {"613": "the current model at the row A settings", "947": "the current model at the row B settings",
                   A_ID: "the gte model, row A", B_ID: "the gte model, row B", "598": "the default reference"}
    used = set()
    for c in claims:
        for k in ("snapshot", "report", "reference", "candidate"):
            if k in c["check"]:
                used.add(c["check"][k])
        for row in c["check"].get("rows", []):
            used.add(row["snapshot"])
    labels = {}
    for run, name in label_names.items():
        for path in RUNS[run][:2]:
            if path in used:
                labels[path] = name
    lines = ["{",
             '  "description": "Second reranker model (gte-reranker-modernbert-base) against the current model at identical recorded settings (plan'
             ' plans/2026-09-15-reranker-ettin.md, Milestone 2), written by compare_gte.py: per row (A against snapshot 613, B against snapshot 947)'
             ' the metrics, slice and per-ticker hit@5 of both runs, the questions entering or leaving the top 5 with the best accepted chunk of each'
             ' in both evidence reports, and the selection rule against the default reference 598. rerank-timeout-ms is not recorded in snapshots:'
             ' 120000 in rows A and B (plan amendment 1), 2000 in 613 and 4000 in 947 (property-diff.txt).",',
             '  "labels": ' + value(labels) + ",",
             '  "claims": [']
    for i, c in enumerate(claims):
        parts = [f'"id": "{c["id"]}"', f'"basis": "{c["basis"]}"']
        if "text" in c:
            parts.append(f'"text": {value(c["text"])}')
        parts.append(f'"check": {value(c["check"])}')
        lines.append("    {" + ", ".join(parts) + "}" + ("," if i < len(claims) - 1 else ""))
    lines += ["  ]", "}"]
    with open(os.path.join(HERE, "claims.json"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Stop, cr.Stop) as stop:
        print(f"stopped: {stop}", file=sys.stderr)
        sys.exit(2)

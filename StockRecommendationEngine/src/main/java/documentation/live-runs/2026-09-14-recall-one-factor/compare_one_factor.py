#!/usr/bin/env python3
"""One-factor recall experiment, RAG-15 E1 rerun (plan documentation/plans/2026-09-14-retrieval-recall.md, Milestone 2; Follow_Ups RAG-15).

Reads only committed JSON files (paths relative to this script's directory): the snapshots (row_to_json exports) and evidence reports of the
three runs of this directory, 931 (a), 932 (b), 933 (c), and of their committed counterparts 598 and 627 under
../2026-09-13-evaluation-evidence/measurement/. Writes three files next to this script, replacing them:
  property-diff.txt  X2: every recorded property of each pair compared (931 against 598, 932 against 598, 933 against 627);
  comparison.txt     X1 (second comparison, beside the committed TraceReproductionCheck output reproduction-931.txt), X3, X5, and nvda-02 and
                     nvda-04 in every run;
  claims.json        the claims (ids from C-601) whose generated block stands in RAG.md, Retrieval Evaluation, One-factor recall experiment.
No database, no application, no model. Numbers are parsed as written (Decimal). Output is deterministic: a re-run reproduces the three files byte
for byte. Best fused position, candidate recall and the class of a question outside hit@5 are computed by the functions of
../2026-09-14-candidate-recall/candidate_recall.py (imported, not copied). The script states positions, ranks and rule criteria; it states no cause.

Selection rule (RAG.md, Cross-encoder reranker, windowed rows; the check type ruleRow): a candidate row meets the rule against its reference only if
(1) aggregate hit@5 does not decrease, (2) non-figure-slice hit@5 does not decrease, (3) no ticker's hit@5 decreases, and (4) no question of kind
FIGURE ranked 1 to 5 in the reference leaves ranks 1 to 5. X5: a run whose snapshot records a rerank fallback is labelled and its rule values
are printed for the record only, not as a selection outcome, and no ruleRow claim is written for it.

Run: python3 compare_one_factor.py   (then: git diff --exit-code property-diff.txt comparison.txt claims.json)
"""
import importlib.util
import json
import os
import sys
from decimal import Decimal

sys.dont_write_bytecode = True  # the import below leaves no __pycache__ in the candidate-recall directory
HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("candidate_recall", os.path.join(HERE, "../2026-09-14-candidate-recall/candidate_recall.py"))
cr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cr)

M = "../2026-09-13-evaluation-evidence/measurement/"
RUNS = {
    "598": (M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json", M + "evidence-598.json"),
    "627": (M + "snapshot-627-traced-snapshot-298-rerank-candidates-40-timeout-4000.json", M + "evidence-627.json"),
    "931": ("snapshot-931-a-reproduction-of-598.json", "evidence-931.json"),
    "932": ("snapshot-932-b-candidate-count-200-rerank-off.json", "evidence-932.json"),
    "933": ("snapshot-933-c-candidate-count-200-rerank-candidates-40-timeout-4000.json", "evidence-933.json"),
}
NAMES = {"931": "run a", "932": "run b", "933": "run c"}
# (reference, candidate, the one factor the plan varies; None for the reproduction)
PAIRS = [("598", "931", None), ("598", "932", "candidateCount"), ("627", "933", "candidateCount")]
KS = [10, 20, 30, 40]
FOCUS = {"nvda-02": 802, "nvda-04": 754}
FIRST_ID = 601
EXPERIMENT = "experiment-candidate-count-598-932.json"
COLUMNS = ["set_version", "question_count", "window_size"]


def load(path):
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def fmt(v):
    return cr.fmt(v)


def fallbacks(S):
    return [(t["id"], t["trace"]["rerank"].get("fallbackReason")) for t in S["results"]["traces"] if t["trace"]["rerank"]["outcome"] == "FALLBACK"]


def rule(ref, cand):
    """Per criterion (name, pass, values) as the ruleRow check computes them."""
    out = []
    a, b = Decimal(str(ref["hit_at_5"])), Decimal(str(cand["hit_at_5"]))
    out.append(("aggregateHitAt5", b >= a, f"hit@5 {cand['hit_at_5']} against {ref['hit_at_5']}"))
    a, b = ref["results"]["slices"]["nonFigure"]["hitAt5"], cand["results"]["slices"]["nonFigure"]["hitAt5"]
    out.append(("nonFigureHitAt5", Decimal(str(b)) >= Decimal(str(a)), f"non-figure-slice hit@5 {b} against {a}"))
    rt, ct = ref["results"]["tickerHitAt5"], cand["results"]["tickerHitAt5"]
    items, ok = [], bool(rt)
    for t in sorted(rt):
        c = ct.get(t)
        good = c is not None and Decimal(str(c)) >= Decimal(str(rt[t]))
        ok = ok and good
        items.append(f"{t} {fmt(c)} against {rt[t]}{'' if good else ' (below)'}")
    out.append(("tickerHitAt5", ok, ", ".join(items)))
    cranks = {q["id"]: q["rank"] for q in cand["results"]["questions"]}
    left, stay = [], []
    for q in ref["results"]["questions"]:
        if q["kind"] == "FIGURE" and q["rank"] is not None and q["rank"] <= 5:
            r = cranks.get(q["id"])
            (stay if r is not None and r <= 5 else left).append(f"{q['id']} (rank {fmt(r)}, was {q['rank']})")
    out.append(("figureKindTop5", not left, ("left the top 5: " + ", ".join(left)) if left else f"{len(stay)} FIGURE questions in the reference's top 5 stay"))
    return out


def main():
    data = {}
    for run, (s, e) in RUNS.items():
        S, E = load(s), load(e)
        if str(S["id"]) != run or str(E["snapshotId"]) != run:
            print(f"stopped: {s} or {e} does not record id {run}", file=sys.stderr)
            return 2
        qs = {}
        ranks = {q["id"]: q for q in S["results"]["questions"]}
        for q in E["questions"]:
            position, chunk, holding = cr.best(q)
            qs[q["id"]] = {"rank": ranks[q["id"]]["rank"], "chunk": ranks[q["id"]]["matchedChunkId"], "best": position, "bestChunk": chunk,
                           "outcome": q["rerankOutcome"]["value"], "kind": ranks[q["id"]]["kind"], "ev": q}
        data[run] = (s, e, S, E, qs)
    order = [q["id"] for q in data["598"][2]["results"]["questions"]]

    # property-diff.txt (X2)
    d = ["Recorded properties per pair (plan Milestone 2, X2), generated by compare_one_factor.py from the committed snapshots",
         "Compared: the snapshot columns set_version, question_count, window_size and every key of properties (trace included). [observed: the",
         "values as stored; derived: equality]. rerank-timeout-ms and max-length are not recorded in any snapshot, so they are not compared here",
         "(run.log records how each run set them).", ""]
    pair_ok = {}
    for ref, cand, factor in PAIRS:
        R, C = data[ref][2], data[cand][2]
        keys = sorted(set(R["properties"]) | set(C["properties"]))
        diffs = []
        for col in COLUMNS:
            if R[col] != C[col]:
                diffs.append(f"{col}: {ref} {fmt(R[col])}, {cand} {fmt(C[col])}")
        equal = []
        for k in keys:
            rv, cv = R["properties"].get(k, "<absent>"), C["properties"].get(k, "<absent>")
            if rv != cv:
                diffs.append(f"properties.{k}: {ref} {fmt(rv)}, {cand} {fmt(cv)}")
            else:
                equal.append(f"{k} {fmt(rv)}")
        expected = [] if factor is None else [factor]
        # Properties that report outcomes of the run, not settings: rerankedQuestions and rerankFallbackQuestions.
        outcome_keys = {"rerankedQuestions", "rerankFallbackQuestions"}
        named = [x for x in diffs if not any(x.startswith(f"properties.{k}:") for k in expected)]
        settings_other = [x for x in named if not any(x.startswith(f"properties.{k}:") for k in outcome_keys)]
        pair_ok[(ref, cand)] = not named
        outcome_only = [x for x in named if x not in settings_other]
        d.append(f"{cand} ({NAMES[cand]}) against {ref}; the plan's factor: {factor or 'none (reproduction)'}")
        d.append("  differing: " + ("; ".join(diffs) if diffs else "none found"))
        d.append("  other than the factor, settings: " + ("; ".join(settings_other) if settings_other else "none found")
                 + "; outcome counts: " + ("; ".join(outcome_only) or "none found"))
        d.append("  X2: " + ("the recorded properties differ only in the plan's factor" if not named else
                             "properties other than the plan's factor differ (named above), so no claim compares this pair"))
        d.append("  equal: " + ", ".join(equal) + "; " + ", ".join(f"{c} {fmt(R[c])}" for c in COLUMNS if R[c] == C[c]))
        d.append("")
    with open(os.path.join(HERE, "property-diff.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(d) + "\n")

    out = []
    p = out.append
    p("One-factor recall experiment, RAG-15 E1 rerun (plan documentation/plans/2026-09-14-retrieval-recall.md, Milestone 2)")
    p("Generated by compare_one_factor.py from committed files only (paths relative to the script's directory):")
    for run, (s, e) in RUNS.items():
        p(f"  {run}{' (' + NAMES[run] + ')' if run in NAMES else ''}: {s}; {e}")
    p("Labels: [observed] a value as stored (file and field named); [derived] computed by this script (computation named). No cause is stated.")
    p("")

    p("1. Settings and outcomes [observed: properties.candidateCount, rerank, rerankCandidates, rerankedQuestions, rerankFallbackQuestions; "
      "derived: trace outcome counts]")
    for run in RUNS:
        S = data[run][2]
        pr = S["properties"]
        counts = {}
        for t in S["results"]["traces"]:
            counts[t["trace"]["rerank"]["outcome"]] = counts.get(t["trace"]["rerank"]["outcome"], 0) + 1
        p(f"  {run}: candidateCount {pr['candidateCount']}, rerank {fmt(pr['rerank'])}, rerankCandidates {pr['rerankCandidates']}, rerankedQuestions "
          f"{pr['rerankedQuestions']}, rerankFallbackQuestions {pr['rerankFallbackQuestions']}; trace outcomes "
          + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    p("  X2 (property-diff.txt): " + "; ".join(f"{c} against {r}: {'only the factor differs' if pair_ok[(r, c)] else 'other properties differ, no claim compares the pair'}"
                                              for r, c, _ in PAIRS))
    p("")

    p("2. X5: rerank fallbacks per run [observed: results.traces[].trace.rerank.outcome FALLBACK and fallbackReason]")
    fell = {}
    for run in RUNS:
        fb = fallbacks(data[run][2])
        fell[run] = fb
        p(f"  {run}: {len(fb)}" + (": " + ", ".join(f"{q} ({r})" for q, r in fb) if fb else ""))
    p("")

    p("3. X1: run a (931) against 598, rank and matched chunk per question [observed: results.questions rank, matchedChunkId; derived: equality]")
    diffs = [f"{q}: rank {fmt(data['598'][4][q]['rank'])} -> {fmt(data['931'][4][q]['rank'])}, chunk {fmt(data['598'][4][q]['chunk'])} -> "
             f"{fmt(data['931'][4][q]['chunk'])}" for q in order
             if (data["598"][4][q]["rank"], data["598"][4][q]["chunk"]) != (data["931"][4][q]["rank"], data["931"][4][q]["chunk"])]
    p(f"  questions compared {len(order)}; differing: " + ("; ".join(diffs) if diffs else "none found"))
    for m in ["hit_at_1", "hit_at_3", "hit_at_5", "mrr"]:
        p(f"  {m}: 598 {data['598'][2][m]}, 931 {data['931'][2][m]}")
    p("")

    p("4. X3: candidate recall [derived: candidate_recall.share over candidate_recall.best, as Milestone 1]")
    p("  run  " + "  ".join(f"recall@{k:<2} (n)" for k in KS) + "  whole list (n)")
    for run in RUNS:
        qs = data[run][4]
        cells = [f"{cr.share(sum(1 for q in qs.values() if q['best'] is not None and q['best'] <= k), len(qs))} "
                 f"({sum(1 for q in qs.values() if q['best'] is not None and q['best'] <= k):>2})" for k in KS]
        n = sum(1 for q in qs.values() if q["best"] is not None)
        cells.append(f"{cr.share(n, len(qs))} ({n:>2})")
        p(f"  {run}  " + "  ".join(cells))
    p("")

    p("5. X3: per question, best fused position / rank in both runs of each pair [derived: best fused position; observed: rank]; '-' null;")
    p("   '*' marks a row where either value differs within the pair; class of a question outside hit@5 in the later run as Milestone 1")
    p("  question  kind        598       932   |    627       933")
    for q in order:
        def cell(run):
            x = data[run][4][q]
            return f"{fmt(x['best']) if x['best'] is not None else '-'}/{fmt(x['rank']) if x['rank'] is not None else '-'}"
        marks = []
        for r, c in (("598", "932"), ("627", "933")):
            a, b = data[r][4][q], data[c][4][q]
            marks.append("*" if (a["best"], a["rank"]) != (b["best"], b["rank"]) else " ")
        cls = []
        for run in ("932", "933"):
            x = data[run][4][q]
            if x["rank"] is None or x["rank"] > 5:
                cls.append(f"{run}: {cr.klass(x['best'])}")
        p(f"  {q:<8}  {data['598'][4][q]['kind']:<9} {cell('598'):>7}  {cell('932'):>7} {marks[0]} | {cell('627'):>7}  {cell('933'):>7} {marks[1]}"
          + ("  outside hit@5: " + "; ".join(cls) if cls else ""))
    p("")

    p("6. nvda-02 and nvda-04 [observed: evidence report fusedPosition, rerankInput (derived in the report for reranked runs), rerankedPosition, "
      "returnedPosition; snapshot rank]")
    for run in RUNS:
        for q, chunk in FOCUS.items():
            x = data[run][4][q]
            c = next(c for ph in x["ev"]["phrases"] for c in ph["chunks"] if c["chunkId"] == chunk)
            p(f"  {run} {q} chunk {chunk}: fusedPosition {fmt(c['fusedPosition']['value'])}, rerankInput {fmt(c['rerankInput']['value'])} "
              f"({c['rerankInput']['basis']}), rerankedPosition {fmt(c['rerankedPosition']['value'])} ({c['rerankedPosition']['basis']}), "
              f"returnedPosition {fmt(c['returnedPosition']['value'])}; rank {fmt(x['rank'])}, rerank outcome {x['outcome']}")
    p("")

    p("7. Selection rule per criterion [derived: as the ruleRow check computes; values observed in each snapshot]")
    for ref, cand in (("598", "627"), ("932", "933")):
        label = "selection outcome" if not fell[cand] and not fell[ref] else \
            f"NOT a selection outcome (X5): {cand} records {len(fell[cand])} rerank fallback(s); values printed for the record only"
        p(f"  {cand} against {ref} ({label}):")
        for name, ok, values in rule(data[ref][2], data[cand][2]):
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

    for run in ("932", "933"):
        s, e, S, E, qs = data[run]
        for k in KS:
            claim({"type": "candidateRecall", "report": e, "k": k,
                   "expected": cr.share(sum(1 for q in qs.values() if q["best"] is not None and q["best"] <= k), len(qs))})
        claim({"type": "candidateRecall", "report": e, "k": "all", "expected": cr.share(sum(1 for q in qs.values() if q["best"] is not None), len(qs))})
        for q in order:
            if qs[q]["rank"] is None or qs[q]["rank"] > 5:
                claim({"type": "bestFusedPosition", "report": e, "question": q, "expected": qs[q]["best"]})
    for q, chunk in FOCUS.items():
        for run in ("932", "933"):
            s, e, S, E, qs = data[run]
            c = next(c for ph in qs[q]["ev"]["phrases"] for c in ph["chunks"] if c["chunkId"] == chunk)
            exp = {"fusedPosition": c["fusedPosition"]["value"], "rerankInput": c["rerankInput"]["value"]}
            if run == "933":
                exp["rerankedPosition"] = c["rerankedPosition"]["value"]
            basis = "derived" if any(c[f]["basis"] == "derived" for f in exp) else "observed"
            claim({"type": "candidate", "report": e, "question": q, "chunk": chunk, "expected": exp}, basis=basis)
        # X2: 933 and 627 differ in recorded outcome counts besides the factor, so no claim lists them together; 598 and 932 do not.
        for runs in (("598", "932"), ("933",)):
            claim({"type": "topK", "question": q, "k": 5, "rows": [
                {"snapshot": RUNS[run][0], "expected": "inside" if data[run][4][q]["rank"] is not None and data[run][4][q]["rank"] <= 5 else "outside"}
                for run in runs]})
        before = data["598"][4][q]["best"]
        after = data["932"][4][q]["best"]
        if pair_ok[("598", "932")]:
            claim({"type": "candidate", "report": RUNS["932"][1], "question": q, "chunk": chunk, "expected": {"fusedPosition": after}},
                  basis="experiment", experiment={"file": EXPERIMENT, "factor": "candidateCount"},
                  text=f"With candidateCount 200 in run b against 40 in snapshot 598 and the other recorded settings equal, chunk {chunk} of {q} "
                       f"was at fused position {after} in run b and at fused position {before} in snapshot 598.")
    criteria = {n: ("pass" if ok else "fail") for n, ok, _ in rule(data["598"][2], data["627"][2])}
    claim({"type": "ruleRow", "reference": RUNS["598"][0], "candidate": RUNS["627"][0], "criteria": criteria})
    for q, reason in fell["933"]:
        ev = data["933"][4][q]["ev"]
        chunk = ev["phrases"][0]["chunks"][0]
        rp = chunk["rerankedPosition"]
        if rp["basis"] != "unknown":
            print(f"stopped: {q} chunk {chunk['chunkId']} rerankedPosition is not unknown", file=sys.stderr)
            return 2
        claim({"type": "notRecorded", "report": RUNS["933"][1], "path": f"questions[id={q}].phrases[0].chunks[chunkId={chunk['chunkId']}].rerankedPosition",
               "reason": rp["reason"]}, basis="unknown",
              text=f"Run c does not record a reranked position for chunk {chunk['chunkId']} of {q}, whose rerank outcome is FALLBACK ({reason}).")

    def value(v):
        if isinstance(v, Decimal):
            return str(v)
        if isinstance(v, dict):
            return "{" + ", ".join(f'"{k}": {value(x)}' for k, x in v.items()) + "}"
        if isinstance(v, list):
            return "[" + ", ".join(value(x) for x in v) + "]"
        return json.dumps(v, ensure_ascii=False)

    labels = {RUNS[r][0]: NAMES[r] for r in ("932", "933")}
    labels.update({RUNS[r][1]: NAMES[r] for r in ("932", "933")})
    lines = ["{",
             '  "description": "One-factor recall experiment, RAG-15 E1 rerun (plan plans/2026-09-14-retrieval-recall.md, Milestone 2), written by'
             ' compare_one_factor.py: candidate recall and the best fused position of every question outside hit@5 for runs b (snapshot 932) and c'
             ' (snapshot 933); nvda-02 and nvda-04 in runs b and c and their top-5 membership in 598, 932, 627 and 933; the selection rule for 627'
             ' against 598; the rerank fallback of run c. Runs b and c ran with the cross-encoder loaded; rerank-timeout-ms was 4000 in run c'
             ' (run.log).",',
             '  "labels": ' + value(labels) + ",",
             '  "claims": [']
    for i, c in enumerate(claims):
        parts = [f'"id": "{c["id"]}"', f'"basis": "{c["basis"]}"']
        if "text" in c:
            parts.append(f'"text": {value(c["text"])}')
        if "experiment" in c:
            parts.append(f'"experiment": {value(c["experiment"])}')
        parts.append(f'"check": {value(c["check"])}')
        lines.append("    {" + ", ".join(parts) + "}" + ("," if i < len(claims) - 1 else ""))
    lines += ["  ]", "}"]
    with open(os.path.join(HERE, "claims.json"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except cr.Stop as stop:
        print(f"stopped: {stop}", file=sys.stderr)
        sys.exit(2)

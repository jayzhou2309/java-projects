#!/usr/bin/env python3
"""One-factor recall experiment, RAG-15 E1 rerun (plan documentation/plans/2026-09-14-retrieval-recall.md, Milestone 2; Follow_Ups RAG-15).

Reads only committed JSON files (paths relative to this script's directory): the snapshots (row_to_json exports) and evidence reports of the
runs of this directory, 931 (a), 932 (b), 933 (c, with a rerank fallback), 947 (the repeat of c under plan amendment 2), and of their committed counterparts 598 and 627 under
../2026-09-13-evaluation-evidence/measurement/. Writes three files next to this script, replacing them:
  property-diff.txt  X2: every recorded property of each pair compared (931 against 598, 932 against 598, 933 and 947 against 627, 947 against 932,
                     947 against 598), and the rerank-timeout-ms of each run as run.log records it (snapshots do not record it);
  comparison.txt     X1 (second comparison, beside the committed TraceReproductionCheck output reproduction-931.txt), X3, X5, and nvda-02 and
                     nvda-04 in every run;
  claims.json        the claims (ids from C-601) whose generated block stands in RAG.md, Retrieval Evaluation, One-factor recall experiment.
No database, no application, no model. Numbers are parsed as written (Decimal). Output is deterministic: a re-run reproduces the three files byte
for byte. Best fused position, candidate recall and the class of a question outside hit@5 are computed by the functions of
../2026-09-14-candidate-recall/candidate_recall.py (imported, not copied). The script states positions, ranks and rule criteria; it states no cause.

Selection rule (RAG.md, Cross-encoder reranker, windowed rows; the check type ruleRow): a candidate row meets the rule against its reference only if
(1) aggregate hit@5 does not decrease, (2) non-figure-slice hit@5 does not decrease, (3) no ticker's hit@5 decreases, and (4) no question of kind
FIGURE ranked 1 to 5 in the reference leaves ranks 1 to 5. X5: a run whose snapshot records a rerank fallback is labelled and its rule values
are printed for the record only, not as a selection outcome, and no ruleRow claim is written for it. Plan amendment 3: a selection outcome is a row
judged against the default reference 598 (reranking off, candidate-count 40); a row judged against another reranked configuration (627) is printed for the
record only and no ruleRow claim is written for it. Claim id C-656, a ruleRow of 947 against 627 in commit 32b100d, was removed under amendment 3 and is
not given to another claim (RETIRED_IDS).

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
    "947": ("snapshot-947-c-repeat-candidate-count-200-rerank-candidates-40-timeout-4000.json", "evidence-947.json"),
}
NAMES = {"931": "run a", "932": "run b", "933": "run c with a rerank fallback", "947": "the run c repeat"}
# (reference, candidate, the settings the plan varies between them ([] for the reproduction), the recorded outcome counts and columns that differ
# with reranking on in one run and off in the other and are named as such). 947 against 932 is the plan's comparison of (c) with (b) and 947 against
# 598 the selection row of plan amendment 3; neither is a one-factor pair.
RERANK_OUTCOMES = ["rerankedQuestions", "retrieval_strategy"]
PAIRS = [("598", "931", [], []), ("598", "932", ["candidateCount"], []), ("627", "933", ["candidateCount"], []), ("627", "947", ["candidateCount"], []),
         ("932", "947", ["rerank", "rerankCandidates"], RERANK_OUTCOMES), ("598", "947", ["candidateCount", "rerank", "rerankCandidates"], RERANK_OUTCOMES)]
# rerank-timeout-ms per run, not recorded in snapshots: run.log of this directory for 931 to 947 (warm-up and "Reranking filing candidates" lines,
# RAG_RETRIEVAL_RERANK_TIMEOUT_MS overrides); ../2026-09-13-evaluation-evidence/measurement/run.log for 598 ("timeout default", 2000) and 627 (4000).
TIMEOUT_MS = {"598": 2000, "627": 4000, "931": 2000, "932": 2000, "933": 4000, "947": 4000}
RETIRED_IDS = {656}

EXPERIMENT_C = "experiment-candidate-count-627-947.json"
KS = [10, 20, 30, 40]
FOCUS = {"nvda-02": 802, "nvda-04": 754}
FIRST_ID = 601
EXPERIMENT = "experiment-candidate-count-598-932.json"
COLUMNS = ["set_version", "question_count", "window_size", "retrieval_strategy"]


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
         "Compared: the snapshot columns set_version, question_count, window_size, retrieval_strategy and every key of properties (trace included).",
         "[observed: the values as stored; derived: equality]. rerank-timeout-ms and max-length are not recorded in any snapshot; the rerank-timeout-ms",
         "of each run is printed as run.log records it (TIMEOUT_MS in the script) and named in the X2 line when it differs; max-length is not compared.",
         "Outcome counts and columns (rerankedQuestions, rerankFallbackQuestions, retrieval_strategy) are named apart from settings.", ""]
    pair_ok = {}
    pair_note = {}
    for ref, cand, factors, following in PAIRS:
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
        # Recorded values that report outcomes of the run, not settings.
        outcome_keys = ["rerankedQuestions", "rerankFallbackQuestions", "retrieval_strategy"]

        def is_key(x, k):
            return x.startswith(f"properties.{k}:") or x.startswith(f"{k}:")

        named = [x for x in diffs if not any(is_key(x, k) for k in factors)]
        settings_other = [x for x in named if not any(is_key(x, k) for k in outcome_keys)]
        outcome_only = [x for x in named if x not in settings_other]
        outcome_following = [x for x in outcome_only if any(is_key(x, k) for k in following)]
        outcome_other = [x for x in outcome_only if x not in outcome_following]
        pair_ok[(ref, cand)] = not settings_other and not outcome_other
        timeout_differs = TIMEOUT_MS[ref] != TIMEOUT_MS[cand]
        d.append(f"{cand} ({NAMES[cand]}) against {ref}; the plan varies: {', '.join(factors) or 'nothing (reproduction)'}")
        d.append("  differing: " + ("; ".join(diffs) if diffs else "none found"))
        d.append("  other than the factor, settings: " + ("; ".join(settings_other) if settings_other else "none found")
                 + "; outcome counts and columns: " + ("; ".join(outcome_only) or "none found"))
        d.append(f"  rerank-timeout-ms (not recorded; run.log): {ref} {TIMEOUT_MS[ref]}, {cand} {TIMEOUT_MS[cand]}")
        if not pair_ok[(ref, cand)]:
            x2 = "properties other than what the plan varies differ (named above), so no claim compares this pair"
        else:
            x2 = "the recorded settings differ only in what the plan varies"
            if outcome_following:
                x2 += ("; the recorded outcome values " + ", ".join(x.split(":")[0] for x in outcome_following)
                       + " also differ, named as outcomes of reranking being on in one run and off in the other")
            if not outcome_following and not timeout_differs:
                x2 = "the recorded properties differ only in what the plan varies"
        if timeout_differs:
            x2 += f"; rerank-timeout-ms, not recorded in the snapshots, also differs ({ref} {TIMEOUT_MS[ref]}, {cand} {TIMEOUT_MS[cand]}; run.log)"
        pair_note[(ref, cand)] = bool(outcome_following) or timeout_differs
        d.append("  X2: " + x2)
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
    p("  X2 (property-diff.txt): " + "; ".join(f"{c} against {r}: {'recorded settings: only what the plan varies differs' + (' (outcome values and rerank-timeout-ms also differ, named there)' if pair_note[(r, c)] else '') if pair_ok[(r, c)] else 'other properties differ, no claim compares the pair'}"
                                              for r, c, _, _ in PAIRS))
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
    p("   '*' marks a row where either value differs from the pair's reference (598 for 932, 627 for 933 and for 947); class of a question outside hit@5 in the later run as Milestone 1")
    p("  question  kind        598       932   |    627       933       947")
    for q in order:
        def cell(run):
            x = data[run][4][q]
            return f"{fmt(x['best']) if x['best'] is not None else '-'}/{fmt(x['rank']) if x['rank'] is not None else '-'}"
        marks = []
        for r, c in (("598", "932"), ("627", "933"), ("627", "947")):
            a, b = data[r][4][q], data[c][4][q]
            marks.append("*" if (a["best"], a["rank"]) != (b["best"], b["rank"]) else " ")
        cls = []
        for run in ("932", "933", "947"):
            x = data[run][4][q]
            if x["rank"] is None or x["rank"] > 5:
                cls.append(f"{run}: {cr.klass(x['best'])}")
        p(f"  {q:<8}  {data['598'][4][q]['kind']:<9} {cell('598'):>7}  {cell('932'):>7} {marks[0]} | {cell('627'):>7}  {cell('933'):>7} {marks[1]} {cell('947'):>7} {marks[2]}"
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
    kinds = {("598", "627"): "selection outcome: judged against the default reference 598 (plan amendment 3)",
             ("932", "933"): "the plan's comparison of (c) with (b); not a selection outcome under plan amendment 3",
             ("932", "947"): "the plan's comparison of (c) with (b); not a selection outcome: selection outcomes are judged against 598 (plan amendment 3)",
             ("627", "947"): "NOT a selection outcome (plan amendment 3): 627 is a reranked configuration, not the default reference 598; "
                             "values printed for the record only, no claim",
             ("598", "947"): "selection outcome: judged against the default reference 598 (plan amendment 3)"}
    for ref, cand in (("598", "627"), ("932", "933"), ("932", "947"), ("627", "947"), ("598", "947")):
        label = kinds[(ref, cand)] if not fell[cand] and not fell[ref] else \
            f"NOT a selection outcome (X5): {cand} records {len(fell[cand])} rerank fallback(s); values printed for the record only"
        p(f"  {cand} against {ref} ({label}):")
        for name, ok, values in rule(data[ref][2], data[cand][2]):
            p(f"    {name}: {'pass' if ok else 'fail'} ({values})")
    p("")

    with open(os.path.join(HERE, "comparison.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    # claims.json
    claims = []

    next_id = [FIRST_ID]

    def claim(check, basis="derived", **extra):
        while next_id[0] in RETIRED_IDS:
            next_id[0] += 1
        c = {"id": f"C-{next_id[0]}", "basis": basis}
        next_id[0] += 1
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
              text=f"Run c with a rerank fallback does not record a reranked position for chunk {chunk['chunkId']} of {q}, whose rerank outcome is FALLBACK ({reason}).")

    # Plan amendment 2: the repeat of run (c), claims from C-639.
    s947, e947, S947, E947, q947 = data["947"]
    if fell["947"]:
        for q, reason in fell["947"]:
            chunk = q947[q]["ev"]["phrases"][0]["chunks"][0]
            claim({"type": "notRecorded", "report": e947, "path": f"questions[id={q}].phrases[0].chunks[chunkId={chunk['chunkId']}].rerankedPosition",
                   "reason": chunk["rerankedPosition"]["reason"]}, basis="unknown",
                  text=f"The run c repeat does not record a reranked position for chunk {chunk['chunkId']} of {q}, whose rerank outcome is FALLBACK ({reason}).")
    for k in KS:
        claim({"type": "candidateRecall", "report": e947, "k": k,
               "expected": cr.share(sum(1 for q in q947.values() if q["best"] is not None and q["best"] <= k), len(q947))})
    claim({"type": "candidateRecall", "report": e947, "k": "all", "expected": cr.share(sum(1 for q in q947.values() if q["best"] is not None), len(q947))})
    for q in order:
        if q947[q]["rank"] is None or q947[q]["rank"] > 5:
            claim({"type": "bestFusedPosition", "report": e947, "question": q, "expected": q947[q]["best"]})
    for q, chunk in FOCUS.items():
        c = next(c for ph in q947[q]["ev"]["phrases"] for c in ph["chunks"] if c["chunkId"] == chunk)
        exp = {"fusedPosition": c["fusedPosition"]["value"], "rerankInput": c["rerankInput"]["value"], "rerankedPosition": c["rerankedPosition"]["value"]}
        basis = "derived" if any(c[f]["basis"] == "derived" for f in exp) else "observed"
        claim({"type": "candidate", "report": e947, "question": q, "chunk": chunk, "expected": exp}, basis=basis)
        inside = lambda run: "inside" if data[run][4][q]["rank"] is not None and data[run][4][q]["rank"] <= 5 else "outside"
        if pair_ok[("627", "947")] and not fell["947"]:
            r627, r947 = data["627"][4][q]["rank"], data["947"][4][q]["rank"]
            where = lambda r: "was inside the top 5" if r is not None and r <= 5 else "was outside the top 5"
            claim({"type": "topK", "question": q, "k": 5, "rows": [{"snapshot": RUNS["627"][0], "expected": inside("627")},
                                                                   {"snapshot": s947, "expected": inside("947")}]},
                  basis="experiment", experiment={"file": EXPERIMENT_C, "factor": "candidateCount"},
                  text=f"With candidateCount 200 in the run c repeat against 40 in snapshot 627, reranking at 40 candidates in both and the other "
                       f"recorded settings equal, {q} {where(r947)} in the run c repeat and {where(r627)} in snapshot 627.")
        else:
            claim({"type": "topK", "question": q, "k": 5, "rows": [{"snapshot": s947, "expected": inside("947")}]})
    if not fell["947"] and not fell["932"]:
        claim({"type": "ruleRow", "reference": RUNS["932"][0], "candidate": s947,
               "criteria": {n: ("pass" if ok else "fail") for n, ok, _ in rule(data["932"][2], S947)}})
        # Plan amendment 3: the selection row of the repeat is judged against the default reference 598; no ruleRow against 627.
        if not fell["598"]:
            claim({"type": "ruleRow", "reference": RUNS["598"][0], "candidate": s947,
                   "criteria": {n: ("pass" if ok else "fail") for n, ok, _ in rule(data["598"][2], S947)}})

    def value(v):
        if isinstance(v, Decimal):
            return str(v)
        if isinstance(v, dict):
            return "{" + ", ".join(f'"{k}": {value(x)}' for k, x in v.items()) + "}"
        if isinstance(v, list):
            return "[" + ", ".join(value(x) for x in v) + "]"
        return json.dumps(v, ensure_ascii=False)

    labels = {RUNS[r][0]: NAMES[r] for r in ("932", "933", "947")}
    labels.update({RUNS[r][1]: NAMES[r] for r in ("932", "933", "947")})
    lines = ["{",
             '  "description": "One-factor recall experiment, RAG-15 E1 rerun (plan plans/2026-09-14-retrieval-recall.md, Milestone 2), written by'
             ' compare_one_factor.py: candidate recall and the best fused position of every question outside hit@5 for runs b (snapshot 932) and c'
             ' (snapshot 933); nvda-02 and nvda-04 in runs b and c and their top-5 membership in 598, 932, 627 and 933; the selection rule for 627'
             ' against 598; the rerank fallback of run c (snapshot 933). Then, under plan amendment 2, the repeat of run c (snapshot 947): candidate'
             ' recall, best fused positions outside hit@5, nvda-02 and nvda-04, the rule row against run b, and, under plan amendment 3, the'
             ' selection rule against the default reference 598 (id C-656, a rule row against 627, removed and not reused). Runs b, c and the repeat ran with the cross-encoder loaded; rerank-timeout-ms was 4000 in run c and its repeat (run.log).",',
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

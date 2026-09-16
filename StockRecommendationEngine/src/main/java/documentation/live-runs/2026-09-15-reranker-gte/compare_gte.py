#!/usr/bin/env python3
"""Second reranker model against the current model (plan documentation/plans/2026-09-15-reranker-ettin.md, Milestone 2 contract and amendment 3;
Follow_Ups RAG-22).

Reads only committed files (paths relative to this script's directory): the snapshots (row_to_json exports), evidence reports and scoring log
extracts of the runs R (current model at the settings of 947, reproduction of 947), R_A (current model at the settings of row A), A and B
(gte-reranker-modernbert-base) of this directory, and of the committed current-model runs 598 and 613 (../2026-09-13-evaluation-evidence/measurement/)
and 947 (../2026-09-14-recall-one-factor/). Pairs (amendment 3): row A with R_A and row B with R, each from the same session; 613 and 947 are
earlier recorded references, printed but not compared by any claim. Writes three files next to this script, replacing them:
  property-diff.txt  G1: every recorded property of R against 947, R_A against 613, A against R_A, B against R, A against 613 and B against 947
                     (earlier references), A and B against 598, and each run's rerank-timeout-ms with its source (snapshots do not record it);
  comparison.txt     G2, G3 and G7: settings and fallbacks, R against 947 per question, candidate-list identity per pair (fused order, rerank
                     input set, rerank inputs' fused positions, naming every differing question), per row every question's rank, matched chunk
                     and best accepted chunk with its fused and reranked position in both runs, questions entering and leaving the top 5 (questions
                     whose rerank input set differs within the pair excluded and named), metrics over all questions and over the questions with
                     identical rerank input sets, slices, per-ticker hit@5, scoring time per question from the scoring logs of the row and its
                     paired run, and the selection rule against 598;
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
  fused order          a trace's fused chunk ids ordered by fused position; rerank input set: the chunk ids of the trace's rerank inputs;
                       rerank inputs' fused positions: each rerank input's chunk id with its fused position (as the candidateLists check type).
  metrics over a question set  hit@k: questions with a stored rank at most k divided by the questions, half up to six places; MRR: each 1/rank half
                       up to twelve places, summed, divided by the questions, half up to six places (a null rank counts 0); over all questions
                       the script stops unless these equal the stored values.
Claim ids: the claims of commit 31eae86 (C-901 to C-965, paired with 613 and 947) were replaced under amendment 3 and the ids reassigned in order
from C-901; no document other than the generated block of RAG.md cited them (run.log, remediation round 1).

Run: python3 compare_gte.py   (then: git diff --exit-code property-diff.txt comparison.txt claims.json)
"""
import importlib.util
import json
import os
import re
import sys
from decimal import ROUND_HALF_UP, Decimal

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
R_ID, RA_ID, A_ID, B_ID = "1366", "1401", "1367", "1368"
RUNS = {
    "598": (M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json", M + "evidence-598.json", M + "scoring-598.txt"),
    "613": (M + "snapshot-613-traced-snapshot-297-rerank-candidates-20.json", M + "evidence-613.json", M + "scoring-613.txt"),
    "947": (F + "snapshot-947-c-repeat-candidate-count-200-rerank-candidates-40-timeout-4000.json", F + "evidence-947.json", F + "scoring-947.txt"),
    R_ID: (f"snapshot-{R_ID}-r-current-model-reproduction-of-947.json", f"evidence-{R_ID}.json", f"scoring-{R_ID}.txt"),
    RA_ID: (f"snapshot-{RA_ID}-ra-current-model-candidate-count-40-rerank-candidates-20.json", f"evidence-{RA_ID}.json", f"scoring-{RA_ID}.txt"),
    A_ID: (f"snapshot-{A_ID}-a-gte-candidate-count-40-rerank-candidates-20.json", f"evidence-{A_ID}.json", f"scoring-{A_ID}.txt"),
    B_ID: (f"snapshot-{B_ID}-b-gte-candidate-count-200-rerank-candidates-40.json", f"evidence-{B_ID}.json", f"scoring-{B_ID}.txt"),
}
NAMES = {"598": "default reference, reranking off", "613": "earlier recorded run, current model, row A settings",
         "947": "earlier recorded run, current model, row B settings", R_ID: "run R, current model, row B settings, reproduction of 947",
         RA_ID: "run R_A, current model, row A settings", A_ID: "row A, gte model", B_ID: "row B, gte model"}
# rerank-timeout-ms per run, not recorded in snapshots, with its source.
TIMEOUT_MS = {"598": 2000, "613": 2000, "947": 4000, R_ID: 4000, RA_ID: 2000, A_ID: 120000, B_ID: 120000}
TIMEOUT_SOURCE = {"598": "application.yaml default at 432f456, no override recorded (../2026-09-14-recall-one-factor/run.log, L1)",
                  "613": "application.yaml default at 432f456 ('timeout default', ../2026-09-13-evaluation-evidence/measurement/run.log, Run 297)",
                  "947": "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000 (../2026-09-14-recall-one-factor/run.log, Run c repeat)",
                  R_ID: "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000 and the timeoutMs=4000 log lines (run.log, run R)",
                  RA_ID: "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=2000 and the timeoutMs=2000 log lines (run.log, run R_A)",
                  A_ID: "application-reranker-gte.yaml rerank-timeout-ms 120000 and the timeoutMs=120000 log lines (run.log, run A)",
                  B_ID: "application-reranker-gte.yaml rerank-timeout-ms 120000 and the timeoutMs=120000 log lines (run.log, run B)"}
# (reference, candidate, recorded keys allowed to differ as the factor, pair kind)
MODEL = ["reranker", "rerankerVersion"]
OUTCOMES = ["rerankedQuestions", "rerankFallbackQuestions", "retrieval_strategy"]
PAIRS = [("947", R_ID, [], "reproduction"), ("613", RA_ID, [], "run R_A against the earlier recorded run at the same settings"),
         (RA_ID, A_ID, MODEL, "row A against its paired same-session current-model run R_A"),
         (R_ID, B_ID, MODEL, "row B against its paired same-session current-model run R"),
         ("613", A_ID, MODEL, "row A against the earlier recorded reference 613 (not a comparison pair)"),
         ("947", B_ID, MODEL, "row B against the earlier recorded reference 947 (not a comparison pair)"),
         ("598", A_ID, MODEL + ["rerank"], "selection reference for row A"),
         ("598", B_ID, MODEL + ["rerank", "candidateCount", "rerankCandidates"], "selection reference for row B")]
ROWS = [("A", RA_ID, A_ID), ("B", R_ID, B_ID)]
# (reference, candidate, what the pair is) for the G7 candidate-list identity section; only the two ROWS pairs are comparison pairs.
IDENTITY = [(RA_ID, A_ID, "row A pair"), (R_ID, B_ID, "row B pair"), ("613", RA_ID, "earlier recorded reference, same settings"),
            ("947", R_ID, "earlier recorded reference, same settings"), ("613", A_ID, "earlier recorded reference, not a comparison pair"),
            ("947", B_ID, "earlier recorded reference, not a comparison pair")]
EARLIER = {A_ID: "613", B_ID: "947"}
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


def entries(trace, question, what, path):
    """chunk id -> fused position of a trace list, ordered by fused position (the candidateLists check type's reading)."""
    lst = trace.get("fused") if what == "fused" else (trace.get("rerank") or {}).get("candidates")
    if not isinstance(lst, list):
        raise Stop(f"{path} {question}: no {what} list")
    out = {}
    for e in lst:
        if not isinstance(e.get("chunkId"), int) or not isinstance(e.get("fusedPosition"), int) or e["chunkId"] in out:
            raise Stop(f"{path} {question}: malformed {what} entry {e}")
        out[e["chunkId"]] = e["fusedPosition"]
    return dict(sorted(out.items(), key=lambda kv: (kv[1], kv[0])))


def identity(data, ref, cand, order):
    """G7: per question, fused order, rerank input set and rerank inputs' fused positions of cand against ref; also the trace removed lists
    (None when either snapshot does not record them)."""
    ta = {t["id"]: t["trace"] for t in data[ref][3]["results"]["traces"]}
    tb = {t["id"]: t["trace"] for t in data[cand][3]["results"]["traces"]}
    fused, sets, positions, removed, unrecorded = [], [], [], [], False
    for q in order:
        if q not in ta or q not in tb:
            raise Stop(f"{q}: no trace in {ref} or {cand}")
        pa, pb = data[ref][0], data[cand][0]
        if list(entries(ta[q], q, "fused", pa)) != list(entries(tb[q], q, "fused", pb)):
            fused.append(q)
        ia, ib = entries(ta[q], q, "input", pa), entries(tb[q], q, "input", pb)
        if set(ia) != set(ib):
            sets.append((q, sorted(set(ia) - set(ib)), sorted(set(ib) - set(ia))))
        if ia != ib:
            positions.append((q, ia, ib))
        if "removed" not in ta[q] or "removed" not in tb[q]:
            unrecorded = True
        elif ta[q]["removed"] != tb[q]["removed"]:
            removed.append(q)
    return fused, sets, positions, None if unrecorded else removed


def over(qs, questions, metric):
    """A metric over the listed questions from stored ranks (see Definitions)."""
    n = Decimal(len(questions))
    q6 = Decimal("0.000001")
    if metric == "mrr":
        total = sum((Decimal(1) / Decimal(qs[q]["rank"])).quantize(Decimal("0.000000000001"), rounding=ROUND_HALF_UP)
                    for q in questions if qs[q]["rank"] is not None)
        return (Decimal(total) / n).quantize(q6, rounding=ROUND_HALF_UP)
    k = {"hitAt1": 1, "hitAt3": 3, "hitAt5": 5}[metric]
    return (Decimal(sum(1 for q in questions if qs[q]["rank"] is not None and qs[q]["rank"] <= k)) / n).quantize(q6, rounding=ROUND_HALF_UP)


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

    p(f"4. Run R_A ({RA_ID}) against the earlier recorded run 613 at the same settings, rank and matched chunk per question [observed: results.questions "
      "rank, matchedChunkId; derived: equality] (for the record; row A is paired with R_A, plan amendment 3)")
    diffs = [f"{q}: rank {fmt(data['613'][5][q]['rank'])} -> {fmt(data[RA_ID][5][q]['rank'])}, chunk {fmt(data['613'][5][q]['chunk'])} -> "
             f"{fmt(data[RA_ID][5][q]['chunk'])}" for q in order
             if (data["613"][5][q]["rank"], data["613"][5][q]["chunk"]) != (data[RA_ID][5][q]["rank"], data[RA_ID][5][q]["chunk"])]
    p(f"  questions compared {len(order)}; differing: " + ("; ".join(diffs) if diffs else "none found"))
    for m in METRICS:
        p(f"  {m}: 613 {stored(data['613'][3], m)}, {RA_ID} {stored(data[RA_ID][3], m)}")
    p("")

    p("5. G7, candidate lists before reranking per pair [observed: results.traces[].trace.fused chunkId and fusedPosition, trace.rerank.candidates "
      "chunkId and fusedPosition; derived: equality per question]")
    p("   fused order: fused chunk ids ordered by fused position; rerank input set: the rerank inputs' chunk ids; input fused positions: each rerank "
      "input's chunk id with its fused position; removed lists: for the record, not checked by a claim")
    ident = {}
    for ref, cand, what in IDENTITY:
        fused, sets, positions, removed = identity(data, ref, cand, order)
        ident[(ref, cand)] = (fused, sets, positions)
        p(f"  {cand} ({NAMES[cand]}) against {ref} ({NAMES[ref]}); {what}")
        p(f"    fused order differs [derived]: {len(fused)} of {len(order)}" + (": " + ", ".join(fused) if fused else ""))
        p(f"    rerank input set differs [derived]: {len(sets)} of {len(order)}"
          + (": " + "; ".join(f"{q} (only in {ref}: {', '.join(map(str, a)) or 'none'}; only in {cand}: {', '.join(map(str, b)) or 'none'})"
                             for q, a, b in sets) if sets else ""))
        p(f"    rerank inputs' fused positions differ [derived]: {len(positions)} of {len(order)}" + (": " + ", ".join(q for q, _, _ in positions) if positions else ""))
        for q, a, b in positions:
            p(f"      {q} {ref}: " + " ".join(f"{c}@{f}" for c, f in a.items()))
            p(f"      {q} {cand}: " + " ".join(f"{c}@{f}" for c, f in b.items()))
        p("    trace removed lists differ [observed: trace.removed; derived: equality]: " + (
            "not compared (a snapshot of the pair records no removed lists)" if removed is None
            else f"{len(removed)} of {len(order)}" + (": " + ", ".join(removed) if removed else "")))
    p("")

    section = 6
    entering_leaving = {}
    for label, ref, cand in ROWS:
        rq, cq = data[ref][5], data[cand][5]
        fused_diff, set_diff, pos_diff = ident[(ref, cand)]
        excluded = [q for q, _, _ in set_diff]
        same_inputs = [q for q in order if q not in excluded]
        p(f"{section}. Row {label}: {cand} (gte) against {ref} (current model, same session), per question [observed: snapshot rank, matchedChunkId; "
          "evidence bestAcceptedChunk and its fusedPosition, rerankedPosition; derived: best fused position]")
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
            p(f"  {q:<8}  {rq[q]['kind']:<9} {cell(rq[q]):>42} | {cell(cq[q]):>42} {mark}{' (rerank input set differs)' if q in excluded else ''}")
        entering = [q for q in same_inputs if not inside(rq[q]["rank"]) and inside(cq[q]["rank"])]
        leaving = [q for q in same_inputs if inside(rq[q]["rank"]) and not inside(cq[q]["rank"])]
        entering_leaving[label] = (entering, leaving)
        p(f"  G7 (section 5): fused order differs in {len(fused_diff)}, rerank input set in {len(set_diff)}, rerank inputs' fused positions in "
          f"{len(pos_diff)} of {len(order)} questions; questions excluded from entering and leaving (rerank input set differs): "
          + (", ".join(excluded) if excluded else "none found"))
        changed = [q for q in order if (rq[q]["rank"], rq[q]["chunk"]) != (cq[q]["rank"], cq[q]["chunk"])]
        p(f"  rank or matched chunk differs [derived]: {len(changed)} of {len(order)}")
        p("  entering the top 5 [derived]: " + (", ".join(f"{q} ({fmt(rq[q]['rank'])} -> {fmt(cq[q]['rank'])})" for q in entering) or "none found"))
        p("  leaving the top 5 [derived]: " + (", ".join(f"{q} ({fmt(rq[q]['rank'])} -> {fmt(cq[q]['rank'])})" for q in leaving) or "none found"))
        p("  metrics over all questions [observed: hit_at_1, hit_at_3, hit_at_5, mrr; results.slices; results.tickerHitAt5]")
        for m in METRICS:
            for run in (ref, cand):
                if over(data[run][5], order, m) != stored(data[run][3], m):
                    raise Stop(f"{run} {m}: recomputed {over(data[run][5], order, m)} differs from stored {stored(data[run][3], m)}")
            p(f"    {m}: {ref} {stored(data[ref][3], m)}, {cand} {stored(data[cand][3], m)}")
        for sl in ("figure", "nonFigure"):
            a, b = data[ref][3]["results"]["slices"][sl], data[cand][3]["results"]["slices"][sl]
            p(f"    slices.{sl} (questions {a['questionCount']}): " + ", ".join(f"{m} {a[m]} -> {b[m]}" for m in METRICS))
        rt, ct = data[ref][3]["results"]["tickerHitAt5"], data[cand][3]["results"]["tickerHitAt5"]
        p("    tickerHitAt5: " + ", ".join(f"{t} {rt[t]} -> {fmt(ct.get(t))}" for t in sorted(rt)))
        p(f"  metrics over the {len(same_inputs)} questions with identical rerank input sets [derived: from stored ranks, see Definitions]"
          + (" (every question: equal to the stored metrics above)" if not excluded else ""))
        for m in METRICS:
            p(f"    {m}: {ref} {over(rq, same_inputs, m)}, {cand} {over(cq, same_inputs, m)}")
        p(f"  rerank fallbacks [observed]: {ref} {len(fell[ref])}, {cand} {len(fell[cand])}")
        n1, med1, max1 = scoring_times(data[cand][2])
        n0, med0, max0 = scoring_times(data[ref][2])
        ne, mede, maxe = scoring_times(data[EARLIER[cand]][2])
        p(f"  scoring time per question [derived: elapsedMs of the topK=10 scoring lines]: {cand} {data[cand][2]}: lines {n1}, median {med1} ms, "
          f"maximum {max1} ms; paired run {ref} {data[ref][2]}: lines {n0}, median {med0} ms, maximum {max0} ms; earlier recorded reference "
          f"{EARLIER[cand]} (another session, not paired) {data[EARLIER[cand]][2]}: lines {ne}, median {mede} ms, maximum {maxe} ms")
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
        fused_diff, set_diff, pos_diff = ident[(ref, cand)]
        if set_diff:
            raise Stop(f"row {label}: rerank input sets differ ({', '.join(q for q, _, _ in set_diff)}); no check type states metrics over a question subset")
        for compare, found in (("fusedOrder", fused_diff), ("rerankInputSet", [q for q, _, _ in set_diff]), ("rerankInputPositions", [q for q, _, _ in pos_diff])):
            claim({"type": "candidateLists", "reference": RUNS[ref][0], "candidate": RUNS[cand][0], "compare": compare, "expected": found})
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

    label_names = {RA_ID: "the same-session current model at the row A settings", R_ID: "the same-session current model at the row B settings",
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
             ' plans/2026-09-15-reranker-ettin.md, Milestone 2 with amendment 3), written by compare_gte.py: per row (A against the same-session run'
             ' R_A, snapshot 1401; B against the same-session run R, snapshot 1366) the identity of the candidate lists before reranking, the'
             ' metrics, slice and per-ticker hit@5 of both runs, the questions entering or leaving the top 5 with the best accepted chunk of each'
             ' in both evidence reports, and the selection rule against the default reference 598. rerank-timeout-ms is not recorded in snapshots:'
             ' 120000 in rows A and B (plan amendment 1), 2000 in R_A and 4000 in R (property-diff.txt).",',
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

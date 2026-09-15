#!/usr/bin/env python3
"""Offline rerank blend simulation from committed traces (plan documentation/plans/2026-09-14-rerank-blend.md, Milestone 1; Follow_Ups RAG-21).

Reads only committed JSON files (paths relative to this script's directory): the retrieval set v2 (split strata and question kinds), snapshot 947
and its evidence report (primary: fused lists, rerank inputs with fused and reranked positions, accepted chunk ids), snapshot 932 (the w = 0 check)
and snapshot 598 (the default reference). Writes two files next to this script, replacing them:
  blend-simulation.txt  S1 split check, S2 reproduction checks, the six tuning rows, the chosen point, its held-out test, and the full-42 selection
                        rule against 598 beside it;
  claims.json           the claims (ids from C-801) whose generated block stands in RAG.md, Retrieval Evaluation, Rerank blend simulation.
No database, no application, no model. Output is deterministic: a re-run reproduces both files byte for byte. The script states ranks and metrics
of a simulated order; it states no cause for any reranker move.

Frozen rules (plan, section "Frozen before any simulation"; copied here, not changed):
  split     stratum = (ticker, kind); within a stratum ids sorted by hex SHA-256 of "rerank-blend-split-v1:" + id; each stratum holds out floor(n/3),
            plus one for the strata with the largest remainders (ties by stratum name) until 14 are held out; the first ids in sorted order are held
            out. The script fails unless the list equals FROZEN_HELD_OUT.
  blend     rerank inputs = the first 40 fused chunks with fused position f and reranked position r; score = w / (k + r) + (1 - w) / (k + f), exact
            rational arithmetic; inputs by score descending, ties by smaller f; fused chunks after the inputs keep fused order; window = first 10.
            Rank = 1-based position of the first window chunk the evidence report lists under an accepted phrase of the question; null if none.
  grid      k in {10, 60} x w in {0.25, 0.5, 0.75}.
  checks    w = 1 must reproduce 947's per-question ranks and w = 0 932's; otherwise the script stops naming every differing question.
  choice    tuning set only: highest tuning hit@5, ties by higher tuning MRR, then smaller w, then k 60. Held-out metrics are computed for the chosen
            point only.
  held-out  on the 14 held-out questions against 598: PASS if held-out hit@5 is at least 598's and no held-out FIGURE question ranked 1 to 5 in 598
            leaves the top 5; otherwise FAIL.
Metrics mirror RetrievalEvaluationService: hit@5 = questions with rank 1..5 / questions, half up to six places; MRR = sum of 1/rank (each half up to
twelve places, null counted 0) / questions, half up to six places. The script confirms both against the stored metrics of 598, 932 and 947 first.
Figure slice membership: a question is in the figure slice when its 947 trace records figureCandidates (the figure leg ran); the script requires the
same membership in the 598 and 932 traces and requires every stored slice value of the three snapshots to equal its recomputation from it.

Run: python3 blend_simulation.py   (then: git diff --exit-code blend-simulation.txt claims.json)
"""
import hashlib
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))

SET = "../../../../resources/evaluation/retrieval-set-v2.json"
R = "../2026-09-14-recall-one-factor/"
M = "../2026-09-13-evaluation-evidence/measurement/"
S947 = R + "snapshot-947-c-repeat-candidate-count-200-rerank-candidates-40-timeout-4000.json"
E947 = R + "evidence-947.json"
S932 = R + "snapshot-932-b-candidate-count-200-rerank-off.json"
S598 = M + "snapshot-598-traced-snapshot-295-reference-rerank-off.json"
INPUTS = [SET, S947, E947, S932, S598]

SALT = "rerank-blend-split-v1:"
HELD_OUT_COUNT = 14
FROZEN_HELD_OUT = ["aapl-04", "aapl-05", "aapl-11", "aapl-12", "aapl-14", "msft-02", "msft-03", "msft-04", "msft-07", "msft-10", "nvda-04", "nvda-08",
                   "nvda-10", "nvda-11"]
RERANK_INPUTS = 40
WINDOW = 10
GRID = [(k, w) for k in (10, 60) for w in (Decimal("0.25"), Decimal("0.5"), Decimal("0.75"))]
FIRST_ID = 801


class Stop(Exception):
    pass


def load(path):
    with open(os.path.join(HERE, path), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def fmt(v):
    return "null" if v is None else str(v)


def six(value):
    return value.quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)


def hit_at(ranks, k):
    return six(Decimal(sum(1 for r in ranks if r is not None and r <= k)) / Decimal(len(ranks)))


def mrr(ranks):
    total = sum((Decimal(1) / Decimal(r)).quantize(Decimal("0.000000000001"), rounding=ROUND_HALF_UP) for r in ranks if r is not None)
    return six(Decimal(total) / Decimal(len(ranks)))


def split(questions):
    strata = {}
    for q in questions:
        strata.setdefault(f"{q['ticker']} {q['kind']}", []).append(q["id"])
    for ids in strata.values():
        ids.sort(key=lambda i: hashlib.sha256((SALT + i).encode("utf-8")).hexdigest())
    counts = {name: len(ids) // 3 for name, ids in strata.items()}
    remaining = HELD_OUT_COUNT - sum(counts.values())
    if remaining < 0 or remaining > len(strata):
        raise Stop(f"split: floor(n/3) sums to {sum(counts.values())}, cannot reach {HELD_OUT_COUNT} with one extra per stratum")
    for name in sorted(strata, key=lambda n: (-(len(strata[n]) % 3), n))[:remaining]:
        counts[name] += 1
    held = sorted(i for name, ids in strata.items() for i in ids[:counts[name]])
    return strata, counts, held


def blended_window(trace, accepted, k, w):
    """(rank, window chunk ids) of one question under the frozen blend; w and k as Fraction-compatible numbers."""
    rerank = trace["rerank"]
    fused = trace["fused"]
    if rerank["outcome"] != "RERANKED":
        raise Stop(f"rerank outcome {rerank['outcome']}, expected RERANKED")
    candidates = rerank["candidates"]
    n = len(candidates)
    if n != min(len(fused), max(RERANK_INPUTS, WINDOW)) or rerank["inputCount"] != n:
        raise Stop(f"rerank inputs {n} (inputCount {rerank['inputCount']}), expected the first {min(len(fused), RERANK_INPUTS)} fused chunks")
    by_position = {c["fusedPosition"]: c for c in fused}
    if sorted(by_position) != list(range(1, len(fused) + 1)):
        raise Stop("fused positions are not 1..n")
    if sorted(c["fusedPosition"] for c in candidates) != list(range(1, n + 1)) or sorted(c["rerankedPosition"] for c in candidates) != list(range(1, n + 1)):
        raise Stop("rerank candidates' fused or reranked positions are not 1..n")
    for c in candidates:
        if by_position[c["fusedPosition"]]["chunkId"] != c["chunkId"]:
            raise Stop(f"rerank candidate {c['chunkId']} is not the fused chunk at position {c['fusedPosition']}")
    wf = Fraction(w)
    scored = sorted(candidates, key=lambda c: (-(wf / (k + c["rerankedPosition"]) + (1 - wf) / (k + c["fusedPosition"])), c["fusedPosition"]))
    order = [c["chunkId"] for c in scored] + [by_position[p]["chunkId"] for p in range(n + 1, len(fused) + 1)]
    window = order[:WINDOW]
    for i, chunk in enumerate(window):
        if chunk in accepted:
            return i + 1, window
    return None, window


def main():
    out = []
    p = out.append
    claims = []

    def claim(basis, check=None, text=None, premises=None):
        c = {"id": f"C-{FIRST_ID + len(claims)}", "basis": basis}
        if text is not None:
            c["text"] = text
        if premises is not None:
            c["from"] = sorted(premises, key=lambda i: int(i[2:]))
        if check is not None:
            c["check"] = check
        claims.append(c)
        return c["id"]

    SETV2, SN947, EV947, SN932, SN598 = (load(x) for x in INPUTS)
    p("Offline rerank blend simulation (plan documentation/plans/2026-09-14-rerank-blend.md, Milestone 1)")
    p("Generated by blend_simulation.py from committed files only (paths relative to the script's directory):")
    for x in INPUTS:
        p(f"  {x} ({os.path.getsize(os.path.join(HERE, x))} bytes)")
    p("Labels: [observed] a value read as stored (file and field named); [derived] computed by this script (computation named).")
    p("Frozen rules: see the script's docstring and the plan. The script states ranks and metrics of a simulated order; it states no cause.")
    p("")

    if EV947["snapshotId"] != SN947["id"] or SN947["id"] != 947 or SN932["id"] != 932 or SN598["id"] != 598:
        raise Stop("snapshot or evidence ids are not 947, 932, 598")
    for S in (SN947, SN932, SN598):
        if S["set_version"] != SETV2["version"] or S["window_size"] != WINDOW or S["question_count"] != len(SETV2["questions"]):
            raise Stop(f"snapshot {S['id']}: set version, window or question count differs from the set v2 and window {WINDOW}")
    p("0. Inputs [observed: snapshot id, set_version, window_size, question_count, properties]")
    for S in (SN947, SN932, SN598):
        pr = S["properties"]
        p(f"  {S['id']}: set {S['set_version']}, window {S['window_size']}, questions {S['question_count']}, candidateCount {pr['candidateCount']}, "
          f"rerank {fmt(pr['rerank'])}, rerankCandidates {pr['rerankCandidates']}, rerankedQuestions {pr['rerankedQuestions']}, "
          f"rerankFallbackQuestions {pr['rerankFallbackQuestions']}")
    if SN947["properties"]["rerankCandidates"] != RERANK_INPUTS or SN947["properties"]["rerankFallbackQuestions"] != 0:
        raise Stop("snapshot 947 does not record rerankCandidates 40 with 0 fallbacks")
    p("")

    ids = [q["id"] for q in SETV2["questions"]]
    kinds = {q["id"]: q["kind"] for q in SETV2["questions"]}
    tickers = {q["id"]: q["ticker"] for q in SETV2["questions"]}
    for S in (SN947, SN932, SN598):
        stored = S["results"]["questions"]
        if [q["id"] for q in stored] != ids or any(q["kind"] != kinds[q["id"]] or q["ticker"] != tickers[q["id"]] for q in stored):
            raise Stop(f"snapshot {S['id']}: question ids, kinds or tickers differ from the set v2 in order or value")

    # S1
    strata, counts, held = split(SETV2["questions"])
    p("1. S1 split [derived: frozen split rule over retrieval-set-v2.json ticker and kind]")
    for name in sorted(strata):
        p(f"  {name:<14} n {len(strata[name]):>2}  held out {counts[name]}  sorted ids {', '.join(strata[name])}")
    p(f"  held out ({len(held)}): {', '.join(held)}")
    same = held == FROZEN_HELD_OUT
    p(f"  frozen list: {', '.join(FROZEN_HELD_OUT)} -> {'equal' if same else 'NOT EQUAL'}")
    if not same:
        raise Stop(f"S1: split yields {held}, frozen list {FROZEN_HELD_OUT}")
    tuning = [i for i in ids if i not in held]
    held = [i for i in ids if i in held]
    p(f"  tuning set ({len(tuning)}): {', '.join(tuning)}")
    p("")

    # Metric formula and slice membership confirmation
    traces947 = {t["id"]: t["trace"] for t in SN947["results"]["traces"]}
    figure = [i for i in ids if traces947[i]["figureCandidates"] is not None]
    non_figure = [i for i in ids if i not in figure]
    p("2. Metric and slice confirmation [derived: recomputed from snapshot results rank; observed: stored hit_at_5, mrr, slices, tickerHitAt5]")
    for S in (SN598, SN932, SN947):
        ranks = {q["id"]: q["rank"] for q in S["results"]["questions"]}
        traced_figure = [t["id"] for t in S["results"]["traces"] if t["trace"]["figureCandidates"] is not None]
        checks = [("hit_at_5", hit_at(list(ranks.values()), 5), S["hit_at_5"]), ("mrr", mrr(list(ranks.values())), S["mrr"]),
                  ("figure membership from trace", traced_figure, figure)]
        for sname, members in (("figure", figure), ("nonFigure", non_figure)):
            st = S["results"]["slices"][sname]
            rs = [ranks[i] for i in members]
            checks += [(f"slices.{sname}.questionCount", len(members), st["questionCount"]), (f"slices.{sname}.hitAt1", hit_at(rs, 1), st["hitAt1"]),
                       (f"slices.{sname}.hitAt3", hit_at(rs, 3), st["hitAt3"]), (f"slices.{sname}.hitAt5", hit_at(rs, 5), st["hitAt5"]),
                       (f"slices.{sname}.mrr", mrr(rs), st["mrr"]), (f"slices.{sname}.missIds", [i for i in members if ranks[i] is None], st["missIds"]),
                       (f"slices.{sname}.notInTop5", [i for i in members if ranks[i] is None or ranks[i] > 5], [x["id"] for x in st["notInTop5"]])]
        for t in sorted(set(tickers.values())):
            checks.append((f"tickerHitAt5.{t}", hit_at([ranks[i] for i in ids if tickers[i] == t], 5), S["results"]["tickerHitAt5"][t]))
        bad = [f"{name} recomputed {fmt(a)} stored {fmt(b)}" for name, a, b in checks
               if (six(Decimal(b)) != a if isinstance(a, Decimal) else a != b)]
        p(f"  {S['id']}: {len(checks)} values compared -> " + ("all equal" if not bad else "DIFFERENT: " + "; ".join(bad)))
        if bad:
            raise Stop(f"metric or slice confirmation failed for snapshot {S['id']}: {bad}")
    p(f"  figure slice ({len(figure)}): {', '.join(figure)}")
    p(f"  non-figure slice ({len(non_figure)}): {', '.join(non_figure)}")
    p("")

    # Accepted chunks
    accepted = {}
    for q in EV947["questions"]:
        if q["acceptedPhraseCount"]["basis"] != "observed":
            raise Stop(f"evidence 947 {q['id']}: accepted phrases not observed")
        accepted[q["id"]] = {c["chunkId"] for ph in q["phrases"] for c in ph["chunks"]}
    if list(accepted) != ids:
        raise Stop("evidence 947 question order differs from the set")

    def simulate(k, w, subset):
        return {i: blended_window(traces947[i], accepted[i], k, w) for i in subset}

    # S2
    p("3. S2 reproduction checks [derived: blended rank over the 947 traces and evidence-947 accepted chunks; observed: stored ranks]")
    stored947 = {q["id"]: q for q in SN947["results"]["questions"]}
    stored932 = {q["id"]: q for q in SN932["results"]["questions"]}
    differing = []
    for label, k, w, stored, returned in (("w = 1 against 947", 60, 1, stored947, True), ("w = 0 against 932", 60, 0, stored932, False)):
        sim = simulate(k, w, ids)
        diff = [f"{i} (simulated {fmt(sim[i][0])}, stored {fmt(stored[i]['rank'])})" for i in ids if sim[i][0] != stored[i]["rank"]]
        matched = [i for i in ids if sim[i][0] is not None and sim[i][1][sim[i][0] - 1] != stored[i]["matchedChunkId"]]
        p(f"  {label} (k {k}): ranks differing -> " + (", ".join(diff) if diff else "none of 42"))
        p(f"    matched chunk ids differing (report only) -> " + (", ".join(matched) if matched else "none of 42"))
        if returned:
            win = [i for i in ids if sim[i][1] != traces947[i]["returnedChunkIds"]]
            p(f"    window differing from the 947 trace returnedChunkIds (report only) -> " + (", ".join(win) if win else "none of 42"))
        differing += [f"{label}: {d}" for d in diff]
    if differing:
        p("")
        p("Stopped at S2: the grid was not run.")
        with open(os.path.join(HERE, "blend-simulation.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")
        raise Stop("S2 failed: " + "; ".join(differing))
    p("")

    # Tuning grid (tuning questions only)
    p(f"4. Tuning grid [derived: blended ranks of the {len(tuning)} tuning questions; hit@5 and MRR as in section 2]")
    p("  k    w     hit@5     (n)  MRR")
    rows = []
    for k, w in GRID:
        sim = simulate(k, w, tuning)
        ranks = [sim[i][0] for i in tuning]
        h, m = hit_at(ranks, 5), mrr(ranks)
        n = sum(1 for r in ranks if r is not None and r <= 5)
        rows.append((k, w, h, m, n))
        p(f"  {k:<3}  {str(w):<4}  {h}  ({n:>2})  {m}")
    stored_tuning = [stored947[i]["rank"] for i in tuning]
    ref_tuning = [q["rank"] for q in SN598["results"]["questions"] if q["id"] in tuning]
    p(f"  for the record [derived: stored ranks of the tuning questions]: 947 hit@5 {hit_at(stored_tuning, 5)} MRR {mrr(stored_tuning)}; "
      f"598 hit@5 {hit_at(ref_tuning, 5)} MRR {mrr(ref_tuning)}")
    chosen = sorted(rows, key=lambda r: (-r[2], -r[3], r[1], 0 if r[0] == 60 else 1))[0]
    ck, cw = chosen[0], chosen[1]
    p(f"  chosen by the frozen order (hit@5, then MRR, then smaller w, then k 60): k {ck}, w {cw}")
    p("")
    tuning_ids = []
    for k, w, h, m, n in rows:
        tuning_ids.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": k, "w": w, "questions": tuning, "metric": "hitAt5", "expected": h}))
        tuning_ids.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": k, "w": w, "questions": tuning, "metric": "mrr", "expected": m}))
    runner_up = sorted(rows, key=lambda r: (-r[2], -r[3], r[1], 0 if r[0] == 60 else 1))
    tied = [r for r in runner_up if r[2] == chosen[2]]
    claim("inferred", text=f"By the frozen choice rule (highest tuning hit@5, ties by higher tuning MRR, then smaller w, then k 60), the chosen point is "
          f"k {ck} and w {cw}; {len(tied)} of the {len(rows)} grid points share its tuning hit@5 of {chosen[2]}.", premises=tuning_ids)

    # Held-out test, chosen point only
    sim = simulate(ck, cw, ids)
    ranks598 = {q["id"]: q["rank"] for q in SN598["results"]["questions"]}
    top5 = lambda r: r is not None and r <= 5
    held_blend = [sim[i][0] for i in held]
    held_598 = [ranks598[i] for i in held]
    hb, hr = hit_at(held_blend, 5), hit_at(held_598, 5)
    p(f"5. Held-out test at k {ck}, w {cw} against 598 on the {len(held)} held-out questions [derived: blended ranks; observed: 598 stored ranks]")
    p("  question  kind       598 rank  blended rank")
    for i in held:
        p(f"  {i:<8}  {kinds[i]:<9}  {fmt(ranks598[i]):>8}  {fmt(sim[i][0]):>12}")
    leaving = [i for i in held if kinds[i] == "FIGURE" and top5(ranks598[i]) and not top5(sim[i][0])]
    entering = [i for i in held if not top5(ranks598[i]) and top5(sim[i][0])]
    leaving_any = [i for i in held if top5(ranks598[i]) and not top5(sim[i][0])]
    hit_ok = hb >= hr
    passed = hit_ok and not leaving
    p(f"  held-out hit@5: blended {hb} ({sum(1 for r in held_blend if top5(r))} of {len(held)}), 598 {hr} ({sum(1 for r in held_598 if top5(r))} of {len(held)}) "
      f"-> {'at least' if hit_ok else 'BELOW'}")
    p("  held-out questions entering the top 5 against 598: " + (", ".join(entering) if entering else "none"))
    p("  held-out questions leaving the top 5 against 598: " + (", ".join(f"{i} ({kinds[i]})" for i in leaving_any) if leaving_any else "none"))
    p("  held-out questions outside the top 5 in both: " + (", ".join(i for i in held if not top5(ranks598[i]) and not top5(sim[i][0])) or "none"))
    p("  held-out FIGURE questions ranked 1 to 5 in 598: " + ", ".join(i for i in held if kinds[i] == "FIGURE" and top5(ranks598[i])))
    p("  held-out FIGURE questions ranked 1 to 5 in 598 that leave the top 5: " + (", ".join(leaving) if leaving else "none"))
    p(f"  RESULT: {'PASS' if passed else 'FAIL'}")
    p("")
    held_premises = [claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": held, "metric": "hitAt5", "expected": hb})]
    held_figure_rank_claims = []
    for i in held:
        pair = [claim("observed", {"type": "rank", "snapshot": S598, "question": i, "expected": ranks598[i]}),
                claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": [i], "metric": "rank", "expected": sim[i][0]})]
        held_premises += pair
        if kinds[i] == "FIGURE" and top5(ranks598[i]):
            held_figure_rank_claims += pair
    figure_held = [i for i in held if kinds[i] == "FIGURE" and top5(ranks598[i])]
    outside_both = [i for i in held if not top5(ranks598[i]) and not top5(sim[i][0])]
    listed = lambda xs: ", ".join(xs) if xs else "0 questions"
    decided = [f"held-out hit@5 of the blend is {hb} ({sum(1 for r in held_blend if top5(r))} of {len(held)}) against {hr} "
               f"({sum(1 for r in held_598 if top5(r))} of {len(held)}) for snapshot 598, {'at least' if hit_ok else 'below'} that value",
               f"entering the top 5 against snapshot 598: {listed(entering)}",
               f"leaving the top 5 against snapshot 598: {listed(leaving_any)}",
               f"outside the top 5 in both: {listed(outside_both)}",
               f"of the {len(figure_held)} held-out FIGURE questions ranked 1 to 5 in snapshot 598 ({', '.join(figure_held)}), "
               f"{len(figure_held) - len(leaving)} stay in the top 5 and {len(leaving)} leave it" + (f" ({', '.join(leaving)})" if leaving else "")]
    claim("inferred", text=f"Held-out test at k {ck} and w {cw}: {'PASS' if passed else 'FAIL'}; " + "; ".join(decided) + ".", premises=held_premises)

    # Full-42 selection rule against 598, beside the held-out test
    p(f"6. Full-42 selection rule at k {ck}, w {cw} against 598 (reported beside the held-out test; it does not replace it)")
    all_blend = {i: sim[i][0] for i in ids}
    rule_premises = []
    crit = []
    b, r = hit_at(list(all_blend.values()), 5), Decimal(SN598["hit_at_5"])
    crit.append(("aggregate hit@5", b, six(r)))
    rule_premises.append(claim("observed", {"type": "metric", "snapshot": S598, "metric": "hitAt5", "expected": six(r)}))
    rule_premises.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": ids, "metric": "hitAt5", "expected": b}))
    b, r = hit_at([all_blend[i] for i in non_figure], 5), six(Decimal(SN598["results"]["slices"]["nonFigure"]["hitAt5"]))
    crit.append(("non-figure-slice hit@5", b, r))
    rule_premises.append(claim("observed", {"type": "metric", "snapshot": S598, "metric": "slices.nonFigure.hitAt5", "expected": r}))
    rule_premises.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": non_figure, "metric": "hitAt5",
                                           "expected": b}))
    for t in sorted(set(tickers.values())):
        members = [i for i in ids if tickers[i] == t]
        b, r = hit_at([all_blend[i] for i in members], 5), six(Decimal(SN598["results"]["tickerHitAt5"][t]))
        crit.append((f"{t} hit@5", b, r))
        rule_premises.append(claim("observed", {"type": "metric", "snapshot": S598, "metric": f"tickerHitAt5.{t}", "expected": r}))
        rule_premises.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": members, "metric": "hitAt5",
                                               "expected": b}))
    for name, b, r in crit:
        p(f"  {name}: blended {b}, 598 {r} -> {'pass' if b >= r else 'fail'}")
    figure_top5 = [i for i in ids if kinds[i] == "FIGURE" and top5(ranks598[i])]
    figure_leaving = [i for i in figure_top5 if not top5(all_blend[i])]
    p(f"  FIGURE-kind questions ranked 1 to 5 in 598 ({len(figure_top5)}): " + ", ".join(f"{i} {ranks598[i]}->{fmt(all_blend[i])}" for i in figure_top5))
    p("  FIGURE top-5: leaving -> " + (", ".join(figure_leaving) if figure_leaving else "none") + f" -> {'pass' if not figure_leaving else 'fail'}")
    for i in figure_top5:
        if i in held:
            continue  # its 598 rank and blended rank are claimed in the held-out claims above
        rule_premises.append(claim("observed", {"type": "rank", "snapshot": S598, "question": i, "expected": ranks598[i]}))
        rule_premises.append(claim("derived", {"type": "blend", "snapshot": S947, "report": E947, "k": ck, "w": cw, "questions": [i], "metric": "rank",
                                               "expected": all_blend[i]}))
    rule_premises += held_figure_rank_claims
    failed = [name for name, b, r in crit if b < r] + (["FIGURE top-5"] if figure_leaving else [])
    rule_pass = not failed
    p(f"  full-42 selection rule: {'meets the rule' if rule_pass else 'does not meet the rule; failing criteria: ' + ', '.join(failed)}")
    p("  all 42 blended ranks [derived] as question 598/blended: " + ", ".join(f"{i} {fmt(ranks598[i])}/{fmt(all_blend[i])}" for i in ids))
    p("")
    criteria_text = "; ".join(f"{name} {b} against {r} ({'pass' if b >= r else 'fail'})" for name, b, r in crit)
    claim("inferred", text=f"Full-42 selection rule at k {ck} and w {cw} against snapshot 598, beside the held-out test: {criteria_text}; FIGURE top-5 "
          + (f"pass, with 0 of its {len(figure_top5)} questions leaving the top 5" if not figure_leaving else f"fail, with {len(figure_leaving)} of its {len(figure_top5)} questions leaving the top 5: " + ", ".join(figure_leaving))
          + f"; the row {'meets' if rule_pass else 'does not meet'} the rule.", premises=rule_premises)
    p(f"Checks: S1 and S2 hold as computed above; held-out result {'PASS' if passed else 'FAIL'}.")

    if len(claims) > 99:
        raise Stop(f"{len(claims)} claims exceed the id range C-801 to C-899")
    with open(os.path.join(HERE, "blend-simulation.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")

    def value(v):
        if isinstance(v, Decimal):
            return str(v)
        return json.dumps(v, ensure_ascii=False)

    lines = ["{",
             '  "description": "Rerank blend simulation over the traces of snapshot 947 (plan plans/2026-09-14-rerank-blend.md, Milestone 1), written by'
             ' blend_simulation.py: tuning hit@5 and MRR of the six grid points, the chosen point, its held-out test against snapshot 598, and the full-42'
             ' selection rule against snapshot 598 beside it.",',
             '  "claims": [']
    for n, c in enumerate(claims):
        parts = [f'"id": "{c["id"]}"', f'"basis": "{c["basis"]}"']
        if "text" in c:
            parts.append(f'"text": {value(c["text"])}')
        if "from" in c:
            parts.append(f'"from": {value(c["from"])}')
        if "check" in c:
            parts.append('"check": {' + ", ".join(f'"{k}": {value(v)}' for k, v in c["check"].items()) + "}")
        lines.append("    {" + ", ".join(parts) + "}" + ("," if n < len(claims) - 1 else ""))
    lines += ["  ]", "}"]
    with open(os.path.join(HERE, "claims.json"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Stop as stop:
        print(f"stopped: {stop}", file=sys.stderr)
        sys.exit(2)

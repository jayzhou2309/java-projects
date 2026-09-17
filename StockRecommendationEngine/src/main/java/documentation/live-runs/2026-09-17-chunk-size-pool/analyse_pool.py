#!/usr/bin/env python3
"""Chunk size and candidate pool, Milestone 1 of plan documentation/plans/2026-09-17-chunk-size-pool.md (Follow_Ups RAG-27, RAG-30).

Reads only committed files in this directory (and snapshot 1615 of ../2026-09-17-chunk-size/measurement/, and the bundled set v2 for the
question ids): the row_to_json snapshot exports, the evidence reports, the chunk exports and content hashes of the three store points, the
storage snapshots, the latency extracts (latency.py over the committed application-log windows), the held-phrase outputs, and run.log (for the
settings of each application start, of which rerank-timeout-ms is recorded nowhere else). Writes, replacing them:
  runs.txt            every run: snapshot id, store, pool, reranking, rerank-candidates, rerank-timeout-ms (from its start line in run.log), role;
  property-diff.txt   every recorded property of every pair the design compares (G3);
  grid.txt            the grid table on the 42 questions and on the 28 tuning questions, the choice per store and reranker state, the held-out
                      test of store B's chosen points (G5), top-5 membership changes, nvda-02 and nvda-04 in every grid run;
  rank-table.txt      per question the rank and matched chunk in the reported runs, and every question not at rank 1 with the chunks above it;
  rank-histogram.txt  questions per rank in the reported runs;
  latency.txt         per-run latency of every run;   storage.txt   the three storage points;   rollback.txt   the rollback equality (G8);
  experiment-*.json   the experiment files of the one-factor pairs;   claims.json   the claims (ids C-1601 on; the claims added in remediation
                      round 1 take the ids after the last id of the first version, C-1984, so that no earlier id moves, and are printed in
                      their blocks before the inferred claim that uses them).
No database, no application, no model. Numbers are parsed as written (Decimal). Output is deterministic: a re-run reproduces the files byte for
byte. The script states ranks, positions, counts, times and sizes; it states no cause.

Frozen rules (plan, section "Frozen design"; copied here, not changed):
  split     held-out = FROZEN_HELD_OUT (the 14 of plan 2026-09-14-rerank-blend.md); the other 28 questions of set v2 are tuning questions.
  choice    on the 28 tuning questions only, per store and per reranker state: the pool with the highest tuning hit@5; ties by higher tuning
            MRR, then the smaller pool. A reranked run with a fallback is labelled and repeated once; when the repeat records no fallback the
            repeat is the grid point; when it also records one the pool is excluded from the choice.
  held-out  once per chosen store-B point, on the 14 held-out questions: PASS if its held-out hit@5 is at least that of the default reference
            (A / 40 / off) and at least that of store A's chosen point of the same reranker state; otherwise FAIL.
  Held-out metrics are computed only for the default reference and the four chosen points. (grid.txt, section 1, prints the stored 42-question
  metrics beside the tuning metrics for all sixteen grid runs. That goes beyond the plan's Reported list, which names 42-question metrics for the
  default reference, the four chosen points, and the latency reference only, and it lets a reader derive the held-out count of an unchosen point
  by subtraction. The choice used the tuning values only, and the script computes no held-out metric for an unchosen point. Corrected in
  remediation round 1: this note said the table printed them "as the plan lists".)
Metrics mirror RetrievalEvaluationService: hit@k = questions ranked 1 to k / questions, half up to six places; MRR = sum of 1/rank (each half up
to twelve places, null counted 0) / questions, half up to six places. The script confirms both against every snapshot's stored metrics first.
"""
import glob
import json
import os
import re
import sys
from decimal import ROUND_HALF_UP, Decimal

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
SET = "../../../../resources/evaluation/retrieval-set-v2.json"
REF_1615 = "../2026-09-17-chunk-size/measurement/snapshot-1615-p0-post-rollback-4000-500-rerank-off.json"
FROZEN_HELD_OUT = ["aapl-04", "aapl-05", "aapl-11", "aapl-12", "aapl-14", "msft-02", "msft-03", "msft-04", "msft-07", "msft-10", "nvda-04", "nvda-08",
                   "nvda-10", "nvda-11"]
POOLS = [40, 100, 200, 250]
STATES = ["off", "on"]
FOCUS = ["nvda-02", "nvda-04"]
FILINGS = [3, 4, 5, 6, 7, 8, 109, 110, 161, 162, 288, 324, 325]
FIRST_ID = 1601
LAST_ID = 1999
APPENDED_FIRST_ID = 1985  # remediation round 1: claims added after the first version (C-1601 to C-1984) take ids from here
STORE_NAMES = {"A": "store A (4000 / 500)", "B": "store B (1650 / 250)", "A2": "the store after the rollback (4000 / 500)"}
CHUNKS = {"A": "chunks-A.json", "B": "chunks-B.json", "A2": "chunks-A2.json"}
STORAGE_FIELDS = ["chunks", "contentChars", "meanChunkChars", "maxChunkChars", "tokenCountSum", "totalRelationBytes", "heapBytes", "toastBytes", "indexesBytes",
                  "storeVersions", "indexes"]


def load(relative):
    with open(os.path.join(HERE, relative), encoding="utf-8") as f:
        return json.load(f, parse_float=Decimal)


def exists(relative):
    return os.path.exists(os.path.join(HERE, relative))


def fmt(value):
    if isinstance(value, bool) or value is None:
        return "null" if value is None else str(value).lower()
    if isinstance(value, list):
        return "[" + ", ".join(fmt(v) for v in value) + "]"
    return str(value)


def dec(v):
    if isinstance(v, Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    return v


def path_value(node, path):
    for segment in path.split("."):
        name, selector = (segment.split("[", 1) + [None])[:2]
        if name:
            node = node[name]
        if selector is not None:
            selector = selector[:-1]
            key, value = selector.split("=", 1)
            node = next(e for e in node if str(e.get(key)) == value)
    return node


SIX = Decimal("0.000001")


def hit_at(ranks, k):
    return (Decimal(sum(1 for r in ranks if r is not None and r <= k)) / Decimal(len(ranks))).quantize(SIX, rounding=ROUND_HALF_UP)


def mrr(ranks):
    total = sum(((Decimal(1) / Decimal(r)).quantize(Decimal("0.000000000001"), rounding=ROUND_HALF_UP) for r in ranks if r is not None), Decimal(0))
    return (total / Decimal(len(ranks))).quantize(SIX, rounding=ROUND_HALF_UP)


# ---------------------------------------------------------------- runs
class Run:
    def __init__(self, file, sid, store, pool, state, role, repeat):
        self.file, self.id, self.store, self.pool, self.state, self.role, self.repeat = file, sid, store, pool, state, role, repeat
        self.snapshot = load(file)
        assert self.snapshot["id"] == sid, file
        self.evidence_file = "evidence-%d.json" % sid
        self.evidence = load(self.evidence_file)
        assert self.evidence["snapshotId"] == sid, file
        self.latency_file = "latency-%d.json" % sid
        self.latency = load(self.latency_file)
        assert self.latency["snapshotId"] == sid, file
        self.questions = self.snapshot["results"]["questions"]
        self.by_id = {q["id"]: q for q in self.questions}
        self.traces = {t["id"]: t["trace"] for t in (self.snapshot["results"].get("traces") or [])}
        self.props = self.snapshot["properties"]
        self.fallbacks = self.props["rerankFallbackQuestions"]
        self.name = "%s / %d / %s%s" % (store, pool, state, " (repeat)" if repeat else "")
        if role == "latencyReference":
            self.name = "latency reference (A / 40 / on, 20 inputs, 2000 ms)"
        if role == "postRollback":
            self.name = "post-rollback (A2 / 40 / off)"

    def ranks(self, ids):
        return [self.by_id[q]["rank"] for q in ids]


PATTERN = re.compile(r"snapshot-(\d+)-(a|b)-pool-(\d+)-rerank-(off|on-40)(-repeat)?\.json$")
runs = []
for path in sorted(glob.glob(os.path.join(HERE, "snapshot-*.json"))):
    name = os.path.basename(path)
    m = PATTERN.match(name)
    if m:
        runs.append(Run(name, int(m.group(1)), m.group(2).upper(), int(m.group(3)), "off" if m.group(4) == "off" else "on", "grid", bool(m.group(5))))
        continue
    m = re.match(r"snapshot-(\d+)-a-latency-reference-pool-40-rerank-on-20(-repeat)?\.json$", name)
    if m:
        runs.append(Run(name, int(m.group(1)), "A", 40, "on", "latencyReference", bool(m.group(2))))
        continue
    m = re.match(r"snapshot-(\d+)-a2-post-rollback-pool-40-rerank-off\.json$", name)
    if m:
        runs.append(Run(name, int(m.group(1)), "A2", 40, "off", "postRollback", False))
        continue
    raise SystemExit("unrecognised snapshot file " + name)
runs.sort(key=lambda r: r.id)
by_snapshot = {r.id: r for r in runs}

# settings of each application start, from run.log (the timeout is recorded nowhere else)
log_lines = open(os.path.join(HERE, "run.log"), encoding="utf-8").read().split("\n")
start_of = {}
for i, line in enumerate(log_lines):
    m = re.match(r"Run (\S+) \(rerank=(true|false)\)", line)
    if m:
        start = next(l for l in log_lines[i + 1:i + 4] if " start: env " in l)
        stored = next((re.search(r"-> snapshot (\d+)", l) for l in log_lines[i + 1:i + 12] if "-> snapshot " in l), None)
        if stored:
            start_of[int(stored.group(1))] = start
for r in runs:
    line = start_of[r.id]
    t = re.search(r"RAG_RETRIEVAL_RERANK_TIMEOUT_MS=(\d+)", line)
    r.timeout = int(t.group(1)) if t else 2000
    r.timeout_source = "run.log start line of snapshot %d: %s" % (r.id, "RAG_RETRIEVAL_RERANK_TIMEOUT_MS=%d" % r.timeout if t else "no RAG_RETRIEVAL_RERANK_TIMEOUT_MS, application.yaml default 2000")
    r.overrides = re.findall(r"(RAG_[A-Z_]+=\S+)", line.split("./mvnw")[0])

set_ids = [q["id"] for q in load(SET)["questions"]]
for r in runs:
    assert [q["id"] for q in r.questions] == set_ids, r.file
HELD_OUT = [q for q in set_ids if q in FROZEN_HELD_OUT]
assert HELD_OUT == FROZEN_HELD_OUT and len(HELD_OUT) == 14
TUNING = [q for q in set_ids if q not in FROZEN_HELD_OUT]
assert len(TUNING) == 28 and len(set_ids) == 42

# the script's metrics against every stored metric
for r in runs:
    allr = r.ranks(set_ids)
    for k, col in ((1, "hit_at_1"), (3, "hit_at_3"), (5, "hit_at_5")):
        assert hit_at(allr, k) == Decimal(str(r.snapshot[col])).quantize(SIX), (r.file, col)
    assert mrr(allr) == Decimal(str(r.snapshot["mrr"])).quantize(SIX), r.file

# grid points: the first run of a (store, pool, state), or its repeat when the first recorded a fallback and the repeat did not
grid = {}
excluded = {}
notes = []
for store in ("A", "B"):
    for state in STATES:
        for pool in POOLS:
            first = [r for r in runs if r.role == "grid" and (r.store, r.pool, r.state, r.repeat) == (store, pool, state, False)]
            rep = [r for r in runs if r.role == "grid" and (r.store, r.pool, r.state, r.repeat) == (store, pool, state, True)]
            if not first:
                continue
            assert len(first) == 1 and len(rep) <= 1
            point = first[0]
            if point.fallbacks > 0:
                if not rep:
                    raise SystemExit("%s recorded a fallback and has no repeat" % point.file)
                if rep[0].fallbacks == 0:
                    notes.append("%s: the first run (snapshot %d) recorded %d fallback question(s); the repeat (snapshot %d) recorded 0 and is the grid point" % (point.name, point.id, point.fallbacks, rep[0].id))
                    point = rep[0]
                else:
                    notes.append("%s: the first run (snapshot %d) recorded %d fallback question(s) and the repeat (snapshot %d) recorded %d; the pool is excluded from the choice" % (point.name, point.id, point.fallbacks, rep[0].id, rep[0].fallbacks))
                    excluded[(store, pool, state)] = True
            grid[(store, pool, state)] = point
HAS_B = any(k[0] == "B" for k in grid)
DEFAULT = grid[("A", 40, "off")]
LATREF = next((r for r in runs if r.role == "latencyReference" and not (r.fallbacks > 0 and any(x.role == "latencyReference" and x.repeat for x in runs))), None) or next(r for r in runs if r.role == "latencyReference")
POST = next((r for r in runs if r.role == "postRollback"), None)

out = {}


def w(name, lines):
    out[name] = "\n".join(lines) + "\n"


# ---------------------------------------------------------------- runs.txt
lines = ["Runs of plan plans/2026-09-17-chunk-size-pool.md, Milestone 1, generated by analyse_pool.py [observed: snapshot properties; rerank-timeout-ms from run.log]",
         "%-6s %-52s %-5s %-4s %-6s %-7s %-10s %-9s %-10s %s" % ("id", "run", "pool", "kw", "rerank", "inputs", "timeoutMs", "fallbacks", "chunkMax", "overrides of the application start (run.log)")]
for r in runs:
    p = r.props
    assert p["candidateCount"] == r.pool and p["keywordCandidateCount"] == r.pool, r.file
    assert p["rerank"] is (r.state == "on"), r.file
    assert p["chunkMaxChars"] == (1650 if r.store == "B" else 4000) and p["chunkOverlapChars"] == (250 if r.store == "B" else 500), r.file
    if r.state == "on":
        assert p["rerankCandidates"] == (20 if r.role == "latencyReference" else 40) and r.timeout == (2000 if r.role == "latencyReference" else 4000), r.file
    else:
        assert p["rerankCandidates"] == 20 and r.timeout == 2000, r.file
    lines.append("%-6d %-52s %-5s %-4s %-6s %-7s %-10d %-9s %-10s %s" % (r.id, r.name, p["candidateCount"], p["keywordCandidateCount"], fmt(p["rerank"]), p["rerankCandidates"], r.timeout,
                                                                        p["rerankFallbackQuestions"], "%s/%s" % (p["chunkMaxChars"], p["chunkOverlapChars"]), " ".join(r.overrides) or "none"))
lines += ["", "Grid points (a repeat replaces a first run that recorded a fallback when the repeat records none):"]
lines += ["  " + n for n in notes] if notes else ["  the grid point of each store, pool, and reranker state is its first run (rerankFallbackQuestions 0 in those runs)"]
w("runs.txt", lines)

# ---------------------------------------------------------------- property diff (G3)
COLUMNS = ["set_version", "question_count", "window_size", "retrieval_strategy"]
OUTCOME_KEYS = ["properties.rerankedQuestions", "properties.rerankFallbackQuestions", "retrieval_strategy"]


def recorded(snapshot):
    rec = {c: snapshot.get(c) for c in COLUMNS}
    for k, v in snapshot["properties"].items():
        rec["properties." + k] = v
    return rec


pairs = []  # (reference run or None with snapshot dict, candidate run, kind, varies, coupled)
ref1615 = load(REF_1615)
for store in ("A", "B"):
    for state in STATES:
        for pool in POOLS[1:]:
            if (store, pool, state) in grid and (store, 40, state) in grid:
                pairs.append((grid[(store, 40, state)], grid[(store, pool, state)], "pool", ["candidateCount"], ["keywordCandidateCount"]))
for state in STATES:
    for pool in POOLS:
        if ("B", pool, state) in grid:
            pairs.append((grid[("A", pool, state)], grid[("B", pool, state)], "store", ["chunkMaxChars"], ["chunkOverlapChars", "storeVersions"]))
pair_verdict = {}
lines = ["Recorded properties per pair (plan Milestone 1, G3), generated by analyse_pool.py from the committed snapshots",
         "Compared: the snapshot columns set_version, question_count, window_size, retrieval_strategy and every key of properties (trace included).",
         "[observed: the values as stored; derived: equality]. rerank-timeout-ms and max-length are recorded in no snapshot; the rerank-timeout-ms of each run is",
         "printed from its application start line in run.log. Pool pairs vary candidateCount with keywordCandidateCount coupled (the design sets both pools to one",
         "value); store pairs vary chunkMaxChars with chunkOverlapChars and storeVersions coupled (the rebuild changes them together).", ""]


def diff(a_rec, b_rec, a_name, b_name, varies, coupled):
    differing, others, outs, equal = [], [], [], []
    for k in sorted(set(a_rec) | set(b_rec)):
        if k not in a_rec or k not in b_rec:
            others.append(k + " (recorded on one side only)")
        elif a_rec[k] != b_rec[k]:
            differing.append("%s: %s %s, %s %s" % (k, a_name, fmt(a_rec[k]), b_name, fmt(b_rec[k])))
            short = k.replace("properties.", "")
            if short in varies or short in coupled:
                continue
            (outs if k in OUTCOME_KEYS else others).append(k)
        else:
            equal.append("%s %s" % (k.replace("properties.", ""), fmt(a_rec[k])))
    return differing, others, outs, equal


def pair_block(a_rec, a_name, a_timeout, b, kind, varies, coupled):
    differing, others, outs, equal = diff(a_rec, recorded(b.snapshot), a_name, str(b.id), varies, coupled)
    lines.append("%d (%s) against %s; the design varies: %s%s" % (b.id, b.name, a_name, ", ".join(varies) if varies else "nothing", "; coupled with it: " + ", ".join(coupled) if coupled else ""))
    lines.append("  differing: " + ("; ".join(differing) if differing else "none found"))
    lines.append("  other than the factor and its coupled properties, settings: " + (", ".join(others) if others else "none found") + "; outcome counts and columns: " + (", ".join(outs) if outs else "none found"))
    lines.append("  rerank-timeout-ms (run.log): %s %s, %d %d" % (a_name, a_timeout, b.id, b.timeout))
    ok = not others and not outs and (a_timeout == b.timeout)
    verdict = ("the recorded properties differ only in the factor and the properties coupled with it, and rerank-timeout-ms is equal" if ok and varies else
               "the recorded properties are equal, and rerank-timeout-ms is equal" if ok else
               "differs beyond the factor: " + ", ".join(others + outs + ([] if a_timeout == b.timeout else ["rerank-timeout-ms"])))
    lines.append("  G3: " + verdict)
    lines.append("  equal: " + ", ".join(equal))
    lines.append("")
    return ok


lines.append("Reproduction: A / 40 / off against snapshot 1615 (the design varies nothing)")
pair_block(recorded(ref1615), "1615", 2000, DEFAULT, "reproduction", [], [])
for a, b, kind, varies, coupled in pairs:
    pair_verdict[(a.id, b.id)] = pair_block(recorded(a.snapshot), str(a.id), a.timeout, b, kind, varies, coupled)
if POST:
    lines.append("Rollback: the post-rollback run against A / 40 / off (the design varies nothing)")
    pair_block(recorded(DEFAULT.snapshot), str(DEFAULT.id), DEFAULT.timeout, POST, "rollback", [], [])
lines.append("For the record, not a one-factor pair: the latency reference against A / 40 / on (rerankCandidates 20 against 40, and rerank-timeout-ms 2000 against 4000)")
pair_block(recorded(grid[("A", 40, "on")].snapshot), str(grid[("A", 40, "on")].id), grid[("A", 40, "on")].timeout, LATREF, "latency", ["rerankCandidates"], [])
w("property-diff.txt", lines)

# ---------------------------------------------------------------- grid, choice, held-out test (G5)
def tuning_metrics(r):
    ranks = r.ranks(TUNING)
    return hit_at(ranks, 1), hit_at(ranks, 3), hit_at(ranks, 5), mrr(ranks)


def held_metrics(r):
    ranks = r.ranks(HELD_OUT)
    return hit_at(ranks, 1), hit_at(ranks, 3), hit_at(ranks, 5), mrr(ranks)


chosen = {}
choice_text = {}
for store in ("A", "B"):
    for state in STATES:
        cands = [(pool, grid[(store, pool, state)]) for pool in POOLS if (store, pool, state) in grid and (store, pool, state) not in excluded]
        if not cands:
            continue
        best = sorted(cands, key=lambda c: (-tuning_metrics(c[1])[2], -tuning_metrics(c[1])[3], c[0]))[0]
        chosen[(store, state)] = best[1]
        top_hit = tuning_metrics(best[1])[2]
        tied_hit = [p for p, r in cands if tuning_metrics(r)[2] == top_hit]
        tied_mrr = [p for p, r in cands if tuning_metrics(r)[2] == top_hit and tuning_metrics(r)[3] == tuning_metrics(best[1])[3]]
        choice_text[(store, state)] = ("pools with the highest tuning hit@5 %s: %s; of those, with the highest tuning MRR %s: %s; the smallest of those: %d"
                                       % (top_hit, ", ".join(map(str, tied_hit)), tuning_metrics(best[1])[3], ", ".join(map(str, tied_mrr)), best[0]))


def top5(r, q):
    rank = r.by_id[q]["rank"]
    return rank is not None and rank <= 5


def rank_text(r, q):
    rank = r.by_id[q]["rank"]
    return "not in window" if rank is None else "rank %d" % rank


lines = ["Grid, choice, and held-out test (plan Milestone 1, G5), generated by analyse_pool.py from the committed snapshots",
         "Labels: [observed] a value as stored; [derived] computed by this script from stored ranks (rule in the script's header). No cause is stated.",
         "Split [derived: set v2 ids]: held-out (14) %s; tuning (28) %s." % (", ".join(HELD_OUT), ", ".join(TUNING)), "",
         "1. Grid [observed: hit@1, hit@3, hit@5, MRR over the 42 questions as stored; derived: the same over the 28 tuning questions]",
         "  %-7s %-5s %-7s %-9s %-10s | %-9s %-9s %-9s %-9s | %-9s %-9s %-9s %-9s" % ("store", "pool", "rerank", "snapshot", "fallbacks", "hit@1", "hit@3", "hit@5", "MRR", "tun hit@1", "tun hit@3", "tun hit@5", "tun MRR")]
for store in ("A", "B"):
    for state in STATES:
        for pool in POOLS:
            r = grid.get((store, pool, state))
            if not r:
                continue
            t = tuning_metrics(r)
            s = r.snapshot
            lines.append("  %-7s %-5d %-7s %-9d %-10s | %-9s %-9s %-9s %-9s | %-9s %-9s %-9s %-9s%s" % (store, pool, state, r.id, r.fallbacks, s["hit_at_1"], s["hit_at_3"], s["hit_at_5"], s["mrr"], t[0], t[1], t[2], t[3],
                         "  (excluded from the choice)" if (store, pool, state) in excluded else ""))
for n in notes:
    lines.append("  note: " + n)
other_runs = [r for r in runs if r.role == "grid" and r not in grid.values()]
for r in other_runs:
    s = r.snapshot
    lines.append("  labelled run, not a grid point: %s snapshot %d, fallbacks %s: hit@5 %s, MRR %s" % (r.name, r.id, r.fallbacks, s["hit_at_5"], s["mrr"]))
lines += ["", "2. Choice per store and reranker state [derived: highest tuning hit@5, ties by higher tuning MRR, then the smaller pool]"]
for key in sorted(chosen):
    r = chosen[key]
    lines.append("  store %s, reranking %s: pool %d (snapshot %d); %s" % (key[0], key[1], r.pool, r.id, choice_text[key]))
lines += ["", "3. Held-out metrics, computed only for the default reference and the chosen points [derived: stored ranks of the 14 held-out questions]",
          "  %-40s %-9s %-9s %-9s %-9s %-9s" % ("run", "snapshot", "hit@1", "hit@3", "hit@5", "MRR")]
held_runs = [("default reference A / 40 / off", DEFAULT)] + [("chosen: store %s, reranking %s (pool %d)" % (k[0], k[1], chosen[k].pool), chosen[k]) for k in sorted(chosen)]
for label, r in held_runs:
    h = held_metrics(r)
    lines.append("  %-40s %-9d %-9s %-9s %-9s %-9s" % (label, r.id, h[0], h[1], h[2], h[3]))
test = {}
if HAS_B:
    lines += ["", "4. Held-out test of store B's chosen points [derived: PASS if held-out hit@5 is at least the default reference's and at least store A's chosen point's of the same state]"]
    for state in STATES:
        b, a = chosen[("B", state)], chosen[("A", state)]
        hb, hd, ha = held_metrics(b)[2], held_metrics(DEFAULT)[2], held_metrics(a)[2]
        outcome = "PASS" if hb >= hd and hb >= ha else "FAIL"
        deciding = {}
        for ref_label, ref in (("the default reference (snapshot %d)" % DEFAULT.id, DEFAULT), ("store A's chosen point (snapshot %d)" % a.id, a)):
            entering = [q for q in HELD_OUT if top5(b, q) and not top5(ref, q)]
            leaving = [q for q in HELD_OUT if not top5(b, q) and top5(ref, q)]
            both_out = [q for q in HELD_OUT if not top5(b, q) and not top5(ref, q)]
            deciding[ref.id] = (entering, leaving, both_out)
        test[state] = (b, a, hb, hd, ha, outcome, deciding)
        lines.append("  reranking %s: store B pool %d (snapshot %d) held-out hit@5 %s (%d of 14); default reference (snapshot %d) %s (%d of 14); store A's chosen point pool %d (snapshot %d) %s (%d of 14): %s"
                     % (state, b.pool, b.id, hb, sum(top5(b, q) for q in HELD_OUT), DEFAULT.id, hd, sum(top5(DEFAULT, q) for q in HELD_OUT), a.pool, a.id, ha, sum(top5(a, q) for q in HELD_OUT), outcome))
        for ref in (DEFAULT, a):
            e, l, o = deciding[ref.id]
            lines.append("    held-out questions against snapshot %d: inside the top 5 in B only: %s; inside the top 5 in snapshot %d only: %s; outside in both: %s"
                         % (ref.id, ", ".join("%s (%s; %s in %d)" % (q, rank_text(b, q), rank_text(ref, q), ref.id) for q in e) or "none found",
                            ref.id, ", ".join("%s (%s; %s in B)" % (q, rank_text(ref, q), rank_text(b, q)) for q in l) or "none found", ", ".join(o) or "none found"))
    lines += ["", "5. Top-5 membership over the 42 questions for store B's chosen points [derived: rank at most 5]"]
    membership = {}
    for state in STATES:
        b = chosen[("B", state)]
        for ref in (DEFAULT, chosen[("A", state)]):
            entering = [q for q in set_ids if top5(b, q) and not top5(ref, q)]
            leaving = [q for q in set_ids if not top5(b, q) and top5(ref, q)]
            membership[(b.id, ref.id)] = (entering, leaving)
            lines.append("  %s (snapshot %d) against %s (snapshot %d): entering %s; leaving %s" % (b.name, b.id, ref.name, ref.id,
                         ", ".join("%s (%s to %s)" % (q, rank_text(ref, q), rank_text(b, q)) for q in entering) or "none found",
                         ", ".join("%s (%s to %s)" % (q, rank_text(ref, q), rank_text(b, q)) for q in leaving) or "none found"))
lines += ["", "6. nvda-02 and nvda-04 in every grid run [observed: evidence report rank, matchedChunkId, rerankOutcome; per chunk holding an accepted phrase",
          "   (chunkId, fusedPosition, rerankInput, rerankedPosition, returnedPosition)]"]


def focus(r, qid):
    q = next(x for x in r.evidence["questions"] if x["id"] == qid)
    holders = [(c["chunkId"], c["fusedPosition"]["value"], c["rerankInput"]["value"], c["rerankedPosition"]["value"], c["returnedPosition"]["value"]) for p in q["phrases"] for c in p["chunks"]]
    return q["rank"]["value"], q["matchedChunkId"]["value"], q["rerankOutcome"]["value"], q["fusedCount"]["value"], holders


for qid in FOCUS:
    for key in sorted(grid, key=lambda k: (k[0], STATES.index(k[2]), k[1])):
        r = grid[key]
        rank, matched, outcome, fused_count, holders = focus(r, qid)
        lines.append("  %s %-14s snapshot %d: rank %s, matched %s, outcome %s, fused list %s; holders %s" % (qid, r.name, r.id, fmt(rank), fmt(matched), outcome, fmt(fused_count),
                     "; ".join("(%s)" % ", ".join(fmt(x) for x in h) for h in holders) or "none listed"))
w("grid.txt", lines)

# ---------------------------------------------------------------- rank table, histogram (G6)
reported = [("default reference", DEFAULT)]
for key in sorted(chosen):
    if chosen[key] not in [r for _, r in reported]:
        reported.append(("chosen %s / %s" % key, chosen[key]))
reported.append(("latency reference", LATREF))
exports = {k: {c["id"]: c for c in load(v)} for k, v in CHUNKS.items() if exists(v)}


def above(r, qid):
    q = r.by_id[qid]
    returned = r.traces[qid]["returnedChunkIds"]
    export = exports[r.store]
    n = len(returned) if q["rank"] is None else q["rank"] - 1
    if q["rank"] is not None:
        assert returned[q["rank"] - 1] == q["matchedChunkId"], (r.file, qid)
    return [(cid, export[cid]["sectionKey"], export[cid]["head"]) for cid in returned[:n]]


lines = ["Rank table (plan Milestone 1, G6), generated by analyse_pool.py from the committed snapshots [observed: results.questions rank and matchedChunkId]",
         "Runs: " + "; ".join("%s = snapshot %d (%s)" % (label, r.id, r.name) for label, r in reported) + ".",
         "rank/matched chunk id; '-' no matched chunk in the window of 10. Chunk ids differ between stores (a rebuild replaces them).", "",
         "%-9s %-10s %-7s %-8s " % ("question", "kind", "ticker", "split") + "  ".join("%-12d" % r.id for _, r in reported)]
for qid in set_ids:
    q0 = DEFAULT.by_id[qid]
    cells = ["%-12s" % ("%s/%s" % (r.by_id[qid]["rank"] if r.by_id[qid]["rank"] is not None else "-", r.by_id[qid]["matchedChunkId"] if r.by_id[qid]["matchedChunkId"] is not None else "-")) for _, r in reported]
    lines.append("%-9s %-10s %-7s %-8s %s" % (qid, q0["kind"], q0["ticker"], "held-out" if qid in HELD_OUT else "tuning", "  ".join(cells)))
lines += ["", "Questions not at rank 1, per run, with the chunks returned above the matched chunk (or the whole window when none is matched) [derived: trace",
          "returnedChunkIds before the stored rank, joined by id to the chunk export of the store the run read; each chunk: id (section, first 80 characters, whitespace collapsed)]"]
non_first = {}
for label, r in reported:
    lines += ["", "%s, snapshot %d (%s; %s):" % (label, r.id, r.name, CHUNKS[r.store])]
    non_first[r.id] = [q for q in set_ids if r.by_id[q]["rank"] != 1]
    for qid in non_first[r.id]:
        q = r.by_id[qid]
        lines.append("  %s: %s, matched chunk %s" % (qid, rank_text(r, qid), q["matchedChunkId"] if q["matchedChunkId"] is not None else "none"))
        for cid, section, head in above(r, qid):
            lines.append("      above: %d (%s, \"%s\")" % (cid, section, head))
w("rank-table.txt", lines)

hist = {}
for r in runs:
    h = {}
    for q in r.questions:
        key = "notInWindow" if q["rank"] is None else str(q["rank"])
        h[key] = h.get(key, 0) + 1
    hist[r.id] = h
lines = ["Rank histogram per reported run (plan Milestone 1, G6), generated by analyse_pool.py [derived: count of results.questions per rank; 'not in window' is a null rank]",
         "Runs: " + "; ".join("%d = %s (%s)" % (r.id, label, r.name) for label, r in reported) + ". The window is 10 results in every run.", "",
         "%-16s" % "rank" + "".join("%7d" % r.id for _, r in reported)]
for rank in list(range(1, 11)) + ["notInWindow"]:
    lines.append("%-16s" % ("not in window" if rank == "notInWindow" else "rank %d" % rank) + "".join("%7d" % hist[r.id].get(str(rank), 0) for _, r in reported))
lines.append("%-16s" % "at rank 1" + "".join("%7d" % hist[r.id].get("1", 0) for _, r in reported))
lines.append("%-16s" % "not at rank 1" + "".join("%7d" % (42 - hist[r.id].get("1", 0)) for _, r in reported))
w("rank-histogram.txt", lines)

# ---------------------------------------------------------------- latency (G7)
lines = ["Latency per run (plan Milestone 1, G7), generated by analyse_pool.py [observed: latency-<id>.json, written by latency.py from the committed application-log windows",
         "app-log-<id>.txt; wall seconds is the curl time_total of the evaluate call]. Retrieval elapsed includes the query embedding, the searches, fusion, and reranking when on.",
         "median: mean of the two middle values; p95: the sorted value at position ceil(0.95 x 42) = 40. A run with a fallback is labelled. No cause is stated.", "",
         "%-6s %-52s | %-8s %-6s %-6s %-6s | %-8s %-6s %-6s %-6s | %-8s %s" % ("id", "run", "retr med", "p95", "max", "min", "rerk med", "p95", "max", "min", "wall s", "fallbacks")]
for r in runs:
    l = r.latency
    a, b = l["retrievalMs"], l["rerankMs"]
    assert l["rerankFallbacks"] == r.fallbacks, r.file
    lines.append("%-6d %-52s | %-8s %-6s %-6s %-6s | %-8s %-6s %-6s %-6s | %-8s %s%s" % ((r.id, r.name, a["median"], a["p95"], a["max"], a["min"]) + ((b["median"], b["p95"], b["max"], b["min"]) if b else ("-", "-", "-", "-"))
                 + (l["wallSeconds"], l["rerankFallbacks"], "  FALLBACK RUN" if l["rerankFallbacks"] else "")))
if HAS_B:
    c = chosen[("B", "on")]
    lines += ["", "Store B's chosen reranked point (snapshot %d, pool %d, 40 inputs, 4000 ms) against the latency reference (snapshot %d, store A, pool 40, 20 inputs, 2000 ms) [observed values side by side]:" % (c.id, c.pool, LATREF.id),
              "  retrieval median %s against %s ms, p95 %s against %s, max %s against %s; rerank median %s against %s ms, p95 %s against %s, max %s against %s; wall %s against %s s; fallbacks %s against %s"
              % (c.latency["retrievalMs"]["median"], LATREF.latency["retrievalMs"]["median"], c.latency["retrievalMs"]["p95"], LATREF.latency["retrievalMs"]["p95"], c.latency["retrievalMs"]["max"], LATREF.latency["retrievalMs"]["max"],
                 c.latency["rerankMs"]["median"], LATREF.latency["rerankMs"]["median"], c.latency["rerankMs"]["p95"], LATREF.latency["rerankMs"]["p95"], c.latency["rerankMs"]["max"], LATREF.latency["rerankMs"]["max"],
                 c.latency["wallSeconds"], LATREF.latency["wallSeconds"], c.latency["rerankFallbacks"], LATREF.latency["rerankFallbacks"])]
w("latency.txt", lines)

# ---------------------------------------------------------------- storage
points = [p for p in ("A", "B", "A2") if exists("storage-%s.json" % p)]
storage = {p: load("storage-%s.json" % p) for p in points}
fields = ["recordedAt", "chunks", "contentChars", "meanChunkChars", "maxChunkChars", "minChunkChars", "tokenCountSum", "maxTokenCount", "totalRelationBytes", "heapBytes", "toastBytes", "indexesBytes",
          "liveTuples", "deadTuples", "lastAutovacuum", "storeVersions"]
lines = ["Storage per point (plan Milestone 1, G7), generated by analyse_pool.py [observed: storage-<point>.json, written by storage.sql; sizes as the pg_* functions reported them at run time,",
         "dead tuples included; no VACUUM was run]. Points: A before anything, B rebuilt at 1650 / 250, A2 after the rollback at 4000 / 500.", "",
         "%-43s " % "field" + " ".join("%-40s" % p for p in points)]
for f in fields:
    lines.append("%-43s " % f + " ".join("%-40s" % fmt(storage[p][f]) for p in points))
for idx in storage["A"]["indexes"]:
    lines.append("%-43s " % ("index " + idx["name"]) + " ".join("%-40s" % fmt(next(i["bytes"] for i in storage[p]["indexes"] if i["name"] == idx["name"])) for p in points))
lines.append("chunks per filing (filingId: %s): " % " / ".join(points) + ", ".join("%d: %s" % (fid, " / ".join(fmt(path_value(storage[p], "filingsDetail[filingId=%d].chunks" % fid)) for p in points)) for fid in FILINGS))
w("storage.txt", lines)

# ---------------------------------------------------------------- phrase gate (G4) and rollback (G8)
def held(report):
    total, n, unheld = 0, 0, []
    for q in report["questions"]:
        for i, p in enumerate(q["phrases"]):
            total += 1
            if p["heldByStoredChunk"]["value"] is True:
                n += 1
            else:
                unheld.append("%s/%d" % (q["id"], i))
    return total, n, unheld


gate_files = [f for f in ("evidence-%d.json" % DEFAULT.id, "evidence-%d-against-B.json" % DEFAULT.id) if exists(f)]
lines = ["Phrase gate (G4) and rollback equality (G8), generated by analyse_pool.py", "",
         "1. Accepted phrases held by a stored chunk [observed: evidence report heldByStoredChunk, evaluated by the application against the store at report time]"]
gate = {}
for f in gate_files:
    gate[f] = held(load(f))
    lines.append("  %s: %d of %d held; not held: %s" % (f, gate[f][1], gate[f][0], ", ".join(gate[f][2]) or "none found"))
for point in ("A", "B", "A2"):
    f = "held-phrases-%s.txt" % point
    if exists(f):
        totals = [l for l in open(os.path.join(HERE, f)).read().splitlines() if l.startswith("HELD_PHRASE TOTALS")][0]
        lines.append("  held_phrases.py direct query at %s: %s" % (point, totals[len("HELD_PHRASE TOTALS "):]))
gate_file = "evidence-%d-against-B.json" % DEFAULT.id
if gate_file in gate:
    ok = gate[gate_file][2] == ["aapl-08/0"] and gate[gate_file][0] == 57
    lines.append("  Gate outcome (derived): %s (the frozen gate: 56 of 57 held, the one not held being aapl-08/0)" % ("HOLDS, store B's grid runs follow" if ok else "DOES NOT HOLD, no grid run on store B"))
rollback = None
if POST:
    hashes = {p: {(c["filingId"], c["chunkIndex"]): c for c in load("chunk-hashes-%s.json" % p)} for p in ("A", "A2")}
    by_id = {p: {c["id"]: c for c in load("chunk-hashes-%s.json" % p)} for p in ("A", "A2")}
    same_store = sum(1 for k, c in hashes["A"].items() if k in hashes["A2"] and (c["chars"], c["contentMd5"], c["sectionKey"]) == (hashes["A2"][k]["chars"], hashes["A2"][k]["contentMd5"], hashes["A2"][k]["sectionKey"]))
    diff_rank, diff_content, same = [], [], 0
    for q in set_ids:
        a, b = DEFAULT.by_id[q], POST.by_id[q]
        if a["rank"] != b["rank"]:
            diff_rank.append(q)
        elif a["matchedChunkId"] is not None:
            ca, cb = by_id["A"][a["matchedChunkId"]], by_id["A2"][b["matchedChunkId"]]
            ea, eb = exports["A"][a["matchedChunkId"]], exports["A2"][b["matchedChunkId"]]
            if (ca["filingId"], ca["chunkIndex"], ca["chars"], ca["contentMd5"], ea["head"]) != (cb["filingId"], cb["chunkIndex"], cb["chars"], cb["contentMd5"], eb["head"]):
                diff_content.append(q)
            else:
                same += 1
        else:
            same += 1
    rollback = (diff_rank, diff_content, same, same_store)
    lines += ["", "2. Rollback: the post-rollback run (snapshot %d) against A / 40 / off (snapshot %d) per question [observed: rank, matchedChunkId; derived: the matched chunks compared by filing," % (POST.id, DEFAULT.id),
              "   chunk index, length, md5 of the content (chunk-hashes-A.json, chunk-hashes-A2.json) and first 80 characters (chunks-A.json, chunks-A2.json); ids differ after a rebuild]",
              "  questions compared %d; rank differing: %s; matched content differing at an equal rank: %s; equal in rank and matched content: %d" % (len(set_ids), ", ".join(diff_rank) or "none found", ", ".join(diff_content) or "none found", same),
              "  store A2 against store A by (filing, chunk index): chunks %d and %d; pairs equal in length, md5 of the content, and section key: %d" % (len(hashes["A2"]), len(hashes["A"]), same_store),
              "  storeVersions after the rollback %s, chunks %s" % (fmt(storage["A2"]["storeVersions"]), storage["A2"]["chunks"]),
              "  G8 outcome (derived): %s" % ("holds" if not diff_rank and not diff_content and same_store == len(hashes["A"]) == len(hashes["A2"]) == 569 and storage["A2"]["storeVersions"] == ["sections-v2-context-v2-chunk4000-500"] else "does not hold")]
w("rollback.txt", lines)

# ---------------------------------------------------------------- experiment files and claims
claims = []
next_id = [FIRST_ID]


appended_id = [APPENDED_FIRST_ID]


def claim(basis, block, check=None, text=None, frm=None, experiment=None, appended=False):
    counter = appended_id if appended else next_id
    c = {"id": "C-%d" % counter[0], "basis": basis, "block": block}
    counter[0] += 1
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


def compare(a, b):
    return "below" if a < b else ("equal to" if a == b else "above")


def experiment_file(a, b, kind):
    name = "experiment-%s-%d-%d.json" % (kind, a.id, b.id)
    if kind == "pool":
        doc = {"description": "Pool %d (snapshot %d) against pool %d (snapshot %d) on %s with reranking %s; the design sets keywordCandidateCount to the same value as candidateCount, so it is coupled. rerank-timeout-ms, not recorded in snapshots, was %d in both (property-diff.txt, from run.log)."
                              % (a.pool, a.id, b.pool, b.id, STORE_NAMES[a.store], a.state, a.timeout),
               "experiment": {"factor": "candidateCount", "settings": [a.pool, b.pool], "coupled": ["keywordCandidateCount"], "snapshots": [a.file, b.file]}}
    else:
        doc = {"description": "Store A at 4000 / 500 (snapshot %d) against store B at 1650 / 250 (snapshot %d), pool %d and reranking %s in both; the rebuild changes chunkOverlapChars and storeVersions together with chunkMaxChars, so they are coupled. rerank-timeout-ms, not recorded in snapshots, was %d in both (property-diff.txt, from run.log)."
                              % (a.id, b.id, a.pool, a.state, a.timeout),
               "experiment": {"factor": "chunkMaxChars", "settings": [4000, 1650], "coupled": ["chunkOverlapChars", "storeVersions"], "snapshots": [a.file, b.file]}}
    out[name] = json.dumps(doc, indent=1) + "\n"
    return name


# reproduction (G2)
b = "reproduction"
rep_ids = [claim("derived", b, {"type": "candidateLists", "reference": REF_1615, "candidate": DEFAULT.file, "compare": "fusedOrder", "expected": []})]
for snap_file, snap in ((REF_1615, ref1615), (DEFAULT.file, DEFAULT.snapshot)):
    rep_ids.append(claim("observed", b, {"type": "metric", "snapshot": snap_file, "metric": "hitAt5", "expected": str(snap["hit_at_5"])}))
    rep_ids.append(claim("observed", b, {"type": "metric", "snapshot": snap_file, "metric": "mrr", "expected": str(snap["mrr"])}))
h1615 = {}
for q in ref1615["results"]["questions"]:
    k = "notInWindow" if q["rank"] is None else str(q["rank"])
    h1615[k] = h1615.get(k, 0) + 1
rep_ids.append(claim("derived", b, {"type": "rankHistogram", "snapshot": REF_1615, "expected": h1615}))
rep_ids.append(claim("derived", b, {"type": "rankHistogram", "snapshot": DEFAULT.file, "expected": hist[DEFAULT.id]}))
ref1615_by_id = {q["id"]: q for q in ref1615["results"]["questions"]}
rep_differing = [q for q in set_ids if (ref1615_by_id[q]["rank"], ref1615_by_id[q]["matchedChunkId"]) != (DEFAULT.by_id[q]["rank"], DEFAULT.by_id[q]["matchedChunkId"])]
rep_ids.append(claim("derived", b, {"type": "questionEquality", "reference": REF_1615, "candidate": DEFAULT.file, "compare": "rankAndMatchedChunk", "expected": rep_differing}, appended=True))
claim("inferred", b, text="The default reference (store A, pool 40, reranking off) has the fused order of snapshot 1615 for the 42 questions, the same stored hit@5 and MRR, the same rank histogram, and %s; the TraceReproductionFilesTests output reproduction-%d.txt (problems=0; a text file, not read by a check) records G2's comparison as run at the time; the runs continued (run.log)"
      % ("the same stored rank and matched chunk id per question" if not rep_differing else "a stored rank or matched chunk id that differs for %s" % ", ".join(rep_differing), DEFAULT.id), frm=rep_ids)

# grid (G3, G5)
b = "grid"
grid_ids = {}
for store in ("A", "B"):
    for state in STATES:
        for pool in POOLS:
            r = grid.get((store, pool, state))
            if not r:
                continue
            t = tuning_metrics(r)
            ids = {}
            ids["hit5"] = claim("observed", b, {"type": "metric", "snapshot": r.file, "metric": "hitAt5", "expected": str(r.snapshot["hit_at_5"])})
            mrr_check = {"type": "metric", "snapshot": r.file, "metric": "mrr", "expected": str(r.snapshot["mrr"])}
            a = grid.get(("A", pool, state))
            if store == "B" and pair_verdict.get((a.id, r.id)):
                ids["mrr"] = claim("experiment", b, mrr_check, text="At pool %d with reranking %s and the other recorded settings equal, store B (1650 / 250) against store A (4000 / 500): the MRR over the 42 questions is %s that of store A's run (%s against %s), and the hit@5 is %s it (%s against %s)"
                                   % (pool, state, compare(r.snapshot["mrr"], a.snapshot["mrr"]), r.snapshot["mrr"], a.snapshot["mrr"], compare(r.snapshot["hit_at_5"], a.snapshot["hit_at_5"]), r.snapshot["hit_at_5"], a.snapshot["hit_at_5"]),
                                   experiment={"file": experiment_file(a, r, "store"), "factor": "chunkMaxChars"})
            else:
                ids["mrr"] = claim("observed", b, mrr_check)
            tun_check = {"type": "subsetMetric", "snapshot": r.file, "questions": TUNING, "metric": "hitAt5", "expected": str(t[2])}
            base = grid.get((store, 40, state))
            if pool != 40 and pair_verdict.get((base.id, r.id)):
                tb = tuning_metrics(base)
                ids["tun5"] = claim("experiment", b, tun_check, text="On %s with reranking %s and the other recorded settings equal, pool %d against pool 40: the tuning hit@5 is %s that of the pool 40 run (%s against %s) and the tuning MRR is %s it (%s against %s)"
                                    % (STORE_NAMES[store], state, pool, compare(t[2], tb[2]), t[2], tb[2], compare(t[3], tb[3]), t[3], tb[3]),
                                    experiment={"file": experiment_file(base, r, "pool"), "factor": "candidateCount"})
            else:
                ids["tun5"] = claim("derived", b, tun_check)
            ids["tunmrr"] = claim("derived", b, {"type": "subsetMetric", "snapshot": r.file, "questions": TUNING, "metric": "mrr", "expected": str(t[3])})
            grid_ids[(store, pool, state)] = ids

# choice (G5)
b = "choice"
choice_ids = {}
for key in sorted(chosen):
    store, state = key
    pools = [p for p in POOLS if (store, p, state) in grid and (store, p, state) not in excluded]
    frm = [grid_ids[(store, p, state)][k] for p in pools for k in ("tun5", "tunmrr")]
    listing = "; ".join("pool %d tuning hit@5 %s and tuning MRR %s" % ((p,) + tuning_metrics(grid[(store, p, state)])[2:4]) for p in pools)
    choice_ids[key] = claim("inferred", b, text="By the frozen choice rule (highest tuning hit@5 over the 28 tuning questions, ties by higher tuning MRR, then the smaller pool), the chosen pool on %s with reranking %s is %d: %s"
                            % (STORE_NAMES[store], state, chosen[key].pool, listing), frm=frm)

# reported runs: metrics, histograms
b = "reported"
for label, r in reported:
    for m, col in (("hitAt1", "hit_at_1"), ("hitAt3", "hit_at_3")) + ((("hitAt5", "hit_at_5"), ("mrr", "mrr")) if r.role != "grid" else ()):
        claim("observed", b, {"type": "metric", "snapshot": r.file, "metric": m, "expected": str(r.snapshot[col])})
    for s in ("figure", "nonFigure"):
        claim("observed", b, {"type": "metric", "snapshot": r.file, "metric": "slices.%s.hitAt5" % s, "expected": str(r.snapshot["results"]["slices"][s]["hitAt5"])})
    for t in ("AAPL", "MSFT", "NVDA"):
        claim("observed", b, {"type": "metric", "snapshot": r.file, "metric": "tickerHitAt5." + t, "expected": str(r.snapshot["results"]["tickerHitAt5"][t])})
    claim("derived", b, {"type": "rankHistogram", "snapshot": r.file, "expected": hist[r.id]})

if HAS_B:
    # top-5 membership of B's chosen points (G6) and the held-out test (G5)
    b = "membership"
    member_ids = {}
    for state in STATES:
        bp = chosen[("B", state)]
        for ref in (DEFAULT, chosen[("A", state)]):
            entering, leaving = membership[(bp.id, ref.id)]
            for q in entering + leaving:
                if (q, ref.id, bp.id) in member_ids:
                    continue
                member_ids[(q, ref.id, bp.id)] = claim("derived", b, {"type": "topK", "question": q, "k": 5, "rows": [{"snapshot": ref.file, "expected": "inside" if q in leaving else "outside"},
                                                                                                                 {"snapshot": bp.file, "expected": "outside" if q in leaving else "inside"}]})
    b = "heldout"
    held_ids = {}
    for label, r in held_runs:
        if r.id in held_ids:
            continue
        h = held_metrics(r)
        held_ids[r.id] = claim("derived", b, {"type": "subsetMetric", "snapshot": r.file, "questions": HELD_OUT, "metric": "hitAt5", "expected": str(h[2])})
        claim("derived", b, {"type": "subsetMetric", "snapshot": r.file, "questions": HELD_OUT, "metric": "mrr", "expected": str(h[3])})
    test_ids = {}
    for state in STATES:
        bp, ap, hb, hd, ha, outcome, deciding = test[state]
        frm = [held_ids[bp.id], held_ids[DEFAULT.id]] + ([held_ids[ap.id]] if ap.id != DEFAULT.id else [])
        parts = []
        for ref, ref_name in ((DEFAULT, "the default reference"), (ap, "store A's chosen point (pool %d)" % ap.pool)):
            if ref is ap and ap.id == DEFAULT.id:
                continue
            e, l, o = deciding[ref.id]
            frm += [member_ids[(q, ref.id, bp.id)] for q in e + l]
            parts.append("against %s, held-out questions inside the top 5 at store B's point only: %s; inside the top 5 at %s only: %s" % (ref_name, ", ".join(e) or "not found", ref_name, ", ".join(l) or "not found"))
        same = " (store A's chosen point with reranking off is the default reference)" if ap.id == DEFAULT.id else ""
        test_ids[state] = claim("inferred", b, text="Held-out test with reranking %s: %s. Held-out hit@5 over the 14 held-out questions of store B's chosen point (pool %d, snapshot %d) is %s, against %s for the default reference (snapshot %d) and %s for store A's chosen point (pool %d, snapshot %d)%s; the frozen rule passes when the first value is at least the other two; %s"
                                % (state, outcome, bp.pool, bp.id, hb, hd, DEFAULT.id, ha, ap.pool, ap.id, same, "; ".join(parts)), frm=list(dict.fromkeys(frm)))

    # every question not at rank 1 at B's chosen points, with the chunks above it (G6)
    b = "ranks"
    for state in STATES:
        r = chosen[("B", state)]
        for q in non_first[r.id]:
            claim("derived", b, {"type": "rankedAbove", "snapshot": r.file, "question": q, "chunks": CHUNKS[r.store], "expected": [cid for cid, _, _ in above(r, q)]})

# nvda-02 and nvda-04 in every grid run (G6)
b = "nvda"
for qid in FOCUS:
    for key in sorted(grid, key=lambda k: (k[0], STATES.index(k[2]), k[1])):
        r = grid[key]
        rank, matched, outcome, fused_count, holders = focus(r, qid)
        claim("observed", b, {"type": "rank", "snapshot": r.file, "question": qid, "expected": dec(rank)})
        if matched is not None:
            claim("observed", b, {"type": "matchedChunk", "snapshot": r.file, "question": qid, "expected": dec(matched)})
        fused = [h for h in holders if h[1] is not None]
        best = min(fused, key=lambda h: h[1]) if fused else (holders[0] if holders else None)
        if best is not None:
            expected = {"fusedPosition": dec(best[1]), "rerankInput": best[2]}
            if r.state == "on":
                expected["rerankedPosition"] = dec(best[3])
            claim("observed", b, {"type": "candidate", "report": r.evidence_file, "question": qid, "chunk": dec(best[0]), "expected": expected})

# latency (G7)
b = "latency"
for r in runs:
    claim("observed", b, {"type": "fileValue", "file": r.latency_file, "path": "retrievalMs", "expected": {k: dec(v) for k, v in r.latency["retrievalMs"].items()}})
    if r.latency["rerankMs"]:
        claim("observed", b, {"type": "fileValue", "file": r.latency_file, "path": "rerankMs", "expected": {k: dec(v) for k, v in r.latency["rerankMs"].items()}})
    claim("observed", b, {"type": "fileValue", "file": r.latency_file, "path": "wallSeconds", "expected": dec(r.latency["wallSeconds"])})
    if r.state == "on":
        claim("observed", b, {"type": "fileValue", "file": r.latency_file, "path": "rerankFallbacks", "expected": dec(r.latency["rerankFallbacks"])})

# storage (G7)
b = "storage"
for p in points:
    for field in STORAGE_FIELDS:
        v = storage[p][field]
        claim("observed", b, {"type": "fileValue", "file": "storage-%s.json" % p, "path": field, "expected": v if isinstance(v, list) else dec(v)})

# phrase gate (G4)
b = "gate"
gate_ids = []
for f in gate_files:
    gate_ids.append(claim("derived", b, {"type": "heldPhrases", "report": f, "expected": {"phrases": gate[f][0], "held": gate[f][1]}}))
if gate_file in gate:
    ok = gate[gate_file][2] == ["aapl-08/0"]
    claim("inferred", b, text="The phrase gate on store B %s: the default reference's report read against store B records 56 of the 57 accepted phrases held by a stored chunk, the one not held being aapl-08's (exempt under the frozen gate, Follow_Ups RAG-29); the direct query held_phrases.py records the same counts in held-phrases-B.txt (a text file, not read by a check); store B's grid runs followed" % ("holds" if ok else "does not hold"), frm=gate_ids[-1:]) if ok else \
        claim("inferred", b, text="The phrase gate on store B does not hold (not held: %s), so store B's grid was not run and the store was rebuilt back" % ", ".join(gate[gate_file][2]), frm=gate_ids[-1:])

# rollback (G8)
if POST:
    b = "rollback"
    roll_ids = []
    for r in (DEFAULT, POST):
        roll_ids.append(claim("derived", b, {"type": "rankHistogram", "snapshot": r.file, "expected": hist[r.id]}))
    for m, col in (("hitAt5", "hit_at_5"), ("mrr", "mrr")):
        roll_ids.append(claim("observed", b, {"type": "metric", "snapshot": POST.file, "metric": m, "expected": str(POST.snapshot[col])}))
    for field in ("chunks", "storeVersions", "contentChars"):
        v = storage["A2"][field]
        roll_ids.append(claim("observed", b, {"type": "fileValue", "file": "storage-A2.json", "path": field, "expected": v if isinstance(v, list) else dec(v)}))
    diff_rank, diff_content, same, same_store = rollback
    store_ids = list(roll_ids[-3:])
    hash_by_id = {p: {c["id"]: (c["filingId"], c["chunkIndex"], c["chars"], c["contentMd5"]) for c in load("chunk-hashes-%s.json" % p)} for p in ("A", "A2")}
    roll_differing = [q for q in set_ids if DEFAULT.by_id[q]["rank"] != POST.by_id[q]["rank"] or (DEFAULT.by_id[q]["matchedChunkId"] is not None and
                      hash_by_id["A"][DEFAULT.by_id[q]["matchedChunkId"]] != hash_by_id["A2"][POST.by_id[q]["matchedChunkId"]])]
    assert set(roll_differing) <= set(diff_rank + diff_content)
    roll_ids.append(claim("derived", b, {"type": "questionEquality", "reference": DEFAULT.file, "candidate": POST.file, "compare": "rankAndMatchedContent",
                                         "referenceChunks": "chunk-hashes-A.json", "candidateChunks": "chunk-hashes-A2.json", "expected": roll_differing}, appended=True))
    claim("inferred", b, text="After the rollback rebuild the store is at sections-v2-context-v2-chunk4000-500 with 569 chunks, and the post-rollback run %s (chunk ids differ after a rebuild; the check reads the md5 the two exports record, not the chunk text); rollback.txt, written by analyse_pool.py and not read by a check, adds the first characters of the matched chunks (content differing there: %s) and the comparison of the two stores chunk by chunk; G8 %s"
          % ("equals the default reference per question in stored rank and in the matched chunk's filing, chunk index, length, and content md5 for the 42 questions" if not roll_differing
             else "differs from the default reference in stored rank or matched chunk for %s" % ", ".join(roll_differing),
             ", ".join(diff_content) or "not found", "holds" if not diff_rank and not diff_content and not roll_differing else "does not hold"), frm=roll_ids)

# decision
if HAS_B and POST:
    claim("inferred", "decision", text="The held-out test of store B's chosen point is %s with reranking off and %s with reranking on; under the frozen design a further grid, size, overlap, or split does not follow in this plan; the store is back at 4000 / 500 (the chunk count, store version, and content length of the storage export after the rollback), and whether to adopt a size and pool is DECISION RAG-30 for Jay" % (test["off"][5], test["on"][5]), frm=[test_ids["off"], test_ids["on"]] + store_ids)

assert next_id[0] == APPENDED_FIRST_ID, "the first version's claims end at C-%d, found C-%d: an earlier id moved" % (APPENDED_FIRST_ID - 1, next_id[0] - 1)
assert appended_id[0] - 1 <= LAST_ID, "claim ids past C-%d: %d" % (LAST_ID, appended_id[0] - 1)
labels = {DEFAULT.file: "the default reference", LATREF.file: "the latency reference", REF_1615: "the previous plan's post-rollback run"}
if POST:
    labels[POST.file] = "the post-rollback run"
for key in sorted(chosen):
    r = chosen[key]
    if r.file not in labels:
        labels[r.file] = "store %s's chosen point with reranking %s" % key
for p, text in (("A", "storage before the rebuild"), ("B", "storage of the rebuilt store"), ("A2", "storage after the rollback")):
    if p in points:
        labels["storage-%s.json" % p] = text
if gate_file in gate:
    labels[gate_file] = "the default reference's report read against the rebuilt store"
if HAS_B:
    labels[CHUNKS["B"]] = "the chunk export of the rebuilt store"
labels["chunk-hashes-A.json"] = "the content hashes before the rebuild"
labels["chunk-hashes-A2.json"] = "the content hashes after the rollback"
used = json.dumps(claims)
labels = {k: v for k, v in labels.items() if json.dumps(k)[1:-1] in used}
doc = {"description": "Chunk size and candidate pool (plan plans/2026-09-17-chunk-size-pool.md, Milestone 1), written by analyse_pool.py: the reproduction of snapshot 1615; the grid of pools 40, 100, 200, 250 with reranking off and on (40 inputs, 4000 ms) on store A (4000 / 500) and store B (1650 / 250) with stored and tuning metrics, pool pairs and store pairs as one-factor experiment claims; the choice per store and reranker state on the 28 tuning questions; the held-out test of store B's chosen points on the 14 held-out questions; metrics, rank histograms, and top-5 membership of the reported runs; the chunks above every question not at rank 1 at store B's chosen points; nvda-02 and nvda-04 in every grid run; latency of every run; storage at three points; the phrase gate; and the rollback. No claim states why a rank, a time, or a size changed.",
       "labels": labels, "claims": claims}
out["claims.json"] = json.dumps(doc, indent=1) + "\n"
print("claims %d (C-%d to C-%d)" % (len(claims), FIRST_ID, appended_id[0] - 1))


for name, text in out.items():
    with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
        f.write(text)
print("written: %s" % ", ".join(out))

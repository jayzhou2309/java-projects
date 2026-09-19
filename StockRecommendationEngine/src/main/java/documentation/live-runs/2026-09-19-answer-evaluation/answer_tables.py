#!/usr/bin/env python3
"""Aggregate, per-question and cross tables of the answer-evaluation pilot and full pass, from committed files only.

Reads, beside this file: the two answer-evaluation exports given on the command line (pilot first, full pass second;
`GET /api/rag/evaluate/answers/{id}` saved verbatim), phrase-offsets.json (export_phrase_offsets.py), and the retrieval
reference ../2026-09-18-rag29-section-key/snapshot-1891-f-defaults-rerank-off.json (defaults, reranking off). Reads no
database and calls nothing. Writes tables.json (read by claims.json through `fileValue`) and tables.txt (for people).

Every aggregate of each export is recomputed here from its per-question rows by the definitions of
AnswerEvaluationService.aggregate (Agent_Harness.md, Answer Evaluation, Aggregates) and compared with the stored value;
`aggregatesMatchStored` and `aggregateDifferences` record the outcome.

The tables count and list. They state no reason for any answer or for any retrieval result. The retrieval reference ran
the set's question text through the retrieval endpoint at its defaults (window 10); an answer-evaluation run retrieves
with the lean profile's search-top-k and with the queries the run itself made, so the two are set side by side, not
equated.

Usage: python3 -B answer_tables.py get-37.json get-38.json
"""
import json
import sys
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

HERE = Path(__file__).resolve().parent
REFERENCE = HERE.parent / "2026-09-18-rag29-section-key" / "snapshot-1891-f-defaults-rerank-off.json"
ANSWERED_STATUSES = ("COMPLETE", "PARTIAL", "INSUFFICIENT_EVIDENCE")
MEASURES = ("retrievedExpected", "visibleToModel", "citedExpected", "figuresInReasoning", "status", "assessment",
            "criticVerdict", "expectedChunkIds", "visibleChunkIds", "citedChunkIds", "citedCount", "citedHoldingPhrase",
            "retrievedCount", "limitations", "unsupportedNumerals", "modelCalls")
ID_LISTS = ("expectedChunkIds", "visibleChunkIds", "citedChunkIds", "limitations", "unsupportedNumerals")
CALLED_OUT = ("nvda-02", "nvda-04", "aapl-08")


def comparable(field, value):
    """Id and code lists are compared as sets (sorted): the order of cited ids is the order of the model's output, not a measure."""
    return sorted(value, key=str) if field in ID_LISTS and isinstance(value, list) else value


def share(numerator, denominator):
    if denominator == 0:
        return None
    return float((Decimal(numerator) / Decimal(denominator)).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP))


def answered(row):
    return row.get("status") in ANSWERED_STATUSES and bool((row.get("reasoning") or "").strip())


def strip_id(code):
    head, _, tail = code.rpartition(":")
    return head if head and tail.isdigit() else code


def recompute(results):
    with_run = [r for r in results if r.get("runId") is not None]
    measured = [r for r in with_run if r.get("evidenceCaptured")]
    ans = [r for r in with_run if answered(r)]
    measured_ans = [r for r in ans if r.get("evidenceCaptured")]

    def count(rows, test):
        return sum(1 for r in rows if test(r))

    def family(rows_measured, rows_all):
        retrieved = count(rows_measured, lambda r: r.get("retrievedExpected") is True)
        visible = count(rows_measured, lambda r: r.get("visibleToModel") is True)
        cited_visible = count(rows_measured, lambda r: r.get("visibleToModel") is True and r.get("citedExpected") is True)
        figure_questions = count(rows_all, lambda r: r.get("figuresInReasoning") is not None)
        figures = count(rows_all, lambda r: r.get("figuresInReasoning") is True)
        insufficient = count(rows_all, lambda r: r.get("status") == "INSUFFICIENT_EVIDENCE")
        return retrieved, visible, cited_visible, figure_questions, figures, insufficient

    r, v, cv, fq, f, ins = family(measured, with_run)
    ra, va, cva, fqa, fa, insa = family(measured_ans, ans)
    statuses, limitations = {}, {}
    for row in with_run:
        statuses[str(row.get("status"))] = statuses.get(str(row.get("status")), 0) + 1
        for code in row.get("limitations") or []:
            limitations[strip_id(code)] = limitations.get(strip_id(code), 0) + 1
    return {
        "attempted": len(results), "withRun": len(with_run), "measured": len(measured),
        "retrieved": r, "shareRetrieved": share(r, len(measured)),
        "visible": v, "shareVisibleGivenRetrieved": share(v, r),
        "citedAndVisible": cv, "shareCitedGivenVisible": share(cv, v),
        "figureQuestions": fq, "figuresInReasoning": f, "shareFiguresInReasoning": share(f, fq),
        "insufficientEvidence": ins, "insufficientEvidenceRate": share(ins, len(with_run)),
        "invalidCitationRuns": statuses.get("INVALID_CITATION", 0),
        "statusCounts": dict(sorted(statuses.items())), "limitationCounts": dict(sorted(limitations.items())),
        "totalTokens": sum(row.get("observedTokens") or 0 for row in with_run),
        "totalModelCalls": sum(row.get("modelCalls") or 0 for row in with_run),
        "totalElapsedMs": sum(row.get("elapsedMs") or 0 for row in results),
        "answeredRuns": len(ans), "noAnswerRuns": len(with_run) - len(ans),
        "measuredAmongAnswered": len(measured_ans),
        "retrievedAmongAnswered": ra, "shareRetrievedAmongAnswered": share(ra, len(measured_ans)),
        "visibleAmongAnswered": va, "shareVisibleGivenRetrievedAmongAnswered": share(va, ra),
        "citedAndVisibleAmongAnswered": cva, "shareCitedGivenVisibleAmongAnswered": share(cva, va),
        "figureQuestionsAmongAnswered": fqa, "figuresInReasoningAmongAnswered": fa,
        "shareFiguresInReasoningAmongAnswered": share(fa, fqa),
        "insufficientAmongAnswered": insa, "insufficientEvidenceRateAmongAnswered": share(insa, len(ans)),
    }


def differences(stored, computed):
    out = []
    for key in sorted(set(stored) | set(computed)):
        a, b = stored.get(key, "<absent>"), computed.get(key, "<absent>")
        same = (abs(a - b) < 5e-7) if isinstance(a, float) and isinstance(b, float) else a == b
        if not same:
            out.append({"field": key, "stored": a, "recomputed": b})
    return out


def pass_summary(export):
    computed = recompute(export["results"])
    diffs = differences(export["aggregates"], computed)
    tokens = [(r.get("observedTokens") or 0, r["id"]) for r in export["results"] if r.get("runId") is not None]
    total = sum(t for t, _ in tokens)
    top = max(tokens) if tokens else (0, None)
    return {
        "snapshot": export["id"], "evaluatedAt": export["evaluatedAt"], "questionCount": export["questionCount"],
        "attempted": export["attempted"], "partial": export["partial"], "partialReason": export["partialReason"],
        "notAttempted": export.get("notAttempted") or [],
        "aggregatesMatchStored": not diffs, "aggregateDifferences": diffs, "aggregates": computed,
        "tokens": {"total": total, "runs": len(tokens),
                   "meanPerRun": float((Decimal(total) / Decimal(len(tokens))).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)) if tokens else None,
                   "maxPerRun": top[0], "maxPerRunQuestion": top[1]},
    }


def rank_group(rank):
    if rank is None:
        return "notInWindow"
    return "top3" if rank <= 3 else "rank4to10"


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: answer_tables.py <pilot export> <full-pass export>")
    pilot, full = (json.loads((HERE / name).read_text(encoding="utf-8")) for name in argv)
    reference = json.loads(REFERENCE.read_text(encoding="utf-8"))
    offsets = json.loads((HERE / "phrase-offsets.json").read_text(encoding="utf-8"))
    ref_rows = {row["id"]: row for row in reference["results"]["questions"]}
    chunk_offsets = {c["chunkId"]: c for c in offsets["chunks"]}
    cut = full["properties"]["modelPassageChars"]

    rows = []
    for r in full["results"]:
        ref = ref_rows.get(r["id"], {})
        expected = r.get("expectedChunkIds") or []
        rows.append({
            "question": r["id"], "kind": r.get("kind"), "referenceRank": ref.get("rank"),
            "referenceMatchedChunk": ref.get("matchedChunkId"),
            "referenceMatchedChunkRetrievedInRun": (ref.get("matchedChunkId") in expected) if ref.get("matchedChunkId") is not None and r.get("expectedChunkIds") is not None else None,
            "retrievedExpected": r.get("retrievedExpected"), "visibleToModel": r.get("visibleToModel"),
            "citedExpected": r.get("citedExpected"), "figuresInReasoning": r.get("figuresInReasoning"),
            "status": r.get("status"), "assessment": r.get("assessment"), "criticVerdict": r.get("criticVerdict"),
            "answered": answered(r), "error": r.get("error"), "observedTokens": r.get("observedTokens"),
            "expectedChunkIds": expected, "visibleChunkIds": r.get("visibleChunkIds") or [],
            "citedChunkIds": r.get("citedChunkIds") or [], "limitations": r.get("limitations") or [],
            "unsupportedNumerals": r.get("unsupportedNumerals") or [],
        })

    # (a) retrieval rank in the reference against retrievedExpected of the run
    by_rank = {}
    for group in ("top3", "rank4to10", "notInWindow"):
        members = [row for row in rows if rank_group(row["referenceRank"]) == group]
        by_rank[group] = {
            "questions": len(members),
            "retrievedExpected": sum(1 for m in members if m["retrievedExpected"] is True),
            "notRetrieved": sum(1 for m in members if m["retrievedExpected"] is False),
            "notMeasured": sum(1 for m in members if m["retrievedExpected"] is None),
            "retrievedExpectedIds": [m["question"] for m in members if m["retrievedExpected"] is True],
            "notRetrievedIds": [m["question"] for m in members if m["retrievedExpected"] is False],
            "citedExpected": sum(1 for m in members if m["citedExpected"] is True),
            "citedExpectedIds": [m["question"] for m in members if m["citedExpected"] is True],
            "referenceMatchedChunkRetrievedInRun": sum(1 for m in members if m["referenceMatchedChunkRetrievedInRun"] is True),
            "referenceMatchedChunkNotRetrievedInRunIds": [m["question"] for m in members if m["referenceMatchedChunkRetrievedInRun"] is False],
        }

    # hit@5 of the reference against the expected citation (the pointer for RAG-27 and RAG-30)
    by_hit = {}
    for name, test in (("referenceRank1to5", lambda k: k is not None and k <= 5), ("referenceRankAbove5OrNone", lambda k: k is None or k > 5)):
        members = [row for row in rows if test(row["referenceRank"])]
        by_hit[name] = {
            "questions": len(members),
            "retrievedExpected": sum(1 for m in members if m["retrievedExpected"] is True),
            "visibleToModel": sum(1 for m in members if m["visibleToModel"] is True),
            "citedExpected": sum(1 for m in members if m["citedExpected"] is True),
            "shareCitedExpected": share(sum(1 for m in members if m["citedExpected"] is True), len(members)),
            "citedExpectedIds": [m["question"] for m in members if m["citedExpected"] is True],
            "notCitedExpectedIds": [m["question"] for m in members if m["citedExpected"] is not True],
        }
        # the split of citedExpected (added in Milestone 3 remediation round 1): visible or not, and the runs' statuses
        cited = [m for m in members if m["citedExpected"] is True]
        statuses = {}
        for m in cited:
            statuses[m["status"]] = statuses.get(m["status"], 0) + 1
        by_hit[name].update({
            "citedAndVisible": sum(1 for m in cited if m["visibleToModel"] is True),
            "citedNotVisible": sum(1 for m in cited if m["visibleToModel"] is not True),
            "citedNotVisibleIds": [m["question"] for m in cited if m["visibleToModel"] is not True],
            "citedExpectedStatusCounts": dict(sorted(statuses.items(), key=lambda kv: (-kv[1], kv[0]))),
            "notCitedExpectedStatuses": {m["question"]: m["status"] for m in members if m["citedExpected"] is not True},
        })

    # (b) among retrieved: visible or cut out of view, with the phrase offsets of the cut-out questions
    retrieved = [row for row in rows if row["retrievedExpected"] is True]
    cut_rows, offset_disagreements = [], []
    for row in retrieved:
        for chunk_id in row["expectedChunkIds"]:
            entry = chunk_offsets.get(chunk_id)
            phrases = [p for p in (entry or {}).get("phrases", []) if p["question"] == row["question"]]
            inside = any(p["insideCut"] for p in phrases) if phrases else None
            if inside is None or inside != (chunk_id in row["visibleChunkIds"]):
                offset_disagreements.append({"question": row["question"], "chunk": chunk_id, "insideCutByOffsets": inside,
                                             "visibleInSnapshot": chunk_id in row["visibleChunkIds"]})
            if row["visibleToModel"] is False:
                for p in phrases:
                    cut_rows.append({"question": row["question"], "chunk": chunk_id, "contentChars": entry["contentChars"],
                                     "phraseStart": p["firstStart"], "phraseEnd": p["firstEnd"], "occurrences": len(p["occurrences"]),
                                     "cited": chunk_id in row["citedChunkIds"]})
    if len({c["question"] for c in cut_rows}) != len(cut_rows):
        raise SystemExit("a cut-out question has several phrase rows: the per-question maps below would drop one")
    among_retrieved = {
        "questions": len(retrieved), "modelPassageChars": cut,
        "visible": sum(1 for row in retrieved if row["visibleToModel"] is True),
        "cutOutOfView": sum(1 for row in retrieved if row["visibleToModel"] is False),
        "cutOutOfViewIds": [row["question"] for row in retrieved if row["visibleToModel"] is False],
        "cutOutOfViewCitedExpectedIds": [row["question"] for row in retrieved if row["visibleToModel"] is False and row["citedExpected"] is True],
        "cutOutPhrases": cut_rows,
        "cutOutPhraseSpanByQuestion": {c["question"]: [c["phraseStart"], c["phraseEnd"]] for c in cut_rows},
        "cutOutChunkByQuestion": {c["question"]: c["chunk"] for c in cut_rows},
        "cutOutPhrasesStraddlingTheCut": [c["question"] for c in cut_rows if c["phraseStart"] < cut < c["phraseEnd"]],
        "offsetsAgreeWithSnapshot": not offset_disagreements, "offsetDisagreements": offset_disagreements,
    }

    # the reference's matched chunk under the same cut, whether or not a run retrieved it
    ref_under_cut = {"insideCut": [], "beyondCut": [], "noMatchedChunk": []}
    ref_offsets = []
    for row in rows:
        chunk_id = row["referenceMatchedChunk"]
        if chunk_id is None:
            ref_under_cut["noMatchedChunk"].append(row["question"])
            continue
        entry = chunk_offsets.get(chunk_id) or {}
        phrases = [p for p in entry.get("phrases", []) if p["question"] == row["question"]]
        inside = any(p["insideCut"] for p in phrases)
        ref_under_cut["insideCut" if inside else "beyondCut"].append(row["question"])
        if not inside:
            for p in phrases:
                ref_offsets.append({"question": row["question"], "chunk": chunk_id, "contentChars": entry.get("contentChars"),
                                    "phraseStart": p["firstStart"], "phraseEnd": p["firstEnd"]})
    reference_cut = {"modelPassageChars": cut, "insideCut": len(ref_under_cut["insideCut"]), "beyondCut": len(ref_under_cut["beyondCut"]),
                     "noMatchedChunk": len(ref_under_cut["noMatchedChunk"]), "beyondCutIds": ref_under_cut["beyondCut"],
                     "noMatchedChunkIds": ref_under_cut["noMatchedChunk"], "beyondCutPhrases": ref_offsets}

    # (c) among visible: cited or not
    visible = [row for row in rows if row["visibleToModel"] is True]
    among_visible = {
        "questions": len(visible),
        "cited": sum(1 for row in visible if row["citedExpected"] is True),
        "notCited": sum(1 for row in visible if row["citedExpected"] is not True),
        "notCitedIds": [row["question"] for row in visible if row["citedExpected"] is not True],
        "notCitedStatuses": {row["question"]: row["status"] for row in visible if row["citedExpected"] is not True},
    }

    # (d) INSUFFICIENT_EVIDENCE and no-answer runs
    insufficient = [row for row in rows if row["status"] == "INSUFFICIENT_EVIDENCE"]
    no_answer = [row for row in rows if not row["answered"]]
    listed = {
        "insufficientEvidence": len(insufficient), "insufficientEvidenceIds": [row["question"] for row in insufficient],
        "insufficientEvidenceRetrievedIds": [row["question"] for row in insufficient if row["retrievedExpected"] is True],
        "insufficientEvidenceVisibleIds": [row["question"] for row in insufficient if row["visibleToModel"] is True],
        "insufficientEvidenceCitedIds": [row["question"] for row in insufficient if row["citedExpected"] is True],
        "noAnswer": len(no_answer), "noAnswerIds": [row["question"] for row in no_answer],
        "noAnswerStatuses": {row["question"]: row["status"] for row in no_answer},
    }

    # figures, critic
    figure_rows = [row for row in rows if row["figuresInReasoning"] is not None]
    kinds = {}
    for row in rows:
        kinds[str(row["kind"])] = kinds.get(str(row["kind"]), 0) + 1
    figures = {
        "kindCounts": dict(sorted(kinds.items())),
        "figureKindWithoutFigureCheckIds": [row["question"] for row in rows if row["kind"] == "FIGURE" and row["figuresInReasoning"] is None],
        "figureQuestions": len(figure_rows),
        "figuresInReasoning": sum(1 for row in figure_rows if row["figuresInReasoning"] is True),
        "figuresNotInReasoningIds": [row["question"] for row in figure_rows if row["figuresInReasoning"] is False],
        "figuresInReasoningAmongCitedExpected": sum(1 for row in figure_rows if row["figuresInReasoning"] is True and row["citedExpected"] is True),
        "figureQuestionsCitedExpected": sum(1 for row in figure_rows if row["citedExpected"] is True),
    }
    verdicts = {}
    for row in rows:
        verdicts[str(row["criticVerdict"])] = verdicts.get(str(row["criticVerdict"]), 0) + 1
    critic = {"verdictCounts": dict(sorted(verdicts.items())),
              "unsupportedNumeralIds": [row["question"] for row in rows if row["unsupportedNumerals"]]}

    # (e) the pilot's questions against the same questions of the full pass
    full_by_id = {r["id"]: r for r in full["results"]}
    comparison = []
    for p in pilot["results"]:
        f = full_by_id.get(p["id"])
        if f is None:
            comparison.append({"question": p["id"], "inFullPass": False, "differingFields": None})
            continue
        differing = [m for m in MEASURES if comparable(m, p.get(m)) != comparable(m, f.get(m))]
        comparison.append({"question": p["id"], "inFullPass": True, "differingFields": differing,
                           "pilot": {m: p.get(m) for m in differing}, "full": {m: f.get(m) for m in differing},
                           "pilotTokens": p.get("observedTokens"), "fullTokens": f.get("observedTokens")})
    headline = ("retrievedExpected", "visibleToModel", "citedExpected", "figuresInReasoning", "status", "assessment", "criticVerdict")
    pilot_against_full = {
        "questions": [c["question"] for c in comparison],
        "equalOnEveryComparedField": [c["question"] for c in comparison if c["differingFields"] == []],
        "differing": [c["question"] for c in comparison if c["differingFields"]],
        "differingOnHeadlineMeasures": [c["question"] for c in comparison if c["differingFields"] and any(m in headline for m in c["differingFields"])],
        "comparedFields": list(MEASURES), "headlineMeasures": list(headline), "perQuestion": comparison,
    }

    tables = {
        "description": "Written by answer_tables.py from the two answer-evaluation exports, phrase-offsets.json and retrieval snapshot "
                       "1891; counts and lists only. referenceRank is the question's stored rank in the retrieval reference (window 10, "
                       "defaults); the other measures are the full pass's unless a key says pilot.",
        "inputs": {"pilot": argv[0], "full": argv[1], "retrievalReference": reference["id"], "phraseOffsets": "phrase-offsets.json"},
        "passes": {"pilot": pass_summary(pilot), "full": pass_summary(full)},
        "full": {"byReferenceRank": by_rank, "byReferenceHitAt5": by_hit, "amongRetrieved": among_retrieved,
                 "referenceMatchedChunkUnderCut": reference_cut, "amongVisible": among_visible, "listed": listed,
                 "figures": figures, "critic": critic,
                 "calledOut": {row["question"]: row for row in rows if row["question"] in CALLED_OUT}},
        "pilotAgainstFull": pilot_against_full,
        "rows": rows,
    }
    (HERE / "tables.json").write_text(json.dumps(tables, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    (HERE / "tables.txt").write_text(render(tables, pilot, full), encoding="utf-8")
    print("tables written: pilot %s full %s aggregatesMatchStored=%s/%s offsetsAgree=%s" % (
        pilot["id"], full["id"], tables["passes"]["pilot"]["aggregatesMatchStored"], tables["passes"]["full"]["aggregatesMatchStored"],
        among_retrieved["offsetsAgreeWithSnapshot"]))


def show(value):
    if value is None:
        return "-"
    if value is True:
        return "yes"
    if value is False:
        return "no"
    return str(value)


def table(header, lines):
    widths = [max(len(str(x)) for x in column) for column in zip(header, *lines)] if lines else [len(h) for h in header]
    out = ["  ".join(str(h).ljust(w) for h, w in zip(header, widths)).rstrip(),
           "  ".join("-" * w for w in widths)]
    out += ["  ".join(str(x).ljust(w) for x, w in zip(line, widths)).rstrip() for line in lines]
    return "\n".join(out) + "\n"


def render(t, pilot, full):
    out = []
    p, f = t["passes"]["pilot"], t["passes"]["full"]
    out.append("Answer evaluation tables (answer_tables.py; inputs %s, %s, phrase-offsets.json, retrieval snapshot %s)\n" % (
        t["inputs"]["pilot"], t["inputs"]["full"], t["inputs"]["retrievalReference"]))
    out.append("Counts and lists only; no table states a reason for an answer or a retrieval result.\n\n")
    out.append("1. Passes\n")
    keys = ["snapshot", "evaluatedAt", "questionCount", "attempted", "partial", "partialReason", "aggregatesMatchStored"]
    lines = [[k, show(p[k]), show(f[k])] for k in keys]
    for k in ("total", "runs", "meanPerRun", "maxPerRun", "maxPerRunQuestion"):
        lines.append(["tokens." + k, show(p["tokens"][k]), show(f["tokens"][k])])
    for k, v in p["aggregates"].items():
        if isinstance(v, dict):
            continue
        lines.append([k, show(v), show(f["aggregates"][k])])
    for name in ("statusCounts", "limitationCounts"):
        for code in sorted(set(p["aggregates"][name]) | set(f["aggregates"][name])):
            lines.append([name + "." + code, show(p["aggregates"][name].get(code, 0)), show(f["aggregates"][name].get(code, 0))])
    out.append(table(["field", "pilot", "full pass"], lines))
    for label, s in (("pilot", p), ("full pass", f)):
        for d in s["aggregateDifferences"]:
            out.append("AGGREGATE DIFFERENCE (%s): %s stored %s recomputed %s\n" % (label, d["field"], d["stored"], d["recomputed"]))

    out.append("\n2. Full pass per question (rank: stored rank in retrieval snapshot %s, window 10; - = none in the window or not applicable)\n" % t["inputs"]["retrievalReference"])
    lines = [[r["question"], r["kind"], show(r["referenceRank"]), show(r["retrievedExpected"]), show(r["visibleToModel"]), show(r["citedExpected"]),
              show(r["figuresInReasoning"]), show(r["status"]), show(r["assessment"]), show(r["criticVerdict"]), show(r["observedTokens"])] for r in t["rows"]]
    out.append(table(["question", "kind", "rank", "retrieved", "visible", "cited", "figures", "status", "assessment", "critic", "tokens"], lines))

    out.append("\n3a. Rank in retrieval snapshot %s against retrievedExpected of the full pass (lean search-top-k %s)\n" % (
        t["inputs"]["retrievalReference"], full["properties"]["searchTopK"]))
    lines = [[g, v["questions"], v["retrievedExpected"], v["notRetrieved"], v["notMeasured"], v["referenceMatchedChunkRetrievedInRun"], v["citedExpected"],
              ", ".join(v["notRetrievedIds"]) or "-"] for g, v in t["full"]["byReferenceRank"].items()]
    out.append(table(["reference rank", "questions", "retrieved", "not retrieved", "not measured", "reference chunk retrieved", "cited expected", "not retrieved ids"], lines))
    lines = [[g, v["questions"], v["retrievedExpected"], v["visibleToModel"], v["citedExpected"], show(v["shareCitedExpected"]), ", ".join(v["notCitedExpectedIds"]) or "-"]
             for g, v in t["full"]["byReferenceHitAt5"].items()]
    out.append("\n" + table(["reference hit@5 group", "questions", "retrieved", "visible", "cited expected", "share cited", "not cited ids"], lines))
    lines = [[g, v["citedExpected"], v["citedAndVisible"], v["citedNotVisible"], json.dumps(v["citedExpectedStatusCounts"]), ", ".join(v["citedNotVisibleIds"]) or "-"]
             for g, v in t["full"]["byReferenceHitAt5"].items()]
    out.append("\nThe cited-expected runs of each group, split (visible: the phrase inside the text shown to a model of the run)\n")
    out.append(table(["reference hit@5 group", "cited expected", "cited and visible", "cited, not visible", "status of the cited runs", "cited, not visible ids"], lines))

    a = t["full"]["amongRetrieved"]
    out.append("\n3b. Among the %d retrieved: visible %d, cut out of view %d (cut at %d characters); offsets agree with the snapshot: %s\n" % (
        a["questions"], a["visible"], a["cutOutOfView"], a["modelPassageChars"], show(a["offsetsAgreeWithSnapshot"])))
    lines = [[c["question"], c["chunk"], c["contentChars"], c["phraseStart"], c["phraseEnd"], c["occurrences"], show(c["cited"])] for c in a["cutOutPhrases"]]
    out.append(table(["cut-out question", "chunk", "chunk chars", "phrase start", "phrase end", "occurrences", "chunk cited"], lines))
    rc = t["full"]["referenceMatchedChunkUnderCut"]
    out.append("\nReference matched chunks under the same cut (whether or not a run retrieved them): inside %d, beyond %d, no matched chunk %d\n" % (
        rc["insideCut"], rc["beyondCut"], rc["noMatchedChunk"]))
    lines = [[c["question"], c["chunk"], c["contentChars"], c["phraseStart"], c["phraseEnd"]] for c in rc["beyondCutPhrases"]]
    out.append(table(["question", "reference chunk", "chunk chars", "phrase start", "phrase end"], lines))

    v = t["full"]["amongVisible"]
    out.append("\n3c. Among the %d visible: cited %d, not cited %d\n" % (v["questions"], v["cited"], v["notCited"]))
    out.append(table(["visible, not cited", "status"], [[q, s] for q, s in v["notCitedStatuses"].items()]))

    out.append("\n3d. INSUFFICIENT_EVIDENCE runs (%d) and runs without an answer (%d)\n" % (t["full"]["listed"]["insufficientEvidence"], t["full"]["listed"]["noAnswer"]))
    chosen = [r for r in t["rows"] if r["status"] == "INSUFFICIENT_EVIDENCE" or not r["answered"]]
    lines = [[r["question"], show(r["status"]), show(r["answered"]), show(r["referenceRank"]), show(r["retrievedExpected"]), show(r["visibleToModel"]),
              show(r["citedExpected"]), show(r["figuresInReasoning"]), show(r["criticVerdict"]), show(r["observedTokens"])] for r in chosen]
    out.append(table(["question", "status", "answered", "rank", "retrieved", "visible", "cited", "figures", "critic", "tokens"], lines))

    out.append("\n3e. The pilot's questions against the same questions of the full pass (two runs of one question; id and code lists compared as sets; compared fields: %s)\n" % ", ".join(MEASURES))
    lines = []
    for c in t["pilotAgainstFull"]["perQuestion"]:
        if not c["inFullPass"]:
            lines.append([c["question"], "not in the full pass", "", "", ""])
            continue
        detail = "; ".join("%s %s -> %s" % (m, json.dumps(c["pilot"][m]), json.dumps(c["full"][m])) for m in c["differingFields"])
        lines.append([c["question"], "equal" if not c["differingFields"] else "differ", show(c["pilotTokens"]), show(c["fullTokens"]), detail or "-"])
    out.append(table(["question", "compared fields", "pilot tokens", "full tokens", "pilot -> full"], lines))

    out.append("\n3f. Called out: %s (full pass)\n" % ", ".join(CALLED_OUT))
    lines = [[r["question"], r["kind"], show(r["referenceRank"]), show(r["referenceMatchedChunk"]), show(r["retrievedExpected"]), show(r["visibleToModel"]),
              show(r["citedExpected"]), show(r["figuresInReasoning"]), show(r["status"]), show(r["criticVerdict"]), json.dumps(r["expectedChunkIds"]), json.dumps(r["citedChunkIds"])]
             for r in t["rows"] if r["question"] in CALLED_OUT]
    out.append(table(["question", "kind", "rank", "reference chunk", "retrieved", "visible", "cited", "figures", "status", "critic", "expected chunks", "cited chunks"], lines))

    fg, cr = t["full"]["figures"], t["full"]["critic"]
    out.append("\n4. Kinds in the set: %s; questions with a figure check (an accepted phrase has a figure token): %d; figures in the reasoning: %d; not: %s\n" % (
        json.dumps(fg["kindCounts"]), fg["figureQuestions"], fg["figuresInReasoning"], ", ".join(fg["figuresNotInReasoningIds"]) or "-"))
    out.append("   Kind FIGURE without a figure check (figuresInReasoning null): %s\n" % (", ".join(fg["figureKindWithoutFigureCheckIds"]) or "-"))
    out.append("   FIGURE questions citing an expected chunk: %d; of them with the figures in the reasoning: %d\n" % (fg["figureQuestionsCitedExpected"], fg["figuresInReasoningAmongCitedExpected"]))
    out.append("   Critic verdicts: %s; runs with unsupported numerals: %s\n" % (json.dumps(cr["verdictCounts"]), ", ".join(cr["unsupportedNumeralIds"]) or "-"))
    return "".join(out)


if __name__ == "__main__":
    main(sys.argv[1:])

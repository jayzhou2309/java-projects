#!/usr/bin/env python3
"""Size-choice table and claims for the chunk-size diagnostic (plan plans/2026-09-17-chunk-size.md, Milestone 1, contract D1 to D3).

Reads only answer-visibility.log in this directory (the ANSWER_VISIBILITY lines of one run of
CrossEncoderAnswerVisibilityLiveTests over every question of set v2 with the visibility, truncation, rank, and
chunkSize experiments) and writes size-choice.txt and claims.json beside it. A re-run reproduces both byte for byte.

What it counts, from the lines and not from any lead-in or expectation:
  D1  the number of lines per experiment that name a question (the setup line and the visibility TOTALS line do not),
      per size for chunkSize, and the setup line's acceptedPhrasesLocated;
  D2  per size: the phrases (one chunkSize line each) whose holding piece ranks first in its own section pool
      (bestRank=1), the phrases split by a piece boundary (noPieceHoldsPhrase=true or piecesHoldingPhrase=0), and the
      phrases whose holding pieces are seen by no scored row (no holder prints phraseSeen=true); then the frozen rule:
      a size with a split phrase is excluded; among the remaining candidate sizes the one with the most first-ranked
      phrases, the larger on a tie; "none" when no remaining size has more first-ranked phrases than the stored 4,000;
  D3  the lines the claims cite are named by their 1-based line number in answer-visibility.log.

The claims it writes are checked by verify (DocumentationClaimsTests; check types diagnosticLine, diagnosticCount,
sizeTable, sizeChoice in RAG.md, Claims), which recomputes the same counts and the rule from the same file. Nothing
here states why a rank or score differs between sizes.
"""

import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = "answer-visibility.log"
STORED = 4000
CANDIDATES = [2000, 1000, 500]
SIZES = [STORED] + CANDIDATES
PREFIX = "ANSWER_VISIBILITY "


def tokens(text):
    """Split on spaces outside brackets, braces, parentheses, and double quotes (as DiagnosticOutput.tokens)."""
    out, current, depth, quoted = [], [], 0, False
    for c in text:
        if c == '"':
            quoted = not quoted
        elif not quoted and c in "[{(":
            depth += 1
        elif not quoted and c in "]})":
            depth -= 1
        if c == " " and depth <= 0 and not quoted:
            if current:
                out.append("".join(current))
            current = []
        else:
            current.append(c)
    if current:
        out.append("".join(current))
    return out


def fields(toks):
    """key=value tokens as a dict (key ends at the first = outside parentheses); a bare token maps to 'true'."""
    result = {}
    for token in toks:
        depth, split = 0, -1
        for i, c in enumerate(token):
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            elif c == "=" and depth == 0:
                split = i
                break
        if split < 0:
            result.setdefault(token, "true")
        else:
            result.setdefault(token[:split], token[split + 1:])
    return result


def holders(value):
    """The holder groups of holders=[piece70{...}, piece71{...}] as dicts with the piece name under 'piece'."""
    groups = []
    for match in re.finditer(r"(piece\d+)\{([^}]*)\}", value):
        holder = {"piece": match.group(1)}
        holder.update(fields(tokens(match.group(2))))
        groups.append(holder)
    return groups


def read_lines(path):
    lines = []
    with open(path, encoding="utf-8") as handle:
        for number, raw in enumerate(handle, start=1):
            text = raw.rstrip("\n")
            if not text.startswith(PREFIX):
                continue
            toks = tokens(text[len(PREFIX):])
            f = fields(toks[1:])
            lines.append({"number": number, "experiment": toks[0], "fields": f, "holders": holders(f["holders"]) if "holders" in f else []})
    return lines


def measurement(line):
    return "question" in line["fields"]


def size_counts(lines, size):
    rows = [l for l in lines if l["experiment"] == "chunkSize" and measurement(l) and l["fields"].get("sizeChars") == str(size)]
    first, split, unseen, not_first, overlap = [], [], [], [], None
    for l in rows:
        f = l["fields"]
        name = "%s (stored chunk %s, line %d)" % (f["question"], f["storedChunk"], l["number"])
        if f.get("noPieceHoldsPhrase") == "true" or f.get("piecesHoldingPhrase") == "0":
            split.append(name)
            continue
        overlap = int(f["overlapChars"])
        rank = int(f["bestRank"])
        if rank == 1:
            first.append(name)
        else:
            not_first.append("%s bestRank %d" % (name, rank))
        if not any(h.get("phraseSeen") == "true" for h in l["holders"]):
            unseen.append(name)
    return {"size": size, "overlap": overlap, "phrases": len(rows), "first": first, "split": split, "unseen": unseen, "not_first": not_first}


def choose(counts):
    """The frozen rule over the candidate sizes; returns (chosen or None, reason)."""
    stored = counts[STORED]
    remaining = [counts[s] for s in CANDIDATES if not counts[s]["split"]]
    if not remaining:
        return None, "no candidate size remains after excluding sizes with a split phrase"
    best = max(remaining, key=lambda c: (len(c["first"]), c["size"]))
    if len(best["first"]) > len(stored["first"]):
        return best["size"], "%d characters has %d first-ranked phrases against %d at the stored %d" % (best["size"], len(best["first"]), len(stored["first"]), STORED)
    return None, "no remaining size has more first-ranked phrases than the stored %d (%d); the most among the remaining sizes is %d at %d characters" % (
        STORED, len(stored["first"]), len(best["first"]), best["size"])


def one(lines, experiment, question=None, chunk=None, size=None, number=None):
    found = [l for l in lines if l["experiment"] == experiment
             and (question is None or l["fields"].get("question") == question)
             and (chunk is None or l["fields"].get("chunk") == str(chunk) or l["fields"].get("storedChunk") == str(chunk)
                  or str(chunk) in re.findall(r"\d+", l["fields"].get("chunksHoldingPhrase", "")))
             and (size is None or l["fields"].get("sizeChars") == str(size))
             and (number is None or l["number"] == number)]
    if len(found) != 1:
        raise SystemExit("expected one %s line for %s, found %d" % (experiment, (question, chunk, size, number), len(found)))
    return found[0]


def main():
    lines = read_lines(os.path.join(HERE, OUTPUT))
    setup = one(lines, "setup")
    totals = [l for l in lines if l["experiment"] == "visibility" and "TOTALS" in l["fields"]]
    if len(totals) != 1:
        raise SystemExit("expected one visibility TOTALS line, found %d" % len(totals))
    totals = totals[0]
    counts = {size: size_counts(lines, size) for size in SIZES}
    chosen, reason = choose(counts)
    per_experiment = {e: sum(1 for l in lines if l["experiment"] == e and measurement(l)) for e in ["visibility", "truncation", "rank", "overlap", "chunkSize"]}
    per_size = {size: sum(1 for l in lines if l["experiment"] == "chunkSize" and measurement(l) and l["fields"].get("sizeChars") == str(size)) for size in SIZES}
    located = int(setup["fields"]["acceptedPhrasesLocated"])
    questions = int(setup["fields"]["questions"])
    scoring = setup["fields"]["configuredScoring"]

    out = []
    out.append("Chunk size diagnostic: size-choice table (plan plans/2026-09-17-chunk-size.md, Milestone 1), computed by size_choice.py from %s" % OUTPUT)
    out.append("Line numbers are 1-based lines of %s. Labels: observed = a value as printed on the named line; derived = a count or rule outcome computed from printed values." % OUTPUT)
    out.append("")
    out.append("Setup (observed, line %d): set=%s questions=%d questionsMeasured=%s acceptedPhrasesLocated=%d acceptedPhrasesNotInAnyStoredChunk=%s model=%s maxLength=%s batchSize=%s configuredScoring=%s" % (
        setup["number"], setup["fields"]["set"], questions, setup["fields"]["questionsMeasured"], located, setup["fields"]["acceptedPhrasesNotInAnyStoredChunk"],
        setup["fields"]["model"], setup["fields"]["maxLength"], setup["fields"]["batchSize"], scoring))
    out.append("")
    out.append("D1. Lines naming a question, per experiment (derived: counted over the file; the setup line and the visibility TOTALS line are not counted):")
    for e, n in per_experiment.items():
        out.append("  %-11s %3d" % (e, n))
    for size in SIZES:
        out.append("  chunkSize at %4d characters: %d" % (size, per_size[size]))
    out.append("  acceptedPhrasesLocated on the setup line: %d; questions in the set: %d (observed). The rank experiment prints one line per located phrase, not one per question." % (located, questions))
    out.append("  visibility TOTALS (observed, line %d): phrases=%s headWholly=%s headPartly=%s headNot=%s someRowHoldsPhrase=%s noRowHoldsPhrase=%s" % (
        totals["number"], totals["fields"]["phrases"], totals["fields"]["headWholly"], totals["fields"]["headPartly"], totals["fields"]["headNot"],
        totals["fields"]["someRowHoldsPhrase"], totals["fields"]["noRowHoldsPhrase"]))
    out.append("")
    out.append("D2. Size-choice table (derived from the chunkSize lines; scoring %s):" % scoring)
    out.append("  size  overlap  phrases  first-ranked (bestRank=1)  split (no piece holds the phrase)  unseen (no holder with phraseSeen=true)")
    for size in SIZES:
        c = counts[size]
        out.append("  %4d  %7s  %7d  %25d  %33d  %39d" % (size, c["overlap"] if c["overlap"] is not None else "-", c["phrases"], len(c["first"]), len(c["split"]), len(c["unseen"])))
    out.append("")
    out.append("Frozen rule (plan, Frozen rules, Size choice): a size with a split phrase is excluded; among the remaining candidate sizes %s the one with the most first-ranked phrases, the larger on a tie; none when no remaining size has more first-ranked phrases than the stored %d." % (CANDIDATES, STORED))
    excluded = [str(s) for s in CANDIDATES if counts[s]["split"]]
    out.append("  Excluded sizes: %s" % (", ".join(excluded) if excluded else "none"))
    out.append("  Chosen size (derived): %s (%s)" % (chosen if chosen is not None else "none", reason))
    out.append("")
    out.append("Per size, the phrases counted as split, unseen, or not first-ranked (observed values on the named lines):")
    for size in SIZES:
        c = counts[size]
        out.append("  %d characters:" % size)
        out.append("    split: %s" % (", ".join(c["split"]) if c["split"] else "none"))
        out.append("    unseen: %s" % (", ".join(c["unseen"]) if c["unseen"] else "none"))
        out.append("    not first-ranked (%d): %s" % (len(c["not_first"]), ", ".join(c["not_first"]) if c["not_first"] else "none"))
    out.append("")
    out.append("D3. Lines cited by the claims for nvda-04 (stored chunk 754) and nvda-02 (stored chunk 802), values as printed:")
    for q, chunk in [("nvda-04", 754), ("nvda-02", 802)]:
        for size in SIZES:
            l = one(lines, "chunkSize", q, chunk, size)
            out.append("  line %d: chunkSize %s at %d: piecesHoldingPhrase=%s bestRank=%s holders=%s" % (
                l["number"], q, size, l["fields"]["piecesHoldingPhrase"], l["fields"]["bestRank"],
                "; ".join("%s score=%s rank=%s phraseSeen=%s" % (h["piece"], h["score"], h["rank"], h["phraseSeen"]) for h in l["holders"])))
        r = one(lines, "rank", q, chunk)
        out.append("  line %d: rank %s: poolChunks=%s bestHeadRank=%s bestWindowedRank(%s)=%s" % (r["number"], q, r["fields"]["poolChunks"], r["fields"]["bestHeadRank"], scoring, r["fields"]["bestWindowedRank(%s)" % scoring]))
        t = one(lines, "truncation", q, chunk)
        out.append("  line %d: truncation %s: headScore=%s windowedScore(%s)=%s recutScore(from 200 chars before the phrase)=%s phraseAloneScore=%s" % (
            t["number"], q, t["fields"]["headScore"], scoring, t["fields"]["windowedScore(%s)" % scoring], t["fields"]["recutScore(from 200 chars before the phrase)"], t["fields"]["phraseAloneScore"]))
    with open(os.path.join(HERE, "size-choice.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")

    claims = []
    ident = [1000]

    def claim(basis, block, check=None, text=None, from_ids=None):
        ident[0] += 1
        c = {"id": "C-%d" % ident[0], "basis": basis, "block": block}
        if text is not None:
            c["text"] = text
        if from_ids is not None:
            c["from"] = from_ids
        if check is not None:
            c["check"] = check
        claims.append(c)
        return c["id"]

    def line_check(experiment, field, expected, question=None, chunk=None, size=None, number=None):
        check = {"type": "diagnosticLine", "output": OUTPUT, "experiment": experiment}
        if number is not None:
            check["line"] = number
        if question is not None:
            check["question"] = question
        if chunk is not None:
            check["chunk"] = chunk
        if size is not None:
            check["sizeChars"] = size
        check["field"] = field
        check["expected"] = expected
        return check

    def printed(field, line):
        value = line["fields"][field]
        try:
            return int(value)
        except ValueError:
            try:
                return float(value)
            except ValueError:
                return value

    # Block run: the setup line.
    for field in ["questions", "acceptedPhrasesLocated", "acceptedPhrasesNotInAnyStoredChunk", "model", "maxLength", "configuredScoring"]:
        claim("observed", "run", line_check("setup", field, printed(field, setup)))
    # Block counts (D1).
    count_ids = []
    for e in ["visibility", "truncation", "rank", "overlap"]:
        count_ids.append(claim("derived", "counts", {"type": "diagnosticCount", "output": OUTPUT, "experiment": e, "expected": per_experiment[e]}))
    for size in SIZES:
        count_ids.append(claim("derived", "counts", {"type": "diagnosticCount", "output": OUTPUT, "experiment": "chunkSize", "sizeChars": size, "expected": per_size[size]}))
    for field in ["phrases", "headWholly", "headPartly", "headNot", "someRowHoldsPhrase", "noRowHoldsPhrase"]:
        count_ids.append(claim("observed", "counts", line_check("visibility", field, printed(field, totals), number=totals["number"])))
    claim("inferred", "counts", text="The output holds, per located phrase, one visibility line, one truncation line, one rank line, and one chunkSize line per size (the rank experiment prints one line per located phrase, not one per question), and the overlap grid did not run", from_ids=["C-1002"] + count_ids)
    # Block table (D2).
    table_ids = []
    for size in SIZES:
        c = counts[size]
        table_ids.append(claim("derived", "table", {"type": "sizeTable", "output": OUTPUT, "sizeChars": size,
                                                    "expected": {"phrases": c["phrases"], "firstRanked": len(c["first"]), "split": len(c["split"]), "unseen": len(c["unseen"])}}))
    for name in counts[500]["split"]:
        q, chunk = re.match(r"(\S+) \(stored chunk (\d+),", name).groups()
        table_ids.append(claim("observed", "table", line_check("chunkSize", "noPieceHoldsPhrase", "true", question=q, chunk=int(chunk), size=500)))
    choice_id = claim("derived", "table", {"type": "sizeChoice", "output": OUTPUT, "stored": STORED, "candidates": CANDIDATES, "expected": chosen if chosen is not None else "none"})
    if chosen is None:
        claim("inferred", "table", text="Under the plan's frozen rule the size choice is empty, and the plan stops after this milestone without a rebuild; the counts do not describe the vector or keyword legs, which a rebuilt store alone would show", from_ids=[choice_id] + table_ids)
    else:
        claim("inferred", "table", text="Under the plan's frozen rule the chosen size for the one rebuild of Milestone 3 is %d characters; the counts do not describe the vector or keyword legs, which a rebuilt store alone would show" % chosen, from_ids=[choice_id] + table_ids)
    # Block nvda (D3): the lines the plan's Why rested on, cited by line.
    for q, chunk in [("nvda-04", 754), ("nvda-02", 802)]:
        for size in SIZES:
            l = one(lines, "chunkSize", q, chunk, size)
            claim("observed", "nvda", line_check("chunkSize", "bestRank", printed("bestRank", l), question=q, chunk=chunk, size=size))
        r = one(lines, "rank", q, chunk)
        claim("observed", "nvda", line_check("rank", "bestWindowedRank(%s)" % scoring, printed("bestWindowedRank(%s)" % scoring, r), question=q, chunk=chunk))
        t = one(lines, "truncation", q, chunk)
        for field in ["headScore", "windowedScore(%s)" % scoring, "recutScore(from 200 chars before the phrase)"]:
            claim("observed", "nvda", line_check("truncation", field, t["fields"][field], question=q, chunk=chunk))

    document = {
        "description": "Chunk size diagnostic (plan plans/2026-09-17-chunk-size.md, Milestone 1), written by size_choice.py from answer-visibility.log, the ANSWER_VISIBILITY lines of one run of CrossEncoderAnswerVisibilityLiveTests over every question of set v2 with the visibility, truncation, rank, and chunkSize experiments (the overlap grid did not run): the setup line, the line counts per experiment and size (D1), the size-choice table and the frozen rule's outcome (D2), and the lines for nvda-04 and nvda-02 cited by line number (D3). Scores are printed as the run printed them, and the claims state no cause for any score or rank.",
        "labels": {OUTPUT: "the diagnostic run"},
        "claims": claims,
    }
    with open(os.path.join(HERE, "claims.json"), "w", encoding="utf-8") as handle:
        handle.write(json.dumps(document, indent=1, ensure_ascii=False) + "\n")
    print("size-choice.txt and claims.json written: %d claims; chosen size %s" % (len(claims), chosen if chosen is not None else "none"))


if __name__ == "__main__":
    main()

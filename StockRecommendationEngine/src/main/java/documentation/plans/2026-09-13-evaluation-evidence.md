# Plan: recorded retrieval evidence and checked claims (Follow_Ups RAG-14)

Orchestrated build per CLAUDE.md. Four serial milestones with contracts written before code. Branch:
`evaluation-evidence`, stacked on `reranker` (c43a4dd) because PR #17 is not merged yet; rebase onto `main` once it
is. Build: `set -a; source .env; set +a; ./mvnw -q -o verify`; never export the application enable flags or `RAG_*`
overrides in a shell that runs tests. The uncommitted edits to `src/main/java/PRD_Stock_Recommendation_Engine_v3.md`,
`src/main/java/documentation/Follow_Ups.md`, and the untracked `src/main/java/documentation/Target_State_Trading_Desk.md`
are not part of this work: no role edits, stages, or reverts them.

## Why

The windowed reranker write-up (plan `2026-09-13-reranker-windows.md`, Status at merge) failed validation five times,
never on the numbers and always on claims the stored evidence could not support. Snapshots keep only each question's
rank and matched chunk, so whether a chunk was a reranker candidate, which chunks outranked an answer, and a chunk's
rank when another chunk matched are unrecorded. Prose written by hand then filled those gaps with assumptions, and one
wrong assumption spread into four documents. Jay's rule (2026-09-13): conclusions may not outrun recorded measurements;
every claim is observed, derived, inferred, or unknown; a cause needs an isolating experiment; a fact is stated once and
referenced.

## Design constraints

- Recording is opt-in per evaluation run: `POST /api/rag/evaluate?trace=true`, recorded as `properties.trace`. Without
  it a snapshot is byte-for-byte the shape it is today. No trace ever reaches `/api/rag/retrieve` responses, the
  recommendation tools, or a prompt.
- Tracing must not change what retrieval returns: the traced path produces the same ordered results and strategy as the
  untraced path for the same inputs, and the reranker is called once per question (the recorded scores are the scores
  that produced the order, never a second scoring call). Timeout, fallback, and `validateRerankedEvidence` apply unchanged.
- Existing snapshots (ids up to 299) read back unchanged; no migration (the `results` column is already JSON).
- The evidence report and the claims check are deterministic and call no chat model. Token positions use the
  cross-encoder's own tokenizer and window arithmetic (`CrossEncoderPairAssembler`); without the cross-encoder bean,
  token fields are reported as unknown with a reason, never guessed.
- Every value in the report carries its basis: `observed` (read from the trace or the database), `derived` (computed
  from observed values by a named rule), or `unknown` (with the reason it is not recorded). `inferred` exists only in
  claims, never in the report.
- Defaults unchanged: reranking and the cross-encoder stay off.

## Milestone 1: record per-question retrieval traces in snapshots

Scope: a traced retrieval path in `FilingRetrievalService` used only by `RetrievalEvaluationService`; a scored
reranking method on `FilingReranker` (default: not supported) implemented by `CrossEncoderReranker`, with per-window
scores from `PairScorer`; `?trace=` on evaluate; trace storage and read-back in `RetrievalEvaluationRepository`;
unit tests; RAG.md Retrieval Evaluation and Cross-encoder sections updated with a dated change-log bullet.

Per question the trace records: the fused, diversified candidate list in order (chunk id, fused position, and the
chunk's 1-based rank in each leg that returned it: vector, keyword, figure, null when absent); the rerank outcome
(`OFF`, `RERANKED`, or `FALLBACK` with the reason class the service already logs); when reranking ran, every rerank
input chunk with its reranked position within the input, its score, its window count, and its per-window scores; and
the returned chunk ids in order.

Correctness contract:
- C1. With a scripted reranker and a scripted repository, the traced and untraced paths return identical results and
  strategy for rerank off, on, timeout, reranker exception, and invalid evidence; the reranker is invoked exactly once
  per traced retrieval; on fallback the trace records the reason and no scores.
- C2. The trace's rerank order restricted to its first topK entries equals the returned results; every rerank input
  chunk appears once; fused positions are 1..n without gaps and match the order the reranker received; leg ranks match
  the scripted leg lists.
- C3. The cross-encoder's scored method returns, for each passage, per-window scores whose maximum is the passage score
  under `max-window` and whose single entry is the score under `head`, and orders exactly as `rerank` does (unit-tested
  with a scripted `PairScorer`; opt-in live test on the real model checks equality with `rerank` on 20 chunks).
- C4. `trace=true` stores traces and `properties.trace` true; absent or false stores none and `properties.trace` false;
  snapshots written before this milestone read back with traces null (`RetrievalEvaluationRepositoryTests`).
- C5. Opt-in live check (`-Drag.evaluation.live=true` with the cross-encoder enabled in that test only): a traced run at
  the settings of snapshot 297 reproduces 297's per-question ranks and matched chunks exactly; any difference fails the
  test naming every differing question.
- Commands: `./mvnw -q -o verify` exit 0; the new opt-in live tests exit 0.
- Out of scope: the evidence report, claims, documentation regeneration, any default change.
- User-facing flow (UT): app with `RAG_CROSS_ENCODER_ENABLED=true`; `POST /api/rag/evaluate?rerank=true&trace=true`
  returns a snapshot whose per-question traces exist for all 42 questions with 0 fallbacks; `GET /api/rag/evaluate/{id}`
  returns the same traces; a run without `trace` has none; the retrieve endpoint's response shape is unchanged.

## Milestone 2: per-question evidence report

Scope: `GET /api/rag/evaluate/{id}/evidence` (token-gated like the rest of the controller) returning, for every question
of a stored snapshot, the report below as JSON, and `?format=markdown` rendering the same content as tables; the
computation in a service with unit tests over scripted data.

Per question: rank and matched chunk (observed); for every accepted phrase, every stored chunk that holds it (same
accession and section, whitespace-normalised containment, the rule `RetrievalEvaluationService.matches` uses), not only
chunks retrieval returned; for each such chunk its WordPiece token length, the phrase's token span, the window length W
beside this question, whether the span lies wholly, partly, or not inside the head window, and the index of the window
that holds it wholly under the snapshot's recorded `rerankerScoring` (derived); its fused position or "not in the fused
list" and its rerank input membership, reranked position, score, and window scores (observed from the trace; unknown with
reason "no trace" on snapshots without one); and the chunks ranked above the best accepted chunk with their scores
(observed).

Correctness contract:
- D1. On scripted chunks and a scripted tokenizer, token spans, head membership, and window indices equal hand-computed
  values, including a phrase straddling a window boundary, a phrase split by the head cut, overlap 0 and 224, a chunk
  shorter than W, and an empty chunk.
- D2. Every accepted phrase of every question is reported against every chunk that holds it; a phrase held by no stored
  chunk is reported as such.
- D3. On a snapshot without traces, every candidate and score field has basis `unknown` and reason `no trace`; no field
  is ever filled from a default or a neighbouring snapshot.
- D4. Without the cross-encoder bean, token fields are `unknown` with reason `tokenizer unavailable`, and the endpoint
  still answers.
- D5. The markdown rendering is generated from the JSON only (a test renders a fixed report and compares it with a
  committed expected file).
- Commands: `./mvnw -q -o verify` exit 0.
- Out of scope: claims, documentation regeneration.
- User-facing flow (UT): for the Milestone 1 traced snapshot, the msft-05 entry lists chunks 460, 515, and 571 with their
  token spans and says whether chunk 515 was a rerank candidate; chunk 466 for msft-04 has an observed reranked position;
  for snapshot 297 (no trace) the same fields say `unknown` / `no trace`.

## Milestone 3: claims file, claims check, and generated documentation blocks

Scope: a claims format and a DB-free check that runs in `verify`; a generator that writes documentation blocks from
claims; CLAUDE.md project specifics gain the evidence rule.

- A claims file per measurement (`documentation/live-runs/<date>-<feature>/claims.json`): each claim has an id, the
  sentence, a basis (`observed`, `derived`, `inferred`, `unknown`, `experiment`), and a check from a closed set: question
  rank in a snapshot; question inside or outside the top k in each of a list of snapshots; aggregate, slice, or ticker
  metric; rule outcome per row; phrase token span and head or window membership; chunk candidate presence, fused position,
  or reranked position; field not recorded. `inferred` claims name the claim ids they follow from; `unknown` claims must
  use the not-recorded check, so a later trace that records the value fails the claim until it is rewritten; a sentence
  stating a cause requires basis `experiment` and a reference to an evidence file of an experiment that varies only that
  factor, otherwise the check rejects it.
- The check evaluates claims against committed evidence reports and snapshots (JSON files in the live-runs directory),
  never the live database, so it runs in `verify`.
- Generated blocks: documentation sections between `<!-- generated:<claims-file> start -->` and `end` markers are written
  from the claims' sentences by the generator; the check fails when a block differs from what the generator would write,
  or cites a claim id that does not exist.

Correctness contract:
- E1. Each check type passes on a true claim and fails on a false one, over fixture evidence (at least: a claim "outside
  the top 5 in every row" failing when one row ranks the question 1, the msft-04 case; a candidate-presence claim marked
  observed failing on a snapshot without a trace; an inferred claim with no premises rejected; a causal sentence without an
  experiment rejected).
- E2. A failing check's message names every failing claim with its id, the expected value, and the value found (the
  RAG-13 lesson: the failure output identifies every item that makes the guard fail).
- E3. A hand-edited generated block, or a block citing a missing claim, fails `verify`; running the generator restores it.
- E4. CLAUDE.md project specifics state the rule and point to the claims format.
- Commands: `./mvnw -q -o verify` exit 0; the check demonstrably fails (captured output) on a deliberately broken fixture
  and is rolled back.
- Out of scope: rewriting the reranker measurement documentation (Milestone 4).
- User-facing flow: none (Scrutiny only).

## Milestone 4: re-measure with traces and regenerate the windowed write-up

Scope: on set v2, window 10, cross-encoder enabled, traced runs at the settings of snapshots 295 to 299 (reference off;
windowed at 10, 20, and 40 candidates with `rerank-timeout-ms` 4,000 at 40; 20 candidates with overlap 224); evidence
reports for each; a claims file covering every statement of the RAG.md "Windowed rows" subsection and the related
change-log and Windows text; those sections replaced by generated blocks; the frozen status in
`2026-09-13-reranker-windows.md` closed with a dated note; RAG-15's recall question answered from the fused positions.

Correctness contract:
- F1. Each traced run reproduces its untraced counterpart's per-question ranks and matched chunks (295 to 299); a
  difference is reported per question and stops the milestone for a decision rather than being documented around.
- F2. Every sentence in the regenerated sections is a generated claim, and the claims check passes in `verify`.
- F3. The five open findings in the frozen status are each resolved by an observed or derived claim, or restated as an
  unknown claim with its reason: chunk 515's candidacy for msft-05; chunk 466's position at 40 candidates; the chunks
  ranked above nvda-01, nvda-11, and msft-04's chunks; "The cause was diagnosed" in the windows plan; the unmarked earlier
  correction notes (kept, marked superseded).
- F4. The selection-rule outcome and defaults are unchanged unless F1 found a difference; both floors pass.
- F5. RAG-15: nvda-02's and nvda-04's answering chunks' fused positions (or absence from the fused list) are stated as
  observed claims.
- Commands: `./mvnw -q -o verify` exit 0; floors live test exit 0; evidence under
  `documentation/live-runs/2026-09-13-evaluation-evidence/`.
- Out of scope: tuning, any default change, and Follow_Ups and PRD rows (Orchestrator with Jay afterwards).
- User-facing flow (UT): the evidence endpoint for the traced 297-equivalent run shows the values the regenerated
  RAG.md cites for msft-05, msft-04, and nvda-01.

## Gate rules

Both validators must pass before the next milestone; at most two remediation rounds, then escalate. Validators never
see the Worker's report or each other's output and run one at a time (shared `target/`). Scrutiny recomputes from stored
evidence and constructs the regression scenario for every guard. For documentation claims, validators use the evidence
report, not the prose.

## Amendment 1 (2026-09-13, after Milestone 1 Scrutiny PASS on remediation round 1)

Milestone 1 (1f57c75, remediated in 39d9394) passed Scrutiny; its first attempt failed C5 because the reproduction test
stopped at a fallback assertion before naming the differing questions. Changes to the plan:

- **Interface, as built.** `FilingReranker.rerankScored` has a default that calls `rerank` once and reports no scores, and
  `FilingRetrievalService` calls `rerankScored` on both `retrieve` and `retrieveTraced`; the untraced path discards the
  scores. This replaces Milestone 1's "default: not supported" and "a traced path used only by evaluation" wording: the
  trace itself is still built and stored only for evaluation runs with `trace=true`, and no trace reaches
  `/api/rag/retrieve`, the recommendation tools, or a prompt. Scrutiny verified identical responses and logs against the
  pre-milestone service over 500 random scenarios and every constructed reranker, one reranker call per retrieval, and
  about 3 KB of extra allocation per 40-candidate, 4-window call.
- **Folded into Milestone 2** (non-blocking Scrutiny findings): `TraceReproductionCheck` must not throw when a question
  that fell back without an error has a null trace (filter by id, then map; add a unit case); RAG.md's Traces `rerank`
  bullet about `noCandidates` needs its in-place dated correction marker.
- **Timeouts in Milestone 4.** A traced run at snapshot 297's settings fell back on two questions once at the default
  2,000 ms (calls of 2,039 and 2,448 ms) and passed on rerun. Milestone 4 applies the earlier plan's rule: only
  fallback-free runs count, a run with fallbacks is rerun once, and a run that still falls back is reported and excluded,
  never compared as if it reproduced its counterpart.

## Amendment 2 (2026-09-13, after Milestone 2 Scrutiny PASS)

Milestone 2 (79e8480) passed Scrutiny on the first round with no correctness finding. Folded into Milestone 3:

- **verify must not need ingested filings.** `RetrievalEvidenceDatabaseTests` asserts every set-v2 phrase is held by a
  stored chunk, so `verify` now fails on a freshly migrated database. Make that assertion opt-in (live) or assert only
  against rows the test inserts itself.
- **A reason must not state a cause it did not read.** The evidence service reports "no trace for this question (its
  retrieval failed)" for any null trace without reading the question's error; say "retrieval failed" only when the
  result records an error, otherwise "no trace recorded for this question".
- **Window fields when nothing was scored.** For reranking off, fallback, untraced questions, or chunks outside the
  rerank input, window starts and holding windows are the rows the recorded scoring would score, not rows that were
  scored; the rule text, the markdown legend, and RAG.md must say so, so a write-up cannot cite them as scored.
- **Legend and rule text.** A non-input chunk's `rerankInput` source must match its `false` value; W's rule must say
  max-length comes from current configuration.
- **Evidence size.** Committed evidence reports are stored as compact JSON only (one line per file); markdown is not
  committed, since the endpoint regenerates it from the JSON. Milestone 3 converts the two Milestone 2 reports
  (evidence-459 and evidence-297) accordingly and notes the change in run.log; the claims check reads the JSON.

## Amendment 3 (2026-09-13, after Milestone 2 UT PASS)

Milestone 2 passed UT on the first round. One more wording note folded into Milestone 3 with Amendment 2's: with the
cross-encoder off, `settings.loadedModelVersion` carries reason `tokenizer unavailable`; it should say the cross-encoder
is not loaded. UT also recorded, from snapshot 459's report, facts Milestone 4's claims will cite as observed: chunk 515
(msft-05's ITEM_7 phrase) was a rerank input at fused position 14, reranked 12, score 0.81250834, window scores
[-4.609346, 0.81250834]; chunk 466 (msft-04) reranked 11 with ten chunks above; chunk 467 fused 21, not a rerank input;
nvda-02's and nvda-04's accepted chunks at fused positions 41 and 54, outside the 20 inputs. These are properties of
snapshot 459 only; Milestone 4 cites them from its own traced runs.

## Amendment 4 (2026-09-13, after Milestone 3 Scrutiny FAIL)

Milestone 3 (811ca14) failed Scrutiny. Every check type passed true claims and failed false ones, but the checker never
compares a claim's `text` with its `check`, while RAG.md said a sentence could not drift from its check: "Snapshot 297
ranks msft-05 first" passed with a check expecting rank 10, and "every row" passed with a check listing two rows. A
metric with more than six decimals crashed the whole check. Remediation round 1 changes the design, in line with Jay's
rule that a sentence is generated from computed values rather than written beside them:

- **Sentences for observed and derived claims are rendered from the check.** Each check type has a fixed sentence
  template filled from its parameters and the values found in the evidence (for example rank: "Snapshot {label} ranks
  {question} {rank or 'outside the window'}."; topK: one clause per listed row, so "every" appears only when the list
  equals a named, checked set of rows). The claims file no longer carries free `text` for these claims; an optional
  `label` names a snapshot or report file for readability and may not contain digits. The generator prints the rendered
  sentence; the check renders it again and compares.
- **Free text only where no check can render it:** `inferred`, `unknown`, and `experiment` claims keep a `text`. It is
  printed with its premises or reason ("inferred from C-004, C-005"), it is screened by the causal and absolute-wording
  rules below, and RAG.md states plainly that such sentences are reviewed by people and validators, not proven by the
  checker.
- **Causal and absolute wording.** The screen matches word stems and inflections (for example caus-, result-, lead-/led,
  driv-/drove, lift-, push-, boost-, improv-, trigger-, contribut-, stem- from, account- for, responsible for, reason,
  why, thus, therefore, consequently, because, due to, thanks to, owing to, made ... to) and absolute or predictive
  wording (always, never, will, every, all, none, guarantee-). A causal match requires basis `experiment`; an absolute
  or predictive match in free text is rejected. The list and its limits are documented.
- **Experiments are checked against recorded runs.** An experiment file names the factor, the two settings, and the two
  committed snapshot files; the check reads both snapshots' recorded properties and fails unless they differ in exactly
  the named factor (every other recorded property equal, `trace` and identifiers excepted), and the claim's check must
  reference one of those snapshots or reports built from them.
- **Robustness and scope:** paths must resolve inside `src/main/java/documentation/live-runs/`; unknown keys are
  rejected at every nesting level, including topK rows; a ruleRow criterion whose data is absent fails rather than
  passing; any exception inside one claim becomes a problem naming it, so one bad claim never stops the list; the
  generator reports an unbalanced document and continues with the rest; the RAG.md wording about differing lines and
  unbalanced files matches the code. Whether referenced files are committed stays a review item, documented.
- The worked example `claims.json` (13 claims) and its RAG.md block are regenerated under the new format.

## Amendment 5 (2026-09-13, Jay: run what can run in parallel)

Milestone 4 splits so its measurement runs while Milestone 3's remediation is still in progress:

- **Milestone 4a, measure (branch `evaluation-evidence-m4a`, worktree `java-projects-m4a`, app port 8082).** Traced
  runs at the settings of snapshots 295 to 299 (reference off; windowed at 10, 20, and 40 candidates with
  `rerank-timeout-ms` 4,000 at 40; 20 candidates with overlap 224), each snapshot exported as row_to_json, each
  evidence report as compact JSON, and a per-run reproduction result against its untraced counterpart using
  `TraceReproductionCheck`. Contract: F1 (reproduction per question; a difference is reported and stops for a decision)
  and F5 (nvda-02's and nvda-04's accepted chunks' fused positions in every run, as recorded facts in run.log), plus the
  Amendment 1 timeout rule. No RAG.md, claims, or plan-status changes. Gate: Scrutiny only (recomputation from the
  committed files).
- **Milestone 4b, claims and regeneration**, after Milestone 3 passes and 4a is merged into `evaluation-evidence`:
  F2, F3, F4 on 4a's evidence, with UT as planned for Milestone 4.
- **Parallel runs share the CPU.** Rerank timeouts are part of each run's settings, so a traced run waits until no
  other Maven test JVM or application from another worktree is running, records the load average when it starts, and
  follows the Amendment 1 rule on any fallback. Validators for different milestones may run at the same time only in
  separate worktrees on separate ports.

## Amendment 6 (2026-09-13, after Milestone 3 re-validation FAIL; final remediation round)

Milestone 4a (8835758) passed Scrutiny and is merged into `evaluation-evidence`; its evidence notes for 4b: run 627's
4,000 ms timeout is a run record (committed files show only that it exceeded 3,072 ms); run 598's startup line is quoted
only in run.log; the run log's environment grep likely printed nothing; "each question has one accepted phrase" holds
only for nvda-02 and nvda-04.

Milestone 3 remediation 1 (e44f4c1) passed every code item and every Amendment 4 bullet and failed on documentation: prose
outside generated blocks is unchecked, a sentence citing a claim outside a block borrows its authority unscreened (a false,
causal heading above a block passed `verify`), and RAG.md and CLAUDE.md did not list it among the things people must read.
Remediation round 2, the last before escalating to Jay:

- **Citations only inside generated blocks.** A `(C-nnn` citation outside a generated block is a problem, so hand-written
  prose can never appear backed by a claim.
- **The line that introduces a block is screened.** The nearest non-empty line above each start marker (a heading or
  lead-in) must pass the causal and absolute screen and may not contain digits or spelled-out numbers.
- **Screen normalisation and coverage.** Before screening, remove soft hyphens, zero-width characters, and markdown emphasis
  and code marks; extend absolute and predictive wording (each, any, whole, entire, must, consistently, going to, expect,
  and contractions of will) and causal stems (raise, put, give, is behind, source of); reject spelled-out numbers in labels.
  The remaining limits (meaning, not words; since, after, so) stay documented.
- **Review items listed completely**, in RAG.md and in CLAUDE.md's rule: prose outside generated blocks; labels; free text of
  inferred, unknown, and experiment claims, including numbers that contradict premises and inferences from unknown claims;
  whether referenced JSON files are committed, unaltered exports; that window wording rests on max-length from configuration
  at report time; settings snapshots do not record. CLAUDE.md must no longer say write-ups are generated without that limit.
- **Templates documented exactly**, every branch including failing and unknown wordings (Scrutiny finding 4); duplicate labels
  resolving to one file are a problem; the cosmetic message fixes.

## Amendment 7 (2026-09-14, Milestone 3 closed at the remediation cap; Milestone 4b scope)

Milestone 3's final Scrutiny (e11b9b6) passed every contract item and every Amendment 4 and 6 bullet except one
documentation guarantee: RAG.md said a heading or lead-in could not state a number, cause, or generalisation beside a
block, while only one physical line above the start marker is screened (a wrapped lead-in, a setext heading, and text
above a multi-line comment pass). Jay chose to close it by rewording: the Orchestrator corrected RAG.md and CLAUDE.md
(the limits and a claim-id uniqueness rule), confirmed by a documentation-only Scrutiny check before Milestone 4b.

Milestone 4b (F2, F3, F4 on Milestone 4a's merged evidence), constraints added:
- Its claims file lives in `live-runs/2026-09-13-evaluation-evidence/measurement/claims.json` and uses the id prefix
  `W-` so no id repeats the existing `C-` claims in RAG.md.
- Every lead-in above a generated block is a single short line with no causal or absolute word and no number, never a
  wrapped line or setext heading; the rest of each regenerated section is block bullets.
- Cite Milestone 4a's run records with their limits (Amendment 6): run 627's 4,000 ms timeout as a run record whose
  committed files show only that calls exceeded 3,072 ms without fallback; settings from stored properties.
- RAG-15 facts at the current settings (nvda-02 chunk 802 fused 41, nvda-04 chunk 754 fused 54, in all five runs) are
  observed claims from 4a's evidence; the candidate-count 200 experiment (snapshot 694, branch `rag15-recall-e1`) is not
  merged and is not cited in 4b.

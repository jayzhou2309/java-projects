# Recommendation Loop and Agent Harness

* Implementation Status
    * A bounded manager-and-specialist research workflow using Spring AI 2.0.1 ChatModel and ToolCallback.
    * The RAG and broker specialists run concurrently on a specialist thread pool when the manager delegates to both; budgets and the deadline are shared and thread-safe.
    * Before retrieval the RAG branch ingests a ticker with no embedded filings and refreshes one past its filing cadence from SEC EDGAR; every response reports the filing dates, bar date, and quote timestamp it used.
    * Uses the existing [filing retrieval pipeline](RAG.md), optional [direct IBKR integration](IBKR.md), and optional [quant layer](Quant.md).
    * Disabled by default.
    * Runs without an MCP server. A future MCP client can provide selected callbacks at the same tool boundary.
    * Take-profit and stop-loss levels come from the deterministic quant layer when enabled; confidence is an uncalibrated input-coverage composite.
    * A critic on a separate, tool-less conversation reviews the manager's answer against the run's own evidence; on REVISE the manager revises once on its own conversation and the revision is reviewed again. The critic discloses, it never stops a validated run.
    * A directional run's raw confidence is mapped through the newest READY calibration snapshot from stored outcomes ([Outcomes.md](Outcomes.md)); until enough directional runs are scored, responses say so and carry no calibrated figure.
    * No trade execution or position sizing is implemented.
    * Every run is written to the recommendations table with version tags, so outcomes can later be measured and attributed; see Recommendation Audit Store below.
    * This is the first research consumer of RAG, not completion of every recommendation requirement in the PRD.

* What the Harness Does
    * Supplies the model with the user request and a restricted list of tools.
    * Receives a tool request, validates it, executes Java code, and returns its result to the model.
    * Repeats until the model returns a final answer or a code-enforced limit is reached.
    * Keeps evidence and contract permissions isolated to one request.
    * Validates the final JSON and resolves citations against actual retrieved passages.
    * Records sanitized tool outcomes and timing.

* RecommendationService
    * Methods
        * recommend(RecommendationRequest request)
            * Validate and normalize the ticker and question.
            * Allocate a run ID and submit work to a bounded executor.
            * Wait at most deadline-ms, then cancel and interrupt the worker.
            * Return HTTP 429 when both worker slots are occupied; there is no pending-work queue.
            * Persist the outcome through persist(...) after the worker returns, including DEADLINE_EXCEEDED and FAILED results; a failed write adds AUDIT_NOT_PERSISTED to the response instead of failing the request.
            * Always stores the run with purpose USER. The controller and the watchlist call only this method.
        * recommend(RecommendationRequest request, RunPurpose purpose)
            * Package-private since 2026-09-19 (it was public in Milestone 1 and had no production caller once recommendForEvaluation existed): code outside the `recommendation` package cannot choose a purpose, so the only public way to store an EVALUATION run is recommendForEvaluation below. RecommendationServiceTests checks by reflection that no public method takes a RunPurpose.
            * The run is the same in every respect: same validation, tool allowlist, instruction-like passage screen, and citation checks; only the stored purpose differs. A null purpose is rejected.
            * No request body, query parameter, header, or configuration property reaches the purpose argument.
        * recommendForEvaluation(RecommendationRequest request)
            * The entry point the answer-evaluation runner calls (Answer Evaluation below; since 2026-09-19), and the only public method that stores a run with a purpose other than USER. It is the same run through the same private path as recommend(request, purpose), always stored with purpose EVALUATION, returned as EvaluationRun: the public response, plus every chunk retrieved during the run (the response's sources hold only the cited ones) each beside the text a model was shown for it.
            * The shown text comes from RecommendationTools.shown(), which applies RecommendationTools.forModel(chunk, model-passage-chars), the function every model-facing copy of a passage goes through; the cut depends only on the chunk and the configured limit, so a chunk returned by several searches is shown the same text each time.
            * The worker hands the evidence over when the run ends, whatever ended it (an answer, a limit such as INVALID_CITATION, a failure). evidenceCaptured is false when the caller's deadline fired before the worker finished, or the copy failed; retrieved is then unknown, not empty.
            * The copy is consistent: RecommendationTools.evidence is a synchronised insertion-ordered map and shown() copies it while holding its lock (evidenceCopy(), which the specialist report and the prefetch message also read), so a search cancelled by a run limit that finishes late, or a parallel search of the same run, cannot change the map mid-copy. Citation validation reads the same map as before. RecommendationToolsTests takes copies while 3,000 searches write from another thread and checks each copy is a gapless prefix of the insertion order; with the earlier plain map the same test ends in ConcurrentModificationException.
            * EvaluationRun is returned by no controller, is not stored, and nothing in it is sent to a model. No prompt text changed; PROMPT_VERSION is unchanged.
        * run(...)
            * Create fresh RecommendationTools, message history, evidence state, and trace.
            * When the manager returns several delegations in one response, submit them to the specialist pool and wait with the run deadline; a limit violation in either specialist stops the run and cancels the other.
            * Before the RAG specialist starts, ensureFilings(...) calls FilingFreshnessService.ensure: a missing ticker is ingested, a stale one refreshed against the SEC index, a fresh one left alone. The outcome appears in the trace as RAG:ensureFilings (FRESH, VERIFIED, REFRESHED, INGESTED, or a failure code); failures and FILINGS_MAY_BE_STALE become limitations. See [RAG.md](RAG.md).
            * Before the manager's first model call, lookBack(...) loads the ticker's prior stored runs with their realized outcomes through TrackRecordService (outcomes enabled, recommendation.track-record-runs > 0) and supplies them as an evidence message; traced as MANAGER:reviewTrackRecord with OK, NO_PRIOR_RUNS, or TRACK_RECORD_UNAVAILABLE (also a limitation). See [Outcomes.md](Outcomes.md).
            * Before the RAG specialist's model runs, prefetchFilings(...) searches the stored filings with the user's question through the same searchFilings callback (recommendation.prefetch-filings, default true) and hands the passages to the specialist as an evidence message; the specialist searches again only for what is missing. Evidence therefore exists whatever the model decides, and no text in the question or a passage can talk it out of retrieving. The prefetch reserves one tool call; when none is left it is skipped and disclosed as prefetch:TOOL_LIMIT.
            * Before the broker specialist starts, prefetchBroker(...) runs findInstrument, and when the contract is unambiguous, getQuote and analyzePriceHistory through the same validated callbacks; the results are handed to the specialist model as an evidence message. The model cannot forget to fetch evidence; it may still call tools for what is missing.
            * Send the request through ChatModel.call(Prompt).
            * Advertise only the callbacks allowed for this run.
            * Execute returned calls sequentially, appending matching tool-response IDs to history.
            * Reject unknown tools and duplicate or empty call IDs.
            * Enforce model-call, tool-call, context, tool-result, observed-token, and elapsed-time limits.
        * finish(...)
            * Parse a strict three-field final JSON shape.
            * Validate the assessment and nonempty explanation.
            * Reject citations that were not retrieved during this run.
            * Require at least one citation for a directional/neutral assessment.
            * review(...): critic and synthesis, bounded by recommendation.critic-rounds (below).
            * Return citation metadata and quotes from application state, never model-supplied URLs or prices.
            * Select take-profit/stop-loss from the run's QuantAnalysis by assessment direction and compute confidence; see [Quant.md](Quant.md).
            * calibrate(...): for BULLISH and BEARISH, map the raw confidence through ConfidenceCalibrationService.apply; traced as MANAGER:calibrateConfidence with APPLIED, INSUFFICIENT_SAMPLE, NO_CALIBRATION, or UNAVAILABLE; NEUTRAL is NOT_DIRECTIONAL without a lookup. See [Outcomes.md](Outcomes.md).
    * Critic and Synthesis
        * After the manager's answer passes structural and citation validation, the critic reviews it on a fresh conversation with no tools. It receives the draft, the cited passages in full, the count of retrieved but uncited passages, quotes, price analysis, track record, data freshness, limitations, and numeralsNotFoundInEvidence: the numerals in the reasoning that the application could not find in that evidence (form names and single digits ignored, commas dropped; rounding produces false positives, so the list is a hint, not a verdict).
        * The critic returns exactly {"verdict":"ACCEPT|REVISE","issues":[...]}; REVISE needs at least one issue, at most ten, each at most 1000 characters. Anything else is INVALID_CRITIC_OUTPUT.
        * On REVISE, while another review remains, the critique is appended to the manager's own conversation as an advisory user message and the manager answers again with its usual tools; the revision passes the same parseDraft validation and is then reviewed again. The manager revises only while another review remains, so the final verdict always describes the answer returned.
        * A critic failure (invalid output, a tool call from the tool-less critic, or a shared budget reached at the critic) becomes critic:<code> and the draft stands with verdict UNAVAILABLE. A failed revision (rejected citation, budget, invalid output) becomes revise:<code> and the previously validated draft stands. Only DEADLINE_EXCEEDED still stops the run.
        * A final REVISE adds CRITIC_UNRESOLVED; critic-rounds 0 adds CRITIC_DISABLED and makes no critic call.
        * Traced as CRITIC:review (ACCEPT, REVISE, or the failure code) and MANAGER:revise (OK or the failure code); every critic and revision call counts against the shared model-call, token, context, and deadline budgets.
    * Why an Explicit Loop
        * Spring AI also provides ChatClient's ToolCallingAdvisor loop.
        * This implementation calls ChatModel directly and owns execution, making the limits and validation easy to test.
        * Do not wrap this path in a second auto-executing tool loop; there must be one execution owner.
        * Model-provider changes can retain the broker interface and tool contracts.
        * OpenAI requests use maxCompletionTokens; other ChatModel implementations receive generic maxTokens.

* RecommendationTools
    * Lifetime
        * One instance per run; no shared conversation or evidence cache.
    * Specialist tools (the manager cannot call these directly)

| Tool | Arguments | Availability | Behavior |
|---|---|---|---|
| searchFilings | query | Always | Search the request ticker's latest stored filings; at most 5 passages per call. Since 2026-09-12 the search is hybrid by default (keyword plus vector candidates fused by reciprocal rank, `rag.retrieval.hybrid-enabled`), for the filings prefetch and the specialist's own calls alike; see RAG.md, Hybrid Retrieval. Since the 2026-09-12 fusion tuning the keyword leg weighs 0.5 against the vector leg's 1.0 and a figure leg (chunks containing every number in the question, weight 1.0) is active for numeric questions, so a question quoting a figure such as 215,938 ranks the chunk holding it first (RAG.md, Fusion tuning) |
| findInstrument | None | Broker enabled | Discover stock contracts for the request ticker |
| getQuote | conid | Broker enabled | Discover implicitly if needed; the conid must be the request conid, the only listing, or the single preferred-currency listing |
| getPortfolioPositions | None | Broker enabled and includePortfolio=true | Read the configured account's positions |
| analyzePriceHistory | conid | Broker and quant enabled | Same contract guards as getQuote; deterministic statistics and ATR-based levels from stored daily bars |

* Tool Validation
    * Require a JSON object with exactly the declared argument names.
    * Validate argument types, lengths, and positive integral contract IDs before calling services.
    * The model cannot change the ticker or configured account through tool arguments.
    * A supplied request conid must still appear in discovery results for the requested ticker; per-contract tools run discovery themselves when findInstrument was not called first.
    * Multiple discovered contracts without an explicit request conid resolve to the single listing in recommendation.preferred-currency (USD by default) and are disclosed as CONTRACT_SELECTED_BY_POLICY:<conid>:<exchange>:<currency>; any other situation produces AMBIGUOUS_CONTRACT. TWS returns foreign cross-listings (MEXI/MXN, EBS/CHF, TSE/CAD, LSEETF/GBP) for most US tickers, so a ticker alone is normally sufficient.
    * Broker errors become structured error results, so the model can explain missing data.
    * Unexpected service failures become TOOL_UNAVAILABLE without exposing exception bodies.
    * The prompt identifies filings and other tool results as untrusted data, not instructions.

* Prompt Injection Posture
    * Instructions reach the models only from the application's own system prompts. The user question, filing passages, quotes, statistics, prior runs, specialist summaries, and critiques travel as data: the question as JSON in a user message, everything else in tool results or evidence messages labelled untrusted. No text from those sources is ever placed in a system message.
    * Whatever a passage or question says, the application decides: the tool allowlist per role (an order or portfolio tool named in a passage does not exist for that role), the fixed argument shapes (the ticker cannot be changed through a search), citations (only chunk IDs retrieved in this run are accepted), the output contract (numeric fields in the answer are rejected), status (a delayed quote stays PARTIAL), confidence, and levels (computed from application state).
    * Retrieved passages are screened by RecommendationTools.looksLikeInstructions, a deterministic pattern check for text addressed to a model: override phrases ("ignore all previous instructions"), role markers ("SYSTEM:", "Assistant:"), tool or citation directions ("call the ... tool", "cite chunk"), and answer directions ("respond only with BULLISH", "set the confidence"). A hit adds EVIDENCE_INSTRUCTION_LIKE:<chunkId> to the limitations (visible to the manager through the specialist report), lists the chunk in instructionLikePassages for the manager and the critic, and changes nothing else: the passage stays in evidence because it may still be genuine, and every passage is untrusted regardless. Ordinary filing prose using "disregard", "instructions", or "prior" alone is not flagged.
        * The screened ids are kept in RecommendationTools.instructionLikeEvidence, a synchronised insertion-ordered set written where evidence is, and every reader (the EVIDENCE_INSTRUCTION_LIKE limitations and instructionLikePassages of a specialist report, the prefetch message, and the critic's evidence) takes instructionLikeCopy(), a copy made while holding the set's lock; the lock is never held across retrieval, a model call or serialisation. Since 2026-09-19: with parallel specialists two filing searches of one manager turn write the set from two threads, and the earlier plain set could lose a screened id from the disclosure or fail the specialist with researchFilings:TOOL_UNAVAILABLE. Ids, order and limitation strings are unchanged. RecommendationToolsTests writes from four threads while copies are taken and checks that no screened id is lost and that each copy is a prefix of the final order; with the plain set the same test fails.
    * Retrieval cannot be skipped: the harness searches the filings with the user's question before the RAG specialist model runs (prefetch-filings), exactly as it prefetches broker data, so "do not search filings" in a question or a passage changes nothing about what evidence exists.
    * What this does not do: it cannot stop a model from being persuaded by a passage into a wrong but well-formed answer. That risk is met by the critic, the outcome scoring, and the evaluation set (AGENT-3 in [Follow_Ups.md](Follow_Ups.md)), not by code.
    * Regression tests: RecommendationServiceTests run an adversarial passage that names a tool, another ticker, a chunk ID, numbers, and a status through every role with a scripted model that "obeys", and assert each attempt is blocked (TOOL_NOT_ALLOWED, INVALID_ARGUMENT, INVALID_CITATION, INVALID_MODEL_OUTPUT, INVALID_SPECIALIST_REPORT), that a well-behaved run discloses the passage and keeps application-computed status, confidence, and levels, that passage text never appears in a system message, and that an injected user question is delivered as data; plus the screen's true and false cases.

* Runtime Limits

| Property | Default | Enforcement |
|---|---|---|
| recommendation.max-model-calls | 14 | Stop after the allowed model responses; raised from 10 for the critic (a full run with both specialists, a review, a revision, and a re-review uses about eleven) |
| recommendation.max-tool-calls | 12 | Reject a tool batch that would exceed the remaining budget; the broker prefetch reserves up to three (raised from 10) |
| recommendation.deadline-ms | 120000 | Caller timeout plus interruption and worker checks between operations; raised from 60000 to leave room for first-run ingestion (RECOMMENDATION_DEADLINE_MS) |
| recommendation.parallel-specialists | true | Run manager delegations concurrently; false executes them in order |
| recommendation.auto-ingest | true | Ingest missing and refresh stale filings before RAG research; false only assesses and discloses |
| rag.refresh.* | see RAG.md | Per-type limits, cadence, recheck window, and nightly schedule shared with the refresh job |
| recommendation.preferred-currency | USD | Listing chosen among several when no request conid is supplied |
| recommendation.track-record-runs | 10 | Prior stored runs shown to the manager with outcomes; 0 disables the look-back |
| recommendation.critic-rounds | 2 | Maximum critic reviews of the manager's answer; the manager revises only while another review remains; 0 disables the critic |
| Specialist pool | 4 threads | Shared by concurrent runs; a saturated pool delays a specialist within the deadline |
| recommendation.max-output-tokens | 1200 | Per-model-request output limit |
| recommendation.max-observed-tokens | 40000 | Stop after reported cumulative usage exceeds the threshold; raised from 16000 because the critic and a revision each re-read the run's context |
| recommendation.max-tool-result-chars | 32000 | Reject oversized serialized tool results; one five-passage search of 4000-character chunks with citations is about 25000 (raised from 24000) |
| recommendation.max-context-chars | 120000 | Check message text, tool arguments, and tool results before each model call; fits two full searches (raised from 80000) |
| recommendation.max-quote-age-seconds | 120 | Freshness requirement for COMPLETE status |
| recommendation.prefetch-filings | true | Search the filings with the user's question before the RAG specialist model runs; false leaves retrieval to the model |
| recommendation.search-top-k | 5 | Passages per searchFilings call; the largest single token lever because passages are re-sent to every later call |
| recommendation.model-passage-chars | 4000 | Longest passage text any model sees (specialist results, manager evidence, critic); stored evidence, citations, sources, and the numeral check keep the full text |
| recommendation.rate-limit-retry-ms | 5000 | One retry of a model call the provider rate-limited (HTTP 429), disclosed as MODEL_RATE_LIMITED_RETRIED; the failed attempt is not a budgeted call; 0 disables |
| Worker slots | 2 | Fixed concurrent runs; reject overflow |
| spring.ai.openai.chat.timeout | 20s | Bound an individual OpenAI request |
| spring.ai.openai.chat.max-retries | 0 | Avoid SDK retry multiplication inside the loop |

* Where the Tokens Go, and the Lean Profile
    * Provider limits are per minute across all calls, and every call re-sends its whole conversation. In one run the same filing passages travel four or five times: the search result to the RAG specialist, the specialist's report (with the passages) to the manager, the cited passages to the critic, and the manager's whole history again on a revision. Five 4,000-character passages are about 5,000 tokens per copy, so a directional run with a revision reaches 22,000 to 29,000 observed tokens in about 20 seconds; two such runs inside one minute exceed a 30,000 tokens-per-minute allowance, which is what the first watchlist pass hit.
    * Levers, in order of effect: search-top-k (passages per search), model-passage-chars (passage length at the model boundary; the audit row and citations always keep the full text), critic-rounds (each round is one or two more full-context calls), track-record-runs (prior runs shown to the manager; the critic gets only the statistics), and max-output-tokens. Full context reaches the manager and the critic regardless of who cites what, so the passage levers dominate.
    * application-lean.yaml (activate with SPRING_PROFILES_ACTIVE=lean) sets search-top-k 3, model-passage-chars 1500, critic-rounds 1, track-record-runs 3, max-output-tokens 700, max-observed-tokens 20000. Measured 2026-09-12 on the same two-ticker watchlist pass, broker off: defaults 22,788 + 15,237 tokens; lean 6,549 + 7,771 tokens, both runs with critic ACCEPT and the full passages still in sources and the audit row. The cost: with shorter and fewer passages the AAPL run declared INSUFFICIENT_EVIDENCE where the default run had found a direction. Use lean for functional testing and development; use the defaults, with the 60-second watchlist pause, when the runs are meant to produce scorable outcomes.

* Budget Semantics
    * Observed usage is measured after responses. It is not a prepaid token limit or hard dollar ceiling.
    * One response can exceed the observed-token threshold before the run is stopped.
    * Zero observedTokens can mean that the provider did not report usage.
    * The context character limit is an approximation and excludes tool-schema/provider serialization overhead.
    * Deadline cancellation is cooperative; a dependency that ignores interruption can keep a worker occupied until its own timeout.
    * Auto-ingestion runs SEC downloads, parsing, and embedding inside the run; a deadline during ingestion interrupts the worker, but each filing commits or rolls back as a unit, so no partial filing is left behind. A first run for a new ticker can take a minute or more; ingest ahead of time through POST /api/rag/ingest when latency matters.
    * The harness checks cancellation before starting subsequent operations.
    * Set downstream database/network timeouts for deployment. This change adds a dedicated IBKR timeout and chat timeout.
    * A caller-side deadline/uncaught failure returns no partial transcript and zero counters; those counters are unavailable, not a claim of zero incurred usage.

* RecommendationRequest
    * ticker: required; 1–16 letters, digits, periods, or hyphens.
    * question: required, nonblank, maximum 4000 characters.
    * conid: optional positive IBKR contract ID; needed only when the ticker has several listings in the preferred currency.
    * includePortfolio: false by default; explicitly enables sending position data to the configured model provider.
    * No accountId, broker password, or TWS host/port is accepted in this request.
    * No field sets the run purpose (see Recommendation Audit Store). The application's JSON mapper ignores unknown properties, so a body carrying "purpose" is accepted and the run is stored as USER; RunPurposeTests post such bodies through the controller with the application's mapper and read the stored rows.

* RecommendationResponse
    * runId: request correlation ID.
    * ticker: normalized ticker.
    * assessment: BULLISH, NEUTRAL, BEARISH, or INSUFFICIENT_EVIDENCE.
    * reasoning: model-generated qualitative explanation after structural/citation validation.
    * sources: cited RetrievedFilingChunk records, including original URLs and metadata.
    * quotes: normalized broker quote snapshots with timestamps and availability.
    * limitations: application-generated missing-data, tool-error, and capability notes.
    * toolTrace: tool name, sanitized outcome, and elapsed milliseconds.
    * modelCalls and observedTokens: usage counters when available.
    * takeProfit and stopLoss: from priceAnalysis long levels for BULLISH, short levels for BEARISH; null for NEUTRAL, INSUFFICIENT_EVIDENCE, stale bars, or no analysis.
    * confidence: input-coverage composite in [0,1] (50% cited-passage similarity, 25% quote verification, 25% price-history availability); null for INSUFFICIENT_EVIDENCE; never itself calibrated, so it remains the predictor that later snapshots calibrate. Since hybrid retrieval became the default (2026-09-12, RAG.md), a cited passage found by the keyword leg can carry a lower vector similarity than a vector-only result would, so the raw confidence may dip for the same question.
    * calibratedConfidence: the realized direction hit rate of prior runs with similar raw confidence, shrunk toward the overall hit rate, from the newest READY calibration snapshot; null unless calibration.status is APPLIED.
    * calibration: status APPLIED, INSUFFICIENT_SAMPLE, NO_CALIBRATION, NOT_DIRECTIONAL, or UNAVAILABLE; the snapshot id, computedAt, horizonDays, samples, baseRate, priorWeight; and the raw-confidence bin used with its binSamples and binHitRate. Null when the run has no confidence.
    * priceAnalysis: the run's QuantAnalysis with provenance and limitations, or null.
    * dataFreshness: latestFilingDates per type, filingsVerifiedAt, filingsMayBeStale, barsAsOf, quoteUpdatedAt, quoteAvailability; what this run actually saw.
    * trackRecord: the prior runs and per-assessment statistics the manager was shown, or null when none exist or the look-back is disabled.
    * critique: the critic's final word on the answer returned: verdict ACCEPT, REVISE, or UNAVAILABLE; issues from the last valid review; reviews, every review in order with the draft assessment it judged, its verdict, and its issues, so a rejected first draft stays on record; revised (the manager changed its answer after a critique); unsupportedNumerals (numerals in the reasoning not found in the run's evidence). Null when the critic is disabled or the run stopped before an answer.
    * HTTP 200 returns a structured run result, including unsuccessful research statuses; callers must inspect status.
    * AUDIT_NOT_PERSISTED in limitations means the run completed but its audit row could not be written; inspect the application log.
    * FILINGS_MAY_BE_STALE means the newest stored quarterly filing is past cadence and the SEC index could not be compared in this run; ensureFilings:<code> names the cause.
    * CRITIC_UNRESOLVED means the critic's last review of the returned answer was REVISE; read critique.issues. critic:<code> and revise:<code> mean the critic or the revision failed and the draft stands; CRITIC_DISABLED means critic-rounds is 0.
    * CONFIDENCE_UNCALIBRATED is present whenever calibratedConfidence is null; calibration.status says why.
    * MODEL_RATE_LIMITED_RETRIED means one model call hit a provider rate limit and succeeded on its single retry; MODEL_UNAVAILABLE as a status means a provider failure stopped the run (see the status table).
    * Invalid requests return HTTP 400; missing/wrong access tokens return HTTP 401; capacity exhaustion returns HTTP 429.

| Status | Meaning |
|---|---|
| COMPLETE | Cited qualitative assessment, verified current realtime quote, and portfolio retrieved if requested |
| PARTIAL | Cited assessment with missing/unverified current quote or requested portfolio |
| INSUFFICIENT_EVIDENCE | Model could not support an assessment |
| INVALID_CITATION / MISSING_EVIDENCE | Unsupported citation or evidence-free assessment rejected |
| INVALID_MODEL_OUTPUT | Final response violates the expected JSON contract |
| TOOL_NOT_ALLOWED / INVALID_TOOL_CALL_ID | Tool protocol or allowlist violation |
| TOOL_LIMIT / MODEL_CALL_LIMIT | Iteration budget exhausted |
| TOKEN_LIMIT / CONTEXT_LIMIT / TOOL_RESULT_LIMIT | Usage or payload bound reached |
| DEADLINE_EXCEEDED | Run deadline reached |
| MODEL_UNAVAILABLE | The provider rejected or failed a model call (rate limit, 5xx, timeout); counters and trace are kept, and at the critic or a revision the validated draft is returned instead |
| FAILED | Unexpected runtime failure outside a model call; the cause class is logged at WARN and the stack at DEBUG |

* Meaning of COMPLETE
    * COMPLETE describes the research inputs and validation path, not investment accuracy or execution readiness.
    * Realtime availability alone is insufficient: the quote needs a usable price and recent updatedAt.
    * Delayed, frozen, missing-timestamp, stale, or materially future-dated quotes cannot satisfy completeness.
    * Structural checks prove citation provenance. Entailment of each sentence by its passage is a model judgment made by the critic, disclosed in critique, not a proof.
    * Numerals in free text are checked for presence in the run's evidence, not mathematically verified; the critic sees the misses as suspects.
    * POSITION_SIZING_NOT_IMPLEMENTED remains present even on COMPLETE research, and CONFIDENCE_UNCALIBRATED until a READY calibration snapshot applies to a directional run; QUANT_DISABLED or NO_PRICE_HISTORY appears when no analysis was attached.

* Enabling the Loop
    * Keep the existing database, OPENAI_API_KEY, and SEC_USER_AGENT configuration.
    * Filings are ingested automatically for a ticker with nothing embedded and refreshed when past cadence (per rag.refresh.limits: newest 10-K, 10-Q, and three 8-Ks); pre-ingest through POST /api/rag/ingest for faster first runs.
    * Set a tool-capable chat model available to your provider account.
    * Configure the TWS socket adapter as described in [IBKR.md](IBKR.md) for quote and optional portfolio context.
    * Set QUANT_ENABLED=true for deterministic levels; see [Quant.md](Quant.md).
    * Broker-disabled runs can still produce filing-based PARTIAL research without levels.
    * Reuse the application's INTEGRATION_ACCESS_TOKEN; do not generate a different token in the request shell.

```bash
export CHAT_MODEL_PROVIDER=openai
export RECOMMENDATION_MODEL=YOUR_TOOL_CAPABLE_MODEL
export RECOMMENDATION_ENABLED=true
export INTEGRATION_ACCESS_TOKEN="$(openssl rand -hex 32)"

./mvnw spring-boot:run
```

* Request Example

```bash
curl -X POST http://localhost:8080/api/recommendations \
  -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "ticker": "AAPL",
    "question": "Assess the latest stored filing risks and current quote.",
    "includePortfolio": false
  }'
```

* Recommendation Audit Store
    * Purpose
        * Postgres is the PRD's system of record; before this table, runs existed only in the HTTP response and the log.
        * Stored runs are the raw material for outcome measurement, confidence calibration, and the agent's own track record.
    * RecommendationRepository
        * save(RecommendationRecord record): insert one row; rows are never updated by the loop.
        * findByRunId(String runId): one stored run, whatever its purpose.
        * findByTicker(String ticker, int limit): newest USER runs for a ticker; EVALUATION runs are never listed.
    * RecommendationRecord fields
        * runId, ticker, conid (from the price analysis or first quote), requestedAt, completedAt, question.
        * status, assessment, takeProfit, stopLoss, confidence, lastClose, barsAsOf (the newest bar the levels were computed from), quoteAvailability.
        * citedChunkIds, limitations, modelCalls, observedTokens.
        * promptVersion (RecommendationService.PROMPT_VERSION; bump on any prompt change), model, quantVersion (QuantProperties.version(), the ATR/level parameter set), processingVersion (filing processing version).
        * responseJson: the complete RecommendationResponse as JSONB.
        * purpose: USER or EVALUATION (RunPurpose). It is a column of the stored record only; RecommendationResponse and responseJson do not carry it.
    * Run purpose
        * USER runs are product data. EVALUATION runs measure the loop itself (plan [2026-09-19-answer-evaluation](plans/2026-09-19-answer-evaluation.md)) and must not feed product data.
        * Readers of the recommendations table and how each treats EVALUATION:
            * RecommendationRepository.findByRunId (GET /api/recommendations/{runId}, OutcomeEvaluationService.evaluate): returns the run and shows its purpose.
            * RecommendationRepository.findByTicker (GET /api/recommendations?ticker=, and TrackRecordService, so the track record given to the manager and the critic): USER only.
            * RecommendationRepository.findPendingEvaluation (outcome scoring passes): USER only.
            * OutcomeEvaluationService.evaluate(runId) (POST /api/outcomes/evaluate/{runId}): finds the run, scores nothing when its purpose is not USER, and returns its stored outcomes (none).
            * OutcomeRepository.summary() and CalibrationRepository.samples(horizon): the joins keep USER runs only, so an outcome row attached to an EVALUATION run by any route still stays out of the aggregates and the calibration.
            * The watchlist reads nothing from the table; it calls recommend(request), so its runs are USER, and its last-run view holds only its own pass.
        * The filters are written as purpose = 'USER', so a purpose added later is excluded from product data until someone decides otherwise.
    * Endpoints (same access token as POST)
        * GET /api/recommendations/{runId}: one stored run of either purpose, with its purpose; 404 when unknown, 400 for a malformed ID.
        * GET /api/recommendations?ticker=AAPL&limit=20: newest stored USER runs for a ticker.
    * Schema
        * Migration V5 creates recommendations with a primary key on run_id, a confidence check in [0,1], and indexes on (ticker, requested_at) and requested_at.
        * Migration V10 adds purpose VARCHAR(16) NOT NULL DEFAULT 'USER' with a check on the two values. Rows stored before V10, and rows written by code that does not name the column, read back as USER. No index was added: every filtered query already selects by ticker or by the pending-outcome conditions.
    * Point-in-time discipline
        * barsAsOf, quoteAvailability, and citedChunkIds record what the run actually saw; later evaluation must only use bars and filings dated after requestedAt.
        * Stopped and failed runs are stored with their status so the record is complete rather than success-only.
    * Consumers
        * [Outcomes.md](Outcomes.md) scores stored runs at fixed horizons; calibration and a look-back tool for the agent are still to come.

* Watchlist Schedule
    * Purpose
        * Outcome scoring, the track record, and calibration all need directional runs made while the broker is up, and nobody should have to send requests at 22:45 SGT by hand. WatchlistScheduler runs the configured tickers through the ordinary recommend(...) path on a cron in exchange time, one ticker at a time, with a directional question template.
        * A scheduled run is an ordinary run: same validation, budgets, critic, audit row, and nightly scoring. Scheduled runs are recognisable by their question text (the template with the ticker substituted).
    * Properties (prefix recommendation.schedule)

| Property | Default | Meaning |
|---|---|---|
| recommendation.schedule.enabled | false | Create the scheduler and its endpoints (WATCHLIST_ENABLED); requires recommendation.enabled and at least one ticker |
| recommendation.schedule.tickers | AAPL,MSFT,NVDA | Tickers run in order each pass (WATCHLIST_TICKERS) |
| recommendation.schedule.cron | 0 45 10 * * MON-FRI | Fires 75 minutes after the US open, in the zone below |
| recommendation.schedule.zone | America/New_York | Cron time zone (22:45 SGT in US summer, 23:45 in winter) |
| recommendation.schedule.question | directional template | {ticker} is substituted; asks for BULLISH or BEARISH over the next 20 trading days unless the evidence cannot support a direction |
| recommendation.schedule.pause-ms | 60000 | Pause between tickers: a full run with the critic can use most of a 30,000 tokens-per-minute provider allowance, so consecutive runs must sit in separate minutes; it also leaves a worker slot free for manual requests |
| recommendation.schedule.include-portfolio | false | Send positions with scheduled runs |

    * Behaviour
        * run(trigger) is synchronized: a manual trigger during a scheduled pass waits for it. Each ticker's failure (HTTP 429 capacity, 400 validation, or an unexpected exception) is recorded as an error in the pass summary and the next ticker runs. The pass returns when the last ticker finishes; with three tickers, default deadlines, and the pause it takes under two minutes in practice.
        * The last pass summary (trigger, start, elapsed, per-ticker run ID, status, assessment, raw and calibrated confidence, critic verdict, or error) is kept in memory and logged; durable history is PLAT-2 in [Follow_Ups.md](Follow_Ups.md).
        * Exchange holidays are not known to the scheduler: a pass on a holiday produces runs without a current quote (PARTIAL) whose bar date is the previous session; they are still scored.
    * Endpoints (integration access token required)
        * GET /api/recommendations/watchlist: configuration and the last pass.
        * POST /api/recommendations/watchlist/run: run the watchlist now (MANUAL trigger).
        * GET /api/recommendations/watchlist/last: the last pass only.

* Portfolio and Contract Selection
    * Set includePortfolio=true when you want the model to receive holdings from the configured account.
    * A ticker alone normally suffices: the single preferred-currency listing is selected and disclosed. If the response reports prefetch:AMBIGUOUS_CONTRACT, inspect /api/broker/instruments?symbol=AAPL and send the intended conid on the next request.
    * Every request starts a new run; there is no cross-request conversational memory yet.

* Deterministic Evaluation Harness
    * RecommendationServiceTests execute the actual loop with scripted ChatModel responses and simulated service results.
    * Scenarios cover successful research, expired sessions, unknown tools, portfolio opt-in, invalid arguments, fake citations,
      missing evidence, numeric output fields, ambiguous contracts, stale/delayed quotes, iteration limits, oversized results,
      context and usage limits, deadline interruption, and evidence isolation between requests.
    * Critic scenarios: a tool-less review that accepts, a review that sends the manager back once on its own conversation and accepts the revision, unresolved critiques after the last review, invalid verdicts and critic tool calls and budget exhaustion that leave the draft standing, a rejected revision, the disabled switch, and the deterministic numeral check. Scripted scenarios that end at the manager's answer run with critic-rounds 0.
    * Tests assert outcomes and enforced boundaries; they do not require the production model to choose one exact call sequence.
    * IntegrationWiringTests verify disabled defaults and enabled dependency wiring without external requests.
    * No live model calls are made by these tests. Their results do not measure model reasoning quality or investment returns.
    * Verification on 2026-09-09: the full suite passed against disposable PostgreSQL/pgvector: 82 tests discovered, 81 passed, 1 opt-in live IBKR test skipped.
    * Live TWS authentication and market-data entitlements require an interactive TWS login; see the current [TWS setup](IBKR.md).

```bash
./mvnw -Dtest=RecommendationServiceTests,IntegrationWiringTests test
```

* Answer Evaluation (since 2026-09-19; plan [2026-09-19-answer-evaluation](plans/2026-09-19-answer-evaluation.md), Milestone 2; AGENT-10 in [Follow_Ups.md](Follow_Ups.md))
    * Purpose
        * The retrieval evaluation ([RAG.md](RAG.md), Retrieval Evaluation) measures whether the expected chunk is returned. This pass runs the same set's questions through the recommendation loop and records, per question, whether the expected chunk was retrieved during the run, whether the accepted phrase was inside the text the model was shown, and whether the answer cites a chunk holding it.
        * Every question is one recommendation run and calls the chat model (the retrieval evaluation never does). Token discipline applies: lean profile, paced runs, a budget approved before any live pass (plan, Boundaries). The measures below are defined and tested on scripted runs; the first live passes (a pilot and one pass over the set, 2026-09-19) are under First live passes below. (Changed 2026-09-19, Milestone 3: this bullet said no live pass had been run.)
    * Path to the model (no new one)
        * AnswerEvaluationService (`rag.evaluation`) builds RecommendationRequest(ticker, question, conid null, includePortfolio false) from a set question and calls RecommendationService.recommendForEvaluation. It builds no prompt and calls no model. The service validates the request as any other, runs the same tool allowlist, prefetch, instruction-like passage screen, citation validation and critic, and stores the run with purpose EVALUATION (Recommendation Audit Store, Run purpose).
        * Only the set's ticker and question text enter the run. The question id, the expected passages, and the purpose never reach a prompt (AnswerEvaluationServiceTests reads every prompt of a scripted run, and asserts EVIDENCE_INSTRUCTION_LIKE:<chunkId> and instructionLikePassages for an instruction-like passage retrieved for an evaluation question).
        * Broker and quant stay off for evaluation runs (IBKR_ENABLED unset), so an answered run reads PARTIAL with BROKER_DISABLED, QUANT_DISABLED and NO_VERIFIED_CURRENT_QUOTE; these codes are recorded, not treated as failures.
    * Endpoints (integration token required, gated in IntegrationAccessConfiguration like `/api/rag/evaluate`)
        * `POST /api/rag/evaluate/answers` runs a pass and returns the stored snapshot when the pass ends. Optional `questions=` (comma-separated question ids; the pass keeps set order) and `limit=` (then keeps the first so many). The request is synchronous: a full paced pass takes many minutes, so call it with a long client timeout; the snapshot is stored when the pass ends even if the caller has gone, and is then read with GET.
        * `GET /api/rag/evaluate/answers` returns the newest snapshot (404 before the first), `GET /api/rag/evaluate/answers/{id}` one snapshot (404 unknown).
        * Refusals, each before any run is started and so before any model call, with the reason in the problem detail: 503 when recommendation.enabled is false (the message names RECOMMENDATION_ENABLED); 400 for a blank `questions`, an id the set does not hold, or a limit below 1 (a typo must not widen a pass that spends tokens); 409 while another pass is running (one pass at a time per application).
    * Measures per question (definitions frozen in the plan, Boundaries; AnswerEvaluationService.measure)
        * "Holds an accepted phrase" is the retrieval evaluation's rule, the same function (RetrievalEvaluationService.matches): same accession number and section key, and the chunk's full content contains the phrase, case-insensitive with whitespace collapsed.
        * `retrievedExpected`: some chunk retrieved during the run (the prefetch and any specialist search) holds an accepted phrase; `expectedChunkIds` lists them; `retrievedCount` is how many chunks the run retrieved.
        * `visibleToModel`: for some such chunk, the phrase lies inside the text the model was shown for it, after the model-passage-chars cut (the same normalised containment, on the shown text from recommendForEvaluation). A phrase that straddles the cut is not visible. `visibleChunkIds` lists the chunks. False when nothing expected was retrieved. "The model" here is any model of the run that was sent the passage, which is at least the RAG specialist whose search (or prefetch) retrieved it; it is not necessarily the manager. The manager receives passages only inside an accepted specialist report, so when the report is rejected (INVALID_SPECIALIST_REPORT inside the service) the manager answers without them while `visibleToModel` still reads true. The service discloses that rejection as the limitation code `researchFilings:INVALID_ARGUMENT` (executeCall maps the internal reason to INVALID_ARGUMENT; the same code covers a delegate call made with arguments), so it is already recorded per question in `limitations` and counted in `limitationCounts`; read `visibleToModel` beside it.
        * `citedExpected`: a cited chunk (the response's sources) holds an accepted phrase, in its full content: a chunk can be cited while its phrase was cut out of view. `citedCount` is the number of cited chunks, `citedHoldingPhrase` how many of them hold an accepted phrase, `citedChunkIds` the ids.
        * `figuresInReasoning`, FIGURE questions only (the set's `kind`): the figures of an accepted phrase are its numeric tokens under the retrieval figure rule (FilingRetrievalRepository.figureTokens, the tokeniser and year rule behind figureTerms: digits with commas and periods kept between digits, length 2 or more, four-digit tokens from 1900 to 2100 left out). A figure appears in the reasoning when a numeric token of the reasoning (same tokeniser, years kept, any length: FilingRetrievalRepository.numericTokens(text, 1)) has the same numeric value once thousands commas are dropped: 42,000 matches 42000, 40.4 matches "$40.4 billion" and 40.40, 65 matches 65%, and the phrase figure 7.0 matches "$7 billion" (corrected 2026-09-19: reasoning numerals of one character were dropped before, so that last match could never happen; the phrase side is unchanged, so a one-character numeral in a phrase is still not a figure). A rescaled or rounded figure (64.4 billion for 64,377) does not match, and a figure inside a longer numeral (282 in 1,282, 7 in 17) does not. True when every figure of some accepted phrase appears; phrases without a figure are skipped; null when no accepted phrase has a figure, and for every NARRATIVE question. `figureTokens` and `figureTokensMissing` record the phrase the check settled on (when it fails, the phrase with the fewest missing figures, the first on a tie).
        * Recorded beside them from the run: `runId`, `status`, `assessment`, `limitations`, `criticVerdict` (null when the critic is off or the run stopped before an answer), `unsupportedNumerals` (the critic's deterministic numeral check), `modelCalls`, `observedTokens`, `elapsedMs` (measured by the runner around the service call), and `reasoning` (so the figure check can be re-derived from the snapshot: since 2026-09-19 the check reads the reasoning as it is stored, with U+0000 removed, where it read the raw text before, so "4", U+0000, "2" is the numeral 42 on both sides).
        * Unknown is null, not false: when `evidenceCaptured` is false (see recommendForEvaluation) `retrievedExpected`, `visibleToModel` and their id lists are null; a question the service refused has `error` set, no `runId`, and no measures.
            * A run that produced no answer is not unknown: by the frozen definitions it reads false on `citedExpected` (it cites nothing) and on `figuresInReasoning` (its reasoning is empty), and false on `retrievedExpected` and `visibleToModel` when it stopped before any search with its evidence captured. Such runs stay in the denominators of the shares below and are left out only of the `AmongAnswered` shares.
    * Aggregates (AnswerEvaluation.Aggregates; counts first, shares at scale 6, null when the denominator is 0)
        * `attempted`; `withRun` (a stored run exists); `measured` (evidence captured).
        * `shareRetrieved` = `retrieved` / `measured`; `shareVisibleGivenRetrieved` = `visible` / `retrieved`; `shareCitedGivenVisible` = `citedAndVisible` / `visible`, where citedAndVisible counts questions with visibleToModel and citedExpected both true; `shareFiguresInReasoning` = `figuresInReasoning` / `figureQuestions` (questions whose figure check is not null).
        * Answered runs (AnswerEvaluationService.answered): the status is COMPLETE, PARTIAL or INSUFFICIENT_EVIDENCE, the three statuses the service gives a validated answer (INSUFFICIENT_EVIDENCE as a status is the model's own assessment), and the reasoning is not blank. Every other status is a stop code (INVALID_CITATION, INVALID_MODEL_OUTPUT, TOKEN_LIMIT, MODEL_CALL_LIMIT, MODEL_UNAVAILABLE, DEADLINE_EXCEEDED, FAILED and the like) and comes with an empty reasoning. `answeredRuns` counts them and `noAnswerRuns` = `withRun` - `answeredRuns`.
        * Each share above has a twin restricted to answered runs, with its own counts: `shareRetrievedAmongAnswered` = `retrievedAmongAnswered` / `measuredAmongAnswered`; `shareVisibleGivenRetrievedAmongAnswered` = `visibleAmongAnswered` / `retrievedAmongAnswered`; `shareCitedGivenVisibleAmongAnswered` = `citedAndVisibleAmongAnswered` / `visibleAmongAnswered`; `shareFiguresInReasoningAmongAnswered` = `figuresInReasoningAmongAnswered` / `figureQuestionsAmongAnswered`; `insufficientEvidenceRateAmongAnswered` = `insufficientAmongAnswered` (answered runs whose status is INSUFFICIENT_EVIDENCE) / `answeredRuns` (corrected 2026-09-19: the numerator was `insufficientEvidence`, counted over all runs, so a run with that status and a blank reasoning was in the numerator and not in the denominator). When `noAnswerRuns` is 0 the two families are equal. The first family says what happened over all runs of the pass; the second does not let a stopped run read as an answer that failed to cite. These counts are boxed in the record, so aggregates written before they existed read them as null.
        * `insufficientEvidenceRate` = runs whose status is INSUFFICIENT_EVIDENCE / `withRun`. A run stopped by a limit also carries the assessment INSUFFICIENT_EVIDENCE but has the limit code as its status, so it is counted in `statusCounts` and not here.
        * `statusCounts` (every run status, so INVALID_CITATION, TOKEN_LIMIT, MODEL_CALL_LIMIT, DEADLINE_EXCEEDED and the like appear with their counts; `invalidCitationRuns` repeats the first) and `limitationCounts` (limitation codes, a trailing numeric id such as the chunk id of EVIDENCE_INSTRUCTION_LIKE removed; a rejected revision shows as revise:INVALID_CITATION here). `totalTokens`, `totalModelCalls`, `totalElapsedMs`.
    * Pacing and stop behaviour
        * Runs are sequential. `rag.evaluation.answers.pause-ms` (default 20,000, 0 to 600,000) is waited between two runs, not after the last and not after a run that stops the pass.
        * Evaluation runs share the recommendation service's two worker slots with user requests. When both are busy the service answers HTTP 429 without starting a run; the runner does not retry: the question is listed with error RECOMMENDATION_CAPACITY_REACHED and no run id, and the pass stops as partial ("RECOMMENDATION_CAPACITY_REACHED at <id>").
        * A provider rate limit is retried once inside the service (recommendation.rate-limit-retry-ms). When the provider still fails the run reports MODEL_UNAVAILABLE, as its status or, at the critic or a revision where the validated draft stands, as critic:MODEL_UNAVAILABLE or revise:MODEL_UNAVAILABLE. Either stops the pass as partial ("MODEL_UNAVAILABLE at <id>", with "after the rate-limit retry" when the run carries MODEL_RATE_LIMITED_RETRIED); that run is stored and listed with its measures. The response does not tell a second 429 from another provider failure, so any MODEL_UNAVAILABLE stops the pass. A rate limit whose retry succeeded does not.
        * A retrieval outage stops the pass after the run that met it: when a run carries the limitation `searchFilings:TOOL_UNAVAILABLE` (a filing search, prefetch or specialist, failed for a reason other than its arguments: the embedding call or the database), the pass is stored partial with "RETRIEVAL_UNAVAILABLE at <id>"; that run is stored and listed. Without this the pass would keep spending chat tokens on runs with no evidence. A provider failure in the same run is reported first (MODEL_UNAVAILABLE).
        * Any other exception from the service, or an interrupted pause, also stops the pass as partial with the reason. An interrupted service call surfaces as "HTTP_503 at <id>". A request the service rejects as invalid (HTTP 400) is listed with error INVALID_REQUEST and the pass continues. Per-run limits (TOKEN_LIMIT, DEADLINE_EXCEEDED, INVALID_CITATION and the like) are recorded and the pass continues.
        * Limitation: a run that ends on the service's outer stop path (status DEADLINE_EXCEEDED or FAILED) carries only its stop code in its limitations, so a `searchFilings:TOOL_UNAVAILABLE` raised inside that run is lost and the runner does not see the outage: the pass continues to the next paid run, and an outage that persists stops the pass there instead, one run later.
        * A stopped pass is stored, never repeated by the runner: `partial` true, `partialReason`, `attempted` of `questionCount`, and `notAttempted` listing the selected questions that were not reached. Each question's measures are also logged at INFO as the pass goes, since the snapshot is written only at the end.
        * The write at the end is protected, because the tokens are spent by then. The thread's interrupt flag is cleared for the write and restored after it (an interrupted pause or service call leaves it set, and a JDBC write on an interrupted thread can fail). When the write still fails, the whole snapshot is logged at INFO on one line after the prefix `ANSWER_EVALUATION_SNAPSHOT `, written as one JSON file `answer-evaluation-<UTC time to the microsecond>.json` under `rag.evaluation.answers.fallback-dir` (default `var/answer-evaluations`, relative to the working directory, created on demand; `var/` is ignored by git and, unlike the earlier default `target/answer-evaluations`, is not deleted by `./mvnw clean`), its path logged at ERROR with the instruction to keep the file, and the request answers HTTP 500 with a problem detail naming the file (`fallbackFile`) and saying to keep it, since it is the only copy of the snapshot outside the log; if the file cannot be written either, the 500 says the snapshot is in the log. The failure's message is kept out of the log above debug and out of the response. The file is the record AnswerEvaluation as JSON and reads back with the same mapper; nothing imports it into the table. The 409 guard is released as usual.
    * Storage (migration V11, `answer_evaluations`, in the style of `retrieval_evaluations`; AnswerEvaluationRepository; appended, never updated)
        * U+0000 is removed before storage, since PostgreSQL's jsonb refuses it and one such character in a reasoning would otherwise lose the pass: the runner removes it from the run text it copies (status, assessment, reasoning, limitation codes, critic verdict, unsupported numerals), so the snapshot returned equals the one stored, and the repository removes any escape of it left in the serialised JSON of any column (a literal backslash followed by u0000 is left alone) and from partial_reason.
        * One row per pass: id, evaluated_at, set_version, question_count, attempted, partial, partial_reason (present exactly when partial, by a check constraint), aggregates JSONB, properties JSONB, results JSONB (`questions`, the attempted questions in pass order each with its run id, and `notAttempted`).
        * `properties`: searchTopK, modelPassageChars, criticRounds, maxOutputTokens, maxObservedTokens, maxModelCalls, trackRecordRuns, prefetchFilings, rateLimitRetryMs, deadlineMs, promptVersion, chatModel (recommendation.model), activeProfiles, set, setVersion, setCreatedOn, storeVersions (SECFilingRepository.findDistinctProcessingVersionsOfEmbeddedFilings, as the retrieval snapshots record it), chunkMaxChars, chunkOverlapChars, hybridEnabled, rerankingEnabled, candidateCount, pauseMs, questions (the filter as given, null when absent), limit.
        * The runs themselves are rows of `recommendations` with purpose EVALUATION, readable by `GET /api/recommendations/{runId}` and absent from every product reader (Run purpose above).
    * What the measures do not show
        * No judged support: nothing here reads whether a cited passage supports the claim made from it. A cited expected chunk does not prove the claim is supported, and an uncited one does not prove the answer is wrong. The critic verdict and the numeral check are recorded beside the measures, not folded into them; a judged metric is a follow-up (plan, Boundaries).
        * `visibleToModel` says the phrase was inside text sent to a model of the run (the specialist that retrieved it), not that the manager received it (see the measure above and `researchFilings:INVALID_ARGUMENT`), and not that any model read or used it. `figuresInReasoning` is a formatting-tolerant token match: a correct figure restated in another scale reads false, and a figure that appears for another reason reads true.
        * The measures describe what happened in a run. They give no reason for a model's answer.
        * Expected passages identify chunks by accession, section and phrase at run time, so a rebuilt store or an edited set changes what a later pass matches, as for the retrieval evaluation.
    * Tests (scripted chat model, the seam RecommendationServiceTests uses; no live model call)
        * AnswerEvaluationServiceTests: the real RecommendationService under the real runner on scripted questions: expected chunk retrieved and cited; retrieved but cut out of view; retrieved, visible, not cited; an invalid citation; INSUFFICIENT_EVIDENCE with the phrase present only in another section; FIGURE with and without the figures in the reasoning; the critic verdict and unsupported numerals; a provider 429 after the retry, at the manager and at the critic; a saturated worker pool (two blocked user runs); a second pass while one runs; the disabled refusal; filter, limit and an invalid request; the pause count; the stored purpose of every run. Since the low-findings round: a pass mixing one answered run with two stopped ones (an invalid citation on a FIGURE question, invalid output before any search) asserting both families of shares; a rejected specialist report recorded as researchFilings:INVALID_ARGUMENT while visibleToModel reads true and the manager's prompt lacks the passage; a scripted retrieval failure stopping the pass; a reasoning carrying U+0000; a repository that throws (file, log line, exception, no failure message leaked, guard released) and one whose fallback directory cannot be written; the interrupt flag cleared during the save and restored, after an interrupted pause and after an interrupted service call.
        * AnswerEvaluationMeasureTests (a phrase straddling the cut, uncaptured evidence, the figure rule and its formatting cases including 7.0 against "$7 billion" and against 17, U+0000 removed from every run string, the figure check on the stored reasoning ("4", U+0000, "2"), the answered-runs numerator of the INSUFFICIENT_EVIDENCE rate with a run whose reasoning is blank after cleaning, the answered predicate per status, aggregates without the answered fields still readable), AnswerEvaluationDatabaseTests (V11, round trip, a snapshot with U+0000 in a reasoning, a limitation code, a property and the partial reason stored and read back, the partial check constraint, a scripted pass against the shared database storing exactly one snapshot whose runs read back as EVALUATION and stay out of the ticker listing, the wired runner's refusal under the default disabled flag), AnswerEvaluationEndpointTests, and IntegrationAccessTests for the token gate.
    * First live passes (2026-09-19; plan Milestone 3; evidence [answer evaluation](live-runs/2026-09-19-answer-evaluation/))
        * What ran: the pilot (`?questions=aapl-01,aapl-08,msft-05,msft-09,nvda-02,nvda-04`) and then one pass over the set with no parameters, once each, on the lean profile with `CHAT_MODEL_PROVIDER=openai` and `RECOMMENDATION_MODEL=gpt-4.1`, broker and quant off, under the token budget and the run conditions frozen in the plan's Status before the runs (the pass over the set only when the pilot is not partial and its mean observed tokens per run is at most 10,000). `run.log` has the times, the settings of the one application start, the gate decision with its numbers, and the product-data queries after the passes.
        * Files (all under the evidence directory): `get-37.json` and `get-38.json` are `GET /api/rag/evaluate/answers/{id}` saved verbatim (each parsed equal to its POST body); `app-log-37.txt` and `app-log-38.txt` are the application log from each pass's start line to its stored line; `phrase-offsets.json` is written by `export_phrase_offsets.py` from one read-only SELECT on `sec_filing_chunks` (the character span of each accepted phrase in the chunks the runs retrieved that hold one, and in the matched chunks of the retrieval reference); `tables.json` and `tables.txt` are written by `answer_tables.py` from those files and the retrieval reference, snapshot 1891 (`live-runs/2026-09-18-rag29-section-key/`), and from nothing else. Recompute with `python3 -B answer_tables.py get-37.json get-38.json` in that directory: the output files come back byte for byte, and the script recomputes every stored aggregate of both exports from their per-question rows and records whether they match (`aggregatesMatchStored`). `tables.txt` holds the per-question table (question, kind, rank in the reference, the measures, status, assessment, critic verdict, tokens).
        * How to read them. The retrieval reference ran each question's text through the retrieval endpoint at the defaults with a window of 10. An answer-evaluation run retrieves with the lean `search-top-k` and with the queries the run itself made (the prefetch and the specialist's searches), so a rank in the reference and a run's `retrievedExpected` are set side by side and are not one measurement. The blocks below count and list. They give no reason for an answer or for a retrieval result. They describe two passes on one profile, one chat model, one set and one store; they do not say what another profile, passage cut, model, or a repeated pass would record, and the pilot block shows that two runs of one question can differ. The bullets that read `tables.json` observe what that file holds; that the file is the committed script's unaltered output is a review item (rerun the script and compare), as [RAG.md](RAG.md), Claims, says of `fileValue`.
        * The passes, their recorded settings and their observed tokens, from the exports and the script's tables:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#passes start -->
        * In the pilot export (the file get-37.json), id is 37. (C-2101, observed)
        * In the pilot export (the file get-37.json), questionCount is 6. (C-2102, observed)
        * In the pilot export (the file get-37.json), attempted is 6. (C-2103, observed)
        * In the pilot export (the file get-37.json), partial is false. (C-2104, observed)
        * In the pilot export (the file get-37.json), aggregates.totalTokens is 34855. (C-2105, observed)
        * In the tables written by the committed script (the file tables.json), passes.pilot.tokens.meanPerRun is 5809.17. (C-2106, observed)
        * In the tables written by the committed script (the file tables.json), passes.pilot.tokens.maxPerRun is 6565. (C-2107, observed)
        * In the tables written by the committed script (the file tables.json), passes.pilot.aggregatesMatchStored is true. (C-2108, observed)
        * In the pilot export (the file get-37.json), properties.questions is [aapl-01, aapl-08, msft-05, msft-09, nvda-02, nvda-04]. (C-2109, observed)
        * In the export of the pass over the set (the file get-38.json), id is 38. (C-2110, observed)
        * In the export of the pass over the set (the file get-38.json), questionCount is 42. (C-2111, observed)
        * In the export of the pass over the set (the file get-38.json), attempted is 42. (C-2112, observed)
        * In the export of the pass over the set (the file get-38.json), partial is false. (C-2113, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.totalTokens is 258639. (C-2114, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.totalModelCalls is 168. (C-2115, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.totalElapsedMs is 197539. (C-2116, observed)
        * In the tables written by the committed script (the file tables.json), passes.full.tokens.meanPerRun is 6158.07. (C-2117, observed)
        * In the tables written by the committed script (the file tables.json), passes.full.tokens.maxPerRun is 7925. (C-2118, observed)
        * In the tables written by the committed script (the file tables.json), passes.full.tokens.maxPerRunQuestion is aapl-06. (C-2119, observed)
        * In the tables written by the committed script (the file tables.json), passes.full.aggregatesMatchStored is true. (C-2120, observed)
        * In the export of the pass over the set (the file get-38.json), properties.questions is null. (C-2121, observed)
        * In the export of the pass over the set (the file get-38.json), properties.activeProfiles is [lean]. (C-2122, observed)
        * In the export of the pass over the set (the file get-38.json), properties.chatModel is gpt-4.1. (C-2123, observed)
        * In the export of the pass over the set (the file get-38.json), properties.promptVersion is manager-specialists-v5-rag-prefetch. (C-2124, observed)
        * In the export of the pass over the set (the file get-38.json), properties.searchTopK is 3. (C-2125, observed)
        * In the export of the pass over the set (the file get-38.json), properties.modelPassageChars is 1500. (C-2126, observed)
        * In the export of the pass over the set (the file get-38.json), properties.criticRounds is 1. (C-2127, observed)
        * In the export of the pass over the set (the file get-38.json), properties.maxOutputTokens is 700. (C-2128, observed)
        * In the export of the pass over the set (the file get-38.json), properties.pauseMs is 20000. (C-2129, observed)
        * In the export of the pass over the set (the file get-38.json), properties.setVersion is v2. (C-2130, observed)
        * In the export of the pass over the set (the file get-38.json), properties.storeVersions is [sections-v2-context-v2-chunk4000-500]. (C-2131, observed)
        * In the export of the pass over the set (the file get-38.json), properties.rerankingEnabled is false. (C-2132, observed)
        * In the export of the pass over the set (the file get-38.json), properties.candidateCount is 40. (C-2133, observed)
        * The pilot's 34855 observed tokens are below the approved pilot figure of about 45,000 and the full pass's 258639 are below the approved figure of about 300,000; the approved figures are in the plan's Status (plans/2026-09-19-answer-evaluation.md, not read by a check), and the 11,372 tokens spent on the plan before these passes are recorded there too, so the plan's running total is 34855 + 258639 + 11372 = 304866 (arithmetic on the premises and that Status figure) (inferred from C-2105 and C-2114) (C-2134, inferred)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#passes end -->
        * The stored aggregates of the pass over the set, and the pilot's after them:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#aggregates start -->
        * In the export of the pass over the set (the file get-38.json), aggregates.withRun is 42. (C-2135, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.measured is 42. (C-2136, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.retrieved is 30. (C-2137, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareRetrieved is 0.714286. (C-2138, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.visible is 15. (C-2139, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareVisibleGivenRetrieved is 0.500000. (C-2140, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.citedAndVisible is 15. (C-2141, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareCitedGivenVisible is 1.000000. (C-2142, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.figureQuestions is 22. (C-2143, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.figuresInReasoning is 5. (C-2144, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareFiguresInReasoning is 0.227273. (C-2145, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.insufficientEvidence is 19. (C-2146, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.insufficientEvidenceRate is 0.452381. (C-2147, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.invalidCitationRuns is 0. (C-2148, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.statusCounts is {"PARTIAL":23,"INSUFFICIENT_EVIDENCE":19}. (C-2149, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.limitationCounts is {"QUANT_DISABLED":42,"BROKER_DISABLED":42,"CRITIC_UNRESOLVED":3,"CONFIDENCE_UNCALIBRATED":42,"NO_VERIFIED_CURRENT_QUOTE":42,"POSITION_SIZING_NOT_IMPLEMENTED":42}. (C-2150, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.answeredRuns is 42. (C-2151, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.noAnswerRuns is 0. (C-2152, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareRetrievedAmongAnswered is 0.714286. (C-2153, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareVisibleGivenRetrievedAmongAnswered is 0.500000. (C-2154, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.shareCitedGivenVisibleAmongAnswered is 1.000000. (C-2155, observed)
        * In the export of the pass over the set (the file get-38.json), aggregates.insufficientEvidenceRateAmongAnswered is 0.452381. (C-2156, observed)
        * In the pilot export (the file get-37.json), aggregates.retrieved is 4. (C-2157, observed)
        * In the pilot export (the file get-37.json), aggregates.visible is 4. (C-2158, observed)
        * In the pilot export (the file get-37.json), aggregates.citedAndVisible is 4. (C-2159, observed)
        * In the pilot export (the file get-37.json), aggregates.figureQuestions is 3. (C-2160, observed)
        * In the pilot export (the file get-37.json), aggregates.figuresInReasoning is 0. (C-2161, observed)
        * In the pilot export (the file get-37.json), aggregates.insufficientEvidence is 2. (C-2162, observed)
        * In the pilot export (the file get-37.json), aggregates.statusCounts is {"PARTIAL":4,"INSUFFICIENT_EVIDENCE":2}. (C-2163, observed)
        * In the pilot export (the file get-37.json), aggregates.noAnswerRuns is 0. (C-2164, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#aggregates end -->
        * Rank in the retrieval reference beside what the runs retrieved and cited:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#retrieval start -->
        * The hit@3 of the retrieval reference at the defaults (snapshot 1891) is 0.714286. (C-2165, observed)
        * The hit@5 of the retrieval reference at the defaults (snapshot 1891) is 0.785714. (C-2166, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.top3.questions is 30. (C-2167, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.top3.retrievedExpected is 30. (C-2168, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.top3.referenceMatchedChunkRetrievedInRun is 30. (C-2169, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.top3.citedExpected is 27. (C-2170, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.rank4to10.questions is 7. (C-2171, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.rank4to10.retrievedExpected is 0. (C-2172, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.rank4to10.notRetrievedIds is [aapl-09, msft-01, msft-04, msft-07, nvda-05, nvda-08, nvda-12]. (C-2173, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.notInWindow.questions is 5. (C-2174, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.notInWindow.retrievedExpected is 0. (C-2175, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceRank.notInWindow.notRetrievedIds is [msft-08, nvda-02, nvda-04, nvda-07, nvda-09]. (C-2176, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.questions is 33. (C-2177, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.retrievedExpected is 30. (C-2178, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.visibleToModel is 15. (C-2179, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.citedExpected is 27. (C-2180, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.shareCitedExpected is 0.818182. (C-2181, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.notCitedExpectedIds is [aapl-07, msft-03, msft-04, msft-12, nvda-08, nvda-12]. (C-2182, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRankAbove5OrNone.questions is 9. (C-2183, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRankAbove5OrNone.retrievedExpected is 0. (C-2184, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRankAbove5OrNone.citedExpected is 0. (C-2185, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRankAbove5OrNone.notCitedExpectedIds is [aapl-09, msft-01, msft-07, msft-08, nvda-02, nvda-04, nvda-05, nvda-07, nvda-09]. (C-2186, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.citedAndVisible is 15. (C-2258, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.citedNotVisible is 12. (C-2259, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.citedNotVisibleIds is [aapl-06, aapl-10, aapl-11, aapl-12, aapl-14, msft-02, msft-06, msft-10, nvda-01, nvda-10, nvda-11, nvda-14]. (C-2260, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.citedExpectedStatusCounts is {"PARTIAL":18,"INSUFFICIENT_EVIDENCE":9}. (C-2261, observed)
        * In the tables written by the committed script (the file tables.json), full.byReferenceHitAt5.referenceRank1to5.notCitedExpectedStatuses is {"aapl-07":"PARTIAL","msft-03":"PARTIAL","msft-04":"INSUFFICIENT_EVIDENCE","msft-12":"INSUFFICIENT_EVIDENCE","nvda-08":"PARTIAL","nvda-12":"INSUFFICIENT_EVIDENCE"}. (C-2262, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#retrieval end -->
        * Among the questions for which a run retrieved a chunk holding an accepted phrase, the phrase inside or beyond the lean cut, with the starting character of the phrase in its chunk:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#visibility start -->
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.questions is 30. (C-2187, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.modelPassageChars is 1500. (C-2188, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.visible is 15. (C-2189, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutOfView is 15. (C-2190, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutOfViewIds is [aapl-06, aapl-07, aapl-10, aapl-11, aapl-12, aapl-14, msft-02, msft-03, msft-06, msft-10, msft-12, nvda-01, nvda-10, nvda-11, nvda-14]. (C-2191, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutOfViewCitedExpectedIds is [aapl-06, aapl-10, aapl-11, aapl-12, aapl-14, msft-02, msft-06, msft-10, nvda-01, nvda-10, nvda-11, nvda-14]. (C-2192, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.offsetsAgreeWithSnapshot is true. (C-2193, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutChunkByQuestion is {"aapl-06":13868,"aapl-07":13839,"aapl-10":13876,"aapl-11":13824,"aapl-12":13825,"aapl-14":13878,"msft-02":14013,"msft-03":14016,"msft-06":13988,"msft-10":14136,"msft-12":14018,"nvda-01":14254,"nvda-10":14344,"nvda-11":14254,"nvda-14":14321}. (C-2194, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutPhraseSpanByQuestion is {"aapl-06":[1886,1957],"aapl-07":[2240,2351],"aapl-10":[1989,2158],"aapl-11":[3276,3418],"aapl-12":[1465,1581],"aapl-14":[1835,1969],"msft-02":[1904,1977],"msft-03":[2283,2421],"msft-06":[1944,2087],"msft-10":[3081,3135],"msft-12":[2827,2902],"nvda-01":[2131,2170],"nvda-10":[2497,2642],"nvda-11":[2538,2635],"nvda-14":[3289,3322]}. (C-2195, observed)
        * In the tables written by the committed script (the file tables.json), full.amongRetrieved.cutOutPhrasesStraddlingTheCut is [aapl-12]. (C-2196, observed)
        * In the phrase offset export of the store (the file phrase-offsets.json), chunks[chunkId=13825].contentChars is 3348. (C-2197, observed)
        * In the phrase offset export of the store (the file phrase-offsets.json), chunks[chunkId=13825].phrases[question=aapl-12].firstStart is 1465. (C-2198, observed)
        * In the phrase offset export of the store (the file phrase-offsets.json), chunks[chunkId=13825].phrases[question=aapl-12].firstEnd is 1581. (C-2199, observed)
        * In the phrase offset export of the store (the file phrase-offsets.json), chunks[chunkId=13825].phrases[question=aapl-12].insideCut is false. (C-2200, observed)
        * In the tables written by the committed script (the file tables.json), full.referenceMatchedChunkUnderCut.insideCut is 14. (C-2201, observed)
        * In the tables written by the committed script (the file tables.json), full.referenceMatchedChunkUnderCut.beyondCut is 23. (C-2202, observed)
        * In the tables written by the committed script (the file tables.json), full.referenceMatchedChunkUnderCut.noMatchedChunk is 5. (C-2203, observed)
        * In the tables written by the committed script (the file tables.json), full.referenceMatchedChunkUnderCut.beyondCutIds is [aapl-05, aapl-06, aapl-07, aapl-09, aapl-10, aapl-11, aapl-12, aapl-14, msft-01, msft-02, msft-03, msft-04, msft-06, msft-07, msft-10, msft-12, msft-13, nvda-01, nvda-05, nvda-10, nvda-11, nvda-12, nvda-14]. (C-2204, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#visibility end -->
        * Citation among the visible, the INSUFFICIENT_EVIDENCE runs, the figure check and the critic:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#citation start -->
        * In the tables written by the committed script (the file tables.json), full.amongVisible.questions is 15. (C-2205, observed)
        * In the tables written by the committed script (the file tables.json), full.amongVisible.cited is 15. (C-2206, observed)
        * In the tables written by the committed script (the file tables.json), full.amongVisible.notCited is 0. (C-2207, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.insufficientEvidence is 19. (C-2208, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.insufficientEvidenceIds is [aapl-06, aapl-09, aapl-11, aapl-14, msft-01, msft-02, msft-04, msft-08, msft-10, msft-12, nvda-01, nvda-02, nvda-04, nvda-05, nvda-09, nvda-10, nvda-11, nvda-12, nvda-14]. (C-2209, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.insufficientEvidenceRetrievedIds is [aapl-06, aapl-11, aapl-14, msft-02, msft-10, msft-12, nvda-01, nvda-10, nvda-11, nvda-14]. (C-2210, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.insufficientEvidenceVisibleIds is []. (C-2211, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.insufficientEvidenceCitedIds is [aapl-06, aapl-11, aapl-14, msft-02, msft-10, nvda-01, nvda-10, nvda-11, nvda-14]. (C-2212, observed)
        * In the tables written by the committed script (the file tables.json), full.listed.noAnswer is 0. (C-2213, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.kindCounts is {"FIGURE":28,"NARRATIVE":14}. (C-2214, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figureKindWithoutFigureCheckIds is [aapl-11, aapl-12, aapl-13, aapl-14, msft-13, nvda-12]. (C-2215, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figureQuestions is 22. (C-2216, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figuresInReasoning is 5. (C-2217, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figuresNotInReasoningIds is [aapl-01, aapl-02, aapl-06, aapl-07, aapl-09, msft-01, msft-02, msft-04, msft-08, msft-10, msft-12, nvda-01, nvda-02, nvda-04, nvda-09, nvda-11, nvda-14]. (C-2218, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figureQuestionsCitedExpected is 12. (C-2219, observed)
        * In the tables written by the committed script (the file tables.json), full.figures.figuresInReasoningAmongCitedExpected is 4. (C-2220, observed)
        * In the tables written by the committed script (the file tables.json), full.critic.verdictCounts is {"ACCEPT":39,"REVISE":3}. (C-2221, observed)
        * In the tables written by the committed script (the file tables.json), full.critic.unsupportedNumeralIds is [aapl-07, msft-01]. (C-2222, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#citation end -->
        * The pilot's questions beside the same questions of the later pass, a pair of runs per question (id and code lists compared as sets):
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#repeat start -->
        * In the tables written by the committed script (the file tables.json), pilotAgainstFull.questions is [aapl-01, aapl-08, msft-05, msft-09, nvda-02, nvda-04]. (C-2223, observed)
        * In the tables written by the committed script (the file tables.json), pilotAgainstFull.equalOnEveryComparedField is [aapl-01, aapl-08, msft-09, nvda-02, nvda-04]. (C-2224, observed)
        * In the tables written by the committed script (the file tables.json), pilotAgainstFull.differing is [msft-05]. (C-2225, observed)
        * In the tables written by the committed script (the file tables.json), pilotAgainstFull.differingOnHeadlineMeasures is []. (C-2226, observed)
        * In the pilot export (the file get-37.json), results[id=msft-05].citedCount is 3. (C-2227, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=msft-05].citedCount is 2. (C-2228, observed)
        * In the pilot export (the file get-37.json), results[id=msft-05].citedHoldingPhrase is 2. (C-2229, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=msft-05].citedHoldingPhrase is 2. (C-2230, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#repeat end -->
        * The rows of the questions the plan names:
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#calledout start -->
        * The retrieval reference at the defaults (snapshot 1891) ranks nvda-02 outside its window of 10 results (no matching chunk). (C-2231, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].retrievedCount is 3. (C-2232, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].retrievedExpected is false. (C-2233, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].visibleToModel is false. (C-2234, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].citedExpected is false. (C-2235, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].citedCount is 3. (C-2236, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].figuresInReasoning is false. (C-2237, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].figureTokensMissing is [68]. (C-2238, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].status is INSUFFICIENT_EVIDENCE. (C-2239, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-02].criticVerdict is ACCEPT. (C-2240, observed)
        * The retrieval reference at the defaults (snapshot 1891) ranks nvda-04 outside its window of 10 results (no matching chunk). (C-2241, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].retrievedCount is 3. (C-2242, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].retrievedExpected is false. (C-2243, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].visibleToModel is false. (C-2244, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].citedExpected is false. (C-2245, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].citedCount is 3. (C-2246, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].figuresInReasoning is false. (C-2247, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].figureTokensMissing is [42,000, 38, 31,000]. (C-2248, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].status is INSUFFICIENT_EVIDENCE. (C-2249, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=nvda-04].criticVerdict is ACCEPT. (C-2250, observed)
        * The retrieval reference at the defaults (snapshot 1891) ranks aapl-08 1st. (C-2251, observed)
        * The retrieval reference at the defaults (snapshot 1891) records chunk 13896 as the matched chunk of aapl-08. (C-2252, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=aapl-08].expectedChunkIds is [13896]. (C-2253, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=aapl-08].visibleChunkIds is [13896]. (C-2254, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=aapl-08].citedChunkIds is [13896]. (C-2255, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=aapl-08].status is PARTIAL. (C-2256, observed)
        * In the export of the pass over the set (the file get-38.json), results[id=aapl-08].criticVerdict is ACCEPT. (C-2257, observed)
        <!-- generated:live-runs/2026-09-19-answer-evaluation/claims.json#calledout end -->
        * Product data after the passes (observed in `run.log`, section C3; database queries and listings, not read by a check): `recommendations` held USER 22 and EVALUATION 1 before the passes and USER 22 and EVALUATION 49 after them; the 48 run ids of the two exports are 48 rows of purpose EVALUATION; `GET /api/recommendations?ticker=` for AAPL, MSFT and NVDA listed 13, 8 and 1 runs, each of purpose USER and none of them a run id of the exports; `recommendation_outcomes` held 0 rows, so also 0 for those run ids; `answer_evaluations` held 3 rows (36, 37, 38).
        * Not measured here: judged support of a claim by its cited passage (What the measures do not show); the default profile; a second pass, so run-to-run variation beyond the pilot's questions is not recorded.

* Next Harness Milestones
    * Before expanding prompts/models: run the versioned retrieval evaluation set (RAG.md, Retrieval Evaluation: 30 filing questions with expected passages, `POST /api/rag/evaluate`, baseline snapshot 13 with hit@5 0.6, floor test `RetrievalEvaluationLiveTests`) and compare against the baseline; the answer-level runner exists (Answer Evaluation above) and its first live passes ran on 2026-09-19 under the lean profile (Answer Evaluation, First live passes; Follow_Ups AGENT-10), so a stored lean reference exists on the current prompt version; comparing a prompt change with it is not yet built (Follow_Ups AGENT-8, "Not yet built": a per-question comparison of two snapshots and a repeat-run allowance; the pilot and the pass, two runs of msft-05, differ in the chunks cited).
    * Before adding MCP: select a server, configure its client/authentication, and explicitly map approved tools into this allowlist.
    * Apply the same timeout, payload, argument, permission, and provenance checks to MCP callbacks.
    * Before multiple users: implement user identities, account bindings, per-user quotas, and retention policies.
    * Before trade execution: persist proposal versions, approvals, guard results, broker order IDs, and reconciliation state.
    * Add durable traces/replay when operational debugging needs to survive restarts; current traces are returned and logged, not stored in a new database table.
    * Manager and specialists share one configured ChatModel, with separate histories and restricted callbacks; no additional agent framework is required.

* Official References
    * [Spring AI tool-calling lifecycle](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html).
    * [Spring AI MCP client integration](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html).
    * [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling).

* Recommendation Pipeline

```text
Authenticated Request
    ↓
RecommendationController → RecommendationService
    ↓
Bounded Worker + Fresh Run Context
    ↓
lookBack: prior runs + outcomes for the ticker (TrackRecordService) → manager evidence
    ↓
Manager ChatModel → researchFilings ∥ researchBroker (specialist pool)
    ↓                                   ↓
RAG Specialist                      Broker Specialist
  ensureFilings (SEC ingest or        findInstrument / getQuote /
  refresh when stale) → searchFilings analyzePriceHistory / positions
    ↓                                   ↓
Restricted Tools → Shared, Synchronized Budget and Deadline Checks
    ↓
ToolResponseMessage ← FilingRetrievalService, FilingIngestionService, BrokerReadService, or QuantAnalysisService
    ↓
Final JSON → Citation / Evidence Validation
    ↓
Critic (no tools) reviews draft against cited passages, quotes, analysis, track record
    ↓ REVISE while a review remains → Manager revises on its own conversation → reviewed again
Quote Freshness Validation
    ↓
Levels by Assessment + Input-Coverage Confidence (application state, see Quant.md)
    ↓ BULLISH / BEARISH
Calibrated Confidence from the newest READY snapshot of stored outcomes (see Outcomes.md)
    ↓
Qualitative Research + Sources + Quotes + Levels + Confidence (+ Calibrated) + Critique + Limitations + Trace
```


* Historical change log — 2026-09-10: manager and specialist workflow (Client Portal-era runs below; current transport is TWS)
    * Manager tools are researchFilings and optional researchBroker, each taking an empty JSON object. Delegation uses the original validated request, preserving ticker, contract selection, and portfolio opt-in.
    * RAG specialist can call only searchFilings. Broker specialist can call only findInstrument/getQuote and opt-in getPortfolioPositions. Specialists cannot delegate recursively or access one another's tools. The manager consolidates results and retains the original final response schema.
    * Each delegation starts a separate message history using the same configured model. Specialist reports require a single nonempty summary field; the application attaches original retrieved evidence, normalized quotes, opted-in portfolio data, and limitations rather than trusting model-generated citation URLs or prices.
    * Specialists execute sequentially inside the existing worker. Model calls, observed tokens, tool calls (including delegation), and deadline are shared across the full run; limits do not reset per specialist. Default model-call allowance increases from 6 to 10 for delegation and consolidation. Context limits apply to each conversation; report size limits apply to specialist handoffs.
    * Traces identify MANAGER, RAG, or BROKER in the tool name. Delegation elapsed time includes the specialist's work, so trace durations must not be summed as exclusive timings. Tool-call IDs are unique within each conversation.
    * Ordinary tool/provider failures return sanitized limitations for manager handling; limit violations terminate the entire run. Final citations resolve against the run's stored evidence, and delayed/stale quotes still cannot produce COMPLETE status.
    * No separate reranking agent or model is configured. The RAG specialist uses the existing retrieval service and its optional FilingReranker extension; default retrieval remains filtered vector search with diversity filtering. A future reranker can be installed at that boundary.
    * User context and deferred refactoring decision (2026-09-10): Jay does not currently have another agent available for reranking and explicitly requested proceeding with the manager, broker specialist, and RAG specialist workflow. A dedicated reranking agent is deferred for future integration; its absence is not a blocker for the current workflow.
    * When that agent becomes available, revisit the RAG specialist's retrieval/reranking orchestration and integrate through the existing FilingReranker boundary where appropriate. Preserve original chunk IDs, citation metadata, result validation, and bounded execution. Do not assume that a separate reranking agent is configured or required today.
    * Manager delegation is model-directed. A final response without supporting filing evidence is still rejected unless it declares INSUFFICIENT_EVIDENCE; missing broker evidence remains explicit in final limitations.
    * Validation uses scripted model responses for actual manager-to-specialist execution, role isolation, shared budgets, malformed reports, missing portfolio/session data, delayed quotes, and citation isolation. This does not constitute a live-model quality evaluation.
    * Verification: 22 focused checks passed (19 recommendation workflow tests and 3 wiring tests), zero failures or skips. Log: /tmp/stock-manager-tests.log. Restart the application to load this workflow; no live model run was performed during this refactor.

* Live workflow verification — 2026-09-10, 14:01 SGT
    * Restarted the recommendation application on port 8081 with the current code, gpt-4.1, both specialists enabled, and default runtime limits. Restored the existing PostgreSQL container. Portfolio inclusion was false.
    * AAPL run `244a41e8-06ea-48b1-b21c-a4cc876e9592` returned HTTP 200 in 16.838 seconds: status PARTIAL, model assessment NEUTRAL, five cited filing passages, six model calls, and 10,035 observed tokens.
    * Trace confirms manager delegation to both specialists. RAG search succeeded; broker instrument discovery returned SESSION_NOT_READY, so no quote was retrieved. Final limitations correctly include NO_VERIFIED_CURRENT_QUOTE. An authenticated, ready IBKR session is required to verify the quote path on a subsequent run.
    * Saved [request](live-runs/2026-09-10-manager/request.json), [full response](live-runs/2026-09-10-manager/response.json), and [sanitized run log](live-runs/2026-09-10-manager/run.log). Full local application log: /tmp/stock-manager-fresh-app.log.
    * Response checks confirmed the partial status, both delegations, five AAPL sources, and SEC source URLs. This is an operational smoke test; the model's qualitative conclusions and individual factual claims were not independently evaluated.

* Live retry after user login — 2026-09-10
    * Run `9bb9b57f-e4dc-469d-bb13-ddaa9911e018`: HTTP 200 in 12.197 seconds, PARTIAL / NEUTRAL, five filing sources, six model calls, 9,990 observed tokens. Both specialist delegations executed; broker discovery returned SESSION_NOT_READY, with no quote. Portfolio inclusion remained false.
    * Direct gateway auth-status check on port 5001 returned authenticated=false, connected=false, established=false, competing=false. The broker quote path remains unverified in this retry.
    * Saved [response](live-runs/2026-09-10-manager-login-retry/response.json), [run log](live-runs/2026-09-10-manager-login-retry/run.log), and [session diagnostic](live-runs/2026-09-10-manager-login-retry/session-status.json).

* Session reset retry — 2026-09-10, 14:16 SGT
    * Run `2b0c050e-77b8-482a-a886-fa8f65c77f90`: HTTP 200 in 11.482 seconds, PARTIAL, six model calls, 10,168 observed tokens. Both specialists executed; broker discovery again returned SESSION_NOT_READY. Gateway diagnostic returned authenticated=false, connected=false, established=false, competing=false. No quote was retrieved.
    * Saved [response](live-runs/2026-09-10-session-reset/response.json), [trace](live-runs/2026-09-10-session-reset/run.log), and [session status](live-runs/2026-09-10-session-reset/session-status.json).

* Ready-session retry — 2026-09-10, 14:19–14:21 SGT
    * Broker discovery now succeeds. Initial request returned PARTIAL due to AMBIGUOUS_CONTRACT; discovery identified NASDAQ AAPL conid 265598 among four listings. Explicit-contract retry hit TOOL_RESULT_LIMIT after two filing searches.
    * A further request explicitly limited the RAG specialist to one search, preserving default application limits. Run `87670e60-68ce-46e5-a067-fdec2af5e5d8` returned HTTP 200 in 13.146 seconds, PARTIAL / NEUTRAL, five sources, eight model calls, and 11,352 observed tokens. Broker recovered from one INVALID_ARGUMENT discovery attempt, successfully discovered the contract, and called getQuote.
    * The quote snapshot returned raw availability DB but null last/bid/ask; normalized availability was UNAVAILABLE. A separate subsequent quote read still returned null prices. Session access is restored, but usable market prices remain unavailable; cause not established. Portfolio inclusion remained false.
    * Saved [final response](live-runs/2026-09-10-ready-session/bounded-response.json), [request](live-runs/2026-09-10-ready-session/bounded-request.json), [trace](live-runs/2026-09-10-ready-session/bounded-run.log), and [quote recheck](live-runs/2026-09-10-ready-session/quote-recheck.json); earlier attempt responses and traces are in the same directory.

* TWS migration — 2026-09-10
    * Broker specialist now uses the official Java TWS socket adapter through the existing BrokerReadService. Current connection: paper TWS port 7497, client ID 71, requested delayed-frozen data type 4. See [TWS setup and validation](IBKR.md).
    * Manager and specialist tools retain their contracts. TWS position reads expose quantities with unavailable market valuations; quote receipt time is not treated as a market timestamp. Existing evidence, contract-selection, portfolio opt-in, and realtime freshness gates remain enforced.
    * Full suite passed: 105 passed, one opt-in live check skipped. Separate live session/account/positions test passed. End-to-end run 9ae35127-de79-42b9-82b6-e2703e0a58ce returned PARTIAL with five sources, seven model calls, and 10,893 tokens; no usable TWS quote arrived. [Saved result](live-runs/2026-09-10-tws-migration/recommendation.json).

* Quant layer integration — 2026-09-11
    * The broker specialist gains analyzePriceHistory when quant.enabled=true; it shares the discovery, explicit-selection, and ambiguity guards with getQuote. Specialist reports now carry priceAnalysis; the manager is told to report returned statistics verbatim and remains forbidden from producing numbers.
    * RecommendationResponse gains priceAnalysis. takeProfit/stopLoss are copied from the analysis by assessment direction; confidence is computed from application state as an input-coverage composite. INSUFFICIENT_EVIDENCE responses carry null confidence and no levels. Model output containing numeric fields is still rejected.
    * Limitations renamed: QUANT_MODULE_NOT_IMPLEMENTED is replaced by QUANT_DISABLED (module off), NO_PRICE_HISTORY (module on, no analysis in this run), POSITION_SIZING_NOT_IMPLEMENTED, and CONFIDENCE_UNCALIBRATED; analysis limitations appear with an analyzePriceHistory: prefix.
    * Default budgets are unchanged. A full run with both specialists, quote, and price analysis uses about eight model calls of the ten allowed; a specialist that repeats searches can still reach MODEL_CALL_LIMIT.
    * Verification: RecommendationServiceTests cover directional levels, confidence arithmetic, neutral/insufficient handling, discovery guards, analysis limitations, and the disabled-module path with scripted model responses. No live model run was made with the new tool. Details and live broker evidence are in [Quant.md](Quant.md).

* Live end-to-end verification with quant — 2026-09-11, 14:37–14:41 SGT
    * Application on port 8081 with filings retrieval, paper TWS (client ID 71, requested delayed type 3), quant analysis, and gpt-4.1. Portfolio inclusion false; request supplied NASDAQ conid 265598.
    * First run `c8d41790-6cd5-4822-9b23-665c4ab5617a` exposed an ordering defect: the broker specialist used the request conid directly without calling findInstrument, so getQuote and analyzePriceHistory returned CONTRACT_NOT_DISCOVERED and the final response carried NO_PRICE_HISTORY with confidence 0.32. Per-contract tools now run discovery implicitly when nothing has been discovered; the conid must still appear in the ticker's discovery results (24 harness tests pass).
    * Second run `e330389a-fb34-4b8c-b2e6-3d62e2f4ac2f` after the fix: HTTP 200 in 16.3 seconds, PARTIAL / NEUTRAL, five 10-K Item 1A sources, six model calls, 11,129 observed tokens. Trace: RAG:searchFilings OK, BROKER:getQuote OK, BROKER:analyzePriceHistory OK (22 ms, from stored bars). priceAnalysis attached with last close 326.57, ATR 7.8907, trend ABOVE_LONG_SMA, momentum 0.0805, and both level pairs. Confidence 0.59. The model's reasoning cited the 50-day SMA position and last close from the analysis. Levels are null because the assessment was NEUTRAL, as designed.
    * Third run `0b4a3bb3-298c-438e-af1f-d579dda5af74` with a direction-oriented question also returned NEUTRAL (seven model calls, 11,561 tokens, confidence 0.58); the model described the technical setup as bullish but weighed filing risks against it. Attachment of levels for BULLISH/BEARISH assessments is verified by scripted tests, not by a live model run.
    * The quote in all runs returned availability UNAVAILABLE with no type callback within five seconds, despite delayed data having been delivered at 14:08 (see [IBKR.md](IBKR.md)); the market was closed. NO_VERIFIED_CURRENT_QUOTE remained, so status stayed PARTIAL.
    * Saved [request](live-runs/2026-09-11-quant-e2e/request.json), [response](live-runs/2026-09-11-quant-e2e/response.json), [directional request](live-runs/2026-09-11-quant-e2e/directional-request.json), [directional response](live-runs/2026-09-11-quant-e2e/directional-response.json), [quant endpoint output](live-runs/2026-09-11-quant-e2e/quant-analysis.json), [session](live-runs/2026-09-11-quant-e2e/session.json), and [sanitized run log](live-runs/2026-09-11-quant-e2e/run.log). No account IDs or positions appear in the saved evidence.

* Concurrent specialists and auto-ingestion — 2026-09-11
    * Manager delegations in one model response now execute concurrently on a four-thread specialist pool (recommendation.parallel-specialists, default true). RunState counters are synchronized; batch tool budgets are reserved before execution; a RunLimitException in one specialist propagates to the manager thread, which cancels the other. Deadline expiry or interruption while waiting cancels both.
    * The RAG delegation first calls ensureFilings: if the ticker has no EMBEDDED filing, the latest filing of each type in recommendation.auto-ingest-filing-types is ingested through the existing FilingIngestionService (advisory-locked per accession). TICKER_NOT_FOUND and INGESTION_FAILED become limitations prefixed ingestFilings:, and research continues against whatever is stored. Default deadline raised to 120 seconds.
    * Existing scripted scenarios run with parallel-specialists=false because they script model responses in call order. New scenarios script responses per conversation role and verify true concurrency with a two-party barrier, shared model-call and allowlist limits under concurrency, deadline interruption of both specialists, ingestion before retrieval for a missing ticker, skipping for stored tickers and when disabled, and failure-to-limitation mapping.

* Deterministic broker prefetch — 2026-09-11
    * Live MSFT run `3eb3520f-f5e4-428c-9e90-e47470f0906b` (first run for the ticker, no request conid): HTTP 200 in 73.1 seconds, PARTIAL / NEUTRAL. Auto-ingestion fetched and embedded the 2026-07-29 10-K (163 chunks) and 2026-04-29 10-Q (95 chunks) in 66.1 seconds inside the RAG branch; retrieval then cited three passages from them. The broker delegation finished in 1.0 second while the RAG branch was still ingesting, confirming concurrent execution. The broker specialist model, however, returned its report without calling any tool, so no quote or price analysis reached the manager (limitations NO_VERIFIED_CURRENT_QUOTE and NO_PRICE_HISTORY).
    * Because broker reads are deterministic, the harness now performs them itself before the specialist model runs: prefetchBroker executes findInstrument, and for an unambiguous contract getQuote and analyzePriceHistory, through the same validated callbacks with the same trace, limitation, and budget handling (each step reserves one tool call). The specialist receives an additional user message with the instruments, quotes, price analysis, and limitations, and is told to call tools only for what is missing. Scripted tests verify evidence arrives with a tool-less specialist, ambiguity without a conid fetches nothing, and an explicit conid among several listings is used directly.
    * Second MSFT run `5e049902-ceb2-4bd8-8f3a-93068935073e` with stored filings: HTTP 200 in 8.8 seconds, PARTIAL / NEUTRAL, five sources across the 10-K and 10-Q, five model calls, 7,300 tokens. Prefetch discovery ran (506 ms) but MSFT has several listings and the request carried no conid, so prefetch:AMBIGUOUS_CONTRACT was recorded and no quote or analysis was fetched; the model reported the ambiguity. Listing selection by policy is the follow-up recorded below.
    * Saved [request](live-runs/2026-09-11-multiagent-autoingest/request.json), [first response](live-runs/2026-09-11-multiagent-autoingest/response.json), [first run log](live-runs/2026-09-11-multiagent-autoingest/run.log), [second response](live-runs/2026-09-11-multiagent-autoingest/response-prefetch.json), and [second run log](live-runs/2026-09-11-multiagent-autoingest/run-prefetch.log). Full suite after these changes: 147 tests, 142 passed, 5 opt-in live tests skipped.

* Listing selection by currency policy — 2026-09-11
    * A TWS discovery probe for AAPL, MSFT, NVDA, TSLA, BRK B, and SHOP returned exactly one USD listing each plus foreign cross-listings. RecommendationTools now resolves several listings without a request conid to the single listing in the preferred currency, records selectedByPolicy, and the harness discloses CONTRACT_SELECTED_BY_POLICY:<conid>:<exchange>:<currency>. The model's own getQuote/analyzePriceHistory calls are held to the same rule, so it cannot pick a foreign listing on its own. Two listings in the preferred currency stay ambiguous.
    * Live MSFT run `a2a74d5f-b959-4e15-a1c8-eb9bd0fa2d88` with a ticker-only request: HTTP 200 in 13.8 seconds, PARTIAL / NEUTRAL, five sources across the stored 10-K and 10-Q (Items 1A, 1C, 2), five model calls, 10,224 tokens, confidence 0.51. Prefetch selected NASDAQ conid 272093 (USD), attempted the quote (UNAVAILABLE, no market-data callback at 03:00 ET), and computed the analysis from 120 fresh TWS bars: last close 492.44, ATR 10.9794, trend ABOVE_LONG_SMA, long levels 514.40 / 481.46, short levels 470.48 / 503.42. The trace interleaves RAG and BROKER entries, showing both specialists ran concurrently. The manager's reasoning cited the trend above the 50-day SMA. Levels are null because the assessment was NEUTRAL.
    * Saved [response](live-runs/2026-09-11-multiagent-autoingest/response-policy.json) and [run log](live-runs/2026-09-11-multiagent-autoingest/run-policy.log). Full suite: 148 tests, 143 passed, 5 opt-in live tests skipped, no failures.
    * Remaining gap: the quote path still returns no callback outside regular hours on this TWS session, so runs stay PARTIAL and confidence lacks its quote term until a quote is verified during market hours (21:30–04:00 SGT).

* Recommendation audit store — 2026-09-11
    * Added migration V5, RecommendationRecord, RecommendationRepository, persistence at the end of recommend(...) for every outcome, and the two read endpoints. Version tags are PROMPT_VERSION `manager-specialists-v3-prefetch`, the configured model, QuantProperties.version(), and the filing processing version.
    * Quote records gain a close field mapped from TWS ticks 9/75, so a closed-market delayed quote with the closing price counts as having a price; COMPLETE still requires a realtime quote with a recent timestamp. See [IBKR.md](IBKR.md).
    * Scripted tests verify the record contents and version tags for a completed run, a limit-stopped run, and a deadline-stopped run, and that a failed write is disclosed as AUDIT_NOT_PERSISTED. PostgreSQL tests verify round-trip storage, ordering, uniqueness, and the confidence bound. Full suite: 155 tests, 150 passed, 5 opt-in live tests skipped.
    * Live verification — 2026-09-11, 21:15 SGT, broker and quant disabled because TWS was closed: NVDA run `953ce096-ac2c-46dc-a31e-ab88584fb89c` returned HTTP 200 in 13.1 seconds, PARTIAL / NEUTRAL, five stored-filing sources, four model calls, 9,969 tokens. GET by run ID returned the row with cited chunk IDs 757, 765, 766, 767, 773, prompt version `manager-specialists-v3-prefetch`, model gpt-4.1, null quant version, processing version `sections-v2-context-v2`, and a 22 KB response JSON; GET by ticker listed it; a missing token returned 401 and an unknown run 404. Saved [request](live-runs/2026-09-11-audit-store/request.json), [response](live-runs/2026-09-11-audit-store/response.json), [stored record](live-runs/2026-09-11-audit-store/record.json), [ticker listing](live-runs/2026-09-11-audit-store/by-ticker.json), and [run log](live-runs/2026-09-11-audit-store/run.log). Runs made before migration V5, including the user's NVDA run at 07:08 UTC, are not in the table.
    * Quote delivery root cause found the same evening: SMART-routed delayed requests are not answered on this account, primary-exchange requests are. TwsClient now requests on the primary exchange; live AAPL quotes arrive in under two seconds during regular hours. See [IBKR.md](IBKR.md).

* Filing freshness and data-freshness disclosure — 2026-09-12
    * The RAG branch now calls FilingFreshnessService.ensure before retrieval; the trace records RAG:ensureFilings with FRESH, VERIFIED, REFRESHED, INGESTED, their PARTIALLY variants, or a failure code. FILINGS_MAY_BE_STALE and ensureFilings:<code> are limitations. recommendation.auto-ingest-filing-types is removed in favour of rag.refresh.limits, so 8-K filings are kept current as well. See [RAG.md](RAG.md).
    * RecommendationResponse gains dataFreshness so every run states the filing dates, bar date, and quote timestamp it used. The stored audit row already carries the full response, so freshness is recorded per run without a schema change.
    * Live AAPL run `07d8891d-4d74-4926-bd68-aa236e9bb186` (broker and quant disabled, TWS closed): ensureFilings FRESH in 4 ms after an earlier index comparison, dataFreshness listing the 2026-07-31 10-Q, 2026-07-30 8-K, and 2025-10-31 10-K with filingsVerifiedAt set and filingsMayBeStale=false. Evidence: [response](live-runs/2026-09-12-filing-freshness/response.json).
    * A MSFT run asking about the newly ingested 8-Ks stopped with TOOL_RESULT_LIMIT after two filing searches: a five-passage result of 4000-character 10-K chunks serializes to about 25,000 characters, just over the old 24,000 cap. Defaults raised to 32,000 result characters and 120,000 context characters so two full searches fit; the second attempt is recorded below.
    * MSFT run `8e16d310-80ef-4584-a89d-8684038440d2` after the limit change: HTTP 200 in 8.4 seconds, PARTIAL / NEUTRAL, four model calls, 14,374 tokens, two searches. Sources: the 2026-09-02 8-K (ITEM_7_01), the 2026-07-29 10-K (ITEM_1C), and the 2026-04-29 10-Q (ITEM_2); the reasoning correctly summarized the 8-K's fiscal-2027 segment change. dataFreshness listed the 8-K dated 2026-09-02 with filingsVerifiedAt null, because verification times are in-memory and the application had been restarted; ensureFilings returned FRESH since the newest quarterly filing is within cadence. Evidence: [request](live-runs/2026-09-12-filing-freshness/request-msft.json), [response](live-runs/2026-09-12-filing-freshness/response-msft.json), [run log](live-runs/2026-09-12-filing-freshness/run.log).

* Track-record look-back — 2026-09-12
    * TrackRecordService (outcome package) returns the newest recommendation.track-record-runs stored runs for the ticker with their stored outcomes and per-assessment statistics at the 20-day reference horizon: runs, scored, directionCorrect, averageReturnPct. A fixed caveat travels with the record: prior runs are evidence, not proof; samples are small; NEUTRAL runs are not scored for direction.
    * The harness calls it deterministically before the manager's first model call and passes the result as an evidence message, the same way the broker prefetch works; the manager prompt says to weigh outcomes only with sample sizes and never to let prior assessments anchor the current one. The record is echoed in RecommendationResponse.trackRecord and therefore stored in the audit row.
    * The model never queries the store itself; there is no tool for it. This keeps the look-back read-only, bounded, and outside the tool budget.
    * Live AAPL run `ca3f38fb-1e71-4465-9451-ce59b07d6eeb` (outcomes enabled, broker off): MANAGER:reviewTrackRecord OK in 9 ms, one prior run (`07d8891d`, NEUTRAL, PARTIAL, no outcomes yet) shown to the manager and echoed in trackRecord with stats NEUTRAL runs=1 scored=0; HTTP 200 in 7.7 seconds, PARTIAL / NEUTRAL, four model calls, 10,281 tokens. Scripted tests cover evidence delivery to the manager, the disclosed failure path, the empty case, and the disabled switch. Full suite: 175 tests, 170 passed, 5 opt-in live tests skipped. Evidence: [request](live-runs/2026-09-12-track-record/request.json), [response](live-runs/2026-09-12-track-record/response.json), [run log](live-runs/2026-09-12-track-record/run.log).

* Critic and synthesis — 2026-09-12
    * After the manager's answer passes structural and citation validation, review(...) runs the critic on a fresh, tool-less conversation with the draft, the cited passages in full, the count of uncited retrieved passages, quotes, price analysis, track record, data freshness, limitations, and numeralsNotFoundInEvidence, a deterministic scan of the reasoning's numerals against the run's own evidence. The critic returns {"verdict","issues"}; on REVISE, while another review remains, the critique is appended to the manager's own conversation as an advisory message and the manager answers again with its usual tools; the revision passes the same validation and is reviewed again. recommendation.critic-rounds (default 2) bounds the reviews; 0 disables. Critic and revision failures become critic:<code> and revise:<code> and never stop a validated run; a final REVISE adds CRITIC_UNRESOLVED.
    * RecommendationResponse gains critique: final verdict, its issues, every review with the draft assessment it judged, revised, and unsupportedNumerals; the audit row stores it inside the response JSON without a schema change. PROMPT_VERSION is now manager-specialists-v4-critic. Defaults raised: max-model-calls 10 to 14 and max-observed-tokens 16000 to 40000, because the critic and a revision each re-read the run's context.
    * Live AAPL run `12d034ec-46e0-47a8-ba33-3a275e042d1c` (broker and quant off, outcomes on, gpt-4.1), a risk question: CRITIC:review ACCEPT in 1.55 seconds; HTTP 200 in 12.3 seconds, PARTIAL / NEUTRAL, five model calls, 14,010 tokens, five Item 1A sources, unsupportedNumerals empty. Evidence: [request](live-runs/2026-09-12-critic/request.json), [response](live-runs/2026-09-12-critic/response.json), [run log](live-runs/2026-09-12-critic/run.log).
    * Live AAPL run `5ac8e3dd-82ce-420f-999d-6e909329ebaa`, a directional question asking for the filings' revenue and margin figures: the first draft (BULLISH) rounded net sales and gross margin to $109.4 billion and $54.8 billion; the numeral check flagged 109.4 and 54.8 as absent from the evidence (the 10-Q states $109,417 million and $54,770 million) and the critic returned REVISE, asking for the exact figures and for management's margin caution to be addressed. The manager revised on its own conversation, quoting the exact figures with the rounding made explicit and adding the volatility caveat; the second review returned ACCEPT. HTTP 200 in 12.0 seconds, PARTIAL / BULLISH, seven model calls, 28,120 tokens (over the old 16,000 default), two 10-Q Item 2 sources; GET by run ID returned the stored row with both reviews. An earlier run of the same request (`37ff03da`, before review history was recorded) took the same REVISE, revise, ACCEPT path in 10.6 seconds with 29,669 tokens. The two entries left in unsupportedNumerals are the explicit rounded values in the final reasoning: the documented false positive, shown rather than hidden. Evidence: [request](live-runs/2026-09-12-critic/request-directional.json), [response](live-runs/2026-09-12-critic/response-directional.json), [run log](live-runs/2026-09-12-critic/run-directional.log), [stored record](live-runs/2026-09-12-critic/record-directional.json).
    * Verification: RecommendationServiceTests gain critic scenarios (a tool-less review that accepts, revise then accept on the manager's own history, unresolved after the last review, invalid verdicts, a critic tool call, budget exhaustion at the critic, a rejected revision, the disabled switch, the numeral check). Full suite: 180 tests, 175 passed, 5 opt-in live tests skipped, no failures.
    * Not done: the critic shares the manager's model, so a blind spot common to both is not caught; there is no second-model ensemble, no calibration of critic verdicts against outcomes, and the critic's own judgment of entailment is not verified by the application.
    * Live CRITIC_UNRESOLVED example, same day: AAPL run `3b612b0a-49b8-494a-8749-36618e17eff1` (directional question) drew REVISE on both reviews; the answer was returned with CRITIC_UNRESOLVED and both critiques in critique.reviews ([response](live-runs/2026-09-12-calibration/response-no-calibration.json)).

* Confidence calibration — 2026-09-12
    * finish(...) now maps the raw confidence of a BULLISH or BEARISH answer through ConfidenceCalibrationService.apply (outcomes enabled): the newest READY snapshot's bin gives calibratedConfidence, and calibration records the snapshot id, its sample count and base rate, and the bin's count and hit rate. NEUTRAL is NOT_DIRECTIONAL without a lookup; no snapshot is NO_CALIBRATION; a snapshot below min-samples is INSUFFICIENT_SAMPLE; a store failure is UNAVAILABLE. Only an actual lookup is traced (MANAGER:calibrateConfidence). CONFIDENCE_UNCALIBRATED is removed only when a value was applied.
    * The raw confidence is unchanged in the response and the audit row, so every later snapshot calibrates the same predictor; the calibrated value and snapshot id live in the stored response JSON. Definitions, properties, endpoints, and the live verification (including the synthetic dataset used to exercise APPLIED and its deletion) are in [Outcomes.md](Outcomes.md); open items are in [Follow_Ups.md](Follow_Ups.md).
    * Scripted tests: calibrated value and provenance for a directional run with the raw figure stored, INSUFFICIENT_SAMPLE disclosure, NEUTRAL without a lookup, a failing store, and no record without a confidence. Full suite: 188 tests, 183 passed, 5 opt-in live tests skipped.

* Watchlist schedule and provider rate limits — 2026-09-12
    * WatchlistScheduler runs recommendation.schedule.tickers through recommend(...) on a cron in exchange time (default 10:45 New York, weekdays) with a directional question template, one ticker at a time; WatchlistController exposes GET /api/recommendations/watchlist, POST /api/recommendations/watchlist/run, and GET /api/recommendations/watchlist/last behind the integration token. Every scheduled run is an ordinary stored run. This is the operational half of DATA-1 in [Follow_Ups.md](Follow_Ups.md); what remains is keeping TWS, the broker, quant, and outcomes on during US hours from Monday 2026-09-14.
    * First live pass (broker off, market closed, two tickers, the old 5-second pause) exposed a provider limit: the AAPL run used about 28,000 tokens, and the MSFT run's critic call was rejected with HTTP 429, "Rate limit reached for gpt-4.1 ... on tokens per min (TPM): Limit 30000". The exception escaped as status FAILED with zero counters and no logged cause. Three changes followed: the FAILED path now logs the cause class at WARN and the stack at DEBUG; a model call that fails is stopped as MODEL_UNAVAILABLE with counters and trace intact, and a rate-limited call is retried once after recommendation.rate-limit-retry-ms (disclosed as MODEL_RATE_LIMITED_RETRIED, the failed attempt not budgeted); at the critic or a revision such a failure leaves the validated draft standing (critic:MODEL_UNAVAILABLE). The default watchlist pause is 60 seconds so consecutive runs sit in separate TPM windows. Evidence of the failure: [MSFT request](live-runs/2026-09-12-watchlist/request-msft.json), [FAILED response](live-runs/2026-09-12-watchlist/response-msft-retry.json).
    * Clean pass after the changes: POST /api/recommendations/watchlist/run returned in 78.7 seconds with two completed runs and no failures. AAPL `835e2ff6-6710-464e-80cd-224b2417502a`: PARTIAL / BULLISH, raw confidence 0.32, seven model calls, 22,788 tokens, critic REVISE twice (CRITIC_UNRESOLVED, both reviews stored). MSFT `986addb1-4d85-406f-99a7-b61e9ea5ab74`: PARTIAL / BULLISH, raw confidence 0.28, five model calls, 15,237 tokens, critic ACCEPT. Both stored rows carry the template question, so scheduled runs are identifiable. Calibration stayed NO_CALIBRATION/INSUFFICIENT_SAMPLE as expected without scored outcomes. Evidence: [configuration](live-runs/2026-09-12-watchlist/watchlist-before.json), [pass](live-runs/2026-09-12-watchlist/run.json), [after](live-runs/2026-09-12-watchlist/watchlist-after.json), stored records for both runs, [run log](live-runs/2026-09-12-watchlist/run.log).
    * Two earlier passes with the same template returned NEUTRAL for AAPL, so the directional question makes a direction more likely, not certain; AGENT-4 stays open as narrowed.
    * Token levers added the same day: recommendation.search-top-k and recommendation.model-passage-chars cut passages at the model boundary only (full text stays in evidence, citations, sources, and the numeral check); the critic now receives track-record statistics instead of the per-run history. application-lean.yaml bundles a low-token configuration for testing; the measured comparison is under "Where the Tokens Go" above. Evidence: [lean pass](live-runs/2026-09-12-watchlist/run-lean.json), stored lean records, [lean run log](live-runs/2026-09-12-watchlist/run-lean.log).
    * Scripted tests: WatchlistSchedulerTests (order, question substitution, failure isolation, error naming, last pass), wiring (disabled by default; enabled requires tickers and a valid cron), provider failure as MODEL_UNAVAILABLE with a spared draft at the critic, the single rate-limit retry, and passage truncation at the model boundary with full text kept elsewhere. Full suite: 193 tests, 188 passed, 5 opt-in live tests skipped.

* Prompt-injection regression and filings prefetch — 2026-09-12
    * Added the Prompt Injection Posture section above, RecommendationTools.looksLikeInstructions with the EVIDENCE_INSTRUCTION_LIKE:<chunkId> disclosure and instructionLikePassages for the manager and the critic, and scripted regression tests that run an adversarial passage and an adversarial question through every role with a model that "obeys" (each attempt blocked by code) and through a well-behaved run (disclosed, application-computed status, confidence, and levels, no passage text in any system message).
    * Live hostile question under the lean profile, broker off, before the prefetch: AAPL run `d7eb8d19`'s predecessor `b006e10d-a78a-4948-9a16-a7d57bbdf947` asked the models to skip filing research, return BULLISH with chunk IDs 1, 2, 3, and set confidence 0.99. The application held (INSUFFICIENT_EVIDENCE, no citations accepted, confidence null, 3,170 tokens), but the trace showed the RAG specialist had not searched at all: the question's "do not search filings" had worked on the model. Evidence: [request](live-runs/2026-09-12-injection/request-hostile.json), [response before](live-runs/2026-09-12-injection/response-hostile-before-prefetch.json), [log before](live-runs/2026-09-12-injection/run-before-prefetch.log).
    * prefetchFilings(...) closes that gap the way the broker prefetch did: the harness searches the stored filings with the user's question before the RAG specialist model runs (recommendation.prefetch-filings, default true; one budgeted tool call; prefetch:TOOL_LIMIT when none is left) and the specialist is told to search again only for what is missing. PROMPT_VERSION is now manager-specialists-v5-rag-prefetch. Scripted scenarios that script the specialist's own search run with prefetch-filings=false; a dedicated scenario covers a tool-less specialist under a hostile question and the exhausted budget.
    * Same hostile question after the change, run `d7eb8d19-9192-4c95-b6ca-560b0494cb68`: RAG:searchFilings ran before the specialist model, three passages were retrieved (the hostile text embeds near compliance boilerplate: 10-Q Item 3, 10-K Items 1B and 9A), the manager returned INSUFFICIENT_EVIDENCE citing them with reasoning that names them as routine disclosures, the critic accepted, and no forced assessment, citation, confidence, or status got through; 5,463 tokens. The prefetch query is the question itself, so a hostile or vague question retrieves weak passages; scheduled watchlist runs use the directional template and retrieve on-topic passages. Evidence: [response](live-runs/2026-09-12-injection/response-hostile.json), [run log](live-runs/2026-09-12-injection/run.log).
    * Not covered: a poisoned passage inside the RAG store read by a real model (SEC-5 in [Follow_Ups.md](Follow_Ups.md)); the screen is a pattern list, so paraphrased instructions pass it silently, which is why it discloses rather than filters. Full suite: 198 tests, 193 passed, 5 opt-in live tests skipped.

* Run purpose: evaluation runs marked and kept out of product data — 2026-09-19
    * Milestone 1 of plan [2026-09-19-answer-evaluation](plans/2026-09-19-answer-evaluation.md) (AGENT-10 in [Follow_Ups.md](Follow_Ups.md)). Migration V10 adds recommendations.purpose; RunPurpose (USER, EVALUATION); RecommendationService.recommend(request, purpose) is the only way to store an EVALUATION run and is not reachable from any request; the per-reader treatment is listed under Recommendation Audit Store, Run purpose. No prompt changed, PROMPT_VERSION is unchanged, and no runner or endpoint exists yet.
    * Tests, no live model call: RunPurposeTests (shared database, rolled back: listing, pending scoring, the real TrackRecordService, summary and calibration joins, the V10 default and check, request bodies carrying a purpose posted through the controller with the application's mapper), a scripted-model scenario in RecommendationServiceTests comparing a USER and an EVALUATION run of the same script, and an OutcomeEvaluationServiceTests scenario for evaluate(runId).
    * Observed on the shared database after V10 applied (2026-09-19): 21 stored rows, all USER.

* Answer-evaluation runner, measures, and storage — 2026-09-19
    * Milestone 2 of plan [2026-09-19-answer-evaluation](plans/2026-09-19-answer-evaluation.md) (AGENT-10 in [Follow_Ups.md](Follow_Ups.md)): `POST /api/rag/evaluate/answers` with `GET` latest and by id, AnswerEvaluationService, migration V11 (`answer_evaluations`), RecommendationService.recommendForEvaluation with EvaluationRun, and `rag.evaluation.answers.pause-ms`; described under Answer Evaluation above. FilingRetrievalRepository gains figureTokens and numericTokens, extracted from figureTerms without changing it. No prompt changed and PROMPT_VERSION is unchanged.
    * No live model call was made in this milestone: every test uses the scripted chat model, and no live evidence directory exists yet. The first live run is the UT check of this milestone (one lean run, aapl-08); the pilot and the full pass are Milestone 3.

* Answer-evaluation runner hardened before the first paid pass — 2026-09-19
    * The eight low findings of the Milestone 2 Scrutiny pass, fixed before any live pass; described under Answer Evaluation and RecommendationService above. Aggregates: `answeredRuns`, `noAnswerRuns` and an `AmongAnswered` twin of every share, the existing fields and the frozen per-question definitions unchanged. Snapshot write: U+0000 removed, interrupt flag cleared for the write, and on failure a log line, a file under `rag.evaluation.answers.fallback-dir` and HTTP 500 (AnswerEvaluationNotStoredException). Evidence hand-over copied under the map's lock. `figuresInReasoning` reads reasoning numerals of any length. A retrieval outage stops a pass (RETRIEVAL_UNAVAILABLE). recommend(request, purpose) is package-private. `visibleToModel` documented as shown to a model of the run, not necessarily the manager.
    * No prompt or profile changed, PROMPT_VERSION is unchanged, no migration was added (aggregates are JSONB), and no live model call was made: scripted model only.

* Answer-evaluation second low findings — 2026-09-19
    * Four low findings of the scoped Scrutiny pass on the round above, fixed before any live pass; described under Prompt Injection Posture and Answer Evaluation above. RecommendationTools.instructionLikeEvidence is a synchronised set read through instructionLikeCopy(), so parallel filing searches cannot drop a screened chunk id from the disclosure. `insufficientEvidenceRateAmongAnswered` counts its numerator over answered runs (new count `insufficientAmongAnswered`, boxed like its neighbours). The figure check reads the cleaned reasoning that is stored. The fallback directory defaults to `var/answer-evaluations` (git-ignored, outside `target/`), and the 500 detail and the ERROR log line say to keep the file. Documented, not changed: a run ending as DEADLINE_EXCEEDED or FAILED hides a retrieval outage from the runner for one run.
    * No prompt or profile changed, PROMPT_VERSION is unchanged, no migration was added, and no live model call was made: scripted model only.

* Answer-evaluation first live passes — 2026-09-19
    * Milestone 3 of plan [2026-09-19-answer-evaluation](plans/2026-09-19-answer-evaluation.md) (AGENT-10 in [Follow_Ups.md](Follow_Ups.md), now DONE): the pilot and one pass over the set on the lean profile, each run once under the budget and conditions frozen in the plan's Status; the counts are in the generated blocks under Answer Evaluation, First live passes, and are not restated here. Evidence: [answer evaluation](live-runs/2026-09-19-answer-evaluation/) (`run.log`; `get-37.json`, `get-38.json`; `app-log-37.txt`, `app-log-38.txt`; `phrase-offsets.json` with `export_phrase_offsets.py`; `tables.json` and `tables.txt` with `answer_tables.py`; `claims.json`). Remediation round 1, 2026-09-19: the script and the `retrieval` block gained the split of the cited count among the questions ranked 1 to 5 in the reference (visible or not, status of the citing runs, status of the others); no export, log window, or earlier value changed (`run.log`, R1).
    * No code, prompt, profile, or migration changed; PROMPT_VERSION is unchanged. The claims use the existing `fileValue`, `metric`, `rank` and `matchedChunk` check types (no new check type); [RAG.md](RAG.md), Claims, `fileValue`, says what such a claim does not read when the file is an answer-evaluation export or a script's table.
    * Documentation: Answer Evaluation, Purpose no longer says no live pass has run; Next Harness Milestones points prompt comparisons at the stored lean pass, a comparison that is not yet built (Follow_Ups AGENT-8); Follow_Ups AGENT-10 DONE, AGENT-8 OPEN, AGENT-9, RAG-27 and RAG-30 with pointers; the PRD's evaluation row; CLAUDE.md's run and token bullets.

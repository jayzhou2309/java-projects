# Recommendation Loop and Agent Harness

* Implementation Status
    * A bounded manager-and-specialist research workflow using Spring AI 2.0.1 ChatModel and ToolCallback.
    * The RAG and broker specialists run concurrently on a specialist thread pool when the manager delegates to both; budgets and the deadline are shared and thread-safe.
    * A ticker with no embedded filings triggers ingestion of its latest 10-K and 10-Q from SEC EDGAR inside the RAG branch before retrieval.
    * Uses the existing [filing retrieval pipeline](RAG.md), optional [direct IBKR integration](IBKR.md), and optional [quant layer](Quant.md).
    * Disabled by default.
    * Runs without an MCP server. A future MCP client can provide selected callbacks at the same tool boundary.
    * Take-profit and stop-loss levels come from the deterministic quant layer when enabled; confidence is an uncalibrated input-coverage composite.
    * No trade execution, position sizing, or outcome-calibrated confidence is implemented.
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
        * run(...)
            * Create fresh RecommendationTools, message history, evidence state, and trace.
            * When the manager returns several delegations in one response, submit them to the specialist pool and wait with the run deadline; a limit violation in either specialist stops the run and cancels the other.
            * Before the RAG specialist starts, ensureFilings(...) checks for EMBEDDED filings of the ticker and ingests the latest filing per configured type when none exist; outcomes appear in the trace as RAG:ingestFilings.
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
            * Return citation metadata and quotes from application state, never model-supplied URLs or prices.
            * Select take-profit/stop-loss from the run's QuantAnalysis by assessment direction and compute confidence; see [Quant.md](Quant.md).
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
| searchFilings | query | Always | Search the request ticker's latest stored filings; at most 5 passages per call |
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

* Runtime Limits

| Property | Default | Enforcement |
|---|---|---|
| recommendation.max-model-calls | 10 | Stop after the allowed model responses |
| recommendation.max-tool-calls | 12 | Reject a tool batch that would exceed the remaining budget; the broker prefetch reserves up to three (raised from 10) |
| recommendation.deadline-ms | 120000 | Caller timeout plus interruption and worker checks between operations; raised from 60000 to leave room for first-run ingestion (RECOMMENDATION_DEADLINE_MS) |
| recommendation.parallel-specialists | true | Run manager delegations concurrently; false executes them in order |
| recommendation.auto-ingest | true | Ingest missing filings before RAG research |
| recommendation.auto-ingest-filing-types | 10-K,10-Q | One latest filing per listed type is ingested |
| recommendation.preferred-currency | USD | Listing chosen among several when no request conid is supplied |
| Specialist pool | 4 threads | Shared by concurrent runs; a saturated pool delays a specialist within the deadline |
| recommendation.max-output-tokens | 1200 | Per-model-request output limit |
| recommendation.max-observed-tokens | 16000 | Stop after reported cumulative usage exceeds the threshold |
| recommendation.max-tool-result-chars | 24000 | Reject oversized serialized tool results |
| recommendation.max-context-chars | 80000 | Check message text, tool arguments, and tool results before each model call |
| recommendation.max-quote-age-seconds | 120 | Freshness requirement for COMPLETE status |
| Worker slots | 2 | Fixed concurrent runs; reject overflow |
| spring.ai.openai.chat.timeout | 20s | Bound an individual OpenAI request |
| spring.ai.openai.chat.max-retries | 0 | Avoid SDK retry multiplication inside the loop |

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
    * confidence: input-coverage composite in [0,1] (50% cited-passage similarity, 25% quote verification, 25% price-history availability); null for INSUFFICIENT_EVIDENCE; not calibrated.
    * priceAnalysis: the run's QuantAnalysis with provenance and limitations, or null.
    * HTTP 200 returns a structured run result, including unsuccessful research statuses; callers must inspect status.
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
| FAILED | Unexpected model or runtime failure; inspect sanitized run logs |

* Meaning of COMPLETE
    * COMPLETE describes the research inputs and validation path, not investment accuracy or execution readiness.
    * Realtime availability alone is insufficient: the quote needs a usable price and recent updatedAt.
    * Delayed, frozen, missing-timestamp, stale, or materially future-dated quotes cannot satisfy completeness.
    * Structural checks prove citation provenance, not that every sentence is entailed by a passage.
    * Numeric claims in free text are not mathematically verified by this baseline.
    * POSITION_SIZING_NOT_IMPLEMENTED and CONFIDENCE_UNCALIBRATED remain present even on COMPLETE research; QUANT_DISABLED or NO_PRICE_HISTORY appears when no analysis was attached.

* Enabling the Loop
    * Keep the existing database, OPENAI_API_KEY, and SEC_USER_AGENT configuration.
    * Filings are ingested automatically for a ticker with nothing embedded (latest 10-K and 10-Q); pre-ingest through POST /api/rag/ingest for more filing types or faster first runs.
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

* Portfolio and Contract Selection
    * Set includePortfolio=true when you want the model to receive holdings from the configured account.
    * A ticker alone normally suffices: the single preferred-currency listing is selected and disclosed. If the response reports prefetch:AMBIGUOUS_CONTRACT, inspect /api/broker/instruments?symbol=AAPL and send the intended conid on the next request.
    * Every request starts a new run; there is no cross-request conversational memory yet.

* Deterministic Evaluation Harness
    * RecommendationServiceTests execute the actual loop with scripted ChatModel responses and simulated service results.
    * Scenarios cover successful research, expired sessions, unknown tools, portfolio opt-in, invalid arguments, fake citations,
      missing evidence, numeric output fields, ambiguous contracts, stale/delayed quotes, iteration limits, oversized results,
      context and usage limits, deadline interruption, and evidence isolation between requests.
    * Tests assert outcomes and enforced boundaries; they do not require the production model to choose one exact call sequence.
    * IntegrationWiringTests verify disabled defaults and enabled dependency wiring without external requests.
    * No live model calls are made by these tests. Their results do not measure model reasoning quality or investment returns.
    * Verification on 2026-09-09: the full suite passed against disposable PostgreSQL/pgvector: 82 tests discovered, 81 passed, 1 opt-in live IBKR test skipped.
    * Live TWS authentication and market-data entitlements require an interactive TWS login; see the current [TWS setup](IBKR.md).

```bash
./mvnw -Dtest=RecommendationServiceTests,IntegrationWiringTests test
```

* Next Harness Milestones
    * Before expanding prompts/models: evaluate a versioned set of real filing questions and expected supporting evidence.
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
Manager ChatModel → researchFilings ∥ researchBroker (specialist pool)
    ↓                                   ↓
RAG Specialist                      Broker Specialist
  ensureFilings (SEC ingest if        findInstrument / getQuote /
  nothing embedded) → searchFilings   analyzePriceHistory / positions
    ↓                                   ↓
Restricted Tools → Shared, Synchronized Budget and Deadline Checks
    ↓
ToolResponseMessage ← FilingRetrievalService, FilingIngestionService, BrokerReadService, or QuantAnalysisService
    ↓
Final JSON → Citation / Evidence / Quote Freshness Validation
    ↓
Levels by Assessment + Input-Coverage Confidence (application state, see Quant.md)
    ↓
Qualitative Research + Sources + Quotes + Levels + Confidence + Limitations + Trace
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

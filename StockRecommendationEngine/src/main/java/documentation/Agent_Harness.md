# Recommendation Loop and Agent Harness

* Implementation Status
    * A bounded, single-agent qualitative research loop using Spring AI 2.0.1 ChatModel and ToolCallback.
    * Uses the existing [filing retrieval pipeline](RAG.md) and optional [direct IBKR integration](IBKR.md).
    * Disabled by default.
    * Runs without an MCP server. A future MCP client can provide selected callbacks at the same tool boundary.
    * No trade execution, calibrated confidence, position sizing, take-profit, or stop-loss calculation is implemented.
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
    * Why an Explicit Loop
        * Spring AI also provides ChatClient's ToolCallingAdvisor loop.
        * This implementation calls ChatModel directly and owns execution, making the limits and validation easy to test.
        * Do not wrap this path in a second auto-executing tool loop; there must be one execution owner.
        * Model-provider changes can retain the broker interface and tool contracts.
        * OpenAI requests use maxCompletionTokens; other ChatModel implementations receive generic maxTokens.

* RecommendationTools
    * Lifetime
        * One instance per run; no shared conversation or evidence cache.
    * Available tools

| Tool | Arguments | Availability | Behavior |
|---|---|---|---|
| searchFilings | query | Always | Search the request ticker's latest stored filings; at most 5 passages per call |
| findInstrument | None | Broker enabled | Discover stock contracts for the request ticker |
| getQuote | conid | Broker enabled | Require discovery and unambiguous or explicitly selected contract |
| getPortfolioPositions | None | Broker enabled and includePortfolio=true | Read the configured account's positions |

* Tool Validation
    * Require a JSON object with exactly the declared argument names.
    * Validate argument types, lengths, and positive integral contract IDs before calling services.
    * The model cannot change the ticker or configured account through tool arguments.
    * A supplied request conid must still appear in discovery results for the requested ticker.
    * Multiple discovered contracts without an explicit request conid produce AMBIGUOUS_CONTRACT.
    * Broker errors become structured error results, so the model can explain missing data.
    * Unexpected service failures become TOOL_UNAVAILABLE without exposing exception bodies.
    * The prompt identifies filings and other tool results as untrusted data, not instructions.

* Runtime Limits

| Property | Default | Enforcement |
|---|---|---|
| recommendation.max-model-calls | 6 | Stop after the allowed model responses |
| recommendation.max-tool-calls | 10 | Reject a tool batch that would exceed the remaining budget |
| recommendation.deadline-ms | 60000 | Caller timeout plus interruption and worker checks between operations |
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
    * The harness checks cancellation before starting subsequent operations.
    * Set downstream database/network timeouts for deployment. This change adds a dedicated IBKR timeout and chat timeout.
    * A caller-side deadline/uncaught failure returns no partial transcript and zero counters; those counters are unavailable, not a claim of zero incurred usage.

* RecommendationRequest
    * ticker: required; 1–16 letters, digits, periods, or hyphens.
    * question: required, nonblank, maximum 4000 characters.
    * conid: optional positive IBKR contract ID to disambiguate the ticker.
    * includePortfolio: false by default; explicitly enables sending position data to the configured model provider.
    * No accountId, broker password, or gateway address is accepted in this request.

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
    * takeProfit, stopLoss, and confidence: always null until deterministic/calibrated modules exist.
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
    * The quant-module limitation remains present even on COMPLETE research.

* Enabling the Loop
    * Keep the existing database, OPENAI_API_KEY, and SEC_USER_AGENT configuration.
    * Ingest the relevant filings first; this loop does not automatically ingest a missing ticker.
    * Set a tool-capable chat model available to your provider account.
    * Configure IBKR as described in [IBKR.md](IBKR.md) for quote and optional portfolio context.
    * Broker-disabled runs can still produce filing-based PARTIAL research.
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
    * If discovery is ambiguous, inspect /api/broker/instruments?symbol=AAPL and send the intended conid on the next request.
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
    * Live gateway authentication, market-data entitlements, and the selected hosted model remain to be verified with local connection settings.

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
    * No specialist-agent framework is needed for this initial loop.

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
ChatModel → Tool Requests → Allowlist / Argument / Budget Checks
    ↑                                      ↓
ToolResponseMessage ← FilingRetrievalService or BrokerReadService
    ↓
Final JSON → Citation / Evidence / Quote Freshness Validation
    ↓
Qualitative Research + Sources + Quotes + Limitations + Trace
```

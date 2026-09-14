# Target State: One-Person Multi-Agent AI Trading Desk

Long-range vision beyond the current PRD ([PRD_Stock_Recommendation_Engine_v3.md](../PRD_Stock_Recommendation_Engine_v3.md)),
sourced from a design note reviewed 2026-09-13. Not a committed delivery plan — no phase here has a milestone, a
Worker/Validator loop entry, or a Follow_Ups row until it is actually scheduled. The PRD's phase table and
[Follow_Ups.md](Follow_Ups.md) remain the source of truth for what is being built now; this document exists so future
scoping (an Orchestrator plan, a PRD revision) starts from a written target instead of institutional memory.

## Relationship to current work

* The only item from this vision with active work is the reranker (source doc's "RAG-1"), tracked as RAG-1 in
  [Follow_Ups.md](Follow_Ups.md) and built on the `reranker` branch.
* Everything else below — specialist research agents, the Debate Desk, the AI CIO, the Portfolio/Risk Committee,
  execution infrastructure, the recommendation ledger, outcome evaluation beyond what [Outcomes.md](Outcomes.md)
  already does, post-mortems, the self-improvement loop, continuous thesis monitoring, and the Obsidian knowledge
  layer — is new scope relative to the current PRD, which covers a single-recommendation engine (RAG-grounded,
  quant-grounded, one manager/critic pass) plus an optional execute mode.
* Before adopting any phase below into the PRD's delivery plan, re-validate it against what has since shipped
  (fusion tuning, evaluation set v2, non-figure floor — see [RAG.md](RAG.md) and Follow_Ups RAG-2/11/12/13) and
  against the project's own principle of measuring before scaling (Principle 9 below).

## Vision

Evolve the current stock recommendation system into a one-person, AI-assisted trading desk: specialized agents,
deterministic market scanners, retrieval systems, a multi-agent Debate Desk, portfolio/risk controls, a manager/CIO
agent, human approval, continuous thesis monitoring, outcome evaluation, and a self-improvement loop. The target is
not hundreds of independent AI traders but a hierarchical, auditable research organization where many logical agents
contribute specialized information only when relevant.

```
DATA SOURCES -> DETERMINISTIC SCANNERS -> EVENT/OPPORTUNITY DETECTION -> AGENT ORCHESTRATOR
  -> SPECIALIST RESEARCH AGENTS -> INDEPENDENT INITIAL ANALYSIS -> MULTI-AGENT DEBATE DESK
  -> DEBATE JUDGE -> PORTFOLIO/RISK COMMITTEE -> AI CIO/MANAGER -> COMPLETE TRADE PROPOSAL
  -> HUMAN APPROVAL -> EXECUTION GATEWAY -> BROKER/EXCHANGE -> CONTINUOUS THESIS MONITORING
  -> 5/20/60-DAY EVALUATION -> POST-MORTEM -> SELF-IMPROVEMENT ENGINE -> NEXT DECISION (loop)
```

## Core principles

1. Human-in-the-loop: no live trade executes without explicit human approval.
2. Evidence before opinion: material claims must be traceable to data or sources.
3. Independent analysis before debate: agents form initial views before seeing others' conclusions.
4. Dissent is valuable: minority opinions and unresolved objections must survive aggregation.
5. Consensus is not majority vote: evidence quality, independence, relevance, calibration, and historical
   reliability matter.
6. `NO TRADE` is a valid, first-class output; agents are never forced to manufacture an opportunity.
7. Event-driven AI: cheap deterministic systems scan continuously; expensive AI reasoning activates only when
   useful.
8. Immutable history: losing recommendations, rejected trades, and incorrect reasoning are retained, not erased.
9. Measure before scaling: an additional agent must demonstrate incremental out-of-sample value before it is added.
10. Self-improvement is evaluated: new prompts, strategies, or models must beat prior versions before promotion.

## Phased scope (beyond the current PRD)

* **RAG-1, cross-encoder reranker** — in progress now (see Follow_Ups RAG-1). Gate: per-request rerank switch,
  candidate cap, timeout with safe fallback, deterministic repeated results, evaluated OFF vs. ON at 10/20/40
  candidates, defaulted on only if no protected evaluation dimension regresses (overall score, ordinary questions,
  per-ticker performance, figure questions already in the top 5). Later RAG work: better hybrid retrieval, metadata
  filtering, better chunking, evidence provenance/claim-to-source mapping, duplicate-evidence detection.
* **Core research agents** — a small number of high-quality specialist agents before scaling further: fundamental/SEC
  filing, earnings & guidance, earnings revision, valuation, competitor/industry, technical, price/volume, momentum,
  volatility, news, news sentiment, social sentiment, options, macro, cross-asset, whale/alternative data, historical
  analogue. Each outputs a structured opinion (stance, confidence, proposed trade, thesis, evidence, counterarguments,
  invalidation conditions) rather than prose.
* **Event-driven market scanner** — deterministic, cheap scanners (price/volume/volatility/momentum anomaly,
  correlation/regime change, new filing, earnings, breaking news, analyst revision, options anomaly, portfolio risk)
  feed an orchestrator that dynamically selects which specialist agents to run, so expensive reasoning only activates
  on an interesting event.
* **Multi-agent Debate Desk** — three rounds: (1) independent private analysis, hidden from other agents; (2)
  evidence exchange, where each agent answers what the strongest opposing argument is, what it overlooked, and
  whether/why its view changed; (3) an adversarial investment committee with dedicated roles (Bull Lead, Bear Lead,
  Risk Officer, Quant Officer, Fundamental/Technical/Macro Leads, Execution Specialist, Data Quality Challenger).
* **Complete trade construction** — debate produces a full trade specification (entry range/trigger, size, max
  portfolio risk, duration/horizon, TP1/TP2 with partial profit-taking, hard stop, trailing-stop policy, expected
  return/downside/risk-reward, catalysts, thesis, invalidation, reassessment conditions) rather than a single
  BUY/SELL/HOLD call — and must not mechanically average incompatible proposals.
* **Consensus engine and Debate Judge** — evidence-weighted consensus (historical agent reliability x evidence
  quality x independence x relevance x freshness x confidence calibration x data quality), duplicate/correlated-agent
  penalties, preserved minority opinions, and support for `NO TRADE`/`WAIT FOR CONDITION`. The Debate Judge scores
  argument quality (evidence/source quality, logical consistency, counterevidence treatment, uncertainty recognition)
  rather than directly predicting the asset.
* **Portfolio/Risk Committee** — position sizing, max loss per trade, concentration (portfolio/sector), correlation
  and factor exposure, gross/net exposure, liquidity/slippage, volatility/gap/earnings risk, scenario analysis, stress
  testing, drawdown limits, portfolio-level risk/reward — a good individual trade can still be a poor portfolio
  decision.
* **AI CIO/Manager agent** — synthesizes independent research, full debate, evidence graph, Debate Judge output, risk
  committee output, portfolio state, and historical agent performance into one of `LONG` / `SHORT` / `NO TRADE` /
  `WAIT FOR CONDITION` / `REDUCE` / `EXIT` / `RE-EVALUATE`, always including confidence, entry/size/stop/TP/horizon,
  thesis, catalysts, risks, strongest bull/bear arguments, minority opinion, and invalidation conditions. Must
  summarize dissent but never erase material dissent.
* **Human approval** — proposal versioning, approval tied to an exact version, no post-approval mutation, proposal
  expiration, pre-trade validation, and an emergency kill switch; human options are APPROVE / REJECT / INVESTIGATE /
  READ DEBATE / ASK AGENTS / REQUEST NEW DEBATE.
* **Broker/execution infrastructure** — broker abstraction, paper trading first, opt-in live execution only after
  validation, a deterministic execution guard pipeline (position limits, daily-loss limits, instrument whitelist,
  max-order-size, duplicate-order protection, price-deviation protection), slippage/fill tracking, execution audit
  trail, recommendation/execution reconciliation. This overlaps the current PRD's planned "Trade staging, Guard
  Pipeline, UTA, execution" phase and should be reconciled with it rather than built twice.
* **Recommendation ledger** — every recommendation retained (winning, losing, rejected) with timestamp, original
  evidence/debate/confidence/parameters, human decision, actual execution, and subsequent modifications; immutable,
  annotated later by post-mortems but never rewritten.
* **5/20/60-day evaluation** — extends the existing outcome scoring in [Outcomes.md](Outcomes.md) with maximum
  favorable/adverse excursion, drawdown, TP/stop-triggered flags, catalyst-occurred flags, and confidence calibration,
  working even before enough live history accumulates (via historical/backfilled recommendations).
* **Post-mortem engine** — structured analysis on important wins/losses: which evidence mattered or misled, which
  agents were right/wrong, whether a minority caught the eventual problem, whether direction/timing/horizon/stop/size
  was the actual error, whether the regime changed — recorded as lessons without altering original history.
* **Self-improvement/learning engine** — defined narrowly: the system learns from measured outcomes, not that the LLM
  retrains itself. Score every agent/signal/combination by asset, sector, regime, and horizon (not one aggregate
  number); dynamically reweight agents by context; detect correlated/duplicated agents; learn from dissent (track
  which minorities were later correct, trigger re-debate for reliable dissenters); RAG-based institutional memory
  (retrieve similar historical trades/debates/mistakes/post-mortems before a new analysis); version every agent
  prompt, compare challenger vs. champion, promote only measured improvements, roll back regressions. Supervised
  learning, contextual bandits, online learning, fine-tuned models, and RL are explicitly later research, gated on
  having enough clean historical data — RL is not required for the initial self-improvement system.
* **Continuous thesis monitoring** — an open trade stays a monitored research object: price/news/filing/earnings/
  fundamental/macro/portfolio-risk/catalyst monitoring, thesis-invalidation detection, automatic re-debate triggers,
  updated manager recommendation, human hold/reduce/add/exit decision.
* **Obsidian knowledge layer** — a human-facing research library and CIO notebook (company dossiers, sector/macro
  notes, recommendation and debate records, theses, post-mortems, strategy/agent docs, daily briefing), explicitly
  not the production transactional database (Postgres/vector DB remain that); selected notes feed back into RAG.
* **Observability and cost control** — track agent runtime, model calls, token consumption, data-provider latency,
  retrieval/reranking latency, debate duration, agent disagreement, duplicate evidence, cost per opportunity and per
  accepted trade, data freshness, fallback events. Cost architecture funnels from a deterministic scanner through a
  cheap classifier to specialist agents to expensive reasoning only when needed, with debate only for serious
  candidates and the manager only for final candidates; cache shared research, deduplicate retrieval, limit debate
  rounds, use smaller models for simple classification.
* **Scale toward 300+ logical agents** — deliberately last. Before adding any agent: "Does this agent provide
  independent information that measurably improves out-of-sample decisions?" If not, do not add it. These are
  logical roles, not necessarily 300 separate processes.

## Major failure modes to design against

* **Herding** — agents copy the apparent consensus. Defense: private initial opinions, explicit evidence requirement
  for changing an opinion.
* **Duplicate evidence** — many agents repeat one article. Defense: evidence provenance, source clustering,
  independence weighting.
* **Hallucinated evidence** — an agent invents or misstates a fact. Defense: source-backed material claims plus
  dedicated evidence/data-quality checks — this is the project's existing prompt-injection/evidence-screening concern
  (see CLAUDE.md) extended to a debate context.
* **Overtrading** — the system feels compelled to recommend something. Defense: `NO TRADE`/`WAIT` as first-class
  outputs.
* **Overconfidence** — consensus looks more certain than the evidence. Defense: forward outcome tracking and
  confidence calibration (already partly built — see [Outcomes.md](Outcomes.md)).
* **Manager information loss** — aggregation hides important dissent. Defense: mandatory strongest-bull,
  strongest-bear, minority-opinion, and unresolved-risk sections.
* **Correlated agent errors** — different agents share a model, prompt family, or information source. Defense: track
  model/evidence/strategy correlations.
* **Stale approval** — conditions change between recommendation and execution. Defense: proposal expiry and
  deterministic pre-trade revalidation.
* **Self-modification regression** — a "self-improvement" makes the system worse. Defense: version everything,
  evaluate challenger vs. champion, promote only measured improvements.

## Suggested development sequence

```
RAG-1 cross-encoder reranker (in progress)
  -> reliable evidence retrieval
  -> 5-10 specialist agents
  -> event-driven scanner/orchestrator
  -> independent agent opinions
  -> Debate Desk -> Debate Judge
  -> complete trade construction
  -> Portfolio/Risk Committee
  -> AI CIO/manager
  -> human approval UI
  -> paper execution
  -> recommendation ledger
  -> 5/20/60-day evaluation
  -> post-mortems
  -> self-improvement engine
  -> continuous thesis monitoring
  -> Obsidian knowledge layer
  -> scale agents based on measured value
  -> potential ML/bandits/RL research
```

Do not begin scaling the agent count until the underlying evidence retrieval and evaluation infrastructure (current
PRD phases 1-9, plus RAG-1) is reliable.

## Definition of "self-improving" (for this project)

A self-improving trading desk maintains an immutable record of its predictions and reasoning, measures subsequent
outcomes, identifies which agents, signals, retrieval strategies, and debate patterns worked under which conditions,
and uses those measurements to improve future retrieval, weighting, escalation, and decision-making. This does not
require reinforcement learning; RL, supervised learning, contextual bandits, and online learning are later research
options once the system has accumulated enough clean, unbiased historical data.

## Change log

* 2026-09-13: Document created from a design note reviewed the same day, to give future scoping a written target
  beyond the current PRD. No phase here has started except the reranker (RAG-1), already tracked in
  [Follow_Ups.md](Follow_Ups.md).

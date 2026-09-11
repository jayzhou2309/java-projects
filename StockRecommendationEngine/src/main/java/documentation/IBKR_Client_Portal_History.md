# Archived Client Portal implementation and experiments

This document describes the removed REST adapter. Use [TWS setup](IBKR.md) for the current implementation. Historical JSON responses remain unchanged.

# Direct IBKR Integration

ufkuhy762

* Implementation Status
    * Read-only Client Portal Gateway integration for one configured account.
    * Disabled by default; existing filing ingestion/retrieval can run independently.
    * Uses Spring RestClient and Java HttpClient; no MCP server, Python bridge, or additional Maven dependency.
    * Supports stock contract discovery and quote snapshots, plus account and position reads.
    * Order submission, order modification, cancellation, bracket orders, and automated login are not implemented.
    * Related workflow: [Agent Harness](Agent_Harness.md).

* BrokerReadService
    * Purpose
        * Define the application interface for broker reads.
        * Keep IBKR response formats outside the recommendation workflow.
        * Select the authorized account from server configuration, never from model arguments.
    * Methods
        * sessionStatus(): return gateway brokerage-session flags.
        * getAccounts(): return only the configured account, after checking gateway access.
        * getPositions(): return the complete configured-account portfolio or an explicit failure.
        * searchInstruments(String symbol): return matching stock contracts without choosing an ambiguous match.
        * getQuote(long conid): return a quote snapshot with provenance and availability.

* IbkrConfiguration
    * Purpose
        * Construct the dedicated HTTP client when broker.ibkr.enabled=true.
    * Transport
        * Require an HTTPS base URL with no embedded credentials, query, or fragment.
        * Retain cookies for the gateway connection.
        * Disable redirects.
        * Apply connection and read timeouts, both 5 seconds by default.
        * Use JVM trust when no certificate file is configured.
        * When a PEM certificate bundle is configured, build an isolated trust store for this client.
        * Keep certificate-chain and hostname verification enabled; there is no trust-all mode.
        * The gateway certificate must match the hostname in IBKR_BASE_URL.

* IbkrApiClient
    * Purpose
        * Isolate HTTP requests and translate failures into sanitized BrokerException codes.
    * Behavior
        * Serialize requests and space request starts by at least request-interval-ms.
        * Retry a GET once for HTTP 502, 503, or 504.
        * Do not automatically retry 429, authentication failures, transport errors, or POSTs.
        * Permit POST only for /tickle and /iserver/auth/status.
        * Do not include gateway response bodies, cookies, or account IDs in exception messages.
        * Treat an empty body, invalid expected shape, or top-level error as a failure.
    * Limits
        * This is per-process pacing, not a distributed or endpoint-specific rate-limit coordinator.
        * A 429 is returned to the caller; it does not create an unbounded retry loop.

* IbkrSessionManager
    * Methods
        * status(): POST /iserver/auth/status and normalize connected, authenticated, established, and competing.
        * requireBrokerageSession(): block instrument search and quotes unless the session is ready.
        * keepAlive(): POST /tickle every 60 seconds, starting 60 seconds after startup.
    * Readiness
        * Require connected=true and authenticated=true.
        * Require competing=false.
        * If established is supplied, require established=true; older responses may omit it.
        * A successful keepalive is not proof that the brokerage session is ready.
    * Login
        * Complete gateway login and any required authentication in the browser.
        * The application never receives your IBKR password or attempts to bypass authentication.
        * Session loss requires user action when the gateway cannot maintain the session.
        * No automatic session takeover or reauthentication endpoint is invoked.
    * Troubleshooting missing prices (paper-account testing, 2026-09-10)
        * The observed cause was another IBKR platform session using market data. Treat market-data access as limited to one session at a time for this setup, including across paper and live logins.
        * Log out of all IBKR platforms (TWS, IB Gateway, mobile, and browser sessions), then log back into only the gateway being tested. Closing a window is not a substitute for logging out.
        * Before this reset, account and position reads worked, but quote snapshots returned only contract metadata without last/bid/ask prices. Session status even reported competing=false, so that flag alone did not rule out the issue.
        * After logging back into Client Portal Gateway, a follow-up AAPL snapshot returned last/bid/ask prices and availability code DB (delayed). The Java live test then passed account, position, and quote checks.
        * This successful result used the standard REST snapshot request. The separate explicit delayed-data socket test uses reqMarketDataType(3) and requires TWS or IB Gateway; it was not verified successfully in this run.

* IbkrBrokerAdapter
    * getAccounts()
        * GET /portfolio/accounts.
        * Accept accountId, with id as a fallback.
        * Filter to IBKR_ACCOUNT_ID and reject access if that account is absent.
        * Return id and currency only, excluding account-owner names and other gateway metadata.
    * getPositions()
        * Call getAccounts() first, as required by IBKR and to check account access.
        * GET /portfolio/{configuredAccountId}/positions/{page}, starting at page 0.
        * Continue while a page contains 100 positions.
        * Stop at a short or empty page.
        * Reject duplicate contract IDs, missing quantities, and a full final page at the configured page limit.
        * Never return a truncated portfolio as complete.
        * Use BigDecimal for quantities and money; preserve missing market values as null.
        * observedAt is retrieval time. The underlying portfolio endpoint may return cached positions.
    * searchInstruments(String symbol)
        * Normalize and validate the ticker.
        * GET /iserver/secdef/search?symbol={symbol}&secType=STK.
        * Keep exact-symbol matches with a stock section.
        * Preserve conid, symbol, company name, exchange, currency when supplied, and security type.
        * A missing currency remains null; the search response is not enriched with contract details yet.
        * Preserve multiple matches. Derivatives discovery is outside this initial scope.
    * getQuote(long conid)
        * Require a positive contract ID and ready brokerage session.
        * GET /iserver/marketdata/snapshot with fields 31, 84, 86, and 6509.
        * Field 31: last price. Field 84: bid. Field 86: ask. Field 6509: availability.
        * An initial request may only start the market-data stream.
        * Try at most snapshot-attempts requests (default 5), with snapshot-interval-ms pauses (default 750ms) between empty snapshots; stop early when a usable price arrives.
        * Reject a response for a different contract.
        * Return null for missing, nonnumeric, zero, or negative stock prices.
        * Preserve _updated as updatedAt and retain the raw availability code.
        * Classify the first character of field 6509: R as REALTIME, D as DELAYED, Z as FROZEN, Y as DELAYED_FROZEN, N as NOT_SUBSCRIBED; blank, missing, or unknown codes are UNAVAILABLE. Preserve this classification even when prices are missing; hasPrice independently indicates whether the snapshot contains a usable price.
        * If no usable price arrives, return UNAVAILABLE with null prices.
        * No streaming WebSocket or historical-bars implementation is included.

* BrokerController
    * Endpoints

| Method | Endpoint | Result |
|---|---|---|
| GET | /api/broker/session | Session flags |
| GET | /api/broker/accounts | Configured accessible account |
| GET | /api/broker/positions | Complete portfolio snapshot |
| GET | /api/broker/instruments?symbol=AAPL | Matching stock contracts |
| GET | /api/broker/quotes/{conid} | Quote snapshot |

* Access and Error Handling
    * IntegrationAccessConfiguration checks the resolved controller, including requests using matrix parameters.
    * Require Authorization: Bearer followed by INTEGRATION_ACCESS_TOKEN.
    * Refuse startup if either integration is enabled with a token shorter than 32 characters.
    * Return Cache-Control: no-store for protected endpoints.
    * This is a single-owner token, not a multi-user identity or account authorization system.
    * Use local access or HTTPS termination when deploying; do not send the bearer token over a public HTTP connection.
    * Existing RAG endpoints retain their existing access behavior.

| Code | HTTP status | Meaning |
|---|---|---|
| INVALID_ARGUMENT | 400 | Invalid ticker or contract ID |
| ACCOUNT_NOT_ALLOWED | 403 | Configured account absent from the gateway's account list |
| RATE_LIMITED | 429 | IBKR rejected the request rate |
| INVALID_RESPONSE | 502 | Response does not match the expected contract |
| LOGIN_REQUIRED | 503 | Upstream returned 401/403; check authentication and permissions |
| SESSION_NOT_READY | 503 | Disconnected, unauthenticated, not established, or competing session |
| UNAVAILABLE | 503 | Gateway transport or upstream failure |
| POSITION_LIMIT | 503 | Pagination limit reached; portfolio completeness cannot be established |
| INTERRUPTED | 503 | Request was interrupted |

* Local Setup
    * Install and run IBKR's Client Portal Gateway using the official instructions.
    * Complete browser authentication for the intended account; a paper account is suitable for development.
    * Obtain and verify the gateway certificate through your local gateway setup.
    * Use the actual gateway port. The default below is 5000; a gateway on 5001 needs a different URL.
    * Supply the existing database, OPENAI_API_KEY, and SEC_USER_AGENT settings required by the application.
    * Set these environment variables locally. The account and certificate values below are placeholders.

```bash
export IBKR_ENABLED=true
export IBKR_BASE_URL=https://localhost:5000/v1/api
export IBKR_ACCOUNT_ID=YOUR_ACCOUNT_ID
export IBKR_CERTIFICATE=/absolute/path/to/verified-gateway-certificate.pem
export INTEGRATION_ACCESS_TOKEN="$(openssl rand -hex 32)"

./mvnw spring-boot:run
```

* Diagnostic Examples
    * Run in a shell containing the same access token as the application.
    * The broker connection can be tested while recommendations remain disabled.

```bash
curl http://localhost:8080/api/broker/session \
  -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN"

curl http://localhost:8080/api/broker/positions \
  -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN"

curl 'http://localhost:8080/api/broker/instruments?symbol=AAPL' \
  -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN"
```

* Quote Example
    * Replace CONTRACT_ID with a conid returned by the instrument lookup.

```bash
curl http://localhost:8080/api/broker/quotes/CONTRACT_ID \
  -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN"
```

* Verification
    * IbkrBrokerAdapterTests exercise HTTP request methods/paths and recorded JSON responses using MockRestServiceServer.
    * IntegrationAccessTests verify missing/incorrect tokens and controller protection.
    * IntegrationWiringTests check opt-in startup without contacting external services.
    * IbkrLiveTests is skipped unless explicitly enabled. It reads only the configured account and positions.
    * Optional ibkr.live.conid also checks brokerage readiness and requires an available quote.
    * The live smoke test does not place orders or print account contents.
    * Verification on 2026-09-09: all 12 adapter checks passed; the live test was skipped. See [Agent Harness](Agent_Harness.md) for full-suite results.

```bash
./mvnw -Dtest=IbkrBrokerAdapterTests,IntegrationAccessTests,IntegrationWiringTests test

# Requires a running authenticated gateway and the IBKR environment variables above.
./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true test

# Optional market-data check; replace CONTRACT_ID.
./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true -Dibkr.live.conid=CONTRACT_ID test
```

* Future Extension
    * Add another BrokerReadService adapter when another broker is required.
    * Add contract-detail enrichment before relying on currency-specific calculations.
    * Add endpoint-aware/distributed pacing and subscription lifecycle management for higher traffic.
    * For multiple users, replace the shared token and configured account with authenticated user-to-account bindings.
    * Before execution, implement persisted proposals, approval of the exact version, deterministic guards, and order reconciliation.
    * Keep order operations outside the recommendation tool allowlist.

* Official References
    * [IBKR Web API authentication, sessions, and market data](https://www.interactivebrokers.com/campus/ibkr-api-page/webapi-doc/).
    * [IBKR positions endpoint and pagination](https://www.interactivebrokers.com/docs/web-api/v1/endpoints/portfolio/positions).
    * [IBKR contract search](https://www.interactivebrokers.com/campus/trading-lessons/contract-search/).

* Broker Read Pipeline

```text
Authenticated HTTP Request or Allowed Model Tool
    ↓
BrokerReadService
    ↓
IbkrBrokerAdapter → IbkrSessionManager
    ↓
IbkrApiClient (TLS, pacing, timeouts, bounded GET retry)
    ↓
Client Portal Gateway → IBKR
    ↓
Normalized Account / Portfolio / Instrument / Quote
```

* Paper TWS retry — 2026-09-10
    * After user confirmed paper TWS login, the existing read-only Python probe connected on port 7497 and completed its API handshake. Requested AAPL SMART/NASDAQ USD delayed market data (type 3), with a 30-second timeout.
    * Quote verification failed: no marketDataType callback and no positive delayed price ticks arrived. Observed message codes: 2104, 2106, 2158. The test exited 1; the cause of missing quotes was not established. No orders or portfolio requests were made.
    * This probe is independent of the recommendation application, whose current Java broker adapter uses Client Portal REST on port 5001. A TWS socket adapter is required for the full recommendation workflow to use TWS directly.
    * Saved [probe log](live-runs/2026-09-10-tws-paper/run.log).

* Availability mapping and delayed-data check — 2026-09-10, 14:28 SGT
    * Availability now reflects the first character of 6509 independently of price presence, including Y and N mappings. Default polling increased to five attempts with configurable 750ms pauses. Adapter, recommendation, and wiring tests passed: 44 tests, zero failures/errors/skips (/tmp/stock-availability-verified-tests.log).
    * Restarted the updated application on port 8081. AAPL conid 265598 returned HTTP 200 after 4.385 seconds: rawAvailability DB, availability DELAYED, last/bid/ask all null after polling. This confirms the delayed classification, but does not verify delivery of a delayed price. No model or portfolio request was made.
    * Saved [quote response](live-runs/2026-09-10-delayed-mapping/quote.json).

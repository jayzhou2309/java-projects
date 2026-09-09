# Direct IBKR Integration

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
        * Try at most snapshot-attempts requests, with a 500ms pause between empty snapshots.
        * Reject a response for a different contract.
        * Return null for missing, nonnumeric, zero, or negative stock prices.
        * Preserve _updated as updatedAt and retain the raw availability code.
        * Classify R as REALTIME, D as DELAYED, Z as FROZEN, and other codes as UNKNOWN.
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

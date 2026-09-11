# IBKR through Trader Workstation (TWS)

The application uses the official Java TWS socket API for read-only session, stock contract, quote, and position access. Client Portal REST, browser login, gateway certificates, and `/tickle` are no longer used. The existing `/api/broker` endpoints and recommendation specialist use the same `BrokerReadService` interface.

## Setup

1. Download and extract the official **TWS API 10.45.01** SDK. Install its Java JAR once into your Maven cache:

   ```bash
   bash scripts/install-tws-sdk.sh /absolute/path/to/IBJts
   ```

   The project pins the SDK and its protobuf dependency in `pom.xml`. SDK binaries are not checked into this repository. Run the installer before Maven builds on a new development machine or CI worker. The SDK's bundled POM has an older version label, so the installer uses `API_VersionNum.txt` and the release JAR instead.

2. Start **paper TWS** and log in interactively. In **Global Configuration → API → Settings**, enable **ActiveX and Socket Clients** and **Read-Only API**, with socket port **7497**. Allow the application's local connection if TWS prompts. Keep TWS running and logged in.
3. Load the existing database/model configuration, then set:

   ```bash
   export IBKR_ENABLED=true
   export IBKR_HOST=127.0.0.1
   export IBKR_PORT=7497
   export IBKR_CLIENT_ID=71
   export IBKR_ACCOUNT_ID=YOUR_PAPER_ACCOUNT_ID
   export IBKR_MARKET_DATA_TYPE=4
   export INTEGRATION_ACCESS_TOKEN=YOUR_EXISTING_APPLICATION_ACCESS_TOKEN
   ./mvnw spring-boot:run
   ```

The application does not accept an IBKR username or password. It connects to the session already authenticated in TWS. Use a unique nonzero client ID for each app/test process; client ID 0 is deliberately excluded. The host/port and selected account come from application configuration, never model arguments. The default port is for paper TWS; configure the actual port when using a different TWS or IB Gateway socket setup. `IBKR_BASE_URL` and `IBKR_CERTIFICATE` are obsolete and should be removed from launch configurations.

## Configuration

| Property | Default | Meaning |~~~~~~~~
|---|---|---|
| broker.ibkr.enabled | false | Opt-in broker bean creation |
| broker.ibkr.host | 127.0.0.1 | TWS socket host |
| broker.ibkr.port | 7497 | Paper TWS API port |
| broker.ibkr.client-id | 71 | Unique positive API client ID |
| broker.ibkr.account-id | empty | Required configured account when enabled |
| broker.ibkr.timeout-ms | 10000 | Bounded operation/queue wait and socket setup timeouts |
| broker.ibkr.quote-wait-ms | 5000 | Maximum streaming quote collection time within the operation budget |
| broker.ibkr.market-data-type | 4 | Requested market-data mode |
| broker.ibkr.max-positions | 1000 | Fail instead of returning a truncated portfolio |

TWS mode 1 is realtime, 2 frozen, 3 delayed, and 4 delayed-frozen. The default 4 permits delayed-frozen requests for closed-market testing. Entitlements and TWS availability determine what is delivered; the application reports the **actual `marketDataType` callback**, including realtime if TWS overrides a delayed request. Requesting a mode is not proof of receiving it.

## Implementation

- `IbkrConfiguration` creates a lazily connected `TwsClient`; startup does not contact TWS. Shutdown closes the socket and reader threads.
- `TwsSocketTransport` uses `EClientSocket`, `EReader`, and `EJavaSignal`. Its narrow interface exposes read requests and subscription cancellation, with no order methods.
- `TwsClient` correlates callbacks by request ID, bounds waiting, ignores late callbacks, and fails pending reads on disconnect. A later request reconnects the socket; it never performs login or session takeover. Operations are serialized because TWS's requested market-data mode is connection-wide. Symbol searches are spaced at least one second apart.
- `IbkrBrokerAdapter` exposes the existing `BrokerReadService` operations. The recommendation manager and broker specialist require no alternative tool protocol.

### Session and account reads

`GET /api/broker/session` connects on demand, waits for the API handshake (`nextValidId`) and managed-account callbacks, then checks `reqCurrentTime`. The existing `connected`, `authenticated`, and `established` fields now describe TWS socket/handshake readiness, not Client Portal flags. TWS connectivity-loss callbacks invalidate readiness. `competing` is set when TWS reports error 10197; false does not prove the absence of every other IBKR login.

`GET /api/broker/accounts` exposes only the configured account after checking the TWS managed-account list. Currency is null because that callback supplies account IDs only.

`GET /api/broker/positions` uses `reqPositionsMulti` for the configured account and waits for `positionMultiEnd`. It rejects account mismatches and excessive position counts, and always cancels the subscription. Quantities, contract IDs, symbols, security types, and currencies are retained. Market price and market value are null: TWS's position callback does not supply them, and average cost is not a current market price. Portfolio reads are sent to the model only when `includePortfolio=true`.

### Contracts and quotes

`GET /api/broker/instruments?symbol=AAPL` uses `reqMatchingSymbols`, retaining exact-symbol stock matches, with a maximum of 20 results. Multiple listings remain explicit. Select the intended conid in recommendation requests, e.g. NASDAQ AAPL `265598` when returned by discovery.

`GET /api/broker/quotes/265598` resolves the stock through `reqContractDetails`, requests the configured market-data mode, and opens a temporary streaming subscription (`reqMktData`, not a regulatory snapshot). It collects ordinary bid/ask/last ticks 1/2/4 and delayed ticks 66/67/68 until all three prices and a type arrive or the collection deadline expires. `cancelMktData` runs on success, timeout, error, and interruption. HTTP snapshot polling and field 6509 parsing have been removed.

Availability comes from callback type 1/2/3/4 as REALTIME/FROZEN/DELAYED/DELAYED_FROZEN. Explicit subscription errors yield NOT_SUBSCRIBED. Without a type callback, availability remains UNAVAILABLE even if a price arrived. `rawAvailability` now contains the numeric TWS callback type as a string, rather than REST codes such as DB.

Missing, nonpositive, nonfinite, and sentinel prices remain null. A type callback can identify delayed data even with no price. `updatedAt` is populated only from last-trade timestamp ticks 45/88; receipt time is separately recorded as `observedAt` and is not substituted for quote freshness. Timestamps may be missing, especially for frozen quotes. A delayed, frozen, untimestamped, or empty quote cannot make a recommendation COMPLETE.

### Errors

The existing sanitized broker error codes and HTTP mappings remain: INVALID_ARGUMENT (400), ACCOUNT_NOT_ALLOWED (403), RATE_LIMITED (429), INVALID_RESPONSE (502), and unavailable/session/position-limit/interruption failures (503). TWS messages are not exposed because they may contain account or connection details. A quote with no prices at the collection deadline returns a structured quote with null prices; it is not presented as a verified current quote.

## Verification

```bash
./mvnw -Dtest=IbkrBrokerAdapterTests,RecommendationServiceTests,IntegrationWiringTests test

# Separate client ID from the running app; verifies session and AAPL discovery.
# Also checks the configured account when IBKR_ACCOUNT_ID is set.
IBKR_CLIENT_ID=72 ./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true test

# Optional quote test; fails unless a usable price arrives.
IBKR_CLIENT_ID=72 ./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true -Dibkr.live.conid=265598 test

# Opt-in position read; requires IBKR_ACCOUNT_ID, sends no positions to a model.
IBKR_CLIENT_ID=72 ./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true -Dibkr.live.positions=true test
```

Deterministic adapter tests use simulated TWS callbacks to verify account isolation, end markers, bounded waits, reconnection, request isolation, market-data types, price validity, errors, and cancellation. Live tests do not place orders. The Python probe in `src/test/python` remains an optional independent socket diagnostic and defaults to delayed-frozen mode.

Prior Client Portal experiments are retained in [historical notes](IBKR_Client_Portal_History.md) and `live-runs`; they are not validation of the new TWS transport.

## Official references

- [TWS API documentation and setup](https://www.interactivebrokers.com/docs/tws-api)
- [Requesting market data](https://www.interactivebrokers.com/docs/tws-api/doc/quick-start/requesting-market-data)
- [Realtime, frozen, delayed and delayed-frozen modes](https://www.interactivebrokers.com/docs/tws-api/doc/market-data-delayed/introduction)
- [Receiving quote fields and frozen data](https://interactivebrokers.github.io/tws-api/md_receive.html)

## Migration verification — 2026-09-10

- Full suite: 106 discovered, 105 passed, one opt-in live test skipped; no failures or errors. Separate live TWS session/account/position test passed. Logs: `/tmp/stock-tws-final-tests.log` and `/tmp/stock-tws-session-positions-live-test.log`.
- Paper TWS on 127.0.0.1:7497 passed handshake, heartbeat, configured-account access, positions, and AAPL discovery. Four listings were returned with currencies, including NASDAQ conid 265598 (USD).
- Live quote checks in requested modes 3 and 4 received no usable price or market-data-type callback within five seconds. The optional quote assertions failed as intended; quote delivery is not verified. The cause of absent data is not established by these tests.
- The application on port 8081 was migrated to TWS with client ID 71 and requested type 4. End-to-end recommendation `9ae35127-de79-42b9-82b6-e2703e0a58ce` returned HTTP 200 in 21.171 seconds, PARTIAL / NEUTRAL, five filing sources, seven model calls, and 10,893 observed tokens. Both specialist delegations and broker discovery/quote operations ran. No portfolio data was sent to the model.
- Saved [session](live-runs/2026-09-10-tws-migration/session.json), [quote](live-runs/2026-09-10-tws-migration/quote.json), [recommendation](live-runs/2026-09-10-tws-migration/recommendation.json), and [trace](live-runs/2026-09-10-tws-migration/run.log), plus summarized automated/live test logs in the same directory.

## Linkage recheck — 2026-09-10, 19:08 SGT

- Split live checks into session/discovery, configured-account/optional-positions, and optional quote tests. Session/discovery and quotes can now be tested without configuring an account. Account checks are explicitly skipped when `IBKR_ACCOUNT_ID` is absent; quote checks are skipped unless `ibkr.live.conid` is supplied.
- Focused automated tests: 48 passed, three opt-in live tests skipped, no failures or errors (`/tmp/stock-tws-linkage-tests.log`). Offline Maven packaging passed (`/tmp/stock-tws-linkage-build.log`).
- Java live check against local TWS port 7497: handshake, heartbeat, and AAPL US contract discovery passed. Account/position checks were skipped because this shell has no configured account. The optional AAPL quote check failed with `UNAVAILABLE` and no usable prices after the five-second collection window (`/tmp/stock-tws-linkage-live.log`).
- Independent official Python SDK probe completed its handshake and received status codes 2104, 2106, and 2158, but no market-data-type callback or delayed price ticks within 30 seconds in requested mode 4 (`/tmp/stock-tws-linkage-probe.log`). This reproduces missing quote delivery in both clients; the underlying cause remains undetermined.
- The application endpoint on port 8081 was unavailable, so this recheck does not establish HTTP or recommendation end-to-end readiness.

### Clean restart retry — 2026-09-10, 19:14 SGT

Stopped TWS, the legacy Client Portal gateway on port 5001, and the stock application on port 8080 at the user's request. Confirmed the IBKR API ports were clear, reopened TWS, and reran tests after the user completed login. TWS alone was listening on port 7497; Client Portal remained stopped. Java session/heartbeat and AAPL discovery passed, but the optional quote test again returned `UNAVAILABLE` with no prices. The independent Python mode-4 probe also received no market-data-type callback or delayed prices within 30 seconds (status codes 2104, 2106, 2158 only). A clean restart did not resolve quote delivery. Account checks remained skipped because `IBKR_ACCOUNT_ID` was unset. Latest retry logs replace `/tmp/stock-tws-linkage-live.log` and `/tmp/stock-tws-linkage-probe.log`.

### API entitlement diagnostic — 2026-09-10

After the user confirmed AAPL prices are visible in TWS, an independent SDK diagnostic requested modes 1, 3, and 4 sequentially for AAPL SMART/NASDAQ with ten-second collection windows. Server version 223 returned heartbeat and tick-request-parameter callbacks. Mode 1 explicitly returned error **10089**, documented by IBKR as an API market-data subscription requirement; this establishes an entitlement rejection for the live API request even though TWS displays a price. Modes 3 and 4 returned no price or market-data-type callbacks, so delayed delivery remains unresolved. Evidence: `/tmp/tws-modes-probe.log`; read-only diagnostic: `/tmp/tws_modes_probe.py`. No subscriptions or account settings were changed.

References: [IBKR error codes](https://www.interactivebrokers.com/docs/tws-api/doc/error-handling/error-codes), [TWS versus API market data](https://www.interactivebrokers.com/campus/trading-lessons/python-receiving-market-data/).

## Delayed quote diagnostic — 2026-09-11

The Java adapter was rechecked with the delayed-data path. Its request sequence is correct:

1. Resolve the stock contract with `reqContractDetails`.
2. Set the connection-wide mode with `reqMarketDataType(3)` or `reqMarketDataType(4)`.
3. Subscribe with `reqMktData`.
4. Accept delayed fields 66/67/68 for bid/ask/last and cancel the subscription.

The focused adapter tests pass, including simulated delayed and delayed-frozen bid/ask/last callbacks. The live diagnostic still receives no delayed price, bid/ask, or `marketDataType` callback from local TWS for AAPL. Therefore the missing values are not caused by the Java tick-field mapping.

`TwsClient` now logs the actual callback type, presence of each quote field, timestamp, and whether IBKR rejected the subscription. It logs subscription rejection codes 354 and 10089 with the conid and requested/actual data type, while continuing to omit provider messages that may contain account or connection details.

Conclusion: TWS socket connectivity and contract discovery work, but this API session is not delivering delayed market data. Verify delayed market-data permissions for the logged-in IBKR account and the selected listing, then rerun:

```bash
IBKR_MARKET_DATA_TYPE=3 IBKR_CLIENT_ID=72 \
  ./mvnw -Dtest=IbkrLiveTests -Dibkr.live=true -Dibkr.live.conid=265598 test
```

For a standalone check independent of the application:

```bash
python3 src/test/python/ibkr_delayed_live_test.py \
  --port 7497 --client-id 93 --market-data-type 3
```

The 2026-09-11 live run reproduced the same result in both clients. Java returned
`availability=UNAVAILABLE`, `rawType=null`, and no last/bid/ask values after five
seconds. The Python probe received only status codes 2104, 2106, and 2158, with
no `marketDataType` callback or delayed ticks after ten seconds. These are market
data farm status messages, not quote data.

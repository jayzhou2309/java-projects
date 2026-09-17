#!/bin/bash
# Rebuild all 13 filings through the application's own endpoint, one at a time (plan plans/2026-09-17-chunk-size-pool.md, Frozen design: store B at
# 1650 / 250, and the rollback to 4000 / 500 with no override). The only writes to the store in this plan are these POST /api/rag/filings/{id}/rebuild calls.
# usage: rebuild_all.sh <point B|A2> [NAME=value overrides ...]   (from the repository root; TOKEN and SCRATCH exported)
# Writes rebuild-responses-<point>.txt (per filing: HTTP code, curl seconds, start and end time, the count of "Embedded chunk" log lines the rebuild
# added, and the response), rebuild-log-<point>.txt (the rebuild-phase log lines other than the per-chunk ones), and appends to run.log. Embedding calls only.
set -u
POINT="$1"; shift
HERE="src/main/java/documentation/live-runs/2026-09-17-chunk-size-pool"
LOG="$HERE/run.log"; OUT="$HERE/rebuild-responses-$POINT.txt"
APPLOG="$SCRATCH/app-rebuild-$POINT-$(date +%H%M%S).log"
now() { date +%H:%M:%S; }
say() { echo "  $*" | tee -a "$LOG"; }
if [ -n "$(lsof -nP -iTCP:8081 -sTCP:LISTEN)" ] || pgrep -f "spring-boot:run|surefire|StockRecommendationEngineApplication" >/dev/null; then echo "port 8081 or a Maven/app process is busy"; exit 2; fi
set -a; source .env; set +a
echo "Rebuild to point $POINT" | tee -a "$LOG"
say "$(now) start: env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN=<not recorded> RAG_CROSS_ENCODER_ENABLED=true $* ./mvnw -q -o spring-boot:run > $(basename "$APPLOG") (overrides given: ${*:-none, the application.yaml defaults 4000 / 500 apply})"
env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN="$TOKEN" RAG_CROSS_ENCODER_ENABLED=true "$@" ./mvnw -q -o spring-boot:run > "$APPLOG" 2>&1 &
for i in $(seq 1 90); do grep -q "Started StockRecommendationEngineApplication" "$APPLOG" && break; sleep 1; done
if ! grep -q "Started StockRecommendationEngineApplication" "$APPLOG"; then say "$(now) the application did not start"; exit 3; fi
say "$(now) $(grep -o 'Started StockRecommendationEngineApplication in [0-9.]* seconds' "$APPLOG")"
: > "$OUT"
FAILED=0
for ID in 3 4 5 6 7 8 109 110 161 162 288 324 325; do
  N0=$(grep -c "Embedded chunk" "$APPLOG"); T0=$(now)
  R=$(curl -s -o "$SCRATCH/rebuild-$POINT-$ID.json" -w '%{http_code} %{time_total}' -X POST "localhost:8081/api/rag/filings/$ID/rebuild" -H "Authorization: Bearer $TOKEN")
  N1=$(grep -c "Embedded chunk" "$APPLOG")
  LINE="filing $ID: HTTP and seconds $R; $T0 to $(now); Embedded chunk lines added $((N1-N0)); response $(cat "$SCRATCH/rebuild-$POINT-$ID.json")"
  echo "$LINE" >> "$OUT"; say "$LINE"
  case "$R" in 200*) grep -q '"SUCCEEDED"' "$SCRATCH/rebuild-$POINT-$ID.json" || FAILED=1;; *) FAILED=1;; esac
  if [ $FAILED = 1 ]; then say "$(now) rebuild of filing $ID did not succeed; stopping the rebuild loop (unexpected)"; break; fi
done
grep -vE "Embedding chunk|Embedded chunk" "$APPLOG" | sed -n '/Started StockRecommendationEngineApplication/,$p' > "$HERE/rebuild-log-$POINT.txt"
say "app log: $(grep -c 'Embedding chunk' "$APPLOG") Embedding chunk, $(grep -c 'Embedded chunk' "$APPLOG") Embedded chunk (one embedding call per chunk), $(grep -c 'Filing rebuild succeeded' "$APPLOG") Filing rebuild succeeded, $(grep -c 'Filing rebuild failed' "$APPLOG") Filing rebuild failed, $(grep -cE ' (WARN|ERROR) ' "$APPLOG") WARN or ERROR lines; rebuild-log-$POINT.txt $(wc -l < "$HERE/rebuild-log-$POINT.txt" | tr -d ' ') lines (from the Started line on, per-chunk lines left out)"
grep -E ' (WARN|ERROR) ' "$APPLOG" | cut -c1-300 | while read -r L; do say "WARN or ERROR line: $L"; done
pkill -f spring-boot:run; sleep 3; pgrep -f "spring-boot:run|StockRecommendationEngineApplication" >/dev/null && { pkill -f spring-boot:run; pkill -f StockRecommendationEngineApplication; sleep 3; }
say "$(now) stopped; listeners on 8081: $(lsof -nP -iTCP:8081 -sTCP:LISTEN | wc -l | tr -d ' '); processes: $(pgrep -f 'spring-boot:run|StockRecommendationEngineApplication' | wc -l | tr -d ' ')"
exit $FAILED

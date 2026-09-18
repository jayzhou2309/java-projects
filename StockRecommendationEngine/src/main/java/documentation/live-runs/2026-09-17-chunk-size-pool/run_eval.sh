#!/bin/bash
# One evaluation run of plan plans/2026-09-17-chunk-size-pool.md, Milestone 1: start the application with the given overrides, warm up with
# one retrieval, POST /api/rag/evaluate?rerank=<r>&trace=true, export the snapshot (GET and row_to_json) and the evidence report at once,
# cut the application-log window, compute the latency extract, stop the application, and append the events to run.log.
# usage: run_eval.sh <label> <rerank true|false> [NAME=value overrides ...]      (from the repository root; TOKEN and SCRATCH exported)
# The label names the files: snapshot-<id>-<label>.json, evidence-<id>.json, app-log-<id>.txt, latency-<id>.json. No chat model is called.
set -u
LABEL="$1"; RERANK="$2"; shift 2
HERE="src/main/java/documentation/live-runs/2026-09-17-chunk-size-pool"
LOG="$HERE/run.log"
APPLOG="$SCRATCH/app-$LABEL-$(date +%H%M%S).log"
now() { date +%H:%M:%S; }
say() { echo "  $*" | tee -a "$LOG"; }
if [ -n "$(lsof -nP -iTCP:8081 -sTCP:LISTEN)" ] || pgrep -f "spring-boot:run|surefire|StockRecommendationEngineApplication" >/dev/null; then echo "port 8081 or a Maven/app process is busy"; exit 2; fi
set -a; source .env; set +a
echo "Run $LABEL (rerank=$RERANK)" | tee -a "$LOG"
say "$(now) start: env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN=<not recorded> RAG_CROSS_ENCODER_ENABLED=true $* ./mvnw -q -o spring-boot:run > $(basename "$APPLOG") (observed: this command line; overrides given: ${*:-none, the application.yaml defaults apply})"
env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN="$TOKEN" RAG_CROSS_ENCODER_ENABLED=true "$@" ./mvnw -q -o spring-boot:run > "$APPLOG" 2>&1 &
for i in $(seq 1 90); do grep -q "Started StockRecommendationEngineApplication" "$APPLOG" && break; if ! pgrep -f "spring-boot:run" >/dev/null; then break; fi; sleep 1; done
if ! grep -q "Started StockRecommendationEngineApplication" "$APPLOG"; then say "$(now) the application did not start; see $(basename "$APPLOG")"; tail -5 "$APPLOG"; exit 3; fi
say "$(now) $(grep -o 'Started StockRecommendationEngineApplication in [0-9.]* seconds' "$APPLOG"); $(grep -o 'Cross-encoder loaded.*' "$APPLOG" | head -1 | cut -c1-200)"
W=$(curl -s -o "$SCRATCH/warmup-$LABEL.json" -w '%{http_code} %{time_total}' -X POST localhost:8081/api/rag/retrieve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"ticker\":\"NVDA\",\"query\":\"What was NVIDIA gross margin in fiscal 2026?\",\"rerank\":$RERANK}")
say "$(now) warm-up POST /api/rag/retrieve (NVDA, rerank $RERANK): HTTP and seconds $W; $(grep -o 'Reranking filing candidates:.*' "$APPLOG" | tail -1)"
L0=$(sysctl -n vm.loadavg); T0=$(now)
E=$(curl -s -o "$SCRATCH/evaluate-$LABEL.json" -w '%{http_code} %{time_total} %{size_download}' -X POST "localhost:8081/api/rag/evaluate?rerank=$RERANK&trace=true" -H "Authorization: Bearer $TOKEN")
L1=$(sysctl -n vm.loadavg)
ID=$(python3 -c "import json,sys; print(json.load(open('$SCRATCH/evaluate-$LABEL.json'))['id'])")
WALL=$(echo "$E" | cut -d' ' -f2)
say "$T0 POST /api/rag/evaluate?rerank=$RERANK&trace=true, loadavg before $L0 after $L1; HTTP, seconds, bytes: $E -> snapshot $ID"
say "$(grep -o "Retrieval evaluation stored: id=$ID,.*" "$APPLOG")"
G=$(curl -s -o "$SCRATCH/get-$ID.json" -w '%{http_code} %{size_download}' "localhost:8081/api/rag/evaluate/$ID" -H "Authorization: Bearer $TOKEN")
V=$(curl -s -o "$SCRATCH/evidence-$ID.pretty.json" -w '%{http_code} %{size_download}' "localhost:8081/api/rag/evaluate/$ID/evidence" -H "Authorization: Bearer $TOKEN")
C=$(python3 "$HERE/compact_json.py" "$SCRATCH/evidence-$ID.pretty.json" "$HERE/evidence-$ID.json")
docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "select row_to_json(r) from retrieval_evaluations r where id=$ID" > "$HERE/snapshot-$ID-$LABEL.json"
SAME=$(python3 - "$SCRATCH/get-$ID.json" "$HERE/snapshot-$ID-$LABEL.json" "$SCRATCH/evaluate-$LABEL.json" <<'PY'
import json,sys
g,r,p=[json.load(open(f)) for f in sys.argv[1:4]]
key=lambda qs:[(q['id'],q['rank'],q.get('matchedChunkId')) for q in qs]
print('GET equals POST response: %s; GET questions (id, rank, matchedChunkId) equal the row export: %s; properties equal: %s' % (g==p, key(g['results'])==key(r['results']['questions']), g['properties']==r['properties']))
PY
)
say "$(now) GET /api/rag/evaluate/$ID: HTTP and bytes $G; GET .../evidence: HTTP and bytes $V; $C; row export snapshot-$ID-$LABEL.json $(wc -c < "$HERE/snapshot-$ID-$LABEL.json" | tr -d ' ') bytes (select row_to_json(r) from retrieval_evaluations r where id=$ID); $SAME"
say "properties: $(python3 -c "import json; p=json.load(open('$HERE/snapshot-$ID-$LABEL.json'))['properties']; print(', '.join('%s %s'%(k,p[k]) for k in ('candidateCount','keywordCandidateCount','rerank','rerankCandidates','rerankedQuestions','rerankFallbackQuestions','chunkMaxChars','chunkOverlapChars','storeVersions')))")"
say "$(python3 "$HERE/extract_log_window.py" "$APPLOG" "$ID" "$HERE/app-log-$ID.txt") -> app-log-$ID.txt"
say "$(python3 "$HERE/latency.py" "$HERE/app-log-$ID.txt" "$HERE/snapshot-$ID-$LABEL.json" "$WALL" "$HERE/latency-$ID.json" | cut -c1-400)"
say "app log: $(grep -c 'Evaluating retrieval' "$APPLOG") Evaluating retrieval, $(grep -c 'Query embedding completed' "$APPLOG") Query embedding completed, $(grep -c 'Reranking failed' "$APPLOG") Reranking failed, $(grep -cE ' (WARN|ERROR) ' "$APPLOG") WARN or ERROR lines"
pkill -f spring-boot:run; sleep 3; pgrep -f "spring-boot:run|StockRecommendationEngineApplication" >/dev/null && { pkill -f spring-boot:run; pkill -f StockRecommendationEngineApplication; sleep 3; }
say "$(now) stopped; listeners on 8081: $(lsof -nP -iTCP:8081 -sTCP:LISTEN | wc -l | tr -d ' '); processes: $(pgrep -f 'spring-boot:run|StockRecommendationEngineApplication' | wc -l | tr -d ' ')"
echo "$ID" > "$SCRATCH/last-id"

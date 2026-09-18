#!/bin/bash
# The one application session of plan plans/2026-09-17-chunk-size.md, Milestone 4 (Follow_Ups RAG-29): start the application at the
# application.yaml defaults with the cross-encoder loaded (RAG_CROSS_ENCODER_ENABLED=true, as the default reference 1777 was started, so the
# recorded properties match 1777's), warm up with one retrieval, then two evaluate calls, each exported at once (GET, row_to_json, evidence
# report compacted, application-log window, latency extract): F = POST /api/rag/evaluate?rerank=false&trace=true (the fresh default traced
# snapshot, H3) and R = POST /api/rag/evaluate?rerank=true&trace=true (a traced run at snapshot 297's settings: rerank true, rerank-candidates
# 20, max-window scoring with overlap 64 and at most 4 windows, window 10, set v2; the new reference of RetrievalEvaluationTraceLiveTests and the
# traced snapshot RetrievalEvidenceLiveTests reads, H4). Then the store's chunk hashes and chunk export are taken (read-only SQL of the
# previous plan), the application is stopped, and the port is checked. 42 embedding calls per evaluate call plus 1 for the warm-up; no chat model.
# usage: run_session.sh      (from the repository root; TOKEN and SCRATCH exported; .env sourced here)
set -u
HERE="src/main/java/documentation/live-runs/2026-09-18-rag29-section-key"
POOL="src/main/java/documentation/live-runs/2026-09-17-chunk-size-pool"
LOG="$HERE/run.log"
APPLOG="$SCRATCH/app-session-$(date +%H%M%S).log"
now() { date +%H:%M:%S; }
say() { echo "  $*" | tee -a "$LOG"; }
if [ -n "$(lsof -nP -iTCP:8081 -sTCP:LISTEN)" ] || pgrep -f "spring-boot:run|surefire|StockRecommendationEngineApplication" >/dev/null; then echo "port 8081 or a Maven/app process is busy"; exit 2; fi
set -a; source .env; set +a
echo "Application session (run_session.sh)" | tee -a "$LOG"
say "$(now) start: env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN=<not recorded> RAG_CROSS_ENCODER_ENABLED=true ./mvnw -q -o spring-boot:run > $(basename "$APPLOG") (observed: this command line; no other override, the application.yaml defaults apply)"
env SERVER_PORT=8081 INTEGRATION_ACCESS_TOKEN="$TOKEN" RAG_CROSS_ENCODER_ENABLED=true ./mvnw -q -o spring-boot:run > "$APPLOG" 2>&1 &
for i in $(seq 1 90); do grep -q "Started StockRecommendationEngineApplication" "$APPLOG" && break; if ! pgrep -f "spring-boot:run" >/dev/null; then break; fi; sleep 1; done
if ! grep -q "Started StockRecommendationEngineApplication" "$APPLOG"; then say "$(now) the application did not start; see $(basename "$APPLOG")"; tail -5 "$APPLOG"; exit 3; fi
say "$(now) $(grep -o 'Started StockRecommendationEngineApplication in [0-9.]* seconds' "$APPLOG"); $(grep -o 'Cross-encoder loaded.*' "$APPLOG" | head -1 | cut -c1-200)"
W=$(curl -s -o "$SCRATCH/warmup.json" -w '%{http_code} %{time_total}' -X POST localhost:8081/api/rag/retrieve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ticker":"NVDA","query":"What was NVIDIA gross margin in fiscal 2026?","rerank":false}')
say "$(now) warm-up POST /api/rag/retrieve (NVDA, rerank false): HTTP and seconds $W"

evaluate() {  # <label> <rerank true|false>
  local LABEL="$1" RERANK="$2"
  local T0 E ID WALL G V C SAME
  T0=$(now)
  E=$(curl -s -o "$SCRATCH/evaluate-$LABEL.json" -w '%{http_code} %{time_total} %{size_download}' -X POST "localhost:8081/api/rag/evaluate?rerank=$RERANK&trace=true" -H "Authorization: Bearer $TOKEN")
  ID=$(python3 -c "import json,sys; print(json.load(open('$SCRATCH/evaluate-$LABEL.json'))['id'])")
  WALL=$(echo "$E" | cut -d' ' -f2)
  say "$T0 POST /api/rag/evaluate?rerank=$RERANK&trace=true; HTTP, seconds, bytes: $E -> snapshot $ID ($LABEL)"
  say "$(grep -o "Retrieval evaluation stored: id=$ID,.*" "$APPLOG")"
  G=$(curl -s -o "$SCRATCH/get-$ID.json" -w '%{http_code} %{size_download}' "localhost:8081/api/rag/evaluate/$ID" -H "Authorization: Bearer $TOKEN")
  V=$(curl -s -o "$SCRATCH/evidence-$ID.pretty.json" -w '%{http_code} %{size_download}' "localhost:8081/api/rag/evaluate/$ID/evidence" -H "Authorization: Bearer $TOKEN")
  C=$(python3 "$POOL/compact_json.py" "$SCRATCH/evidence-$ID.pretty.json" "$HERE/evidence-$ID.json")
  docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "select row_to_json(r) from retrieval_evaluations r where id=$ID" > "$HERE/snapshot-$ID-$LABEL.json"
  cp "$SCRATCH/get-$ID.json" "$HERE/get-$ID.json"
  SAME=$(python3 - "$SCRATCH/get-$ID.json" "$HERE/snapshot-$ID-$LABEL.json" "$SCRATCH/evaluate-$LABEL.json" <<'PY'
import json,sys
g,r,p=[json.load(open(f)) for f in sys.argv[1:4]]
key=lambda qs:[(q['id'],q['rank'],q.get('matchedChunkId')) for q in qs]
print('GET equals POST response: %s; GET questions (id, rank, matchedChunkId) equal the row export: %s; properties equal: %s' % (g==p, key(g['results'])==key(r['results']['questions']), g['properties']==r['properties']))
PY
)
  say "$(now) GET /api/rag/evaluate/$ID: HTTP and bytes $G (kept as get-$ID.json); GET .../evidence: HTTP and bytes $V; $C; row export snapshot-$ID-$LABEL.json $(wc -c < "$HERE/snapshot-$ID-$LABEL.json" | tr -d ' ') bytes (select row_to_json(r) from retrieval_evaluations r where id=$ID); $SAME"
  say "properties: $(python3 -c "import json; p=json.load(open('$HERE/snapshot-$ID-$LABEL.json'))['properties']; print(', '.join('%s %s'%(k,p[k]) for k in ('set','setCreatedOn','candidateCount','keywordCandidateCount','rerank','rerankCandidates','rerankerScoring','rerankerVersion','rerankedQuestions','rerankFallbackQuestions','chunkMaxChars','chunkOverlapChars','storeVersions')))")"
  say "aapl-08: $(python3 -c "import json; s=json.load(open('$HERE/snapshot-$ID-$LABEL.json')); q=[q for q in s['results']['questions'] if q['id']=='aapl-08'][0]; print('rank %s matchedChunkId %s' % (q['rank'], q['matchedChunkId']))")"
  say "$(python3 "$POOL/extract_log_window.py" "$APPLOG" "$ID" "$HERE/app-log-$ID.txt") -> app-log-$ID.txt"
  say "$(python3 "$POOL/latency.py" "$HERE/app-log-$ID.txt" "$HERE/snapshot-$ID-$LABEL.json" "$WALL" "$HERE/latency-$ID.json" | cut -c1-400)"
  echo "$ID" > "$SCRATCH/last-id-$LABEL"
}
evaluate "f-defaults-rerank-off" false
evaluate "r-traced-297-settings-rerank-candidates-20" true

say "$(now) chunk-hashes.sql (the previous plan's script, read-only) -> chunk-hashes.json; chunks.sql -> chunks.json"
docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < "$POOL/chunk-hashes.sql" > "$HERE/chunk-hashes.json"
docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < "$POOL/chunks.sql" > "$HERE/chunks.json"
say "$(python3 -c "
import json
h=json.load(open('$HERE/chunk-hashes.json')); a2=json.load(open('$POOL/chunk-hashes-A2.json')); c=json.load(open('$HERE/chunks.json'))
print('chunk-hashes.json %d objects, ids %d to %d; equals chunk-hashes-A2.json as parsed JSON: %s; chunks.json %d objects' % (len(h), min(x['id'] for x in h), max(x['id'] for x in h), h==a2, len(c)))")"
say "app log: $(grep -c 'Evaluating retrieval' "$APPLOG") Evaluating retrieval, $(grep -c 'Query embedding completed' "$APPLOG") Query embedding completed, $(grep -c 'Reranking completed' "$APPLOG") Reranking completed, $(grep -c 'Reranking failed' "$APPLOG") Reranking failed, $(grep -cE ' (WARN|ERROR) ' "$APPLOG") WARN or ERROR lines"
pkill -f spring-boot:run; sleep 3; pgrep -f "spring-boot:run|StockRecommendationEngineApplication" >/dev/null && { pkill -f spring-boot:run; pkill -f StockRecommendationEngineApplication; sleep 3; }
say "$(now) stopped; listeners on 8081: $(lsof -nP -iTCP:8081 -sTCP:LISTEN | wc -l | tr -d ' '); processes: $(pgrep -f 'spring-boot:run|StockRecommendationEngineApplication' | wc -l | tr -d ' ')"
cp "$APPLOG" "$SCRATCH/app-session-kept.log"

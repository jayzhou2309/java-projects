#!/bin/bash
# The grid runs of one store after its pool-40 reranking-off run (plan plans/2026-09-17-chunk-size-pool.md, Frozen design): pools 100, 200, 250 with reranking off
# at the default rerank settings, then pools 40, 100, 200, 250 with reranking on, rerank-candidates 40 and rerank-timeout-ms 4000.
# usage: run_grid.sh <store letter a|b> <first|rest> [ingestion overrides for store b ...]   (from the repository root; TOKEN and SCRATCH exported)
STORE="$1"; PART="$2"; shift 2
HERE="src/main/java/documentation/live-runs/2026-09-17-chunk-size-pool"
if [ "$PART" = first ]; then
  "$HERE/run_eval.sh" "$STORE-pool-40-rerank-off" false "$@" || exit $?
  exit 0
fi
for POOL in 100 200 250; do
  "$HERE/run_eval.sh" "$STORE-pool-$POOL-rerank-off" false RAG_RETRIEVAL_CANDIDATE_COUNT=$POOL RAG_RETRIEVAL_KEYWORD_CANDIDATE_COUNT=$POOL "$@" || exit $?
done
for POOL in 40 100 200 250; do
  "$HERE/run_eval.sh" "$STORE-pool-$POOL-rerank-on-40" true RAG_RETRIEVAL_CANDIDATE_COUNT=$POOL RAG_RETRIEVAL_KEYWORD_CANDIDATE_COUNT=$POOL RAG_RETRIEVAL_RERANK_CANDIDATES=40 RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000 "$@" || exit $?
done

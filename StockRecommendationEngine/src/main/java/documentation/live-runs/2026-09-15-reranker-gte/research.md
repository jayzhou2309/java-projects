# Reranker model research (Hugging Face search, 2026-09-15; Orchestrator's research agent; nothing downloaded)

Model facts from each repo's config.json and /api/models/<id>/tree/main; downloads are HF API 30-day counts on 2026-09-15.

## Finance-tuned cross-encoders: none credible
- emericklaf/bge-reranker-finqa-tatqa-finetuned — bge-reranker-base 278M; no licence; "Unnamed Dataset" 42,285 pairs (likely FinQA/TAT-QA); no evaluation; no ONNX. https://huggingface.co/emericklaf/bge-reranker-finqa-tatqa-finetuned
- tolivert/financial-reranker-v1 — MiniLM-L6 BERT; no licence, no README, no evaluation. https://huggingface.co/tolivert/financial-reranker-v1
- filmonfeme/financial-chatbot-reranker — ms-marco-MiniLM-L6-v2; 2,000 pairs (US tax code); no licence/eval. https://huggingface.co/filmonfeme/financial-chatbot-reranker
- jefffreyli/financial-reranker — XLM-R 278M; template card; no licence/eval. https://huggingface.co/jefffreyli/financial-reranker
- kkresearch/bge-reranker-v2-m3-korean-finance — apache-2.0; Korean; no eval. https://huggingface.co/kkresearch/bge-reranker-v2-m3-korean-finance
- alirezaaminzadeh/SecReranker is cybersecurity, not SEC filings.
- FinMTEB (https://arxiv.org/abs/2502.10990) ranks embedding models only.
- Financial retrieval papers use general rerankers: FinRank (https://arxiv.org/html/2608.07400, SEC 10-K/10-Q): ms-marco-MiniLM-L-6-v2 MRR 79.8, TF-IDF 80.4, e5-mistral-7b 83.6. FinanceRAG (https://arxiv.org/html/2411.16732): bge-reranker-v2-m3 / jina-reranker-v2 / gte-multilingual-reranker per dataset, no fine-tuning.

## General rerankers (selected)
- cross-encoder/ms-marco-MiniLM-L-6-v2 (current): BERT 22.7M, 6x384, 512, apache-2.0.
- cross-encoder/ettin-reranker-32m-v1 / 68m / 150m: ModernBERT 10x384 / 19x512 / 22x768; 8k; apache-2.0; official onnx/model.onnx. https://huggingface.co/cross-encoder/ettin-reranker-32m-v1
- Alibaba-NLP/gte-reranker-modernbert-base: ModernBERT 150M; 8192; apache-2.0; ONNX incl. int8.
- ibm-granite/granite-embedding-reranker-english-r2: ModernBERT 150M; apache-2.0.
- BAAI/bge-reranker-base (XLM-R 278M, MIT), -large (560M), -v2-m3 (568M, apache-2.0).
- mixedbread-ai/mxbai-rerank-base-v1 / xsmall-v1: DeBERTa-v2; apache-2.0.
- jinaai/jina-reranker-v2-base-multilingual: CC-BY-NC-4.0 (non-commercial) — excluded.

## Benchmarks touching finance (not evidence for SEC filings)
- NVIDIA (https://arxiv.org/html/2409.07691v1, Table 1), FiQA NDCG@10, top-100 reranked after snowflake-arctic-embed-l (0.4471 alone): jina-v2 0.4511, mxbai-large-v1 0.4396, bge-v2-m3 0.4332, MiniLM-L-12 0.3850.
- Ettin blog (https://huggingface.co/blog/ettin-reranker), MTEB(eng, v2) Retrieval 10 tasks incl. FiQA2018, mean NDCG@10 over 6 retrievers, self-reported: ettin-150m 0.5994, ettin-68m 0.5915, gte-modernbert 0.5843, ettin-32m 0.5779, granite-r2 0.5656, bge-v2-m3 0.5526, bge-large 0.5098, MiniLM-L6 0.5082, MiniLM-L12 0.5066, bge-base 0.4890, mxbai-base-v1 0.4865.
- IBM granite card BEIR average: granite 55.8, gte-modernbert 56.1, bge-large 54.3, MiniLM-L12 53.2, bge-base 53.0.

## CPU cost relative to MiniLM-L-6 (derived from architecture, not measured; per 512-token window)
MiniLM-L-12 ~2x; ettin-32m ~1.2x; ettin-68m ~4x; 150M ModernBERT ~10x; bge-base-class ~7.6x; bge-large / v2-m3 ~27x.

## Recommendation (inference)
1. cross-encoder/ettin-reranker-32m-v1 (68m if budget allows). 2. gte-reranker-modernbert-base (too slow for 40x4 windows). 3. bge-reranker-v2-m3 (used in financial RAG work; ~27x cost).

## File check by the Orchestrator (2026-09-15, repo commit b33e5ceb5110773ea9cf5e00c9bedc83a8c2afdd)
- tokenizer.json: model BPE; post_processor TemplateProcessing, pair [CLS] A [SEP] B [SEP], all type_id 0; [CLS] 50281, [SEP] 50282, [PAD] 50283; padding null; truncation {Right, 7999, LongestFirst}.
- config.json: architectures ModernBertModel, max_position_embeddings 7999, local_attention 128, global_attn_every_n_layers 3, pad_token_id 50283, id2label {0: LABEL_0}.
- Project ONNX Runtime 1.29.0, DJL tokenizers 0.38.0 (pom.xml).

## Note added with Milestone 1 (2026-09-15, Worker)
- Copied unchanged above from the Orchestrator's scratch notes of 2026-09-15. The model the plan first chose from these notes, `cross-encoder/ettin-reranker-32m-v1`, was downloaded but cannot score pairs as approved: its `onnx/model.onnx` outputs `last_hidden_state` [batch, sequence, 384] and lacks its scoring head, which lives in separate Sentence-Transformers module files. Jay chose `Alibaba-NLP/gte-reranker-modernbert-base` instead; see plan `plans/2026-09-15-reranker-ettin.md`, Status, amendment 1.
- The CPU cost figures above are architecture-derived estimates (labelled so above), not measurements; this milestone's scoring-time observations for the chosen model are in RAG.md, Second reranker model.

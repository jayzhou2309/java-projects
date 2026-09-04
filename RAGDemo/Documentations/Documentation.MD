# Stock Recommendation Engine

An AI-powered stock analysis system combining:

- SEC EDGAR filings
- PostgreSQL + pgvector
- Spring AI
- RAG
- Quantitative market indicators
- LLM-based analysis

## Architecture
## High-Level Flow

MAG 7 -> Stock Database -> SEC CIK -> SEC EDGAR -> Filings -> Document Parsing -> Chunking -> Embeddings -> pgvector
-> RAG -> LLM -> Stock Analysis

## Current Status

- [x] PostgreSQL + pgvector
- [x] Spring AI RAG pipeline
- [x] Stock entity
- [x] Filing entity
- [ ] SEC EDGAR ingestion
- [ ] Market data
- [ ] Quant indicators
- [ ] Recommendation engine

## Tech Stack

Java, Spring Boot, Spring AI, PostgreSQL, pgvector, Flyway, Docker
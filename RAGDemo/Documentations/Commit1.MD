### Configuration

SEC Filings retrival is controlled via application.yaml
- filing types
- filings per type

Stock CIK -> SEC API -> Recent Filings -> Filtered by Filing Type -> Take configured number 
of recent filings -> FilingMetaData

### Filing Documents
App does not need to download filing documents, instead it reads from HTMl
- SEC Filing MetaData -> Filing asscession number -> primary document
-> SEC Archival URL -> HTML Document

SEC HTML -> Text extraction -> Chunking -> Embedding -> PGVector -> RAG -> LLM

* SECClient — communicates with SEC and retrieves filing data.
* FilingMetadata — represents filing information returned by SEC.
* SecIngestionService — will later determine which filings need to be stored and ingested.
* Filing — stores filing metadata in PostgreSQL.
* Spring AI / pgvector — stores the searchable embedded content.

Current Flow:
PostgresSQL - StockRepo - All 7 Stocks - SEC Client - SEC Submissions API - FilingMetaData - Duplicate Check
New Filing - Create Filing Entity - PostgresSQL - Build SEC Source URL - Fetch SEC HTML - SEC HTML Parser - 
Clean Text - Spring AI Document + Metadata - TokenTextSplitter - List<Document>

Current Problems:
Netty is not working - Switching to JDK HTTP
package project.stockrecommendationengine.rag.ingestion;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.EmbeddedFilingChunk;
import project.stockrecommendationengine.rag.dto.FilingChunkData;
import project.stockrecommendationengine.rag.dto.FilingSection;
import project.stockrecommendationengine.rag.dto.SECFilingMetadata;
import project.stockrecommendationengine.rag.entity.FilingChunk;
import project.stockrecommendationengine.rag.entity.SECFiling;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class FilingIngestionService {

    public static final String PROCESSING_VERSION = "sections-v2-context-v2";
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SECClient secClient;
    private final FilingHtmlParser filingHtmlParser;
    private final FilingChunker filingChunker;
    private final FilingEmbeddingService filingEmbeddingService;
    private final SECFilingRepository filingRepository;

    private final PlatformTransactionManager transactionManager;

    public void ingest(String ticker, List<String> filingTypes, int limit) {
        String normalizedTicker = ticker.trim().toUpperCase(Locale.ROOT);
        log.info("Finding SEC filings: ticker={}, filingTypes={}, limit={}", normalizedTicker, filingTypes, limit);
        List<SECFilingMetadata> filings = secClient.getRecentFilings(
                normalizedTicker, filingTypes, limit);
        log.info("Found {} matching filings for ticker={}", filings.size(), normalizedTicker);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (SECFilingMetadata metadata : filings) ingestOne(metadata);
    }

    /** Ingest one filing in its own transaction; a failure is recorded as FAILED and rethrown. */
    public void ingestOne(SECFilingMetadata metadata) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        {
            long started = System.nanoTime();
            log.info("Processing filing: ticker={}, accession={}, type={}, date={}",
                    metadata.ticker(), metadata.accessionNo(), metadata.filingType(), metadata.filingDate());
            try {
                // executeWithoutResult returns only after commit, so commit failures
                // are caught here too. Earlier filings have their own committed transactions.
                transaction.executeWithoutResult(status -> { lock(metadata.accessionNo()); ingestFiling(metadata, false); });
                log.info("Filing transaction committed: ticker={}, accession={}, elapsedMs={}",
                        metadata.ticker(), metadata.accessionNo(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException failure) {
                if (failure instanceof org.springframework.web.server.ResponseStatusException) throw failure;
                log.warn("Filing ingestion failed; recording failure: ticker={}, accession={}, elapsedMs={}",
                        metadata.ticker(), metadata.accessionNo(), (System.nanoTime() - started) / 1_000_000);
                try {
                    transaction.executeWithoutResult(status -> {
                        lock(metadata.accessionNo());
                        SECFiling filing = findOrCreate(metadata);
                        // Do not overwrite a successful concurrent ingestion.
                        if (!isComplete(filing)) {
                            filing.setIngestionStatus("FAILED");
                            filing.setIngestionError(failure.getMessage());
                            filingRepository.saveAndFlush(filing);
                            log.info("Failure record flushed: accession={}", metadata.accessionNo());
                        }
                    });
                    log.info("Failure-record transaction committed: accession={}", metadata.accessionNo());
                } catch (RuntimeException recordingFailure) {
                    log.error("Could not persist failure record: accession={}", metadata.accessionNo(), recordingFailure);
                    failure.addSuppressed(recordingFailure);
                }
                throw failure;
            }
        }
    }

    private void lock(String accession) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, accession);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Filing is already being processed");
        }
    }

    public java.util.Map<String, Object> rebuild(long filingId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String runId = java.util.UUID.randomUUID().toString();
        try {
            return tx.execute(status -> {
                var filing = filingRepository.findById(filingId).orElseThrow(() ->
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND, "Filing not found"));
                lock(filing.getAccessionNo());
                int previousCount = filing.getChunks().size();
                String previousVersion = filing.getProcessingVersion();
                var metadata = new SECFilingMetadata(filing.getTicker(), filing.getCik(), filing.getAccessionNo(),
                        filing.getFilingType(), filing.getFilingDate(), filing.getReportDate(),
                        filing.getPrimaryDocument(), filing.getSourceUrl());
                ingestFiling(metadata, true);
                int count = filing.getChunks().size();
                jdbc.update("""
                        INSERT INTO filing_rebuild_runs(run_id, filing_id, processing_version, outcome,
                            previous_version, previous_chunks, resulting_chunks)
                        VALUES (?, ?, ?, 'SUCCEEDED', ?, ?, ?)
                        """, runId, filingId, PROCESSING_VERSION, previousVersion, previousCount, count);
                log.info("Filing rebuild succeeded: runId={}, filingId={}, chunks={}, version={}",
                        runId, filingId, count, PROCESSING_VERSION);
                return java.util.Map.<String, Object>of("runId", runId, "filingId", filingId,
                        "processingVersion", PROCESSING_VERSION, "chunks", count, "outcome", "SUCCEEDED");
            });
        } catch (org.springframework.web.server.ResponseStatusException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            try {
                tx.executeWithoutResult(status -> jdbc.update("""
                        INSERT INTO filing_rebuild_runs(run_id, filing_id, processing_version, outcome, error_code)
                        VALUES (?, ?, ?, 'FAILED', ?)
                        """, runId, filingId, PROCESSING_VERSION, failure.getClass().getSimpleName()));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            log.error("Filing rebuild failed: runId={}, filingId={}; previous content retained", runId, filingId);
            throw failure;
        }
    }

    private boolean isComplete(SECFiling filing) {
        return "EMBEDDED".equals(filing.getIngestionStatus()) && !filing.getChunks().isEmpty();
    }

    private SECFiling findOrCreate(SECFilingMetadata metadata) {
        return filingRepository.findByAccessionNo(metadata.accessionNo())
                .orElseGet(() -> SECFiling.builder()
                        .ticker(metadata.ticker())
                        .cik(metadata.cik())
                        .accessionNo(metadata.accessionNo())
                        .filingType(metadata.filingType())
                        .filingDate(metadata.filingDate())
                        .reportDate(metadata.reportDate())
                        .primaryDocument(metadata.primaryDocument())
                        .sourceUrl(metadata.sourceUrl())
                        .build());
    }

    private void ingestFiling(SECFilingMetadata metadata, boolean force) {
        log.info("Checking stored filing: accession={}", metadata.accessionNo());
        SECFiling filing = findOrCreate(metadata);
        if (!force && isComplete(filing)) {
            log.info("Skipping already embedded filing: accession={}, chunks={}",
                    metadata.accessionNo(), filing.getChunks().size());
            return;
        }

        log.info("Downloading filing HTML: accession={}", metadata.accessionNo());
        String html = secClient.fetchFilingHTML(metadata.sourceUrl());
        log.info("Parsing filing HTML: accession={}, characters={}",
                metadata.accessionNo(), html == null ? 0 : html.length());
        List<FilingSection> sections = filingHtmlParser.parse(html);
        log.info("Parsed filing: accession={}, sections={}; chunking sections", metadata.accessionNo(), sections.size());
        List<FilingChunkData> chunks = filingChunker.chunk(sections);
        log.info("Chunking completed: accession={}, chunks={}", metadata.accessionNo(), chunks.size());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No filing content could be extracted: " + metadata.accessionNo());
        }
        log.info("Embedding filing chunks: accession={}, chunks={}", metadata.accessionNo(), chunks.size());
        List<EmbeddedFilingChunk> embeddedChunks = filingEmbeddingService.embedChunks(chunks);
        if (embeddedChunks.size() != chunks.size()) {
            throw new IllegalStateException("Embedding result count does not match filing chunks");
        }

        log.info("Embeddings completed: accession={}, chunks={}", metadata.accessionNo(), embeddedChunks.size());
        log.info("Saving filing: accession={}, replacingChunks={}, newChunks={}",
                metadata.accessionNo(), filing.getChunks().size(), embeddedChunks.size());
        // Delete old retry chunks before inserting replacements with the same indexes.
        filing.getChunks().clear();
        filingRepository.saveAndFlush(filing);
        filing.getChunks().addAll(toEntities(filing, embeddedChunks));
        filing.setProcessingVersion(PROCESSING_VERSION);
        filing.setIngestionStatus("EMBEDDED");
        filing.setIngestionError(null);
        filingRepository.saveAndFlush(filing);
        log.info("Filing and chunks flushed; awaiting commit: accession={}", metadata.accessionNo());
    }

    private List<FilingChunk> toEntities(
            SECFiling filing,
            List<EmbeddedFilingChunk> embeddedChunks
    ) {
        return embeddedChunks.stream()
                .map(embedded -> {
                    FilingChunkData chunk = embedded.chunkData();
                    return FilingChunk.builder()
                            .filing(filing)
                            .chunkIndex(chunk.chunkIndex())
                            .sectionKey(chunk.sectionKey())
                            .sectionTitle(chunk.sectionTitle())
                            .sectionChunkIndex(
                                    chunk.sectionChunkIndex()
                            )
                            .content(chunk.content())
                            .startChar(chunk.startChar())
                            .endChar(chunk.endChar())
                            .tokenCount(chunk.tokenCount())
                            .embedding(embedded.embedding())
                            .build();
                })
                .toList();
    }
}

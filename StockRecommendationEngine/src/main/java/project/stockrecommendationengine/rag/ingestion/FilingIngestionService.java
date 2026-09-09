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

        for (SECFilingMetadata metadata : filings) {
            long started = System.nanoTime();
            log.info("Processing filing: ticker={}, accession={}, type={}, date={}",
                    metadata.ticker(), metadata.accessionNo(), metadata.filingType(), metadata.filingDate());
            try {
                // executeWithoutResult returns only after commit, so commit failures
                // are caught here too. Earlier filings have their own committed transactions.
                transaction.executeWithoutResult(status -> ingestFiling(metadata));
                log.info("Filing transaction committed: ticker={}, accession={}, elapsedMs={}",
                        metadata.ticker(), metadata.accessionNo(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException failure) {
                log.warn("Filing ingestion failed; recording failure: ticker={}, accession={}, elapsedMs={}",
                        metadata.ticker(), metadata.accessionNo(), (System.nanoTime() - started) / 1_000_000);
                try {
                    transaction.executeWithoutResult(status -> {
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

    private void ingestFiling(SECFilingMetadata metadata) {
        log.info("Checking stored filing: accession={}", metadata.accessionNo());
        SECFiling filing = findOrCreate(metadata);
        if (isComplete(filing)) {
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

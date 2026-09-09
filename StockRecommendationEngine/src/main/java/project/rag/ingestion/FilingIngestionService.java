package project.rag.ingestion;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.rag.dto.EmbeddedFilingChunk;
import project.rag.dto.FilingChunkData;
import project.rag.dto.FilingSection;
import project.rag.dto.SECFilingMetadata;
import project.rag.entity.FilingChunk;
import project.rag.entity.SECFiling;
import project.rag.repository.SECFilingRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FilingIngestionService {

    private final SECClient secClient;
    private final FilingHtmlParser filingHtmlParser;
    private final FilingChunker filingChunker;
    private final FilingEmbeddingService filingEmbeddingService;
    private final SECFilingRepository filingRepository;

    @Transactional
    public void ingest(
            String ticker,
            List<String> filingTypes,
            int limit
    ) {
        String normalizedTicker = ticker.toUpperCase();
        String cik = secClient.resolveCik(normalizedTicker);
        List<SECFilingMetadata> filings =
                secClient.getRecentFilings(
                        normalizedTicker,
                        filingTypes, limit
                );
        for (SECFilingMetadata metadata : filings) {
            if (filingRepository.existsByAccessionNo(metadata.accessionNo())){
                continue;
            }
            ingestFiling(
                    normalizedTicker,
                    cik,
                    metadata
            );
        }
    }

    private void ingestFiling(String ticker, String cik, SECFilingMetadata metadata){
        SECFiling filing = SECFiling.builder()
                .ticker(ticker)
                .cik(cik)
                .accessionNo(metadata.accessionNo())
                .filingType(metadata.filingType())
                .filingDate(metadata.filingDate())
                .reportDate(metadata.reportDate())
                .primaryDocument(metadata.primaryDocument())
                .sourceUrl(metadata.sourceUrl())
                .ingestionStatus("PENDING")
                .build();
        filingRepository.save(filing);

        try {
            // 1. SEC HTML
            String html = secClient.fetchFilingHTML(metadata.sourceUrl());
            filing.setIngestionStatus("FETCHED");

            // 2. HTML -> Sementic SEC Sections
            List<FilingSection> sections = filingHtmlParser.parse(html);
            filing.setIngestionStatus("PARSED");

            // 3. Sections -> chunks
            List<FilingChunkData> chunks = filingChunker.chunk(sections);
            filing.setIngestionStatus("CHUNKED");

            // 4. Chunks -> Embeddings
            List<EmbeddedFilingChunk> embeddedChunks = filingEmbeddingService.embedChunks(chunks);

            // 5. DTO -> JPA Entities
            List<FilingChunk> chunkEntities = toEntities(filing, embeddedChunks);

            // 6. Attach chunks to filing
            filing.getChunks().addAll(chunkEntities);
            filing.setIngestionStatus("EMBEDDED");

            filingRepository.save(filing);

        } catch (Exception e) {
            filing.setIngestionStatus("FAILED");
            filing.setIngestionError(e.getMessage());
            filingRepository.save(filing);
            throw e;
        }

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

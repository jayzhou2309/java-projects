package project.stockrecommendationengine.rag.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.stockrecommendationengine.rag.dto.SECFilingMetadata;
import project.stockrecommendationengine.rag.dto.EmbeddedFilingChunk;
import project.stockrecommendationengine.rag.dto.FilingChunkData;
import project.stockrecommendationengine.rag.entity.FilingChunk;
import project.stockrecommendationengine.rag.entity.SECFiling;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class FilingIngestionServiceTests {
    @Autowired FilingIngestionService service;
    @Autowired SECFilingRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean SECClient sec;
    @MockitoBean FilingEmbeddingService embeddings;
    private TransactionTemplate transaction;
    private final List<String> accessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        when(sec.fetchFilingHTML(anyString())).thenReturn("<div>Item 1. Business</div><div>Business content.</div>");
        when(embeddings.embedChunks(anyList())).thenAnswer(invocation -> {
            List<FilingChunkData> chunks = invocation.getArgument(0);
            return chunks.stream().map(chunk -> new EmbeddedFilingChunk(chunk, new float[1536])).toList();
        });
    }

    @AfterEach
    void removeTestFilings() {
        transaction.executeWithoutResult(tx -> accessions.forEach(accession ->
                repository.findByAccessionNo(accession).ifPresent(repository::delete)));
    }

    private SECFilingMetadata metadata() {
        String accession = UUID.randomUUID().toString().replace("-", "");
        accessions.add(accession);
        return new SECFilingMetadata("TEST", "0000000001", accession, "10-K", LocalDate.now(),
                null, "test.htm", "https://example.invalid/" + accession);
    }

    private void assertFiling(SECFilingMetadata metadata, String status, int chunks) {
        transaction.executeWithoutResult(tx -> {
            var filing = repository.findByAccessionNo(metadata.accessionNo()).orElseThrow();
            assertThat(filing.getIngestionStatus()).isEqualTo(status);
            assertThat(filing.getChunks()).hasSize(chunks);
            if (status.equals("FAILED")) {
                assertThat(filing.getIngestionError()).isNotBlank();
            } else {
                assertThat(filing.getIngestionError()).isNull();
            }
        });
    }

    @Test
    void previousFilingSurvivesFailureAndFailedFilingCanBeRetried() {
        var first = metadata();
        var second = metadata();
        when(sec.getRecentFilings("TEST", List.of("10-K"), 2)).thenReturn(List.of(first, second));
        when(sec.fetchFilingHTML(second.sourceUrl())).thenThrow(new IllegalStateException("SEC unavailable"));
        assertThatThrownBy(() -> service.ingest(" test ", List.of("10-K"), 2))
                .hasMessage("SEC unavailable");
        assertFiling(first, "EMBEDDED", 1);
        assertFiling(second, "FAILED", 0);

        doReturn("<p>Item 1. Business</p><p>Retried content.</p>").when(sec).fetchFilingHTML(second.sourceUrl());
        service.ingest("TEST", List.of("10-K"), 2);
        assertFiling(first, "EMBEDDED", 1);
        assertFiling(second, "EMBEDDED", 1);
        verify(sec, times(1)).fetchFilingHTML(first.sourceUrl());
    }

    @Test
    void emptyExtractionIsRecordedAsFailureWithoutEmbeddingCalls() {
        var filing = metadata();
        when(sec.getRecentFilings("TEST", List.of("10-K"), 1)).thenReturn(List.of(filing));
        when(sec.fetchFilingHTML(filing.sourceUrl())).thenReturn("<div>No section headings</div>");
        assertThatThrownBy(() -> service.ingest("TEST", List.of("10-K"), 1))
                .hasMessageContaining("No filing content");
        assertFiling(filing, "FAILED", 0);
        verifyNoInteractions(embeddings);
    }

    @Test
    void databaseWriteFailureRollsBackChunksButKeepsFailureRecord() {
        var filing = metadata();
        when(sec.getRecentFilings("TEST", List.of("10-K"), 1)).thenReturn(List.of(filing));
        when(embeddings.embedChunks(anyList())).thenAnswer(invocation -> {
            List<FilingChunkData> chunks = invocation.getArgument(0);
            return chunks.stream().map(chunk -> new EmbeddedFilingChunk(chunk, new float[3])).toList();
        });
        assertThatThrownBy(() -> service.ingest("TEST", List.of("10-K"), 1)).isInstanceOf(RuntimeException.class);
        assertFiling(filing, "FAILED", 0);
    }

    @Test
    void retryReplacesExistingChunksWithoutUniqueIndexConflict() {
        var metadata = metadata();
        transaction.executeWithoutResult(tx -> {
            var filing = SECFiling.builder().ticker(metadata.ticker()).cik(metadata.cik())
                    .accessionNo(metadata.accessionNo()).filingType(metadata.filingType())
                    .filingDate(metadata.filingDate()).sourceUrl(metadata.sourceUrl())
                    .ingestionStatus("FAILED").ingestionError("Old failure").build();
            filing.getChunks().add(FilingChunk.builder().filing(filing).chunkIndex(0)
                    .content("Old partial content").build());
            repository.saveAndFlush(filing);
        });
        when(sec.getRecentFilings("TEST", List.of("10-K"), 1)).thenReturn(List.of(metadata));
        service.ingest("TEST", List.of("10-K"), 1);
        assertFiling(metadata, "EMBEDDED", 1);
        transaction.executeWithoutResult(tx -> assertThat(repository.findByAccessionNo(metadata.accessionNo())
                .orElseThrow().getChunks().get(0).getContent()).isEqualTo("Business content."));
    }

    @Test
    void legacyEmptySuccessIsRetried() {
        var metadata = metadata();
        repository.saveAndFlush(SECFiling.builder().ticker(metadata.ticker()).cik(metadata.cik())
                .accessionNo(metadata.accessionNo()).filingType(metadata.filingType())
                .filingDate(metadata.filingDate()).sourceUrl(metadata.sourceUrl()).ingestionStatus("EMBEDDED").build());
        when(sec.getRecentFilings("TEST", List.of("10-K"), 1)).thenReturn(List.of(metadata));
        service.ingest("TEST", List.of("10-K"), 1);
        assertFiling(metadata, "EMBEDDED", 1);
        verify(sec).fetchFilingHTML(metadata.sourceUrl());
    }
}

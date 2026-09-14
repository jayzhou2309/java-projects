package project.stockrecommendationengine.rag.evaluation;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stored chunk text read for the evaluation evidence report; read-only. */
@Repository
@RequiredArgsConstructor
public class EvidenceChunkRepository {
    private final JdbcTemplate jdbc;

    /** A stored chunk's id and content as read at report time. */
    public record StoredChunk(long id, String content) {
    }

    /** Every stored chunk of the filing with this accession number whose section key is {@code sectionKey}, ordered by id. */
    public List<StoredChunk> chunks(String accessionNo, String sectionKey) {
        return jdbc.query("""
                SELECT c.id, c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id
                WHERE f.accession_no = ? AND c.section_key = ? ORDER BY c.id
                """, (rs, i) -> new StoredChunk(rs.getLong("id"), rs.getString("content")), accessionNo, sectionKey);
    }
}

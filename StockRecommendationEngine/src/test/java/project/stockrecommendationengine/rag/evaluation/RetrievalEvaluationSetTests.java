package project.stockrecommendationengine.rag.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import static org.assertj.core.api.Assertions.*;

/**
 * The bundled set against the real store: counts and coverage, and that every expected passage is a verbatim excerpt
 * (case-insensitive, whitespace-normalised) of a stored chunk in the named filing and section. Read-only.
 */
@SpringBootTest
class RetrievalEvaluationSetTests {
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired JdbcTemplate jdbc;

    @Test void setHasTheExpectedSizeCoverageAndDistinctPhrases() {
        var set = loader.load();
        assertThat(set.version()).isEqualTo("v1");
        assertThat(set.createdOn()).isNotNull();
        assertThat(set.questions()).hasSizeBetween(24, 30);
        assertThat(set.questions()).extracting(RetrievalEvaluationQuestion::id).doesNotHaveDuplicates();
        for (String ticker : List.of("AAPL", "MSFT", "NVDA")) {
            assertThat(set.questions().stream().filter(q -> q.ticker().equals(ticker)).count()).as("questions for " + ticker).isGreaterThanOrEqualTo(8);
        }
        assertThat(set.questions().stream().filter(q -> q.kind() == Kind.FIGURE).count()).as("figure questions").isGreaterThanOrEqualTo(6);
        var phrases = set.questions().stream().flatMap(q -> q.expected().stream()).map(ExpectedPassage::phrase).toList();
        assertThat(phrases).doesNotHaveDuplicates();
        var sections = set.questions().stream().flatMap(q -> q.expected().stream()).map(ExpectedPassage::sectionKey).toList();
        assertThat(sections).contains("ITEM_1A", "ITEM_7", "ITEM_8", "ITEM_1", "ITEM_7_01");
    }

    @Test void everyExpectedPassageIsAVerbatimExcerptOfAStoredChunk() {
        var set = loader.load();
        List<String> misses = new ArrayList<>();
        for (var question : set.questions()) {
            for (var passage : question.expected()) {
                List<String> contents = jdbc.queryForList("""
                        SELECT c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id
                        WHERE f.accession_no = ? AND c.section_key = ?
                        """, String.class, passage.accessionNo(), passage.sectionKey());
                String wanted = normalise(passage.phrase());
                boolean found = contents.stream().anyMatch(content -> normalise(content).contains(wanted));
                if (!found) misses.add(question.id() + " -> " + passage + " (" + contents.size() + " chunks in that filing and section)");
            }
        }
        assertThat(misses).as("expected passages not found in the store").isEmpty();
    }

    static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}

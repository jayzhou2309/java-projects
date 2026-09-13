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
 * The bundled sets against the real store: counts and coverage, and that every expected passage is a verbatim excerpt
 * (case-insensitive, whitespace-normalised) of a stored chunk in the named filing and section. One check runs over the
 * set rag.evaluation.set selects, another explicitly over v1, so both stay honest. Read-only.
 */
@SpringBootTest
class RetrievalEvaluationSetTests {
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired RetrievalEvaluationProperties properties;
    @Autowired JdbcTemplate jdbc;

    @Test void theSelectedSetHasTheExpectedSizeCoverageAndDistinctPhrases() {
        var set = loader.load();
        assertThat(set).isEqualTo(loader.load(properties.getSet()));
        assertThat(set.version()).isNotBlank();
        assertThat(set.createdOn()).isNotNull();
        assertThat(set.questions()).hasSizeBetween(24, 44);
        assertCoverage(set);
    }

    @Test void theDefaultSelectionIsSetV2() {
        assertThat(properties.getSet()).isEqualTo(RetrievalEvaluationSetLoader.DEFAULT_RESOURCE);
        var set = loader.load();
        assertThat(set.version()).isEqualTo("v2");
        assertThat(set.questions()).hasSizeBetween(40, 44);
    }

    @Test void theBoundHitAt5FloorIsTheSetV2Derivation() {
        // Set v2 winner snapshot 69: hit@5 0.785714 - 0.1 = 0.685714, rounded down to a multiple of 0.05.
        assertThat(properties.getMinHitAt5()).isEqualByComparingTo("0.65");
    }

    @Test void everyExpectedPassageOfTheSelectedSetIsAVerbatimExcerptOfAStoredChunk() {
        var set = loader.load();
        assertThat(misses(set)).as("expected passages of set %s (%s) not found in the store", set.version(), properties.getSet()).isEmpty();
    }

    @Test void everyExpectedPassageOfSetV1IsAVerbatimExcerptOfAStoredChunk() {
        var set = loader.load(RetrievalEvaluationSetLoader.V1_RESOURCE);
        assertThat(set.version()).isEqualTo("v1");
        assertThat(set.questions()).hasSize(30);
        assertCoverage(set);
        assertThat(misses(set)).as("expected passages of set v1 not found in the store").isEmpty();
    }

    /** One entry per expectation with no chunk of that accession and section containing the phrase: question id, the expectation, and the chunk count. */
    private List<String> misses(RetrievalEvaluationSet set) {
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
        return misses;
    }

    private static void assertCoverage(RetrievalEvaluationSet set) {
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

    static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}

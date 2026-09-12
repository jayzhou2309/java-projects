package project.stockrecommendationengine.rag.evaluation;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import static org.assertj.core.api.Assertions.*;

/** Shape rules and failure modes of the loader, exercised on in-memory JSON; the bundled resource is checked against the store elsewhere. */
class RetrievalEvaluationSetLoaderTests {
    private final RetrievalEvaluationSetLoader loader = new RetrievalEvaluationSetLoader();

    @Test void parsesAWellFormedSetIntoRecords() {
        var set = loader.parse(set(
                question("q-1", "AAPL", "FIGURE", "What was the effective tax rate?", "\"notes\": \"MD&A tax table\"",
                        expectation("0000320193-25-000079", "ITEM_7", "Effective tax rate 15.6 % 24.1 % 14.7 %")),
                question("q-2", "BRK.B", "NARRATIVE", "Which risks matter?", null,
                        expectation("0000320193-25-000079", "ITEM_1A", "first distinctive phrase"),
                        expectation("0000320193-26-000020", "ITEM_1A", "second distinctive phrase"))));
        assertThat(set.version()).isEqualTo("v1");
        assertThat(set.createdOn()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(set.questions()).hasSize(2);
        var first = set.questions().get(0);
        assertThat(first.id()).isEqualTo("q-1");
        assertThat(first.ticker()).isEqualTo("AAPL");
        assertThat(first.kind()).isEqualTo(Kind.FIGURE);
        assertThat(first.question()).isEqualTo("What was the effective tax rate?");
        assertThat(first.notes()).isEqualTo("MD&A tax table");
        assertThat(first.expected()).containsExactly(new ExpectedPassage("0000320193-25-000079", "ITEM_7", "Effective tax rate 15.6 % 24.1 % 14.7 %"));
        var second = set.questions().get(1);
        assertThat(second.notes()).isNull();
        assertThat(second.kind()).isEqualTo(Kind.NARRATIVE);
        assertThat(second.expected()).hasSize(2);
    }

    @Test void bundledResourceLoadsAndSatisfiesTheShapeRules() {
        var set = loader.load();
        assertThat(set.version()).isEqualTo("v1");
        assertThat(set.createdOn()).isNotNull();
        assertThat(set.questions()).hasSizeBetween(24, 30);
        assertThat(set.questions()).extracting(RetrievalEvaluationQuestion::id).doesNotHaveDuplicates();
        for (var question : set.questions()) {
            assertThat(question.ticker()).as(question.id()).matches("[A-Z0-9.-]{1,16}");
            assertThat(question.question()).as(question.id()).isNotBlank().hasSizeLessThanOrEqualTo(4000);
            assertThat(question.kind()).as(question.id()).isNotNull();
            assertThat(question.expected()).as(question.id()).isNotEmpty();
            for (var passage : question.expected()) {
                assertThat(passage.accessionNo()).as(question.id()).matches("\\d{10}-\\d{2}-\\d{6}");
                assertThat(passage.sectionKey()).as(question.id()).isNotBlank().hasSizeLessThanOrEqualTo(64);
                assertThat(passage.phrase()).as(question.id()).hasSizeBetween(12, 200);
            }
        }
    }

    @Test void duplicateQuestionIdIsRejectedByName() {
        String json = set(question("dup-1", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "phrase number one")),
                question("dup-1", "AAPL", "FIGURE", "Two?", null, expectation("0000320193-25-000079", "ITEM_7", "phrase number two")));
        assertThatThrownBy(() -> loader.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("dup-1").hasMessageContaining("Duplicate");
    }

    @Test void emptyExpectedListIsRejectedByName() {
        String json = set(question("no-expect", "AAPL", "FIGURE", "One?", null));
        assertThatThrownBy(() -> loader.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no-expect");
    }

    @Test void blankPhraseIsRejectedByName() {
        String json = set(question("blank-phrase", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "      ")));
        assertThatThrownBy(() -> loader.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("blank-phrase").hasMessageContaining("blank phrase");
    }

    @Test void phraseLengthBoundsAreEnforced() {
        String tooShort = set(question("short", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "eleven char")));
        assertThatThrownBy(() -> loader.parse(tooShort)).isInstanceOf(IllegalStateException.class).hasMessageContaining("short");
        String tooLong = set(question("long", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "x".repeat(201))));
        assertThatThrownBy(() -> loader.parse(tooLong)).isInstanceOf(IllegalStateException.class).hasMessageContaining("long");
        assertThat(loader.parse(set(question("edge", "AAPL", "FIGURE", "One?", null,
                expectation("0000320193-25-000079", "ITEM_7", "x".repeat(12)), expectation("0000320193-25-000079", "ITEM_7", "y".repeat(200)))))
                .questions()).hasSize(1);
    }

    @Test void invalidTickerAccessionSectionKindAndQuestionAreRejectedByName() {
        assertThatThrownBy(() -> loader.parse(set(question("bad-ticker", "aapl", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-ticker").hasMessageContaining("ticker");
        assertThatThrownBy(() -> loader.parse(set(question("bad-acc", "AAPL", "FIGURE", "One?", null, expectation("320193-25-79", "ITEM_7", "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-acc").hasMessageContaining("accession");
        assertThatThrownBy(() -> loader.parse(set(question("bad-section", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "S".repeat(65), "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-section").hasMessageContaining("section key");
        assertThatThrownBy(() -> loader.parse(set(question("bad-kind", "AAPL", "NUMBER", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-kind").hasMessageContaining("kind");
        assertThatThrownBy(() -> loader.parse(set(question("blank-q", "AAPL", "FIGURE", "  ", null, expectation("0000320193-25-000079", "ITEM_7", "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank-q");
        assertThatThrownBy(() -> loader.parse(set(question("long-q", "AAPL", "FIGURE", "q".repeat(4001), null, expectation("0000320193-25-000079", "ITEM_7", "a distinctive phrase")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("long-q").hasMessageContaining("4000");
    }

    @Test void twoQuestionsSharingAPhraseAreRejectedByName() {
        String json = set(question("first", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "the very same phrase")),
                question("second", "AAPL", "FIGURE", "Two?", null, expectation("0000320193-25-000079", "ITEM_8", "the very same phrase")));
        assertThatThrownBy(() -> loader.parse(json)).isInstanceOf(IllegalStateException.class).hasMessageContaining("second").hasMessageContaining("phrase");
    }

    @Test void setLevelFieldsAreRequired() {
        String q = question("q-1", "AAPL", "FIGURE", "One?", null, expectation("0000320193-25-000079", "ITEM_7", "a distinctive phrase"));
        assertThatThrownBy(() -> loader.parse("{\"createdOn\": \"2026-09-12\", \"questions\": [" + q + "]}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> loader.parse("{\"version\": \"v1\", \"createdOn\": \"not a date\", \"questions\": [" + q + "]}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("createdOn");
        assertThatThrownBy(() -> loader.parse("{\"version\": \"v1\", \"createdOn\": \"2026-09-12\", \"questions\": []}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("questions");
    }

    private static String set(String... questions) {
        return "{\"version\": \"v1\", \"createdOn\": \"2026-09-12\", \"questions\": [" + String.join(",", questions) + "]}";
    }

    private static String question(String id, String ticker, String kind, String text, String extra, String... expectations) {
        return "{\"id\": \"" + id + "\", \"ticker\": \"" + ticker + "\", \"kind\": \"" + kind + "\", \"question\": \"" + text + "\", "
                + (extra == null ? "" : extra + ", ") + "\"expected\": [" + String.join(",", expectations) + "]}";
    }

    private static String expectation(String accessionNo, String sectionKey, String phrase) {
        return "{\"accessionNo\": \"" + accessionNo + "\", \"sectionKey\": \"" + sectionKey + "\", \"phrase\": \"" + phrase + "\"}";
    }
}

package project.stockrecommendationengine.rag.evaluation;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;
import static org.assertj.core.api.Assertions.*;

/**
 * Shape rules and failure modes of the loader, exercised on in-memory JSON, plus resource selection through
 * rag.evaluation.set and the content invariants of the bundled sets; the bundled resources are checked against the store
 * elsewhere.
 */
class RetrievalEvaluationSetLoaderTests {
    private final RetrievalEvaluationSetLoader loader = new RetrievalEvaluationSetLoader(new RetrievalEvaluationProperties());

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

    @Test void theDefaultPropertySelectsSetV2WithinItsBoundsAndShapeRules() {
        assertThat(new RetrievalEvaluationProperties().getSet()).isEqualTo("evaluation/retrieval-set-v2.json");
        var set = loader.load();
        assertThat(set.version()).isEqualTo("v2");
        assertThat(set.createdOn()).isEqualTo(LocalDate.parse("2026-09-13"));
        assertThat(set.questions()).hasSizeBetween(40, 44);
        assertShapeRules(set);
    }

    @Test void thePropertyCanSelectSetV1AndTheParameterisedEntryPointMatches() {
        var properties = new RetrievalEvaluationProperties();
        properties.setSet("evaluation/retrieval-set-v1.json");
        var v1 = new RetrievalEvaluationSetLoader(properties).load();
        assertThat(v1.version()).isEqualTo("v1");
        assertThat(v1.createdOn()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(v1.questions()).hasSize(30);
        assertShapeRules(v1);
        assertThat(loader.load(RetrievalEvaluationSetLoader.V1_RESOURCE)).isEqualTo(v1);
        assertThat(loader.load(RetrievalEvaluationSetLoader.DEFAULT_RESOURCE)).isEqualTo(loader.load());
    }

    @Test void aMissingOrBlankResourceFailsClearlyNamingIt() {
        var properties = new RetrievalEvaluationProperties();
        properties.setSet("evaluation/retrieval-set-v9.json");
        assertThatThrownBy(() -> new RetrievalEvaluationSetLoader(properties).load())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("evaluation/retrieval-set-v9.json").hasMessageContaining("not found");
        assertThatThrownBy(() -> loader.load("evaluation/does-not-exist.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("evaluation/does-not-exist.json");
        assertThatThrownBy(() -> loader.load("  ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("rag.evaluation.set");
    }

    @Test void theSetPropertyMustNotBeBlank() {
        try (ValidatorFactory validators = Validation.buildDefaultValidatorFactory()) {
            var properties = new RetrievalEvaluationProperties();
            assertThat(validators.getValidator().validate(properties)).isEmpty();
            properties.setSet(" ");
            assertThat(validators.getValidator().validate(properties)).extracting(v -> v.getPropertyPath().toString()).containsExactly("set");
        }
    }

    @Test void setV2CarriesFigureQuestionsPerTickerAndTheV1QuestionsUnchanged() {
        var v2 = loader.load();
        var v1 = loader.load(RetrievalEvaluationSetLoader.V1_RESOURCE);

        // A question triggers the figure leg only when figureTerms is non-empty: a numeric token that is not a lone year.
        var withFigures = v2.questions().stream().filter(q -> !FilingRetrievalRepository.figureTerms(q.question()).isEmpty()).toList();
        assertThat(withFigures).as("questions whose text carries a figure").hasSizeGreaterThanOrEqualTo(10);
        for (String ticker : List.of("AAPL", "MSFT", "NVDA")) {
            assertThat(withFigures.stream().filter(q -> q.ticker().equals(ticker)).count()).as("figure questions for " + ticker).isGreaterThanOrEqualTo(3);
            assertThat(v2.questions().stream().filter(q -> q.ticker().equals(ticker)).count()).as("questions for " + ticker).isGreaterThanOrEqualTo(8);
        }
        assertThat(withFigures).allSatisfy(q -> assertThat(q.kind()).as(q.id()).isEqualTo(Kind.FIGURE));
        // The rule is the repository's own: a year alone, or a year beside words, never counts.
        assertThat(FilingRetrievalRepository.figureTerms("What was revenue in fiscal 2026?")).isEmpty();

        Map<String, RetrievalEvaluationQuestion> byId = v2.questions().stream().collect(Collectors.toMap(RetrievalEvaluationQuestion::id, Function.identity()));
        for (String id : List.of("nvda-01", "nvda-03")) {
            assertThat(byId.get(id).expected()).as("alternative expectations for " + id).hasSizeGreaterThanOrEqualTo(2);
        }
        // Plan Amendment 1: only 10-K Item 1A states the manufacturing and final-assembly concentration, so nvda-07 has no alternative.
        assertThat(byId.get("nvda-07").expected()).as("nvda-07 keeps its single v1 expectation").hasSize(1);
        for (var original : v1.questions()) {
            var carried = byId.get(original.id());
            assertThat(carried).as("v1 question " + original.id() + " in v2").isNotNull();
            assertThat(carried.question()).as(original.id()).isEqualTo(original.question());
            assertThat(carried.ticker()).as(original.id()).isEqualTo(original.ticker());
            assertThat(carried.kind()).as(original.id()).isEqualTo(original.kind());
            assertThat(carried.expected()).as("v2 keeps every v1 expectation of " + original.id()).containsAll(original.expected());
        }
        for (var added : v2.questions().stream().filter(q -> v1.questions().stream().noneMatch(o -> o.id().equals(q.id()))).toList()) {
            assertThat(added.id()).matches(added.ticker().toLowerCase(java.util.Locale.ROOT) + "-\\d{2}");
            assertThat(added.kind()).as(added.id()).isEqualTo(Kind.FIGURE);
            assertThat(FilingRetrievalRepository.figureTerms(added.question())).as("figure terms of new question " + added.id()).isNotEmpty();
            assertThat(added.expected()).as(added.id()).noneMatch(p -> normalise(p.phrase()).equals(normalise(added.question())));
        }
    }

    @Test void rewordedFigureQuestionsDoNotEchoTheirPhraseButKeepTheirFigures() {
        Map<String, RetrievalEvaluationQuestion> byId = loader.load().questions().stream()
                .collect(Collectors.toMap(RetrievalEvaluationQuestion::id, Function.identity()));
        for (String id : List.of("msft-11", "msft-12", "msft-14", "nvda-13")) {
            var question = byId.get(id);
            assertThat(question).as(id).isNotNull();
            assertThat(FilingRetrievalRepository.figureTerms(question.question())).as("figure terms of " + id).isNotEmpty();
            for (var passage : question.expected()) {
                assertThat(longestSharedWordRun(question.question(), passage.phrase()))
                        .as(id + " shares a run of words with its phrase: " + passage.phrase()).isLessThan(5);
            }
        }
        assertThat(longestSharedWordRun("The Quick, brown fox jumps over", "a quick brown FOX jumps!")).isEqualTo(4);
    }

    /** Length of the longest run of consecutive words common to both texts, case-insensitive with punctuation ignored. */
    private static int longestSharedWordRun(String first, String second) {
        String[] a = words(first);
        String[] b = words(second);
        int longest = 0;
        int[][] runs = new int[a.length + 1][b.length + 1];
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    runs[i][j] = runs[i - 1][j - 1] + 1;
                    longest = Math.max(longest, runs[i][j]);
                }
            }
        }
        return longest;
    }

    private static String[] words(String text) {
        String stripped = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
        return stripped.isEmpty() ? new String[0] : stripped.split("\\s+");
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void assertShapeRules(RetrievalEvaluationSet set) {
        assertThat(set.questions()).extracting(RetrievalEvaluationQuestion::id).doesNotHaveDuplicates();
        assertThat(set.questions().stream().flatMap(q -> q.expected().stream()).map(ExpectedPassage::phrase).toList()).doesNotHaveDuplicates();
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

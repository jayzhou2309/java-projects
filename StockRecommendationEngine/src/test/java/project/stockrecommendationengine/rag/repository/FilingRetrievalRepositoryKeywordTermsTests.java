package project.stockrecommendationengine.rag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static project.stockrecommendationengine.rag.repository.FilingRetrievalRepository.keywordTerms;

class FilingRetrievalRepositoryKeywordTermsTests {

    @Test
    void foldsCaseQuotesEachTokenDropsStopwordsAndKeepsFirstAppearanceOrder() {
        String terms = keywordTerms(
                "What were Apple's Greater China net sales in fiscal 2025, and how did they compare?");
        assertThat(terms).isEqualTo("'apple' | 'greater' | 'china' | 'net' | 'sales' | 'fiscal' | '2025' | 'compare'");
        assertThat(terms.split(" \\| ")).doesNotHaveDuplicates()
                .contains("'greater'", "'china'", "'net'", "'sales'", "'fiscal'", "'2025'")
                .allMatch(term -> term.startsWith("'") && term.endsWith("'"))
                .noneMatch(term -> term.contains("apple's") || term.contains("what") || term.contains("were"));
    }

    @Test
    void deduplicatesCaseInsensitivelyAndDropsSingleCharacterTokens() {
        assertThat(keywordTerms("Revenue revenue REVENUE grew; a b c 7 %")).isEqualTo("'revenue' | 'grew'");
    }

    @Test
    void keepsCommasAndPeriodsOnlyBetweenDigits() {
        assertThat(keywordTerms("Greater China 64,377 (4) % and $40.4 billion in 2025. U.S. sales"))
                .isEqualTo("'greater' | 'china' | '64,377' | '40.4' | 'billion' | '2025' | 'sales'");
        assertThat(keywordTerms("Revenue $ 215,938 $ 130,497 Up 65%"))
                .isEqualTo("'revenue' | '215,938' | '130,497' | '65'");
    }

    @Test
    void stopwordOnlyPunctuationOnlyBlankAndNullQueriesYieldEmptyTerms() {
        assertThat(keywordTerms("the and of")).isEmpty();
        assertThat(keywordTerms("The AND Of it's")).isEmpty();
        assertThat(keywordTerms("?!, ... --- $ %")).isEmpty();
        assertThat(keywordTerms("   ")).isEmpty();
        assertThat(keywordTerms("")).isEmpty();
        assertThat(keywordTerms(null)).isEmpty();
    }

    @Test
    void embeddedQuotesCannotEscapeTheTsqueryLiteral() {
        // Tokens are alphanumeric so a quote can never be inside one; the OR-join carries no raw user text.
        String terms = keywordTerms("'; DROP TABLE sec_filing_chunks; -- ' | 'x' & !'y'");
        assertThat(terms).isEqualTo("'drop' | 'table' | 'sec' | 'filing' | 'chunks'");
        assertThat(terms).doesNotContain(";", "--", "&", "!");
    }
}

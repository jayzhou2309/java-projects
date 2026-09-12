package project.stockrecommendationengine.rag.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static project.stockrecommendationengine.rag.repository.FilingRetrievalRepository.figureTerms;
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

    // Fusion tuning Milestone 1 (RAG-12), C1: the figure leg's AND string.

    @Test
    void figureTermsAndJoinTheNumericTokensInQueryOrderKeepingYearsBesideOtherFigures() {
        assertThat(figureTerms("fiscal 2026 revenue 215,938 up 65%")).isEqualTo("'2026' & '215,938' & '65'");
        assertThat(figureTerms("Greater China net sales 64,377")).isEqualTo("'64,377'");
        assertThat(figureTerms("Revenue $ 215,938 $ 130,497 Up 65% and $40.4 billion in 2025. U.S. sales"))
                .isEqualTo("'215,938' & '130,497' & '65' & '40.4' & '2025'");
        // Exactly the numeric tokens keywordTerms keeps, quoted the same way: a percent or dollar sign is never part of one.
        assertThat(keywordTerms("fiscal 2026 revenue 215,938 up 65%"))
                .isEqualTo("'fiscal' | '2026' | 'revenue' | '215,938' | '65'");
        assertThat(figureTerms("65% then 65 % again, 2026 and 2026")).isEqualTo("'65' & '2026'");
        // Outside 1900 to 2100 a four-digit token is a figure, not a year.
        assertThat(figureTerms("in 1899 and 2101")).isEqualTo("'1899' & '2101'");
        assertThat(figureTerms("1900 shares and 2100 options")).isEmpty();
    }

    @Test
    void figureTermsAreEmptyForYearsOnlyNoNumbersSingleDigitsOrBlankQueries() {
        assertThat(figureTerms("risks in fiscal 2025")).isEmpty();
        assertThat(figureTerms("compare fiscal 2024, 2025 and 2026")).isEmpty();
        assertThat(figureTerms("no numbers here")).isEmpty();
        assertThat(figureTerms("a 7 % rise in fiscal 2025")).isEmpty();
        assertThat(figureTerms("?!, ... --- $ %")).isEmpty();
        assertThat(figureTerms("   ")).isEmpty();
        assertThat(figureTerms("")).isEmpty();
        assertThat(figureTerms(null)).isEmpty();
    }

    @Test
    void figureTermsCarryNoRawQueryText() {
        String terms = figureTerms("'; DROP TABLE sec_filing_chunks; -- 42 | 'x' & !'y' 2025");
        assertThat(terms).isEqualTo("'42' & '2025'");
        assertThat(terms).doesNotContain(";", "--", "|", "!", "drop");
    }

    @Test
    void embeddedQuotesCannotEscapeTheTsqueryLiteral() {
        // Tokens are alphanumeric so a quote can never be inside one; the OR-join carries no raw user text.
        String terms = keywordTerms("'; DROP TABLE sec_filing_chunks; -- ' | 'x' & !'y'");
        assertThat(terms).isEqualTo("'drop' | 'table' | 'sec' | 'filing' | 'chunks'");
        assertThat(terms).doesNotContain(";", "--", "&", "!");
    }
}

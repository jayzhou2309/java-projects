package project.stockrecommendationengine.rag.ingestion;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import project.stockrecommendationengine.rag.dto.FilingSection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two-pattern experiment of plan 2026-09-17-chunk-size.md, Milestone 4 (Follow_Ups RAG-29, contract H2): the item heading pattern of
 * {@link FilingHtmlParser} before and after commit 19876f2 (2026-09-12), applied to the heading strings of the stored 8-K, 10-K, and 10-Q
 * sections, with the key built as the parser builds it ({@code "ITEM_" + group 1 upper-cased with '.' replaced by '_'}). The 8-K headings
 * key ITEM_2, ITEM_9, ITEM_5 under the old pattern and ITEM_2_02, ITEM_9_01, ITEM_5_02 under the current one; the 10-K and 10-Q headings key
 * the same under both. The parser's pattern is private, so the current pattern is repeated here as a string and the last test proves that
 * the parser itself keys these headings as the repeated pattern does. No parser change.
 */
class FilingHtmlParserItemKeyTests {
    /** FilingHtmlParser.ITEM_PATTERN before commit 19876f2 (the pattern that keyed the AAPL 8-Ks ingested on 2026-09-10). */
    static final Pattern BEFORE_19876F2 = Pattern.compile("(?i)^item\\s+(\\d+[a-z]?)\\.?\\s*(.*)$");
    /** FilingHtmlParser.ITEM_PATTERN since commit 19876f2 (2026-09-12): the optional {@code .NN} sub-item is kept in group 1. */
    static final Pattern SINCE_19876F2 = Pattern.compile("(?i)^item\\s+(\\d+[a-z]?(?:\\.\\d+)?)\\.?\\s*(.*)$");

    @ParameterizedTest(name = "{0}: old {1}, current {2}")
    @CsvSource(delimiter = '|', value = {
            "Item 2.02 Results of Operations and Financial Condition | ITEM_2 | ITEM_2_02",
            "Item 9.01 Financial Statements and Exhibits | ITEM_9 | ITEM_9_01",
            "Item 5.02 Departure of Directors or Certain Officers | ITEM_5 | ITEM_5_02",
            "Item 1A. Risk Factors | ITEM_1A | ITEM_1A",
            "Item 7. Management's Discussion | ITEM_7 | ITEM_7"})
    void theOldAndCurrentPatternsKeyTheHeading(String heading, String oldKey, String currentKey) {
        assertThat(key(BEFORE_19876F2, heading)).as("before 19876f2: %s", heading).isEqualTo(oldKey);
        assertThat(key(SINCE_19876F2, heading)).as("since 19876f2: %s", heading).isEqualTo(currentKey);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Item 2.02 Results of Operations and Financial Condition | ITEM_2_02",
            "Item 9.01 Financial Statements and Exhibits | ITEM_9_01",
            "Item 5.02 Departure of Directors or Certain Officers | ITEM_5_02",
            "Item 1A. Risk Factors | ITEM_1A",
            "Item 7. Management's Discussion | ITEM_7"})
    void theParserInTheTreeKeysTheHeadingAsTheCurrentPatternDoes(String heading, String currentKey) {
        List<FilingSection> sections = new FilingHtmlParser().parse("<p>" + heading + "</p><p>Body text.</p>");
        assertThat(sections).extracting(FilingSection::sectionKey).containsExactly(currentKey);
        assertThat(currentKey).isEqualTo(key(SINCE_19876F2, heading));
    }

    /** The parser's key rule: {@code "ITEM_" + group 1 upper-cased with '.' replaced by '_'}; null when the pattern does not match. */
    static String key(Pattern pattern, String heading) {
        Matcher matcher = pattern.matcher(heading);
        if (!matcher.matches()) return null;
        return "ITEM_" + matcher.group(1).toUpperCase(Locale.ROOT).replace('.', '_');
    }
}

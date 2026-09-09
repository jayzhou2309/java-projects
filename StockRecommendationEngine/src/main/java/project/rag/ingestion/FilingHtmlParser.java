package project.rag.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import project.rag.dto.FilingSection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FilingHtmlParser {

    private static final Pattern ITEM_PATTERN = Pattern.compile(
            "(?i)^item\\s+(\\d+[a-z]?)\\.?\\s*(.*)$"
    );

    private static final int MAX_HEADING_LENGTH = 250;

    public List<FilingSection> parse(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException(
                    "SEC filing HTML cannot be empty"
            );
        }

        Document document = Jsoup.parse(html);

        cleanDocument(document);

        return extractSections(document);
    }

    private void cleanDocument(Document document) {
        document.select(
                "script, style, noscript, svg, nav, header, footer"
        ).remove();
    }

    private List<FilingSection> extractSections(
            Document document
    ) {
        List<FilingSection> sections = new ArrayList<>();

        String currentSectionKey = null;
        String currentSectionTitle = null;

        StringBuilder currentContent = new StringBuilder();

        /*
         * Avoid div/span because they frequently contain the same
         * text as their child elements, causing duplicated content.
         *
         * SEC filings commonly place useful content inside:
         * - paragraphs
         * - table cells
         * - headings
         */
        for (Element element :
                document.select("h1, h2, h3, h4, h5, h6, p, td")) {

            String text = normalize(element.text());

            if (text.isBlank()) {
                continue;
            }

            Matcher matcher = ITEM_PATTERN.matcher(text);

            if (isSectionHeading(text, matcher)) {

                addSection(
                        sections,
                        currentSectionKey,
                        currentSectionTitle,
                        currentContent
                );

                String itemNumber =
                        matcher.group(1)
                                .toUpperCase();

                currentSectionKey =
                        "ITEM_" + itemNumber;

                String title = matcher.group(2).trim();

                currentSectionTitle =
                        title.isBlank()
                                ? null
                                : title;

                currentContent = new StringBuilder();

                continue;
            }

            if (currentSectionKey != null) {
                appendContent(
                        currentContent,
                        text
                );
            }
        }

        // Add final section
        addSection(
                sections,
                currentSectionKey,
                currentSectionTitle,
                currentContent
        );

        return sections;
    }

    private boolean isSectionHeading(
            String text,
            Matcher matcher
    ) {
        return text.length() <= MAX_HEADING_LENGTH
                && matcher.matches();
    }

    private void addSection(
            List<FilingSection> sections,
            String sectionKey,
            String sectionTitle,
            StringBuilder content
    ) {
        if (sectionKey == null || content.isEmpty()) {
            return;
        }

        String normalizedContent =
                content.toString().trim();

        if (normalizedContent.isBlank()) {
            return;
        }

        sections.add(
                new FilingSection(
                        sectionKey,
                        sectionTitle,
                        normalizedContent
                )
        );
    }

    private void appendContent(
            StringBuilder content,
            String text
    ) {
        if (text.isBlank()) {
            return;
        }

        /*
         * Prevent immediately repeated blocks.
         *
         * SEC tables sometimes produce identical adjacent cells.
         */
        int length = content.length();

        if (length > 0) {
            int previousLineStart =
                    content.lastIndexOf(
                            "\n",
                            Math.max(0, length - 2)
                    );

            String previousLine =
                    content.substring(
                            previousLineStart + 1
                    ).trim();

            if (previousLine.equals(text)) {
                return;
            }
        }

        content.append(text)
                .append("\n");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
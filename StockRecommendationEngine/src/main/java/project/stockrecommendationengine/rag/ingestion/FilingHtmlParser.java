package project.stockrecommendationengine.rag.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import java.util.Locale;
import org.springframework.stereotype.Component;
import project.stockrecommendationengine.rag.dto.FilingSection;

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

        for (String block : textBlocks(document)) {
            String text = normalize(block);

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
                                .toUpperCase(Locale.ROOT);

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

        content.append(text)
                .append("\n");
    }

    // Visit each text node once; block boundaries separate headings and paragraphs
    // while inline spans remain part of the surrounding text.
    private List<String> textBlocks(Document document) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        document.body().traverse(new NodeVisitor() {
            private void flush() {
                if (!block.isEmpty()) {
                    blocks.add(block.toString());
                    block.setLength(0);
                }
            }

            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element element
                        && (element.isBlock() || element.normalName().equals("br"))) {
                    flush();
                }
                if (node instanceof TextNode text) {
                    block.append(text.getWholeText());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock()) {
                    flush();
                }
            }
        });
        if (!block.isEmpty()) {
            blocks.add(block.toString());
        }
        return blocks;
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
package project.rag.ingestion;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class FilingHtmlParser {
    private static final Pattern ITEM_PATTERN = Pattern.compile(
            "(?i)^item\\s+(\\d+[a-z]?)\\.?\\s*(.*)$"
    );

    public List<FilingSection> parse(String html){
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("SEC Filing HTML cannot be empty");
        }

        Document document = Jsoup.parse(html);
        cleanDocument(document);
        return extractSections(document);
    }

    private void cleanDocument(Document document){
        document.select("script, style, noscript, svg").remove();
    }

    private List<FilingSection> extractSections(
            Document document
    ) {
        List<FilingSection> sections =
                new ArrayList<>();
        String currentSectionKey = null;
        String currentSectionTitle = null;
        StringBuilder currentContent =
                new StringBuilder();
        for (Element element :
                document.select("p, div, span, td")) {
            String text =
                    normalize(element.text());
            if (text.isBlank()) {
                continue;
            }
            Matcher matcher =
                    ITEM_PATTERN.matcher(text);
            if (matcher.matches() &&
                    text.length() < 250) {
                if (currentSectionKey != null &&
                        !currentContent.isEmpty()) {
                    sections.add(
                            new FilingSection(
                                    currentSectionKey,
                                    currentSectionTitle,
                                    currentContent
                                            .toString()
                                            .trim()
                            )
                    );
                }
                String itemNumber =
                        matcher.group(1)
                                .toUpperCase();
                currentSectionKey =
                        "ITEM_" +
                                itemNumber.replace(".", "");
                currentSectionTitle =
                        matcher.group(2).trim();
                currentContent =
                        new StringBuilder();
                continue;
            }
            if (currentSectionKey != null) {
                currentContent
                        .append(text)
                        .append("\n");
            }
        }
        if (currentSectionKey != null &&
                !currentContent.isEmpty()) {
            sections.add(
                    new FilingSection(
                            currentSectionKey,
                            currentSectionTitle,
                            currentContent
                                    .toString()
                                    .trim()
                    )
            );
        }
        return sections;
    }

    private String normalize(String value){
        return value
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}

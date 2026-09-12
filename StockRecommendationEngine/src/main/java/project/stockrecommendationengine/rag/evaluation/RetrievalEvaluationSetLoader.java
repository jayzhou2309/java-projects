package project.stockrecommendationengine.rag.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the retrieval evaluation set from the classpath and validates its shape. Every violation is reported as an
 * IllegalStateException that names the offending question id, so a broken set fails fast at load time rather than
 * silently skewing a metric. Nothing loaded here reaches a model prompt.
 */
@Component
public class RetrievalEvaluationSetLoader {
    public static final String RESOURCE = "evaluation/retrieval-set-v1.json";
    static final int MAX_QUESTION_LENGTH = 4000;
    static final int MAX_SECTION_KEY_LENGTH = 64;
    static final int MIN_PHRASE_LENGTH = 12;
    static final int MAX_PHRASE_LENGTH = 200;
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9.-]{1,16}");
    private static final Pattern ACCESSION_NO = Pattern.compile("\\d{10}-\\d{2}-\\d{6}");
    private final JsonMapper json = JsonMapper.builder().build();

    /** Loads and validates the bundled set. */
    public RetrievalEvaluationSet load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read retrieval evaluation set " + RESOURCE, e);
        }
    }

    /** Parses and validates a set from JSON text; used by load() and by tests on in-memory documents. */
    public RetrievalEvaluationSet parse(String content) {
        JsonNode root = json.readTree(content);
        if (!root.isObject()) throw new IllegalStateException("Retrieval evaluation set must be a JSON object");
        String version = requireText(root, "version", "set");
        LocalDate createdOn = parseDate(requireText(root, "createdOn", "set"));
        JsonNode questionsNode = root.path("questions");
        if (!questionsNode.isArray() || questionsNode.isEmpty())
            throw new IllegalStateException("Retrieval evaluation set must contain a non-empty questions array");
        Set<String> ids = new HashSet<>();
        Set<String> phrases = new HashSet<>();
        List<RetrievalEvaluationQuestion> questions = new ArrayList<>();
        for (JsonNode node : questionsNode) {
            RetrievalEvaluationQuestion question = parseQuestion(node);
            if (!ids.add(question.id())) throw new IllegalStateException("Duplicate question id " + question.id());
            for (ExpectedPassage passage : question.expected()) {
                if (!phrases.add(passage.phrase()))
                    throw new IllegalStateException("Question " + question.id() + " repeats a phrase already expected by another question: " + passage.phrase());
            }
            questions.add(question);
        }
        return new RetrievalEvaluationSet(version, createdOn, List.copyOf(questions));
    }

    private RetrievalEvaluationQuestion parseQuestion(JsonNode node) {
        if (!node.isObject()) throw new IllegalStateException("Every question must be a JSON object");
        String id = node.path("id").asString("").trim();
        if (id.isEmpty()) throw new IllegalStateException("A question is missing its id");
        String ticker = requireText(node, "ticker", id);
        if (!TICKER.matcher(ticker).matches()) throw new IllegalStateException("Question " + id + " has an invalid ticker: " + ticker);
        Kind kind = parseKind(requireText(node, "kind", id), id);
        String question = requireText(node, "question", id);
        if (question.length() > MAX_QUESTION_LENGTH)
            throw new IllegalStateException("Question " + id + " is longer than " + MAX_QUESTION_LENGTH + " characters");
        JsonNode expectedNode = node.path("expected");
        if (!expectedNode.isArray() || expectedNode.isEmpty())
            throw new IllegalStateException("Question " + id + " must expect at least one passage");
        List<ExpectedPassage> expected = new ArrayList<>();
        for (JsonNode passage : expectedNode) expected.add(parseExpectation(passage, id));
        String notes = node.path("notes").isString() ? node.path("notes").stringValue() : null;
        return new RetrievalEvaluationQuestion(id, ticker, kind, question, List.copyOf(expected), notes);
    }

    private ExpectedPassage parseExpectation(JsonNode node, String id) {
        if (!node.isObject()) throw new IllegalStateException("Question " + id + " has an expectation that is not a JSON object");
        String accessionNo = requireText(node, "accessionNo", id);
        if (!ACCESSION_NO.matcher(accessionNo).matches())
            throw new IllegalStateException("Question " + id + " has an invalid accession number: " + accessionNo);
        String sectionKey = requireText(node, "sectionKey", id);
        if (sectionKey.length() > MAX_SECTION_KEY_LENGTH)
            throw new IllegalStateException("Question " + id + " has a section key longer than " + MAX_SECTION_KEY_LENGTH + " characters");
        String phrase = node.path("phrase").asString("");
        if (phrase.isBlank()) throw new IllegalStateException("Question " + id + " has a blank phrase");
        if (phrase.length() < MIN_PHRASE_LENGTH || phrase.length() > MAX_PHRASE_LENGTH)
            throw new IllegalStateException("Question " + id + " has a phrase outside " + MIN_PHRASE_LENGTH + " to "
                    + MAX_PHRASE_LENGTH + " characters: " + phrase);
        return new ExpectedPassage(accessionNo, sectionKey, phrase);
    }

    private static Kind parseKind(String value, String id) {
        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Question " + id + " has an unknown kind: " + value);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Retrieval evaluation set has an invalid createdOn date: " + value, e);
        }
    }

    private static String requireText(JsonNode node, String field, String owner) {
        String value = node.path(field).asString("").trim();
        if (value.isEmpty()) throw new IllegalStateException(("set".equals(owner) ? "Retrieval evaluation set" : "Question " + owner)
                + " is missing " + field);
        return value;
    }
}

package project.stockrecommendationengine.rag.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * A scripted {@link PassageTokenizer} for tests: every maximal run of non-whitespace characters is one token, with its UTF-16 span, so
 * token positions can be computed by hand ("w000 w001 w002" is tokens 0, 1, 2). No model files or native library.
 */
public final class ScriptedWordTokenizer implements PassageTokenizer {
    private final String modelVersion;
    private final List<String> tokenized = new ArrayList<>();

    public ScriptedWordTokenizer(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    @Override
    public Tokens tokenize(String text) {
        tokenized.add(text);
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            spans.add(new int[] {start, i});
        }
        return new Tokens(spans.stream().mapToInt(span -> span[0]).toArray(), spans.stream().mapToInt(span -> span[1]).toArray());
    }

    /** Every text tokenized so far, in call order. */
    public List<String> tokenized() {
        return tokenized;
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    /** {@code count} tokens {@code w000 w001 ...} separated by single spaces. */
    public static String words(int count) {
        return words(0, count);
    }

    /** Tokens {@code w<from>} to {@code w<to - 1>}, zero-padded to three digits (four from 1,000), separated by single spaces. */
    public static String words(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int index = from; index < to; index++) {
            if (index > from) out.append(' ');
            out.append(String.format(index < 1000 ? "w%03d" : "w%04d", index));
        }
        return out.toString();
    }
}

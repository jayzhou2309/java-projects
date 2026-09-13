package project.stockrecommendationengine.rag.retrieval;

/**
 * Java-side bounds on the text handed to the cross-encoder's native tokenizer, so native work per call is bounded whatever a
 * request carries: null becomes empty text, text longer than {@link #MAX_CHARS} UTF-16 units is cut (never between the two
 * halves of a surrogate pair), and an unpaired surrogate (possible in JSON such as a lone escaped high surrogate) is replaced with U+FFFD so
 * the native side only ever receives well-formed Unicode. Imports nothing from DJL or ONNX Runtime.
 * <p>
 * Token lengths are not decided here: the query and each passage are tokenized alone without truncation and cut to the model
 * window in Java ({@link CrossEncoderPairAssembler}). The bound keeps that untruncated tokenization cheap: it is linear in the text,
 * about 25 ms for 20,000 CJK characters (20,000 tokens) and 10 ms for 20,000 characters of {@code "a "} (10,000 tokens) on the
 * development Mac, so a call with a query and 40 bounded passages spends about 1 s tokenizing. It is five times the API's
 * 4,000-character query limit and ten times a 2,000-character chunk, so ordinary text reaches its first 512 tokens long before
 * it; only text made mostly of characters that yield no token (whitespace, combining marks) can be cut before that.
 */
final class CrossEncoderInputBounds {
    /** Longest query or passage passed to the tokenizer, in UTF-16 code units. The API caps a query at 4,000. */
    static final int MAX_CHARS = 20_000;

    private CrossEncoderInputBounds() {
    }

    static String bound(String text) {
        return bound(text, MAX_CHARS);
    }

    static String bound(String text, int maxChars) {
        if (text == null) return "";
        int end = Math.min(text.length(), maxChars);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        StringBuilder out = null;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            // A low surrogate reached here is unpaired: a paired one is consumed with its high surrogate below.
            boolean paired = Character.isHighSurrogate(c) ? i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1))
                    : !Character.isLowSurrogate(c);
            if (paired) {
                if (out != null) out.append(c);
                if (Character.isHighSurrogate(c)) {
                    if (out != null) out.append(text.charAt(i + 1));
                    i++;
                }
            } else {
                if (out == null) out = new StringBuilder(end).append(text, 0, i);
                out.append('\uFFFD');
            }
        }
        if (out != null) return out.toString();
        return end == text.length() ? text : text.substring(0, end);
    }
}

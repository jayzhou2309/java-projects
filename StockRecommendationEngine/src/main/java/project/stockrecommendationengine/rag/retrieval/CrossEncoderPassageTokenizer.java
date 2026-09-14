package project.stockrecommendationengine.rag.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link PassageTokenizer} over the cross-encoder's {@code tokenizer.json}, built by the same
 * {@link OnnxCrossEncoderScorer#singleSequenceTokenizer} the scorer encodes with and called the same way
 * ({@code encode(text, false, false)}), so its token count for a text equals the scorer's. The native tokenizer reports character
 * spans as Unicode code point offsets (a supplementary character such as an emoji is one position; measured 2026-09-13,
 * {@code CrossEncoderPassageTokenizerLiveTests}); {@link CrossEncoderTokenPositions#utf16Spans} converts them to UTF-16 offsets. Constructed only by
 * {@link CrossEncoderConfiguration}, after the scorer, so the scorer's native-library checks and DJL runtime defaults already apply.
 */
final class CrossEncoderPassageTokenizer implements PassageTokenizer {
    private final HuggingFaceTokenizer tokenizer;
    private final String modelVersion;
    /** Read-held by every tokenize call, write-held by close. */
    private final ReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    CrossEncoderPassageTokenizer(Path tokenizerPath, String modelVersion) throws IOException {
        OnnxCrossEncoderScorer.requireBundledTokenizerLibrary();
        this.tokenizer = OnnxCrossEncoderScorer.singleSequenceTokenizer(tokenizerPath);
        this.modelVersion = modelVersion;
    }

    @Override
    public Tokens tokenize(String text) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Cross-encoder tokenizer is closed");
            Encoding encoding = tokenizer.encode(text, false, false);
            CharSpan[] spans = encoding.getCharTokenSpans();
            int count = encoding.getIds().length;
            if (spans == null || spans.length != count) {
                throw new IllegalStateException("Tokenizer reported " + (spans == null ? "no" : spans.length) + " character spans for " + count + " tokens");
            }
            int[] starts = new int[count];
            int[] ends = new int[count];
            for (int token = 0; token < count; token++) {
                if (spans[token] == null) throw new IllegalStateException("Tokenizer reported no character span for token " + token);
                starts[token] = spans[token].getStart();
                ends[token] = spans[token].getEnd();
            }
            return CrossEncoderTokenPositions.utf16Spans(text, starts, ends);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            tokenizer.close();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}

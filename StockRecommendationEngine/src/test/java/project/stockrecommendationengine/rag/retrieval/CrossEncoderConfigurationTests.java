package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Bean creation for the cross-encoder: nothing (and no ONNX Runtime or tokenizer class) when disabled; startup failure naming
 * the path for a missing file or a checksum mismatch when enabled. Uses temp files, never the real model.
 */
class CrossEncoderConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(CrossEncoderConfiguration.class)
            .withBean(CrossEncoderProperties.class);

    @TempDir Path temp;

    @Test
    void disabledByDefaultCreatesNoRerankerAndLoadsNoOnnxOrTokenizerClass() throws Exception {
        assertThat(new CrossEncoderProperties().isEnabled()).isFalse();
        Set<String> before = loadedNativeBindingClasses();
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilingReranker.class)
                .doesNotHaveBean(PairScorer.class).doesNotHaveBean(CrossEncoderModelFiles.class).doesNotHaveBean(PassageTokenizer.class)
                .doesNotHaveBean(CrossEncoderConfiguration.class));
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=false", "rag.retrieval.cross-encoder.model-path=/does/not/exist.onnx")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilingReranker.class));
        // Order-independent: whatever other tests in this JVM loaded, the disabled contexts added no ai.onnxruntime or ai.djl class.
        assertThat(loadedNativeBindingClasses()).isEqualTo(before);
        // Positive control for the detector itself: loading (not initialising) an ONNX Runtime class shows up.
        Class.forName("ai.onnxruntime.OrtException", false, getClass().getClassLoader());
        assertThat(loadedNativeBindingClasses()).contains("ai.onnxruntime.OrtException");
    }

    @Test
    void enabledWithAMissingModelFileFailsStartupNamingThePath() throws Exception {
        Path tokenizer = write("tokenizer.json", "{}");
        Path missing = temp.resolve("absent/model.onnx");
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + missing,
                        "rag.retrieval.cross-encoder.tokenizer-path=" + tokenizer,
                        "rag.retrieval.cross-encoder.model-sha256=00")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("Cross-encoder file not found").contains(missing.toString()).contains("model-path");
                });
    }

    @Test
    void enabledWithAMissingTokenizerFileFailsStartupNamingThePath() throws Exception {
        Path model = write("model.onnx", "not a model");
        Path missing = temp.resolve("tokenizer.json");
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + model,
                        "rag.retrieval.cross-encoder.tokenizer-path=" + missing,
                        "rag.retrieval.cross-encoder.model-sha256=" + CrossEncoderModelFiles.sha256(model))
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("Cross-encoder file not found").contains(missing.toString()).contains("tokenizer-path"));
    }

    @Test
    void enabledWithAWrongChecksumFailsStartupNamingThePathBeforeAnyNativeLoad() throws Exception {
        Path model = write("model.onnx", "not the real model");
        Path tokenizer = write("tokenizer.json", "{}");
        String wrong = "5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a";
        Set<String> before = loadedNativeBindingClasses();
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + model,
                        "rag.retrieval.cross-encoder.tokenizer-path=" + tokenizer,
                        "rag.retrieval.cross-encoder.model-sha256=" + wrong)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("checksum mismatch").contains(model.toString()).contains(CrossEncoderModelFiles.sha256(model)).contains(wrong);
                });
        assertThat(loadedNativeBindingClasses()).isEqualTo(before);

        // The tokenizer checksum is checked too when it is set.
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + model,
                        "rag.retrieval.cross-encoder.tokenizer-path=" + tokenizer,
                        "rag.retrieval.cross-encoder.model-sha256=" + CrossEncoderModelFiles.sha256(model).toUpperCase(),
                        "rag.retrieval.cross-encoder.tokenizer-sha256=" + wrong)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("checksum mismatch").contains(tokenizer.toString()).contains("tokenizer-sha256"));
    }

    @Test
    void enabledWithoutAModelChecksumFailsStartupNamingThePath() throws Exception {
        Path model = write("model.onnx", "x");
        Path tokenizer = write("tokenizer.json", "{}");
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + model,
                        "rag.retrieval.cross-encoder.tokenizer-path=" + tokenizer)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("model-sha256 is required").contains(model.toString()));
    }

    @Test
    void verifiedFilesReportTheFirst12HexCharactersOfTheModelChecksumAsTheVersion() throws Exception {
        Path model = write("model.onnx", "abc");
        Path tokenizer = write("tokenizer.json", "{}");
        var properties = new CrossEncoderProperties();
        properties.setModelPath(model.toString());
        properties.setTokenizerPath(tokenizer.toString());
        // SHA-256 of "abc".
        properties.setModelSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        var files = CrossEncoderModelFiles.verify(properties);
        assertThat(files.version()).isEqualTo("ba7816bf8f01");
        assertThat(files.modelPath()).isEqualTo(model.toAbsolutePath().normalize());
        assertThat(properties.getMaxLength()).isEqualTo(512);
        assertThat(properties.getBatchSize()).isEqualTo(20);
    }

    @Test
    void outOfRangeMaxLengthOrBatchSizeFailsBinding() {
        runner.withPropertyValues("rag.retrieval.cross-encoder.max-length=1024").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("rag.retrieval.cross-encoder.batch-size=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void windowPropertiesDefaultToMaxWindowOverlap64MaxWindows4AndBindTheirBounds() {
        var defaults = new CrossEncoderProperties();
        assertThat(defaults.getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW);
        assertThat(defaults.getWindowOverlapTokens()).isEqualTo(64);
        assertThat(defaults.getMaxWindows()).isEqualTo(4);
        runner.run(context -> {
            var bound = context.getBean(CrossEncoderProperties.class);
            assertThat(bound.getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW);
            assertThat(bound.getWindowOverlapTokens()).isEqualTo(64);
            assertThat(bound.getMaxWindows()).isEqualTo(4);
        });
        // The property values are the enum labels, as application.yaml and RAG_CROSS_ENCODER_PASSAGE_SCORING spell them.
        runner.withPropertyValues("rag.retrieval.cross-encoder.passage-scoring=head", "rag.retrieval.cross-encoder.window-overlap-tokens=0",
                        "rag.retrieval.cross-encoder.max-windows=16")
                .run(context -> {
                    var bound = context.getBean(CrossEncoderProperties.class);
                    assertThat(bound.getPassageScoring()).isEqualTo(PassageScoring.HEAD);
                    assertThat(bound.getWindowOverlapTokens()).isZero();
                    assertThat(bound.getMaxWindows()).isEqualTo(16);
                });
        runner.withPropertyValues("rag.retrieval.cross-encoder.passage-scoring=max-window")
                .run(context -> assertThat(context.getBean(CrossEncoderProperties.class).getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW));
        assertThat(PassageScoring.HEAD.label()).isEqualTo("head");
        assertThat(PassageScoring.MAX_WINDOW.label()).isEqualTo("max-window");
        runner.withPropertyValues("rag.retrieval.cross-encoder.passage-scoring=tail").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("rag.retrieval.cross-encoder.window-overlap-tokens=257").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("rag.retrieval.cross-encoder.window-overlap-tokens=-1").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("rag.retrieval.cross-encoder.max-windows=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("rag.retrieval.cross-encoder.max-windows=17").run(context -> assertThat(context).hasFailed());
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(temp.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        StringBuilder messages = new StringBuilder();
        while (cause != null) {
            messages.append(cause.getMessage()).append('\n');
            cause = cause.getCause();
        }
        return messages.toString();
    }

    /** Names of loaded classes in the ONNX Runtime and DJL packages, from the JVM's own class hierarchy dump. */
    static Set<String> loadedNativeBindingClasses() throws Exception {
        String hierarchy = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "vmClassHierarchy",
                new Object[] {null}, new String[] {String[].class.getName()});
        return Arrays.stream(hierarchy.split("\n"))
                .map(line -> line.replaceAll("^[|\\s-]*", "").split("/")[0].trim())
                .filter(name -> name.startsWith("ai.onnxruntime.") || name.startsWith("ai.djl."))
                .collect(Collectors.toSet());
    }
}

package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The second reranker model's wiring without its model files (plan 2026-09-15-reranker-ettin, E3 and E6): nothing when disabled;
 * startup failure naming both properties when both models are enabled, before any file is read or native class loaded; file and
 * checksum failures naming this model's properties; the profile's frozen values; the snapshot name; and the unchanged pair
 * assembler accepting this model's all-zero pair template. Temp files only.
 */
class GteRerankerConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(CrossEncoderConfiguration.class, GteRerankerConfiguration.class)
            .withBean(CrossEncoderProperties.class)
            .withBean(GteRerankerProperties.class);

    @TempDir Path temp;

    @Test
    void disabledByDefaultCreatesNothingAndLoadsNoOnnxOrTokenizerClass() throws Exception {
        assertThat(new GteRerankerProperties().isEnabled()).isFalse();
        Set<String> before = CrossEncoderConfigurationTests.loadedNativeBindingClasses();
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilingReranker.class).doesNotHaveBean(PairScorer.class)
                .doesNotHaveBean(PassageTokenizer.class).doesNotHaveBean(GteRerankerModelFiles.class).doesNotHaveBean(GteRerankerConfiguration.class)
                .doesNotHaveBean(CrossEncoderConfiguration.class));
        runner.withPropertyValues("rag.retrieval.gte-reranker.enabled=false", "rag.retrieval.gte-reranker.model-path=/does/not/exist.onnx")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilingReranker.class));
        assertThat(CrossEncoderConfigurationTests.loadedNativeBindingClasses()).isEqualTo(before);
    }

    @Test
    void enablingBothModelsFailsStartupNamingBothPropertiesBeforeAnyFileIsReadOrNativeClassLoaded() throws Exception {
        Set<String> before = CrossEncoderConfigurationTests.loadedNativeBindingClasses();
        // Paths that do not exist: a file check would fail with "file not found", so the message proves the guard ran first.
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true", "rag.retrieval.gte-reranker.enabled=true",
                        "rag.retrieval.cross-encoder.model-path=" + temp.resolve("absent-current.onnx"),
                        "rag.retrieval.gte-reranker.model-path=" + temp.resolve("absent-gte.onnx"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context.getStartupFailure());
                    assertThat(message).contains("Only one reranker model may be enabled")
                            .contains("rag.retrieval.cross-encoder.enabled").contains("rag.retrieval.gte-reranker.enabled")
                            .doesNotContain("file not found");
                });
        assertThat(CrossEncoderConfigurationTests.loadedNativeBindingClasses()).isEqualTo(before);
        assertThatThrownBy(() -> GteRerankerConfiguration.ExclusionGuard.check(true, true)).hasMessageContaining("are both true");
        assertThatCode(() -> GteRerankerConfiguration.ExclusionGuard.check(true, false)).doesNotThrowAnyException();
        assertThatCode(() -> GteRerankerConfiguration.ExclusionGuard.check(false, true)).doesNotThrowAnyException();
    }

    @Test
    void theCurrentModelAloneNeverMeetsTheGuardAndFailsOnlyOnItsOwnFiles() {
        Path missing = temp.resolve("absent/model.onnx");
        runner.withPropertyValues("rag.retrieval.cross-encoder.enabled=true", "rag.retrieval.cross-encoder.model-path=" + missing)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("Cross-encoder file not found").contains(missing.toString()).doesNotContain("Only one reranker model"));
    }

    @Test
    void enabledWithAMissingFileOrWrongChecksumFailsStartupNamingThePathAndThisModelsProperty() throws Exception {
        Path tokenizer = write("tokenizer.json", "{}");
        Path missing = temp.resolve("absent/model.onnx");
        runner.withPropertyValues("rag.retrieval.gte-reranker.enabled=true", "rag.retrieval.gte-reranker.model-path=" + missing,
                        "rag.retrieval.gte-reranker.tokenizer-path=" + tokenizer, "rag.retrieval.gte-reranker.model-sha256=00")
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("GTE reranker file not found").contains(missing.toString()).contains("rag.retrieval.gte-reranker.model-path"));

        Path model = write("model.onnx", "not the real model");
        String wrong = "c6d3226502addbcd4d2cf273802957ebf8a2a6bf94037dcb9b1d95bfc01e5d93";
        Set<String> before = CrossEncoderConfigurationTests.loadedNativeBindingClasses();
        runner.withPropertyValues("rag.retrieval.gte-reranker.enabled=true", "rag.retrieval.gte-reranker.model-path=" + model,
                        "rag.retrieval.gte-reranker.tokenizer-path=" + tokenizer, "rag.retrieval.gte-reranker.model-sha256=" + wrong)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("GTE reranker file checksum mismatch").contains(model.toString()).contains(CrossEncoderModelFiles.sha256(model))
                        .contains("rag.retrieval.gte-reranker.model-sha256").contains(wrong));
        assertThat(CrossEncoderConfigurationTests.loadedNativeBindingClasses()).isEqualTo(before);

        runner.withPropertyValues("rag.retrieval.gte-reranker.enabled=true", "rag.retrieval.gte-reranker.model-path=" + model,
                        "rag.retrieval.gte-reranker.tokenizer-path=" + tokenizer,
                        "rag.retrieval.gte-reranker.model-sha256=" + CrossEncoderModelFiles.sha256(model),
                        "rag.retrieval.gte-reranker.tokenizer-sha256=" + wrong)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("GTE reranker file checksum mismatch").contains(tokenizer.toString()).contains("tokenizer-sha256"));

        runner.withPropertyValues("rag.retrieval.gte-reranker.enabled=true", "rag.retrieval.gte-reranker.model-path=" + model,
                        "rag.retrieval.gte-reranker.tokenizer-path=" + tokenizer)
                .run(context -> assertThat(rootMessage(context.getStartupFailure()))
                        .contains("rag.retrieval.gte-reranker.model-sha256 is required").contains(model.toString()));
    }

    @Test
    void verifiedFilesReportTheFirst12HexCharactersAsTheVersion() throws Exception {
        var properties = new GteRerankerProperties();
        properties.setModelPath(write("model.onnx", "abc").toString());
        properties.setTokenizerPath(write("tokenizer.json", "{}").toString());
        properties.setModelSha256("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD");
        assertThat(GteRerankerModelFiles.verify(properties).version()).isEqualTo("ba7816bf8f01");
    }

    @Test
    void propertiesDefaultToTheCurrentModelsWindowSettingsAndValidateTheSameBounds() {
        var defaults = new GteRerankerProperties();
        assertThat(defaults.getModelPath()).isEqualTo("models/gte-reranker-modernbert-base/model.onnx");
        assertThat(defaults.getTokenizerPath()).isEqualTo("models/gte-reranker-modernbert-base/tokenizer.json");
        assertThat(List.of(defaults.getMaxLength(), defaults.getBatchSize(), defaults.getWindowOverlapTokens(), defaults.getMaxWindows()))
                .containsExactly(512, 20, 64, 4);
        assertThat(defaults.getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW);
        for (String invalid : List.of("max-length=513", "max-length=15", "batch-size=0", "batch-size=65", "window-overlap-tokens=-1",
                "window-overlap-tokens=257", "max-windows=0", "max-windows=17", "passage-scoring=tail")) {
            runner.withPropertyValues("rag.retrieval.gte-reranker." + invalid).run(context -> assertThat(context).as(invalid).hasFailed());
        }
    }

    @Test
    void theProfileFileEnablesThisModelWithThePinnedChecksumsTheFrozenSettingsAndA120000MsTimeout() throws Exception {
        var sources = new YamlPropertySourceLoader().load("reranker-gte", new ClassPathResource("application-reranker-gte.yaml"));
        var environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);
        Binder binder = Binder.get(environment);
        GteRerankerProperties profile = binder.bind(GteRerankerProperties.PREFIX, GteRerankerProperties.class).get();
        assertThat(profile.isEnabled()).isTrue();
        assertThat(profile.getModelPath()).isEqualTo("models/gte-reranker-modernbert-base/model.onnx");
        assertThat(profile.getTokenizerPath()).isEqualTo("models/gte-reranker-modernbert-base/tokenizer.json");
        assertThat(profile.getModelSha256()).isEqualTo("c6d3226502addbcd4d2cf273802957ebf8a2a6bf94037dcb9b1d95bfc01e5d93");
        assertThat(profile.getTokenizerSha256()).isEqualTo("2aea6ff4701d063e7e029b6be695a1659f2caaa2ae4fb0e8b18285818271becd");
        assertThat(List.of(profile.getMaxLength(), profile.getBatchSize(), profile.getWindowOverlapTokens(), profile.getMaxWindows()))
                .containsExactly(512, 20, 64, 4);
        assertThat(profile.getPassageScoring()).isEqualTo(PassageScoring.MAX_WINDOW);
        assertThat(binder.bind("rag.retrieval.rerank-timeout-ms", Long.class).get()).isEqualTo(120_000L);
        // The profile does not touch the current model's keys or turn reranking on.
        assertThat(binder.bind("rag.retrieval.cross-encoder", Map.class).isBound()).isFalse();
        assertThat(binder.bind("rag.retrieval.reranking-enabled", Boolean.class).isBound()).isFalse();
    }

    @Test
    void theRerankTimeoutBoundAdmits120000AndNoMoreWhileTheDefaultStays2000() {
        assertThat(new FilingRetrievalProperties().getRerankTimeoutMs()).isEqualTo(2000);
        ApplicationContextRunner retrieval = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withBean(FilingRetrievalProperties.class);
        retrieval.withPropertyValues("rag.retrieval.rerank-timeout-ms=120000")
                .run(context -> assertThat(context.getBean(FilingRetrievalProperties.class).getRerankTimeoutMs()).isEqualTo(120_000));
        retrieval.withPropertyValues("rag.retrieval.rerank-timeout-ms=120001").run(context -> assertThat(context).hasFailed());
        retrieval.withPropertyValues("rag.retrieval.rerank-timeout-ms=99").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void snapshotsNameTheSecondModelApartFromTheCurrentOne() {
        PairScorer scorer = (query, passages) -> new float[passages.size()];
        var gte = new FilingRetrievalService(mock(FilingEmbeddingService.class), mock(FilingRetrievalRepository.class), new FilingRetrievalProperties(),
                Optional.of(new GteReranker(scorer, "c6d3226502ad")));
        var current = new FilingRetrievalService(mock(FilingEmbeddingService.class), mock(FilingRetrievalRepository.class), new FilingRetrievalProperties(),
                Optional.of(new CrossEncoderReranker(scorer, "5d3e70fd0c9f")));
        assertThat(gte.rerankerName()).contains("GteReranker");
        assertThat(gte.rerankerVersion()).contains("c6d3226502ad");
        assertThat(current.rerankerName()).contains("CrossEncoderReranker");
        assertThat(current.rerankerVersion()).contains("5d3e70fd0c9f");
        gte.shutdownRerankExecutor();
        current.shutdownRerankExecutor();
    }

    @Test
    void theUnchangedPairAssemblerReadsTheAllZeroModernBertTemplate() throws Exception {
        // The shape of gte-reranker-modernbert-base's tokenizer.json at commit f7481e60 (vocabulary omitted): every piece typed 0,
        // padding.pad_id 50283, a truncation block. The reader requires first and middle typed like A and last like B; 0, 0, 0 satisfies it.
        Path tokenizer = write("tokenizer.json", """
                {"added_tokens": [{"id": 50281, "content": "[CLS]"}, {"id": 50282, "content": "[SEP]"}, {"id": 50283, "content": "[PAD]"}],
                 "truncation": {"direction": "Right", "max_length": 8000, "strategy": "LongestFirst", "stride": 0},
                 "padding": {"strategy": {"Fixed": 8000}, "direction": "Right", "pad_id": 50283, "pad_type_id": 0, "pad_token": "[PAD]"},
                 "post_processor": {"type": "TemplateProcessing",
                   "pair": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}},
                            {"SpecialToken": {"id": "[SEP]", "type_id": 0}}, {"Sequence": {"id": "B", "type_id": 0}},
                            {"SpecialToken": {"id": "[SEP]", "type_id": 0}}],
                   "special_tokens": {"[CLS]": {"id": "[CLS]", "ids": [50281], "tokens": ["[CLS]"]},
                                      "[SEP]": {"id": "[SEP]", "ids": [50282], "tokens": ["[SEP]"]}}}}
                """);
        CrossEncoderPairAssembler assembler = CrossEncoderPairAssembler.fromTokenizerJson(tokenizer, 16);
        assertThat(assembler.specialIds()).containsExactly(50281, 50282, 50282, 50283);
        CrossEncoderPairAssembler.Batch batch = assembler.assemble(new long[] {7, 8}, List.of(new long[] {20, 21, 22}, new long[] {}));
        assertThat(batch.inputIds()[0]).containsExactly(50281, 7, 8, 50282, 20, 21, 22, 50282);
        assertThat(batch.inputIds()[1]).containsExactly(50281, 7, 8, 50282, 50282, 50283, 50283, 50283);
        assertThat(batch.attentionMask()[1]).containsExactly(1, 1, 1, 1, 1, 0, 0, 0);
        assertThat(batch.tokenTypeIds()[0]).containsOnly(0L);
        assertThat(batch.tokenTypeIds()[1]).containsOnly(0L);
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
}

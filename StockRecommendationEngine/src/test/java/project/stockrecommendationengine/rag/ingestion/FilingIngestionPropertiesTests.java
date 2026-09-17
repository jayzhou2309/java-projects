package project.stockrecommendationengine.rag.ingestion;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk-size plan Milestone 2: the bounds of {@code rag.ingestion.chunk-max-chars} (100 to 20,000) and {@code chunk-overlap-chars}
 * (0 to half the size) under bean validation, which {@code @Validated} applies when Spring binds the properties, and the processing
 * version string the settings produce. The diagnostic's sizes (4,000 / 500, 2,000 / 250, 1,000 / 125, 500 / 62) must all be valid.
 */
class FilingIngestionPropertiesTests {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll static void close() {
        factory.close();
    }

    @ParameterizedTest
    @CsvSource({"4000, 500", "2000, 250", "1000, 125", "500, 62", "100, 0", "100, 50", "20000, 10000", "1000, 500"})
    void sizesInsideTheBoundsAreValid(int size, int overlap) {
        assertThat(validator.validate(properties(size, overlap))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"99, 0", "20001, 0", "1000, -1", "1000, 501", "500, 251", "4000, 2001", "100, 51"})
    void sizesOutsideTheBoundsAreRejected(int size, int overlap) {
        Set<ConstraintViolation<FilingIngestionProperties>> violations = validator.validate(properties(size, overlap));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void anOverlapAboveHalfTheSizeNamesBothProperties() {
        Set<ConstraintViolation<FilingIngestionProperties>> violations = validator.validate(properties(1000, 501));
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactly("rag.ingestion.chunk-overlap-chars must be at most half of rag.ingestion.chunk-max-chars");
        assertThat(validator.validate(properties(1000, 500))).isEmpty();
    }

    @Test
    void theProcessingVersionNamesTheSizeAndOverlap() {
        assertThat(new FilingIngestionProperties().processingVersion()).isEqualTo("sections-v2-context-v2-chunk4000-500");
        assertThat(properties(1000, 125).processingVersion()).isEqualTo("sections-v2-context-v2-chunk1000-125");
        assertThat(properties(20000, 10000).processingVersion()).isEqualTo("sections-v2-context-v2-chunk20000-10000").hasSizeLessThanOrEqualTo(80);
        assertThat(FilingIngestionProperties.PROCESSING_VERSION_PREFIX).isEqualTo("sections-v2-context-v2");
    }

    private static FilingIngestionProperties properties(int size, int overlap) {
        FilingIngestionProperties properties = new FilingIngestionProperties();
        properties.setChunkMaxChars(size);
        properties.setChunkOverlapChars(overlap);
        return properties;
    }
}

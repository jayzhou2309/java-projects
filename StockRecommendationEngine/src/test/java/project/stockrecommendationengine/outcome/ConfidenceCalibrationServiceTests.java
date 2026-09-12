package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Bin;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Sample;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfidenceCalibrationServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-12T05:00:00Z");
    private final CalibrationRepository repository = mock(CalibrationRepository.class);
    private final OutcomeProperties properties = new OutcomeProperties();
    private final ConfidenceCalibrationService service = new ConfidenceCalibrationService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void computeReadsSamplesAtTheReferenceHorizonAndStoresTheSnapshot() {
        properties.getCalibration().setMinSamples(2);
        when(repository.samples(20)).thenReturn(List.of(new Sample(new BigDecimal("0.6"), true, "BULLISH", "p"),
                new Sample(new BigDecimal("0.6"), false, "BEARISH", "p")));
        when(repository.save(any())).thenAnswer(invocation -> {
            ConfidenceCalibration c = invocation.getArgument(0);
            return new ConfidenceCalibration(7L, c.computedAt(), c.horizonDays(), c.status(), c.samples(), c.minSamples(), c.priorWeight(),
                    c.baseRate(), c.expectedCalibrationError(), c.brierScore(), c.bins(), c.assessments(), c.promptVersions(), c.caveat());
        });
        var stored = service.compute();
        assertThat(stored.id()).isEqualTo(7L);
        assertThat(stored.computedAt()).isEqualTo(NOW);
        assertThat(stored.status()).isEqualTo("READY");
        assertThat(stored.samples()).isEqualTo(2);
        assertThat(stored.minSamples()).isEqualTo(2);
        assertThat(stored.priorWeight()).isEqualTo(10);
        properties.setHorizons(List.of(5, 60));
        when(repository.samples(5)).thenReturn(List.of());
        assertThat(service.compute().horizonDays()).as("first horizon when 20 is not configured").isEqualTo(5);
    }

    @Test void applyUsesTheNewestReadySnapshotOrExplainsWhyNot() {
        when(repository.latest(20)).thenReturn(Optional.empty());
        var none = service.apply(new BigDecimal("0.9"));
        assertThat(none.status()).isEqualTo("NO_CALIBRATION");
        assertThat(none.calibratedConfidence()).isNull();
        var bins = List.of(bin("0", "0.5", 0, null, "0.4"), bin("0.5", "1", 40, "0.55", "0.583333"));
        when(repository.latest(20)).thenReturn(Optional.of(snapshot("INSUFFICIENT_SAMPLE", 12, bins)));
        var thin = service.apply(new BigDecimal("0.9"));
        assertThat(thin.status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(thin.calibratedConfidence()).isNull();
        assertThat(thin.calibration().samples()).isEqualTo(12);
        when(repository.latest(20)).thenReturn(Optional.of(snapshot("READY", 40, bins)));
        var applied = service.apply(new BigDecimal("0.9"));
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.calibratedConfidence()).isEqualByComparingTo("0.583333");
        assertThat(applied.bin().samples()).isEqualTo(40);
        assertThat(service.apply(new BigDecimal("0.1")).calibratedConfidence()).isEqualByComparingTo("0.4");
        assertThat(service.latest()).isPresent();
    }

    private static ConfidenceCalibration snapshot(String status, int samples, List<Bin> bins) {
        return new ConfidenceCalibration(3L, NOW, 20, status, samples, 30, 10, new BigDecimal("0.4"), new BigDecimal("0.1"),
                new BigDecimal("0.25"), bins, Map.of("BULLISH", samples), Map.of("p", samples), ConfidenceCalibration.CAVEAT);
    }
    private static Bin bin(String lower, String upper, int samples, String hitRate, String calibrated) {
        return new Bin(new BigDecimal(lower), new BigDecimal(upper), samples, hitRate == null ? null : new BigDecimal(hitRate),
                hitRate == null ? null : new BigDecimal(hitRate), null, null, new BigDecimal(calibrated));
    }
}

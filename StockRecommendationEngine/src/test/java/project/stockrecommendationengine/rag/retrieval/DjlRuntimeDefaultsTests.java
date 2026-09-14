package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

/**
 * DJL's telemetry call ({@code Ec2Utils.callHome}) is disabled by default: {@code OPT_OUT_TRACKING} and {@code ai.djl.offline}
 * are set to true when absent, never over an existing environment variable or property. The static-initializer checks run in a
 * child JVM with those variables removed from its environment, so this JVM's properties and environment cannot mask the result.
 */
class DjlRuntimeDefaultsTests {
    private static final List<String> DJL_ENVIRONMENT = List.of("OPT_OUT_TRACKING", "DJL_OFFLINE");

    @Test
    void setsBothPropertiesWhenNeitherTheEnvironmentNorAPropertyHasThem() {
        Properties properties = new Properties();
        assertThat(DjlRuntimeDefaults.apply(Map.of(), properties)).containsExactly("OPT_OUT_TRACKING", "ai.djl.offline");
        assertThat(properties.getProperty("OPT_OUT_TRACKING")).isEqualTo("true");
        assertThat(properties.getProperty("ai.djl.offline")).isEqualTo("true");
    }

    @Test
    void neverOverridesAnExistingPropertyOrEnvironmentVariable() {
        Properties properties = new Properties();
        properties.setProperty("OPT_OUT_TRACKING", "false");
        properties.setProperty("ai.djl.offline", "false");
        assertThat(DjlRuntimeDefaults.apply(Map.of(), properties)).isEmpty();
        assertThat(properties.getProperty("OPT_OUT_TRACKING")).isEqualTo("false");
        assertThat(properties.getProperty("ai.djl.offline")).isEqualTo("false");

        Properties fromEnvironment = new Properties();
        assertThat(DjlRuntimeDefaults.apply(Map.of("OPT_OUT_TRACKING", "false", "DJL_OFFLINE", "false"), fromEnvironment)).isEmpty();
        assertThat(fromEnvironment).isEmpty();

        Properties partly = new Properties();
        assertThat(DjlRuntimeDefaults.apply(Map.of("OPT_OUT_TRACKING", "true"), partly)).containsExactly("ai.djl.offline");
        assertThat(partly.getProperty("OPT_OUT_TRACKING")).isNull();
    }

    @Test
    void theScorersStaticInitializerSetsThePropertiesWhenAbsent() throws Exception {
        ForkedJvm.Result result = ForkedJvm.run(ScorerStaticSetupProbe.class, List.of(), List.of(), DJL_ENVIRONMENT, 60, null);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.lines()).contains("PROBE before OPT_OUT_TRACKING=null ai.djl.offline=null",
                "PROBE after OPT_OUT_TRACKING=true ai.djl.offline=true");
    }

    @Test
    void theScorersStaticInitializerKeepsAnExistingValue() throws Exception {
        ForkedJvm.Result result = ForkedJvm.run(ScorerStaticSetupProbe.class,
                List.of("-DOPT_OUT_TRACKING=false", "-Dai.djl.offline=false"), List.of(), DJL_ENVIRONMENT, 60, null);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.lines()).contains("PROBE after OPT_OUT_TRACKING=false ai.djl.offline=false");
        assertThat(result.output()).contains("DJL telemetry is not disabled");
    }
}

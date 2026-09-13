package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JVM-wide defaults for the DJL tokenizer, applied before any DJL class that could contact the network is initialised
 * ({@link OnnxCrossEncoderScorer}'s static initializer calls {@link #apply()}). This class imports nothing from DJL, so calling
 * it loads no DJL class.
 * <p>
 * DJL 0.38.0's {@code ai.djl.util.Ec2Utils.callHome(String)}, called by every {@code HuggingFaceTokenizer.newInstance} and so by
 * {@code HuggingFaceTokenizer.Builder.build()}, sends at most once a day a PUT and GETs to the EC2 metadata endpoint
 * {@code http://169.254.169.254} (1 s timeout) and, on an AWS host, a GET to {@code https://djl-telemetry-<region>.s3.<region>.amazonaws.com};
 * its connections use {@code Proxy.NO_PROXY}. It returns immediately when {@code Utils.isOfflineMode()} is true (environment
 * {@code DJL_OFFLINE}, else system property {@code ai.djl.offline}) or when {@code Boolean.parseBoolean(Utils.getEnvOrSystemProperty("OPT_OUT_TRACKING"))}
 * is true (environment variable first, then system property). Both are system properties here, each set to {@code true} only
 * when neither its environment variable nor its property is present; an existing value is never overridden, and a present
 * value that leaves the call enabled is logged at WARN. {@code ai.djl.offline} additionally makes DJL's own URL helper
 * ({@code Utils.openUrl}) and its model-zoo and repository download paths refuse to connect; nothing else in this application
 * uses DJL.
 */
@Slf4j
final class DjlRuntimeDefaults {
    static final String OPT_OUT_TRACKING = "OPT_OUT_TRACKING";
    static final String OFFLINE_PROPERTY = "ai.djl.offline";
    static final String OFFLINE_ENVIRONMENT = "DJL_OFFLINE";

    private DjlRuntimeDefaults() {
    }

    /** Applies the defaults to this JVM's system properties, reading this process's environment. */
    static synchronized List<String> apply() {
        return apply(System.getenv(), System.getProperties());
    }

    /** Sets each default on {@code properties} when absent from both; returns the property names it set. */
    static List<String> apply(Map<String, String> environment, Properties properties) {
        List<String> set = new ArrayList<>();
        setIfAbsent(environment.get(OPT_OUT_TRACKING), OPT_OUT_TRACKING, properties, set);
        setIfAbsent(environment.get(OFFLINE_ENVIRONMENT), OFFLINE_PROPERTY, properties, set);
        // DJL reads the environment variable first, then the property, exactly as resolved here.
        String tracking = environment.getOrDefault(OPT_OUT_TRACKING, properties.getProperty(OPT_OUT_TRACKING));
        String offline = environment.getOrDefault(OFFLINE_ENVIRONMENT, properties.getProperty(OFFLINE_PROPERTY));
        if (!Boolean.parseBoolean(tracking) && !Boolean.parseBoolean(offline)) {
            log.warn("DJL telemetry is not disabled ({}={}, {}={}); building the tokenizer may contact the EC2 metadata endpoint",
                    OPT_OUT_TRACKING, tracking, OFFLINE_PROPERTY, offline);
        }
        return set;
    }

    private static void setIfAbsent(String environmentValue, String property, Properties properties, List<String> set) {
        if (environmentValue != null || properties.getProperty(property) != null) return;
        properties.setProperty(property, "true");
        set.add(property);
    }
}

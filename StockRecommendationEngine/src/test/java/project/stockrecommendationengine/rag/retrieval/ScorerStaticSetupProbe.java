package project.stockrecommendationengine.rag.retrieval;

/**
 * Child-JVM main for {@link DjlRuntimeDefaultsTests}: prints the DJL network properties, initialises (does not construct)
 * {@link OnnxCrossEncoderScorer}, and prints them again. Loads no model and no native library.
 */
final class ScorerStaticSetupProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("PROBE before OPT_OUT_TRACKING=" + System.getProperty(DjlRuntimeDefaults.OPT_OUT_TRACKING)
                + " ai.djl.offline=" + System.getProperty(DjlRuntimeDefaults.OFFLINE_PROPERTY));
        Class.forName("project.stockrecommendationengine.rag.retrieval.OnnxCrossEncoderScorer", true,
                ScorerStaticSetupProbe.class.getClassLoader());
        System.out.println("PROBE after OPT_OUT_TRACKING=" + System.getProperty(DjlRuntimeDefaults.OPT_OUT_TRACKING)
                + " ai.djl.offline=" + System.getProperty(DjlRuntimeDefaults.OFFLINE_PROPERTY));
    }
}

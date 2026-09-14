package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.rerank.live=true): runs {@link CrossEncoderLengthProbe} with the real model in a child JVM, so an input that
 * makes the native tokenizer abort the process fails this test with the child's exit code instead of killing Maven. The child
 * scores queries of 508, 509 and 600 words, 4,000, 20,000 and 25,000 characters, a single 5,000-character word, and mixed
 * Unicode with emoji (plus a lone surrogate and a NUL), each against a 2,000-character passage; a query-by-passage length sweep;
 * and a 20-pair batch at 512 tokens per pair. The child's environment has no {@code OPT_OUT_TRACKING} or {@code DJL_OFFLINE}, so
 * the scorer's own static setup must disable DJL's telemetry call.
 * <p>
 * While the child runs, its TCP and UDP sockets are sampled with {@code lsof -nP -a -p <pid> -iTCP -iUDP} (no system or firewall
 * setting is changed). The child opens one loopback connection to this test at the end as a positive control, so the sampler is
 * shown to see a connection; any other socket fails the test. Sampling can miss a connection shorter than the sampling interval,
 * so this supports, and does not replace, the code inspection of {@code Ec2Utils.callHome} recorded in RAG.md.
 */
@EnabledIfSystemProperty(named = "rag.rerank.live", matches = "true")
class CrossEncoderForkedLiveTests {
    private static final List<String> BOUNDARY_CASES = List.of("words508", "words509", "words600", "chars4000", "chars20000",
            "chars25000", "singleWord5000", "unicodeEmoji");
    /** Cases whose query must be cut (by tokens or, for 25,000 characters, also by the character bound) and logged. */
    private static final List<String> QUERY_CUT_CASES = List.of("words508", "words509", "words600", "chars4000", "chars20000", "chars25000");

    @Test
    void boundaryLengthQueriesScoreNormallyInAChildJvmWithNoOutboundConnection() throws Exception {
        Path modelDir = Path.of(System.getProperty("rag.rerank.model-dir", "models/cross-encoder-ms-marco-MiniLM-L-6-v2")).toAbsolutePath();
        Path lsof = Path.of("/usr/sbin/lsof");
        Set<String> socketLines = new LinkedHashSet<>();
        AtomicInteger samples = new AtomicInteger();
        try (ServerSocket control = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try (Socket accepted = control.accept()) {
                    accepted.getInputStream().read();
                } catch (Exception ignored) {
                    // closed with the test
                }
            }, "control-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            ForkedJvm.Result result = ForkedJvm.run(CrossEncoderLengthProbe.class, List.of(),
                    List.of(modelDir.toString(), String.valueOf(control.getLocalPort())),
                    List.of("OPT_OUT_TRACKING", "DJL_OFFLINE", "RUST_FLAVOR", "RUST_LIBRARY_PATH"), 300,
                    pid -> {
                        if (!Files.isExecutable(lsof)) return;
                        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                            socketLines.addAll(sockets(lsof, pid));
                            samples.incrementAndGet();
                        }
                    });

            System.out.println("CROSS_ENCODER forked exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
            System.out.println("CROSS_ENCODER lsof available=" + Files.isExecutable(lsof) + " samples=" + samples.get()
                    + " distinctSocketLines=" + socketLines.size());
            socketLines.forEach(line -> System.out.println("CROSS_ENCODER lsof " + line));

            assertThat(result.timedOut()).as(result.output()).isFalse();
            assertThat(result.exitCode()).as("child exit code (134 is a native abort)\n" + result.output()).isZero();
            assertThat(result.output()).doesNotContain("panicked").doesNotContain("fatal runtime error").contains("PROBE done");
            assertThat(result.lines()).contains("PROBE properties before OPT_OUT_TRACKING=null ai.djl.offline=null RUST_FLAVOR=null")
                    .anyMatch(line -> line.startsWith("PROBE properties after OPT_OUT_TRACKING=true ai.djl.offline=true RUST_FLAVOR=cpu"));
            for (String name : BOUNDARY_CASES) {
                List<String> caseLines = caseLines(result.lines(), name);
                assertThat(caseLines).as(name).isNotEmpty();
                assertThat(caseLines.get(caseLines.size() - 1)).as(name).matches("PROBE result case=" + name + " .* scores=\\[-?\\d.*\\] elapsedMs=\\d+");
                boolean cutLogged = caseLines.stream().anyMatch(line -> line.contains("Cross-encoder query truncated"));
                if (QUERY_CUT_CASES.contains(name)) assertThat(cutLogged).as(name + " logs the query cut").isTrue();
            }
            assertThat(caseLines(result.lines(), "singleWord5000")).noneMatch(line -> line.contains("Cross-encoder query truncated"));
            assertThat(result.lines()).filteredOn(line -> line.startsWith("PROBE sweep ")).hasSize(10);
            assertThat(result.lines()).anyMatch(line -> line.startsWith("PROBE batch pairs=20 "));

            if (Files.isExecutable(lsof)) {
                String port = ":" + control.getLocalPort();
                assertThat(socketLines).as("the control connection is seen by the sampler").anyMatch(line -> line.contains(port));
                assertThat(socketLines).as("no socket other than the loopback control connection").allMatch(line -> line.contains(port));
            }
        }
    }

    /** The PROBE start line of a case through its result line, including the log lines in between. */
    private static List<String> caseLines(List<String> lines, String name) {
        int start = lines.indexOf("PROBE start case=" + name);
        if (start < 0) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            out.add(lines.get(i));
            if (lines.get(i).startsWith("PROBE result case=" + name + " ")) return out;
        }
        return List.of();
    }

    private static List<String> sockets(Path lsof, long pid) {
        try {
            Process process = new ProcessBuilder(lsof.toString(), "-nP", "-a", "-p", String.valueOf(pid), "-iTCP", "-iUDP")
                    .redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.startsWith("COMMAND")) lines.add(line.replaceAll("\\s+", " "));
                }
            }
            process.waitFor();
            return lines;
        } catch (Exception e) {
            return List.of("lsof failed: " + e.getClass().getSimpleName());
        }
    }
}

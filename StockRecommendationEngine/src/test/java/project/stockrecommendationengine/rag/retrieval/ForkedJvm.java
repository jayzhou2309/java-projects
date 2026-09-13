package project.stockrecommendationengine.rag.retrieval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Runs a main class of the test classpath in a child JVM, so a native abort (a Rust panic, exit code 134) ends only the child
 * and the calling test sees its exit code and output instead of Maven's test JVM dying.
 */
final class ForkedJvm {
    record Result(int exitCode, List<String> lines, boolean timedOut) {
        String output() {
            return String.join("\n", lines);
        }
    }

    private ForkedJvm() {
    }

    /**
     * Starts {@code mainClass} with this JVM's classpath, the given JVM options and arguments, and an environment without the
     * variables in {@code removeEnvironment}; stdout and stderr are merged, echoed with a {@code [child] } prefix, and returned.
     * {@code whileRunning}, when given, receives the child's pid on its own thread as soon as the process starts.
     */
    static Result run(Class<?> mainClass, List<String> jvmOptions, List<String> args, List<String> removeEnvironment,
                      long timeoutSeconds, LongConsumer whileRunning) throws IOException, InterruptedException {
        return run(List.of(), mainClass, jvmOptions, args, removeEnvironment, timeoutSeconds, whileRunning);
    }

    /**
     * As above, with {@code commandPrefix} placed before the java executable (for example {@code /usr/bin/time -l}, whose report
     * on stderr is merged into the returned lines). The pid given to {@code whileRunning} is then the prefix command's.
     */
    static Result run(List<String> commandPrefix, Class<?> mainClass, List<String> jvmOptions, List<String> args, List<String> removeEnvironment,
                      long timeoutSeconds, LongConsumer whileRunning) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(commandPrefix);
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        removeEnvironment.forEach(builder.environment()::remove);
        Process process = builder.start();
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    lines.add(line);
                    System.out.println("[child] " + line);
                }
            } catch (IOException ignored) {
                // the child exited
            }
        }, "forked-jvm-output");
        reader.start();
        Thread watcher = null;
        if (whileRunning != null) {
            watcher = new Thread(() -> whileRunning.accept(process.pid()), "forked-jvm-watcher");
            watcher.start();
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(10_000);
        if (watcher != null) watcher.join(10_000);
        return new Result(finished ? process.exitValue() : -1, List.copyOf(lines), !finished);
    }
}

package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The repository-wide claims check and generator (RAG.md, Claims): every {@code claims.json} under
 * {@code src/main/java/documentation/live-runs}, every claims file a generated block references, and every markdown file under
 * {@code src/main/java} plus {@code CLAUDE.md}. Every file a claim reads, and every claims file a block names, must resolve inside
 * {@code live-runs} (the evidence root). Reads files as they are on disk (whether they are committed is a review item); never the database.
 */
final class DocumentationClaims {
    static final Path LIVE_RUNS = Path.of("src/main/java/documentation/live-runs");
    static final Path DOCUMENTATION_ROOT = Path.of("src/main/java");
    static final Path CLAUDE_MD = Path.of("CLAUDE.md");

    private DocumentationClaims() {
    }

    /** The generator's outcome over the repository: the documentation files it rewrote and every file or block it left unwritten, with why. */
    record Generated(List<Path> changed, List<String> problems) {
    }

    /** Every problem of every claims file and documentation file under {@code root}, claims files first, each in path order. */
    static List<String> check(Path root) {
        Path evidenceRoot = root.resolve(LIVE_RUNS);
        List<Path> claimsFiles = new ArrayList<>(claimsFiles(root));
        List<Path> documents = documents(root);
        for (Path document : documents) {
            for (Path referenced : GeneratedBlocks.referencedClaimsFiles(document)) {
                if (Files.isRegularFile(referenced) && ClaimsCheck.outside(referenced, evidenceRoot) == null
                        && claimsFiles.stream().noneMatch(p -> p.toAbsolutePath().normalize().equals(referenced))) {
                    claimsFiles.add(referenced);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (Path claims : claimsFiles) problems.addAll(ClaimsCheck.check(claims, evidenceRoot));
        for (Path document : documents) problems.addAll(GeneratedBlocks.check(document, evidenceRoot));
        return problems;
    }

    /** The single failure message naming every problem, one per line. */
    static String message(List<String> problems) {
        return "Claims check failed: " + problems.size() + (problems.size() == 1 ? " problem" : " problems")
                + " (after an intended change regenerate blocks with -Dclaims.generate=true on DocumentationClaimsTests; format in RAG.md, Claims)\n"
                + String.join("\n", problems);
    }

    /**
     * Rewrites the generated blocks of every documentation file under {@code root} that has markers. A file or block that cannot be generated
     * (unbalanced markers, an unreadable claims file, a claim without a rendered sentence) is reported and left as it is; the others are
     * still written.
     */
    static Generated generate(Path root) {
        Path evidenceRoot = root.resolve(LIVE_RUNS);
        List<Path> changed = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path document : documents(root)) {
            if (!GeneratedBlocks.hasMarkers(document)) continue;
            GeneratedBlocks.Generation generation = GeneratedBlocks.write(document, evidenceRoot);
            if (generation.changed()) changed.add(document);
            problems.addAll(generation.problems());
        }
        return new Generated(changed, problems);
    }

    static List<Path> claimsFiles(Path root) {
        return walk(root.resolve(LIVE_RUNS), path -> path.getFileName().toString().equals("claims.json"));
    }

    static List<Path> documents(Path root) {
        List<Path> documents = walk(root.resolve(DOCUMENTATION_ROOT), path -> path.getFileName().toString().endsWith(".md"));
        if (Files.isRegularFile(root.resolve(CLAUDE_MD))) documents.add(root.resolve(CLAUDE_MD));
        return documents;
    }

    private static List<Path> walk(Path directory, java.util.function.Predicate<Path> wanted) {
        if (!Files.isDirectory(directory)) return new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            return new ArrayList<>(paths.filter(Files::isRegularFile).filter(wanted).sorted().toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

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
 * {@code src/main/java} plus {@code CLAUDE.md}. Reads committed files only; never the database.
 */
final class DocumentationClaims {
    static final Path LIVE_RUNS = Path.of("src/main/java/documentation/live-runs");
    static final Path DOCUMENTATION_ROOT = Path.of("src/main/java");
    static final Path CLAUDE_MD = Path.of("CLAUDE.md");

    private DocumentationClaims() {
    }

    /** Every problem of every claims file and documentation file under {@code root}, claims files first, each in path order. */
    static List<String> check(Path root) {
        List<Path> claimsFiles = new ArrayList<>(claimsFiles(root));
        List<Path> documents = documents(root);
        for (Path document : documents) {
            for (Path referenced : GeneratedBlocks.referencedClaimsFiles(document)) {
                if (Files.isRegularFile(referenced) && claimsFiles.stream().noneMatch(p -> p.toAbsolutePath().normalize().equals(referenced))) {
                    claimsFiles.add(referenced);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (Path claims : claimsFiles) problems.addAll(ClaimsCheck.check(claims));
        for (Path document : documents) problems.addAll(GeneratedBlocks.check(document));
        return problems;
    }

    /** The single failure message naming every problem, one per line. */
    static String message(List<String> problems) {
        return "Claims check failed: " + problems.size() + (problems.size() == 1 ? " problem" : " problems")
                + " (after an intended change regenerate blocks with -Dclaims.generate=true on DocumentationClaimsTests; format in RAG.md, Claims)\n"
                + String.join("\n", problems);
    }

    /** Rewrites the generated blocks of every documentation file under {@code root} that has markers; returns the files changed. */
    static List<Path> generate(Path root) {
        List<Path> changed = new ArrayList<>();
        for (Path document : documents(root)) {
            if (GeneratedBlocks.hasMarkers(document) && GeneratedBlocks.write(document)) changed.add(document);
        }
        return changed;
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

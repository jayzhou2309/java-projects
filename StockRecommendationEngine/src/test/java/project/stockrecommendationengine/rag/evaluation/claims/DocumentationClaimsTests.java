package project.stockrecommendationengine.rag.evaluation.claims;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Evaluation evidence Milestone 3 in {@code verify}: every committed claims file holds against its committed evidence, and every
 * generated documentation block is exactly what the generator writes from its claims, with every claim citation resolving (RAG.md,
 * Claims). With {@code -Dclaims.generate=true} the blocks are rewritten first:
 * {@code ./mvnw -q -o test -Dtest=DocumentationClaimsTests -Dclaims.generate=true}.
 */
class DocumentationClaimsTests {
    static final String GENERATE = "claims.generate";

    @Test
    void everyClaimsFileAndGeneratedBlockInTheRepositoryChecks() {
        Path root = Path.of("");
        if (Boolean.getBoolean(GENERATE)) {
            List<Path> changed = DocumentationClaims.generate(root);
            System.out.println("CLAIMS_GENERATE changed=" + changed.size() + " " + changed);
        }
        List<String> problems = DocumentationClaims.check(root);
        System.out.println("CLAIMS_CHECK claimsFiles=" + DocumentationClaims.claimsFiles(root).size() + " documents=" + DocumentationClaims.documents(root).size()
                + " problems=" + problems.size());
        if (!problems.isEmpty()) fail(DocumentationClaims.message(problems));
    }
}

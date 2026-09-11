package project.stockrecommendationengine.quant;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read-only inspection of the deterministic analysis the recommendation loop attaches. */
@RestController
@RequestMapping("/api/quant")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.enabled", havingValue = "true")
public class QuantController {
    private final QuantAnalysisService service;

    @GetMapping("/analysis/{conid}")
    public QuantAnalysis analysis(@PathVariable long conid) { return service.analyze(conid); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> insufficientData(IllegalArgumentException ex) {
        String code = "INSUFFICIENT_BARS".equals(ex.getMessage()) ? ex.getMessage() : "INVALID_ARGUMENT";
        return ResponseEntity.status(code.equals("INSUFFICIENT_BARS") ? 422 : 400).body(Map.of("code", code));
    }
}

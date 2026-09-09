package project.stockrecommendationengine.recommendation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.enabled", havingValue = "true")
public class RecommendationController {
    private final RecommendationService service;
    @PostMapping public RecommendationResponse recommend(@Valid @RequestBody RecommendationRequest request) {
        return service.recommend(request);
    }
}

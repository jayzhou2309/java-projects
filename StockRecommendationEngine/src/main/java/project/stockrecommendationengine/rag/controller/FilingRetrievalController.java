package project.stockrecommendationengine.rag.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class FilingRetrievalController {
    private final FilingRetrievalService retrievalService;

    @PostMapping("/retrieve")
    public RetrievalResponse retrieve(@Valid @RequestBody RetrievalRequest request) {
        return retrievalService.retrieve(request);
    }
}

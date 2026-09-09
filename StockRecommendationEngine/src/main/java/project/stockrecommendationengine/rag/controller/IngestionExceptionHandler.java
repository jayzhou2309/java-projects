package project.stockrecommendationengine.rag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.stockrecommendationengine.rag.ingestion.UnknownTickerException;

@RestControllerAdvice(assignableTypes = FilingIngestionController.class)
public class IngestionExceptionHandler {
    @ExceptionHandler(UnknownTickerException.class)
    public ProblemDetail unknownTicker(UnknownTickerException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}

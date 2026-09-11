package project.stockrecommendationengine.broker.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.quant.QuantController;

@RestControllerAdvice(assignableTypes = {BrokerController.class, QuantController.class})
public class BrokerExceptionHandler {
    @ExceptionHandler(BrokerException.class)
    public ResponseEntity<?> handle(BrokerException ex) {
        int status = switch (ex.code()) {
            case INVALID_ARGUMENT -> 400;
            case ACCOUNT_NOT_ALLOWED -> 403;
            case RATE_LIMITED -> 429;
            case INVALID_RESPONSE -> 502;
            default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.code().name()));
    }
}

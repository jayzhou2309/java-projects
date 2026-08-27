package project.demotradingapp.execeptions;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private ErrorResponse buildError(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ){
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .message(message)
                .error(status.getReasonPhrase())
                .path(request.getRequestURI())
                .build();
    }

    @ExceptionHandler(BadRequestExeception.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getMessage(), request));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            BadRequestExeception ex, HttpServletRequest request
    ){
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(buildError(status, "An unexpected error occurredw", request));
    }

}

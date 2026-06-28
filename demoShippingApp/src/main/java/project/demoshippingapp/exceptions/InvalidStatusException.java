package project.demoshippingapp.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidStatusException extends RuntimeException {

    public InvalidStatusException(String status) {
        super("Invalid status: " + status + ". Valid values: CREATED, IN_TRANSIT, DELIVERED, FAILED");
    }
}
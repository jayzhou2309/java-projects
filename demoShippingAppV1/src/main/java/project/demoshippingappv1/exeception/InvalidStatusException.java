package project.demoshippingappv1.exeception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import project.demoshippingappv1.model.ShipmentStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidStatusException extends RuntimeException {

    public InvalidStatusException(ShipmentStatus status) {
        super("Invalid status: " + status + ". Valid values: CREATED, IN_TRANSIT, DELIVERED, FAILED");
    }
}

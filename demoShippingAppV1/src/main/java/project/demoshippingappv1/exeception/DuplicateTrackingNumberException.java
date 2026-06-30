package project.demoshippingappv1.exeception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateTrackingNumberException extends RuntimeException {

    public DuplicateTrackingNumberException(String trackingNumber) {
        super("Tracking number already exists: " + trackingNumber);
    }
}

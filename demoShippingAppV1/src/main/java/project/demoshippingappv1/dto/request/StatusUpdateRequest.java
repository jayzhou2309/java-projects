package project.demoshippingappv1.dto.request;

import lombok.Data;
import project.demoshippingappv1.model.ShipmentStatus;

@Data
public class StatusUpdateRequest {
    private ShipmentStatus status;
}

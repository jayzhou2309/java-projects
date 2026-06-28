package project.demoshippingapp.dto.request;

import lombok.Data;
import project.demoshippingapp.model.ShipmentStatus;

@Data
public class StatusUpdateRequest {
    private ShipmentStatus status;
}

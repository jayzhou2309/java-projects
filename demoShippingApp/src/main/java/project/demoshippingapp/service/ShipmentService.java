package project.demoshippingapp.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import project.demoshippingapp.dto.request.CreateShipmentRequest;
import project.demoshippingapp.exceptions.DuplicateTrackingNumberException;
import project.demoshippingapp.exceptions.InvalidStatusException;
import project.demoshippingapp.exceptions.ResourceNotFoundException;
import project.demoshippingapp.mappers.ShipmentMapper;
import project.demoshippingapp.model.Shipment;
import project.demoshippingapp.model.ShipmentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class ShipmentService {
    private final ShipmentMapper shipmentMapper;

    public Shipment createShipment(CreateShipmentRequest request){
        String trackingNumber = "TRK" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();
        if (shipmentMapper.selectByTrackingNumber(trackingNumber).isPresent()){
            throw new DuplicateTrackingNumberException(trackingNumber);
        }

        Shipment newShipment = Shipment.builder()
                .trackingNumber(trackingNumber)
                .senderName(request.getSenderName())
                .receiverName(request.getReceiverName())
                .originAddress(request.getOriginAddress())
                .destAddress(request.getDestAddress())
                .status(ShipmentStatus.CREATED)
                .build();
        shipmentMapper.insert(newShipment);
        return newShipment;
    }

    public List<Shipment> getAllShipment(){
        return shipmentMapper.selectAll();
    }

    public Shipment getShipmentById(Long id){
        return shipmentMapper.selectById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ID not Found"));
    }

    public Shipment getShipmentByTrackingNumber(String trackingNumber){
        return shipmentMapper.selectByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking Number not Found"));
    }

    public Optional<Shipment> updateStatus(Long id, String newStatus){
        if (!ShipmentStatus.isValid(newStatus)) {
            throw new InvalidStatusException(newStatus);
        }
        if(shipmentMapper.selectById(id).isEmpty()) {
            throw new ResourceNotFoundException("ID not Found");
        }
        shipmentMapper.updateStatus(id, newStatus.toUpperCase());
        return shipmentMapper.selectById(id);
    }

    public void deleteShipment(Long id){
        if(shipmentMapper.selectById(id).isEmpty()) {
            throw new ResourceNotFoundException("ID not Found");
        }
        shipmentMapper.deleteById(id);
    }

    public void deleteShipmentByStatus(ShipmentStatus status){
        if(shipmentMapper.selectByStatus(String.valueOf(status)).equals(status)){
            shipmentMapper.deleteByStatus(String.valueOf(status));
        }
    }


}

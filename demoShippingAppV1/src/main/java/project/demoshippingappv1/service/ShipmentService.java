package project.demoshippingappv1.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import project.demoshippingappv1.dto.request.CreateShipmentRequest;
import project.demoshippingappv1.dto.request.StatusUpdateRequest;
import project.demoshippingappv1.exeception.DuplicateTrackingNumberException;
import project.demoshippingappv1.exeception.InvalidStatusException;
import project.demoshippingappv1.exeception.ResourceNotFoundException;
import project.demoshippingappv1.mapper.ShipmentMapper;
import project.demoshippingappv1.model.Shipment;
import project.demoshippingappv1.model.ShipmentStatus;

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
                .substring(0,8)
                .toUpperCase();
        // Check Duplicate Tracking Number
        if(shipmentMapper.selectByTrackingNumber(trackingNumber).isPresent()){
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

    public Optional<Shipment> selectById(Long id){
        return shipmentMapper.selectById(id);
    }

    public Optional<Shipment> selectByTrackingNumber(String trackingNumber){
        return shipmentMapper.selectByTrackingNumber(trackingNumber);
    }

    public List<Shipment> selectAll(){
        return shipmentMapper.selectAll();
    }

    public List<Shipment> selectByStatus(ShipmentStatus status){
        return shipmentMapper.selectByStatus(status);
    }

    public List<Shipment> selectBySenderName(String senderName){
        return shipmentMapper.selectBySenderName(senderName);
    }

    public List<Shipment> selectByReceiverName(String receiverName){
        return shipmentMapper.selectByReceiverName(receiverName);
    }

    public List<Shipment> selectByFilters(String senderName, String receiverName, ShipmentStatus status) {
        return shipmentMapper.selectByFilters(senderName, receiverName, status);
    }

    public List<Shipment> selectPage(int offset,int size) {
        return shipmentMapper.selectPage(offset, size);
    }

    public int countAll(){
        return shipmentMapper.countAll();
    }

    public int countByStatus(ShipmentStatus status){
        return shipmentMapper.countByStatus(status);
    }

    public Shipment updateFull(Shipment shipment){
        Shipment existing = shipmentMapper.selectById(shipment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));

        // preserve status unless explicitly part of update
        shipment.setStatus(existing.getStatus());
        int updated = shipmentMapper.updateFull(shipment);
        if (updated == 0){
            throw new ResourceNotFoundException("Shipment not found" + shipment.getId());
        }
        return shipmentMapper.selectById(shipment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));
    }

    public Optional<Shipment> updateStatus(Long id, StatusUpdateRequest request){
        if (!ShipmentStatus.isValid(String.valueOf(request.getStatus()))){
            throw new InvalidStatusException(request.getStatus());
        }
        if (shipmentMapper.selectById(id).isEmpty()){
            throw new ResourceNotFoundException("ID not Found");
        }
        shipmentMapper.updateStatus(id, request.getStatus());
        return shipmentMapper.selectById(id);
    }

    public void deleteById(Long id){
        if (shipmentMapper.selectById(id).isEmpty()){
            throw new ResourceNotFoundException(" ID not Found");
        }
        shipmentMapper.deleteById(id);
    }

    public void deleteByStatus(ShipmentStatus status){
        int deleted = shipmentMapper.deleteByStatus(status);
        if(deleted == 0){
            throw new ResourceNotFoundException("No Shipment with " + status + " is found");
        }
    }
}

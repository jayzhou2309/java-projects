package project.demoshippingappv1.mapper;

import org.apache.ibatis.annotations.*;
import project.demoshippingappv1.model.Shipment;
import project.demoshippingappv1.model.ShipmentStatus;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ShipmentMapper {
    void insert(Shipment shipment);
    Optional<Shipment> selectById(Long id);
    Optional<Shipment> selectByTrackingNumber(String trackingNumber);
    List<Shipment> selectAll();
    List<Shipment> selectByStatus(ShipmentStatus status);
    List<Shipment> selectBySenderName(String senderName);
    List<Shipment> selectByReceiverName(String receiverName);
    List<Shipment> selectByFilters(
            @Param("senderName")   String senderName,
            @Param("receiverName") String receiverName,
            @Param("status")       ShipmentStatus status
    );
    List<Shipment> selectPage(
            @Param("offset") int offset,
            @Param("size")   int size
    );
    int countAll();
    int countByStatus(ShipmentStatus status);
    int updateStatus(
            @Param("id")        Long id,
            @Param("newStatus") ShipmentStatus newStatus
    );
    int updateFull(Shipment shipment);
    int deleteById(Long id);
    int deleteByStatus(ShipmentStatus status);
}

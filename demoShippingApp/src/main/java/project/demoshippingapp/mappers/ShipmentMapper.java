package project.demoshippingapp.mappers;

import org.apache.ibatis.annotations.*;
import project.demoshippingapp.model.Shipment;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ShipmentMapper {
    void insert(Shipment shipment);
    Optional<Shipment> selectById(Long id);
    Optional<Shipment> selectByTrackingNumber(String trackingNumber);
    List<Shipment> selectAll();
    List<Shipment> selectByStatus(String status);
    List<Shipment> selectBySenderName(String senderName);
    List<Shipment> selectByReceiverName(String receiverName);
    List<Shipment> selectByFilters(
            @Param("senderName")   String senderName,
            @Param("receiverName") String receiverName,
            @Param("status")       String status
    );
    List<Shipment> selectPage(
            @Param("offset") int offset,
            @Param("size")   int size
    );
    int countAll();
    int countByStatus(String status);
    int updateStatus(
            @Param("id")        Long id,
            @Param("newStatus") String newStatus
    );
    int updateFull(Shipment shipment);
    int deleteById(Long id);
    int deleteByStatus(String status);
}

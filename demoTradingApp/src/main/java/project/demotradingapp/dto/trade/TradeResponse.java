package project.demotradingapp.dto.trade;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TradeResponse {
    private Long id;
    private String symbol;
    private BigDecimal quantity;
    private BigDecimal executionPrice;
    private LocalDateTime executedAt;
    private Long buyOrderId;
    private Long sellOrderId;
}

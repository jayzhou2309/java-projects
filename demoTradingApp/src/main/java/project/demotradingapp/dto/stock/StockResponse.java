package project.demotradingapp.dto.stock;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StockResponse {
    private Long id;
    private String symbol;
    private BigDecimal currentPrice;
    private boolean active;
}

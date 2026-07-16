package project.demotradingapp.dto.portfolio;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class HoldingResponse {
    private String symbol;
    private Long quantity;
    private BigDecimal averagePrice;
    private BigDecimal marketValue;
}

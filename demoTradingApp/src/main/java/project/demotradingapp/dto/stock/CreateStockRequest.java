package project.demotradingapp.dto.stock;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateStockRequest {
    private String symbol;
    private String companyName;
    private BigDecimal currentPrice;
}

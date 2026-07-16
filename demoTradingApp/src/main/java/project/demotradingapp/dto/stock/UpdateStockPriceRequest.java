package project.demotradingapp.dto.stock;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateStockPriceRequest {
    private BigDecimal currentPrice;
}

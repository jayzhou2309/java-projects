package project.demotradingapp.dto.stock;

import lombok.Data;

@Data
public class CreateStockRequest {
    private String symbol;
    private String companyName;
    private String currentPrice;
}

package project.demotradingapp.dto.stock;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StockResponse {
    private Long id;
    private String symbol;
    private String currentPrice;
    private boolean active;
}

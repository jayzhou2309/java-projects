package project.demotradingapp.dto.portfolio;

import lombok.Builder;
import lombok.Data;


import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class PortfolioResponse {
    private Long id;
    private BigDecimal availableCash;
    private BigDecimal reservedCash;
    private List<HoldingResponse> holdings;
}

package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.demotradingapp.dto.portfolio.HoldingResponse;
import project.demotradingapp.service.HoldingService;

@RestController
@RequestMapping("/holdings")
@RequiredArgsConstructor
public class HoldingsController {
    private final HoldingService holdingService;

    // GET /holdings
    @GetMapping("/")
    public HoldingResponse getHoldings(){
        return holdingService.getHolding();
    }
    // GET /holdings/{stockId}
}

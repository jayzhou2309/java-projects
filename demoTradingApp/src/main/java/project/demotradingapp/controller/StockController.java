package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import project.demotradingapp.dto.stock.CreateStockRequest;
import project.demotradingapp.dto.stock.StockResponse;
import project.demotradingapp.dto.stock.UpdateStockPriceRequest;
import project.demotradingapp.service.StockService;

import java.util.List;

@RequestMapping("/api/stocks")
@RestController
@RequiredArgsConstructor
public class StockController {
    private final StockService stockService;

    // GET    /stocks
    @GetMapping("/")
    public ResponseEntity<List<StockResponse>> getAllStocks(){
        return ResponseEntity.ok(stockService.getAllStocks());
    }

    // GET    /stocks/{id}
    @GetMapping("/{id}")
    public ResponseEntity<StockResponse> getStockResponse(@PathVariable Long stockId){
        return ResponseEntity.ok(stockService.getStockResponse(stockId));
    }

    // GET    /stocks/symbol/{symbol}
    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<StockResponse> getStockSymbol(@RequestBody String ticker){
        return ResponseEntity.ok(stockService.getStockByTicker(ticker));
    }

    // POST   /stocks
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/create")
    public ResponseEntity<StockResponse> createStock(@RequestBody CreateStockRequest request){
        return ResponseEntity.ok(stockService.createStock(request));
    }

    // PUT    /stocks/{id}
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update/{id}")
    public ResponseEntity<StockResponse> updateStock(@PathVariable Long stockId ,@RequestBody UpdateStockPriceRequest request){
        return ResponseEntity.ok(stockService.updateStock(stockId, request));
    }

    // DELETE /stocks/{id}
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteStock(@PathVariable Long stockId){
        stockService.deleteStock(stockId);
        return ResponseEntity.noContent().build();
    }
}

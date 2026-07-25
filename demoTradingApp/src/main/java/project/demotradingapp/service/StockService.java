package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.stock.CreateStockRequest;
import project.demotradingapp.dto.stock.StockResponse;
import project.demotradingapp.dto.stock.UpdateStockPriceRequest;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {
    public StockResponse getStock(Long stockId){

    }

    public StockResponse getStockByTicker(String ticker){

    }

    public List<StockResponse> getAllStocks(){

    }

    public StockResponse createStock(CreateStockRequest request){

    }

    public StockResponse updateStock(Long stockId, UpdateStockPriceRequest request){

    }

    public void deleteStock(Long stockId){

    }
}

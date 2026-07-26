package project.demotradingapp.service;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.stock.CreateStockRequest;
import project.demotradingapp.dto.stock.StockResponse;
import project.demotradingapp.dto.stock.UpdateStockPriceRequest;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;
import project.demotradingapp.entity.User;
import project.demotradingapp.mapper.StockMapper;
import project.demotradingapp.repository.StocksRepo;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockService {
    private final StockMapper stockMapper;
    private final StocksRepo stocksRepo;

    public StockResponse getStockResponse(Long stockId){
        Stock stock = stocksRepo.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));
        return stockMapper.toStockResponse(stock);
    }

    public Stock getStock(Long stockId){
        return stocksRepo.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));
    }

    public StockResponse getStockByTicker(String ticker){
        Stock stock = stocksRepo.findBySymbol(ticker)
                .orElseThrow(() -> new IllegalArgumentException("Stock Ticker not Found"));

        return stockMapper.toStockResponse(stock);
    }

    public List<StockResponse> getAllStocks(){
        List<Stock> stockList = stocksRepo.findAll();
        return stockList.stream()
                .map(stockMapper::toStockResponse)
                .toList();
    }

    @Transactional
    public StockResponse createStock(CreateStockRequest request){
        if (stocksRepo.existsBySymbol(request.getSymbol())){
            throw new IllegalArgumentException("Stock is already present");
        }
        if (request.getCurrentPrice().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Price must be greater than zero");
        }
        Stock stock = Stock.builder()
                .symbol(request.getSymbol())
                .companyName(request.getCompanyName())
                .currentPrice(request.getCurrentPrice())
                .active(true)
                .build();
        stocksRepo.save(stock);
        return stockMapper.toStockResponse(stock);
    }

    @Transactional
    public StockResponse updateStock(Long stockId, UpdateStockPriceRequest request){
        if (request.getCurrentPrice().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Price must be greater than zero");
        }
        Stock stock = stocksRepo.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("Stock ID not found"));
        stock.setCurrentPrice(request.getCurrentPrice());
        stocksRepo.save(stock);
        return stockMapper.toStockResponse(stock);
    }

    public void deleteStock(Long stockId){
        Stock stock = stocksRepo.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("Stock ID not found"));
        stock.setActive(false);
        stocksRepo.save(stock);
    }
}

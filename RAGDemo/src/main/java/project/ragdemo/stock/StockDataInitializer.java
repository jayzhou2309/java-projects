package project.ragdemo.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockDataInitializer implements CommandLineRunner {

    private final StockRepository stockRepository;

    @Value("${app.secclient.stock-data-init}")
    private boolean initialized;
    @Override
    public void run(String... args) throws Exception {
        if (!initialized) return;
        if (stockRepository.count() > 0) return;

        stockRepository.save(
                Stock.builder()
                        .symbol("AAPL")
                        .companyName("Apple Inc.")
                        .cik("0000320193")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("MSFT")
                        .companyName("Microsoft Corporation")
                        .cik("0000789019")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("GOOGL")
                        .companyName("Alphabet Inc.")
                        .cik("0001652044")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("AMZN")
                        .companyName("Amazon.com, Inc.")
                        .cik("0001018724")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("NVDA")
                        .companyName("NVIDIA Corporation")
                        .cik("0001045810")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("META")
                        .companyName("Meta Platforms, Inc.")
                        .cik("0001326801")
                        .active(true)
                        .build());
        stockRepository.save(
                Stock.builder()
                        .symbol("TSLA")
                        .companyName("Tesla Inc.")
                        .cik("0001318605")
                        .active(true)
                        .build());
    }
}

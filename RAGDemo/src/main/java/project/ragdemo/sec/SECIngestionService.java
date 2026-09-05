package project.ragdemo.sec;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import project.ragdemo.sec.client.SECClient;
import project.ragdemo.sec.client.SECHtmlParser;
import project.ragdemo.sec.dto.FilingMetadata;
import project.ragdemo.stock.Stock;
import project.ragdemo.stock.StockRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SECIngestionService {
    private final StockRepository stockRepository;
    private final FilingRepository filingRepository;
    private final SECClient secClient;
    private final SECHtmlParser sECHtmlParser;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();
    private final VectorStore vectorStore;

    public void ingestAllStocks() {
        List<Stock> stocks = stockRepository.findAll();

        for (Stock stock : stocks){

            System.out.println("Processing: " + stock.getSymbol());

            List<FilingMetadata> filings =
                    secClient.getAllRecentFilings(stock.getCik());

            // Check for Duplicate Filings
            for (FilingMetadata filing : filings){

                Optional<Filing> existingFiling = filingRepository.findByStockIdAndAccessionNo(stock.getId(), filing.accessionNo());
                Filing currentFiling;
                if (existingFiling.isPresent()){
                        currentFiling = existingFiling.get();
                        if (currentFiling.getStatus() != FilingStatus.FAILED){
                            System.out.println("Skipping Existing File: " + filing.accessionNo());
                            continue;
                        }
                    } else {
                    currentFiling = Filing.builder()
                            .stock(stock)
                            .filingType(FilingType.fromSecForm(filing.form()))
                            .accessionNo(filing.accessionNo())
                            .filedDate(LocalDate.parse(filing.filingDate()))
                            .sourceUrl(
                                    secClient.buildSourceURL(stock.getCik(), filing.accessionNo(), filing.primaryDocument()))
                            .build();
                    // 1st save: preserve state - filing status
                    filingRepository.save(currentFiling);
                }


                try {
                    String html = secClient.getFilingDocument(
                            stock.getCik(),
                            filing.accessionNo(),
                            filing.primaryDocument()
                    );

                    String text = sECHtmlParser.extractText(html);

                    Document document = new Document(
                            text, Map.of(
                                    "symbol", stock.getSymbol(),
                                "filingType", filing.form(),
                                "accessionNo", filing.accessionNo(),
                                "sourceUrl", currentFiling.getSourceUrl()
                            )
                    );
                    List<Document> chunks = splitter.apply(List.of(document));

                    vectorStore.add(chunks);

                    currentFiling.setStatus(FilingStatus.EMBEDDED);
                    currentFiling.setEmbeddedAt(LocalDateTime.now());
                    filingRepository.save(currentFiling);

                    System.out.println("Extracted Text: " + text.length() + " characters");
                    System.out.println("Chunks Created " + chunks.size());
                } catch (Exception e) {
                    currentFiling.setStatus(FilingStatus.FAILED);
                    filingRepository.save(currentFiling);
                    System.out.println("Failed Processing Filing: " + filing.accessionNo());
                    e.printStackTrace();
                }
            }
        }
    }

}

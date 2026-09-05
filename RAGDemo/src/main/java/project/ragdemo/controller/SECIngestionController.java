package project.ragdemo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.ragdemo.sec.SECIngestionService;

@RestController
@RequiredArgsConstructor
public class SECIngestionController {
    private final SECIngestionService secIngestionService;

    @GetMapping("/ingest-sec")
    public String ingestSEC(){
        System.out.println("Ingest Endpoint Hit");
        secIngestionService.ingestAllStocks();
        return "SEC Ingestion Completed";
    }
}

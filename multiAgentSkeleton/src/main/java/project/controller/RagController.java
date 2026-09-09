package project.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.rag.DocumentLoaderService;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class RagController {
    private final DocumentLoaderService documentLoaderService;

    @GetMapping("/rag/load")
    public String loadDocument(@RequestParam String filePath) throws IOException {
        return documentLoaderService.loadDocument(filePath);
    }
}

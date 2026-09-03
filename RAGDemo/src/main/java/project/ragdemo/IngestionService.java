package project.ragdemo;


import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;
    private final OpenAiChatModel chatModel;

    @Value("classpath:/static/apple10q.pdf")
    private Resource resource;

    @Value("${app.ingestion.enabled:false}")
    private boolean ingestionEnabled;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Model: " + chatModel.getOptions().getModel());
        if (!ingestionEnabled){
            return;
        }
        var pdfReader = new PagePdfDocumentReader(resource);
        TokenTextSplitter textSplitter = TokenTextSplitter.builder().build();
        vectorStore.accept(textSplitter.apply(pdfReader.get()));
        log.info("VectorStore loaded with Data!");
    }
}

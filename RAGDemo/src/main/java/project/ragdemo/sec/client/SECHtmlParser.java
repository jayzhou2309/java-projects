package project.ragdemo.sec.client;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public class SECHtmlParser {

    public String extractText(String html){
        Document document = Jsoup.parse(html);

        return document.body().text();
    }
}

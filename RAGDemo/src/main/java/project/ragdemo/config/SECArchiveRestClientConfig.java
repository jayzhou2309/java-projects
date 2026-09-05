package project.ragdemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class SECArchiveRestClientConfig {
    @Bean
    public RestClient secArchiveRestClient(
            @Value("${app.sec.archive-base-url}") String baseUrl
    ) {
        HttpClient httpClient = HttpClient.newHttpClient();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }
}

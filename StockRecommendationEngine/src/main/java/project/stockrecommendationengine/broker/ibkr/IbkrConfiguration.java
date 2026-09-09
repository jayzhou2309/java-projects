package project.stockrecommendationengine.broker.ibkr;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class IbkrConfiguration {
    @Bean
    IbkrApiClient ibkrApiClient(IbkrProperties properties) throws Exception {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER));
        if (!properties.getCertificate().isBlank()) {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (var input = Files.newInputStream(Path.of(properties.getCertificate()))) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw new IllegalArgumentException("IBKR certificate file is empty");
                int index = 0;
                for (var certificate : certificates) store.setCertificateEntry("ibkr-" + index++, certificate);
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
            builder.sslContext(ssl);
        }
        var factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        return new IbkrApiClient(RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(factory).build(), properties);
    }
}

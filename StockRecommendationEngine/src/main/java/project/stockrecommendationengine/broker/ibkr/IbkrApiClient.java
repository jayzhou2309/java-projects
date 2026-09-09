package project.stockrecommendationengine.broker.ibkr;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import project.stockrecommendationengine.broker.BrokerException;
import static project.stockrecommendationengine.broker.BrokerException.Code.*;

/** Package-private transport operations prevent callers outside the adapter from invoking arbitrary endpoints. */
public class IbkrApiClient {
    private final RestClient client;
    private final IbkrProperties properties;
    private long nextRequestNanos;

    public IbkrApiClient(RestClient client, IbkrProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    synchronized JsonNode get(String path) { return request(HttpMethod.GET, path); }
    synchronized JsonNode postSession(String path) {
        if (!path.equals("/tickle") && !path.equals("/iserver/auth/status")) {
            throw new IllegalArgumentException("Only session maintenance POSTs are allowed");
        }
        return request(HttpMethod.POST, path);
    }

    private JsonNode request(HttpMethod method, String path) {
        for (int attempt = 0; attempt < 2; attempt++) {
            pause(Math.max(0, (nextRequestNanos - System.nanoTime()) / 1_000_000));
            nextRequestNanos = System.nanoTime() + properties.getRequestIntervalMs() * 1_000_000L;
            try {
                JsonNode response = client.method(method).uri(path).retrieve().body(JsonNode.class);
                if (response == null || response.isNull() || response.has("error")) {
                    throw new BrokerException(INVALID_RESPONSE);
                }
                return response;
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 401 || status == 403) throw new BrokerException(LOGIN_REQUIRED);
                if (status == 429) throw new BrokerException(RATE_LIMITED);
                // Retry only transient GET failures. Never replay a session POST or an unknown request.
                if (method == HttpMethod.GET && attempt == 0 && (status == 502 || status == 503 || status == 504)) continue;
                throw new BrokerException(UNAVAILABLE);
            } catch (RestClientException ex) {
                throw new BrokerException(UNAVAILABLE);
            }
        }
        throw new BrokerException(UNAVAILABLE);
    }

    static void pause(long millis) {
        if (Thread.currentThread().isInterrupted()) throw new BrokerException(INTERRUPTED);
        try { if (millis > 0) Thread.sleep(millis); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerException(INTERRUPTED);
        }
    }
}

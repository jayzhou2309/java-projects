package project.stockrecommendationengine.broker.ibkr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.stockrecommendationengine.broker.BrokerData.SessionStatus;
import project.stockrecommendationengine.broker.BrokerException;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class IbkrSessionManager {
    private final IbkrApiClient client;

    public SessionStatus status() {
        var node = client.postSession("/iserver/auth/status");
        if (!node.path("connected").isBoolean() || !node.path("authenticated").isBoolean()) {
            throw new BrokerException(BrokerException.Code.INVALID_RESPONSE);
        }
        return new SessionStatus(node.path("connected").asBoolean(), node.path("authenticated").asBoolean(),
                node.path("established").isBoolean() ? node.path("established").asBoolean() : null,
                node.path("competing").asBoolean(false));
    }

    public void requireBrokerageSession() {
        if (!status().ready()) throw new BrokerException(BrokerException.Code.SESSION_NOT_READY);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void keepAlive() {
        try { client.postSession("/tickle"); }
        catch (BrokerException ex) { log.warn("IBKR keepalive failed: code={}", ex.code()); }
    }
}

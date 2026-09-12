package project.stockrecommendationengine.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import project.stockrecommendationengine.broker.api.BrokerController;
import project.stockrecommendationengine.broker.ibkr.IbkrProperties;
import project.stockrecommendationengine.outcome.OutcomeController;
import project.stockrecommendationengine.quant.QuantController;
import project.stockrecommendationengine.recommendation.RecommendationController;

/** Authorize the resolved controller, avoiding alternate URL encodings bypassing a path-prefix filter. */
@Configuration
public class IntegrationAccessConfiguration implements WebMvcConfigurer, HandlerInterceptor {
    private final byte[] expected;

    public IntegrationAccessConfiguration(IntegrationAccessProperties properties, IbkrProperties ibkr, Environment environment) {
        if ((ibkr.isEnabled() || environment.getProperty("recommendation.enabled", Boolean.class, false)
                || environment.getProperty("outcomes.enabled", Boolean.class, false))
                && properties.getToken().length() < 32) {
            throw new IllegalStateException("Set INTEGRATION_ACCESS_TOKEN to at least 32 characters before enabling integrations");
        }
        expected = ("Bearer " + properties.getToken()).getBytes(StandardCharsets.UTF_8);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !(BrokerController.class.isAssignableFrom(method.getBeanType())
                || QuantController.class.isAssignableFrom(method.getBeanType())
                || OutcomeController.class.isAssignableFrom(method.getBeanType())
                || RecommendationController.class.isAssignableFrom(method.getBeanType()))) return true;
        response.setHeader("Cache-Control", "no-store");
        String supplied = request.getHeader("Authorization");
        if (expected.length < 39 || supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            return false;
        }
        return true;
    }
}

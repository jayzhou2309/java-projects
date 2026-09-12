package project.stockrecommendationengine.access;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.SessionStatus;
import project.stockrecommendationengine.broker.api.BrokerController;
import project.stockrecommendationengine.broker.ibkr.IbkrProperties;
import project.stockrecommendationengine.rag.controller.RetrievalEvaluationController;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationRepository;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IntegrationAccessTests {
    @Test void protectsResolvedBrokerEndpointsIncludingMatrixParameters() throws Exception {
        var properties = new IntegrationAccessProperties();
        properties.setToken("test-token-with-at-least-32-characters");
        var access = new IntegrationAccessConfiguration(properties, new IbkrProperties(), new MockEnvironment());
        var broker = mock(BrokerReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BrokerController(broker)).addInterceptors(access).build();
        mvc.perform(get("/api/broker/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/broker;ignored/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/broker/session").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(broker);
        when(broker.sessionStatus()).thenReturn(new SessionStatus(true, true, true, false));
        mvc.perform(get("/api/broker/session").header("Authorization", "Bearer " + properties.getToken()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(broker).sessionStatus();
    }

    @Test void protectsRetrievalEvaluationEndpoints() throws Exception {
        var properties = new IntegrationAccessProperties();
        properties.setToken("test-token-with-at-least-32-characters");
        var access = new IntegrationAccessConfiguration(properties, new IbkrProperties(), new MockEnvironment());
        var service = mock(RetrievalEvaluationService.class);
        var repository = mock(RetrievalEvaluationRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RetrievalEvaluationController(service, repository)).addInterceptors(access).build();
        mvc.perform(post("/api/rag/evaluate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rag/evaluate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rag/evaluate/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rag;ignored/evaluate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rag/evaluate").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, repository);
        mvc.perform(get("/api/rag/evaluate").header("Authorization", "Bearer " + properties.getToken()))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/rag/evaluate/999999").header("Authorization", "Bearer " + properties.getToken())).andExpect(status().isNotFound());
        verify(repository).latest();
        verify(repository).findById(999999L);
    }

    @Test void everyGatedRequestIsRefusedWhileNoTokenIsConfigured() throws Exception {
        var access = new IntegrationAccessConfiguration(new IntegrationAccessProperties(), new IbkrProperties(), new MockEnvironment());
        var mvc = MockMvcBuilders.standaloneSetup(new RetrievalEvaluationController(mock(RetrievalEvaluationService.class),
                mock(RetrievalEvaluationRepository.class))).addInterceptors(access).build();
        mvc.perform(get("/api/rag/evaluate").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
    }

    @Test void refusesToEnableIntegrationsWithAMissingToken() {
        var ibkr = new IbkrProperties();
        ibkr.setEnabled(true);
        assertThatThrownBy(() -> new IntegrationAccessConfiguration(new IntegrationAccessProperties(), ibkr, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTEGRATION_ACCESS_TOKEN");
        assertThatThrownBy(() -> new IntegrationAccessConfiguration(new IntegrationAccessProperties(), new IbkrProperties(),
                new MockEnvironment().withProperty("recommendation.enabled", "true")))
                .isInstanceOf(IllegalStateException.class);
    }
}

package project.ragdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import project.ragdemo.controller.HealthController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "app.auth.username=test-researcher", "app.auth.password=test-only-password"
})
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    @Test void healthIsPublicAndMinimal() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test void otherRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/analysis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/monitor.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/analysis").with(httpBasic("test-researcher", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test void validCredentialsPassSecurityButDoNotInventAnAnalysisApi() throws Exception {
        mvc.perform(get("/api/v1/analysis").with(httpBasic("test-researcher", "test-only-password")))
                .andExpect(status().isNotFound());
    }

    @Test void csrfRemainsRequiredForWrites() throws Exception {
        mvc.perform(post("/api/v1/analysis").with(httpBasic("test-researcher", "test-only-password")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/analysis").with(httpBasic("test-researcher", "test-only-password")).with(csrf()))
                .andExpect(status().isNotFound());
    }
}

package project.ragdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Open endpoints — health checks, actuator info, etc.
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                // Basic auth for now — swap for API-key or JWT auth later
                .httpBasic(basic -> {})
                // CSRF is for browser/form clients; safe to disable for a stateless API used via curl/Postman
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
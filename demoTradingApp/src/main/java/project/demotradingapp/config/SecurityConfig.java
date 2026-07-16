package project.demotradingapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import project.demotradingapp.security.user.UserAccountDetailsService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserAccountDetailsService userAccountDetailsService;

    public SecurityConfig(UserAccountDetailsService userAccountDetailsService) {
        this.userAccountDetailsService = userAccountDetailsService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(){
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userAccountDetailsService);
        provider.setPasswordEncoder(passwordEncoder());

        return provider;
    }

    // Delegate Requests to AuthProvider
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}

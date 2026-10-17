package com.aepsy.voucher.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService adminUser(AdminProperties properties) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(properties.password())).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        // Stateless header authentication; this API does not use browser session cookies.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/claims", "/api/v1/claims/**", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setHeader("WWW-Authenticate", "Basic realm=\"voucher-admin\"");
                    mapper.writeValue(response.getOutputStream(), ApiErrorHandler.error("UNAUTHORIZED", "Admin authentication is required."));
                }).accessDeniedHandler((request, response, failure) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(), ApiErrorHandler.error("FORBIDDEN", "Admin permission is required."));
                }))
                .build();
    }
}

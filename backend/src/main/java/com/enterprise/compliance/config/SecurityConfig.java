package com.enterprise.compliance.config;

import com.enterprise.compliance.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService bootstrapUserDetailsService(
            @Value("${app.bootstrap.enabled}") boolean enabled,
            @Value("${app.bootstrap.username}") String username,
            @Value("${app.bootstrap.password}") String password,
            @Value("${server.address}") String address, PasswordEncoder encoder) {
        var users = new InMemoryUserDetailsManager();
        if (enabled) {
            if (!("127.0.0.1".equals(address) || "::1".equals(address))) {
                throw new IllegalStateException("Bootstrap CRUD requires loopback server.address");
            }
            if (username.isBlank() || password.length() < 12
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("Bootstrap credentials must be explicitly configured with a 12-72 byte password");
            }
            users.createUser(User.withUsername(username).password(encoder.encode(password))
                    .roles("BOOTSTRAP_ADMIN").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${app.bootstrap.enabled}") boolean enabled) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/health").permitAll();
                    if (enabled) {
                        auth.requestMatchers("/api/bootstrap/users", "/api/bootstrap/users/**")
                                .hasRole("BOOTSTRAP_ADMIN");
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> write(mapper, res, 401, "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> write(mapper, res, 403, "Access denied")));
        if (enabled) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(
                    (req, res, ex) -> write(mapper, res, 401, "Authentication required")));
        }
        return http.build();
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}

package com.iitsm.infrastructure.security;

import com.iitsm.infrastructure.config.SecurityTimeConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration: stateless JWT authentication, RBAC, BCrypt password hashing.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitRules rateLimitRules;
    private final ClientIpResolver clientIpResolver;
    private final Clock wallClock;
    private final MeterRegistry meterRegistry;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitRules rateLimitRules,
            ClientIpResolver clientIpResolver,
            @Qualifier(SecurityTimeConfig.WALL_CLOCK) Clock wallClock,
            MeterRegistry meterRegistry) {
        this.jwtAuthenticationFilter = Objects.requireNonNull(jwtAuthenticationFilter, "jwtAuthenticationFilter cannot be null");
        this.rateLimitRules = Objects.requireNonNull(rateLimitRules, "rateLimitRules cannot be null");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
    }

    /**
     * ADR-046: built here with {@code new}, not as a bean, so it runs inside the security chain only (a filter bean
     * would also be registered in the servlet container's chain).
     */
    private RateLimitFilter rateLimitFilter() {
        Counter rateLimited = Counter.builder(RateLimitFilter.METRIC)
                .description("Sign-in requests refused by the per-address rate limit")
                .register(meterRegistry);
        return new RateLimitFilter(rateLimitRules, clientIpResolver, wallClock, rateLimited);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"))
                        .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                        .frameOptions(org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Authentication required or token expired\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json");
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"errorCode\":\"ERR_FORBIDDEN\",\"message\":\"Access denied for this resource\"}");
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        // ADR-047: before the /api/auth/** rule below (the first matching rule wins)
                        .requestMatchers("/api/auth/logout-all").authenticated()
                        // ADR-048: only administrators set up, enable or disable two-step verification; verify stays public
                        .requestMatchers("/api/auth/mfa/setup", "/api/auth/mfa/enable", "/api/auth/mfa/disable")
                        .hasRole("ADMIN")
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/styles.css",
                                "/app.js",
                                "/favicon.ico",
                                "/assets/**",
                                "/error",
                                "/api/auth/**",
                                "/api/public/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health"
                        ).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/technicians").hasAnyRole("ADMIN", "TEAM_LEAD")
                        .requestMatchers("/api/admin/**", "/api/simulation/**").hasRole("ADMIN")
                        .requestMatchers("/api/technician/**").hasAnyRole("TECHNICIAN", "TEAM_LEAD", "ADMIN")
                        .requestMatchers("/api/employee/**").authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }
}


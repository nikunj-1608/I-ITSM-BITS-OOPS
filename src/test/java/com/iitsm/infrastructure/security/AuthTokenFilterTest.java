package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Authentication hardening, Phase S3 (ADR-047): the filter rejects an access token whose session was revoked, and
 * still accepts a token that carries no session id. Spring-free; the session store is mocked.
 */
class AuthTokenFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock WALL = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "unit-test-secret-key-that-is-at-least-32-bytes-long!";

    private final RefreshTokenRepository sessions = mock(RefreshTokenRepository.class);
    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 900_000, new MockEnvironment(), WALL, 5);
    private final Employee user = new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
            Designation.ASSOCIATE, "Engineering", 1);
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        UserDetailsService details = email -> new IitsmUserDetails(user,
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")), NOW);
        filter = new JwtAuthenticationFilter(provider, details, sessions, WALL);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse call(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("a token of a live session is accepted and signs the user in")
    void liveSession() throws Exception {
        when(sessions.isFamilyActive("family-1", NOW)).thenReturn(true);

        MockHttpServletResponse response = call(provider.generateToken(user, "family-1"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("a token of a revoked or expired session is 401 'Session revoked or expired' and signs nobody in")
    void revokedSession() throws Exception {
        when(sessions.isFamilyActive("family-1", NOW)).thenReturn(false);

        MockHttpServletResponse response = call(provider.generateToken(user, "family-1"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Session revoked or expired");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a token without a session id is accepted without asking the session store")
    void tokenWithoutSessionId() throws Exception {
        MockHttpServletResponse response = call(provider.generateToken(user));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(sessions, never()).isFamilyActive(any(), any());
    }

    @Test
    @DisplayName("the session is looked up with the wall clock instant")
    void usesTheWallClock() throws Exception {
        when(sessions.isFamilyActive("family-2", NOW)).thenReturn(true);

        call(provider.generateToken(user, "family-2"));

        verify(sessions).isFamilyActive("family-2", NOW);
    }
}

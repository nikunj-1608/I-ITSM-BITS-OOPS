package com.iitsm.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authentication hardening, Phases S3 and S4 (ADR-047, ADR-048): the URL rules of {@code SecurityConfig} are matched in
 * order and the first match wins, so the specific rules must stand before the {@code /api/auth/**} rule that lets
 * everyone in. Moving them below it would silently open the endpoints; this test fails then.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class SecurityConfigOrderingTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("S4: setup, enable and disable are not open to anonymous callers (401, not 200 or a validation error)")
    void mfaManagementIsNotPublic() throws Exception {
        for (String path : new String[] {"/api/auth/mfa/setup", "/api/auth/mfa/enable", "/api/auth/mfa/disable"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("S3: sign out everywhere is not open to anonymous callers")
    void logoutAllIsNotPublic() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the public sign-in endpoints stay public: login, verify, refresh, logout and register answer without a token")
    void publicEndpointsStayPublic() throws Exception {
        for (String path : new String[] {"/api/auth/login", "/api/auth/mfa/verify", "/api/auth/refresh",
                "/api/auth/logout", "/api/auth/register"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(result -> {
                        int answered = result.getResponse().getStatus();
                        if (answered == 401 || answered == 403) {
                            throw new AssertionError(path + " must not demand a token but answered " + answered);
                        }
                    });
        }
    }

    @Test
    @DisplayName("an unknown path under /api/auth is not protected by accident of the new rules")
    void otherAuthPathsKeepTheirRule() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}

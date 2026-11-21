package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.infrastructure.security.SecureOpaqueTokenGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Authentication hardening, Phase S3 (ADR-047): sessions over HTTP through the whole security chain. A session is a
 * short access token plus a rotating refresh token; a replayed refresh token, a sign-out and "sign out everywhere"
 * revoke access tokens at once, and a locked or deactivated account cannot renew.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class RefreshTokenRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String GENERIC_CODE = "ERR_INVALID_REFRESH_TOKEN";
    private static final int SESSION_CAP = 10;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecureOpaqueTokenGenerator opaqueTokens;

    private record Session(String email, String accessToken, String refreshToken) {}

    private String newUser() throws Exception {
        String email = "sess." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Session Test", "email", email,
                                "password", PASSWORD, "department", "Engineering"))))
                .andExpect(status().isOk());
        return email;
    }

    private Session login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return session(email, result);
    }

    private Session session(String email, MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Session(email, body.get("token").asText(), body.get("refreshToken").asText());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private ResultActions rawPost(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Session refreshed(String email, String refreshToken) throws Exception {
        return session(email, refresh(refreshToken).andExpect(status().isOk()).andReturn());
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken));
    }

    @Test
    @DisplayName("S3: sign-in returns an access token, a refresh token and the lifetime (15 minutes)")
    void loginStartsASession() throws Exception {
        String email = newUser();

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("S3: registration also starts a session")
    void registrationStartsASession() throws Exception {
        String email = "reg." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Reg Test", "email", email,
                                "password", PASSWORD, "department", "Engineering"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    @DisplayName("S3: /me has no tokens in its answer")
    void meCarriesNoTokens() throws Exception {
        Session session = login(newUser());

        me(session.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    @DisplayName("S3: refresh gives new tokens; the new access token works and the used refresh token is dead")
    void refreshRotates() throws Exception {
        Session first = login(newUser());

        Session second = refreshed(first.email(), first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        me(second.accessToken()).andExpect(status().isOk());
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3: replaying a used refresh token revokes the session: the newer refresh token and every access token die")
    void reuseKillsTheSession() throws Exception {
        Session first = login(newUser());
        Session second = refreshed(first.email(), first.refreshToken());
        me(first.accessToken()).andExpect(status().isOk());

        refresh(first.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(GENERIC_CODE));

        refresh(second.refreshToken()).andExpect(status().isUnauthorized());
        me(second.accessToken()).andExpect(status().isUnauthorized());
        me(first.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3: a replay of one session does not touch the user's other session")
    void reuseIsPerSession() throws Exception {
        String email = newUser();
        Session a = login(email);
        Session b = login(email);
        refreshed(email, a.refreshToken());

        refresh(a.refreshToken()).andExpect(status().isUnauthorized());

        me(b.accessToken()).andExpect(status().isOk());
        refresh(b.refreshToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S3: sign-out kills the access token at once and the refresh token; signing out again is still 204")
    void logoutRevokesAtOnce() throws Exception {
        Session session = login(newUser());
        me(session.accessToken()).andExpect(status().isOk());

        rawPost("/api/auth/logout", "{\"refreshToken\":\"" + session.refreshToken() + "\"}")
                .andExpect(status().isNoContent());

        me(session.accessToken()).andExpect(status().isUnauthorized());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        rawPost("/api/auth/logout", "{\"refreshToken\":\"" + session.refreshToken() + "\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("S3: sign-out is 204 for an unknown token, no token and a null token, and needs no access token")
    void logoutIsIdempotent() throws Exception {
        rawPost("/api/auth/logout", "{\"refreshToken\":\"" + "x".repeat(43) + "\"}").andExpect(status().isNoContent());
        rawPost("/api/auth/logout", "{}").andExpect(status().isNoContent());
        rawPost("/api/auth/logout", "{\"refreshToken\":null}").andExpect(status().isNoContent());
        rawPost("/api/auth/logout", "{\"refreshToken\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("S3: sign out everywhere ends every session of the user, the calling one included, and nobody else's")
    void logoutAllEndsEverySession() throws Exception {
        String email = newUser();
        Session a = login(email);
        Session b = login(email);
        Session stranger = login(newUser());

        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isNoContent());

        refresh(a.refreshToken()).andExpect(status().isUnauthorized());
        refresh(b.refreshToken()).andExpect(status().isUnauthorized());
        me(a.accessToken()).andExpect(status().isUnauthorized());
        me(b.accessToken()).andExpect(status().isUnauthorized());
        me(stranger.accessToken()).andExpect(status().isOk());
        login(email);
    }

    @Test
    @DisplayName("S3: sign out everywhere needs a valid access token")
    void logoutAllNeedsAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3: a deactivated user cannot refresh, and the session is gone")
    void deactivatedUserCannotRefresh() throws Exception {
        Session session = login(newUser());
        jdbc.update("UPDATE users SET active = false WHERE email = ?", session.email());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE users SET active = true WHERE email = ?", session.email());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        me(session.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3: a locked user cannot refresh")
    void lockedUserCannotRefresh() throws Exception {
        Session session = login(newUser());
        jdbc.update("UPDATE users SET locked = true, locked_until = ? WHERE email = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), session.email());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S3: after sign-out the same user can sign in again and gets a working session")
    void signInAgainAfterLogout() throws Exception {
        String email = newUser();
        Session first = login(email);
        rawPost("/api/auth/logout", "{\"refreshToken\":\"" + first.refreshToken() + "\"}");

        Session second = login(email);

        me(second.accessToken()).andExpect(status().isOk());
        refresh(second.refreshToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S3: a token never issued, a malformed or an oversized body is 401 or 400, never 500")
    void badInputIsNeverA500() throws Exception {
        refresh("x".repeat(43)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(GENERIC_CODE));
        refresh("é中文-not-base64-!@#").andExpect(status().isUnauthorized());
        refresh("x".repeat(201)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        refresh("   ").andExpect(status().isBadRequest());
        rawPost("/api/auth/refresh", "{}").andExpect(status().isBadRequest());
        rawPost("/api/auth/refresh", "{\"refreshToken\":null}").andExpect(status().isBadRequest());
        rawPost("/api/auth/refresh", "not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_MALFORMED_JSON"));
        rawPost("/api/auth/refresh", "{\"refreshToken\":123}").andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("S3: an unknown, expired-looking and revoked token all answer with the same body")
    void sameAnswerForEveryFailure() throws Exception {
        Session used = login(newUser());
        refreshed(used.email(), used.refreshToken());
        String unknown = refresh("x".repeat(43)).andReturn().getResponse().getContentAsString();
        String replayed = refresh(used.refreshToken()).andReturn().getResponse().getContentAsString();

        JsonNode a = objectMapper.readTree(unknown);
        JsonNode b = objectMapper.readTree(replayed);
        assertThat(a.get("code")).isEqualTo(b.get("code"));
        assertThat(a.get("message")).isEqualTo(b.get("message"));
        assertThat(a.get("status")).isEqualTo(b.get("status"));
    }

    @Test
    @DisplayName("S3: the database holds the SHA-256 hash of the refresh token, never the token, and no answer shows the hash")
    void databaseHoldsOnlyTheHash() throws Exception {
        String email = newUser();
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        Session session = session(email, result);
        String hash = opaqueTokens.hash(session.refreshToken());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, hash))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class,
                session.refreshToken())).isZero();
        assertThat(hash).hasSize(64).doesNotContain(session.refreshToken());
        assertThat(result.getResponse().getContentAsString()).doesNotContain(hash);
        MvcResult refreshed = refresh(session.refreshToken()).andReturn();
        assertThat(refreshed.getResponse().getContentAsString()).doesNotContain(hash).doesNotContain("tokenHash");
    }

    @Test
    @DisplayName("S3: the refresh token is 43 URL-safe characters of 256-bit randomness, different at every sign-in")
    void tokenShape() throws Exception {
        String email = newUser();
        Session a = login(email);
        Session b = login(email);

        assertThat(a.refreshToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(a.refreshToken()).isNotEqualTo(b.refreshToken());
    }

    @Test
    @DisplayName("S3: the 11th sign-in of a user ends their oldest session (cap 10) and no other")
    void sessionCap() throws Exception {
        String email = newUser();
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i <= SESSION_CAP; i++) {
            sessions.add(login(email));
        }

        refresh(sessions.get(0).refreshToken()).andExpect(status().isUnauthorized());
        me(sessions.get(0).accessToken()).andExpect(status().isUnauthorized());
        for (Session alive : sessions.subList(1, sessions.size())) {
            me(alive.accessToken()).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("S3: two simultaneous refreshes of one token: exactly one succeeds, never two new tokens")
    void simultaneousRefreshOfOneToken() throws Exception {
        Session session = login(newUser());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return refresh(session.refreshToken()).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = "
                + "(SELECT family_id FROM refresh_tokens WHERE token_hash = ?)", Integer.class,
                opaqueTokens.hash(session.refreshToken()))).isEqualTo(2);
    }
}

package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.SecurityAuditLog;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.security.Base32;
import com.iitsm.domain.security.TotpGenerator;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import com.iitsm.infrastructure.security.SecureOpaqueTokenGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Authentication hardening, rule "never log a secret" (ADR-046 onwards): every log line the application writes, at
 * DEBUG level, while passwords, tokens and refused attempts go through the sign-in endpoints, must not contain a
 * password or a token. Each phase adds its own secrets (S3 refresh tokens, S4 one-time codes and secrets).
 */
@SpringBootTest(properties = {
        "iitsm.security.rate-limit.enabled=true",
        "iitsm.security.rate-limit.login-capacity=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class SecurityLogHygieneTest {

    private static final String APP_LOGGER = "com.iitsm";
    private static final String AUDIT_LOGGER = SecurityAuditLog.LOGGER_NAME;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecureOpaqueTokenGenerator opaqueTokens;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger appLogger;
    private Logger auditLogger;
    private Level previousLevel;

    @BeforeEach
    void capture() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData(); // every class must pass alone (ADR-025): the test profile does not auto-seed
        }
        appLogger = (Logger) LoggerFactory.getLogger(APP_LOGGER);
        previousLevel = appLogger.getLevel();
        appLogger.setLevel(Level.DEBUG);
        auditLogger = (Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        captured.start();
        appLogger.addAppender(captured);
        auditLogger.addAppender(captured);
    }

    @AfterEach
    void release() {
        appLogger.detachAppender(captured);
        auditLogger.detachAppender(captured);
        appLogger.setLevel(previousLevel);
        captured.stop();
    }

    private ResultActions postJson(String path, String ip, Map<String, String> body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }));
    }

    /** Every captured line as text: the formatted message and the whole cause chain of an attached exception. */
    private List<String> loggedText() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(captured.list)) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                lines.add(cause.getClassName() + ": " + cause.getMessage());
            }
        }
        return lines;
    }

    @Test
    @DisplayName("S2: registration, login, a wrong password and 429s never log a password or a token")
    void signInFlowLogsNoSecret() throws Exception {
        String email = "hygiene." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        String password = "Secret-" + UUID.randomUUID();
        String wrongPassword = "Wrong-" + UUID.randomUUID();
        postJson("/api/auth/register", "10.47.0.1",
                Map.of("name", "Log Hygiene", "email", email, "password", password, "department", "Engineering"))
                .andExpect(status().isOk());
        String body = postJson("/api/auth/login", "10.47.0.2", Map.of("email", email, "password", password))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/login", "10.47.0.3", Map.of("email", email, "password", wrongPassword));
        }

        List<String> lines = loggedText();
        assertThat(lines).as("something was logged, so the capture works").isNotEmpty();
        assertThat(lines).noneMatch(line -> line != null
                && (line.contains(password) || line.contains(wrongPassword) || line.contains(token)));
    }
    @Test
    @DisplayName("S3: sessions (sign-in, refresh, a replay, sign-out everywhere) never log a refresh token, its hash or a token")
    void sessionFlowLogsNoSecret() throws Exception {
        String email = "hygiene." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        String password = "Secret-" + UUID.randomUUID();
        postJson("/api/auth/register", "10.47.1.1",
                Map.of("name", "Log Hygiene", "email", email, "password", password, "department", "Engineering"))
                .andExpect(status().isOk());
        JsonNode first = json(postJson("/api/auth/login", "10.47.1.2", Map.of("email", email, "password", password)));
        String access1 = first.get("token").asText();
        String refresh1 = first.get("refreshToken").asText();
        JsonNode second = json(postJson("/api/auth/refresh", "10.47.1.3", Map.of("refreshToken", refresh1)));
        String access2 = second.get("token").asText();
        String refresh2 = second.get("refreshToken").asText();
        postJson("/api/auth/refresh", "10.47.1.4", Map.of("refreshToken", refresh1)).andExpect(status().isUnauthorized());
        postJson("/api/auth/refresh", "10.47.1.5", Map.of("refreshToken", "bogus-" + UUID.randomUUID()));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access2)).andExpect(status().isUnauthorized());
        postJson("/api/auth/logout", "10.47.1.6", Map.of("refreshToken", refresh2)).andExpect(status().isNoContent());

        List<String> lines = loggedText();
        assertThat(lines).as("the security audit trail is written").anyMatch(line -> line != null && line.contains("REFRESH_REUSE_DETECTED"));
        for (String secret : List.of(password, access1, access2, refresh1, refresh2,
                opaqueTokens.hash(refresh1), opaqueTokens.hash(refresh2))) {
            assertThat(lines).noneMatch(line -> line != null && line.contains(secret));
        }
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("S4: two-step verification (setup, enable, sign-in, wrong codes, a recovery code, a reset) never logs a secret, a code or a challenge")
    void mfaFlowLogsNoSecret() throws Exception {
        String email = "hygiene." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        String password = "Secret-" + UUID.randomUUID();
        userService.createAdministrator(userRepository.findByEmail(new Email("admin@nimbusfinserve.com")).orElseThrow(),
                "Hygiene Admin", email, password, AdminLevel.SYSTEM_ADMIN);
        String access = json(postJson("/api/auth/login", "10.47.2.1", Map.of("email", email, "password", password)))
                .get("token").asText();
        JsonNode setup = json(postJson("/api/auth/mfa/setup", access, "10.47.2.2", Map.of()));
        String secretText = setup.get("secret").asText();
        byte[] secret = Base32.decode(secretText);
        long step = TotpGenerator.stepAt(Instant.now());
        String enableCode = TotpGenerator.codeAt(secret, step - 1);
        JsonNode enabled = json(postJson("/api/auth/mfa/enable", access, "10.47.2.3", Map.of("code", enableCode)));
        String recovery = enabled.get("recoveryCodes").get(0).asText();
        String challenge = json(postJson("/api/auth/login", "10.47.2.4", Map.of("email", email, "password", password)))
                .get("mfaToken").asText();
        String signInCode = TotpGenerator.codeAt(secret, step);
        json(postJson("/api/auth/mfa/verify", "10.47.2.5", Map.of("mfaToken", challenge, "code", signInCode)));
        postJson("/api/auth/mfa/verify", "10.47.2.6", Map.of("mfaToken", challenge, "code", "000000"));
        postJson("/api/auth/mfa/verify", "10.47.2.7", Map.of("mfaToken", challenge, "code", recovery.toLowerCase()))
                .andExpect(status().isOk());
        String adminToken = json(postJson("/api/auth/login", "10.47.2.8",
                Map.of("email", "admin@nimbusfinserve.com", "password", "Password123!"))).get("token").asText();
        String targetId = userRepository.findByEmail(new Email(email)).orElseThrow().getId();
        mockMvc.perform(post("/api/admin/users/" + targetId + "/mfa/reset").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        List<String> lines = loggedText();
        assertThat(lines).as("the audit trail records the MFA events").anyMatch(line -> line != null && line.contains("MFA_ENABLED"))
                .anyMatch(line -> line != null && line.contains("MFA_RESET"))
                .anyMatch(line -> line != null && line.contains("MFA_RECOVERY_CODE_USED"));
        for (String secretValue : List.of(password, secretText, enableCode, signInCode, recovery, recovery.toLowerCase(),
                challenge, access, setup.get("otpauthUri").asText())) {
            assertThat(lines).noneMatch(line -> line != null && line.contains(secretValue));
        }
    }

    private ResultActions postJson(String path, String token, String ip, Map<String, String> body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .header("Authorization", "Bearer " + token)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        return mockMvc.perform(request);
    }
}

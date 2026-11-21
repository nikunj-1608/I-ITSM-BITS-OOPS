package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.security.Base32;
import com.iitsm.domain.security.TotpGenerator;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.AesGcmSecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Authentication hardening, Phase S4 (ADR-048): optional TOTP two-step verification for administrators, over HTTP through
 * the whole security chain. Each test makes its own administrator (the seeded one stays without two-step verification,
 * because other tests sign in as it) and computes the codes with the production {@code TotpGenerator}.
 *
 * <p>Time steps: enabling uses the code of step {@code s-1}, the sign-in the code of step {@code s}: a code is accepted once
 * and only for a step newer than the last one, and the window lets the previous, current and next step through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class MfaLoginRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String SEEDED_ADMIN = "admin@nimbusfinserve.com";
    private static final String GENERIC_CODE = "ERR_INVALID_MFA_CODE";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AesGcmSecretCipher cipher;
    @Autowired private Clock sharedClock;

    private record Admin(String id, String email, String token, byte[] secret, String secretText) {}

    @BeforeEach
    void seed() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void resetSimulationClock() {
        if (sharedClock instanceof AdjustableClock adjustable) {
            adjustable.resetToRealTime();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private String newAdminEmail() {
        User seeded = userRepository.findByEmail(new Email(SEEDED_ADMIN)).orElseThrow();
        String email = "mfa." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        userService.createAdministrator(seeded, "Mfa Admin", email, PASSWORD, AdminLevel.SYSTEM_ADMIN);
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return rawPost("/api/auth/login", null, objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions rawPost(String path, String token, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private ResultActions postJson(String path, String token, Map<String, ?> body) throws Exception {
        return rawPost(path, token, objectMapper.writeValueAsString(body));
    }

    private String accessToken(String email) throws Exception {
        return json(login(email, PASSWORD).andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private static long currentStep() {
        return TotpGenerator.stepAt(Instant.now());
    }

    private static String code(byte[] secret, long step) {
        return TotpGenerator.codeAt(secret, step);
    }

    /** A 6-digit code that is not valid for any step the server could accept now. */
    private static String wrongCode(byte[] secret) {
        Set<String> valid = new java.util.HashSet<>();
        for (long step = currentStep() - 3; step <= currentStep() + 3; step++) {
            valid.add(code(secret, step));
        }
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String text = String.format("%06d", candidate);
            if (!valid.contains(text)) {
                return text;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Makes an administrator, sets up two-step verification and confirms it with the code of the previous step. */
    private Admin enrolledAdmin() throws Exception {
        String email = newAdminEmail();
        String token = accessToken(email);
        JsonNode setup = json(postJson("/api/auth/mfa/setup", token, Map.of()).andExpect(status().isOk()).andReturn());
        String secretText = setup.get("secret").asText();
        byte[] secret = Base32.decode(secretText);
        postJson("/api/auth/mfa/enable", token, Map.of("code", code(secret, currentStep() - 1)))
                .andExpect(status().isOk());
        User user = userRepository.findByEmail(new Email(email)).orElseThrow();
        return new Admin(user.getId(), email, token, secret, secretText);
    }

    private List<String> recoveryCodesOf(String email) throws Exception {
        // a second administrator enrolment, to read the codes the response returns
        String token = accessToken(email);
        JsonNode setup = json(postJson("/api/auth/mfa/setup", token, Map.of()).andExpect(status().isOk()).andReturn());
        byte[] secret = Base32.decode(setup.get("secret").asText());
        JsonNode enabled = json(postJson("/api/auth/mfa/enable", token, Map.of("code", code(secret, currentStep() - 1)))
                .andExpect(status().isOk()).andReturn());
        List<String> codes = new ArrayList<>();
        enabled.get("recoveryCodes").forEach(node -> codes.add(node.asText()));
        return codes;
    }

    /** Waits (at most ~10 s) until the real clock is early in a 30-second step, so a test that needs the next step's code cannot straddle a tick. */
    private static void awaitEarlyInAStep() throws InterruptedException {
        long secondsIntoStep = Instant.now().getEpochSecond() % TotpGenerator.STEP_SECONDS;
        if (secondsIntoStep > TotpGenerator.STEP_SECONDS - 12) {
            Thread.sleep((TotpGenerator.STEP_SECONDS - secondsIntoStep + 1) * 1000);
        }
    }

    private String challengeOf(String email) throws Exception {
        JsonNode body = json(login(email, PASSWORD).andExpect(status().isOk()).andReturn());
        return body.get("mfaToken").asText();
    }

    private ResultActions verify(String challenge, String code) throws Exception {
        return postJson("/api/auth/mfa/verify", null, Map.of("mfaToken", challenge, "code", code));
    }

    // ---- enrolment ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S4: setup returns the secret and the otpauth URI once; the database holds ciphertext, never the secret")
    void setupShowsTheSecretOnceAndStoresCiphertext() throws Exception {
        String email = newAdminEmail();
        String token = accessToken(email);

        JsonNode setup = json(postJson("/api/auth/mfa/setup", token, Map.of())
                .andExpect(status().isOk()).andReturn());

        String secret = setup.get("secret").asText();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);
        String stored = jdbc.queryForObject("SELECT mfa_secret FROM users WHERE email = ?", String.class, email);
        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(jdbc.queryForObject("SELECT mfa_enabled FROM users WHERE email = ?", Boolean.class, email)).isFalse();
        String id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        assertThat(cipher.decrypt(stored, id)).isEqualTo(secret);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    @Test
    @DisplayName("S4: setup again before confirming gives a new secret; the old one no longer confirms")
    void setupAgainReplacesThePendingSecret() throws Exception {
        String token = accessToken(newAdminEmail());
        byte[] first = Base32.decode(json(postJson("/api/auth/mfa/setup", token, Map.of()).andReturn()).get("secret").asText());
        byte[] second = Base32.decode(json(postJson("/api/auth/mfa/setup", token, Map.of()).andReturn()).get("secret").asText());

        assertThat(second).isNotEqualTo(first);
        postJson("/api/auth/mfa/enable", token, Map.of("code", code(first, currentStep())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(GENERIC_CODE));
        postJson("/api/auth/mfa/enable", token, Map.of("code", code(second, currentStep())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S4: enabling returns 8 recovery codes once; /me then says two-step verification is on")
    void enableReturnsRecoveryCodes() throws Exception {
        String email = newAdminEmail();
        List<String> codes = recoveryCodesOf(email);

        assertThat(codes).hasSize(8).allMatch(c -> c.matches("[A-Z2-7]{10}"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id = "
                + "(SELECT id FROM users WHERE email = ?)", Integer.class, email)).isEqualTo(8);
        for (String stored : jdbc.queryForList("SELECT code_hash FROM mfa_recovery_codes WHERE user_id = "
                + "(SELECT id FROM users WHERE email = ?)", String.class, email)) {
            assertThat(codes).doesNotContain(stored);
        }
        assertThat(jdbc.queryForObject("SELECT mfa_enabled FROM users WHERE email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    @DisplayName("S4: confirming without a setup, or an account that already has it, is a 409 with its own code")
    void enrolmentConflicts() throws Exception {
        String token = accessToken(newAdminEmail());

        postJson("/api/auth/mfa/enable", token, Map.of("code", "123456"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_MFA_NOT_ENROLLED"));
        postJson("/api/auth/mfa/disable", token, Map.of("password", PASSWORD, "code", "123456"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_MFA_NOT_ENROLLED"));
        Admin enrolled = enrolledAdmin();
        postJson("/api/auth/mfa/setup", enrolled.token(), Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_MFA_ALREADY_ENABLED"));
        postJson("/api/auth/mfa/enable", enrolled.token(), Map.of("code", "123456"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_MFA_ALREADY_ENABLED"));
    }

    @Test
    @DisplayName("S4: bad bodies are 400, never 500")
    void badBodies() throws Exception {
        String token = accessToken(newAdminEmail());

        rawPost("/api/auth/mfa/enable", token, "{}").andExpect(status().isBadRequest());
        rawPost("/api/auth/mfa/enable", token, "{\"code\":\"" + "9".repeat(33) + "\"}").andExpect(status().isBadRequest());
        rawPost("/api/auth/mfa/enable", token, "not json").andExpect(status().isBadRequest());
        rawPost("/api/auth/mfa/disable", token, "{\"code\":\"123456\"}").andExpect(status().isBadRequest());
        rawPost("/api/auth/mfa/verify", null, "{\"mfaToken\":\"x\"}").andExpect(status().isBadRequest());
        rawPost("/api/auth/mfa/verify", null, "{}").andExpect(status().isBadRequest());
    }

    // ---- who may enrol ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S4: employee, technician and team lead get 403 on setup, enable and disable; no token gets 401")
    void onlyAdministrators() throws Exception {
        for (String email : List.of("employee@nimbusfinserve.com", "tech.network@nimbusfinserve.com",
                "lead.network@nimbusfinserve.com")) {
            String token = accessToken(email);
            postJson("/api/auth/mfa/setup", token, Map.of()).andExpect(status().isForbidden());
            postJson("/api/auth/mfa/enable", token, Map.of("code", "123456")).andExpect(status().isForbidden());
            postJson("/api/auth/mfa/disable", token, Map.of("password", PASSWORD, "code", "123456"))
                    .andExpect(status().isForbidden());
        }
        postJson("/api/auth/mfa/setup", null, Map.of()).andExpect(status().isUnauthorized());
        postJson("/api/auth/mfa/enable", null, Map.of("code", "123456")).andExpect(status().isUnauthorized());
        postJson("/api/auth/mfa/disable", null, Map.of("password", PASSWORD, "code", "123456"))
                .andExpect(status().isUnauthorized());
    }

    // ---- signing in ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S4: with two-step verification the password step returns a challenge and NO access token and NO refresh token")
    void passwordStepReturnsOnlyAChallenge() throws Exception {
        Admin admin = enrolledAdmin();

        login(admin.email(), PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaToken").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    @DisplayName("S4: a wrong password is the same generic 401 as before, with no hint about two-step verification")
    void wrongPasswordGivesNoHint() throws Exception {
        Admin admin = enrolledAdmin();

        String body = login(admin.email(), "Wrong-" + PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("mfa").doesNotContain("Mfa");
    }

    @Test
    @DisplayName("S4: the right code returns tokens that work; the same code again is refused (replay)")
    void rightCodeThenReplay() throws Exception {
        Admin admin = enrolledAdmin();
        String challenge = challengeOf(admin.email());
        String code = code(admin.secret(), currentStep());

        MvcResult done = verify(challenge, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900)).andReturn();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + json(done).get("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaEnabled").value(true));
        verify(challengeOf(admin.email()), code).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(GENERIC_CODE));
    }

    @Test
    @DisplayName("S4: a wrong code is 401 ERR_INVALID_MFA_CODE; five wrong codes lock the account like five wrong passwords")
    void fiveWrongCodesLockTheAccount() throws Exception {
        Admin admin = enrolledAdmin();
        String challenge = challengeOf(admin.email());
        String wrong = wrongCode(admin.secret());

        for (int i = 0; i < 5; i++) {
            verify(challenge, wrong).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(GENERIC_CODE));
        }

        assertThat(jdbc.queryForObject("SELECT locked FROM users WHERE email = ?", Boolean.class, admin.email())).isTrue();
        login(admin.email(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
        verify(challenge, code(admin.secret(), currentStep())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM users WHERE email = ?", Boolean.class,
                admin.email())).isTrue();
    }

    @Test
    @DisplayName("S4: the password step does not clear the failure count of an account with two-step verification; the code does")
    void passwordStepDoesNotResetTheCount() throws Exception {
        Admin admin = enrolledAdmin();
        String challenge = challengeOf(admin.email());
        String wrong = wrongCode(admin.secret());
        for (int i = 0; i < 3; i++) {
            verify(challenge, wrong).andExpect(status().isUnauthorized());
        }

        login(admin.email(), PASSWORD).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class,
                admin.email())).isEqualTo(3);
        verify(challenge, code(admin.secret(), currentStep())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class,
                admin.email())).isZero();
    }

    @Test
    @DisplayName("S4: a recovery code signs in once, typed in lower case with a hyphen; the second use is refused")
    void recoveryCodeWorksOnce() throws Exception {
        String email = newAdminEmail();
        List<String> codes = recoveryCodesOf(email);
        String typed = codes.get(0).substring(0, 5).toLowerCase() + "-" + codes.get(0).substring(5);

        verify(challengeOf(email), typed).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());

        verify(challengeOf(email), codes.get(0)).andExpect(status().isUnauthorized());
        verify(challengeOf(email), codes.get(1)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S4: the challenge is not an access token, and an access token is not a challenge")
    void tokensAreNotInterchangeable() throws Exception {
        Admin admin = enrolledAdmin();
        String challenge = challengeOf(admin.email());
        String access = json(verify(challenge, code(admin.secret(), currentStep())).andExpect(status().isOk()).andReturn())
                .get("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());
        verify(access, code(admin.secret(), currentStep() + 1)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(GENERIC_CODE));
        verify("garbage", "123456").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(GENERIC_CODE));
    }

    @Test
    @DisplayName("S4: the code steps follow the real clock: moving the simulation clock an hour changes nothing")
    void simulationClockDoesNotChangeTheCodes() throws Exception {
        Admin admin = enrolledAdmin();
        String seededToken = accessToken(SEEDED_ADMIN);
        mockMvc.perform(post("/api/simulation/advance?minutes=60").header("Authorization", "Bearer " + seededToken))
                .andExpect(status().isOk());

        verify(challengeOf(admin.email()), code(admin.secret(), currentStep())).andExpect(status().isOk());
    }

    // ---- turning it off ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S4: disabling needs the password AND a valid code; afterwards sign-in needs no code and the codes are gone")
    void disable() throws Exception {
        awaitEarlyInAStep();
        Admin admin = enrolledAdmin();
        String live = json(verify(challengeOf(admin.email()), code(admin.secret(), currentStep())).andReturn()).get("token").asText();

        postJson("/api/auth/mfa/disable", live, Map.of("password", "wrong", "code", code(admin.secret(), currentStep() + 1)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ERR_INVALID_CREDENTIALS"));
        postJson("/api/auth/mfa/disable", live, Map.of("password", PASSWORD, "code", wrongCode(admin.secret())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(GENERIC_CODE));
        assertThat(jdbc.queryForObject("SELECT mfa_enabled FROM users WHERE email = ?", Boolean.class, admin.email())).isTrue();

        postJson("/api/auth/mfa/disable", live, Map.of("password", PASSWORD, "code", code(admin.secret(), currentStep() + 1)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT mfa_enabled FROM users WHERE email = ?", Boolean.class, admin.email())).isFalse();
        assertThat(jdbc.queryForObject("SELECT mfa_secret FROM users WHERE email = ?", String.class, admin.email())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mfa_recovery_codes WHERE user_id = ?", Integer.class,
                admin.id())).isZero();
        login(admin.email(), PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    @DisplayName("S4: another administrator can reset it (sessions end, sign-in needs no code); resetting oneself is refused")
    void adminReset() throws Exception {
        Admin target = enrolledAdmin();
        String targetSession = json(verify(challengeOf(target.email()), code(target.secret(), currentStep())).andReturn())
                .get("token").asText();
        String seededToken = accessToken(SEEDED_ADMIN);
        String seededId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, SEEDED_ADMIN);

        postJson("/api/admin/users/" + seededId + "/mfa/reset", seededToken, Map.of()).andExpect(status().isBadRequest());
        postJson("/api/admin/users/" + target.id() + "/mfa/reset", seededToken, Map.of()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + targetSession))
                .andExpect(status().isUnauthorized());
        login(target.email(), PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        postJson("/api/admin/users/" + target.id() + "/mfa/reset", seededToken, Map.of()).andExpect(status().isConflict());
        postJson("/api/admin/users/NOBODY/mfa/reset", seededToken, Map.of()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S4: only an administrator can reset; an employee gets 403, no token gets 401")
    void resetNeedsAnAdministrator() throws Exception {
        Admin target = enrolledAdmin();

        postJson("/api/admin/users/" + target.id() + "/mfa/reset", accessToken("employee@nimbusfinserve.com"), Map.of())
                .andExpect(status().isForbidden());
        postJson("/api/admin/users/" + target.id() + "/mfa/reset", null, Map.of()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT mfa_enabled FROM users WHERE email = ?", Boolean.class, target.email())).isTrue();
    }

    @Test
    @DisplayName("S4: no response after setup contains the secret, and the login answers never contain it")
    void secretIsNeverReturnedAgain() throws Exception {
        Admin admin = enrolledAdmin();
        List<String> answers = new ArrayList<>();
        answers.add(login(admin.email(), PASSWORD).andReturn().getResponse().getContentAsString());
        String challenge = challengeOf(admin.email());
        answers.add(verify(challenge, code(admin.secret(), currentStep())).andReturn().getResponse().getContentAsString());
        answers.add(mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + admin.token()))
                .andReturn().getResponse().getContentAsString());

        assertThat(answers).noneMatch(answer -> answer.contains(admin.secretText()));
        assertThat(answers).noneMatch(answer -> answer.contains("mfa_secret") || answer.contains("mfaSecret"));
    }
}

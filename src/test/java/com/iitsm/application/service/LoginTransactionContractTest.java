package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item P1): the shape that keeps a login from starving the
 * connection pool. {@code LoginPoolStarvationRegressionTest} proves the behaviour; this pins the annotations that
 * cause it, because the failure it prevents only shows under load.
 */
class LoginTransactionContractTest {

    @Test
    @DisplayName("P1: UserService has no class-level transaction and authenticate has none, not even SUPPORTS")
    void authenticateRunsWithoutATransaction() throws NoSuchMethodException {
        Method authenticate = UserService.class.getMethod("authenticate", String.class, String.class);

        assertThat(UserService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(authenticate.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("S3: verifyCredentials (the password check behind every sign-in) has no transaction either")
    void verifyCredentialsRunsWithoutATransaction() throws NoSuchMethodException {
        Method verify = UserService.class.getMethod("verifyCredentials", String.class, String.class);

        assertThat(verify.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("S3: AuthSessionService.login holds no transaction around the password check; sessions open their own")
    void sessionLoginHasNoTransactionOfItsOwn() throws NoSuchMethodException {
        Method login = AuthSessionService.class.getMethod("login", String.class, String.class);
        Method issue = RefreshTokenService.class.getMethod("issueSession", com.iitsm.domain.model.User.class);

        assertThat(AuthSessionService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(login.getAnnotation(Transactional.class)).isNull();
        assertThat(RefreshTokenService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(issue.getAnnotation(Transactional.class)).isNotNull();
        assertThat(issue.getAnnotation(Transactional.class).propagation()).isNotEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("P1: the writing methods of UserService each declare their own transaction")
    void writingMethodsStayTransactional() {
        for (String name : new String[] {"registerEmployee", "createTechnician", "createTeamLead", "createAdministrator",
                "updateEmployeeDesignation", "unlockUser"}) {
            Method method = Arrays.stream(UserService.class.getMethods()).filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class)).as(name).isNotNull();
        }
    }

    @Test
    @DisplayName("P1: LoginAttemptService opens no nested transaction (no REQUIRES_NEW)")
    void loginAttemptsUseOrdinaryTransactions() {
        for (Method method : LoginAttemptService.class.getDeclaredMethods()) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            if (transactional != null) {
                assertThat(transactional.propagation()).as(method.getName()).isNotEqualTo(Propagation.REQUIRES_NEW);
            }
        }
        assertThat(LoginAttemptService.class.getAnnotation(Transactional.class)).isNull();
    }
}

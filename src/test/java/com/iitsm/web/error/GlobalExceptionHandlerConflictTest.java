package com.iitsm.web.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.domain.exception.ConcurrentUpdateException;
import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.exception.ServiceBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADR-017: every way a lost write race can reach the web layer ends as HTTP 409, never 500. */
class GlobalExceptionHandlerConflictTest {

    private static final String CONFLICT_CODE = "ERR_CONCURRENT_MODIFICATION";

    @RestController
    static class StubController {

        @GetMapping("/commit-conflict")
        String commitConflict() {
            // Spring raises this when the transaction commits, after the service method has returned.
            throw new ObjectOptimisticLockingFailureException("com.iitsm.IncidentJpaEntity", "FIN-2026-000001");
        }

        @GetMapping("/query-conflict")
        String queryConflict() {
            // The adapter wraps a conflict thrown inside a read query (auto-flush) like this.
            throw new PersistenceFailureException("Failed to retrieve incidents for reporter: EMP-1",
                    new ObjectOptimisticLockingFailureException("com.iitsm.IncidentJpaEntity", "FIN-2026-000001"));
        }

        @GetMapping("/deeply-wrapped")
        String deeplyWrapped() {
            throw new PersistenceFailureException("outer",
                    new IllegalStateException("middle", new CannotAcquireLockException("lock timeout")));
        }

        @GetMapping("/domain-conflict")
        String domainConflict() {
            throw new ConcurrentUpdateException(new RuntimeException("stale"));
        }

        @GetMapping("/lock-timeout")
        String lockTimeout() {
            throw new CannotAcquireLockException("could not obtain lock");
        }

        @GetMapping("/other-persistence-failure")
        String otherPersistenceFailure() {
            throw new PersistenceFailureException("Failed to save incident: X",
                    new DataIntegrityViolationException("constraint"));
        }

        @GetMapping("/busy")
        String busy() {
            throw new ServiceBusyException();
        }

        @GetMapping("/unrelated")
        String unrelated() {
            throw new IllegalStateException("boom");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void assertConflict(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CONFLICT_CODE))
                .andExpect(jsonPath("$.message").value(ConcurrentUpdateException.USER_MESSAGE));
    }

    @Test
    @DisplayName("A lock failure thrown at commit is a 409, not 'unexpected server error'")
    void commitTimeConflictIsConflict() throws Exception {
        assertConflict("/commit-conflict");
    }

    @Test
    @DisplayName("A lock failure thrown inside a query and wrapped as PersistenceFailureException is a 409")
    void queryTimeConflictIsConflict() throws Exception {
        assertConflict("/query-conflict");
    }

    @Test
    @DisplayName("The cause chain is searched, however deeply the lock failure is wrapped")
    void deeplyWrappedConflictIsConflict() throws Exception {
        assertConflict("/deeply-wrapped");
    }

    @Test
    @DisplayName("ConcurrentUpdateException and lock-acquisition timeouts are 409 as well")
    void domainConflictAndLockTimeoutAreConflict() throws Exception {
        assertConflict("/domain-conflict");
        assertConflict("/lock-timeout");
    }

    @Test
    @DisplayName("The conflict message never leaks entity names or ids")
    void conflictMessageHasNoInternals() throws Exception {
        mvc.perform(get("/commit-conflict"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Incident"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("FIN-2026"))));
    }

    @Test
    @DisplayName("ADR-018: a report that cannot get its turn is 503 with a stable code, not 500")
    void busyIsServiceUnavailable() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ServiceBusyException.ERROR_CODE))
                .andExpect(jsonPath("$.message").value(ServiceBusyException.USER_MESSAGE));
    }

    @Test
    @DisplayName("Other persistence failures and unrelated errors are still 500")
    void nonConflictErrorsStayServerErrors() throws Exception {
        mvc.perform(get("/other-persistence-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ERR_PERSISTENCE_FAILURE"));
        mvc.perform(get("/unrelated"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ERR_INTERNAL_SERVER_ERROR"));
    }
}

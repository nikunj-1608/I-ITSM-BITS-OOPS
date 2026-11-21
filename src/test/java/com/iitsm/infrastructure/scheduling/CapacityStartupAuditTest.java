package com.iitsm.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iitsm.application.service.CapacityReconciliationService;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.CapacityAudit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CapacityStartupAuditTest {

    private CapacityReconciliationService reconciliationService;
    private CapacityStartupAudit startupAudit;
    private Logger logger;
    private ListAppender<ILoggingEvent> logLines;

    @BeforeEach
    void setUp() {
        reconciliationService = mock(CapacityReconciliationService.class);
        startupAudit = new CapacityStartupAudit(reconciliationService);
        logger = (Logger) LoggerFactory.getLogger(CapacityStartupAudit.class);
        logLines = new ListAppender<>();
        logLines.start();
        logger.addAppender(logLines);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logLines);
    }

    private static Technician technicianWithPhantomSlot() {
        Technician technician = new Technician("TECH-1", "Tech", new Email("tech-1@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, 3, "TEAM-NET");
        technician.assignIncident("INC-GHOST");
        return technician;
    }

    @Test
    @DisplayName("A consistent state is logged as one INFO line")
    void consistentStateIsInfo() {
        when(reconciliationService.audit()).thenReturn(CapacityAudit.of(List.of(), List.of()));

        startupAudit.onApplicationEvent(null);

        assertThat(logLines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.INFO);
            assertThat(line.getFormattedMessage()).contains("match the open tickets");
        });
    }

    @Test
    @DisplayName("A difference is logged as one WARN line that says where to look and how to repair")
    void differenceIsWarning() {
        when(reconciliationService.audit()).thenReturn(CapacityAudit.of(List.of(technicianWithPhantomSlot()), List.of()));

        startupAudit.onApplicationEvent(null);

        assertThat(logLines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("1 difference(s)").contains("PHANTOM_SLOT")
                    .contains("/api/admin/capacity/reconcile");
        });
    }

    @Test
    @DisplayName("A failing check is logged as an error and never stops the application from starting")
    void failureDoesNotStopStartup() {
        when(reconciliationService.audit()).thenThrow(new IllegalStateException("database not ready"));

        assertThatCode(() -> startupAudit.onApplicationEvent(null)).doesNotThrowAnyException();

        assertThat(logLines.list).singleElement().satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.ERROR));
    }
}

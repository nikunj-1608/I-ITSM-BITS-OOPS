package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.iitsm.application.port.IncidentSearchPort;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class SearchServiceTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private com.iitsm.domain.repository.UserRepository userRepository;

    @Autowired
    private com.iitsm.infrastructure.persistence.CompanySeeder companySeeder;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @Test
    @DisplayName("SearchService executes multi-criteria search and paged search successfully")
    void testSearchAndPagination() {
        Incident incident = incidentService.createIncident(
                "EMP-0001",
                "Kubernetes pod crashing with OOMKilled",
                "Service gateway pod restarted 12 times in the last hour.");

        SearchService.IncidentSearchCriteria criteria = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, "EMP-0001", "Kubernetes", null, null);

        List<Incident> results = searchService.search(criteria);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getTitle()).contains("Kubernetes");

        Page<Incident> paged = searchService.searchPaged(criteria, PageRequest.of(0, 10));
        assertThat(paged.getContent()).isNotEmpty();
        assertThat(paged.getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Text search finds a ticket by its id and by its staff display name (priority + id)")
    void testSearchByTicketIdAndDisplayName() {
        Incident incident = incidentService.createIncident(
                "EMP-0001",
                "Zebra cluster failover stalls",
                "Failover of the zebra cluster stalls for several minutes every night.");

        SearchService.IncidentSearchCriteria byId = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, null, incident.getId(), null, null);
        SearchService.IncidentSearchCriteria byDisplayName = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, null, "P2-" + incident.getId(), null, null);
        SearchService.IncidentSearchCriteria byPartialName = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, null, "P2-" + incident.getId().substring(0, 3), null, null);

        assertThat(searchService.search(byId)).extracting(Incident::getId).contains(incident.getId());
        assertThat(searchService.search(byDisplayName)).extracting(Incident::getId).containsExactly(incident.getId());
        assertThat(searchService.search(byPartialName)).extracting(Incident::getId)
                .as("a half-typed display name is not guessed at").doesNotContain(incident.getId());
    }

    @Test
    @DisplayName("Status and SLA status sets reach the search port unchanged (ADR-049)")
    void testStatusAndSlaStatusPassThrough() {
        IncidentSearchPort port = mock(IncidentSearchPort.class);
        SearchService service = new SearchService(port);
        Set<IncidentStatus> statuses = Set.of(IncidentStatus.NEW, IncidentStatus.IN_PROGRESS);
        Set<SlaStatus> slaStatuses = Set.of(SlaStatus.BREACHED, SlaStatus.AT_RISK);

        service.search(criteria(statuses, slaStatuses, null, null));
        service.searchPaged(criteria(statuses, slaStatuses, null, null), PageRequest.of(0, 5));

        ArgumentCaptor<IncidentSearchPort.SearchCriteria> captor =
                ArgumentCaptor.forClass(IncidentSearchPort.SearchCriteria.class);
        verify(port).search(captor.capture());
        assertThat(captor.getValue().statuses()).isEqualTo(statuses);
        assertThat(captor.getValue().slaStatuses()).isEqualTo(slaStatuses);
        verify(port).searchPaged(captor.capture(), any());
        assertThat(captor.getValue().statuses()).isEqualTo(statuses);
        assertThat(captor.getValue().slaStatuses()).isEqualTo(slaStatuses);
    }

    @Test
    @DisplayName("fromDate after toDate is a validation error naming fromDate, and the port is not called")
    void testFromDateAfterToDateIsRefused() {
        IncidentSearchPort port = mock(IncidentSearchPort.class);
        SearchService service = new SearchService(port);
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        Instant from = to.plusMillis(1);

        assertThatThrownBy(() -> service.search(criteria(null, null, from, to)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("fromDate")
                .extracting("errorCode").isEqualTo("ERR_VALIDATION_FAILED");
        assertThatThrownBy(() -> service.searchPaged(criteria(null, null, from, to), PageRequest.of(0, 5)))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("Equal fromDate and toDate, or only one of them, are accepted")
    void testEqualOrOpenDateRangeIsAccepted() {
        IncidentSearchPort port = mock(IncidentSearchPort.class);
        SearchService service = new SearchService(port);
        Instant at = Instant.parse("2026-11-01T00:00:00Z");

        assertThatCode(() -> service.search(criteria(null, null, at, at))).doesNotThrowAnyException();
        assertThatCode(() -> service.search(criteria(null, null, at, null))).doesNotThrowAnyException();
        assertThatCode(() -> service.search(criteria(null, null, null, at))).doesNotThrowAnyException();
    }

    private static SearchService.IncidentSearchCriteria criteria(
            Set<IncidentStatus> statuses, Set<SlaStatus> slaStatuses, Instant from, Instant to) {
        return new SearchService.IncidentSearchCriteria(
                statuses, null, null, slaStatuses, null, null, null, null, from, to);
    }
}

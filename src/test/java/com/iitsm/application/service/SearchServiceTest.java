package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                null, null, null, null, null, "EMP-0001", "Kubernetes", null, null);

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
                null, null, null, null, null, null, incident.getId(), null, null);
        SearchService.IncidentSearchCriteria byDisplayName = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, "P2-" + incident.getId(), null, null);
        SearchService.IncidentSearchCriteria byPartialName = new SearchService.IncidentSearchCriteria(
                null, null, null, null, null, null, "P2-" + incident.getId().substring(0, 3), null, null);

        assertThat(searchService.search(byId)).extracting(Incident::getId).contains(incident.getId());
        assertThat(searchService.search(byDisplayName)).extracting(Incident::getId).containsExactly(incident.getId());
        assertThat(searchService.search(byPartialName)).extracting(Incident::getId)
                .as("a half-typed display name is not guessed at").doesNotContain(incident.getId());
    }
}

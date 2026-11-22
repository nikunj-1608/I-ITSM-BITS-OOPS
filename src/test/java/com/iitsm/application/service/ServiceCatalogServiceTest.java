package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.BusinessCriticality;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.repository.ItServiceRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceCatalogServiceTest {

    private static ItService service(String id, String name) {
        return new ItService(id, name, "TEAM-X", BusinessCriticality.TIER_3_BUSINESS_OPERATIONAL, 10, Set.of());
    }

    @Test
    @DisplayName("Services are sorted by name ignoring case, then by id")
    void sortsByNameIgnoringCaseThenId() {
        ItServiceRepository repository = mock(ItServiceRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                service("SVC-3", "vpn Gateway"),
                service("SVC-2", "Email"),
                service("SVC-9", "email"),
                service("SVC-1", "ERP"),
                service("SVC-0", "Email")));

        List<ItService> sorted = new ServiceCatalogService(repository).listServices();

        assertThat(sorted).extracting(ItService::getId)
                .containsExactly("SVC-0", "SVC-2", "SVC-9", "SVC-1", "SVC-3");
    }

    @Test
    @DisplayName("An empty catalogue gives an empty list")
    void emptyCatalogue() {
        ItServiceRepository repository = mock(ItServiceRepository.class);
        when(repository.findAll()).thenReturn(List.of());

        assertThat(new ServiceCatalogService(repository).listServices()).isEmpty();
    }
}

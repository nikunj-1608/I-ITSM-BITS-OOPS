package com.iitsm.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.AvailabilityStatus;
import com.iitsm.domain.model.UserRole;
import com.iitsm.infrastructure.persistence.entity.UserJpaEntity;
import com.iitsm.infrastructure.persistence.repository.SpringDataUserRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the null-safe semantics of findAvailableTechnicians: SQL three-valued logic must not silently drop
 * technicians whose availability status or capacity column is NULL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AvailableTechniciansQueryTest {

    @Autowired
    private SpringDataUserRepository users;

    private void technician(String id, Integer maxCapacity, AvailabilityStatus status, boolean active) {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(id);
        entity.setName("Tech " + id);
        entity.setEmail(id.toLowerCase() + "@query-test.com");
        entity.setPasswordHash("hash");
        entity.setRole(UserRole.ROLE_TECHNICIAN);
        entity.setUserType("TECHNICIAN");
        entity.setMaxCapacity(maxCapacity);
        entity.setAvailabilityStatus(status);
        entity.setActive(active);
        users.saveAndFlush(entity);
    }

    private List<String> availableIds() {
        return users.findAvailableTechnicians().stream().map(UserJpaEntity::getId).toList();
    }

    @Test
    @DisplayName("Technicians with NULL availability status or NULL capacity are still returned")
    void nullColumnsAreNotDropped() {
        technician("Q-NULL-STATUS", 5, null, true);
        technician("Q-NULL-CAP", null, AvailabilityStatus.ONLINE, true);

        assertThat(availableIds()).contains("Q-NULL-STATUS", "Q-NULL-CAP");
    }

    @Test
    @DisplayName("OFFLINE, inactive and full technicians are excluded")
    void offlineInactiveAndFullAreExcluded() {
        technician("Q-OFFLINE", 5, AvailabilityStatus.OFFLINE, true);
        technician("Q-INACTIVE", 5, AvailabilityStatus.ONLINE, false);
        technician("Q-FULL", 0, AvailabilityStatus.ONLINE, true);
        technician("Q-OK", 5, AvailabilityStatus.ONLINE, true);

        assertThat(availableIds()).contains("Q-OK").doesNotContain("Q-OFFLINE", "Q-INACTIVE", "Q-FULL");
    }
}

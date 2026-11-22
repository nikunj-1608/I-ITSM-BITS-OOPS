package com.iitsm.application.service;

import com.iitsm.domain.model.ItService;
import com.iitsm.domain.repository.ItServiceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookup of the IT service catalogue for the administrator's search filter (ADR-049).
 * Services are ordered by name, ignoring case, then by id, so the dropdown is stable.
 */
@Service
@Transactional(readOnly = true)
public class ServiceCatalogService {

    private static final Comparator<ItService> BY_NAME_THEN_ID =
            Comparator.comparing(ItService::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ItService::getId);

    private final ItServiceRepository itServiceRepository;

    public ServiceCatalogService(ItServiceRepository itServiceRepository) {
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
    }

    public List<ItService> listServices() {
        return itServiceRepository.findAll().stream().sorted(BY_NAME_THEN_ID).toList();
    }
}

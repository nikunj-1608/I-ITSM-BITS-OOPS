package com.iitsm.web.dto;

import com.iitsm.domain.model.ItService;

// OOP (Immutability): ItServiceOptionDto is a record, so it is a read-only data carrier with no setters.
/**
 * One IT service as an option of the administrator's service filter (ADR-049): id, name and criticality only;
 * the owning team, user base and keywords are not exposed.
 */
public record ItServiceOptionDto(String id, String name, String businessCriticality) {

    public static ItServiceOptionDto fromDomain(ItService service) {
        return new ItServiceOptionDto(
                service.getId(), service.getName(), service.getBusinessCriticality().name());
    }
}

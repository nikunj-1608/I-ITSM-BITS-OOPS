package com.iitsm.infrastructure.persistence.repository;

/** One row of the service-experience query (ADR-044, Phase L4): a resolver and how many tickets of a service they resolved. */
public record ResolverCount(String resolvedBy, long resolved) {
}

-- =============================================================================
-- Flyway Migration V3: Per-department, per-year ticket sequence counters
-- Ticket numbers look like FIN-2026-000142; one row holds the last number issued
-- for a department code in a calendar year (the count restarts each year).
-- =============================================================================

CREATE TABLE ticket_sequences (
    department_code VARCHAR(8) NOT NULL,
    ticket_year INT NOT NULL,
    last_sequence BIGINT NOT NULL,
    PRIMARY KEY (department_code, ticket_year)
);

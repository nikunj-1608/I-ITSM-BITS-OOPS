-- ADR-044 (explainable technician assignment, Phase L2): why each ticket got its technician.
-- One row per assignment, written in the transaction of the assignment itself; rows are only added, never changed.
-- V7 and V8 stay reserved for the conditional migrations of decision D21 (remaining-defects plan).
-- candidates_json holds the strategy's ranking (best first, cut to iitsm.assignment.explanation-max-candidates) as one
-- JSON document written and read by the infrastructure mapper only; the domain never sees JSON. Manual, inherited and
-- requeued decisions have no strategy and an empty ranking ('{"format":1,"candidates":[]}').
-- chosen_technician_id and actor_id carry no foreign key: the actor can be SYSTEM or DISPATCHER, and a decision is a
-- historical record that must survive a later change to the user table (as audit_records does).
CREATE TABLE assignment_decisions (
    id VARCHAR(64) PRIMARY KEY,
    incident_id VARCHAR(64) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE ON UPDATE CASCADE,
    pool_kind VARCHAR(32) NOT NULL,
    strategy_name VARCHAR(64),
    chosen_technician_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    related_incident_id VARCHAR(64),
    candidate_count INTEGER NOT NULL,
    candidates_json TEXT NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_assignment_decisions_pool_kind CHECK (pool_kind IN
        ('OWNING_TEAM', 'SKILLED_OVERFLOW', 'MANUAL', 'INHERITED_FROM_PARENT', 'REQUEUED_TO_SAME_TECHNICIAN')),
    CONSTRAINT ck_assignment_decisions_candidate_count CHECK (candidate_count >= 0)
);

-- The admin ticket dialog reads the newest decisions of one ticket.
CREATE INDEX idx_assignment_decisions_incident ON assignment_decisions(incident_id, decided_at);

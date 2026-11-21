-- Time credited to a ticket's resolution deadline for waiting on the employee's verdict (ADR-024).
-- Kept so that a later priority override or reclassify, which recompute the deadline from the ticket's
-- creation, do not drop the credit a rejected fix earned (ADR-022). Existing rows have no recorded credit.
ALTER TABLE sla_trackers ADD COLUMN credited_duration_seconds BIGINT NOT NULL DEFAULT 0;

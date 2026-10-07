-- V45: Reassert the employee-match employment FK against the canonical
-- employment_assignment model.
--
-- `employee_match_decision.employment_period_id` is a legacy column name, but
-- every production writer stores `employment_assignment.assignment_id` in it.
-- There is no `employment` table in this schema. Keeping the FK on the
-- assignment table preserves the V8 ingestion contract and allows clean
-- Flyway installs to advance beyond this compatibility migration.

ALTER TABLE employee_match_decision
    DROP FOREIGN KEY fk_employee_match_employment;

ALTER TABLE employee_match_decision
    ADD CONSTRAINT fk_employee_match_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id);

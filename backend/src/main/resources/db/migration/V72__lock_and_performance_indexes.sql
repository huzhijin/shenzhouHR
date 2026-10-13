-- Performance indexes for szsc DAY_CONTEXT_SQL and HR recalculation queries.
-- All built with ALGORITHM=INPLACE, LOCK=NONE so they can run without downtime.

-- attendance_group_assignment: timeline anti-join used by DAY_CONTEXT_SQL
ALTER TABLE attendance_group_assignment
    ADD INDEX idx_aga_emp_eff_created
        (employee_id, effective_from, created_at),
    ALGORITHM=INPLACE, LOCK=NONE;

-- attendance_assignment_timeline: NOT EXISTS anti-join
ALTER TABLE attendance_assignment_timeline
    ADD INDEX idx_aat_assign_eff_seq
        (attendance_group_assignment_id, business_effective_from, event_sequence),
    ALGORITHM=INPLACE, LOCK=NONE;

-- calendar_publication_timeline: published-calendar lookup
ALTER TABLE calendar_publication_timeline
    ADD INDEX idx_cpt_cal_eff_seq
        (work_calendar_id, business_effective_from, event_sequence),
    ALGORITHM=INPLACE, LOCK=NONE;

-- shift_publication_timeline: published-shift lookup
ALTER TABLE shift_publication_timeline
    ADD INDEX idx_spt_shift_eff_seq
        (shift_template_id, business_effective_from, event_sequence),
    ALGORITHM=INPLACE, LOCK=NONE;

-- audit_event: two correlated subqueries in DAY_CONTEXT_SQL
ALTER TABLE audit_event
    ADD INDEX idx_ae_resource_time
        (resource_type, resource_id_ref, occurred_at),
    ALGORITHM=INPLACE, LOCK=NONE;

-- effective_attendance_event: szsc PUNCH_SQL and HR punch queries
ALTER TABLE effective_attendance_event
    ADD INDEX idx_eae_emp_instant
        (employee_id, point_instant),
    ALGORITHM=INPLACE, LOCK=NONE;

-- time_account_ledger_entry: OA recalc listUnreversedOaSyncEntries
ALTER TABLE time_account_ledger_entry
    ADD INDEX idx_tale_acct_src_type_date
        (time_account_id, source_type, entry_type, business_date),
    ALGORITHM=INPLACE, LOCK=NONE;

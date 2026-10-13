-- Company is the sole business boundary from V11 onward.
-- Stable identifiers and historical rows are preserved. Historical foreign-key
-- constraint names remain metadata only so the cutover does not rebuild tables.

ALTER TABLE auth_data_scope
    DROP CHECK ck_auth_scope_target;

ALTER TABLE auth_data_scope
    ALTER CHECK ck_auth_scope_period NOT ENFORCED;

ALTER TABLE people_import_batch
    ALTER CHECK ck_people_import_status NOT ENFORCED,
    ALTER CHECK ck_people_import_template_type NOT ENFORCED;

ALTER TABLE attendance_source
    ALTER CHECK ck_att_source_status NOT ENFORCED,
    ALTER CHECK ck_att_source_type NOT ENFORCED;

ALTER TABLE device_person_binding
    ALTER CHECK ck_device_person_period NOT ENFORCED;

ALTER TABLE raw_attendance_fact
    ALTER CHECK ck_raw_fact_identity NOT ENFORCED,
    ALTER CHECK ck_raw_fact_kind NOT ENFORCED,
    ALTER CHECK ck_raw_fact_temporal NOT ENFORCED;

ALTER TABLE effective_attendance_event
    ALTER CHECK ck_effective_event_direction NOT ENFORCED,
    ALTER CHECK ck_effective_event_kind NOT ENFORCED,
    ALTER CHECK ck_effective_event_temporal NOT ENFORCED;

ALTER TABLE duplicate_review_group
    ALTER CHECK ck_duplicate_group_direction NOT ENFORCED,
    ALTER CHECK ck_duplicate_group_status NOT ENFORCED,
    ALTER CHECK ck_duplicate_group_window NOT ENFORCED;

ALTER TABLE evidence_interval_slice
    ALTER CHECK ck_evidence_slice_period NOT ENFORCED,
    ALTER CHECK ck_evidence_slice_status NOT ENFORCED,
    ALTER CHECK ck_evidence_slice_winner NOT ENFORCED;

ALTER TABLE punch_import_file
    ALTER CHECK ck_punch_file_scan NOT ENFORCED,
    ALTER CHECK ck_punch_file_size NOT ENFORCED;

ALTER TABLE attendance_report_projection
    ALTER CHECK ck_att_report_projection_period NOT ENFORCED,
    ALTER CHECK ck_att_report_projection_publish NOT ENFORCED,
    ALTER CHECK ck_att_report_projection_state NOT ENFORCED,
    ALTER CHECK ck_att_report_projection_status NOT ENFORCED;

ALTER TABLE attendance_report_daily_fact
    ALTER CHECK ck_att_report_daily_actual_work NOT ENFORCED,
    ALTER CHECK ck_att_report_daily_day_type NOT ENFORCED,
    ALTER CHECK ck_att_report_daily_late NOT ENFORCED,
    ALTER CHECK ck_att_report_daily_punch_order NOT ENFORCED;

ALTER TABLE attendance_report_oa_fact
    ALTER CHECK ck_att_report_oa_status NOT ENFORCED,
    ALTER CHECK ck_att_report_oa_temporal_shape NOT ENFORCED,
    ALTER CHECK ck_att_report_oa_type NOT ENFORCED;

ALTER TABLE attendance_report_exception_fact
    ALTER CHECK ck_att_report_exception_severity NOT ENFORCED,
    ALTER CHECK ck_att_report_exception_state NOT ENFORCED;

ALTER TABLE attendance_report_time_account_fact
    ALTER CHECK ck_att_report_account_type NOT ENFORCED;

ALTER TABLE attendance_report_export_job
    ALTER CHECK ck_att_report_export_delivery NOT ENFORCED,
    ALTER CHECK ck_att_report_export_digests NOT ENFORCED,
    ALTER CHECK ck_att_report_export_extension NOT ENFORCED,
    ALTER CHECK ck_att_report_export_fields NOT ENFORCED,
    ALTER CHECK ck_att_report_export_period NOT ENFORCED,
    ALTER CHECK ck_att_report_export_purpose NOT ENFORCED,
    ALTER CHECK ck_att_report_export_state NOT ENFORCED,
    ALTER CHECK ck_att_report_export_status NOT ENFORCED,
    ALTER CHECK ck_att_report_export_time NOT ENFORCED,
    ALTER CHECK ck_att_report_export_type NOT ENFORCED;

SET @company_cutover_previous_foreign_key_checks =
    @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;

RENAME TABLE legal_entity TO company;

ALTER TABLE company
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE employee
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE organization_identity
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE auth_data_scope
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE people_import_batch
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE people_import_publication
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE location
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE shift_template
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE work_calendar
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_group
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_policy_scope
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_source
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE source_device
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE device_person_binding
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_evidence_subject_lock
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE raw_attendance_fact
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE effective_attendance_event
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE duplicate_review_group
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE evidence_interval_slice
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_recalculation_intent
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE punch_mapping_profile
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE punch_import_batch
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE punch_import_file
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_projection
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_daily_fact
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_oa_fact
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_exception_fact
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_time_account_fact
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

ALTER TABLE attendance_report_export_job
    RENAME COLUMN legal_entity_id TO company_id,
    ALGORITHM = INPLACE;

SET SESSION FOREIGN_KEY_CHECKS =
    @company_cutover_previous_foreign_key_checks;

ALTER TABLE auth_data_scope
    ALTER CHECK ck_auth_scope_period ENFORCED;

ALTER TABLE people_import_batch
    ALTER CHECK ck_people_import_status ENFORCED,
    ALTER CHECK ck_people_import_template_type ENFORCED;

ALTER TABLE attendance_source
    ALTER CHECK ck_att_source_status ENFORCED,
    ALTER CHECK ck_att_source_type ENFORCED;

ALTER TABLE device_person_binding
    ALTER CHECK ck_device_person_period ENFORCED;

ALTER TABLE raw_attendance_fact
    ALTER CHECK ck_raw_fact_identity ENFORCED,
    ALTER CHECK ck_raw_fact_kind ENFORCED,
    ALTER CHECK ck_raw_fact_temporal ENFORCED;

ALTER TABLE effective_attendance_event
    ALTER CHECK ck_effective_event_direction ENFORCED,
    ALTER CHECK ck_effective_event_kind ENFORCED,
    ALTER CHECK ck_effective_event_temporal ENFORCED;

ALTER TABLE duplicate_review_group
    ALTER CHECK ck_duplicate_group_direction ENFORCED,
    ALTER CHECK ck_duplicate_group_status ENFORCED,
    ALTER CHECK ck_duplicate_group_window ENFORCED;

ALTER TABLE evidence_interval_slice
    ALTER CHECK ck_evidence_slice_period ENFORCED,
    ALTER CHECK ck_evidence_slice_status ENFORCED,
    ALTER CHECK ck_evidence_slice_winner ENFORCED;

ALTER TABLE punch_import_file
    ALTER CHECK ck_punch_file_scan ENFORCED,
    ALTER CHECK ck_punch_file_size ENFORCED;

ALTER TABLE attendance_report_projection
    ALTER CHECK ck_att_report_projection_period ENFORCED,
    ALTER CHECK ck_att_report_projection_publish ENFORCED,
    ALTER CHECK ck_att_report_projection_state ENFORCED,
    ALTER CHECK ck_att_report_projection_status ENFORCED;

ALTER TABLE attendance_report_daily_fact
    ALTER CHECK ck_att_report_daily_actual_work ENFORCED,
    ALTER CHECK ck_att_report_daily_day_type ENFORCED,
    ALTER CHECK ck_att_report_daily_late ENFORCED,
    ALTER CHECK ck_att_report_daily_punch_order ENFORCED;

ALTER TABLE attendance_report_oa_fact
    ALTER CHECK ck_att_report_oa_status ENFORCED,
    ALTER CHECK ck_att_report_oa_temporal_shape ENFORCED,
    ALTER CHECK ck_att_report_oa_type ENFORCED;

ALTER TABLE attendance_report_exception_fact
    ALTER CHECK ck_att_report_exception_severity ENFORCED,
    ALTER CHECK ck_att_report_exception_state ENFORCED;

ALTER TABLE attendance_report_time_account_fact
    ALTER CHECK ck_att_report_account_type ENFORCED;

ALTER TABLE attendance_report_export_job
    ALTER CHECK ck_att_report_export_delivery ENFORCED,
    ALTER CHECK ck_att_report_export_digests ENFORCED,
    ALTER CHECK ck_att_report_export_extension ENFORCED,
    ALTER CHECK ck_att_report_export_fields ENFORCED,
    ALTER CHECK ck_att_report_export_period ENFORCED,
    ALTER CHECK ck_att_report_export_purpose ENFORCED,
    ALTER CHECK ck_att_report_export_state ENFORCED,
    ALTER CHECK ck_att_report_export_status ENFORCED,
    ALTER CHECK ck_att_report_export_time ENFORCED,
    ALTER CHECK ck_att_report_export_type ENFORCED;

ALTER TABLE company
    RENAME INDEX uq_legal_entity_code TO uq_company_code;

ALTER TABLE employee
    RENAME INDEX ix_employee_legal_entity_status
    TO ix_employee_company_status;

ALTER TABLE organization_identity
    RENAME INDEX ix_organization_identity_legal_entity
    TO ix_organization_identity_company;

ALTER TABLE attendance_policy_scope
    RENAME INDEX ix_attendance_policy_scope_legal_entity
    TO ix_attendance_policy_scope_company;

ALTER TABLE attendance_source
    RENAME INDEX uq_att_source_id_entity
    TO uq_att_source_id_company;

UPDATE auth_data_scope
SET scope_type = 'COMPANY'
WHERE scope_type = 'LEGAL_ENTITY';

ALTER TABLE auth_data_scope
    ADD CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'COMPANY' AND company_id IS NOT NULL AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND company_id IS NULL AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF' AND company_id IS NULL AND organization_id IS NULL)
    );

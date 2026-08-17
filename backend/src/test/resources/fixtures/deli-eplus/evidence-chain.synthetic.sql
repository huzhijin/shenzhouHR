-- SYNTHETIC_TEST_ONLY_NOT_VENDOR_DATA
-- Apply exactly once to an empty, fully migrated, isolated MySQL 8 test
-- database. Every identity and business value below is synthetic.
--
-- attendance_sync_job_page and raw_attendance_fact do not have a direct
-- foreign key in the production schema. This fixture preserves the real
-- ingestion correlation through attendance source, request id, counts and
-- timestamps, then uses the schema foreign keys for the immutable evidence
-- chain from raw fact through recalculation intent.

START TRANSACTION;

INSERT INTO company (
    company_id, code, name, status, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000001',
    'SYNTHETIC_TEST_ONLY_COMPANY',
    'SYNTHETIC TEST ONLY COMPANY',
    'ACTIVE',
    '2026-08-01 00:00:00.000000'
);

INSERT INTO employee (
    employee_id, company_id, display_name, employment_status,
    onboard_date, employee_number, row_version, created_at, updated_at
) VALUES (
    'd3110000-0000-0000-0000-000000000002',
    'd3110000-0000-0000-0000-000000000001',
    'SYNTHETIC TEST ONLY EMPLOYEE',
    'ACTIVE',
    '2026-08-01',
    'SYNTHETIC_TEST_ONLY_E001',
    0,
    '2026-08-01 00:00:00.000000',
    '2026-08-01 00:00:00.000000'
);

INSERT INTO organization_identity (
    organization_id, company_id, identity_status, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000003',
    'd3110000-0000-0000-0000-000000000001',
    'ACTIVE',
    '2026-08-01 00:00:00.000000'
);

INSERT INTO auth_principal (
    principal_id, employee_id, status, row_version, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000004',
    NULL,
    'ACTIVE',
    0,
    '2026-08-01 00:00:00.000000'
);

INSERT INTO employment_assignment (
    assignment_id, employee_id, organization_id, position_id,
    payroll_plan_id, cost_center_id, effective_from, effective_to,
    employment_period_id, termination_date, source_import_batch_id,
    row_version, change_reason, created_by, created_at,
    version_valid_to, record_status
) VALUES (
    'd3110000-0000-0000-0000-000000000005',
    'd3110000-0000-0000-0000-000000000002',
    'd3110000-0000-0000-0000-000000000003',
    NULL,
    NULL,
    NULL,
    '2026-08-01 00:00:00.000000',
    NULL,
    'd3110000-0000-0000-0000-000000000005',
    NULL,
    NULL,
    0,
    'SYNTHETIC_TEST_ONLY_EMPLOYMENT',
    'd3110000-0000-0000-0000-000000000004',
    '2026-08-01 00:00:00.000000',
    NULL,
    'ACTIVE'
);

INSERT INTO employment_period_identity (
    employment_period_id, employee_id, company_id, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000005',
    'd3110000-0000-0000-0000-000000000002',
    'd3110000-0000-0000-0000-000000000001',
    '2026-08-01 00:00:00.000000'
);

INSERT INTO attendance_evidence_subject_lock (
    company_id, employee_id, touched_at
) VALUES (
    'd3110000-0000-0000-0000-000000000001',
    'd3110000-0000-0000-0000-000000000002',
    '2026-08-16 02:00:00.500000'
);

INSERT INTO attendance_source (
    attendance_source_id, company_id, source_code, source_type,
    display_name, status, row_version, created_by, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000006',
    'd3110000-0000-0000-0000-000000000001',
    'SYNTHETIC_TEST_ONLY_DELI',
    'DELI_CLOUD',
    'SYNTHETIC TEST ONLY DELI SOURCE',
    'ACTIVE',
    0,
    'd3110000-0000-0000-0000-000000000004',
    '2026-08-01 00:00:00.000000'
);

INSERT INTO attendance_sync_job (
    attendance_sync_job_id, attendance_source_id, requested_watermark,
    status, started_at, finished_at, page_count, accepted_count,
    quarantined_count, safe_error_code, correlation_id, row_version,
    requested_by, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000007',
    'd3110000-0000-0000-0000-000000000006',
    '1',
    'SUCCEEDED',
    '2026-08-16 02:00:00.000000',
    '2026-08-16 02:00:01.000000',
    1,
    1,
    0,
    NULL,
    'SYNTHETIC_TEST_ONLY_REQUEST_001',
    1,
    'd3110000-0000-0000-0000-000000000004',
    '2026-08-16 02:00:00.000000'
);

INSERT INTO attendance_sync_job_page (
    attendance_sync_job_page_id, attendance_sync_job_id, page_number,
    input_cursor, next_cursor, record_count, accepted_count,
    quarantined_count, page_digest, request_id, committed_at
) VALUES (
    'd3110000-0000-0000-0000-000000000008',
    'd3110000-0000-0000-0000-000000000007',
    1,
    '1',
    '2',
    1,
    1,
    0,
    '1111111111111111111111111111111111111111111111111111111111111111',
    'SYNTHETIC_TEST_ONLY_REQUEST_001',
    '2026-08-16 02:00:01.000000'
);

INSERT INTO attendance_sync_watermark (
    attendance_source_id, committed_cursor, committed_page_digest,
    row_version, committed_at
) VALUES (
    'd3110000-0000-0000-0000-000000000006',
    '2',
    '1111111111111111111111111111111111111111111111111111111111111111',
    1,
    '2026-08-16 02:00:01.000000'
);

INSERT INTO raw_attendance_fact (
    raw_attendance_fact_id, attendance_source_id, company_id, fact_kind,
    source_business_key, source_version, stable_fingerprint,
    source_time_text, source_time_zone, source_instant,
    interval_start, interval_end, canonical_payload_digest,
    raw_object_ref, request_id, received_at, created_by
) VALUES (
    'd3110000-0000-0000-0000-000000000009',
    'd3110000-0000-0000-0000-000000000006',
    'd3110000-0000-0000-0000-000000000001',
    'PUNCH_POINT',
    'SYNTHETIC-RECORD-001',
    'SYNTHETIC-VERSION-001',
    NULL,
    '1786843800',
    'Asia/Shanghai',
    '2026-08-16 01:30:00.000000',
    NULL,
    NULL,
    '2222222222222222222222222222222222222222222222222222222222222222',
    NULL,
    'SYNTHETIC_TEST_ONLY_REQUEST_001',
    '2026-08-16 02:00:00.500000',
    'd3110000-0000-0000-0000-000000000004'
);

INSERT INTO normalized_attendance_record (
    normalized_attendance_record_id, raw_attendance_fact_id,
    normalization_revision, schema_version, record_kind,
    normalized_direction, point_instant, interval_start, interval_end,
    validation_status, issue_code, canonical_digest,
    supersedes_normalized_record_id, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000010',
    'd3110000-0000-0000-0000-000000000009',
    1,
    'DELI_CHECKIN_V1',
    'PUNCH_POINT',
    'AUTO',
    '2026-08-16 01:30:00.000000',
    NULL,
    NULL,
    'VALID',
    NULL,
    '3333333333333333333333333333333333333333333333333333333333333333',
    NULL,
    '2026-08-16 02:00:00.500000'
);

INSERT INTO employee_match_decision (
    employee_match_decision_id, normalized_attendance_record_id,
    match_status, match_reason, employee_id, employment_period_id,
    device_person_binding_id, resolver_snapshot_digest, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000011',
    'd3110000-0000-0000-0000-000000000010',
    'MATCHED',
    'CONFIRMED_BINDING',
    'd3110000-0000-0000-0000-000000000002',
    'd3110000-0000-0000-0000-000000000005',
    NULL,
    '4444444444444444444444444444444444444444444444444444444444444444',
    '2026-08-16 02:00:00.500000'
);

INSERT INTO effective_attendance_event (
    effective_attendance_event_id, company_id, employee_id, event_kind,
    normalized_direction, point_instant, interval_start, interval_end,
    canonical_digest, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000012',
    'd3110000-0000-0000-0000-000000000001',
    'd3110000-0000-0000-0000-000000000002',
    'PUNCH_POINT',
    'AUTO',
    '2026-08-16 01:30:00.000000',
    NULL,
    NULL,
    '5555555555555555555555555555555555555555555555555555555555555555',
    '2026-08-16 02:00:00.500000'
);

INSERT INTO effective_event_lifecycle_fact (
    effective_event_lifecycle_fact_id, effective_attendance_event_id,
    lifecycle_type, related_event_id, source_reversal_record_id,
    knowledge_at, actor_id, request_id, change_reason, fact_digest
) VALUES (
    'd3110000-0000-0000-0000-000000000013',
    'd3110000-0000-0000-0000-000000000012',
    'ACTIVATED',
    NULL,
    NULL,
    '2026-08-16 02:00:00.500000',
    'd3110000-0000-0000-0000-000000000004',
    'SYNTHETIC_TEST_ONLY_REQUEST_001',
    'SYNTHETIC_TEST_ONLY_DELI_SYNC',
    '6666666666666666666666666666666666666666666666666666666666666666'
);

INSERT INTO evidence_link (
    evidence_link_id, effective_attendance_event_id,
    raw_attendance_fact_id, normalized_attendance_record_id,
    employee_match_decision_id, link_type, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000014',
    'd3110000-0000-0000-0000-000000000012',
    'd3110000-0000-0000-0000-000000000009',
    'd3110000-0000-0000-0000-000000000010',
    'd3110000-0000-0000-0000-000000000011',
    'PRIMARY',
    '2026-08-16 02:00:00.500000'
);

INSERT INTO attendance_recalculation_intent (
    attendance_recalculation_intent_id, company_id, employee_id,
    business_date, reason_code, effective_attendance_event_id,
    resolver_snapshot_digest, period_version, request_id,
    intent_digest, created_at
) VALUES (
    'd3110000-0000-0000-0000-000000000015',
    'd3110000-0000-0000-0000-000000000001',
    'd3110000-0000-0000-0000-000000000002',
    '2026-08-16',
    'DELI_PUNCH_INGESTED',
    'd3110000-0000-0000-0000-000000000012',
    '7777777777777777777777777777777777777777777777777777777777777777',
    'SYNTHETIC_TEST_ONLY_OPEN_PERIOD_V1',
    'SYNTHETIC_TEST_ONLY_REQUEST_001',
    '8888888888888888888888888888888888888888888888888888888888888888',
    '2026-08-16 02:00:00.500000'
);

COMMIT;

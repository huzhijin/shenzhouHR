CREATE TABLE punch_mapping_profile (
    punch_mapping_profile_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vendor_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    profile_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_mapping_profile_id),
    UNIQUE KEY uq_punch_mapping_profile
        (legal_entity_id, vendor_code, model_code, location_id, profile_code),
    KEY ix_punch_mapping_scope
        (legal_entity_id, location_id, vendor_code, model_code),
    CONSTRAINT fk_punch_mapping_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_punch_mapping_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_punch_mapping_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_mapping_profile_version (
    punch_mapping_profile_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_mapping_profile_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    field_mapping_json JSON NOT NULL,
    date_formats_json JSON NOT NULL,
    source_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trim_policy VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enum_mapping_json JSON NOT NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supersedes_profile_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_mapping_profile_version_id),
    UNIQUE KEY uq_punch_mapping_version
        (punch_mapping_profile_id, version_number),
    UNIQUE KEY uq_punch_mapping_successor (supersedes_profile_version_id),
    KEY ix_punch_mapping_versions
        (punch_mapping_profile_id, version_number, created_at),
    CONSTRAINT fk_punch_mapping_version_profile
        FOREIGN KEY (punch_mapping_profile_id)
        REFERENCES punch_mapping_profile (punch_mapping_profile_id),
    CONSTRAINT fk_punch_mapping_version_previous
        FOREIGN KEY (supersedes_profile_version_id)
        REFERENCES punch_mapping_profile_version (punch_mapping_profile_version_id),
    CONSTRAINT fk_punch_mapping_version_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_mapping_version_number CHECK (version_number > 0),
    CONSTRAINT ck_punch_mapping_trim CHECK (
        trim_policy IN ('NONE', 'TRIM', 'TRIM_AND_COLLAPSE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_batch (
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mapping_profile_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_batch_id),
    KEY ix_punch_batch_scope
        (legal_entity_id, location_id, created_at, punch_import_batch_id),
    KEY ix_punch_batch_source
        (attendance_source_id, created_at, punch_import_batch_id),
    CONSTRAINT fk_punch_batch_source_scope
        FOREIGN KEY (attendance_source_id, legal_entity_id)
        REFERENCES attendance_source (attendance_source_id, legal_entity_id),
    CONSTRAINT fk_punch_batch_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_punch_batch_mapping_version
        FOREIGN KEY (mapping_profile_version_id)
        REFERENCES punch_mapping_profile_version (punch_mapping_profile_version_id),
    CONSTRAINT fk_punch_batch_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_file (
    punch_import_file_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_object_ref VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_size_bytes BIGINT UNSIGNED NOT NULL,
    content_type VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    malware_scan_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    field_contract_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    uploaded_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_file_id),
    UNIQUE KEY uq_punch_file_scope_hash
        (legal_entity_id, attendance_source_id, content_sha256, field_contract_digest),
    UNIQUE KEY uq_punch_file_batch (punch_import_batch_id),
    KEY ix_punch_file_hash (content_sha256, file_size_bytes),
    CONSTRAINT fk_punch_file_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_file_source_scope
        FOREIGN KEY (attendance_source_id, legal_entity_id)
        REFERENCES attendance_source (attendance_source_id, legal_entity_id),
    CONSTRAINT fk_punch_file_actor
        FOREIGN KEY (uploaded_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_file_size CHECK (file_size_bytes BETWEEN 1 AND 20971520),
    CONSTRAINT ck_punch_file_scan CHECK (
        malware_scan_status IN ('PENDING', 'CLEAN', 'REJECTED', 'UNAVAILABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_row (
    punch_import_row_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_file_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_number INT UNSIGNED NOT NULL,
    raw_values_json JSON NOT NULL,
    stable_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    employee_match_decision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    blocking_issue_count INT UNSIGNED NOT NULL DEFAULT 0,
    warning_issue_count INT UNSIGNED NOT NULL DEFAULT 0,
    published_raw_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_row_id),
    UNIQUE KEY uq_punch_import_row_number
        (punch_import_batch_id, punch_import_file_id, row_number),
    KEY ix_punch_import_row_batch
        (punch_import_batch_id, row_number, punch_import_row_id),
    KEY ix_punch_import_row_fingerprint
        (stable_fingerprint, punch_import_row_id),
    CONSTRAINT fk_punch_import_row_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_import_row_file
        FOREIGN KEY (punch_import_file_id)
        REFERENCES punch_import_file (punch_import_file_id),
    CONSTRAINT fk_punch_import_row_match
        FOREIGN KEY (employee_match_decision_id)
        REFERENCES employee_match_decision (employee_match_decision_id),
    CONSTRAINT fk_punch_import_row_raw
        FOREIGN KEY (published_raw_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_punch_import_row_event
        FOREIGN KEY (effective_attendance_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT ck_punch_import_row_number CHECK (row_number BETWEEN 1 AND 50000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_normalization_attempt (
    punch_import_normalization_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    mapping_profile_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolver_snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    duplicate_window_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (punch_import_normalization_attempt_id),
    UNIQUE KEY uq_punch_attempt_number
        (punch_import_batch_id, attempt_number),
    UNIQUE KEY uq_punch_attempt_digest
        (punch_import_batch_id, attempt_digest),
    KEY ix_punch_attempt_status
        (punch_import_batch_id, status, attempt_number),
    CONSTRAINT fk_punch_attempt_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_attempt_mapping
        FOREIGN KEY (mapping_profile_version_id)
        REFERENCES punch_mapping_profile_version (punch_mapping_profile_version_id),
    CONSTRAINT ck_punch_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_punch_attempt_status CHECK (
        status IN ('RUNNING', 'VALID', 'INVALID', 'FROZEN_BLOCKED', 'FAILED')
    ),
    CONSTRAINT ck_punch_attempt_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_issue (
    punch_import_issue_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_normalization_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_row_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    issue_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    severity VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    field_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    safe_message VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_issue_id),
    KEY ix_punch_issue_attempt
        (punch_import_normalization_attempt_id, severity, punch_import_issue_id),
    KEY ix_punch_issue_row
        (punch_import_row_id, severity, punch_import_issue_id),
    CONSTRAINT fk_punch_issue_attempt
        FOREIGN KEY (punch_import_normalization_attempt_id)
        REFERENCES punch_import_normalization_attempt
            (punch_import_normalization_attempt_id),
    CONSTRAINT fk_punch_issue_row
        FOREIGN KEY (punch_import_row_id)
        REFERENCES punch_import_row (punch_import_row_id),
    CONSTRAINT ck_punch_issue_severity CHECK (
        severity IN ('BLOCKING', 'WARNING')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_precheck (
    punch_import_precheck_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    precheck_token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    total_count INT UNSIGNED NOT NULL,
    valid_count INT UNSIGNED NOT NULL,
    blocking_count INT UNSIGNED NOT NULL,
    warning_count INT UNSIGNED NOT NULL,
    affected_subjects_json JSON NOT NULL,
    row_result_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_precheck_id),
    UNIQUE KEY uq_punch_precheck_attempt (normalization_attempt_id),
    UNIQUE KEY uq_punch_precheck_token (precheck_token_digest),
    KEY ix_punch_precheck_batch
        (punch_import_batch_id, created_at, punch_import_precheck_id),
    CONSTRAINT fk_punch_precheck_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_precheck_attempt
        FOREIGN KEY (normalization_attempt_id)
        REFERENCES punch_import_normalization_attempt
            (punch_import_normalization_attempt_id),
    CONSTRAINT ck_punch_precheck_counts CHECK (
        total_count = valid_count + blocking_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_publication (
    punch_import_publication_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_precheck_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publish_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_count INT UNSIGNED NOT NULL,
    retained_invalid_count INT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    published_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_publication_id),
    UNIQUE KEY uq_punch_publication_precheck (punch_import_precheck_id),
    UNIQUE KEY uq_punch_publication_idem (published_by, idempotency_key),
    KEY ix_punch_publication_batch
        (punch_import_batch_id, published_at, punch_import_publication_id),
    CONSTRAINT fk_punch_publication_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_publication_precheck
        FOREIGN KEY (punch_import_precheck_id)
        REFERENCES punch_import_precheck (punch_import_precheck_id),
    CONSTRAINT fk_punch_publication_actor
        FOREIGN KEY (published_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_publication_mode CHECK (
        publish_mode IN ('STRICT', 'VALID_ROWS_ONLY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_state_event (
    punch_import_state_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_number INT UNSIGNED NOT NULL,
    from_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_state_event_id),
    UNIQUE KEY uq_punch_state_sequence
        (punch_import_batch_id, sequence_number),
    KEY ix_punch_state_current
        (punch_import_batch_id, sequence_number, to_state),
    CONSTRAINT fk_punch_state_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_state_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_state_from CHECK (
        from_state IS NULL OR from_state IN (
            'DRAFT', 'VALIDATING', 'VALIDATION_FAILED',
            'AWAITING_CONFIRMATION', 'BLOCKED_BY_FROZEN_PERIOD',
            'PUBLISHING', 'PUBLISHED', 'PARTIALLY_PUBLISHED',
            'PUBLISH_FAILED', 'VOIDED'
        )
    ),
    CONSTRAINT ck_punch_state_to CHECK (
        to_state IN (
            'DRAFT', 'VALIDATING', 'VALIDATION_FAILED',
            'AWAITING_CONFIRMATION', 'BLOCKED_BY_FROZEN_PERIOD',
            'PUBLISHING', 'PUBLISHED', 'PARTIALLY_PUBLISHED',
            'PUBLISH_FAILED', 'VOIDED'
        )
    ),
    CONSTRAINT ck_punch_state_initial CHECK (
        (sequence_number = 1 AND from_state IS NULL AND to_state = 'DRAFT')
        OR (sequence_number > 1 AND from_state IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE punch_import_error_report (
    punch_import_error_report_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    punch_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_object_ref VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_size_bytes BIGINT UNSIGNED NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_import_error_report_id),
    UNIQUE KEY uq_punch_error_report_attempt (normalization_attempt_id),
    KEY ix_punch_error_report_batch
        (punch_import_batch_id, created_at, punch_import_error_report_id),
    CONSTRAINT fk_punch_error_report_batch
        FOREIGN KEY (punch_import_batch_id)
        REFERENCES punch_import_batch (punch_import_batch_id),
    CONSTRAINT fk_punch_error_report_attempt
        FOREIGN KEY (normalization_attempt_id)
        REFERENCES punch_import_normalization_attempt
            (punch_import_normalization_attempt_id),
    CONSTRAINT fk_punch_error_report_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_error_report_size CHECK (file_size_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('29000000-0000-0000-0000-000000000001', 'ATTENDANCE_PUNCH_IMPORT:READ', 'ATTENDANCE_PUNCH_IMPORT', 'READ'),
    ('29000000-0000-0000-0000-000000000002', 'ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD', 'ATTENDANCE_PUNCH_IMPORT', 'TEMPLATE_DOWNLOAD'),
    ('29000000-0000-0000-0000-000000000003', 'ATTENDANCE_PUNCH_IMPORT:UPLOAD', 'ATTENDANCE_PUNCH_IMPORT', 'UPLOAD'),
    ('29000000-0000-0000-0000-000000000004', 'ATTENDANCE_PUNCH_IMPORT:PRECHECK', 'ATTENDANCE_PUNCH_IMPORT', 'PRECHECK'),
    ('29000000-0000-0000-0000-000000000005', 'ATTENDANCE_PUNCH_IMPORT:PUBLISH', 'ATTENDANCE_PUNCH_IMPORT', 'PUBLISH'),
    ('29000000-0000-0000-0000-000000000006', 'ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH', 'ATTENDANCE_PUNCH_IMPORT', 'PARTIAL_PUBLISH'),
    ('29000000-0000-0000-0000-000000000007', 'ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE', 'ATTENDANCE_PUNCH_IMPORT', 'VOID_OR_REVERSE'),
    ('29000000-0000-0000-0000-000000000008', 'ATTENDANCE_PUNCH_IMPORT:RAW_FILE_READ', 'ATTENDANCE_PUNCH_IMPORT', 'RAW_FILE_READ'),
    ('29000000-0000-0000-0000-000000000009', 'ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ', 'ATTENDANCE_PUNCH_IMPORT', 'RAW_ROW_READ'),
    ('29000000-0000-0000-0000-000000000010', 'ATTENDANCE_PUNCH_IMPORT:ERROR_REPORT_DOWNLOAD', 'ATTENDANCE_PUNCH_IMPORT', 'ERROR_REPORT_DOWNLOAD'),
    ('29000000-0000-0000-0000-000000000011', 'ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW', 'ATTENDANCE_PUNCH_IMPORT', 'DUPLICATE_REVIEW'),
    ('29000000-0000-0000-0000-000000000012', 'ATTENDANCE_PUNCH_IMPORT:RECALCULATE', 'ATTENDANCE_PUNCH_IMPORT', 'RECALCULATE');

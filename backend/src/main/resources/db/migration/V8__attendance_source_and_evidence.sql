CREATE TABLE attendance_source (
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_source_id),
    UNIQUE KEY uq_att_source_code (legal_entity_id, source_code),
    UNIQUE KEY uq_att_source_id_entity (attendance_source_id, legal_entity_id),
    KEY ix_att_source_scope (legal_entity_id, source_type, status, attendance_source_id),
    CONSTRAINT fk_att_source_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_att_source_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_att_source_type CHECK (
        source_type IN ('DELI_CLOUD', 'OA_ATTENDANCE', 'DEVICE_EXCEL', 'STANDARD_XLSX')
    ),
    CONSTRAINT ck_att_source_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_source_config_revision (
    source_config_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    endpoint_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page_size INT UNSIGNED NOT NULL,
    rate_limit_per_minute INT UNSIGNED NOT NULL,
    backoff_seconds INT UNSIGNED NOT NULL,
    secret_reference_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    adapter_settings_json JSON NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    supersedes_config_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_config_revision_id),
    UNIQUE KEY uq_att_source_cfg_rev (attendance_source_id, revision_number),
    UNIQUE KEY uq_att_source_cfg_successor (supersedes_config_revision_id),
    KEY ix_att_source_cfg_effective
        (attendance_source_id, effective_from, revision_number),
    CONSTRAINT fk_att_source_cfg_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_att_source_cfg_previous
        FOREIGN KEY (supersedes_config_revision_id)
        REFERENCES attendance_source_config_revision (source_config_revision_id),
    CONSTRAINT fk_att_source_cfg_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_att_source_cfg_rev CHECK (revision_number > 0),
    CONSTRAINT ck_att_source_cfg_page CHECK (page_size BETWEEN 1 AND 1000),
    CONSTRAINT ck_att_source_cfg_rate CHECK (rate_limit_per_minute > 0),
    CONSTRAINT ck_att_source_cfg_secret_ref CHECK (
        secret_reference_name IS NULL
        OR secret_reference_name REGEXP '^[A-Z][A-Z0-9_]{2,127}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_sync_job (
    attendance_sync_job_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_watermark VARCHAR(512) COLLATE utf8mb4_bin NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    page_count INT UNSIGNED NOT NULL DEFAULT 0,
    accepted_count INT UNSIGNED NOT NULL DEFAULT 0,
    quarantined_count INT UNSIGNED NOT NULL DEFAULT 0,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    requested_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_sync_job_id),
    KEY ix_att_sync_job_source
        (attendance_source_id, created_at, attendance_sync_job_id),
    KEY ix_att_sync_job_status
        (status, created_at, attendance_sync_job_id),
    CONSTRAINT fk_att_sync_job_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_att_sync_job_actor
        FOREIGN KEY (requested_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_att_sync_job_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED',
            'PARTIALLY_QUARANTINED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_att_sync_job_times CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_sync_job_page (
    attendance_sync_job_page_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_sync_job_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    page_number INT UNSIGNED NOT NULL,
    input_cursor VARCHAR(512) COLLATE utf8mb4_bin NULL,
    next_cursor VARCHAR(512) COLLATE utf8mb4_bin NULL,
    record_count INT UNSIGNED NOT NULL,
    accepted_count INT UNSIGNED NOT NULL,
    quarantined_count INT UNSIGNED NOT NULL,
    page_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_sync_job_page_id),
    UNIQUE KEY uq_att_sync_page_number (attendance_sync_job_id, page_number),
    UNIQUE KEY uq_att_sync_page_digest (attendance_sync_job_id, page_digest),
    KEY ix_att_sync_page_cursor (attendance_sync_job_id, next_cursor),
    CONSTRAINT fk_att_sync_page_job
        FOREIGN KEY (attendance_sync_job_id)
        REFERENCES attendance_sync_job (attendance_sync_job_id),
    CONSTRAINT ck_att_sync_page_number CHECK (page_number > 0),
    CONSTRAINT ck_att_sync_page_counts CHECK (
        record_count = accepted_count + quarantined_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_sync_watermark (
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    committed_cursor VARCHAR(512) COLLATE utf8mb4_bin NULL,
    committed_page_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    committed_at DATETIME(6) NULL,
    PRIMARY KEY (attendance_source_id),
    CONSTRAINT fk_att_watermark_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_device (
    source_device_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_device_ref VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_device_id),
    UNIQUE KEY uq_source_device_ref (attendance_source_id, external_device_ref),
    UNIQUE KEY uq_source_device_scope
        (source_device_id, legal_entity_id, location_id),
    KEY ix_source_device_location
        (legal_entity_id, location_id, source_device_id),
    CONSTRAINT fk_source_device_source_scope
        FOREIGN KEY (attendance_source_id, legal_entity_id)
        REFERENCES attendance_source (attendance_source_id, legal_entity_id),
    CONSTRAINT fk_source_device_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_source_device_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_device_revision (
    source_device_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_device_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    device_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    coordinate_system_tag VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    supersedes_device_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_device_revision_id),
    UNIQUE KEY uq_source_device_revision (source_device_id, revision_number),
    UNIQUE KEY uq_source_device_rev_successor (supersedes_device_revision_id),
    KEY ix_source_device_rev_effective
        (source_device_id, effective_from, effective_to),
    CONSTRAINT fk_source_device_rev_device
        FOREIGN KEY (source_device_id) REFERENCES source_device (source_device_id),
    CONSTRAINT fk_source_device_rev_previous
        FOREIGN KEY (supersedes_device_revision_id)
        REFERENCES source_device_revision (source_device_revision_id),
    CONSTRAINT fk_source_device_rev_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_source_device_rev_number CHECK (revision_number > 0),
    CONSTRAINT ck_source_device_rev_period CHECK (
        effective_to IS NULL OR effective_to > effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE device_person_binding (
    device_person_binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_device_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_person_ref VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (device_person_binding_id),
    UNIQUE KEY uq_device_person_start
        (source_device_id, external_person_ref, effective_from),
    KEY ix_device_person_resolution
        (source_device_id, external_person_ref, effective_from, effective_to),
    KEY ix_device_person_employee
        (employee_id, effective_from, effective_to, device_person_binding_id),
    CONSTRAINT fk_device_person_source_scope
        FOREIGN KEY (attendance_source_id, legal_entity_id)
        REFERENCES attendance_source (attendance_source_id, legal_entity_id),
    CONSTRAINT fk_device_person_device_scope
        FOREIGN KEY (source_device_id, legal_entity_id, location_id)
        REFERENCES source_device (source_device_id, legal_entity_id, location_id),
    CONSTRAINT fk_device_person_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_device_person_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_device_person_period CHECK (
        effective_to IS NULL OR effective_to > effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_evidence_subject_lock (
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    touched_at DATETIME(6) NOT NULL,
    PRIMARY KEY (legal_entity_id, employee_id),
    CONSTRAINT fk_att_subject_lock_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_att_subject_lock_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE raw_attendance_fact (
    raw_attendance_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fact_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_business_key VARCHAR(191) COLLATE utf8mb4_bin NULL,
    source_version VARCHAR(128) COLLATE utf8mb4_bin NULL,
    stable_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_time_text VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    source_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_instant DATETIME(6) NULL,
    interval_start DATETIME(6) NULL,
    interval_end DATETIME(6) NULL,
    canonical_payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_object_ref VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at DATETIME(6) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (raw_attendance_fact_id),
    UNIQUE KEY uq_raw_fact_source_version
        (attendance_source_id, source_business_key, source_version),
    UNIQUE KEY uq_raw_fact_fingerprint
        (attendance_source_id, stable_fingerprint),
    KEY ix_raw_fact_source_received
        (attendance_source_id, received_at, raw_attendance_fact_id),
    KEY ix_raw_fact_scope_instant
        (legal_entity_id, source_instant, raw_attendance_fact_id),
    CONSTRAINT fk_raw_fact_source_scope
        FOREIGN KEY (attendance_source_id, legal_entity_id)
        REFERENCES attendance_source (attendance_source_id, legal_entity_id),
    CONSTRAINT fk_raw_fact_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_raw_fact_kind CHECK (
        fact_kind IN ('PUNCH_POINT', 'OA_DOCUMENT', 'REVERSAL')
    ),
    CONSTRAINT ck_raw_fact_identity CHECK (
        (
            source_business_key IS NOT NULL
            AND source_version IS NOT NULL
        )
        OR stable_fingerprint IS NOT NULL
    ),
    CONSTRAINT ck_raw_fact_temporal CHECK (
        (
            source_instant IS NOT NULL
            AND interval_start IS NULL
            AND interval_end IS NULL
        )
        OR (
            source_instant IS NULL
            AND interval_start IS NOT NULL
            AND interval_end IS NOT NULL
            AND interval_end > interval_start
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE normalized_attendance_record (
    normalized_attendance_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_attendance_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalization_revision INT UNSIGNED NOT NULL,
    schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_direction VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    point_instant DATETIME(6) NULL,
    interval_start DATETIME(6) NULL,
    interval_end DATETIME(6) NULL,
    validation_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issue_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    canonical_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supersedes_normalized_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (normalized_attendance_record_id),
    UNIQUE KEY uq_normalized_revision
        (raw_attendance_fact_id, normalization_revision),
    UNIQUE KEY uq_normalized_successor (supersedes_normalized_record_id),
    KEY ix_normalized_point
        (validation_status, point_instant, normalized_attendance_record_id),
    KEY ix_normalized_interval
        (validation_status, interval_start, interval_end),
    CONSTRAINT fk_normalized_raw
        FOREIGN KEY (raw_attendance_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_normalized_previous
        FOREIGN KEY (supersedes_normalized_record_id)
        REFERENCES normalized_attendance_record (normalized_attendance_record_id),
    CONSTRAINT ck_normalized_revision CHECK (normalization_revision > 0),
    CONSTRAINT ck_normalized_kind CHECK (
        record_kind IN ('PUNCH_POINT', 'OA_INTERVAL', 'REVERSAL')
    ),
    CONSTRAINT ck_normalized_direction CHECK (
        normalized_direction IS NULL
        OR normalized_direction IN ('AUTO', 'IN', 'OUT')
    ),
    CONSTRAINT ck_normalized_status CHECK (
        validation_status IN ('VALID', 'QUARANTINED', 'SUPERSEDED')
    ),
    CONSTRAINT ck_normalized_temporal CHECK (
        (
            point_instant IS NOT NULL
            AND interval_start IS NULL
            AND interval_end IS NULL
        )
        OR (
            point_instant IS NULL
            AND interval_start IS NOT NULL
            AND interval_end IS NOT NULL
            AND interval_end > interval_start
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_match_decision (
    employee_match_decision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_attendance_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    match_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    match_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_person_binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolver_snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (employee_match_decision_id),
    UNIQUE KEY uq_employee_match_record (normalized_attendance_record_id),
    KEY ix_employee_match_subject
        (employee_id, match_status, employee_match_decision_id),
    CONSTRAINT fk_employee_match_normalized
        FOREIGN KEY (normalized_attendance_record_id)
        REFERENCES normalized_attendance_record (normalized_attendance_record_id),
    CONSTRAINT fk_employee_match_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_employee_match_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id),
    CONSTRAINT fk_employee_match_binding
        FOREIGN KEY (device_person_binding_id)
        REFERENCES device_person_binding (device_person_binding_id),
    CONSTRAINT ck_employee_match_status CHECK (
        match_status IN ('MATCHED', 'UNMATCHED', 'AMBIGUOUS', 'OUT_OF_SCOPE')
    ),
    CONSTRAINT ck_employee_match_cardinality CHECK (
        (
            match_status = 'MATCHED'
            AND employee_id IS NOT NULL
            AND employment_period_id IS NOT NULL
        )
        OR (
            match_status <> 'MATCHED'
            AND employee_id IS NULL
            AND employment_period_id IS NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE effective_attendance_event (
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_direction VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    point_instant DATETIME(6) NULL,
    interval_start DATETIME(6) NULL,
    interval_end DATETIME(6) NULL,
    canonical_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (effective_attendance_event_id),
    UNIQUE KEY uq_effective_event_digest
        (legal_entity_id, employee_id, canonical_digest),
    KEY ix_effective_event_point
        (legal_entity_id, employee_id, point_instant, effective_attendance_event_id),
    KEY ix_effective_event_interval
        (legal_entity_id, employee_id, interval_start, interval_end),
    CONSTRAINT fk_effective_event_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_effective_event_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_effective_event_kind CHECK (
        event_kind IN ('PUNCH_POINT', 'OA_INTERVAL')
    ),
    CONSTRAINT ck_effective_event_direction CHECK (
        normalized_direction IS NULL
        OR normalized_direction IN ('AUTO', 'IN', 'OUT')
    ),
    CONSTRAINT ck_effective_event_temporal CHECK (
        (
            point_instant IS NOT NULL
            AND interval_start IS NULL
            AND interval_end IS NULL
        )
        OR (
            point_instant IS NULL
            AND interval_start IS NOT NULL
            AND interval_end IS NOT NULL
            AND interval_end > interval_start
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_attendance_document (
    oa_attendance_document_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_business_key VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    source_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    document_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_attendance_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_rank BIGINT UNSIGNED NOT NULL,
    supersedes_document_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    first_submitted_at DATETIME(6) NULL,
    approved_at DATETIME(6) NULL,
    modified_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (oa_attendance_document_id),
    UNIQUE KEY uq_oa_document_version
        (attendance_source_id, source_business_key, source_version),
    UNIQUE KEY uq_oa_document_normalized (normalized_attendance_record_id),
    UNIQUE KEY uq_oa_document_successor (supersedes_document_id),
    KEY ix_oa_document_current
        (attendance_source_id, source_business_key, knowledge_rank),
    CONSTRAINT fk_oa_document_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_oa_document_normalized
        FOREIGN KEY (normalized_attendance_record_id)
        REFERENCES normalized_attendance_record (normalized_attendance_record_id),
    CONSTRAINT fk_oa_document_previous
        FOREIGN KEY (supersedes_document_id)
        REFERENCES oa_attendance_document (oa_attendance_document_id),
    CONSTRAINT ck_oa_document_type CHECK (
        document_type IN (
            'LEAVE', 'LEAVE_REVOCATION', 'OVERTIME', 'TRIP', 'OUTING',
            'PUNCH_CORRECTION', 'TIME_OFF', 'EXEMPT_PUNCH'
        )
    ),
    CONSTRAINT ck_oa_document_status CHECK (
        source_status IN (
            'APPROVED', 'DRAFT', 'REJECTED', 'UNKNOWN',
            'MODIFIED', 'SUPPLEMENTED', 'REVOKED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE source_reversal_record (
    source_reversal_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reversal_raw_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_raw_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_effective_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reversal_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (source_reversal_record_id),
    UNIQUE KEY uq_source_reversal_raw (reversal_raw_fact_id),
    KEY ix_source_reversal_target
        (target_raw_fact_id, created_at, source_reversal_record_id),
    CONSTRAINT fk_source_reversal_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_source_reversal_raw
        FOREIGN KEY (reversal_raw_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_source_reversal_target_raw
        FOREIGN KEY (target_raw_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_source_reversal_target_event
        FOREIGN KEY (target_effective_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT ck_source_reversal_kind CHECK (
        reversal_kind IN ('MODIFIED', 'REVOKED', 'VOIDED', 'SUPERSEDED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE effective_event_lifecycle_fact (
    effective_event_lifecycle_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lifecycle_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    related_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_reversal_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_at DATETIME(6) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    fact_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (effective_event_lifecycle_fact_id),
    UNIQUE KEY uq_event_lifecycle_digest
        (effective_attendance_event_id, fact_digest),
    KEY ix_event_lifecycle_knowledge
        (effective_attendance_event_id, knowledge_at, effective_event_lifecycle_fact_id),
    CONSTRAINT fk_event_lifecycle_event
        FOREIGN KEY (effective_attendance_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT fk_event_lifecycle_related
        FOREIGN KEY (related_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT fk_event_lifecycle_reversal
        FOREIGN KEY (source_reversal_record_id)
        REFERENCES source_reversal_record (source_reversal_record_id),
    CONSTRAINT fk_event_lifecycle_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_event_lifecycle_type CHECK (
        lifecycle_type IN ('ACTIVATED', 'RETRACTED', 'SUPERSEDED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE evidence_link (
    evidence_link_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_attendance_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_attendance_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_match_decision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (evidence_link_id),
    UNIQUE KEY uq_evidence_link_event_raw
        (effective_attendance_event_id, raw_attendance_fact_id),
    KEY ix_evidence_link_raw
        (raw_attendance_fact_id, effective_attendance_event_id),
    CONSTRAINT fk_evidence_link_event
        FOREIGN KEY (effective_attendance_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT fk_evidence_link_raw
        FOREIGN KEY (raw_attendance_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_evidence_link_normalized
        FOREIGN KEY (normalized_attendance_record_id)
        REFERENCES normalized_attendance_record (normalized_attendance_record_id),
    CONSTRAINT fk_evidence_link_match
        FOREIGN KEY (employee_match_decision_id)
        REFERENCES employee_match_decision (employee_match_decision_id),
    CONSTRAINT ck_evidence_link_type CHECK (
        link_type IN ('PRIMARY', 'EXACT_DUPLICATE', 'REVIEW_MEMBER', 'REVERSAL')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE duplicate_review_group (
    duplicate_review_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_direction VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_seconds INT UNSIGNED NOT NULL,
    window_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    group_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (duplicate_review_group_id),
    UNIQUE KEY uq_duplicate_group_digest
        (legal_entity_id, employee_id, group_digest),
    KEY ix_duplicate_group_pending
        (legal_entity_id, status, created_at, duplicate_review_group_id),
    CONSTRAINT fk_duplicate_group_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_duplicate_group_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_duplicate_group_direction CHECK (
        normalized_direction IN ('AUTO', 'IN', 'OUT')
    ),
    CONSTRAINT ck_duplicate_group_window CHECK (window_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_duplicate_group_status CHECK (
        status IN ('PENDING_DUPLICATE_REVIEW', 'RESOLVED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE duplicate_review_member (
    duplicate_review_member_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    duplicate_review_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_attendance_fact_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_effective_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    seconds_from_anchor INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (duplicate_review_member_id),
    UNIQUE KEY uq_duplicate_member_raw
        (duplicate_review_group_id, raw_attendance_fact_id),
    KEY ix_duplicate_member_event
        (prior_effective_event_id, duplicate_review_group_id),
    CONSTRAINT fk_duplicate_member_group
        FOREIGN KEY (duplicate_review_group_id)
        REFERENCES duplicate_review_group (duplicate_review_group_id),
    CONSTRAINT fk_duplicate_member_raw
        FOREIGN KEY (raw_attendance_fact_id)
        REFERENCES raw_attendance_fact (raw_attendance_fact_id),
    CONSTRAINT fk_duplicate_member_event
        FOREIGN KEY (prior_effective_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE duplicate_review_resolution (
    duplicate_review_resolution_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    duplicate_review_group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolution VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_group_version BIGINT UNSIGNED NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (duplicate_review_resolution_id),
    UNIQUE KEY uq_duplicate_resolution_group (duplicate_review_group_id),
    UNIQUE KEY uq_duplicate_resolution_idem (actor_id, idempotency_key),
    CONSTRAINT fk_duplicate_resolution_group
        FOREIGN KEY (duplicate_review_group_id)
        REFERENCES duplicate_review_group (duplicate_review_group_id),
    CONSTRAINT fk_duplicate_resolution_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_duplicate_resolution_value CHECK (
        resolution IN ('SAME_FACT', 'DISTINCT_FACTS')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE evidence_interval_slice (
    evidence_interval_slice_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slice_start DATETIME(6) NOT NULL,
    slice_end DATETIME(6) NOT NULL,
    resolution_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    winner_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_event_ids_json JSON NOT NULL,
    slice_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_at DATETIME(6) NOT NULL,
    PRIMARY KEY (evidence_interval_slice_id),
    UNIQUE KEY uq_evidence_slice_digest
        (legal_entity_id, employee_id, slice_digest),
    KEY ix_evidence_slice_subject
        (legal_entity_id, employee_id, slice_start, slice_end),
    CONSTRAINT fk_evidence_slice_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_evidence_slice_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_evidence_slice_winner
        FOREIGN KEY (winner_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id),
    CONSTRAINT ck_evidence_slice_period CHECK (slice_end > slice_start),
    CONSTRAINT ck_evidence_slice_status CHECK (
        resolution_status IN ('SELECTED', 'EVIDENCE_CONFLICT', 'REVERSED')
    ),
    CONSTRAINT ck_evidence_slice_winner CHECK (
        (resolution_status = 'SELECTED' AND winner_event_id IS NOT NULL)
        OR (resolution_status <> 'SELECTED' AND winner_event_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_recalculation_intent (
    attendance_recalculation_intent_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolver_snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    intent_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_recalculation_intent_id),
    UNIQUE KEY uq_att_recalc_intent_digest
        (legal_entity_id, employee_id, business_date, intent_digest),
    KEY ix_att_recalc_intent_subject
        (legal_entity_id, employee_id, business_date, created_at),
    CONSTRAINT fk_att_recalc_intent_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_att_recalc_intent_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_recalc_intent_event
        FOREIGN KEY (effective_attendance_event_id)
        REFERENCES effective_attendance_event (effective_attendance_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_ingestion_idempotency (
    attendance_ingestion_idempotency_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT UNSIGNED NULL,
    response_headers_json JSON NULL,
    response_body_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (attendance_ingestion_idempotency_id),
    UNIQUE KEY uq_att_ingestion_idempotency
        (actor_id, operation_code, resource_type, resource_id, idempotency_key),
    KEY ix_att_ingestion_idem_lookup
        (actor_id, operation_code, idempotency_key, status),
    CONSTRAINT fk_att_ingestion_idem_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_att_ingestion_idem_status CHECK (
        status IN ('STARTED', 'COMPLETED_SUCCESS')
    ),
    CONSTRAINT ck_att_ingestion_idem_response CHECK (
        (
            status = 'STARTED'
            AND response_status IS NULL
            AND completed_at IS NULL
        )
        OR (
            status = 'COMPLETED_SUCCESS'
            AND response_status IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('28000000-0000-0000-0000-000000000001', 'ATTENDANCE_SOURCE:READ', 'ATTENDANCE_SOURCE', 'READ'),
    ('28000000-0000-0000-0000-000000000002', 'ATTENDANCE_SOURCE:CONFIGURE', 'ATTENDANCE_SOURCE', 'CONFIGURE'),
    ('28000000-0000-0000-0000-000000000003', 'ATTENDANCE_SOURCE:RUN', 'ATTENDANCE_SOURCE', 'RUN'),
    ('28000000-0000-0000-0000-000000000004', 'ATTENDANCE_SOURCE:RETRY', 'ATTENDANCE_SOURCE', 'RETRY'),
    ('28000000-0000-0000-0000-000000000005', 'ATTENDANCE_SOURCE:QUARANTINE_READ', 'ATTENDANCE_SOURCE', 'QUARANTINE_READ');

-- Deployable, fail-closed Seeyon OA MySQL runtime contract.
-- Credential values remain repository-external OA_MYSQL_* configuration and
-- are never persisted in these tables.

CREATE TABLE oa_metadata_probe_run (
    oa_metadata_probe_run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    probe_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    database_product VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    database_version_family VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    database_character_set VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    database_collation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_table_count INT UNSIGNED NOT NULL,
    present_table_count INT UNSIGNED NOT NULL,
    expected_column_count INT UNSIGNED NOT NULL,
    compatible_column_count INT UNSIGNED NOT NULL,
    index_count INT UNSIGNED NOT NULL,
    dictionary_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    metadata_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    duration_millis BIGINT UNSIGNED NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    probed_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    probed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (oa_metadata_probe_run_id),
    KEY ix_oa_probe_source_time
        (attendance_source_id, probed_at, oa_metadata_probe_run_id),
    CONSTRAINT fk_oa_probe_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_oa_probe_actor
        FOREIGN KEY (probed_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_oa_probe_status CHECK (
        probe_status IN ('VERIFIED', 'NOT_VERIFIED', 'FAILED')
    ),
    CONSTRAINT ck_oa_probe_counts CHECK (
        expected_table_count = 10
        AND present_table_count <= expected_table_count
        AND compatible_column_count <= expected_column_count
    ),
    CONSTRAINT ck_oa_probe_error CHECK (
        (probe_status = 'VERIFIED' AND safe_error_code IS NULL)
        OR (probe_status <> 'VERIFIED' AND safe_error_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_metadata_probe_table_result (
    oa_metadata_probe_table_result_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_metadata_probe_run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    table_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    table_present BOOLEAN NOT NULL,
    expected_column_count INT UNSIGNED NOT NULL,
    actual_column_count INT UNSIGNED NOT NULL,
    compatible_column_count INT UNSIGNED NOT NULL,
    primary_key_column_count INT UNSIGNED NOT NULL,
    index_count INT UNSIGNED NOT NULL,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    table_metadata_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (oa_metadata_probe_table_result_id),
    UNIQUE KEY uq_oa_probe_table
        (oa_metadata_probe_run_id, table_name),
    CONSTRAINT fk_oa_probe_table_run
        FOREIGN KEY (oa_metadata_probe_run_id)
        REFERENCES oa_metadata_probe_run (oa_metadata_probe_run_id),
    CONSTRAINT ck_oa_probe_table_name CHECK (
        table_name IN (
            'formmain_0265', 'formmain_0170', 'formmain_0171',
            'formson_0172', 'formmain_0251', 'formson_0252',
            'formmain_0201', 'formson_0202', 'formmain_0203',
            'formson_0204'
        )
    ),
    CONSTRAINT ck_oa_probe_table_counts CHECK (
        compatible_column_count <= expected_column_count
        AND compatible_column_count <= actual_column_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_runtime_contract_revision (
    oa_runtime_contract_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    dictionary_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    date_range_end_rule VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    metadata_probe_run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    supersedes_contract_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    contract_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (oa_runtime_contract_revision_id),
    UNIQUE KEY uq_oa_contract_revision
        (attendance_source_id, revision_number),
    UNIQUE KEY uq_oa_contract_successor
        (supersedes_contract_revision_id),
    KEY ix_oa_contract_published
        (attendance_source_id, contract_status, revision_number),
    CONSTRAINT fk_oa_contract_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_oa_contract_probe
        FOREIGN KEY (metadata_probe_run_id)
        REFERENCES oa_metadata_probe_run (oa_metadata_probe_run_id),
    CONSTRAINT fk_oa_contract_previous
        FOREIGN KEY (supersedes_contract_revision_id)
        REFERENCES oa_runtime_contract_revision (oa_runtime_contract_revision_id),
    CONSTRAINT fk_oa_contract_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_oa_contract_published_by
        FOREIGN KEY (published_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_oa_contract_status CHECK (
        contract_status IN ('DRAFT', 'PUBLISHED', 'REJECTED')
    ),
    CONSTRAINT ck_oa_contract_date_rule CHECK (
        date_range_end_rule IN ('NOT_VERIFIED', 'END_EXCLUSIVE', 'END_DATE_INCLUSIVE')
    ),
    CONSTRAINT ck_oa_contract_publication CHECK (
        (contract_status = 'PUBLISHED'
            AND metadata_probe_run_id IS NOT NULL
            AND published_by IS NOT NULL
            AND published_at IS NOT NULL
            AND date_range_end_rule <> 'NOT_VERIFIED')
        OR (contract_status <> 'PUBLISHED'
            AND published_by IS NULL
            AND published_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_runtime_table_contract (
    oa_runtime_table_contract_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_runtime_contract_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    form_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    table_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    primary_key_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_table_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_key_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    foreign_key_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    approval_status_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    approved_values_json JSON NOT NULL,
    pending_values_json JSON NOT NULL,
    rejected_values_json JSON NOT NULL,
    revoked_values_json JSON NOT NULL,
    update_cursor_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    update_cursor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stable_cursor_column VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    stable_cursor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enum_mappings_json JSON NOT NULL,
    duty_overtime_raw_value VARCHAR(128) COLLATE utf8mb4_bin NULL,
    table_contract_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (oa_runtime_table_contract_id),
    UNIQUE KEY uq_oa_contract_table
        (oa_runtime_contract_revision_id, table_name),
    CONSTRAINT fk_oa_contract_table_revision
        FOREIGN KEY (oa_runtime_contract_revision_id)
        REFERENCES oa_runtime_contract_revision (oa_runtime_contract_revision_id),
    CONSTRAINT ck_oa_contract_form_kind CHECK (
        form_kind IN (
            'TRIP', 'LEAVE', 'OVERTIME', 'OUTING',
            'EXEMPT_PUNCH', 'PUNCH_CORRECTION'
        )
    ),
    CONSTRAINT ck_oa_contract_row_role CHECK (
        row_role IN ('MAIN', 'DETAIL')
    ),
    CONSTRAINT ck_oa_contract_cursor_type CHECK (
        update_cursor_type IN ('NOT_VERIFIED', 'DATE_TIME', 'INTEGER', 'TEXT')
        AND stable_cursor_type IN ('NOT_VERIFIED', 'INTEGER', 'TEXT')
    ),
    CONSTRAINT ck_oa_contract_duty_value CHECK (
        (table_name = 'formson_0172')
        OR duty_overtime_raw_value IS NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_source_attendance_group_scope (
    oa_source_attendance_group_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_config_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (oa_source_attendance_group_scope_id),
    UNIQUE KEY uq_oa_source_group_scope
        (source_config_revision_id, attendance_group_revision_id),
    KEY ix_oa_source_group_revision
        (attendance_group_revision_id, attendance_source_id),
    CONSTRAINT fk_oa_source_group_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_oa_source_group_config
        FOREIGN KEY (source_config_revision_id)
        REFERENCES attendance_source_config_revision (source_config_revision_id),
    CONSTRAINT fk_oa_source_group_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_oa_source_group_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_attendance_document_context (
    oa_attendance_document_context_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_attendance_document_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_runtime_contract_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    activation_decision VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_status_value VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    overtime_treatment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recognized_work_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    payroll_credit_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    time_off_credit_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    authorized_context_json JSON NOT NULL,
    context_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (oa_attendance_document_context_id),
    UNIQUE KEY uq_oa_document_context (oa_attendance_document_id),
    KEY ix_oa_document_context_decision
        (activation_decision, created_at, oa_attendance_document_context_id),
    CONSTRAINT fk_oa_document_context_document
        FOREIGN KEY (oa_attendance_document_id)
        REFERENCES oa_attendance_document (oa_attendance_document_id),
    CONSTRAINT fk_oa_document_context_contract
        FOREIGN KEY (oa_runtime_contract_revision_id)
        REFERENCES oa_runtime_contract_revision (oa_runtime_contract_revision_id),
    CONSTRAINT fk_oa_document_context_group
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT ck_oa_document_overtime_treatment CHECK (
        overtime_treatment IN (
            'NOT_APPLICABLE', 'NORMAL', 'DUTY_UNPAID', 'UNKNOWN'
        )
    ),
    CONSTRAINT ck_oa_document_overtime_credit CHECK (
        overtime_treatment <> 'DUTY_UNPAID'
        OR (payroll_credit_minutes = 0 AND time_off_credit_minutes = 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE oa_attendance_document
    DROP CHECK ck_oa_document_status,
    ADD CONSTRAINT ck_oa_document_status CHECK (
        source_status IN (
            'APPROVED', 'PENDING', 'DRAFT', 'REJECTED', 'UNKNOWN',
            'MODIFIED', 'SUPPLEMENTED', 'REVOKED'
        )
    );

-- V8/V9 introduced these capabilities without granting them to the bootstrap
-- administrator roles. Keep employee/self-service roles unchanged.
INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_SOURCE:READ',
      'ATTENDANCE_SOURCE:RUN',
      'ATTENDANCE_SOURCE:RETRY',
      'ATTENDANCE_SOURCE:QUARANTINE_READ',
      'ATTENDANCE_PUNCH_IMPORT:READ',
      'ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD',
      'ATTENDANCE_PUNCH_IMPORT:UPLOAD',
      'ATTENDANCE_PUNCH_IMPORT:PRECHECK',
      'ATTENDANCE_PUNCH_IMPORT:PUBLISH',
      'ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH',
      'ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE',
      'ATTENDANCE_PUNCH_IMPORT:RAW_FILE_READ',
      'ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ',
      'ATTENDANCE_PUNCH_IMPORT:ERROR_REPORT_DOWNLOAD',
      'ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW',
      'ATTENDANCE_PUNCH_IMPORT:RECALCULATE'
  )
WHERE role.role_code = 'HR_ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM auth_role_capability existing
      WHERE existing.role_id = role.role_id
        AND existing.capability_id = capability.capability_id
  );

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_SOURCE:READ',
      'ATTENDANCE_SOURCE:CONFIGURE',
      'ATTENDANCE_SOURCE:RUN',
      'ATTENDANCE_SOURCE:RETRY',
      'ATTENDANCE_SOURCE:QUARANTINE_READ',
      'ATTENDANCE_PUNCH_IMPORT:READ',
      'ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD',
      'ATTENDANCE_PUNCH_IMPORT:UPLOAD',
      'ATTENDANCE_PUNCH_IMPORT:PRECHECK',
      'ATTENDANCE_PUNCH_IMPORT:PUBLISH',
      'ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH',
      'ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE',
      'ATTENDANCE_PUNCH_IMPORT:RAW_FILE_READ',
      'ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ',
      'ATTENDANCE_PUNCH_IMPORT:ERROR_REPORT_DOWNLOAD',
      'ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW',
      'ATTENDANCE_PUNCH_IMPORT:RECALCULATE'
  )
WHERE role.role_code = 'SYSTEM_ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM auth_role_capability existing
      WHERE existing.role_id = role.role_id
        AND existing.capability_id = capability.capability_id
  );

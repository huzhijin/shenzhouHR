CREATE TABLE IF NOT EXISTS people_import_batch (
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mapping_json JSON NULL,
    added_count INT UNSIGNED NOT NULL DEFAULT 0,
    updated_count INT UNSIGNED NOT NULL DEFAULT 0,
    unchanged_count INT UNSIGNED NOT NULL DEFAULT 0,
    conflict_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_count INT UNSIGNED NOT NULL DEFAULT 0,
    blocking_issue_count INT UNSIGNED NOT NULL DEFAULT 0,
    precheck_version BIGINT UNSIGNED NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    voided_at DATETIME(6) NULL,
    duplicate_of_publication_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (batch_id),
    KEY ix_people_import_scope_status (legal_entity_id, status, created_at, batch_id),
    KEY ix_people_import_file_hash (file_sha256),
    CONSTRAINT fk_people_import_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_people_import_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_people_import_updated_by
        FOREIGN KEY (updated_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_people_import_template_type
        CHECK (template_type IN ('ORGANIZATION', 'EMPLOYEE', 'EMPLOYMENT', 'PRIOR_SERVICE')),
    CONSTRAINT ck_people_import_status
        CHECK (status IN (
            'DRAFT', 'VALIDATING', 'VALIDATION_FAILED', 'AWAITING_CONFIRMATION',
            'PUBLISHING', 'PUBLISHED', 'PUBLISH_FAILED', 'VOIDED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS people_import_file (
    file_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content LONGBLOB NOT NULL,
    uploaded_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (file_id),
    UNIQUE KEY uq_people_import_file_batch (batch_id),
    KEY ix_people_import_file_sha (sha256),
    CONSTRAINT fk_people_import_file_batch
        FOREIGN KEY (batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT fk_people_import_file_actor
        FOREIGN KEY (uploaded_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_people_import_file_size
        CHECK (size_bytes > 0 AND size_bytes <= 20971520)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE people_import_diff (
    diff_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `row_number` INT UNSIGNED NOT NULL,
    entity_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    matched_resource_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_values_json JSON NOT NULL,
    current_values_json JSON NULL,
    proposed_values_json JSON NULL,
    PRIMARY KEY (diff_id),
    KEY ix_people_import_diff_batch_category (batch_id, category, `row_number`, diff_id),
    CONSTRAINT fk_people_import_diff_batch
        FOREIGN KEY (batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT ck_people_import_diff_category
        CHECK (category IN ('ADDED', 'UPDATED', 'UNCHANGED', 'CONFLICT', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE people_import_issue (
    issue_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `row_number` INT UNSIGNED NOT NULL,
    field_name VARCHAR(100) NULL,
    issue_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message VARCHAR(500) NOT NULL,
    severity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_employee_ids_json JSON NULL,
    PRIMARY KEY (issue_id),
    KEY ix_people_import_issue_batch_severity (batch_id, severity, `row_number`, issue_id),
    CONSTRAINT fk_people_import_issue_batch
        FOREIGN KEY (batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT ck_people_import_issue_severity
        CHECK (severity IN ('WARNING', 'BLOCKING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE people_import_publication (
    publication_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json JSON NOT NULL,
    local_version_ids_json JSON NOT NULL,
    published_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (publication_id),
    UNIQUE KEY uq_people_publication_batch (batch_id),
    UNIQUE KEY uq_people_publication_scoped_file_hash
        (legal_entity_id, template_type, template_version, file_sha256),
    UNIQUE KEY uq_people_publication_idempotency (published_by, idempotency_key),
    CONSTRAINT fk_people_publication_batch
        FOREIGN KEY (batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT fk_people_publication_legal_entity
        FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_people_publication_actor
        FOREIGN KEY (published_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE people_import_batch
    ADD CONSTRAINT fk_people_import_duplicate_publication
        FOREIGN KEY (duplicate_of_publication_id)
        REFERENCES people_import_publication (publication_id);

CREATE TABLE people_import_rollback (
    rollback_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_publication_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    restored_snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_version_ids_json JSON NOT NULL,
    rolled_back_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rolled_back_at DATETIME(6) NOT NULL,
    PRIMARY KEY (rollback_id),
    UNIQUE KEY uq_people_rollback_publication (source_publication_id),
    UNIQUE KEY uq_people_rollback_idempotency (rolled_back_by, idempotency_key),
    CONSTRAINT fk_people_rollback_batch
        FOREIGN KEY (batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT fk_people_rollback_publication
        FOREIGN KEY (source_publication_id) REFERENCES people_import_publication (publication_id),
    CONSTRAINT fk_people_rollback_actor
        FOREIGN KEY (rolled_back_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE people_idempotency_record (
    idempotency_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (idempotency_record_id),
    UNIQUE KEY uq_people_idempotency_actor_action_key
        (actor_id, action_code, idempotency_key),
    CONSTRAINT fk_people_idempotency_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE organization_version
    ADD COLUMN status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN source_authority VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN source_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN change_reason VARCHAR(500) NULL,
    ADD COLUMN created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD KEY ix_org_version_source_batch (source_import_batch_id),
    ADD CONSTRAINT fk_org_version_source_batch
        FOREIGN KEY (source_import_batch_id) REFERENCES people_import_batch (batch_id),
    ADD CONSTRAINT fk_org_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    ADD CONSTRAINT ck_org_version_source_authority
        CHECK (source_authority IN ('INITIAL_EXCEL', 'LOCAL')),
    ADD CONSTRAINT ck_org_version_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'));

UPDATE organization_version version
JOIN organization_identity identity
  ON identity.organization_id = version.organization_id
SET version.status = CASE
    WHEN identity.identity_status = 'ACTIVE' THEN 'ACTIVE'
    ELSE 'INACTIVE'
END;

ALTER TABLE employee
    ADD COLUMN employee_number VARCHAR(128) COLLATE utf8mb4_bin NULL,
    ADD COLUMN row_version BIGINT UNSIGNED NOT NULL DEFAULT 0;

UPDATE employee
SET employee_number = CONCAT('LEGACY-', employee_id),
    updated_at = updated_at
WHERE employee_number IS NULL;

ALTER TABLE employee
    MODIFY COLUMN employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    ADD UNIQUE KEY uq_employee_number (legal_entity_id, employee_number);

CREATE TABLE employee_version (
    employee_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_employee_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    source_authority VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    change_reason VARCHAR(500) NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    current_marker TINYINT GENERATED ALWAYS AS
        (CASE WHEN effective_to IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (employee_version_id),
    UNIQUE KEY uq_employee_version_current (employee_id, current_marker),
    KEY ix_employee_version_period (employee_id, effective_from, effective_to),
    KEY ix_employee_version_number (employee_number, effective_from, effective_to),
    KEY ix_employee_version_source_batch (source_import_batch_id),
    CONSTRAINT fk_employee_version_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_employee_version_source_batch
        FOREIGN KEY (source_import_batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT fk_employee_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_employee_version_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'TERMINATED')),
    CONSTRAINT ck_employee_version_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_employee_version_source
        CHECK (source_authority IN ('INITIAL_EXCEL', 'LOCAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_principal (
    principal_id, employee_id, status, created_at, row_version
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP(6),
    0
)
ON DUPLICATE KEY UPDATE principal_id = VALUES(principal_id);

UPDATE organization_version
SET created_by = '20000000-0000-0000-0000-000000000001'
WHERE created_by IS NULL;

INSERT INTO employee_version (
    employee_version_id, employee_id, employee_number, display_name, status,
    external_employee_id, effective_from, effective_to, source_authority,
    row_version, change_reason, created_by, created_at
)
SELECT
    UUID(), employee.employee_id, employee.employee_number, employee.display_name,
    CASE
        WHEN employee.employment_status = 'TERMINATED' THEN 'TERMINATED'
        WHEN employee.employment_status = 'INACTIVE' THEN 'INACTIVE'
        ELSE 'ACTIVE'
    END,
    (
        SELECT MIN(COALESCE(
            current_binding.seeyon_person_id,
            current_binding.seeyon_oa_code,
            current_binding.deli_user_id,
            current_binding.deli_ext_id,
            current_binding.deli_employee_num
        ))
        FROM employee_source_binding current_binding
        WHERE current_binding.employee_id = employee.employee_id
          AND current_binding.effective_to IS NULL
    ),
    COALESCE(employee.onboard_date, DATE('1970-01-01')), NULL, 'LOCAL',
    employee.row_version, 'V5_LEGACY_BACKFILL',
    '20000000-0000-0000-0000-000000000001', employee.created_at
FROM employee
;

CREATE TABLE employee_current_projection (
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (employee_id),
    UNIQUE KEY uq_employee_current_version (current_version_id),
    CONSTRAINT fk_employee_projection_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_employee_projection_version
        FOREIGN KEY (current_version_id) REFERENCES employee_version (employee_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at)
SELECT employee_id, employee_version_id, CURRENT_TIMESTAMP(6)
FROM employee_version
WHERE effective_to IS NULL;

ALTER TABLE employment_assignment
    ADD COLUMN employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN termination_date DATE NULL,
    ADD COLUMN source_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN change_reason VARCHAR(500) NULL,
    ADD COLUMN created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN version_valid_to DATETIME(6) NULL,
    ADD COLUMN record_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN current_version_marker TINYINT GENERATED ALWAYS AS
        (CASE WHEN version_valid_to IS NULL THEN 1 ELSE NULL END) STORED,
    ADD KEY ix_assignment_source_batch (source_import_batch_id),
    ADD KEY ix_assignment_period_version (employment_period_id, created_at),
    ADD CONSTRAINT fk_assignment_source_batch
        FOREIGN KEY (source_import_batch_id) REFERENCES people_import_batch (batch_id),
    ADD CONSTRAINT fk_assignment_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    ADD CONSTRAINT ck_assignment_version_period
        CHECK (version_valid_to IS NULL OR version_valid_to > created_at),
    ADD CONSTRAINT ck_assignment_record_status
        CHECK (record_status IN ('ACTIVE', 'RETRACTED'));

UPDATE employment_assignment
SET employment_period_id = assignment_id,
    termination_date = CASE
        WHEN effective_to IS NULL THEN NULL
        ELSE DATE_SUB(DATE(effective_to), INTERVAL 1 DAY)
    END;

ALTER TABLE employment_assignment
    MODIFY COLUMN employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uq_assignment_current_period
        (employment_period_id, current_version_marker);

CREATE TABLE prior_service_record (
    prior_service_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_days INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    business_date DATE NOT NULL,
    source_import_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reversal_of_record_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resulting_total_days INT UNSIGNED NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (prior_service_record_id),
    UNIQUE KEY uq_prior_service_request (employee_id, request_id),
    UNIQUE KEY uq_prior_service_sequence (employee_id, row_version),
    KEY ix_prior_service_employee_time
        (employee_id, row_version, occurred_at, prior_service_record_id),
    KEY ix_prior_service_source_batch (source_import_batch_id),
    CONSTRAINT fk_prior_service_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_prior_service_source_batch
        FOREIGN KEY (source_import_batch_id) REFERENCES people_import_batch (batch_id),
    CONSTRAINT fk_prior_service_reversal
        FOREIGN KEY (reversal_of_record_id) REFERENCES prior_service_record (prior_service_record_id),
    CONSTRAINT fk_prior_service_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_prior_service_type
        CHECK (record_type IN ('OPENING_IMPORT', 'ADJUSTMENT', 'REVERSAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('23000000-0000-0000-0000-000000000001', 'PEOPLE_IMPORT:TEMPLATE_DOWNLOAD', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000002', 'PEOPLE_IMPORT:READ', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000003', 'PEOPLE_IMPORT:CREATE', 'PEOPLE', 'CREATE'),
    ('23000000-0000-0000-0000-000000000004', 'PEOPLE_IMPORT:UPLOAD', 'PEOPLE', 'UPLOAD'),
    ('23000000-0000-0000-0000-000000000005', 'PEOPLE_IMPORT:MAP', 'PEOPLE', 'EDIT'),
    ('23000000-0000-0000-0000-000000000006', 'PEOPLE_IMPORT:PRECHECK', 'PEOPLE', 'VALIDATE'),
    ('23000000-0000-0000-0000-000000000007', 'PEOPLE_IMPORT:ERROR_REPORT_DOWNLOAD', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000008', 'PEOPLE_IMPORT:PUBLISH', 'PEOPLE', 'PUBLISH'),
    ('23000000-0000-0000-0000-000000000009', 'PEOPLE_IMPORT:VOID', 'PEOPLE', 'VOID'),
    ('23000000-0000-0000-0000-000000000010', 'PEOPLE_IMPORT:ROLLBACK', 'PEOPLE', 'ROLLBACK'),
    ('23000000-0000-0000-0000-000000000011', 'ORGANIZATION:READ', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000012', 'ORGANIZATION:CREATE', 'PEOPLE', 'CREATE'),
    ('23000000-0000-0000-0000-000000000013', 'ORGANIZATION:EDIT', 'PEOPLE', 'EDIT'),
    ('23000000-0000-0000-0000-000000000014', 'EMPLOYEE:READ', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000015', 'EMPLOYEE:CREATE', 'PEOPLE', 'CREATE'),
    ('23000000-0000-0000-0000-000000000016', 'EMPLOYEE:EDIT', 'PEOPLE', 'EDIT'),
    ('23000000-0000-0000-0000-000000000017', 'EMPLOYMENT:READ', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000018', 'EMPLOYMENT:CREATE', 'PEOPLE', 'CREATE'),
    ('23000000-0000-0000-0000-000000000019', 'EMPLOYMENT:EDIT', 'PEOPLE', 'EDIT'),
    ('23000000-0000-0000-0000-000000000020', 'PRIOR_SERVICE:READ', 'PEOPLE', 'READ'),
    ('23000000-0000-0000-0000-000000000021', 'PRIOR_SERVICE:ADJUST', 'PEOPLE', 'ADJUST');

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
CROSS JOIN auth_capability capability
WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN')
  AND capability.capability_id LIKE '23000000-%';

DELETE role_capability
FROM auth_role_capability role_capability
JOIN auth_capability capability
  ON capability.capability_id = role_capability.capability_id
WHERE capability.capability_code = 'MASTER_DATA:SYNC_PREVIEW';

DELETE FROM auth_capability
WHERE capability_code = 'MASTER_DATA:SYNC_PREVIEW';

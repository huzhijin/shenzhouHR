CREATE TABLE company (
    company_id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_principal (
    principal_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE auth_principal_role_assignment (
    assignment_id VARCHAR(36) PRIMARY KEY,
    principal_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(36) NOT NULL,
    data_scope_id VARCHAR(36),
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP
);

CREATE TABLE auth_role_capability (
    role_id VARCHAR(36) NOT NULL,
    capability_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (role_id, capability_id)
);

CREATE TABLE auth_capability (
    capability_id VARCHAR(36) PRIMARY KEY,
    capability_code VARCHAR(96) NOT NULL
);

CREATE TABLE auth_data_scope (
    scope_id VARCHAR(36) PRIMARY KEY,
    scope_type VARCHAR(32) NOT NULL,
    company_id VARCHAR(36),
    organization_id VARCHAR(36),
    include_descendants BOOLEAN NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    CONSTRAINT fk_test_auth_scope_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT ck_test_auth_scope_target CHECK (
        (scope_type = 'COMPANY' AND company_id IS NOT NULL
            AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND company_id IS NULL
            AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF' AND company_id IS NULL
            AND organization_id IS NULL)
    )
);

CREATE TABLE organization_identity (
    organization_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    identity_status VARCHAR(32) NOT NULL
);
ALTER TABLE organization_identity ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;

CREATE TABLE organization_version (
    organization_version_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    parent_organization_id VARCHAR(36),
    code VARCHAR(128) NOT NULL,
    name VARCHAR(200) NOT NULL,
    org_type VARCHAR(32) NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP
);

CREATE TABLE organization_current_projection (
    organization_id VARCHAR(36) PRIMARY KEY,
    current_version_id VARCHAR(36) NOT NULL,
    projection_batch_id VARCHAR(36) DEFAULT 'TEST' NOT NULL,
    projected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE organization_current_closure (
    ancestor_organization_id VARCHAR(36) NOT NULL,
    descendant_organization_id VARCHAR(36) NOT NULL,
    depth SMALLINT DEFAULT 0 NOT NULL,
    projection_batch_id VARCHAR(36) DEFAULT 'TEST' NOT NULL,
    PRIMARY KEY (ancestor_organization_id, descendant_organization_id)
);

CREATE TABLE organization_source_binding (
    source_binding_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_org_id VARCHAR(128) NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP
);

CREATE TABLE employee (
    employee_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    employment_status VARCHAR(32) NOT NULL
);

CREATE TABLE punch_exemption_assignment (
    exemption_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    listed_employee_number VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE employment_assignment (
    assignment_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP
);

CREATE TABLE employee_source_binding (
    binding_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    seeyon_oa_code VARCHAR(128),
    binding_status VARCHAR(32) NOT NULL,
    effective_to TIMESTAMP
);

-- WAVE-1 tests keep their schema explicit so H2 remains a fast contract test,
-- while the separately required MySQL suite verifies the real Flyway DDL.
ALTER TABLE auth_principal ADD COLUMN employee_id VARCHAR(36);
CREATE UNIQUE INDEX uq_test_auth_principal_employee
    ON auth_principal (employee_id);
ALTER TABLE auth_principal ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE auth_principal ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE auth_principal_role_assignment ADD COLUMN assigned_by VARCHAR(36);
ALTER TABLE auth_principal_role_assignment ADD COLUMN reason VARCHAR(500) DEFAULT 'SYNTHETIC TEST ASSIGNMENT' NOT NULL;
ALTER TABLE auth_principal_role_assignment ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE auth_capability ADD COLUMN permission_domain VARCHAR(32);
ALTER TABLE auth_capability ADD COLUMN action_code VARCHAR(32);

CREATE TABLE auth_role (
    role_id VARCHAR(36) PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(100) NOT NULL,
    permission_domain VARCHAR(32) NOT NULL
);

CREATE TABLE local_account (
    account_id VARCHAR(36) PRIMARY KEY,
    principal_id VARCHAR(36) NOT NULL UNIQUE,
    username VARCHAR(128) NOT NULL,
    normalized_username VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    first_password_change_required BOOLEAN DEFAULT TRUE NOT NULL,
    locked_until TIMESTAMP,
    last_login_at TIMESTAMP,
    session_epoch BIGINT DEFAULT 0 NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_test_local_account_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id)
);

CREATE TABLE password_credential (
    credential_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    parameter_version VARCHAR(32) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_test_password_credential_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
);

CREATE TABLE login_failure_window (
    account_id VARCHAR(36) PRIMARY KEY,
    failure_count INTEGER DEFAULT 0 NOT NULL,
    window_started_at TIMESTAMP,
    last_failed_at TIMESTAMP,
    locked_until TIMESTAMP,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_test_login_failure_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
);

CREATE TABLE password_reset_grant (
    grant_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    token_digest CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    issued_by VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_test_password_reset_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
);

CREATE TABLE user_session (
    session_id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    token_digest CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP,
    idle_expires_at TIMESTAMP NOT NULL,
    absolute_expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    revocation_reason VARCHAR(500),
    request_id VARCHAR(64) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_test_user_session_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
);

CREATE INDEX ix_test_user_session_active
    ON user_session (account_id, status, idle_expires_at, absolute_expires_at);

CREATE TABLE session_revocation (
    revocation_id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    revoked_by VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    revoked_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_test_session_revocation_session
        FOREIGN KEY (session_id) REFERENCES user_session (session_id),
    CONSTRAINT fk_test_session_revocation_account
        FOREIGN KEY (account_id) REFERENCES local_account (account_id)
);

CREATE TABLE audit_event (
    event_id VARCHAR(36) PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    actor_id_ref VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id_ref VARCHAR(128),
    scope_digest CHAR(64),
    purpose_code VARCHAR(64),
    result_code VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    policy_version VARCHAR(64),
    before_digest CHAR(64),
    after_digest CHAR(64),
    correlation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    previous_hash CHAR(64),
    event_hash CHAR(64) NOT NULL UNIQUE,
    anchor_ref VARCHAR(128)
);

CREATE INDEX ix_test_audit_actor_time
    ON audit_event (actor_id_ref, occurred_at);
CREATE INDEX ix_test_audit_resource_time
    ON audit_event (resource_type, resource_id_ref, occurred_at);

CREATE TABLE policy_template (
    template_id VARCHAR(36) PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    field_definitions_json CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE policy_version (
    version_id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    parameters_json CLOB NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    change_reason VARCHAR(500) NOT NULL,
    validation_json CLOB,
    snapshot_json CLOB,
    snapshot_digest CHAR(64),
    rollback_of_version_id VARCHAR(36),
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    updated_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (template_id, version_number),
    CONSTRAINT fk_test_policy_version_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_test_policy_version_rollback
        FOREIGN KEY (rollback_of_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT ck_test_policy_version_period
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX ix_test_policy_version_lifecycle
    ON policy_version (template_id, status, effective_from, effective_to);

CREATE TABLE policy_scope_binding (
    binding_id VARCHAR(36) PRIMARY KEY,
    version_id VARCHAR(36) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_resource_id VARCHAR(128) NOT NULL,
    priority INTEGER NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    row_version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT fk_test_policy_scope_version
        FOREIGN KEY (version_id) REFERENCES policy_version (version_id)
);

CREATE INDEX ix_test_policy_scope_conflict
    ON policy_scope_binding (
        scope_type, scope_resource_id, priority, effective_from, effective_to
    );

CREATE TABLE policy_publication_record (
    publication_id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    snapshot_digest CHAR(64),
    CONSTRAINT fk_test_policy_publication_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_test_policy_publication_version
        FOREIGN KEY (version_id) REFERENCES policy_version (version_id)
);

CREATE TABLE policy_rollback_record (
    rollback_id VARCHAR(36) PRIMARY KEY,
    template_id VARCHAR(36) NOT NULL,
    source_version_id VARCHAR(36) NOT NULL,
    target_version_id VARCHAR(36) NOT NULL,
    created_version_id VARCHAR(36) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_test_policy_rollback_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_test_policy_rollback_source
        FOREIGN KEY (source_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_test_policy_rollback_target
        FOREIGN KEY (target_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_test_policy_rollback_created
        FOREIGN KEY (created_version_id) REFERENCES policy_version (version_id)
);

-- WAVE-2 keeps H2 explicit and portable; MySQL-specific generated columns,
-- checks, indexes and Flyway behavior are verified by the separate real-MySQL gate.
ALTER TABLE organization_version ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE organization_version ADD COLUMN status VARCHAR(32) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE organization_version ADD COLUMN source_authority VARCHAR(32) DEFAULT 'LOCAL' NOT NULL;
ALTER TABLE organization_version ADD COLUMN source_import_batch_id VARCHAR(36);
ALTER TABLE organization_version ADD COLUMN change_reason VARCHAR(500);
ALTER TABLE organization_version ADD COLUMN created_by VARCHAR(36);
ALTER TABLE organization_version ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;

ALTER TABLE employee ADD COLUMN employee_number VARCHAR(128);
ALTER TABLE employee ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE employee ADD COLUMN onboard_date DATE;
ALTER TABLE employee ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE employee ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
UPDATE employee SET employee_number = CONCAT('LEGACY-', employee_id);

ALTER TABLE employment_assignment ADD COLUMN termination_date DATE;
ALTER TABLE employment_assignment ADD COLUMN employment_period_id VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN position_id VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN payroll_plan_id VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN cost_center_id VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN source_import_batch_id VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE employment_assignment ADD COLUMN change_reason VARCHAR(500);
ALTER TABLE employment_assignment ADD COLUMN created_by VARCHAR(36);
ALTER TABLE employment_assignment ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
ALTER TABLE employment_assignment ADD COLUMN version_valid_to TIMESTAMP;
ALTER TABLE employment_assignment ADD COLUMN record_status VARCHAR(32) DEFAULT 'ACTIVE' NOT NULL;

CREATE TABLE employment_period_identity (
    employment_period_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (employment_period_id, employee_id, company_id)
);

CREATE TABLE people_import_batch (
    batch_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    template_type VARCHAR(32) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    file_sha256 CHAR(64),
    mapping_json CLOB,
    added_count INTEGER DEFAULT 0 NOT NULL,
    updated_count INTEGER DEFAULT 0 NOT NULL,
    unchanged_count INTEGER DEFAULT 0 NOT NULL,
    conflict_count INTEGER DEFAULT 0 NOT NULL,
    error_count INTEGER DEFAULT 0 NOT NULL,
    blocking_issue_count INTEGER DEFAULT 0 NOT NULL,
    precheck_version BIGINT,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    voided_at TIMESTAMP,
    duplicate_of_publication_id VARCHAR(36)
);

CREATE TABLE people_import_file (
    file_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL UNIQUE,
    original_file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content BLOB NOT NULL,
    uploaded_by VARCHAR(36) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL
);

CREATE TABLE people_import_diff (
    diff_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    row_number INTEGER NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    matched_resource_id VARCHAR(36),
    source_values_json CLOB NOT NULL,
    current_values_json CLOB,
    proposed_values_json CLOB
);

CREATE TABLE people_import_issue (
    issue_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    row_number INTEGER NOT NULL,
    field_name VARCHAR(100),
    issue_code VARCHAR(96) NOT NULL,
    message VARCHAR(500) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    candidate_employee_ids_json CLOB
);

CREATE TABLE people_import_publication (
    publication_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL UNIQUE,
    company_id VARCHAR(36),
    template_type VARCHAR(32),
    template_version VARCHAR(32),
    file_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    local_version_ids_json CLOB NOT NULL,
    published_by VARCHAR(36) NOT NULL,
    published_at TIMESTAMP NOT NULL,
    UNIQUE (published_by, idempotency_key),
    UNIQUE (company_id, template_type, template_version, file_sha256)
);

CREATE TABLE people_import_rollback (
    rollback_id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    source_publication_id VARCHAR(36) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    restored_snapshot_digest CHAR(64) NOT NULL,
    created_version_ids_json CLOB NOT NULL,
    rolled_back_by VARCHAR(36) NOT NULL,
    rolled_back_at TIMESTAMP NOT NULL,
    UNIQUE (rolled_back_by, idempotency_key)
);

CREATE TABLE people_idempotency_record (
    idempotency_record_id VARCHAR(36) PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    resource_id VARCHAR(36),
    result_json CLOB,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (actor_id, action_code, idempotency_key)
);

CREATE TABLE employee_version (
    employee_version_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    employee_number VARCHAR(128) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    external_employee_id VARCHAR(128),
    effective_from DATE NOT NULL,
    effective_to DATE,
    source_authority VARCHAR(32) NOT NULL,
    source_import_batch_id VARCHAR(36),
    row_version BIGINT DEFAULT 0 NOT NULL,
    change_reason VARCHAR(500),
    created_by VARCHAR(36),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE employee_current_projection (
    employee_id VARCHAR(36) PRIMARY KEY,
    current_version_id VARCHAR(36) NOT NULL UNIQUE,
    projected_at TIMESTAMP NOT NULL
);

CREATE TABLE prior_service_record (
    prior_service_record_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    record_type VARCHAR(32) NOT NULL,
    amount_days INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    business_date DATE NOT NULL,
    source_import_batch_id VARCHAR(36),
    reversal_of_record_id VARCHAR(36),
    resulting_total_days INTEGER NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    UNIQUE (employee_id, request_id),
    UNIQUE (employee_id, row_version)
);

CREATE TABLE location (
    location_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (company_id, location_code),
    UNIQUE (company_id, location_id),
    UNIQUE (company_id, location_id, location_code),
    CONSTRAINT fk_test_location_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE location_revision (
    location_revision_id VARCHAR(36) PRIMARY KEY,
    location_id VARCHAR(36) NOT NULL,
    revision_number INTEGER NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_location_revision_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (location_id, revision_number),
    UNIQUE (location_id, effective_from),
    UNIQUE (supersedes_location_revision_id),
    CONSTRAINT fk_test_location_revision_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_test_location_revision_predecessor
        FOREIGN KEY (supersedes_location_revision_id)
        REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_test_location_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_location_revision_number CHECK (revision_number > 0)
);

CREATE TABLE shared_location (
    shared_location_id VARCHAR(36) PRIMARY KEY,
    location_code VARCHAR(64) NOT NULL UNIQUE,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (shared_location_id, location_code),
    CONSTRAINT fk_test_shared_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE shared_location_revision (
    shared_location_revision_id VARCHAR(36) PRIMARY KEY,
    shared_location_id VARCHAR(36) NOT NULL,
    revision_number INTEGER NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    supersedes_shared_location_revision_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (shared_location_id, revision_number),
    UNIQUE (shared_location_id, effective_from),
    UNIQUE (supersedes_shared_location_revision_id),
    CONSTRAINT fk_test_shared_location_revision_location
        FOREIGN KEY (shared_location_id)
        REFERENCES shared_location (shared_location_id),
    CONSTRAINT fk_test_shared_location_revision_predecessor
        FOREIGN KEY (supersedes_shared_location_revision_id)
        REFERENCES shared_location_revision (shared_location_revision_id),
    CONSTRAINT fk_test_shared_location_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_shared_location_revision_number
        CHECK (revision_number > 0),
    CONSTRAINT ck_test_shared_location_revision_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_test_shared_location_revision_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE company_location_availability (
    company_location_availability_id VARCHAR(36) PRIMARY KEY,
    shared_location_id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    location_id VARCHAR(36) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (company_id, shared_location_id),
    UNIQUE (location_id),
    CONSTRAINT fk_test_company_location_shared_code
        FOREIGN KEY (shared_location_id, location_code)
        REFERENCES shared_location (shared_location_id, location_code),
    CONSTRAINT fk_test_company_location_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_company_location_projection_company
        FOREIGN KEY (company_id, location_id, location_code)
        REFERENCES location (company_id, location_id, location_code),
    CONSTRAINT fk_test_company_location_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_company_location_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_test_company_location_period
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE shift_template (
    shift_template_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    location_id VARCHAR(36) NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (company_id, template_code),
    CONSTRAINT fk_test_shift_template_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_shift_template_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_test_shift_template_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE shift_version (
    shift_version_id VARCHAR(36) PRIMARY KEY,
    shift_template_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    effective_from DATE NOT NULL,
    time_zone_snapshot VARCHAR(64) NOT NULL,
    segments_json CLOB NOT NULL,
    supersedes_shift_version_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (shift_template_id, version_number),
    UNIQUE (supersedes_shift_version_id),
    CONSTRAINT fk_test_shift_version_template
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_test_shift_version_predecessor
        FOREIGN KEY (supersedes_shift_version_id)
        REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_test_shift_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_shift_version_number CHECK (version_number > 0)
);

CREATE TABLE work_calendar (
    work_calendar_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    location_id VARCHAR(36) NOT NULL,
    calendar_code VARCHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (company_id, calendar_code),
    CONSTRAINT fk_test_work_calendar_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_work_calendar_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_test_work_calendar_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE work_calendar_version (
    work_calendar_version_id VARCHAR(36) PRIMARY KEY,
    work_calendar_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    calendar_name VARCHAR(100) NOT NULL,
    calendar_year INTEGER NOT NULL,
    time_zone_snapshot VARCHAR(64) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    supersedes_work_calendar_version_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (work_calendar_id, version_number),
    UNIQUE (supersedes_work_calendar_version_id),
    CONSTRAINT fk_test_work_calendar_version_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_test_work_calendar_version_predecessor
        FOREIGN KEY (supersedes_work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_test_work_calendar_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_work_calendar_version_number CHECK (version_number > 0),
    CONSTRAINT ck_test_work_calendar_version_year
        CHECK (calendar_year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_test_work_calendar_version_period
        CHECK (effective_to > effective_from)
);

CREATE TABLE work_calendar_day (
    work_calendar_day_id VARCHAR(36) PRIMARY KEY,
    work_calendar_version_id VARCHAR(36) NOT NULL,
    business_date DATE NOT NULL,
    day_type VARCHAR(32) NOT NULL,
    shift_version_override_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (work_calendar_version_id, business_date),
    CONSTRAINT fk_test_work_calendar_day_version
        FOREIGN KEY (work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_test_work_calendar_day_shift
        FOREIGN KEY (shift_version_override_id) REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_test_work_calendar_day_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_work_calendar_day_type
        CHECK (day_type IN ('WORKDAY', 'WEEKEND', 'PUBLIC_HOLIDAY', 'SPECIAL_WORKDAY'))
);

CREATE TABLE attendance_group (
    attendance_group_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36) NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (company_id, group_code),
    CONSTRAINT fk_test_attendance_group_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_attendance_group_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE attendance_group_revision (
    attendance_group_revision_id VARCHAR(36) PRIMARY KEY,
    attendance_group_id VARCHAR(36) NOT NULL,
    revision_number INTEGER NOT NULL,
    group_name VARCHAR(100) NOT NULL,
    location_revision_id VARCHAR(36) NOT NULL,
    work_calendar_id VARCHAR(36) NOT NULL,
    shift_template_id VARCHAR(36) NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_attendance_group_revision_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (attendance_group_id, revision_number),
    UNIQUE (attendance_group_id, effective_from),
    UNIQUE (supersedes_attendance_group_revision_id),
    CONSTRAINT fk_test_attendance_group_revision_group
        FOREIGN KEY (attendance_group_id) REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_test_attendance_group_revision_location
        FOREIGN KEY (location_revision_id) REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_test_attendance_group_revision_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_test_attendance_group_revision_shift
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_test_attendance_group_revision_predecessor
        FOREIGN KEY (supersedes_attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_test_attendance_group_revision_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_attendance_group_revision_number CHECK (revision_number > 0)
);

CREATE TABLE attendance_group_assignment (
    attendance_group_assignment_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36) NOT NULL,
    attendance_group_revision_id VARCHAR(36) NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_assignment_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (supersedes_assignment_id),
    CONSTRAINT fk_test_attendance_assignment_group_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_test_attendance_assignment_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_test_attendance_assignment_predecessor
        FOREIGN KEY (supersedes_assignment_id)
        REFERENCES attendance_group_assignment (attendance_group_assignment_id),
    CONSTRAINT fk_test_attendance_assignment_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id)
);

CREATE TABLE location_timeline (
    location_timeline_id VARCHAR(36) PRIMARY KEY,
    location_id VARCHAR(36) NOT NULL,
    location_revision_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36),
    recorded_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    UNIQUE (location_id, event_sequence),
    UNIQUE (predecessor_timeline_id),
    CONSTRAINT fk_test_location_timeline_location
        FOREIGN KEY (location_id) REFERENCES location (location_id),
    CONSTRAINT fk_test_location_timeline_revision
        FOREIGN KEY (location_revision_id) REFERENCES location_revision (location_revision_id),
    CONSTRAINT fk_test_location_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES location_timeline (location_timeline_id),
    CONSTRAINT fk_test_location_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_location_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_test_location_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_test_location_timeline_resolution
    ON location_timeline (
        location_id, business_effective_from, recorded_at, event_sequence
    );

CREATE TABLE shift_publication_timeline (
    shift_publication_timeline_id VARCHAR(36) PRIMARY KEY,
    shift_template_id VARCHAR(36) NOT NULL,
    shift_version_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36),
    recorded_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    UNIQUE (shift_template_id, event_sequence),
    UNIQUE (predecessor_timeline_id),
    CONSTRAINT fk_test_shift_publication_template
        FOREIGN KEY (shift_template_id) REFERENCES shift_template (shift_template_id),
    CONSTRAINT fk_test_shift_publication_version
        FOREIGN KEY (shift_version_id) REFERENCES shift_version (shift_version_id),
    CONSTRAINT fk_test_shift_publication_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES shift_publication_timeline (shift_publication_timeline_id),
    CONSTRAINT fk_test_shift_publication_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_shift_publication_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_test_shift_publication_state CHECK (state IN ('PUBLISHED', 'INACTIVE'))
);
CREATE INDEX ix_test_shift_publication_resolution
    ON shift_publication_timeline (
        shift_template_id, business_effective_from, recorded_at, event_sequence
    );

CREATE TABLE calendar_publication_timeline (
    calendar_publication_timeline_id VARCHAR(36) PRIMARY KEY,
    work_calendar_id VARCHAR(36) NOT NULL,
    work_calendar_version_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36),
    recorded_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    UNIQUE (work_calendar_id, event_sequence),
    UNIQUE (predecessor_timeline_id),
    CONSTRAINT fk_test_calendar_publication_calendar
        FOREIGN KEY (work_calendar_id) REFERENCES work_calendar (work_calendar_id),
    CONSTRAINT fk_test_calendar_publication_version
        FOREIGN KEY (work_calendar_version_id)
        REFERENCES work_calendar_version (work_calendar_version_id),
    CONSTRAINT fk_test_calendar_publication_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES calendar_publication_timeline (calendar_publication_timeline_id),
    CONSTRAINT fk_test_calendar_publication_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_calendar_publication_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_test_calendar_publication_state CHECK (state IN ('PUBLISHED', 'INACTIVE'))
);
CREATE INDEX ix_test_calendar_publication_resolution
    ON calendar_publication_timeline (
        work_calendar_id, business_effective_from, recorded_at, event_sequence
    );

CREATE TABLE attendance_group_timeline (
    attendance_group_timeline_id VARCHAR(36) PRIMARY KEY,
    attendance_group_id VARCHAR(36) NOT NULL,
    attendance_group_revision_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36),
    recorded_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    UNIQUE (attendance_group_id, event_sequence),
    UNIQUE (predecessor_timeline_id),
    CONSTRAINT fk_test_attendance_group_timeline_group
        FOREIGN KEY (attendance_group_id) REFERENCES attendance_group (attendance_group_id),
    CONSTRAINT fk_test_attendance_group_timeline_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_test_attendance_group_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES attendance_group_timeline (attendance_group_timeline_id),
    CONSTRAINT fk_test_attendance_group_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_attendance_group_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_test_attendance_group_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_test_attendance_group_timeline_resolution
    ON attendance_group_timeline (
        attendance_group_id, business_effective_from, recorded_at, event_sequence
    );

CREATE TABLE attendance_assignment_timeline (
    attendance_assignment_timeline_id VARCHAR(36) PRIMARY KEY,
    attendance_group_assignment_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    state VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_timeline_id VARCHAR(36),
    recorded_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    UNIQUE (attendance_group_assignment_id, event_sequence),
    UNIQUE (predecessor_timeline_id),
    CONSTRAINT fk_test_attendance_assignment_timeline_assignment
        FOREIGN KEY (attendance_group_assignment_id)
        REFERENCES attendance_group_assignment (attendance_group_assignment_id),
    CONSTRAINT fk_test_attendance_assignment_timeline_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_test_attendance_assignment_timeline_predecessor
        FOREIGN KEY (predecessor_timeline_id)
        REFERENCES attendance_assignment_timeline (attendance_assignment_timeline_id),
    CONSTRAINT fk_test_attendance_assignment_timeline_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_attendance_assignment_timeline_sequence CHECK (event_sequence > 0),
    CONSTRAINT ck_test_attendance_assignment_timeline_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_test_attendance_assignment_timeline_resolution
    ON attendance_assignment_timeline (
        employee_id, business_effective_from, recorded_at, event_sequence
    );

CREATE TABLE attendance_policy_template (
    policy_template_id VARCHAR(36) PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    field_definitions_json CLOB NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_test_attendance_policy_template_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_attendance_policy_template_code
        CHECK (template_code IN (
            'MEAL_DEDUCTION',
            'LATE_GRACE',
            'MONTHLY_LATE_EXEMPTION',
            'PUNCH_WINDOW',
            'PERIOD_CLOSE'
        ))
);

CREATE TABLE attendance_policy_scope (
    scope_id VARCHAR(36) PRIMARY KEY,
    policy_template_id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    row_version BIGINT DEFAULT 0 NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (policy_template_id, company_id),
    CONSTRAINT fk_test_attendance_policy_scope_template
        FOREIGN KEY (policy_template_id)
        REFERENCES attendance_policy_template (policy_template_id),
    CONSTRAINT fk_test_attendance_policy_scope_company
        FOREIGN KEY (company_id) REFERENCES company (company_id)
);

CREATE TABLE attendance_policy_scoped_version (
    scoped_version_id VARCHAR(36) PRIMARY KEY,
    scope_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    parameters_json CLOB NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    validation_json CLOB,
    snapshot_json CLOB NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    rollback_of_scoped_version_id VARCHAR(36),
    row_version BIGINT DEFAULT 0 NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (scope_id, version_number),
    CONSTRAINT fk_test_attendance_policy_scoped_scope
        FOREIGN KEY (scope_id) REFERENCES attendance_policy_scope (scope_id),
    CONSTRAINT fk_test_attendance_policy_scoped_rollback
        FOREIGN KEY (rollback_of_scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id)
);

CREATE INDEX ix_test_attendance_policy_scoped_resolution
    ON attendance_policy_scoped_version (
        scope_id, effective_from, effective_to, version_number
    );

CREATE TABLE attendance_policy_lifecycle_event (
    lifecycle_event_id VARCHAR(36) PRIMARY KEY,
    scope_id VARCHAR(36) NOT NULL,
    scoped_version_id VARCHAR(36) NOT NULL,
    event_sequence INTEGER NOT NULL,
    action VARCHAR(32) NOT NULL,
    business_effective_from DATE NOT NULL,
    predecessor_event_id VARCHAR(36),
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    UNIQUE (scope_id, event_sequence),
    UNIQUE (predecessor_event_id),
    CONSTRAINT fk_test_attendance_policy_lifecycle_scope
        FOREIGN KEY (scope_id) REFERENCES attendance_policy_scope (scope_id),
    CONSTRAINT fk_test_attendance_policy_lifecycle_version
        FOREIGN KEY (scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id),
    CONSTRAINT fk_test_attendance_policy_lifecycle_predecessor
        FOREIGN KEY (predecessor_event_id)
        REFERENCES attendance_policy_lifecycle_event (lifecycle_event_id)
);

CREATE TABLE attendance_policy_binding_family (
    binding_family_id VARCHAR(36) PRIMARY KEY,
    attendance_group_id VARCHAR(36) NOT NULL,
    policy_kind VARCHAR(64) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (attendance_group_id, policy_kind),
    CONSTRAINT fk_test_attendance_policy_binding_family_group
        FOREIGN KEY (attendance_group_id)
        REFERENCES attendance_group (attendance_group_id)
);

CREATE TABLE attendance_policy_binding_revision (
    binding_revision_id VARCHAR(36) PRIMARY KEY,
    binding_family_id VARCHAR(36) NOT NULL,
    attendance_group_revision_id VARCHAR(36) NOT NULL,
    attendance_policy_scoped_version_id VARCHAR(36) NOT NULL,
    revision_number INTEGER NOT NULL,
    effective_from DATE NOT NULL,
    supersedes_binding_revision_id VARCHAR(36),
    snapshot_digest CHAR(64) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (binding_family_id, revision_number),
    UNIQUE (binding_family_id, effective_from),
    UNIQUE (supersedes_binding_revision_id),
    CONSTRAINT fk_test_attendance_policy_binding_family
        FOREIGN KEY (binding_family_id)
        REFERENCES attendance_policy_binding_family (binding_family_id),
    CONSTRAINT fk_test_attendance_policy_binding_group_revision
        FOREIGN KEY (attendance_group_revision_id)
        REFERENCES attendance_group_revision (attendance_group_revision_id),
    CONSTRAINT fk_test_attendance_policy_binding_version
        FOREIGN KEY (attendance_policy_scoped_version_id)
        REFERENCES attendance_policy_scoped_version (scoped_version_id),
    CONSTRAINT fk_test_attendance_policy_binding_predecessor
        FOREIGN KEY (supersedes_binding_revision_id)
        REFERENCES attendance_policy_binding_revision (binding_revision_id)
);

CREATE TABLE attendance_setup_idempotency (
    attendance_setup_idempotency_id VARCHAR(36) PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    operation_code VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    response_status INTEGER,
    response_headers_json CLOB,
    response_body_json CLOB,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    UNIQUE (
        actor_id, operation_code, resource_type, resource_id, idempotency_key
    ),
    CONSTRAINT fk_test_attendance_setup_idempotency_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_test_attendance_setup_idempotency_state
        CHECK (state IN ('STARTED', 'COMPLETED_SUCCESS')),
    CONSTRAINT ck_test_attendance_setup_idempotency_response
        CHECK (
            (state = 'STARTED'
                AND response_status IS NULL
                AND response_headers_json IS NULL
                AND response_body_json IS NULL
                AND completed_at IS NULL)
            OR (state = 'COMPLETED_SUCCESS'
                AND response_status IS NOT NULL
                AND response_headers_json IS NOT NULL
                AND response_body_json IS NOT NULL
                AND completed_at IS NOT NULL)
        )
);
CREATE INDEX ix_test_attendance_setup_idempotency_state
    ON attendance_setup_idempotency (state, created_at);

CREATE TABLE policy_template (
    template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    field_definitions_json JSON NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (template_id),
    UNIQUE KEY uq_policy_template_code (template_code),
    KEY ix_policy_template_status_updated (status, updated_at, template_id),
    CONSTRAINT fk_policy_template_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_policy_template_updated_by
        FOREIGN KEY (updated_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_policy_template_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE policy_version (
    version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parameters_json JSON NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    change_reason VARCHAR(500) NOT NULL,
    validation_json JSON NULL,
    snapshot_json JSON NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    rollback_of_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version_id),
    UNIQUE KEY uq_policy_version_number (template_id, version_number),
    KEY ix_policy_version_lifecycle
        (template_id, status, effective_from, effective_to, version_number),
    KEY ix_policy_version_rollback (rollback_of_version_id),
    CONSTRAINT fk_policy_version_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_policy_version_rollback
        FOREIGN KEY (rollback_of_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_policy_version_created_by
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_policy_version_updated_by
        FOREIGN KEY (updated_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_policy_version_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'INACTIVE')),
    CONSTRAINT ck_policy_version_number CHECK (version_number > 0),
    CONSTRAINT ck_policy_version_period
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE policy_scope_binding (
    binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_resource_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority INT UNSIGNED NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (binding_id),
    UNIQUE KEY uq_policy_scope_binding (
        version_id,
        scope_type,
        scope_resource_id,
        priority,
        effective_from
    ),
    KEY ix_policy_scope_version (version_id, priority, binding_id),
    KEY ix_policy_scope_conflict (
        scope_type,
        scope_resource_id,
        priority,
        effective_from,
        effective_to,
        version_id
    ),
    CONSTRAINT fk_policy_scope_version
        FOREIGN KEY (version_id) REFERENCES policy_version (version_id),
    CONSTRAINT ck_policy_scope_type
        CHECK (scope_type IN ('COMPANY', 'LOCATION', 'ATTENDANCE_GROUP', 'POLICY_GROUP')),
    CONSTRAINT ck_policy_scope_priority CHECK (priority <= 10000),
    CONSTRAINT ck_policy_scope_period
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE policy_publication_record (
    publication_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (publication_id),
    KEY ix_policy_publication_template_time (template_id, occurred_at, publication_id),
    KEY ix_policy_publication_version_time (version_id, occurred_at, publication_id),
    KEY ix_policy_publication_request (request_id),
    CONSTRAINT fk_policy_publication_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_policy_publication_version
        FOREIGN KEY (version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_policy_publication_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_policy_publication_action
        CHECK (action IN ('PUBLISH', 'DEACTIVATE', 'ROLLBACK')),
    CONSTRAINT ck_policy_publication_result
        CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE policy_rollback_record (
    rollback_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (rollback_id),
    KEY ix_policy_rollback_template_time (template_id, occurred_at, rollback_id),
    KEY ix_policy_rollback_source (source_version_id),
    KEY ix_policy_rollback_target (target_version_id),
    KEY ix_policy_rollback_created (created_version_id),
    KEY ix_policy_rollback_request (request_id),
    CONSTRAINT fk_policy_rollback_template
        FOREIGN KEY (template_id) REFERENCES policy_template (template_id),
    CONSTRAINT fk_policy_rollback_source
        FOREIGN KEY (source_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_policy_rollback_target
        FOREIGN KEY (target_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_policy_rollback_created
        FOREIGN KEY (created_version_id) REFERENCES policy_version (version_id),
    CONSTRAINT fk_policy_rollback_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_policy_rollback_result
        CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id,
    capability_code,
    permission_domain,
    action_code
) VALUES
    ('22000000-0000-0000-0000-000000000001', 'POLICY:READ', 'POLICY', 'READ'),
    ('22000000-0000-0000-0000-000000000002', 'POLICY:CREATE', 'POLICY', 'CREATE'),
    ('22000000-0000-0000-0000-000000000003', 'POLICY:EDIT', 'POLICY', 'EDIT'),
    ('22000000-0000-0000-0000-000000000004', 'POLICY:VALIDATE', 'POLICY', 'VALIDATE'),
    ('22000000-0000-0000-0000-000000000005', 'POLICY:SIMULATE', 'POLICY', 'SIMULATE'),
    ('22000000-0000-0000-0000-000000000006', 'POLICY:PUBLISH', 'POLICY', 'PUBLISH'),
    ('22000000-0000-0000-0000-000000000007', 'POLICY:DEACTIVATE', 'POLICY', 'DEACTIVATE'),
    ('22000000-0000-0000-0000-000000000008', 'POLICY:ROLLBACK', 'POLICY', 'ROLLBACK');

INSERT INTO auth_role_capability (role_id, capability_id) VALUES
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000002'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000003'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000004'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000005'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000006'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000007'),
    ('10000000-0000-0000-0000-000000000002', '22000000-0000-0000-0000-000000000008');

CREATE TABLE legal_entity (
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (legal_entity_id),
    UNIQUE KEY uq_legal_entity_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee (
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    employment_status VARCHAR(32) NOT NULL,
    onboard_date DATE NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (employee_id),
    KEY ix_employee_legal_entity_status (legal_entity_id, employment_status),
    CONSTRAINT fk_employee_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_identity (
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    identity_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id),
    KEY ix_organization_identity_legal_entity (legal_entity_id, identity_status),
    CONSTRAINT fk_org_identity_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_version (
    organization_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(200) NOT NULL,
    org_type VARCHAR(32) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    formal_sync_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    current_marker TINYINT GENERATED ALWAYS AS (CASE WHEN effective_to IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (organization_version_id),
    UNIQUE KEY uq_org_version_current (organization_id, current_marker),
    KEY ix_org_version_parent_period (parent_organization_id, effective_from, effective_to),
    CONSTRAINT fk_org_version_identity FOREIGN KEY (organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_org_version_parent FOREIGN KEY (parent_organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT ck_org_version_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_current_projection (
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id),
    UNIQUE KEY uq_org_current_version (current_version_id),
    KEY ix_org_projection_batch (projection_batch_id),
    CONSTRAINT fk_org_projection_identity FOREIGN KEY (organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_org_projection_version FOREIGN KEY (current_version_id) REFERENCES organization_version (organization_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_current_closure (
    ancestor_organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    descendant_organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depth SMALLINT UNSIGNED NOT NULL,
    projection_batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (ancestor_organization_id, descendant_organization_id),
    KEY ix_org_closure_descendant (descendant_organization_id, ancestor_organization_id),
    CONSTRAINT fk_org_closure_ancestor FOREIGN KEY (ancestor_organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_org_closure_descendant FOREIGN KEY (descendant_organization_id) REFERENCES organization_identity (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_source_binding (
    source_binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_org_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    confirmation_ref VARCHAR(128) NULL,
    current_marker TINYINT GENERATED ALWAYS AS (CASE WHEN effective_to IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (source_binding_id),
    UNIQUE KEY uq_org_source_binding_current (source_system, source_org_id, current_marker),
    UNIQUE KEY uq_org_source_binding_identity_current (organization_id, source_system, current_marker),
    KEY ix_org_source_binding_identity_period (organization_id, effective_from, effective_to),
    CONSTRAINT fk_org_source_binding_identity FOREIGN KEY (organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT ck_org_source_binding_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employment_assignment (
    assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payroll_plan_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cost_center_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    PRIMARY KEY (assignment_id),
    KEY ix_assignment_employee_period (employee_id, effective_from, effective_to),
    KEY ix_assignment_org_period (organization_id, effective_from, effective_to),
    CONSTRAINT fk_assignment_employee FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_assignment_organization FOREIGN KEY (organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT ck_assignment_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employee_source_binding (
    binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seeyon_person_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    seeyon_oa_code VARCHAR(128) COLLATE utf8mb4_bin NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    deli_ext_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    deli_employee_num VARCHAR(128) COLLATE utf8mb4_bin NULL,
    binding_status VARCHAR(32) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    source VARCHAR(32) NOT NULL,
    confirmation_ref VARCHAR(128) NULL,
    current_marker TINYINT GENERATED ALWAYS AS (CASE WHEN effective_to IS NULL THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (binding_id),
    UNIQUE KEY uq_employee_binding_employee_current (employee_id, current_marker),
    UNIQUE KEY uq_employee_binding_seeyon_person_current (seeyon_person_id, current_marker),
    UNIQUE KEY uq_employee_binding_oa_code_current (seeyon_oa_code, current_marker),
    UNIQUE KEY uq_employee_binding_deli_user_current (deli_user_id, current_marker),
    UNIQUE KEY uq_employee_binding_deli_ext_current (deli_ext_id, current_marker),
    UNIQUE KEY uq_employee_binding_deli_number_current (deli_employee_num, current_marker),
    KEY ix_employee_binding_employee_period (employee_id, effective_from, effective_to),
    CONSTRAINT fk_employee_binding_employee FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_employee_binding_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_principal (
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (principal_id),
    UNIQUE KEY uq_auth_principal_employee (employee_id),
    CONSTRAINT fk_auth_principal_employee FOREIGN KEY (employee_id) REFERENCES employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_role (
    role_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    permission_domain VARCHAR(32) NOT NULL,
    PRIMARY KEY (role_id),
    UNIQUE KEY uq_auth_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_capability (
    capability_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_domain VARCHAR(32) NOT NULL,
    action_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (capability_id),
    UNIQUE KEY uq_auth_capability_code (capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_role_capability (
    role_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capability_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (role_id, capability_id),
    CONSTRAINT fk_role_capability_role FOREIGN KEY (role_id) REFERENCES auth_role (role_id),
    CONSTRAINT fk_role_capability_capability FOREIGN KEY (capability_id) REFERENCES auth_capability (capability_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_data_scope (
    scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    legal_entity_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    include_descendants BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from DATETIME(6) NOT NULL,
    valid_to DATETIME(6) NULL,
    PRIMARY KEY (scope_id),
    KEY ix_auth_scope_period (valid_from, valid_to),
    CONSTRAINT fk_auth_scope_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_auth_scope_organization FOREIGN KEY (organization_id) REFERENCES organization_identity (organization_id),
    CONSTRAINT ck_auth_scope_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'LEGAL_ENTITY' AND legal_entity_id IS NOT NULL AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION' AND legal_entity_id IS NULL AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF' AND legal_entity_id IS NULL AND organization_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_principal_role_assignment (
    assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_to DATETIME(6) NULL,
    assigned_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (assignment_id),
    KEY ix_principal_role_active (principal_id, valid_from, valid_to),
    CONSTRAINT fk_principal_role_principal FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_principal_role_role FOREIGN KEY (role_id) REFERENCES auth_role (role_id),
    CONSTRAINT fk_principal_role_scope FOREIGN KEY (data_scope_id) REFERENCES auth_data_scope (scope_id),
    CONSTRAINT ck_principal_role_period CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_event (
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_id_ref VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    action_code VARCHAR(96) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id_ref VARCHAR(128) COLLATE utf8mb4_bin NULL,
    scope_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose_code VARCHAR(64) NULL,
    result_code VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NULL,
    policy_version VARCHAR(64) NULL,
    before_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    after_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    anchor_ref VARCHAR(128) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_audit_event_hash (event_hash),
    KEY ix_audit_event_occurred (occurred_at),
    KEY ix_audit_event_actor (actor_id_ref, occurred_at),
    KEY ix_audit_event_resource (resource_type, resource_id_ref, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

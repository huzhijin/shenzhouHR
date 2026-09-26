-- Versioned annual-leave and leave-type policy management foundation.

CREATE TABLE annual_leave_policy_version (
    annual_leave_policy_version_id
        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    scope_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    scope_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            COALESCE(company_id, 'SYSTEM_DEFAULT')
        ) STORED,
    based_on_version_id
        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    qualification_required BOOLEAN NOT NULL,
    qualification_months INT UNSIGNED NOT NULL,
    leap_day_rule VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tiers_json JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (annual_leave_policy_version_id),
    UNIQUE KEY uq_annual_leave_policy_version_number
        (scope_key, version_number),
    KEY ix_annual_leave_policy_resolution
        (scope_key, status, effective_from, effective_to, version_number),
    CONSTRAINT fk_annual_leave_policy_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_annual_leave_policy_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_annual_leave_policy_baseline
        FOREIGN KEY (based_on_version_id)
        REFERENCES annual_leave_policy_version
            (annual_leave_policy_version_id),
    CONSTRAINT ck_annual_leave_policy_period CHECK (
        effective_to IS NULL OR effective_to > effective_from
    ),
    CONSTRAINT ck_annual_leave_policy_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'INACTIVE')
    ),
    CONSTRAINT ck_annual_leave_policy_scope CHECK (
        (scope_type = 'SYSTEM' AND company_id IS NULL)
        OR (scope_type = 'COMPANY' AND company_id IS NOT NULL)
    ),
    CONSTRAINT ck_annual_leave_policy_leap_rule CHECK (
        leap_day_rule IN ('FEBRUARY_28', 'MARCH_1')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO annual_leave_policy_version (
    annual_leave_policy_version_id, version_number, scope_type, company_id,
    based_on_version_id, effective_from, effective_to, qualification_required,
    qualification_months, leap_day_rule, tiers_json, status,
    snapshot_digest, row_version, change_reason,
    created_by, created_at, published_at
) VALUES (
    'ANNUAL_LEAVE_DEFAULT_V1', 1, 'SYSTEM', NULL,
    NULL, '1970-01-01', NULL, TRUE, 12, 'FEBRUARY_28',
    JSON_ARRAY(
        JSON_OBJECT('code', 'FIVE_DAYS',
            'minimumMonthsInclusive', 12,
            'maximumMonthsExclusive', 120,
            'days', 5, 'hours', 40.00),
        JSON_OBJECT('code', 'TEN_DAYS',
            'minimumMonthsInclusive', 120,
            'maximumMonthsExclusive', 240,
            'days', 10, 'hours', 80.00),
        JSON_OBJECT('code', 'FIFTEEN_DAYS',
            'minimumMonthsInclusive', 240,
            'maximumMonthsExclusive', NULL,
            'days', 15, 'hours', 120.00)
    ),
    'PUBLISHED', SHA2('ANNUAL_LEAVE_DEFAULT_V1', 256), 1,
    '入职未满12个月0年假；满12个月后累计完整工龄按5/10/15天分档',
    '20000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);

CREATE TABLE annual_leave_policy_lifecycle_event (
    annual_leave_policy_lifecycle_event_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    annual_leave_policy_version_id
        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (annual_leave_policy_lifecycle_event_id),
    UNIQUE KEY uq_annual_leave_policy_lifecycle_sequence
        (annual_leave_policy_version_id, event_sequence),
    KEY ix_annual_leave_policy_lifecycle_resolution
        (annual_leave_policy_version_id, action,
         business_effective_from, recorded_at),
    CONSTRAINT fk_annual_leave_policy_lifecycle_version
        FOREIGN KEY (annual_leave_policy_version_id)
        REFERENCES annual_leave_policy_version
            (annual_leave_policy_version_id),
    CONSTRAINT fk_annual_leave_policy_lifecycle_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_annual_leave_policy_lifecycle_action CHECK (
        action IN (
            'DRAFT_CREATED', 'DRAFT_DISCARDED', 'PUBLISHED',
            'DEACTIVATE_SCHEDULED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO annual_leave_policy_lifecycle_event (
    annual_leave_policy_lifecycle_event_id,
    annual_leave_policy_version_id, event_sequence, action,
    business_effective_from, reason, actor_id, recorded_at
) VALUES (
    UUID(), 'ANNUAL_LEAVE_DEFAULT_V1', 1, 'PUBLISHED',
    '1970-01-01', 'V25 系统年假初始默认发布基线',
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
);

CREATE TABLE annual_leave_policy_idempotency_record (
    annual_leave_policy_idempotency_record_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    annual_leave_policy_version_id
        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (annual_leave_policy_idempotency_record_id),
    UNIQUE KEY uq_annual_leave_policy_idempotency
        (principal_id, idempotency_key),
    CONSTRAINT fk_annual_leave_policy_idempotency_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_annual_leave_policy_idempotency_version
        FOREIGN KEY (annual_leave_policy_version_id)
        REFERENCES annual_leave_policy_version
            (annual_leave_policy_version_id),
    CONSTRAINT ck_annual_leave_policy_idempotency_operation CHECK (
        operation IN ('CREATE_DRAFT', 'PUBLISH', 'DISCARD_DRAFT')
    ),
    CONSTRAINT ck_annual_leave_policy_idempotency_digest CHECK (
        request_digest REGEXP '^[0-9a-f]{64}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '2d000000-0000-0000-0000-000000000007',
    'LEAVE_POLICY:MANAGE', 'LEAVE', 'MANAGE_POLICY'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'LEAVE_POLICY:MANAGE'
WHERE role.role_code = 'HR_ADMIN';

ALTER TABLE leave_policy_revision
    DROP INDEX uq_leave_policy_revision_effective,
    ADD COLUMN scope_type
        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'SYSTEM' AFTER leave_type_id,
    ADD COLUMN company_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER scope_type,
    ADD COLUMN scope_key
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            COALESCE(company_id, 'SYSTEM_DEFAULT')
        ) STORED AFTER company_id,
    ADD COLUMN based_on_revision_id
        VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER scope_key,
    ADD COLUMN version_number INT UNSIGNED NOT NULL DEFAULT 1
        AFTER based_on_revision_id,
    ADD COLUMN status
        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'PUBLISHED' AFTER configuration_note,
    ADD COLUMN snapshot_digest
        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER status,
    ADD COLUMN row_version BIGINT UNSIGNED NOT NULL DEFAULT 0
        AFTER snapshot_digest,
    ADD COLUMN change_reason VARCHAR(500) NOT NULL
        DEFAULT 'V17 safe-launch leave policy baseline'
        AFTER row_version,
    ADD COLUMN created_by
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER change_reason;

UPDATE leave_policy_revision
SET snapshot_digest = SHA2(CONCAT_WS('|',
        leave_policy_revision_id, calendar_basis,
        minimum_amount, step_amount,
        paid_attendance_credit, balance_controlled,
        configuration_note), 256),
    change_reason = configuration_note,
    created_by = '20000000-0000-0000-0000-000000000001';

ALTER TABLE leave_policy_revision
    MODIFY COLUMN snapshot_digest
        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN created_by
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uq_leave_policy_revision_number
        (leave_type_id, scope_key, version_number),
    ADD KEY ix_leave_policy_revision_resolution
        (leave_type_id, scope_key, status,
         effective_from, effective_to, version_number),
    ADD CONSTRAINT fk_leave_policy_revision_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    ADD CONSTRAINT fk_leave_policy_revision_baseline
        FOREIGN KEY (based_on_revision_id)
        REFERENCES leave_policy_revision (leave_policy_revision_id),
    ADD CONSTRAINT fk_leave_policy_revision_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    ADD CONSTRAINT ck_leave_policy_revision_scope CHECK (
        (scope_type = 'SYSTEM' AND company_id IS NULL)
        OR (scope_type = 'COMPANY' AND company_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_leave_policy_revision_status CHECK (
        status IN ('PUBLISHED', 'INACTIVE')
    ),
    ADD CONSTRAINT ck_leave_policy_revision_digest CHECK (
        snapshot_digest REGEXP '^[0-9a-f]{64}$'
    );

CREATE TABLE leave_policy_lifecycle_event (
    leave_policy_lifecycle_event_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_policy_revision_id
        VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence INT UNSIGNED NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_effective_from DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_policy_lifecycle_event_id),
    UNIQUE KEY uq_leave_policy_lifecycle_revision_sequence
        (leave_policy_revision_id, event_sequence),
    CONSTRAINT fk_leave_policy_lifecycle_revision
        FOREIGN KEY (leave_policy_revision_id)
        REFERENCES leave_policy_revision (leave_policy_revision_id),
    CONSTRAINT fk_leave_policy_lifecycle_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_leave_policy_lifecycle_action CHECK (
        action IN ('PUBLISHED', 'DEACTIVATE_SCHEDULED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO leave_policy_lifecycle_event (
    leave_policy_lifecycle_event_id, leave_policy_revision_id,
    event_sequence, action, business_effective_from, reason,
    actor_id, recorded_at
)
SELECT UUID(), revision.leave_policy_revision_id, 1, 'PUBLISHED',
       revision.effective_from, 'V25 既有请假规则发布基线',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM leave_policy_revision revision;

CREATE TABLE leave_policy_idempotency_record (
    leave_policy_idempotency_record_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_policy_revision_id
        VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_policy_idempotency_record_id),
    UNIQUE KEY uq_leave_policy_idempotency
        (principal_id, idempotency_key),
    CONSTRAINT fk_leave_policy_idempotency_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_leave_policy_idempotency_revision
        FOREIGN KEY (leave_policy_revision_id)
        REFERENCES leave_policy_revision (leave_policy_revision_id),
    CONSTRAINT ck_leave_policy_idempotency_operation CHECK (
        operation = 'CREATE_PUBLISHED_VERSION'
    ),
    CONSTRAINT ck_leave_policy_idempotency_digest CHECK (
        request_digest REGEXP '^[0-9a-f]{64}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

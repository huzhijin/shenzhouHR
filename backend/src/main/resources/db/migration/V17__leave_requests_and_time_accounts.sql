-- W6 production leave/time-account vertical slice.
-- V16 is reserved for the attendance-rate projection; V18/V19 are reserved
-- for the parallel OA and workbench slices.

-- Composite reference keys make every W6 employment reference prove the
-- employee/company identity instead of accepting an arbitrary UUID that only
-- happens to exist in another employment row.
ALTER TABLE employee
    ADD UNIQUE KEY uq_employee_company_ref (employee_id, company_id);

ALTER TABLE organization_identity
    ADD UNIQUE KEY uq_organization_company_ref (organization_id, company_id);

CREATE TABLE employment_period_identity (
    employment_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (employment_period_id),
    UNIQUE KEY uq_employment_period_identity_reference
        (employment_period_id, employee_id, company_id),
    KEY ix_employment_period_identity_employee
        (employee_id, company_id, employment_period_id),
    CONSTRAINT fk_employment_period_identity_employee_company
        FOREIGN KEY (employee_id, company_id)
        REFERENCES employee (employee_id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- DISTINCT intentionally fails on the stable-period primary key if legacy
-- data maps one period to more than one employee/company.
INSERT INTO employment_period_identity (
    employment_period_id, employee_id, company_id, created_at
)
SELECT DISTINCT assignment.employment_period_id,
       assignment.employee_id,
       employee.company_id,
       CURRENT_TIMESTAMP(6)
FROM employment_assignment assignment
JOIN employee ON employee.employee_id = assignment.employee_id;

CREATE TABLE leave_type (
    leave_type_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_type_id),
    UNIQUE KEY uq_leave_type_code (leave_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_policy_revision (
    leave_policy_revision_id VARCHAR(96)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_type_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    calendar_basis VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    minimum_amount DECIMAL(12,4) NOT NULL,
    step_amount DECIMAL(12,4) NOT NULL,
    paid_attendance_credit BOOLEAN NOT NULL,
    balance_controlled BOOLEAN NOT NULL,
    configuration_note VARCHAR(500) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_policy_revision_id),
    UNIQUE KEY uq_leave_policy_revision_effective
        (leave_type_id, effective_from),
    KEY ix_leave_policy_revision_period
        (leave_type_id, effective_from, effective_to),
    CONSTRAINT fk_leave_policy_revision_type
        FOREIGN KEY (leave_type_id) REFERENCES leave_type (leave_type_id),
    CONSTRAINT ck_leave_policy_revision_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_leave_policy_revision_basis
        CHECK (calendar_basis IN ('WORKDAY', 'CALENDAR_DAY', 'HOUR')),
    CONSTRAINT ck_leave_policy_revision_amount
        CHECK (minimum_amount > 0 AND step_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_request (
    leave_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_type_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_policy_revision_id VARCHAR(96)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    requested_amount DECIMAL(12,4) NOT NULL,
    requested_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_hours DECIMAL(12,2) NULL,
    actual_used_hours DECIMAL(12,2) NULL,
    returned_hours DECIMAL(12,2) NULL,
    day_part VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    bereavement_relationship VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NOT NULL,
    current_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    external_approval_state VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    calculation_eligible BOOLEAN NOT NULL,
    balance_application_status VARCHAR(48)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NOT_APPLICABLE',
    balance_application_reason VARCHAR(500) NOT NULL DEFAULT '',
    balance_applied_hours DECIMAL(12,2) NOT NULL DEFAULT 0,
    balance_applied_account_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    synced_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_request_id),
    UNIQUE KEY uq_leave_request_external
        (source_system, external_request_id),
    KEY ix_leave_request_self (employee_id, created_at, leave_request_id),
    KEY ix_leave_request_admin
        (company_id, organization_id, current_status, created_at),
    KEY ix_leave_request_balance_account (balance_applied_account_id),
    CONSTRAINT fk_leave_request_employee_company
        FOREIGN KEY (employee_id, company_id)
        REFERENCES employee (employee_id, company_id),
    CONSTRAINT fk_leave_request_employment_identity
        FOREIGN KEY (employment_period_id, employee_id, company_id)
        REFERENCES employment_period_identity
            (employment_period_id, employee_id, company_id),
    CONSTRAINT fk_leave_request_organization_company
        FOREIGN KEY (organization_id, company_id)
        REFERENCES organization_identity (organization_id, company_id),
    CONSTRAINT fk_leave_request_type
        FOREIGN KEY (leave_type_id) REFERENCES leave_type (leave_type_id),
    CONSTRAINT fk_leave_request_policy_revision
        FOREIGN KEY (leave_policy_revision_id)
        REFERENCES leave_policy_revision (leave_policy_revision_id),
    CONSTRAINT ck_leave_request_period CHECK (end_at > start_at),
    CONSTRAINT ck_leave_request_amount CHECK (
        requested_amount > 0
        AND (requested_hours IS NULL OR requested_hours > 0)
        AND (actual_used_hours IS NULL OR actual_used_hours >= 0)
        AND (returned_hours IS NULL OR returned_hours >= 0)
        AND balance_applied_hours >= 0
    ),
    CONSTRAINT ck_leave_request_unit CHECK (requested_unit IN ('DAY', 'HOUR')),
    CONSTRAINT ck_leave_request_day_part CHECK (
        day_part IS NULL OR day_part IN ('MORNING', 'AFTERNOON', 'FULL_DAY')
    ),
    CONSTRAINT ck_leave_request_status CHECK (
        current_status IN (
            'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
            'CANCELLED', 'RETURNED'
        )
    ),
    CONSTRAINT ck_leave_request_calculation CHECK (
        calculation_eligible = (current_status = 'APPROVED')
        OR calculation_eligible = FALSE
    ),
    CONSTRAINT ck_leave_request_balance_application CHECK (
        balance_application_status IN (
            'NOT_APPLICABLE', 'PENDING_NOT_CALCULATED',
            'CALCULATION_NOT_RECOGNIZED', 'HOURS_UNRESOLVED',
            'RETURN_HOURS_UNRESOLVED', 'CROSS_YEAR_UNRESOLVED',
            'ACCOUNT_NOT_MATERIALIZED', 'INSUFFICIENT_BALANCE',
            'BALANCE_IDENTITY_CHANGE_UNRESOLVED',
            'APPLIED', 'REVERSED', 'RETURN_APPLIED'
        )
    ),
    CONSTRAINT ck_leave_request_digest
        CHECK (source_payload_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_request_status_event (
    leave_request_status_event_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    source_system VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    from_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_actor_ref VARCHAR(128) COLLATE utf8mb4_bin NULL,
    change_reason VARCHAR(500) NOT NULL,
    source_payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_request_status_event_id),
    UNIQUE KEY uq_leave_request_event_sequence (leave_request_id, sequence_no),
    UNIQUE KEY uq_leave_request_event_external
        (source_system, source_event_id),
    KEY ix_leave_request_event_history (leave_request_id, occurred_at),
    CONSTRAINT fk_leave_request_event_request
        FOREIGN KEY (leave_request_id) REFERENCES leave_request (leave_request_id),
    CONSTRAINT ck_leave_request_event_from_status CHECK (
        from_status IS NULL OR from_status IN (
            'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
            'CANCELLED', 'RETURNED'
        )
    ),
    CONSTRAINT ck_leave_request_event_to_status CHECK (
        to_status IN (
            'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
            'CANCELLED', 'RETURNED'
        )
    ),
    CONSTRAINT ck_leave_request_event_digest
        CHECK (source_payload_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE time_account (
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_year SMALLINT UNSIGNED NOT NULL,
    balance_hours DECIMAL(12,2) NOT NULL DEFAULT 0,
    policy_version_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (time_account_id),
    UNIQUE KEY uq_time_account_identity_reference
        (time_account_id, employee_id, employment_period_id, company_id),
    UNIQUE KEY uq_time_account_employee_year
        (employee_id, employment_period_id, account_type, account_year),
    KEY ix_time_account_company_year (company_id, account_year, account_type),
    CONSTRAINT fk_time_account_employee_company
        FOREIGN KEY (employee_id, company_id)
        REFERENCES employee (employee_id, company_id),
    CONSTRAINT fk_time_account_employment_identity
        FOREIGN KEY (employment_period_id, employee_id, company_id)
        REFERENCES employment_period_identity
            (employment_period_id, employee_id, company_id),
    CONSTRAINT ck_time_account_type
        CHECK (account_type IN ('ANNUAL_LEAVE', 'TIME_OFF', 'WORK_HOURS')),
    CONSTRAINT ck_time_account_balance CHECK (balance_hours >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE time_account_ledger_entry (
    time_account_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    entry_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_hours DECIMAL(12,2) NOT NULL,
    source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    effective_from DATE NOT NULL,
    expires_on DATE NULL,
    policy_version_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_version_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    close_snapshot_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reversal_of_entry_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (time_account_ledger_entry_id),
    UNIQUE KEY uq_time_account_ledger_sequence (time_account_id, sequence_no),
    UNIQUE KEY uq_time_account_ledger_request (time_account_id, request_id),
    UNIQUE KEY uq_time_account_ledger_reversal (reversal_of_entry_id),
    KEY ix_time_account_ledger_source (source_type, source_id),
    CONSTRAINT fk_time_account_ledger_account
        FOREIGN KEY (time_account_id) REFERENCES time_account (time_account_id),
    CONSTRAINT fk_time_account_ledger_reversal
        FOREIGN KEY (reversal_of_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT fk_time_account_ledger_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_time_account_ledger_amount CHECK (amount_hours <> 0),
    CONSTRAINT ck_time_account_ledger_type CHECK (
        entry_type IN (
            'OPENING', 'GRANT', 'USE', 'ADJUSTMENT', 'REVERSAL',
            'EXPIRY', 'OVERTIME_CREDIT', 'RETURN'
        )
    ),
    CONSTRAINT ck_time_account_ledger_reversal_shape CHECK (
        (entry_type = 'REVERSAL' AND reversal_of_entry_id IS NOT NULL)
        OR (entry_type <> 'REVERSAL' AND reversal_of_entry_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE leave_request
    ADD CONSTRAINT fk_leave_request_balance_applied_account
        FOREIGN KEY (
            balance_applied_account_id, employee_id,
            employment_period_id, company_id
        ) REFERENCES time_account (
            time_account_id, employee_id, employment_period_id, company_id
        );

CREATE TABLE annual_leave_entitlement_projection (
    annual_leave_entitlement_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entitlement_year SMALLINT UNSIGNED NOT NULL,
    business_date DATE NOT NULL,
    qualified BOOLEAN NOT NULL,
    entitlement_days DECIMAL(8,2) NOT NULL,
    entitlement_hours DECIMAL(10,2) NOT NULL,
    balance_hours DECIMAL(10,2) NOT NULL,
    current_employment_completed_months INT UNSIGNED NOT NULL,
    prior_service_months INT UNSIGNED NOT NULL,
    cumulative_service_months INT UNSIGNED NOT NULL,
    tier_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_version_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_service_resolution_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_service_read BOOLEAN NOT NULL,
    prior_service_raw_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    prior_service_raw_value INT UNSIGNED NULL,
    source_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (annual_leave_entitlement_projection_id),
    UNIQUE KEY uq_annual_entitlement_employee_year
        (employee_id, employment_period_id, entitlement_year),
    KEY ix_annual_entitlement_scope
        (company_id, organization_id, entitlement_year),
    CONSTRAINT fk_annual_entitlement_employee_company
        FOREIGN KEY (employee_id, company_id)
        REFERENCES employee (employee_id, company_id),
    CONSTRAINT fk_annual_entitlement_employment_identity
        FOREIGN KEY (employment_period_id, employee_id, company_id)
        REFERENCES employment_period_identity
            (employment_period_id, employee_id, company_id),
    CONSTRAINT fk_annual_entitlement_organization_company
        FOREIGN KEY (organization_id, company_id)
        REFERENCES organization_identity (organization_id, company_id),
    CONSTRAINT ck_annual_entitlement_amounts CHECK (
        entitlement_days >= 0 AND entitlement_hours >= 0 AND balance_hours >= 0
    ),
    CONSTRAINT ck_annual_entitlement_digest
        CHECK (source_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_annual_entitlement_prior_shape CHECK (
        (prior_service_read = TRUE
         AND prior_service_raw_unit IS NOT NULL
         AND prior_service_raw_value IS NOT NULL)
        OR (prior_service_read = FALSE
            AND prior_service_raw_unit IS NULL
            AND prior_service_raw_value IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE leave_account_materialization_record (
    leave_account_materialization_record_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entitlement_year SMALLINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    employee_count INT UNSIGNED NOT NULL,
    annual_grant_entries INT UNSIGNED NOT NULL,
    balance_entries INT UNSIGNED NOT NULL,
    unresolved_request_count INT UNSIGNED NOT NULL,
    materialized_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_account_materialization_record_id),
    UNIQUE KEY uq_leave_account_materialization_idempotency
        (principal_id, idempotency_key),
    KEY ix_leave_account_materialization_year
        (entitlement_year, materialized_at),
    CONSTRAINT fk_leave_account_materialization_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_leave_account_materialization_digest
        CHECK (request_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO leave_type (
    leave_type_id, leave_code, display_name, active, created_at
) VALUES
    ('27000000-0000-0000-0000-000000000001', 'ANNUAL_LEAVE', '年假', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000002', 'BEREAVEMENT_LEAVE', '丧假', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000003', 'NURSING_LEAVE', '护理假', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000004', 'SICK_LEAVE', '病假', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000005', 'PERSONAL_LEAVE', '事假', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000006', 'BREASTFEEDING_TIME', '哺乳时间', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000007', 'PRENATAL_EXAM_TIME', '产检时间', TRUE, CURRENT_TIMESTAMP(6)),
    ('27000000-0000-0000-0000-000000000008', 'TIME_OFF', '调休', TRUE, CURRENT_TIMESTAMP(6));

INSERT INTO leave_policy_revision (
    leave_policy_revision_id, leave_type_id, effective_from, effective_to,
    calendar_basis, minimum_amount, step_amount, paid_attendance_credit,
    balance_controlled, configuration_note, published_at
) VALUES
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:ANNUAL_LEAVE', '27000000-0000-0000-0000-000000000001', '2026-01-01', NULL, 'WORKDAY', 0.5, 0.5, TRUE, TRUE, 'FAST_LAUNCH_CONFIGURABLE_DEFAULT_TODO_HR_CONFIRMATION', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:BEREAVEMENT_LEAVE', '27000000-0000-0000-0000-000000000002', '2026-01-01', NULL, 'WORKDAY', 1, 1, TRUE, FALSE, '关系选项：配偶/子女/父母3天，兄弟姐妹/祖父母/外祖父母1天；后续变更新增版本', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:NURSING_LEAVE', '27000000-0000-0000-0000-000000000003', '2026-01-01', NULL, 'CALENDAR_DAY', 15, 1, TRUE, FALSE, '15个自然日；日历细则待HR确认时新增版本', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:SICK_LEAVE', '27000000-0000-0000-0000-000000000004', '2026-01-01', NULL, 'WORKDAY', 0.5, 0.5, FALSE, FALSE, '最小0.5个实际班次工作日', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:PERSONAL_LEAVE', '27000000-0000-0000-0000-000000000005', '2026-01-01', NULL, 'HOUR', 0.5, 0.5, FALSE, FALSE, '最小0.5小时', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:BREASTFEEDING_TIME', '27000000-0000-0000-0000-000000000006', '2026-01-01', NULL, 'HOUR', 0.5, 0.5, TRUE, FALSE, '独立工作时间，具体额度待HR确认时新增版本', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:PRENATAL_EXAM_TIME', '27000000-0000-0000-0000-000000000007', '2026-01-01', NULL, 'HOUR', 0.5, 0.5, TRUE, FALSE, '独立工作时间，具体额度待HR确认时新增版本', CURRENT_TIMESTAMP(6)),
    ('LEAVE_POLICY_2026_FAST_LAUNCH_V1:TIME_OFF', '27000000-0000-0000-0000-000000000008', '2026-01-01', NULL, 'HOUR', 0.5, 0.5, TRUE, TRUE, '调休余额按小时控制；正常加班转调休倍率及OA枚举待签署映射，义务加班永不生成调休额度', CURRENT_TIMESTAMP(6));

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    ('2d000000-0000-0000-0000-000000000003', 'LEAVE_MANAGEMENT:READ', 'LEAVE', 'READ'),
    ('2d000000-0000-0000-0000-000000000005', 'LEAVE_MANAGEMENT:EXPORT', 'LEAVE', 'EXPORT'),
    ('2d000000-0000-0000-0000-000000000006', 'LEAVE_ACCOUNT:MATERIALIZE', 'LEAVE', 'MATERIALIZE');

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'LEAVE_MANAGEMENT:READ', 'LEAVE_MANAGEMENT:EXPORT',
      'LEAVE_ACCOUNT:MATERIALIZE'
  )
WHERE role.role_code IN ('HR_ADMIN', 'DEPARTMENT_HEAD');

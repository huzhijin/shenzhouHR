INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    (
        '2a000000-0000-0000-0000-000000000010',
        'ATTENDANCE_PERIOD:READ',
        'ATTENDANCE_PERIOD',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000011',
        'ATTENDANCE_PERIOD:PRECLOSE',
        'ATTENDANCE_PERIOD',
        'PRECLOSE'
    ),
    (
        '2a000000-0000-0000-0000-000000000012',
        'ATTENDANCE_PERIOD:CLOSE',
        'ATTENDANCE_PERIOD',
        'CLOSE'
    ),
    (
        '2a000000-0000-0000-0000-000000000013',
        'ATTENDANCE_PERIOD:REOPEN',
        'ATTENDANCE_PERIOD',
        'REOPEN'
    ),
    (
        '2a000000-0000-0000-0000-000000000014',
        'ATTENDANCE_PERIOD:SCHEDULE_CLOSE',
        'ATTENDANCE_PERIOD',
        'SCHEDULE_CLOSE'
    ),
    (
        '2a000000-0000-0000-0000-000000000015',
        'ATTENDANCE_PERIOD:CREATE',
        'ATTENDANCE_PERIOD',
        'CREATE'
    );

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_PERIOD:READ',
      'ATTENDANCE_PERIOD:CREATE',
      'ATTENDANCE_PERIOD:PRECLOSE',
      'ATTENDANCE_PERIOD:CLOSE'
  )
WHERE role.role_code IN ('SYSTEM_ADMIN', 'HR_ADMIN');

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_PERIOD:REOPEN',
      'ATTENDANCE_PERIOD:SCHEDULE_CLOSE'
  )
WHERE role.role_code = 'SYSTEM_ADMIN';

CREATE TABLE attendance_period (
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE NOT NULL,
    period_end_exclusive DATE NOT NULL,
    current_version BIGINT UNSIGNED NOT NULL,
    current_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_token_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_id),
    UNIQUE KEY uq_attendance_period_month
        (company_id, period_start),
    UNIQUE KEY uq_attendance_period_scope
        (attendance_period_id, company_id),
    KEY ix_attendance_period_list
        (company_id, period_start, attendance_period_id),
    KEY ix_attendance_period_state
        (company_id, current_state, period_start),
    CONSTRAINT fk_attendance_period_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_attendance_period_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_month
        CHECK (
            DAYOFMONTH(period_start) = 1
            AND period_end_exclusive = DATE_ADD(period_start, INTERVAL 1 MONTH)
        ),
    CONSTRAINT ck_attendance_period_state
        CHECK (
            current_state IN (
                'OPEN', 'FROZEN_FOR_CLOSE', 'CLOSED', 'REOPENED'
            )
        ),
    CONSTRAINT ck_attendance_period_token
        CHECK (current_token_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attendance_period_snapshot_state
        CHECK (
            (current_state IN ('OPEN', 'FROZEN_FOR_CLOSE')
                AND current_close_snapshot_id IS NULL)
            OR (current_state IN ('CLOSED', 'REOPENED')
                AND current_close_snapshot_id IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_version (
    attendance_period_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number BIGINT UNSIGNED NOT NULL,
    period_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_token_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_version_id),
    UNIQUE KEY uq_attendance_period_version
        (attendance_period_id, version_number),
    UNIQUE KEY uq_attendance_period_version_scope
        (attendance_period_version_id, attendance_period_id),
    KEY ix_attendance_period_version_history
        (company_id, attendance_period_id, version_number),
    CONSTRAINT fk_attendance_period_version_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_version_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_version_state
        CHECK (
            period_state IN (
                'OPEN', 'FROZEN_FOR_CLOSE', 'CLOSED', 'REOPENED'
            )
        ),
    CONSTRAINT ck_attendance_period_version_token
        CHECK (period_token_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_transition (
    attendance_period_transition_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_version BIGINT UNSIGNED NOT NULL,
    to_version BIGINT UNSIGNED NOT NULL,
    from_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transition_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_transition_id),
    UNIQUE KEY uq_attendance_period_transition_version
        (attendance_period_id, to_version),
    KEY ix_attendance_period_transition_history
        (company_id, attendance_period_id, occurred_at,
         attendance_period_transition_id),
    CONSTRAINT fk_attendance_period_transition_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_transition_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_transition_version
        CHECK (to_version > from_version),
    CONSTRAINT ck_attendance_period_transition_state
        CHECK (
            from_state IN (
                'OPEN', 'FROZEN_FOR_CLOSE', 'CLOSED', 'REOPENED'
            )
            AND to_state IN (
                'OPEN', 'FROZEN_FOR_CLOSE', 'CLOSED', 'REOPENED'
            )
        ),
    CONSTRAINT ck_attendance_period_transition_type
        CHECK (
            transition_type IN (
                'FROZE_FOR_CLOSE', 'RELEASED_CLOSE_FREEZE',
                'CLOSED', 'REOPENED'
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_precheck (
    attendance_period_precheck_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_period_version BIGINT UNSIGNED NOT NULL,
    expected_period_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_period_token_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    precheck_token_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dependency_snapshot_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dependency_digests_json JSON NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_employee_date_count BIGINT UNSIGNED NOT NULL,
    successful_calculation_date_count BIGINT UNSIGNED NOT NULL,
    unresolved_blocker_count BIGINT UNSIGNED NOT NULL,
    running_source_or_import_count BIGINT UNSIGNED NOT NULL,
    running_recalculation_count BIGINT UNSIGNED NOT NULL,
    source_fresh BOOLEAN NOT NULL,
    control_totals_reconcile BOOLEAN NOT NULL,
    closable BOOLEAN NOT NULL,
    checked_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_precheck_id),
    UNIQUE KEY uq_attendance_period_precheck_token
        (precheck_token_digest),
    KEY ix_attendance_period_precheck_latest
        (company_id, attendance_period_id, checked_at,
         attendance_period_precheck_id),
    CONSTRAINT fk_attendance_period_precheck_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_precheck_projection
        FOREIGN KEY (attendance_report_projection_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id),
    CONSTRAINT fk_attendance_period_precheck_actor
        FOREIGN KEY (checked_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_precheck_state
        CHECK (expected_period_state IN ('OPEN', 'REOPENED')),
    CONSTRAINT ck_attendance_period_precheck_digest
        CHECK (
            expected_period_token_digest REGEXP '^[0-9a-f]{64}$'
            AND precheck_token_digest REGEXP '^[0-9a-f]{64}$'
            AND dependency_snapshot_digest REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_attendance_period_precheck_expiry
        CHECK (expires_at > checked_at),
    CONSTRAINT ck_attendance_period_precheck_closable
        CHECK (
            closable = FALSE
            OR (
                expected_employee_date_count =
                    successful_calculation_date_count
                AND unresolved_blocker_count = 0
                AND running_source_or_import_count = 0
                AND running_recalculation_count = 0
                AND source_fresh = TRUE
                AND control_totals_reconcile = TRUE
                AND attendance_report_projection_id IS NOT NULL
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_precheck_blocker (
    attendance_period_precheck_blocker_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_precheck_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    blocker_code VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    blocker_count BIGINT UNSIGNED NOT NULL,
    dependency_digest VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_precheck_blocker_id),
    UNIQUE KEY uq_attendance_precheck_blocker
        (attendance_period_precheck_id, blocker_code),
    CONSTRAINT fk_attendance_precheck_blocker_precheck
        FOREIGN KEY (attendance_period_precheck_id)
        REFERENCES attendance_period_precheck
            (attendance_period_precheck_id),
    CONSTRAINT ck_attendance_precheck_blocker_count
        CHECK (blocker_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_close_snapshot (
    attendance_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    closed_period_version BIGINT UNSIGNED NOT NULL,
    attendance_period_precheck_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_count BIGINT UNSIGNED NOT NULL,
    member_set_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dependency_digests_json JSON NOT NULL,
    snapshot_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_close_snapshot_id),
    UNIQUE KEY uq_attendance_close_snapshot_version
        (attendance_period_id, closed_period_version),
    UNIQUE KEY uq_attendance_close_snapshot_precheck
        (attendance_period_precheck_id),
    KEY ix_attendance_close_snapshot_history
        (company_id, attendance_period_id, closed_at,
         attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_close_snapshot_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_close_snapshot_precheck
        FOREIGN KEY (attendance_period_precheck_id)
        REFERENCES attendance_period_precheck
            (attendance_period_precheck_id),
    CONSTRAINT fk_attendance_close_snapshot_projection
        FOREIGN KEY (attendance_report_projection_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id),
    CONSTRAINT fk_attendance_close_snapshot_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_close_snapshot_digest
        CHECK (
            member_set_digest REGEXP '^[0-9a-f]{64}$'
            AND snapshot_digest REGEXP '^[0-9a-f]{64}$'
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_close_snapshot_member (
    attendance_close_snapshot_member_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_daily_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    calculation_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scheduled_minutes BIGINT UNSIGNED NOT NULL,
    confirmed_scheduled_work_minutes BIGINT UNSIGNED NOT NULL,
    extended_presence_minutes BIGINT UNSIGNED NOT NULL,
    recognized_overtime_minutes BIGINT UNSIGNED NOT NULL,
    leave_or_time_off_minutes BIGINT UNSIGNED NOT NULL,
    absence_minutes BIGINT UNSIGNED NOT NULL,
    actual_work_minutes BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_close_snapshot_member_id),
    UNIQUE KEY uq_attendance_close_snapshot_member
        (attendance_close_snapshot_id, employee_id, business_date),
    UNIQUE KEY uq_attendance_close_snapshot_daily_fact
        (attendance_close_snapshot_id, attendance_report_daily_fact_id),
    KEY ix_attendance_close_snapshot_member_employee
        (employee_id, business_date, attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_close_snapshot_member_snapshot
        FOREIGN KEY (attendance_close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_close_snapshot_member_fact
        FOREIGN KEY (attendance_report_daily_fact_id)
        REFERENCES attendance_report_daily_fact
            (attendance_report_daily_fact_id),
    CONSTRAINT fk_attendance_close_snapshot_member_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_attendance_close_snapshot_member_actual
        CHECK (
            actual_work_minutes =
                confirmed_scheduled_work_minutes
                + recognized_overtime_minutes
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_close_control_total (
    attendance_close_control_total_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_key VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    member_count BIGINT UNSIGNED NOT NULL,
    scheduled_minutes BIGINT UNSIGNED NOT NULL,
    confirmed_scheduled_work_minutes BIGINT UNSIGNED NOT NULL,
    extended_presence_minutes BIGINT UNSIGNED NOT NULL,
    recognized_overtime_minutes BIGINT UNSIGNED NOT NULL,
    leave_or_time_off_minutes BIGINT UNSIGNED NOT NULL,
    absence_minutes BIGINT UNSIGNED NOT NULL,
    actual_work_minutes BIGINT UNSIGNED NOT NULL,
    control_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_close_control_total_id),
    UNIQUE KEY uq_attendance_close_control_scope
        (attendance_close_snapshot_id, scope_key),
    CONSTRAINT fk_attendance_close_control_snapshot
        FOREIGN KEY (attendance_close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_close_control_org
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT ck_attendance_close_control_scope
        CHECK (
            (scope_key = 'GLOBAL' AND organization_id IS NULL)
            OR (scope_key LIKE 'ORGANIZATION:%'
                AND organization_id IS NOT NULL)
        ),
    CONSTRAINT ck_attendance_close_control_actual
        CHECK (
            actual_work_minutes =
                confirmed_scheduled_work_minutes
                + recognized_overtime_minutes
        ),
    CONSTRAINT ck_attendance_close_control_digest
        CHECK (control_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_reopen_approval (
    attendance_period_reopen_approval_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_reference VARCHAR(191)
        COLLATE utf8mb4_bin NOT NULL,
    from_version BIGINT UNSIGNED NOT NULL,
    reopened_version BIGINT UNSIGNED NOT NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reopened_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_reopen_approval_id),
    UNIQUE KEY uq_attendance_period_reopen_version
        (attendance_period_id, reopened_version),
    KEY ix_attendance_period_reopen_history
        (company_id, attendance_period_id, reopened_at,
         attendance_period_reopen_approval_id),
    CONSTRAINT fk_attendance_period_reopen_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_reopen_snapshot
        FOREIGN KEY (attendance_close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_period_reopen_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_reopen_version
        CHECK (reopened_version > from_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_post_close_difference (
    attendance_post_close_difference_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_close_snapshot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    closed_calculation_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    reopened_calculation_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    difference_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    closed_result_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    reopened_result_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    difference_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_post_close_difference_id),
    UNIQUE KEY uq_attendance_post_close_difference
        (attendance_close_snapshot_id, employee_id, business_date,
         difference_digest),
    KEY ix_attendance_post_close_difference_list
        (company_id, attendance_period_id, business_date, employee_id),
    CONSTRAINT fk_attendance_post_close_difference_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_post_close_difference_snapshot
        FOREIGN KEY (attendance_close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id),
    CONSTRAINT fk_attendance_post_close_difference_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_attendance_post_close_difference_type
        CHECK (
            difference_type IN ('CHANGED', 'REMOVED', 'ADDED')
        ),
    CONSTRAINT ck_attendance_post_close_difference_sides
        CHECK (
            (difference_type = 'ADDED'
                AND closed_calculation_version_id IS NULL
                AND closed_result_digest IS NULL
                AND reopened_calculation_version_id IS NOT NULL
                AND reopened_result_digest IS NOT NULL)
            OR (difference_type = 'REMOVED'
                AND closed_calculation_version_id IS NOT NULL
                AND closed_result_digest IS NOT NULL
                AND reopened_calculation_version_id IS NULL
                AND reopened_result_digest IS NULL)
            OR (difference_type = 'CHANGED'
                AND closed_calculation_version_id IS NOT NULL
                AND closed_result_digest IS NOT NULL
                AND reopened_calculation_version_id IS NOT NULL
                AND reopened_result_digest IS NOT NULL)
        ),
    CONSTRAINT ck_attendance_post_close_difference_digest
        CHECK (
            (closed_result_digest IS NULL
                OR closed_result_digest REGEXP '^[0-9a-f]{64}$')
            AND (
                reopened_result_digest IS NULL
                OR reopened_result_digest REGEXP '^[0-9a-f]{64}$'
            )
            AND difference_digest REGEXP '^[0-9a-f]{64}$'
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_mutation (
    attendance_period_mutation_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_code VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT UNSIGNED NULL,
    response_body_json JSON NULL,
    resource_version BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (attendance_period_mutation_id),
    UNIQUE KEY uq_attendance_period_mutation_idempotency
        (actor_id, operation_code, attendance_period_id,
         idempotency_key),
    KEY ix_attendance_period_mutation_resource
        (company_id, attendance_period_id, created_at,
         attendance_period_mutation_id),
    CONSTRAINT fk_attendance_period_mutation_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_mutation_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_mutation_operation
        CHECK (operation_code IN ('CLOSE', 'REOPEN')),
    CONSTRAINT ck_attendance_period_mutation_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_attendance_period_mutation_digest
        CHECK (request_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attendance_period_mutation_completion
        CHECK (
            (status = 'IN_PROGRESS'
                AND response_status IS NULL
                AND response_body_json IS NULL
                AND resource_version IS NULL
                AND completed_at IS NULL)
            OR (status IN ('COMPLETED', 'FAILED')
                AND response_status IS NOT NULL
                AND completed_at IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_period_audit_event (
    attendance_period_audit_event_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_code VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_code VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_version BIGINT UNSIGNED NOT NULL,
    before_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    after_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_period_audit_event_id),
    KEY ix_attendance_period_audit_history
        (company_id, attendance_period_id, occurred_at,
         attendance_period_audit_event_id),
    CONSTRAINT fk_attendance_period_audit_period
        FOREIGN KEY (attendance_period_id, company_id)
        REFERENCES attendance_period (attendance_period_id, company_id),
    CONSTRAINT fk_attendance_period_audit_actor
        FOREIGN KEY (actor_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_period_audit_result
        CHECK (result_code IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT ck_attendance_period_audit_digest
        CHECK (
            (before_digest IS NULL
                OR before_digest REGEXP '^[0-9a-f]{64}$')
            AND (after_digest IS NULL
                OR after_digest REGEXP '^[0-9a-f]{64}$')
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE attendance_period
    ADD CONSTRAINT fk_attendance_period_current_snapshot
        FOREIGN KEY (current_close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id);

ALTER TABLE attendance_period_version
    ADD CONSTRAINT fk_attendance_period_version_snapshot
        FOREIGN KEY (close_snapshot_id)
        REFERENCES attendance_close_snapshot
            (attendance_close_snapshot_id);

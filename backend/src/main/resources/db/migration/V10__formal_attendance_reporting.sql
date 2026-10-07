CREATE TABLE attendance_report_projection (
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE NOT NULL,
    period_end_exclusive DATE NOT NULL,
    period_state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    formula_catalog_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_versions_json JSON NOT NULL,
    source_snapshot_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_as_of DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    created_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_projection_id),
    UNIQUE KEY uq_att_report_projection_version
        (legal_entity_id, period_start, projection_version),
    UNIQUE KEY uq_att_report_projection_content
        (legal_entity_id, period_start, projection_digest),
    UNIQUE KEY uq_att_report_projection_scope
        (attendance_report_projection_id, legal_entity_id),
    KEY ix_att_report_projection_latest
        (legal_entity_id, period_start, status, published_at,
         attendance_report_projection_id),
    CONSTRAINT fk_att_report_projection_entity
        FOREIGN KEY (legal_entity_id)
        REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_att_report_projection_actor
        FOREIGN KEY (created_by)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_att_report_projection_period
        CHECK (period_end_exclusive > period_start),
    CONSTRAINT ck_att_report_projection_state
        CHECK (period_state IN ('OPEN', 'FROZEN', 'CLOSED', 'REOPENED')),
    CONSTRAINT ck_att_report_projection_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_att_report_projection_publish
        CHECK (
            (status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR (status = 'DRAFT' AND published_at IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_daily_fact (
    attendance_report_daily_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    day_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shift_label VARCHAR(200) NOT NULL,
    scheduled_minutes INT UNSIGNED NOT NULL,
    confirmed_scheduled_work_minutes INT UNSIGNED NOT NULL,
    recognized_overtime_minutes INT UNSIGNED NOT NULL,
    leave_or_time_off_minutes INT UNSIGNED NOT NULL,
    absence_minutes INT UNSIGNED NOT NULL,
    actual_work_minutes INT UNSIGNED NOT NULL,
    late_minutes INT UNSIGNED NOT NULL,
    penalized_late_minutes INT UNSIGNED NOT NULL,
    early_departure_minutes INT UNSIGNED NOT NULL,
    missing_punch_count INT UNSIGNED NOT NULL,
    first_punch_at DATETIME(6) NULL,
    last_punch_at DATETIME(6) NULL,
    calculation_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_daily_fact_id),
    UNIQUE KEY uq_att_report_daily_projection_day
        (attendance_report_projection_id, employee_id, business_date),
    KEY ix_att_report_daily_scope
        (legal_entity_id, organization_id, employee_id, business_date),
    KEY ix_att_report_daily_employee
        (employee_id, business_date, attendance_report_daily_fact_id),
    CONSTRAINT fk_att_report_daily_projection_scope
        FOREIGN KEY (attendance_report_projection_id, legal_entity_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id, legal_entity_id),
    CONSTRAINT fk_att_report_daily_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_report_daily_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_att_report_daily_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id),
    CONSTRAINT fk_att_report_daily_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_daily_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_att_report_daily_day_type
        CHECK (
            day_type IN (
                'WEEKDAY', 'SATURDAY', 'SUNDAY',
                'PUBLIC_HOLIDAY', 'ADJUSTED_WORKDAY'
            )
        ),
    CONSTRAINT ck_att_report_daily_actual_work
        CHECK (
            actual_work_minutes =
                confirmed_scheduled_work_minutes
                + recognized_overtime_minutes
        ),
    CONSTRAINT ck_att_report_daily_late
        CHECK (penalized_late_minutes <= late_minutes),
    CONSTRAINT ck_att_report_daily_punch_order
        CHECK (
            first_punch_at IS NULL
            OR last_punch_at IS NULL
            OR last_punch_at >= first_punch_at
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_oa_fact (
    attendance_report_oa_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_attendance_document_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    leave_type_code VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    temporal_shape VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    point_instant DATETIME(6) NULL,
    interval_start DATETIME(6) NULL,
    interval_end DATETIME(6) NULL,
    recognized_minutes INT UNSIGNED NOT NULL,
    source_status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128)
        COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_oa_fact_id),
    UNIQUE KEY uq_att_report_oa_projection_document
        (attendance_report_projection_id, oa_attendance_document_id),
    KEY ix_att_report_oa_scope_time
        (legal_entity_id, organization_id, employee_id, interval_start),
    CONSTRAINT fk_att_report_oa_projection_scope
        FOREIGN KEY (attendance_report_projection_id, legal_entity_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id, legal_entity_id),
    CONSTRAINT fk_att_report_oa_document
        FOREIGN KEY (oa_attendance_document_id)
        REFERENCES oa_attendance_document (oa_attendance_document_id),
    CONSTRAINT fk_att_report_oa_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_report_oa_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_att_report_oa_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id),
    CONSTRAINT fk_att_report_oa_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_oa_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_att_report_oa_type
        CHECK (
            document_type IN (
                'LEAVE', 'LEAVE_REVOCATION', 'OVERTIME', 'TRIP',
                'OUTING', 'PUNCH_CORRECTION', 'TIME_OFF', 'EXEMPT_PUNCH'
            )
        ),
    CONSTRAINT ck_att_report_oa_status
        CHECK (
            source_status IN (
                'APPROVED', 'DRAFT', 'REJECTED', 'UNKNOWN',
                'MODIFIED', 'SUPPLEMENTED', 'REVOKED'
            )
        ),
    CONSTRAINT ck_att_report_oa_temporal_shape
        CHECK (
            (
                temporal_shape = 'POINT'
                AND point_instant IS NOT NULL
                AND interval_start IS NULL
                AND interval_end IS NULL
            )
            OR (
                temporal_shape IN ('INTERVAL', 'DATE_RANGE')
                AND point_instant IS NULL
                AND interval_start IS NOT NULL
                AND interval_end IS NOT NULL
                AND interval_end > interval_start
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_exception_fact (
    attendance_report_exception_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exception_case_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    exception_type VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    severity VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exception_minutes INT UNSIGNED NOT NULL,
    safe_evidence_summary VARCHAR(500) NOT NULL,
    calculation_version_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_exception_fact_id),
    UNIQUE KEY uq_att_report_exception_projection_case
        (attendance_report_projection_id, exception_case_id),
    KEY ix_att_report_exception_scope
        (legal_entity_id, organization_id, employee_id, business_date, state),
    CONSTRAINT fk_att_report_exception_projection_scope
        FOREIGN KEY (attendance_report_projection_id, legal_entity_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id, legal_entity_id),
    CONSTRAINT fk_att_report_exception_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_report_exception_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_att_report_exception_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_exception_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_att_report_exception_severity
        CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    CONSTRAINT ck_att_report_exception_state
        CHECK (
            state IN (
                'OPEN', 'PENDING_EVIDENCE', 'PENDING_REVIEW', 'RESOLVED'
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_time_account_fact (
    attendance_report_time_account_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_version_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opening_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    granted_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    overtime_credit_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    manual_increase_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    used_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    expired_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    returned_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    manual_deduction_hours DECIMAL(16,2) UNSIGNED NOT NULL,
    ledger_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_time_account_fact_id),
    UNIQUE KEY uq_att_report_account_projection
        (attendance_report_projection_id, account_id),
    KEY ix_att_report_account_scope
        (legal_entity_id, organization_id, employee_id, account_type),
    CONSTRAINT fk_att_report_account_projection_scope
        FOREIGN KEY (attendance_report_projection_id, legal_entity_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id, legal_entity_id),
    CONSTRAINT fk_att_report_account_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_report_account_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_att_report_account_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_account_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_att_report_account_type
        CHECK (
            account_type IN (
                'ANNUAL_LEAVE', 'COMP_TIME', 'RECOGNIZED_OVERTIME'
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_export_job (
    attendance_report_export_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_type VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE NOT NULL,
    legal_entity_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organization_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    filter_status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    purpose VARCHAR(200) NOT NULL,
    projection_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authorization_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    query_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visible_content_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    formula_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    export_fields_json JSON NOT NULL,
    row_count BIGINT UNSIGNED NOT NULL,
    delivery_mode VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    file_extension VARCHAR(8)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_sha256 CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_length BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failure_code VARCHAR(96)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    claimed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (attendance_report_export_id),
    KEY ix_att_report_export_owner
        (principal_id, created_at, attendance_report_export_id),
    KEY ix_att_report_export_worker
        (status, expires_at, created_at, attendance_report_export_id),
    CONSTRAINT fk_att_report_export_principal
        FOREIGN KEY (principal_id)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_att_report_export_entity
        FOREIGN KEY (legal_entity_id)
        REFERENCES legal_entity (legal_entity_id),
    CONSTRAINT fk_att_report_export_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_export_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee (employee_id),
    CONSTRAINT ck_att_report_export_type
        CHECK (
            report_type IN (
                'ATTENDANCE_DETAIL', 'LEAVE', 'OVERTIME', 'WORK_HOURS',
                'EXCEPTIONS', 'LATE', 'MISSED_PUNCH',
                'ATTENDANCE_RATE', 'ANNUAL_LEAVE'
            )
        ),
    CONSTRAINT ck_att_report_export_period
        CHECK (DAYOFMONTH(period_start) = 1),
    CONSTRAINT ck_att_report_export_purpose
        CHECK (CHAR_LENGTH(TRIM(purpose)) BETWEEN 2 AND 200),
    CONSTRAINT ck_att_report_export_fields
        CHECK (
            JSON_TYPE(export_fields_json) = 'ARRAY'
            AND JSON_LENGTH(export_fields_json) > 0
        ),
    CONSTRAINT ck_att_report_export_digests
        CHECK (
            authorization_digest REGEXP '^[0-9a-f]{64}$'
            AND query_fingerprint REGEXP '^[0-9a-f]{64}$'
            AND visible_content_digest REGEXP '^[0-9a-f]{64}$'
            AND (
                content_sha256 IS NULL
                OR content_sha256 REGEXP '^[0-9a-f]{64}$'
            )
        ),
    CONSTRAINT ck_att_report_export_extension
        CHECK (
            file_extension IS NULL
            OR file_extension REGEXP '^[a-z0-9]{1,8}$'
        ),
    CONSTRAINT ck_att_report_export_delivery
        CHECK (delivery_mode IN ('SYNC', 'ASYNC')),
    CONSTRAINT ck_att_report_export_status
        CHECK (status IN ('QUEUED', 'BUILDING', 'READY', 'FAILED')),
    CONSTRAINT ck_att_report_export_time
        CHECK (
            expires_at > created_at
            AND (
                completed_at IS NULL
                OR completed_at >= created_at
            )
        ),
    CONSTRAINT ck_att_report_export_state
        CHECK (
            (
                status = 'QUEUED'
                AND delivery_mode = 'ASYNC'
                AND claimed_at IS NULL
                AND completed_at IS NULL
                AND content_type IS NULL
                AND file_extension IS NULL
                AND content_sha256 IS NULL
                AND content_length = 0
                AND failure_code IS NULL
            )
            OR (
                status = 'BUILDING'
                AND delivery_mode = 'ASYNC'
                AND claimed_at IS NOT NULL
                AND completed_at IS NULL
                AND content_type IS NULL
                AND file_extension IS NULL
                AND content_sha256 IS NULL
                AND content_length = 0
                AND failure_code IS NULL
            )
            OR (
                status = 'READY'
                AND (
                    (
                        delivery_mode = 'SYNC'
                        AND claimed_at IS NULL
                    )
                    OR (
                        delivery_mode = 'ASYNC'
                        AND claimed_at IS NOT NULL
                    )
                )
                AND completed_at IS NOT NULL
                AND content_type IS NOT NULL
                AND file_extension IS NOT NULL
                AND content_sha256 IS NOT NULL
                AND content_length > 0
                AND failure_code IS NULL
            )
            OR (
                status = 'FAILED'
                AND delivery_mode = 'ASYNC'
                AND claimed_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND content_type IS NULL
                AND file_extension IS NULL
                AND content_sha256 IS NULL
                AND content_length = 0
                AND failure_code IS NOT NULL
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attendance_report_export_artifact (
    attendance_report_export_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content LONGBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_export_id),
    CONSTRAINT fk_att_report_export_artifact_job
        FOREIGN KEY (attendance_report_export_id)
        REFERENCES attendance_report_export_job
            (attendance_report_export_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_role (
    role_id, role_code, role_name, permission_domain
) VALUES
    (
        '10000000-0000-0000-0000-000000000004',
        'EXECUTIVE',
        '高管',
        'ATTENDANCE'
    ),
    (
        '10000000-0000-0000-0000-000000000005',
        'DEPARTMENT_HEAD',
        '部门负责人',
        'ATTENDANCE'
    ),
    (
        '10000000-0000-0000-0000-000000000006',
        'EMPLOYEE_SELF',
        '员工本人',
        'ATTENDANCE'
    ),
    (
        '10000000-0000-0000-0000-000000000007',
        'MANUFACTURING_CENTER_SUPERVISOR',
        '制造中心主管',
        'ATTENDANCE'
    );

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES
    (
        '2a000000-0000-0000-0000-000000000001',
        'ATTENDANCE_DASHBOARD:READ',
        'ATTENDANCE_REPORT',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000002',
        'ATTENDANCE_REPORT:READ',
        'ATTENDANCE_REPORT',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000003',
        'ATTENDANCE_REPORT:EXPORT_CREATE',
        'ATTENDANCE_REPORT',
        'EXPORT_CREATE'
    ),
    (
        '2a000000-0000-0000-0000-000000000004',
        'ATTENDANCE_REPORT:EXPORT_DOWNLOAD',
        'ATTENDANCE_REPORT',
        'EXPORT_DOWNLOAD'
    ),
    (
        '2a000000-0000-0000-0000-000000000005',
        'ATTENDANCE_SELF:READ',
        'ATTENDANCE_SELF',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000006',
        'LEAVE_SELF:READ',
        'ATTENDANCE_SELF',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000007',
        'ATTENDANCE_FEEDBACK:READ',
        'ATTENDANCE_FEEDBACK',
        'READ'
    ),
    (
        '2a000000-0000-0000-0000-000000000008',
        'ATTENDANCE_FEEDBACK:CREATE',
        'ATTENDANCE_FEEDBACK',
        'CREATE'
    );

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_DASHBOARD:READ',
      'ATTENDANCE_REPORT:READ'
  )
WHERE role.role_code IN (
    'HR_ADMIN', 'EXECUTIVE', 'DEPARTMENT_HEAD',
    'MANUFACTURING_CENTER_SUPERVISOR'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_REPORT:EXPORT_CREATE',
      'ATTENDANCE_REPORT:EXPORT_DOWNLOAD'
  )
WHERE role.role_code = 'HR_ADMIN';

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code IN (
      'ATTENDANCE_REPORT:READ',
      'ATTENDANCE_SELF:READ',
      'LEAVE_SELF:READ',
      'ATTENDANCE_FEEDBACK:READ',
      'ATTENDANCE_FEEDBACK:CREATE'
  )
WHERE role.role_code = 'EMPLOYEE_SELF';

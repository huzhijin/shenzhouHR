INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '2a000000-0000-0000-0000-000000000009',
    'ATTENDANCE_REPORT:REFRESH',
    'ATTENDANCE_REPORT',
    'REFRESH'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_REPORT:REFRESH'
WHERE role.role_code IN ('SYSTEM_ADMIN', 'HR_ADMIN');

CREATE TABLE attendance_report_refresh_record (
    attendance_report_refresh_record_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    period_start DATE NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    refresh_mode VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_version VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_as_of DATETIME(6) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    publication_created BOOLEAN NOT NULL,
    active_employee_count BIGINT UNSIGNED NOT NULL,
    daily_fact_count BIGINT UNSIGNED NOT NULL,
    oa_fact_count BIGINT UNSIGNED NOT NULL,
    exception_fact_count BIGINT UNSIGNED NOT NULL,
    time_account_fact_count BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_refresh_record_id),
    UNIQUE KEY uq_att_report_refresh_idempotency
        (principal_id, idempotency_key),
    KEY ix_att_report_refresh_scope
        (company_id, period_start, created_at,
         attendance_report_refresh_record_id),
    CONSTRAINT fk_att_report_refresh_principal
        FOREIGN KEY (principal_id)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_att_report_refresh_company
        FOREIGN KEY (company_id)
        REFERENCES company (company_id),
    CONSTRAINT fk_att_report_refresh_source
        FOREIGN KEY (source_projection_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id),
    CONSTRAINT fk_att_report_refresh_projection
        FOREIGN KEY (attendance_report_projection_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id),
    CONSTRAINT ck_att_report_refresh_period
        CHECK (DAYOFMONTH(period_start) = 1),
    CONSTRAINT ck_att_report_refresh_mode
        CHECK (
            refresh_mode IN (
                'EMPTY_MONTH',
                'AUTHORITATIVE_DAILY_CALCULATION',
                'FORMAL_PROJECTION_COPY'
            )
        ),
    CONSTRAINT ck_att_report_refresh_source
        CHECK (
            (
                refresh_mode = 'EMPTY_MONTH'
                AND source_projection_id IS NULL
                AND active_employee_count = 0
                AND daily_fact_count = 0
                AND oa_fact_count = 0
                AND exception_fact_count = 0
                AND time_account_fact_count = 0
            )
            OR refresh_mode = 'AUTHORITATIVE_DAILY_CALCULATION'
            OR (
                refresh_mode = 'FORMAL_PROJECTION_COPY'
                AND source_projection_id IS NOT NULL
            )
        ),
    CONSTRAINT ck_att_report_refresh_digests
        CHECK (
            request_digest REGEXP '^[0-9a-f]{64}$'
            AND projection_digest REGEXP '^[0-9a-f]{64}$'
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

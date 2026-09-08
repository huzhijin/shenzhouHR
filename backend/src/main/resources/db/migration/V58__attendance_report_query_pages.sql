INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '2a000000-0000-0000-0000-000000000031',
    'ATTENDANCE_REPORT_QUERY:READ',
    'ATTENDANCE_REPORT',
    'QUERY_READ'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability report_read
  ON report_read.capability_code = 'ATTENDANCE_REPORT:READ'
JOIN auth_role_capability existing
  ON existing.role_id = role.role_id
 AND existing.capability_id = report_read.capability_id
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_REPORT_QUERY:READ'
WHERE NOT EXISTS (
        SELECT 1
        FROM auth_role_capability already
        WHERE already.role_id = role.role_id
          AND already.capability_id = capability.capability_id
);

CREATE INDEX ix_att_report_query_daily
    ON attendance_report_daily_fact (
        attendance_report_projection_id,
        organization_id,
        employee_id,
        business_date);

CREATE INDEX ix_att_report_query_exception
    ON attendance_report_exception_fact (
        attendance_report_projection_id,
        organization_id,
        employee_id,
        business_date,
        exception_type,
        state);

CREATE INDEX ix_att_report_query_oa
    ON attendance_report_oa_fact (
        attendance_report_projection_id,
        organization_id,
        employee_id,
        document_type,
        interval_start);

CREATE INDEX ix_att_report_query_account
    ON attendance_report_time_account_fact (
        attendance_report_projection_id,
        organization_id,
        employee_id,
        account_type);

CREATE TABLE attendance_report_auto_recalc_slot (
    slot_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slot_start DATETIME(6) NOT NULL,
    deli_succeeded_at DATETIME(6) NULL,
    oa_succeeded_at DATETIME(6) NULL,
    recalc_due_at DATETIME(6) NULL,
    recalc_completed_at DATETIME(6) NULL,
    status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (slot_id),
    UNIQUE KEY uq_att_report_auto_recalc_slot (slot_start),
    CONSTRAINT ck_att_report_auto_recalc_status
        CHECK (status IN (
            'WAITING_SOURCES',
            'SCHEDULED',
            'RUNNING',
            'COMPLETED',
            'SKIPPED',
            'FAILED'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- Minimal relational fixture for the real immutable publication and query mappers.
CREATE TABLE company (company_id VARCHAR(36) PRIMARY KEY);
CREATE TABLE auth_principal (principal_id VARCHAR(36) PRIMARY KEY, status VARCHAR(32));
CREATE TABLE employee (employee_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), employee_number VARCHAR(64));
CREATE TABLE employee_version (
    employee_version_id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36),
    employee_number VARCHAR(64), display_name VARCHAR(128), effective_from DATE, effective_to DATE
);
CREATE TABLE employment_assignment (
    assignment_id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36), organization_id VARCHAR(36),
    effective_from TIMESTAMP, effective_to TIMESTAMP
);
CREATE TABLE organization_identity (organization_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36));
CREATE TABLE organization_version (
    organization_version_id VARCHAR(36) PRIMARY KEY, organization_id VARCHAR(36), name VARCHAR(128),
    effective_from TIMESTAMP, effective_to TIMESTAMP
);
CREATE TABLE attendance_report_publication_lock (
    company_id VARCHAR(36), period_start DATE, PRIMARY KEY (company_id, period_start)
);
CREATE TABLE attendance_report_projection (
    attendance_report_projection_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36),
    period_start DATE, period_end_exclusive DATE, period_state VARCHAR(32),
    projection_version VARCHAR(128), formula_catalog_version VARCHAR(128), source_versions_json JSON,
    source_snapshot_digest CHAR(64), projection_digest CHAR(64), status VARCHAR(32),
    data_as_of TIMESTAMP(6), published_at TIMESTAMP(6), created_by VARCHAR(36), created_at TIMESTAMP(6),
    UNIQUE (company_id, period_start, projection_digest),
    CHECK ((status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
);
CREATE TABLE attendance_report_daily_fact (
    attendance_report_daily_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36), employee_id VARCHAR(36), employee_version_id VARCHAR(36), employment_period_id VARCHAR(36),
    organization_id VARCHAR(36), organization_version_id VARCHAR(36), business_date DATE,
    day_type VARCHAR(32), shift_label VARCHAR(128), scheduled_minutes BIGINT,
    confirmed_scheduled_work_minutes BIGINT, recognized_overtime_minutes BIGINT,
    paid_overtime_minutes BIGINT, compensatory_overtime_minutes BIGINT, voluntary_overtime_minutes BIGINT, total_overtime_minutes BIGINT,
    leave_or_time_off_minutes BIGINT, leave_type VARCHAR(32), absence_minutes BIGINT, actual_work_minutes BIGINT,
    scheduled_attendance_days INTEGER, actual_attendance_days DECIMAL(3,1), late_minutes BIGINT,
    penalized_late_minutes BIGINT, early_departure_minutes BIGINT, missing_punch_count INTEGER,
    first_punch_at TIMESTAMP(6), last_punch_at TIMESTAMP(6), calculation_version_id VARCHAR(128), result_digest CHAR(64), created_at TIMESTAMP(6),
    UNIQUE (attendance_report_projection_id, employee_id, business_date)
);
CREATE TABLE attendance_report_exception_fact (
    attendance_report_exception_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36), exception_case_id VARCHAR(128), employee_id VARCHAR(36), employee_version_id VARCHAR(36),
    organization_id VARCHAR(36), organization_version_id VARCHAR(36), business_date DATE,
    exception_type VARCHAR(64), severity VARCHAR(32), state VARCHAR(32), exception_minutes BIGINT,
    safe_evidence_summary VARCHAR(256), calculation_version_id VARCHAR(128), created_at TIMESTAMP(6),
    UNIQUE (attendance_report_projection_id, exception_case_id)
);
CREATE TABLE attendance_source (
    attendance_source_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), source_type VARCHAR(32)
);
CREATE TABLE raw_attendance_fact (
    raw_attendance_fact_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), fact_kind VARCHAR(32),
    attendance_source_id VARCHAR(36)
);
CREATE TABLE normalized_attendance_record (
    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY, raw_attendance_fact_id VARCHAR(36),
    validation_status VARCHAR(32), record_kind VARCHAR(32)
);
CREATE TABLE employee_match_decision (
    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY, match_status VARCHAR(32), employee_id VARCHAR(36)
);
CREATE TABLE oa_attendance_document (
    oa_attendance_document_id VARCHAR(36) PRIMARY KEY, normalized_attendance_record_id VARCHAR(36),
    source_business_key VARCHAR(128), document_type VARCHAR(32), source_status VARCHAR(32), source_version VARCHAR(128),
    attendance_source_id VARCHAR(36)
);
CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(36) PRIMARY KEY, overtime_type VARCHAR(32));
CREATE TABLE attendance_report_oa_fact (
    attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36), oa_attendance_document_id VARCHAR(36), employee_id VARCHAR(36), employee_version_id VARCHAR(36),
    employment_period_id VARCHAR(36), organization_id VARCHAR(36), organization_version_id VARCHAR(36),
    document_type VARCHAR(32), leave_type_code VARCHAR(32), temporal_shape VARCHAR(32),
    point_instant TIMESTAMP(6), interval_start TIMESTAMP(6), interval_end TIMESTAMP(6), recognized_minutes BIGINT, recognized_minutes_by_date_json VARCHAR(16000), allocation_semantics VARCHAR(32),
    source_status VARCHAR(32), source_version VARCHAR(128), created_at TIMESTAMP(6),
    UNIQUE (attendance_report_projection_id, oa_attendance_document_id)
);
CREATE TABLE attendance_report_time_account_fact (
    attendance_report_time_account_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36), account_id VARCHAR(128), employee_id VARCHAR(36), employee_version_id VARCHAR(36),
    organization_id VARCHAR(36), organization_version_id VARCHAR(36), account_type VARCHAR(32),
    opening_hours DECIMAL(12,2), granted_hours DECIMAL(12,2), overtime_credit_hours DECIMAL(12,2),
    manual_increase_hours DECIMAL(12,2), used_hours DECIMAL(12,2), expired_hours DECIMAL(12,2), returned_hours DECIMAL(12,2),
    manual_deduction_hours DECIMAL(12,2), ledger_version VARCHAR(128), created_at TIMESTAMP(6)
);
CREATE TABLE attendance_hr_punch_adjustment (
    adjustment_id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    employee_id VARCHAR(36) NOT NULL,
    business_date DATE NOT NULL,
    on_duty_at TIMESTAMP(6) NULL,
    off_duty_at TIMESTAMP(6) NULL,
    day_types VARCHAR(256) NULL,
    overtime_minutes_override INT NULL,
    cleared_exception_types VARCHAR(256) NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    reversed_at TIMESTAMP(6) NULL,
    reversed_by VARCHAR(36) NULL,
    PRIMARY KEY (adjustment_id),
    CONSTRAINT fk_test_hr_punch_adj_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_test_hr_punch_adj_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_test_hr_punch_adj_payload CHECK (
        on_duty_at IS NOT NULL
        OR off_duty_at IS NOT NULL
        OR overtime_minutes_override IS NOT NULL
        OR (cleared_exception_types IS NOT NULL AND cleared_exception_types <> '')
        OR (day_types IS NOT NULL AND day_types <> '')
    )
);
CREATE INDEX ix_test_hr_punch_adj_employee ON attendance_hr_punch_adjustment (employee_id, business_date, created_at);
CREATE INDEX ix_test_hr_punch_adj_company ON attendance_hr_punch_adjustment (company_id, business_date);

CREATE TABLE attendance_report_hr_paper_fact (
    attendance_report_hr_paper_fact_id VARCHAR(36) NOT NULL,
    attendance_report_projection_id VARCHAR(36) NOT NULL,
    company_id VARCHAR(36) NOT NULL,
    hr_adjustment_id VARCHAR(36) NOT NULL,
    hr_source_kind VARCHAR(40) NOT NULL,
    source_document_key VARCHAR(36) NOT NULL,
    employee_id VARCHAR(36) NOT NULL,
    employee_version_id VARCHAR(36) NOT NULL,
    employment_period_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    organization_version_id VARCHAR(36) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    leave_type_code VARCHAR(64) NULL,
    temporal_shape VARCHAR(16) NOT NULL,
    point_instant TIMESTAMP(6) NULL,
    interval_start TIMESTAMP(6) NULL,
    interval_end TIMESTAMP(6) NULL,
    recognized_minutes INT NOT NULL,
    recognized_minutes_by_date_json VARCHAR(4096) NULL,
    source_status VARCHAR(32) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (attendance_report_hr_paper_fact_id),
    CONSTRAINT uq_test_att_report_hr_paper_source
        UNIQUE (attendance_report_projection_id, hr_adjustment_id, hr_source_kind),
    CONSTRAINT uq_test_att_report_hr_paper_key
        UNIQUE (attendance_report_projection_id, source_document_key),
    CONSTRAINT fk_test_att_report_hr_paper_adjustment
        FOREIGN KEY (hr_adjustment_id)
        REFERENCES attendance_hr_punch_adjustment (adjustment_id),
    CONSTRAINT fk_test_att_report_hr_paper_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_test_att_report_hr_paper_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_test_att_report_hr_paper_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id),
    CONSTRAINT fk_test_att_report_hr_paper_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_test_att_report_hr_paper_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_test_att_report_hr_paper_kind
        CHECK (
            hr_source_kind IS NOT NULL
            AND (
                (hr_source_kind IN ('ENTRY', 'EXIT')
                    AND document_type = 'PUNCH_CORRECTION'
                    AND temporal_shape = 'POINT'
                    AND point_instant IS NOT NULL
                    AND interval_start IS NULL
                    AND interval_end IS NULL)
                OR (hr_source_kind LIKE 'DAY_TYPE:%'
                    AND document_type IN ('LEAVE', 'TIME_OFF', 'OUTING', 'TRIP')
                    AND temporal_shape = 'INTERVAL'
                    AND point_instant IS NULL
                    AND interval_start IS NOT NULL
                    AND interval_end IS NOT NULL
                    AND interval_start < interval_end)
            )
        ),
    CONSTRAINT ck_test_att_report_hr_paper_status
        CHECK (source_status = 'APPROVED' AND recognized_minutes = 0)
);
CREATE INDEX ix_test_att_report_hr_paper_scope
    ON attendance_report_hr_paper_fact (attendance_report_projection_id, employee_id, interval_start);


-- Minimal relational fixture for HR paper fact write/read integration test.
-- Adapted from V10/V62/V66/V78 for H2 compatibility.

-- H2 compatibility function for MySQL FIND_IN_SET
CREATE ALIAS IF NOT EXISTS FIND_IN_SET FOR "com.szsemicon.hr.reporting.infrastructure.persistence.HrPaperFactWriteReadTest.findInSet";

CREATE TABLE company (
    company_id VARCHAR(36) PRIMARY KEY
);

CREATE TABLE auth_principal (
    principal_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(32)
);

CREATE TABLE employee (
    employee_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    employee_number VARCHAR(64)
);

CREATE TABLE employee_version (
    employee_version_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36),
    employee_number VARCHAR(64),
    display_name VARCHAR(128),
    effective_from DATE,
    effective_to DATE
);

CREATE TABLE employment_assignment (
    assignment_id VARCHAR(36) PRIMARY KEY,
    employee_id VARCHAR(36),
    organization_id VARCHAR(36),
    effective_from TIMESTAMP(6),
    effective_to TIMESTAMP(6)
);

CREATE TABLE organization_identity (
    organization_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    identity_status VARCHAR(32)
);

CREATE TABLE organization_version (
    organization_version_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36),
    name VARCHAR(128),
    effective_from TIMESTAMP(6),
    effective_to TIMESTAMP(6)
);

CREATE TABLE attendance_hr_punch_adjustment (
    adjustment_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    employee_id VARCHAR(36),
    business_date DATE NOT NULL,
    on_duty_at TIMESTAMP(6) NULL,
    off_duty_at TIMESTAMP(6) NULL,
    day_types VARCHAR(256) NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    reversed_at TIMESTAMP(6) NULL,
    reversed_by VARCHAR(36) NULL
);

CREATE TABLE attendance_source (
    attendance_source_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    source_type VARCHAR(32)
);

CREATE TABLE raw_attendance_fact (
    raw_attendance_fact_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    fact_kind VARCHAR(32),
    attendance_source_id VARCHAR(36)
);

CREATE TABLE normalized_attendance_record (
    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY,
    raw_attendance_fact_id VARCHAR(36),
    validation_status VARCHAR(32),
    record_kind VARCHAR(32)
);

CREATE TABLE employee_match_decision (
    normalized_attendance_record_id VARCHAR(36) PRIMARY KEY,
    match_status VARCHAR(32),
    employee_id VARCHAR(36)
);

CREATE TABLE oa_attendance_document (
    oa_attendance_document_id VARCHAR(36) PRIMARY KEY,
    normalized_attendance_record_id VARCHAR(36),
    source_business_key VARCHAR(128),
    document_type VARCHAR(32),
    source_status VARCHAR(32),
    source_version VARCHAR(128),
    attendance_source_id VARCHAR(36)
);

CREATE TABLE oa_attendance_document_context (
    oa_attendance_document_context_id VARCHAR(36) PRIMARY KEY,
    oa_attendance_document_id VARCHAR(36) NOT NULL,
    oa_runtime_contract_revision_id VARCHAR(36) NOT NULL,
    attendance_group_revision_id VARCHAR(36),
    activation_decision VARCHAR(64) NOT NULL,
    raw_status_value VARCHAR(128) NOT NULL,
    overtime_treatment VARCHAR(32) NOT NULL,
    overtime_type VARCHAR(64),
    recognized_work_minutes INT NOT NULL DEFAULT 0,
    payroll_credit_minutes INT NOT NULL DEFAULT 0,
    time_off_credit_minutes INT NOT NULL DEFAULT 0,
    authorized_context_json VARCHAR(8000) NOT NULL,
    context_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (oa_attendance_document_id),
    FOREIGN KEY (oa_attendance_document_id) REFERENCES oa_attendance_document(oa_attendance_document_id)
);

CREATE TABLE attendance_report_projection (
    attendance_report_projection_id VARCHAR(36) PRIMARY KEY,
    company_id VARCHAR(36),
    period_start DATE,
    period_end_exclusive DATE,
    period_state VARCHAR(32),
    projection_version VARCHAR(128),
    formula_catalog_version VARCHAR(128),
    source_versions_json JSON,
    source_snapshot_digest CHAR(64),
    projection_digest CHAR(64),
    status VARCHAR(32),
    data_as_of TIMESTAMP(6),
    published_at TIMESTAMP(6),
    created_by VARCHAR(36),
    created_at TIMESTAMP(6),
    UNIQUE (company_id, period_start, projection_digest),
    CHECK ((status = 'DRAFT' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL))
);

CREATE TABLE attendance_report_oa_fact (
    attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36),
    oa_attendance_document_id VARCHAR(36),
    employee_id VARCHAR(36),
    employee_version_id VARCHAR(36),
    employment_period_id VARCHAR(36),
    organization_id VARCHAR(36),
    organization_version_id VARCHAR(36),
    document_type VARCHAR(32),
    leave_type_code VARCHAR(32),
    temporal_shape VARCHAR(32),
    point_instant TIMESTAMP(6),
    interval_start TIMESTAMP(6),
    interval_end TIMESTAMP(6),
    recognized_minutes BIGINT,
    recognized_minutes_by_date_json VARCHAR(16000),
    allocation_semantics VARCHAR(32),
    source_status VARCHAR(32),
    source_version VARCHAR(128),
    created_at TIMESTAMP(6),
    UNIQUE (attendance_report_projection_id, oa_attendance_document_id)
);

CREATE TABLE attendance_report_hr_paper_fact (
    attendance_report_hr_paper_fact_id VARCHAR(36) PRIMARY KEY,
    attendance_report_projection_id VARCHAR(36) REFERENCES attendance_report_projection,
    company_id VARCHAR(36),
    hr_adjustment_id VARCHAR(36),
    hr_source_kind VARCHAR(40) NOT NULL,
    source_document_key VARCHAR(36) NOT NULL,
    employee_id VARCHAR(36),
    employee_version_id VARCHAR(36),
    employment_period_id VARCHAR(36),
    organization_id VARCHAR(36),
    organization_version_id VARCHAR(36),
    document_type VARCHAR(32),
    leave_type_code VARCHAR(64),
    temporal_shape VARCHAR(16) NOT NULL,
    point_instant TIMESTAMP(6),
    interval_start TIMESTAMP(6),
    interval_end TIMESTAMP(6),
    recognized_minutes BIGINT NOT NULL,
    recognized_minutes_by_date_json JSON,
    source_status VARCHAR(32) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (attendance_report_projection_id, hr_adjustment_id, hr_source_kind),
    UNIQUE (attendance_report_projection_id, source_document_key)
);

CREATE INDEX ix_att_report_hr_paper_scope
    ON attendance_report_hr_paper_fact (attendance_report_projection_id, employee_id, interval_start);

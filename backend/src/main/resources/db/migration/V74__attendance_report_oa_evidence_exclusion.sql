-- Audited, reversible report-only decisions. This migration creates no decisions
-- and never modifies OA/raw evidence or its workflow status. All times are UTC.
CREATE TABLE attendance_report_oa_evidence_exclusion (
    exclusion_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_business_key VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
    source_version VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    oa_attendance_document_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    knowledge_at DATETIME(6) NOT NULL,
    reversed_at DATETIME(6) NULL,
    reason VARCHAR(1000) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    audit_reference VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    user_confirmation_text TEXT NOT NULL,
    user_confirmation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reversed_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reversal_reason VARCHAR(1000) NULL,
    reversal_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (exclusion_id),
    UNIQUE KEY uq_oa_report_exclusion_identity
        (company_id, employee_id, attendance_source_id, source_business_key,
         source_version, oa_attendance_document_id),
    UNIQUE KEY uq_oa_report_exclusion_request_document (request_id, oa_attendance_document_id),
    KEY ix_oa_report_exclusion_document_asof
        (oa_attendance_document_id, company_id, employee_id, knowledge_at, reversed_at),
    KEY ix_oa_report_exclusion_source_key
        (attendance_source_id, source_business_key, knowledge_at),
    CONSTRAINT fk_oa_report_exclusion_company FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_oa_report_exclusion_employee FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_oa_report_exclusion_source FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_oa_report_exclusion_document FOREIGN KEY (oa_attendance_document_id)
        REFERENCES oa_attendance_document (oa_attendance_document_id),
    CONSTRAINT fk_oa_report_exclusion_actor FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_oa_report_exclusion_reverser FOREIGN KEY (reversed_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_oa_report_exclusion_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_oa_report_exclusion_confirmation CHECK (CHAR_LENGTH(TRIM(user_confirmation_text)) > 0),
    CONSTRAINT ck_oa_report_exclusion_reversal CHECK (
        (reversed_at IS NULL AND reversed_by IS NULL AND reversal_reason IS NULL AND reversal_request_id IS NULL)
        OR (reversed_at IS NOT NULL AND reversed_at >= knowledge_at AND reversed_by IS NOT NULL
            AND reversal_reason IS NOT NULL AND CHAR_LENGTH(TRIM(reversal_reason)) > 0
            AND reversal_request_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

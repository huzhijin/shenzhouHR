-- HR manual adjustments (手工补卡 ENTRY/EXIT, 当天类型 DAY_TYPE:x) publish PAPER
-- report facts with no oa_attendance_document row. They live in their own
-- immutable table so attendance_report_oa_fact (its NOT NULL document id, FK
-- and unique key) is untouched: an old package that reads only that table
-- keeps working after a rollback, and simply does not see HR PAPER facts.
-- Every row is bound to its real HR source row via hr_adjustment_id.

CREATE TABLE attendance_report_hr_paper_fact (
    attendance_report_hr_paper_fact_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_report_projection_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hr_adjustment_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hr_source_kind VARCHAR(40)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_document_key VARCHAR(36)
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
    recognized_minutes_by_date_json JSON NULL,
    source_status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128)
        COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_report_hr_paper_fact_id),
    UNIQUE KEY uq_att_report_hr_paper_source
        (attendance_report_projection_id, hr_adjustment_id, hr_source_kind),
    UNIQUE KEY uq_att_report_hr_paper_key
        (attendance_report_projection_id, source_document_key),
    KEY ix_att_report_hr_paper_scope
        (attendance_report_projection_id, employee_id, interval_start),
    CONSTRAINT fk_att_report_hr_paper_projection_scope
        FOREIGN KEY (attendance_report_projection_id, company_id)
        REFERENCES attendance_report_projection
            (attendance_report_projection_id, company_id),
    CONSTRAINT fk_att_report_hr_paper_adjustment
        FOREIGN KEY (hr_adjustment_id)
        REFERENCES attendance_hr_punch_adjustment (adjustment_id),
    CONSTRAINT fk_att_report_hr_paper_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_att_report_hr_paper_employee_version
        FOREIGN KEY (employee_version_id)
        REFERENCES employee_version (employee_version_id),
    CONSTRAINT fk_att_report_hr_paper_employment
        FOREIGN KEY (employment_period_id)
        REFERENCES employment_assignment (assignment_id),
    CONSTRAINT fk_att_report_hr_paper_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization_identity (organization_id),
    CONSTRAINT fk_att_report_hr_paper_org_version
        FOREIGN KEY (organization_version_id)
        REFERENCES organization_version (organization_version_id),
    CONSTRAINT ck_att_report_hr_paper_kind
        CHECK (
            hr_source_kind IS NOT NULL
            AND (
                (hr_source_kind IN ('ENTRY', 'EXIT')
                    AND document_type = 'PUNCH_CORRECTION'
                    AND temporal_shape = 'POINT'
                    AND point_instant IS NOT NULL
                    AND interval_start IS NULL
                    AND interval_end IS NULL)
                OR (hr_source_kind LIKE 'DAY\_TYPE:%'
                    AND document_type IN ('LEAVE', 'TIME_OFF', 'OUTING', 'TRIP')
                    AND temporal_shape = 'INTERVAL'
                    AND point_instant IS NULL
                    AND interval_start IS NOT NULL
                    AND interval_end IS NOT NULL
                    AND interval_start < interval_end)
            )
        ),
    CONSTRAINT ck_att_report_hr_paper_status
        CHECK (source_status = 'APPROVED' AND recognized_minutes = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

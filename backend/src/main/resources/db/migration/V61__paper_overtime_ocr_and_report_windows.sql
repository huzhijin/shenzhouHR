-- Paper overtime entry, PAPER_OVERTIME source type, and HR capability.

ALTER TABLE attendance_source
    DROP CHECK ck_att_source_type;

ALTER TABLE attendance_source
    ADD CONSTRAINT ck_att_source_type CHECK (
        source_type IN (
            'DELI_CLOUD',
            'OA_ATTENDANCE',
            'DEVICE_EXCEL',
            'STANDARD_XLSX',
            'PAPER_OVERTIME'
        )
    );

CREATE TABLE paper_overtime_batch (
    paper_overtime_batch_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    saved_at DATETIME(6) NULL,
    PRIMARY KEY (paper_overtime_batch_id),
    KEY ix_paper_ot_batch_company (company_id, created_at),
    CONSTRAINT fk_paper_ot_batch_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_paper_ot_batch_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_paper_ot_batch_status CHECK (
        status IN ('DRAFT', 'SAVED', 'REJECTED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE paper_overtime_attachment (
    paper_overtime_attachment_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_overtime_batch_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    content MEDIUMBLOB NOT NULL,
    ocr_text MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (paper_overtime_attachment_id),
    KEY ix_paper_ot_attachment_batch (paper_overtime_batch_id),
    CONSTRAINT fk_paper_ot_attachment_batch
        FOREIGN KEY (paper_overtime_batch_id)
        REFERENCES paper_overtime_batch (paper_overtime_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE paper_overtime_line (
    paper_overtime_line_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_overtime_batch_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ocr_name VARCHAR(100) NULL,
    ocr_department VARCHAR(191) NULL,
    overtime_date DATE NULL,
    start_at DATETIME(6) NULL,
    end_at DATETIME(6) NULL,
    overtime_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(500) NULL,
    oa_attendance_document_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (paper_overtime_line_id),
    KEY ix_paper_ot_line_batch (paper_overtime_batch_id),
    KEY ix_paper_ot_line_employee (employee_id, overtime_date),
    CONSTRAINT fk_paper_ot_line_batch
        FOREIGN KEY (paper_overtime_batch_id)
        REFERENCES paper_overtime_batch (paper_overtime_batch_id),
    CONSTRAINT ck_paper_ot_line_type CHECK (
        overtime_type IS NULL
        OR overtime_type IN ('PAID', 'COMPENSATORY', 'VOLUNTARY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '61000000-0000-0000-0000-000000000001',
    'PAPER_OVERTIME:MANAGE',
    'ATTENDANCE_SOURCE',
    'MANAGE'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'PAPER_OVERTIME:MANAGE'
WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN');

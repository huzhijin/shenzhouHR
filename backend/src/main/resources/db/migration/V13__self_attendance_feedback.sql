-- Minimal append-only employee attendance feedback. Replies and attachments
-- intentionally remain outside this first production write model.
CREATE TABLE attendance_feedback (
    attendance_feedback_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_date DATE NOT NULL,
    problem_type VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(2000) NOT NULL,
    state VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_feedback_id),
    UNIQUE KEY uk_attendance_feedback_idempotency
        (principal_id, idempotency_key),
    KEY ix_attendance_feedback_self
        (employee_id, company_id, created_at, attendance_feedback_id),
    CONSTRAINT fk_attendance_feedback_principal
        FOREIGN KEY (principal_id)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_attendance_feedback_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee (employee_id),
    CONSTRAINT fk_attendance_feedback_company
        FOREIGN KEY (company_id)
        REFERENCES company (company_id),
    CONSTRAINT ck_attendance_feedback_problem
        CHECK (
            problem_type IN (
                'MISSING_PUNCH', 'LATE', 'OVERTIME', 'LEAVE', 'OTHER'
            )
        ),
    CONSTRAINT ck_attendance_feedback_state
        CHECK (state = 'SUBMITTED'),
    CONSTRAINT ck_attendance_feedback_digest
        CHECK (request_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attendance_feedback_content
        CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 2 AND 2000),
    CONSTRAINT ck_attendance_feedback_reason
        CHECK (CHAR_LENGTH(TRIM(change_reason)) BETWEEN 2 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

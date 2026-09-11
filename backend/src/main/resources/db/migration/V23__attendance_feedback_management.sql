-- Close the employee attendance-feedback loop without mutating the V13
-- append-only submission record. A feedback item has at most one final
-- management reply; the employee-facing state is derived as RESOLVED when
-- this row exists.
CREATE TABLE attendance_feedback_reply (
    attendance_feedback_reply_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_feedback_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replied_by_principal_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reply_text VARCHAR(2000) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_feedback_reply_id),
    UNIQUE KEY uk_attendance_feedback_reply_feedback
        (attendance_feedback_id),
    UNIQUE KEY uk_attendance_feedback_reply_idempotency
        (replied_by_principal_id, idempotency_key),
    KEY ix_attendance_feedback_reply_created
        (created_at, attendance_feedback_reply_id),
    CONSTRAINT fk_attendance_feedback_reply_feedback
        FOREIGN KEY (attendance_feedback_id)
        REFERENCES attendance_feedback (attendance_feedback_id),
    CONSTRAINT fk_attendance_feedback_reply_principal
        FOREIGN KEY (replied_by_principal_id)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_feedback_reply_digest
        CHECK (request_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attendance_feedback_reply_text
        CHECK (CHAR_LENGTH(TRIM(reply_text)) BETWEEN 2 AND 2000),
    CONSTRAINT ck_attendance_feedback_reply_reason
        CHECK (CHAR_LENGTH(TRIM(change_reason)) BETWEEN 2 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '2f000000-0000-0000-0000-000000000001',
    'ATTENDANCE_FEEDBACK:MANAGE',
    'ATTENDANCE_FEEDBACK',
    'MANAGE'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_FEEDBACK:MANAGE'
WHERE role.role_code IN (
    'SYSTEM_ADMIN',
    'HR_ADMIN',
    'DEPARTMENT_HEAD',
    'MANUFACTURING_CENTER_SUPERVISOR'
);

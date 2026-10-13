-- HR can fill missing punches on reports; append-only with reversal.

CREATE TABLE attendance_hr_punch_adjustment (
    adjustment_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_date DATE NOT NULL,
    on_duty_at DATETIME(6) NULL,
    off_duty_at DATETIME(6) NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    reversed_at DATETIME(6) NULL,
    reversed_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (adjustment_id),
    KEY ix_hr_punch_adj_employee (employee_id, business_date, created_at),
    KEY ix_hr_punch_adj_company (company_id, business_date),
    CONSTRAINT fk_hr_punch_adj_company
        FOREIGN KEY (company_id) REFERENCES company (company_id),
    CONSTRAINT fk_hr_punch_adj_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_hr_punch_adj_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_hr_punch_adj_clock CHECK (
        on_duty_at IS NOT NULL OR off_duty_at IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
) VALUES (
    '62000000-0000-0000-0000-000000000001',
    'ATTENDANCE_ADJUST:MANAGE',
    'ATTENDANCE_REPORT',
    'MANAGE'
);

INSERT INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_code = 'ATTENDANCE_ADJUST:MANAGE'
WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN');

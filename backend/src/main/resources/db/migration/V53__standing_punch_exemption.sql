CREATE TABLE punch_exemption_assignment (
    exemption_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listed_employee_number VARCHAR(64) NOT NULL,
    source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    valid_to DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (exemption_id),
    KEY ix_punch_exemption_employee_period (
        employee_id, valid_from, valid_to),
    KEY ix_punch_exemption_listed_number (listed_employee_number),
    CONSTRAINT fk_punch_exemption_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT ck_punch_exemption_source CHECK (source IN ('STANDING_LIST')),
    CONSTRAINT ck_punch_exemption_period
        CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

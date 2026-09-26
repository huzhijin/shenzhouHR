ALTER TABLE employee_source_binding
    ADD COLUMN deli_attendance_source_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER deli_employee_num,
    ADD COLUMN deli_row_version BIGINT UNSIGNED NOT NULL DEFAULT 0
        AFTER confirmation_ref,
    ADD COLUMN deli_confirmed_by
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER deli_row_version,
    ADD COLUMN deli_confirmed_at DATETIME(6) NULL
        AFTER deli_confirmed_by,
    ADD COLUMN deli_change_reason VARCHAR(500) NULL
        AFTER deli_confirmed_at,
    DROP INDEX uq_employee_binding_deli_user_current,
    DROP INDEX uq_employee_binding_deli_ext_current,
    ADD UNIQUE KEY uq_employee_binding_deli_user_current
        (deli_attendance_source_id, deli_user_id, current_marker),
    ADD UNIQUE KEY uq_employee_binding_deli_ext_current
        (deli_attendance_source_id, deli_ext_id, current_marker),
    ADD KEY ix_employee_binding_deli_source_current
        (deli_attendance_source_id, current_marker, binding_status, employee_id),
    ADD CONSTRAINT fk_employee_binding_deli_source
        FOREIGN KEY (deli_attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    ADD CONSTRAINT fk_employee_binding_deli_actor
        FOREIGN KEY (deli_confirmed_by)
        REFERENCES auth_principal (principal_id),
    ADD CONSTRAINT ck_employee_binding_deli_confirmation CHECK (
        (
            deli_attendance_source_id IS NULL
            AND deli_confirmed_by IS NULL
            AND deli_confirmed_at IS NULL
            AND deli_change_reason IS NULL
        )
        OR (
            deli_attendance_source_id IS NOT NULL
            AND binding_status = 'CONFIRMED'
            AND confirmation_ref IS NOT NULL
            AND deli_confirmed_by IS NOT NULL
            AND deli_confirmed_at IS NOT NULL
            AND deli_change_reason IS NOT NULL
            AND (deli_ext_id IS NOT NULL OR deli_user_id IS NOT NULL)
        )
    );

-- Legacy rows deliberately remain unscoped. They may contain identifiers from
-- an unknown credential instance, so an operator must confirm the exact Deli
-- source before the formal resolver can use them.

CREATE TABLE deli_employee_binding_revision (
    deli_employee_binding_revision_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deli_ext_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    revision_action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_row_version BIGINT UNSIGNED NOT NULL,
    confirmation_ref VARCHAR(128) NULL,
    changed_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (deli_employee_binding_revision_id),
    KEY ix_deli_binding_revision_history
        (binding_id, changed_at, deli_employee_binding_revision_id),
    KEY ix_deli_binding_revision_source_employee
        (attendance_source_id, employee_id, changed_at),
    CONSTRAINT fk_deli_binding_revision_binding
        FOREIGN KEY (binding_id) REFERENCES employee_source_binding (binding_id),
    CONSTRAINT fk_deli_binding_revision_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_deli_binding_revision_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id),
    CONSTRAINT fk_deli_binding_revision_actor
        FOREIGN KEY (changed_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_deli_binding_revision_action CHECK (
        revision_action IN ('CONFIRMED', 'SUPERSEDED', 'CLOSED')
    ),
    CONSTRAINT ck_deli_binding_revision_identity CHECK (
        deli_ext_id IS NOT NULL OR deli_user_id IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deli_source_connection_probe (
    deli_source_connection_probe_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    returned_record_count INT UNSIGNED NOT NULL,
    latency_millis BIGINT UNSIGNED NOT NULL,
    tested_cursor_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tested_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (deli_source_connection_probe_id),
    KEY ix_deli_source_probe_latest
        (attendance_source_id, tested_at, deli_source_connection_probe_id),
    CONSTRAINT fk_deli_source_probe_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_deli_source_probe_actor
        FOREIGN KEY (tested_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_deli_source_probe_status CHECK (
        status IN ('SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_deli_source_probe_shape CHECK (
        (status = 'SUCCEEDED' AND safe_error_code IS NULL)
        OR (status = 'FAILED' AND safe_error_code IS NOT NULL)
    ),
    CONSTRAINT ck_deli_source_probe_count CHECK (
        returned_record_count BETWEEN 0 AND 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

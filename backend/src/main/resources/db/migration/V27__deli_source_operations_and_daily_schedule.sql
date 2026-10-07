CREATE TABLE deli_source_operation (
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    initialization_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    initial_cursor VARCHAR(512) COLLATE utf8mb4_bin NULL,
    initialization_basis VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    initialization_confirmed_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    initialization_confirmed_at DATETIME(6) NULL,
    schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_local_time TIME NOT NULL DEFAULT '02:00:00',
    schedule_time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        DEFAULT 'Asia/Shanghai',
    next_run_at DATETIME(6) NULL,
    active_claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_claim_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_claim_expires_at DATETIME(6) NULL,
    last_claimed_at DATETIME(6) NULL,
    last_scheduled_job_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_scheduler_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (attendance_source_id),
    KEY ix_deli_source_schedule_due
        (schedule_enabled, next_run_at, attendance_source_id),
    KEY ix_deli_source_claim_expiry
        (active_claim_expires_at, attendance_source_id),
    CONSTRAINT fk_deli_source_operation_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_deli_source_operation_confirmed_actor
        FOREIGN KEY (initialization_confirmed_by)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_deli_source_operation_created_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_deli_source_operation_updated_actor
        FOREIGN KEY (updated_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_deli_source_operation_last_job
        FOREIGN KEY (last_scheduled_job_id)
        REFERENCES attendance_sync_job (attendance_sync_job_id),
    CONSTRAINT ck_deli_source_initial_cursor CHECK (
        (
            initialization_confirmed = FALSE
            AND initial_cursor IS NULL
            AND initialization_basis IS NULL
            AND initialization_confirmed_by IS NULL
            AND initialization_confirmed_at IS NULL
        )
        OR (
            initialization_confirmed = TRUE
            AND initial_cursor = '0'
            AND initialization_basis = 'DELI_ALREADY_INITIALIZED'
            AND initialization_confirmed_by IS NOT NULL
            AND initialization_confirmed_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_deli_source_schedule_time_zone CHECK (
        CHAR_LENGTH(schedule_time_zone) BETWEEN 1 AND 64
    ),
    CONSTRAINT ck_deli_source_claim_shape CHECK (
        (
            active_claim_token IS NULL
            AND active_claim_owner IS NULL
            AND active_claim_expires_at IS NULL
        )
        OR (
            active_claim_token IS NOT NULL
            AND active_claim_owner IS NOT NULL
            AND active_claim_expires_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_deli_source_schedule_next CHECK (
        schedule_enabled = FALSE OR next_run_at IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE deli_source_schedule_slot (
    attendance_source_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scheduled_local_date DATE NOT NULL,
    attendance_sync_job_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claimed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attendance_source_id, scheduled_local_date),
    UNIQUE KEY uq_deli_schedule_slot_job (attendance_sync_job_id),
    CONSTRAINT fk_deli_schedule_slot_source
        FOREIGN KEY (attendance_source_id)
        REFERENCES attendance_source (attendance_source_id),
    CONSTRAINT fk_deli_schedule_slot_job
        FOREIGN KEY (attendance_sync_job_id)
        REFERENCES attendance_sync_job (attendance_sync_job_id),
    CONSTRAINT fk_deli_schedule_slot_actor
        FOREIGN KEY (claimed_by) REFERENCES auth_principal (principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing Deli sources are deliberately left unconfirmed. An authorized
-- operator must attest that the tenant initialization already happened and
-- confirm official first cursor 0 before any post-upgrade vendor request.
INSERT INTO deli_source_operation (
    attendance_source_id, initialization_confirmed, initial_cursor,
    initialization_basis, initialization_confirmed_by,
    initialization_confirmed_at, schedule_enabled, schedule_local_time,
    schedule_time_zone, next_run_at, row_version, created_by, created_at,
    updated_by, updated_at, change_reason
)
SELECT source.attendance_source_id, FALSE, NULL, NULL, NULL, NULL,
       FALSE, '02:00:00', 'Asia/Shanghai', NULL, 0,
       source.created_by, source.created_at,
       source.created_by, CURRENT_TIMESTAMP(6),
       'V27 migration: operator confirmation required before first synchronization'
FROM attendance_source source
WHERE source.source_type = 'DELI_CLOUD';

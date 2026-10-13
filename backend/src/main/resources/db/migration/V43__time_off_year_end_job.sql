-- V43: auditable, cluster-safe orchestration state for TIME_OFF year-end expiry.
--
-- Balance mutations remain exclusively inside szsc_oa_time_off_expire. These
-- tables only coordinate the HR scheduler and retain per-employee outcomes.

ALTER TABLE time_account
    ADD KEY ix_time_account_type_year_id
        (account_type, account_year, time_account_id);

CREATE TABLE time_off_year_end_lock (
    account_year SMALLINT UNSIGNED NOT NULL,
    lock_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_expires_at DATETIME(6) NULL,
    acquired_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_year),
    KEY ix_time_off_year_end_lock_expiry (lock_expires_at),
    CONSTRAINT ck_time_off_year_end_lock_shape CHECK (
        (
            lock_token IS NULL
            AND lock_owner IS NULL
            AND lock_expires_at IS NULL
            AND acquired_at IS NULL
        )
        OR (
            lock_token IS NOT NULL
            AND lock_owner IS NOT NULL
            AND lock_expires_at IS NOT NULL
            AND acquired_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE time_off_year_end_run (
    time_off_year_end_run_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_year SMALLINT UNSIGNED NOT NULL,
    trigger_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lock_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_count INT UNSIGNED NOT NULL DEFAULT 0,
    success_count INT UNSIGNED NOT NULL DEFAULT 0,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    skipped_count INT UNSIGNED NOT NULL DEFAULT 0,
    failure_summary VARCHAR(500) NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (time_off_year_end_run_id),
    KEY ix_time_off_year_end_run_year_started (account_year, started_at),
    KEY ix_time_off_year_end_run_status_started (run_status, started_at),
    CONSTRAINT ck_time_off_year_end_trigger CHECK (
        trigger_type IN ('SCHEDULED', 'MANUAL')
    ),
    CONSTRAINT ck_time_off_year_end_run_status CHECK (
        run_status IN (
            'RUNNING', 'SUCCEEDED', 'COMPLETED_WITH_FAILURES',
            'FAILED', 'SKIPPED_LOCKED'
        )
    ),
    CONSTRAINT ck_time_off_year_end_run_completion CHECK (
        (run_status = 'RUNNING' AND completed_at IS NULL)
        OR (run_status <> 'RUNNING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_time_off_year_end_run_counts CHECK (
        (run_status = 'RUNNING')
        OR (
            run_status = 'FAILED'
            AND total_count >= success_count + failure_count + skipped_count
        )
        OR total_count = success_count + failure_count + skipped_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE time_off_year_end_run_item (
    time_off_year_end_run_item_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_off_year_end_run_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    affected_hours DECIMAL(12,2) NULL,
    result_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_detail VARCHAR(500) NULL,
    attempted_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (time_off_year_end_run_item_id),
    UNIQUE KEY uq_time_off_year_end_run_account
        (time_off_year_end_run_id, time_account_id),
    KEY ix_time_off_year_end_item_employee
        (employee_number, completed_at),
    KEY ix_time_off_year_end_item_status
        (item_status, completed_at),
    CONSTRAINT fk_time_off_year_end_item_run
        FOREIGN KEY (time_off_year_end_run_id)
        REFERENCES time_off_year_end_run (time_off_year_end_run_id),
    CONSTRAINT fk_time_off_year_end_item_account
        FOREIGN KEY (time_account_id) REFERENCES time_account (time_account_id),
    CONSTRAINT ck_time_off_year_end_item_digest
        CHECK (payload_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_time_off_year_end_item_status CHECK (
        item_status IN ('SUCCESS', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_time_off_year_end_item_hours CHECK (
        affected_hours IS NULL OR affected_hours >= 0
    ),
    CONSTRAINT ck_time_off_year_end_item_result CHECK (
        (item_status = 'SUCCESS' AND affected_hours IS NOT NULL)
        OR (item_status = 'FAILED' AND result_code IS NOT NULL)
        OR (item_status = 'SKIPPED' AND affected_hours IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

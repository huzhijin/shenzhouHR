-- V44: durable idempotency for HR annual-leave opening/adjustment writes.
--
-- The record is claimed and completed in the same InnoDB transaction as the
-- account/ledger mutation. A rolled-back mutation therefore cannot leave a
-- successful idempotency result behind, while a committed replay can return
-- without appending another immutable ledger entry.

CREATE TABLE annual_leave_balance_idempotency_record (
    annual_leave_balance_idempotency_record_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    operation VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resulting_ledger_entry_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resulting_balance_hours DECIMAL(12,2) NULL,
    resulting_row_version BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (annual_leave_balance_idempotency_record_id),
    UNIQUE KEY uq_annual_leave_balance_idempotency_key
        (principal_id, idempotency_key),
    KEY ix_annual_leave_balance_idempotency_principal
        (principal_id, created_at),
    KEY ix_annual_leave_balance_idempotency_account
        (time_account_id, created_at),
    CONSTRAINT fk_annual_leave_balance_idempotency_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_annual_leave_balance_idempotency_account
        FOREIGN KEY (time_account_id) REFERENCES time_account (time_account_id),
    CONSTRAINT fk_annual_leave_balance_idempotency_ledger
        FOREIGN KEY (resulting_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT ck_annual_leave_balance_idempotency_operation CHECK (
        operation IN ('SET_OPENING', 'ADJUST')
    ),
    CONSTRAINT ck_annual_leave_balance_idempotency_digest CHECK (
        request_digest REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_annual_leave_balance_idempotency_status CHECK (
        record_status IN ('PROCESSING', 'COMPLETED')
    ),
    CONSTRAINT ck_annual_leave_balance_idempotency_result CHECK (
        (
            record_status = 'PROCESSING'
            AND resulting_ledger_entry_id IS NULL
            AND resulting_balance_hours IS NULL
            AND resulting_row_version IS NULL
            AND completed_at IS NULL
        ) OR (
            record_status = 'COMPLETED'
            AND resulting_balance_hours IS NOT NULL
            AND resulting_row_version IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

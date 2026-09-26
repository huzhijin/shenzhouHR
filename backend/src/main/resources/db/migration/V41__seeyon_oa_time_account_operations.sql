-- V41: Seeyon OA leave reservation and overtime time-off account contract.
--
-- The OA plug-in is deliberately not granted direct DML on time_account or
-- time_account_ledger_entry. V42 exposes SQL SECURITY DEFINER routines which
-- serialize on the account row and write these immutable operation records.
--
-- payload_digest contract (used by V42): lower-case SHA-256 hex of a canonical
-- UTF-8 string. Decimal hours have exactly two digits and dates use yyyy-MM-dd.
-- The routines validate the shape and use the digest for idempotency conflict
-- detection; they do not attempt to reconstruct Java's canonical string.

-- Dedicated non-human principal for every ledger mutation made through V42.
-- auth_principal has no machine-principal code column, so this fixed UUID is
-- the durable semantic identifier `SEEYON_OA_INTEGRATION`.
INSERT INTO auth_principal (
    principal_id, employee_id, status, created_at, row_version
) VALUES (
    '41000000-0000-0000-0000-000000000001',
    NULL, 'ACTIVE', CURRENT_TIMESTAMP(6), 0
);

CREATE TABLE oa_time_account_reservation (
    reservation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'SEEYON_OA',
    source_request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_year SMALLINT UNSIGNED NOT NULL,
    business_date DATE NOT NULL,
    reserved_hours DECIMAL(12,2) NOT NULL,
    returned_hours DECIMAL(12,2) NOT NULL DEFAULT 0,
    reservation_status VARCHAR(24)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_cycle INT UNSIGNED NOT NULL DEFAULT 1,
    confirmed_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    released_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uq_oa_ta_reservation_request
        (source_system, source_request_id),
    KEY ix_oa_ta_reservation_account_status
        (time_account_id, reservation_status),
    KEY ix_oa_ta_reservation_employee_year
        (employee_id, account_type, account_year, reservation_status),
    CONSTRAINT fk_oa_ta_reservation_account_identity
        FOREIGN KEY (
            time_account_id, employee_id, employment_period_id, company_id
        ) REFERENCES time_account (
            time_account_id, employee_id, employment_period_id, company_id
        ),
    CONSTRAINT ck_oa_ta_reservation_source
        CHECK (source_system = 'SEEYON_OA'),
    CONSTRAINT ck_oa_ta_reservation_account_type
        CHECK (account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')),
    CONSTRAINT ck_oa_ta_reservation_hours CHECK (
        reserved_hours > 0
        AND MOD(reserved_hours * 100, 50) = 0
        AND returned_hours >= 0
        AND MOD(returned_hours * 100, 50) = 0
        AND returned_hours <= reserved_hours
    ),
    CONSTRAINT ck_oa_ta_reservation_status CHECK (
        reservation_status IN (
            'RESERVED', 'CONFIRMED', 'CONSUMED', 'RELEASED'
        )
    ),
    CONSTRAINT ck_oa_ta_reservation_digest
        CHECK (payload_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oa_ta_reservation_cycle CHECK (reservation_cycle > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_time_account_operation_event (
    operation_event_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    operation_cycle INT UNSIGNED NOT NULL DEFAULT 1,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    affected_hours DECIMAL(12,2) NOT NULL DEFAULT 0,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (operation_event_id),
    UNIQUE KEY uq_oa_ta_operation_event
        (operation_kind, source_request_id, source_event_id, operation_cycle),
    KEY ix_oa_ta_operation_request
        (source_request_id, recorded_at),
    CONSTRAINT ck_oa_ta_operation_kind CHECK (
        operation_kind IN (
            'LEAVE_CONFIRM', 'LEAVE_CONSUME', 'LEAVE_RELEASE',
            'LEAVE_RETURN', 'TIME_OFF_CREDIT', 'TIME_OFF_EXPIRY',
            'TIME_OFF_REVERSE'
        )
    ),
    CONSTRAINT ck_oa_ta_operation_digest
        CHECK (payload_digest REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oa_ta_operation_cycle CHECK (operation_cycle > 0),
    CONSTRAINT ck_oa_ta_operation_affected CHECK (affected_hours >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_leave_balance_return (
    leave_balance_return_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'SEEYON_OA',
    source_revocation_request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reservation_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    returned_hours DECIMAL(12,2) NOT NULL,
    business_date DATE NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expiry_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    returned_at DATETIME(6) NOT NULL,
    PRIMARY KEY (leave_balance_return_id),
    UNIQUE KEY uq_oa_leave_return_request
        (source_system, source_revocation_request_id),
    KEY ix_oa_leave_return_reservation
        (reservation_id, returned_at),
    CONSTRAINT fk_oa_leave_return_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES oa_time_account_reservation (reservation_id),
    CONSTRAINT fk_oa_leave_return_ledger
        FOREIGN KEY (return_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT fk_oa_leave_return_expiry_ledger
        FOREIGN KEY (expiry_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT ck_oa_leave_return_source
        CHECK (source_system = 'SEEYON_OA'),
    CONSTRAINT ck_oa_leave_return_hours CHECK (
        returned_hours > 0
        AND MOD(returned_hours * 100, 50) = 0
    ),
    CONSTRAINT ck_oa_leave_return_digest
        CHECK (payload_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oa_time_off_credit (
    time_off_credit_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'SEEYON_OA',
    source_overtime_line_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employment_period_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    time_account_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_year SMALLINT UNSIGNED NOT NULL,
    overtime_business_date DATE NOT NULL,
    credited_hours DECIMAL(12,2) NOT NULL,
    expires_on DATE NOT NULL,
    credit_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credit_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expiry_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    credit_reversal_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    expiry_reversal_ledger_entry_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    credited_at DATETIME(6) NOT NULL,
    reversed_at DATETIME(6) NULL,
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (time_off_credit_id),
    UNIQUE KEY uq_oa_time_off_credit_line
        (source_system, source_overtime_line_id),
    KEY ix_oa_time_off_credit_account_status
        (time_account_id, credit_status, expires_on),
    CONSTRAINT fk_oa_time_off_credit_account_identity
        FOREIGN KEY (
            time_account_id, employee_id, employment_period_id, company_id
        ) REFERENCES time_account (
            time_account_id, employee_id, employment_period_id, company_id
        ),
    CONSTRAINT fk_oa_time_off_credit_ledger
        FOREIGN KEY (credit_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT fk_oa_time_off_expiry_ledger
        FOREIGN KEY (expiry_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT fk_oa_time_off_credit_reversal
        FOREIGN KEY (credit_reversal_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT fk_oa_time_off_expiry_reversal
        FOREIGN KEY (expiry_reversal_ledger_entry_id)
        REFERENCES time_account_ledger_entry (time_account_ledger_entry_id),
    CONSTRAINT ck_oa_time_off_credit_source
        CHECK (source_system = 'SEEYON_OA'),
    CONSTRAINT ck_oa_time_off_credit_hours CHECK (
        credited_hours > 0
        AND MOD(credited_hours * 100, 50) = 0
    ),
    CONSTRAINT ck_oa_time_off_credit_year CHECK (
        account_year = YEAR(overtime_business_date)
        AND YEAR(expires_on) = account_year
        AND MONTH(expires_on) = 12
        AND DAY(expires_on) = 31
    ),
    CONSTRAINT ck_oa_time_off_credit_status CHECK (
        credit_status IN ('CREDITED', 'EXPIRED', 'REVERSED')
    ),
    CONSTRAINT ck_oa_time_off_credit_expiry_shape CHECK (
        (credit_status = 'CREDITED' AND expiry_ledger_entry_id IS NULL)
        OR (credit_status = 'EXPIRED' AND expiry_ledger_entry_id IS NOT NULL)
        OR (credit_status = 'REVERSED')
    ),
    CONSTRAINT ck_oa_time_off_credit_digest
        CHECK (payload_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- This view is the only direct balance projection intended for the OA user.
-- A negative available_hours value is intentionally not hidden; it signals
-- corrupted/manual account mutation and the V42 routines fail closed on it.
CREATE SQL SECURITY DEFINER VIEW szsc_oa_time_account_balance_v AS
SELECT
    employee.employee_number AS employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours,
    COALESCE(reservation.reserved_hours, 0.00) AS reserved_hours,
    account.balance_hours
        - COALESCE(reservation.reserved_hours, 0.00) AS available_hours
FROM time_account account
JOIN employee employee
  ON employee.employee_id = account.employee_id
 AND employee.company_id = account.company_id
LEFT JOIN (
    SELECT
        time_account_id,
        SUM(reserved_hours) AS reserved_hours
    FROM oa_time_account_reservation
    WHERE reservation_status IN ('RESERVED', 'CONFIRMED')
    GROUP BY time_account_id
) reservation
  ON reservation.time_account_id = account.time_account_id
WHERE employee.employment_status = 'ACTIVE'
  AND EXISTS (
      SELECT 1
      FROM employment_assignment employment
      WHERE employment.employee_id = account.employee_id
        AND employment.employment_period_id = account.employment_period_id
        AND employment.current_version_marker = 1
        AND employment.record_status = 'ACTIVE'
        AND employment.effective_from <= CURRENT_TIMESTAMP(6)
        AND (
            employment.effective_to IS NULL
            OR employment.effective_to > CURRENT_TIMESTAMP(6)
        )
  );

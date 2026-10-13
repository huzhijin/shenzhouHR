-- V42: atomic SQL SECURITY DEFINER API for the Seeyon OA plug-in.
--
-- Public procedure result-set columns (exact order and names):
--   source_request_id, operation_status, account_type, account_year, affected_hours,
--   balance_hours, reserved_hours, available_hours
--
-- All mutating procedures own their transaction and serialize on the target
-- time_account row. Business rejection uses SQLSTATE 45000 and an ASCII
-- SZSC_* MESSAGE_TEXT. A caller must treat any SQL exception or missing result
-- row as failure. Do not grant OA direct DML on any base table.
--
-- Canonical digest examples (UTF-8, SHA-256 lower-case hex):
-- RESERVE|formId|employeeNo|accountType|year|hours(2dp)|yyyy-MM-dd
-- CONFIRM|formId|eventId
-- CONSUME|formId|eventId
-- RELEASE|formId|eventId
-- RETURN|revocationFormId|originalFormId|hours(2dp)|yyyy-MM-dd|eventId
-- CREDIT|lineId|employeeNo|TIME_OFF|year|hours(2dp)|yyyy-MM-dd|eventId
-- EXPIRY|employeeNo|year|eventId
-- REVERSE|lineId|eventId
-- CAN_REVERSE|lineId
-- The database validates digest shape and idempotency equality; Java owns the
-- canonical byte-string construction.

DELIMITER $$

CREATE PROCEDURE szsc_oa_private_resolve_account(
    IN p_employee_number VARCHAR(128),
    IN p_account_type VARCHAR(32),
    IN p_account_year SMALLINT UNSIGNED,
    IN p_business_date DATE,
    IN p_create_time_off BOOLEAN,
    OUT o_employee_id VARCHAR(36),
    OUT o_employment_period_id VARCHAR(36),
    OUT o_company_id VARCHAR(36),
    OUT o_time_account_id VARCHAR(36),
    OUT o_policy_version_id VARCHAR(96)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_employee_count INT DEFAULT 0;
    DECLARE v_employee_status VARCHAR(32);
    DECLARE v_employment_count INT DEFAULT 0;
    DECLARE v_account_count INT DEFAULT 0;
    DECLARE v_policy_count INT DEFAULT 0;
    DECLARE v_account_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);

    SET o_employee_id = NULL;
    SET o_employment_period_id = NULL;
    SET o_company_id = NULL;
    SET o_time_account_id = NULL;
    SET o_policy_version_id = NULL;

    IF p_employee_number IS NULL OR CHAR_LENGTH(TRIM(p_employee_number)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYEE_NUMBER_REQUIRED';
    END IF;
    IF p_account_type NOT IN ('ANNUAL_LEAVE', 'TIME_OFF') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_TYPE_UNSUPPORTED';
    END IF;
    IF p_account_year IS NULL OR p_business_date IS NULL
       OR p_account_year <> YEAR(p_business_date) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_YEAR_MISMATCH';
    END IF;

    -- OA passes org_member.code, whose signed contract is employee.employee_number.
    -- The legacy schema only enforces uniqueness inside a company, therefore
    -- the OA boundary explicitly requires global uniqueness and fails closed.
    SELECT COUNT(*), MIN(employee.employee_id), MIN(employee.company_id),
           MIN(employee.employment_status)
      INTO v_employee_count, o_employee_id, o_company_id, v_employee_status
    FROM employee employee
    WHERE employee.employee_number = p_employee_number;

    IF v_employee_count <> 1 OR v_employee_status <> 'ACTIVE' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYEE_NOT_UNIQUE_OR_INACTIVE';
    END IF;

    -- There must be one, and only one, current version of an employment period
    -- which is effective on the business date. Row limiting is intentionally
    -- absent so corrupt overlapping periods fail closed.
    SELECT COUNT(*), MIN(employment.employment_period_id)
      INTO v_employment_count, o_employment_period_id
    FROM employment_assignment employment
    WHERE employment.employee_id = o_employee_id
      AND employment.current_version_marker = 1
      AND employment.record_status = 'ACTIVE'
      AND employment.effective_from < DATE_ADD(p_business_date, INTERVAL 1 DAY)
      AND (
          employment.effective_to IS NULL
          OR employment.effective_to > CAST(p_business_date AS DATETIME)
      );

    IF v_employment_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYMENT_NOT_UNIQUE_OR_INACTIVE';
    END IF;

    IF p_create_time_off AND p_account_type <> 'TIME_OFF' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_AUTO_CREATE_FORBIDDEN';
    END IF;

    -- Resolve and lock an existing account before policy lookup. Published
    -- policy resolution is a prerequisite for first-time TIME_OFF creation,
    -- not for using an already materialized account whose policy snapshot is
    -- carried by time_account.policy_version_id.
    SELECT COUNT(*), MIN(account.time_account_id),
           MIN(account.policy_version_id)
      INTO v_account_count, o_time_account_id, o_policy_version_id
    FROM time_account account
    WHERE account.employee_id = o_employee_id
      AND account.employment_period_id = o_employment_period_id
      AND account.company_id = o_company_id
      AND account.account_type = p_account_type
      AND account.account_year = p_account_year
    FOR UPDATE;

    IF v_account_count > 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING';
    END IF;

    IF v_account_count = 0 THEN
        IF NOT COALESCE(p_create_time_off, FALSE) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING';
        END IF;

        -- A company revision overrides the system revision. More than one
        -- applicable published revision at either precedence is corruption.
        SELECT COUNT(*), MIN(policy.leave_policy_revision_id)
          INTO v_policy_count, o_policy_version_id
        FROM leave_policy_revision policy
        JOIN leave_type type
          ON type.leave_type_id = policy.leave_type_id
         AND type.leave_code = 'TIME_OFF'
         AND type.active = TRUE
        WHERE policy.scope_type = 'COMPANY'
          AND policy.company_id = o_company_id
          AND policy.status = 'PUBLISHED'
          AND policy.effective_from <= p_business_date
          AND (policy.effective_to IS NULL OR policy.effective_to > p_business_date);

        IF v_policy_count > 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_OFF_POLICY_NOT_UNIQUE';
        END IF;

        IF v_policy_count = 0 THEN
            SELECT COUNT(*), MIN(policy.leave_policy_revision_id)
              INTO v_policy_count, o_policy_version_id
            FROM leave_policy_revision policy
            JOIN leave_type type
              ON type.leave_type_id = policy.leave_type_id
             AND type.leave_code = 'TIME_OFF'
             AND type.active = TRUE
            WHERE policy.scope_type = 'SYSTEM'
              AND policy.company_id IS NULL
              AND policy.status = 'PUBLISHED'
              AND policy.effective_from <= p_business_date
              AND (policy.effective_to IS NULL OR policy.effective_to > p_business_date);
        END IF;

        IF v_policy_count <> 1 OR o_policy_version_id IS NULL THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_OFF_POLICY_NOT_UNIQUE';
        END IF;

        SET o_time_account_id = UUID();
        INSERT INTO time_account (
            time_account_id, employee_id, employment_period_id, company_id,
            account_type, account_year, balance_hours, policy_version_id,
            row_version, created_at, updated_at
        ) VALUES (
            o_time_account_id, o_employee_id, o_employment_period_id,
            o_company_id, 'TIME_OFF', p_account_year, 0.00,
            o_policy_version_id, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
        ) ON DUPLICATE KEY UPDATE
            time_account_id = time_account_id;

        -- The unique account identity serializes concurrent first credits.
        -- This current/locking read sees the winner even under REPEATABLE READ.
        SELECT COUNT(*), MIN(account.time_account_id),
               MIN(account.policy_version_id)
          INTO v_account_count, o_time_account_id, o_policy_version_id
        FROM time_account account
        WHERE account.employee_id = o_employee_id
          AND account.employment_period_id = o_employment_period_id
          AND account.company_id = o_company_id
          AND account.account_type = p_account_type
          AND account.account_year = p_account_year
        FOR UPDATE;
    END IF;

    IF v_account_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING';
    END IF;

    -- The account row is the serialization boundary for balance, holds and
    -- the per-account ledger sequence.
    SELECT account.policy_version_id, account.balance_hours
      INTO o_policy_version_id, v_account_balance
    FROM time_account account
    WHERE account.time_account_id = o_time_account_id
    FOR UPDATE;

    SELECT COALESCE(SUM(entry.amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry entry
    WHERE entry.time_account_id = o_time_account_id;

    IF v_account_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;

    SELECT COALESCE(SUM(reservation.reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation reservation
    WHERE reservation.time_account_id = o_time_account_id
      AND reservation.reservation_status IN ('RESERVED', 'CONFIRMED');

    IF v_account_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;
END$$

CREATE PROCEDURE szsc_oa_private_emit_balance(
    IN p_source_request_id VARCHAR(128),
    IN p_status VARCHAR(32),
    IN p_affected_hours DECIMAL(12,2),
    IN p_time_account_id VARCHAR(36)
)
SQL SECURITY DEFINER
READS SQL DATA
BEGIN
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);

    SELECT balance_hours
      INTO v_balance
    FROM time_account
    WHERE time_account_id = p_time_account_id;
    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = p_time_account_id;
    SELECT COALESCE(SUM(reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation
    WHERE time_account_id = p_time_account_id
      AND reservation_status IN ('RESERVED', 'CONFIRMED');

    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;
    IF v_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;

    SELECT
        p_source_request_id AS source_request_id,
        p_status AS operation_status,
        account.account_type AS account_type,
        account.account_year AS account_year,
        p_affected_hours AS affected_hours,
        account.balance_hours AS balance_hours,
        v_reserved AS reserved_hours,
        account.balance_hours - v_reserved AS available_hours
    FROM time_account account
    WHERE account.time_account_id = p_time_account_id;
END$$

CREATE PROCEDURE szsc_oa_leave_reserve(
    IN p_source_request_id VARCHAR(128),
    IN p_employee_number VARCHAR(128),
    IN p_account_type VARCHAR(32),
    IN p_account_year SMALLINT UNSIGNED,
    IN p_hours DECIMAL(12,2),
    IN p_business_date DATE,
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_existing_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_existing_employee_number VARCHAR(128);
    DECLARE v_existing_account_type VARCHAR(32);
    DECLARE v_existing_year SMALLINT UNSIGNED;
    DECLARE v_existing_hours DECIMAL(12,2);
    DECLARE v_existing_business_date DATE;
    DECLARE v_existing_digest CHAR(64);
    DECLARE v_existing_status VARCHAR(24);
    DECLARE v_employee_id VARCHAR(36);
    DECLARE v_employment_period_id VARCHAR(36);
    DECLARE v_company_id VARCHAR(36);
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_source_request_id IS NULL OR CHAR_LENGTH(TRIM(p_source_request_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_REQUEST_ID_REQUIRED';
    END IF;
    IF p_hours IS NULL OR p_hours <= 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_POSITIVE';
    END IF;
    IF MOD(p_hours * 100, 50) <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_HALF_HOUR_MULTIPLE';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;

    SELECT reservation_id, employee_number, account_type, account_year,
           reserved_hours, business_date, payload_digest, reservation_status,
           time_account_id
      INTO v_existing_id, v_existing_employee_number, v_existing_account_type,
           v_existing_year, v_existing_hours, v_existing_business_date,
           v_existing_digest, v_existing_status, v_time_account_id
    FROM oa_time_account_reservation
    WHERE source_system = 'SEEYON_OA'
      AND source_request_id = p_source_request_id
    FOR UPDATE;

    IF v_existing_id IS NOT NULL
       AND v_existing_status IN ('RESERVED', 'CONFIRMED') THEN
        IF NOT (BINARY v_existing_employee_number
                    <=> BINARY p_employee_number)
           OR NOT (v_existing_account_type <=> p_account_type)
           OR NOT (v_existing_year <=> p_account_year)
           OR NOT (v_existing_hours <=> p_hours)
           OR NOT (v_existing_business_date <=> p_business_date)
           OR NOT (v_existing_digest <=> p_payload_digest) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;

        SELECT balance_hours
          INTO v_balance
        FROM time_account
        WHERE time_account_id = v_time_account_id
        FOR UPDATE;
        SELECT COALESCE(SUM(amount_hours), 0.00)
          INTO v_ledger_balance
        FROM time_account_ledger_entry
        WHERE time_account_id = v_time_account_id;
        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');
        IF v_balance <> v_ledger_balance THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
        END IF;
        IF v_balance < v_reserved THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
        END IF;
        COMMIT;
        CALL szsc_oa_private_emit_balance(
            p_source_request_id, v_existing_status, p_hours, v_time_account_id
        );
    ELSE
        IF v_existing_status = 'CONSUMED' THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_CONSUMED_RESERVATION_CANNOT_RESUBMIT';
        END IF;

        CALL szsc_oa_private_resolve_account(
            p_employee_number, p_account_type, p_account_year,
            p_business_date, FALSE,
            v_employee_id, v_employment_period_id, v_company_id,
            v_time_account_id, v_policy_version_id
        );

        IF p_account_type = 'TIME_OFF'
           AND CURRENT_DATE() > STR_TO_DATE(
               CONCAT(p_account_year, '-12-31'), '%Y-%m-%d'
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_OFF_ACCOUNT_EXPIRED';
        END IF;

        SELECT balance_hours
          INTO v_balance
        FROM time_account
        WHERE time_account_id = v_time_account_id;

        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');

        IF v_balance < v_reserved
           OR v_balance - v_reserved < p_hours THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_INSUFFICIENT_AVAILABLE_BALANCE';
        END IF;

        IF v_existing_status = 'RELEASED' THEN
            -- A withdrawn/rejected form may be corrected and submitted again.
            -- Reuse its stable OA request identity, but perform a fresh atomic
            -- availability check and replace every immutable reservation input.
            UPDATE oa_time_account_reservation
            SET employee_number = p_employee_number,
                employee_id = v_employee_id,
                employment_period_id = v_employment_period_id,
                company_id = v_company_id,
                time_account_id = v_time_account_id,
                account_type = p_account_type,
                account_year = p_account_year,
                business_date = p_business_date,
                reserved_hours = p_hours,
                returned_hours = 0.00,
                reservation_status = 'RESERVED',
                payload_digest = p_payload_digest,
                reservation_cycle = reservation_cycle + 1,
                confirmed_at = NULL,
                consumed_at = NULL,
                released_at = NULL,
                updated_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE reservation_id = v_existing_id;
        ELSE
            INSERT INTO oa_time_account_reservation (
                reservation_id, source_system, source_request_id, employee_number,
                employee_id, employment_period_id, company_id, time_account_id,
                account_type, account_year, business_date, reserved_hours,
                returned_hours, reservation_status, payload_digest,
                reservation_cycle, confirmed_at, consumed_at, released_at,
                created_at, updated_at, row_version
            ) VALUES (
                UUID(), 'SEEYON_OA', p_source_request_id, p_employee_number,
                v_employee_id, v_employment_period_id, v_company_id,
                v_time_account_id, p_account_type, p_account_year,
                p_business_date, p_hours, 0.00, 'RESERVED', p_payload_digest,
                1, NULL, NULL, NULL,
                CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0
            );
        END IF;

        COMMIT;
        CALL szsc_oa_private_emit_balance(
            p_source_request_id, 'RESERVED', p_hours, v_time_account_id
        );
    END IF;
END$$

CREATE PROCEDURE szsc_oa_leave_confirm(
    IN p_source_request_id VARCHAR(128),
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_reservation_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_hours DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    DECLARE v_reservation_cycle INT UNSIGNED;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_source_request_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_request_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_REQUEST_ID_REQUIRED';
    END IF;
    IF p_source_event_id IS NULL OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_EVENT_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;
    SELECT reservation_id, time_account_id, reserved_hours,
           reservation_status, reservation_cycle
      INTO v_reservation_id, v_time_account_id, v_hours, v_status,
           v_reservation_cycle
    FROM oa_time_account_reservation
    WHERE source_system = 'SEEYON_OA'
      AND source_request_id = p_source_request_id
    FOR UPDATE;

    IF v_reservation_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_RESERVATION_NOT_FOUND';
    END IF;

    SELECT balance_hours
      INTO v_balance
    FROM time_account
    WHERE time_account_id = v_time_account_id
    FOR UPDATE;
    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = v_time_account_id;
    SELECT COALESCE(SUM(reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation
    WHERE time_account_id = v_time_account_id
      AND reservation_status IN ('RESERVED', 'CONFIRMED');
    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;
    IF v_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'LEAVE_CONFIRM'
      AND source_request_id = p_source_request_id
      AND source_event_id = p_source_event_id
      AND operation_cycle = v_reservation_cycle
    FOR UPDATE;

    IF v_event_digest IS NOT NULL THEN
        IF v_event_digest <> p_payload_digest THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
    ELSE
        IF v_status = 'RELEASED' THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_RESERVATION_ALREADY_RELEASED';
        END IF;
        IF v_status = 'RESERVED' THEN
            UPDATE oa_time_account_reservation
            SET reservation_status = 'CONFIRMED',
                confirmed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE reservation_id = v_reservation_id;
            SET v_status = 'CONFIRMED';
        END IF;
        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, operation_cycle, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'LEAVE_CONFIRM', p_source_request_id,
            p_source_event_id, v_reservation_cycle, p_payload_digest, v_status,
            v_hours, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        p_source_request_id, v_status, v_hours, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_leave_consume(
    IN p_source_request_id VARCHAR(128),
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_reservation_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_hours DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    DECLARE v_reservation_cycle INT UNSIGNED;
    DECLARE v_business_date DATE;
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_ledger_id VARCHAR(36);
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_source_request_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_request_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_REQUEST_ID_REQUIRED';
    END IF;
    IF p_source_event_id IS NULL OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_EVENT_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;
    SELECT reservation_id, time_account_id, reserved_hours,
           reservation_status, business_date, reservation_cycle
      INTO v_reservation_id, v_time_account_id, v_hours,
           v_status, v_business_date, v_reservation_cycle
    FROM oa_time_account_reservation
    WHERE source_system = 'SEEYON_OA'
      AND source_request_id = p_source_request_id
    FOR UPDATE;

    IF v_reservation_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_RESERVATION_NOT_FOUND';
    END IF;

    SELECT balance_hours, policy_version_id
      INTO v_balance, v_policy_version_id
    FROM time_account
    WHERE time_account_id = v_time_account_id
    FOR UPDATE;

    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = v_time_account_id;
    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;
    SELECT COALESCE(SUM(reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation
    WHERE time_account_id = v_time_account_id
      AND reservation_status IN ('RESERVED', 'CONFIRMED');
    IF v_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'LEAVE_CONSUME'
      AND source_request_id = p_source_request_id
      AND source_event_id = p_source_event_id
      AND operation_cycle = v_reservation_cycle
    FOR UPDATE;

    IF v_event_digest IS NOT NULL THEN
        IF v_event_digest <> p_payload_digest THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
    ELSE
        IF v_status = 'RESERVED' THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_RESERVATION_NOT_CONFIRMED';
        ELSEIF v_status = 'RELEASED' THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_RESERVATION_ALREADY_RELEASED';
        ELSEIF v_status = 'CONFIRMED' THEN
            IF v_balance < v_hours THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
            END IF;

            SELECT COALESCE(MAX(sequence_no), 0) + 1
              INTO v_sequence
            FROM time_account_ledger_entry
            WHERE time_account_id = v_time_account_id;

            SET v_ledger_id = UUID();
            INSERT INTO time_account_ledger_entry (
                time_account_ledger_entry_id, time_account_id, sequence_no,
                entry_type, amount_hours, source_type, source_id,
                business_date, effective_from, expires_on,
                policy_version_id, period_version_id, close_snapshot_id,
                request_id, reversal_of_entry_id, actor_id, occurred_at
            ) VALUES (
                v_ledger_id, v_time_account_id, v_sequence,
                'USE', -v_hours, 'SEEYON_OA_LEAVE',
                LEFT(p_source_request_id, 128), v_business_date,
                v_business_date, NULL, v_policy_version_id, NULL, NULL,
                CONCAT('SZSC:LEAVE_USE:', SHA2(p_source_request_id, 256)),
                NULL, '41000000-0000-0000-0000-000000000001',
                CURRENT_TIMESTAMP(6)
            );

            UPDATE time_account
            SET balance_hours = balance_hours - v_hours,
                row_version = row_version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE time_account_id = v_time_account_id;

            UPDATE oa_time_account_reservation
            SET reservation_status = 'CONSUMED',
                consumed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE reservation_id = v_reservation_id;
            SET v_status = 'CONSUMED';
        END IF;

        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, operation_cycle, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'LEAVE_CONSUME', p_source_request_id,
            p_source_event_id, v_reservation_cycle, p_payload_digest, v_status,
            v_hours, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        p_source_request_id, v_status, v_hours, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_leave_release(
    IN p_source_request_id VARCHAR(128),
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_reservation_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_hours DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    DECLARE v_reservation_cycle INT UNSIGNED;
    DECLARE v_account_type VARCHAR(32);
    DECLARE v_account_year SMALLINT UNSIGNED;
    DECLARE v_business_date DATE;
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_source_request_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_request_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_REQUEST_ID_REQUIRED';
    END IF;
    IF p_source_event_id IS NULL OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_EVENT_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;
    SELECT reservation_id, time_account_id, reserved_hours,
           reservation_status, account_type, account_year, business_date,
           reservation_cycle
      INTO v_reservation_id, v_time_account_id, v_hours, v_status,
           v_account_type, v_account_year, v_business_date,
           v_reservation_cycle
    FROM oa_time_account_reservation
    WHERE source_system = 'SEEYON_OA'
      AND source_request_id = p_source_request_id
    FOR UPDATE;

    IF v_reservation_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_RESERVATION_NOT_FOUND';
    END IF;

    SELECT balance_hours, policy_version_id
      INTO v_balance, v_policy_version_id
    FROM time_account
    WHERE time_account_id = v_time_account_id
    FOR UPDATE;

    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = v_time_account_id;
    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;
    SELECT COALESCE(SUM(reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation
    WHERE time_account_id = v_time_account_id
      AND reservation_status IN ('RESERVED', 'CONFIRMED');
    IF v_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'LEAVE_RELEASE'
      AND source_request_id = p_source_request_id
      AND source_event_id = p_source_event_id
      AND operation_cycle = v_reservation_cycle
    FOR UPDATE;

    IF v_event_digest IS NOT NULL THEN
        IF v_event_digest <> p_payload_digest THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
    ELSE
        IF v_status = 'CONSUMED' THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_CONSUMED_RESERVATION_NEEDS_RETURN';
        ELSEIF v_status IN ('RESERVED', 'CONFIRMED') THEN
            -- A year-end expiry preserves active holds. Releasing one in a
            -- later year must expire it in this same transaction, otherwise
            -- rejected leave would resurrect expired TIME_OFF balance.
            IF v_account_type = 'TIME_OFF'
               AND CURRENT_DATE() > STR_TO_DATE(
                   CONCAT(v_account_year, '-12-31'), '%Y-%m-%d'
               ) THEN
                IF v_balance < v_hours THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
                END IF;
                SELECT COALESCE(MAX(sequence_no), 0) + 1
                  INTO v_sequence
                FROM time_account_ledger_entry
                WHERE time_account_id = v_time_account_id;
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    UUID(), v_time_account_id, v_sequence,
                    'EXPIRY', -v_hours, 'SEEYON_OA_RELEASE',
                    LEFT(p_source_request_id, 128), CURRENT_DATE(),
                    CURRENT_DATE(), STR_TO_DATE(
                        CONCAT(v_account_year, '-12-31'), '%Y-%m-%d'
                    ), v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:RELEASE_EXPIRY:', SHA2(
                        CONCAT(p_source_request_id, '|', p_source_event_id), 256
                    )),
                    NULL, '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
                UPDATE time_account
                SET balance_hours = balance_hours - v_hours,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE time_account_id = v_time_account_id;
            END IF;

            UPDATE oa_time_account_reservation
            SET reservation_status = 'RELEASED',
                released_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE reservation_id = v_reservation_id;
            SET v_status = 'RELEASED';
        END IF;

        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, operation_cycle, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'LEAVE_RELEASE', p_source_request_id,
            p_source_event_id, v_reservation_cycle, p_payload_digest, v_status,
            v_hours, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        p_source_request_id, v_status, v_hours, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_leave_return(
    IN p_original_request_id VARCHAR(128),
    IN p_revocation_request_id VARCHAR(128),
    IN p_source_event_id VARCHAR(128),
    IN p_hours DECIMAL(12,2),
    IN p_business_date DATE,
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_reservation_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_reserved_hours DECIMAL(12,2);
    DECLARE v_returned_hours DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    DECLARE v_account_type VARCHAR(32);
    DECLARE v_account_year SMALLINT UNSIGNED;
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_return_ledger_id VARCHAR(36);
    DECLARE v_expiry_ledger_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_existing_return_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_existing_original_id VARCHAR(128);
    DECLARE v_existing_hours DECIMAL(12,2);
    DECLARE v_existing_business_date DATE;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;
    DECLARE v_is_existing BOOLEAN DEFAULT FALSE;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_original_request_id IS NULL
       OR CHAR_LENGTH(TRIM(p_original_request_id)) = 0
       OR p_revocation_request_id IS NULL
       OR CHAR_LENGTH(TRIM(p_revocation_request_id)) = 0
       OR p_source_event_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_ID_REQUIRED';
    END IF;
    IF p_hours IS NULL OR p_hours <= 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_POSITIVE';
    END IF;
    IF MOD(p_hours * 100, 50) <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_HALF_HOUR_MULTIPLE';
    END IF;
    IF p_business_date IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BUSINESS_DATE_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;

    SELECT reservation_id, time_account_id, reserved_hours, returned_hours,
           reservation_status, account_type, account_year
      INTO v_reservation_id, v_time_account_id, v_reserved_hours,
           v_returned_hours, v_status, v_account_type, v_account_year
    FROM oa_time_account_reservation
    WHERE source_system = 'SEEYON_OA'
      AND source_request_id = p_original_request_id
    FOR UPDATE;

    -- Historical OA leave which predates the reservation cutover is never
    -- guessed into a RETURN. HR must reconcile it through a signed adjustment.
    IF v_reservation_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ORIGINAL_RESERVATION_NOT_FOUND';
    END IF;
    IF YEAR(p_business_date) <> v_account_year THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_YEAR_MISMATCH';
    END IF;

    SELECT balance_hours, policy_version_id
      INTO v_balance, v_policy_version_id
    FROM time_account
    WHERE time_account_id = v_time_account_id
    FOR UPDATE;

    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = v_time_account_id;
    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;
    SELECT COALESCE(SUM(reserved_hours), 0.00)
      INTO v_reserved
    FROM oa_time_account_reservation
    WHERE time_account_id = v_time_account_id
      AND reservation_status IN ('RESERVED', 'CONFIRMED');
    IF v_balance < v_reserved THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
    END IF;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'LEAVE_RETURN'
      AND source_request_id = p_revocation_request_id
      AND source_event_id = p_source_event_id
    FOR UPDATE;

    IF v_event_digest IS NOT NULL
       AND v_event_digest <> p_payload_digest THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
    END IF;

    SELECT return_record.leave_balance_return_id,
           reservation.source_request_id,
           return_record.returned_hours,
           return_record.business_date
      INTO v_existing_return_id, v_existing_original_id,
           v_existing_hours, v_existing_business_date
    FROM oa_leave_balance_return return_record
    JOIN oa_time_account_reservation reservation
      ON reservation.reservation_id = return_record.reservation_id
    WHERE return_record.source_system = 'SEEYON_OA'
      AND return_record.source_revocation_request_id = p_revocation_request_id
    FOR UPDATE;

    IF v_existing_return_id IS NOT NULL THEN
        IF NOT (BINARY v_existing_original_id
                    <=> BINARY p_original_request_id)
           OR NOT (v_existing_hours <=> p_hours)
           OR NOT (v_existing_business_date <=> p_business_date) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
        SET v_is_existing = TRUE;
    END IF;

    IF v_event_digest IS NULL THEN
        IF NOT v_is_existing THEN
            IF v_status <> 'CONSUMED' THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'SZSC_ORIGINAL_LEAVE_NOT_CONSUMED';
            END IF;
            IF v_returned_hours + p_hours > v_reserved_hours THEN
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'SZSC_RETURN_EXCEEDS_CONSUMED_HOURS';
            END IF;

            SELECT COALESCE(MAX(sequence_no), 0) + 1
              INTO v_sequence
            FROM time_account_ledger_entry
            WHERE time_account_id = v_time_account_id;

            SET v_return_ledger_id = UUID();
            INSERT INTO time_account_ledger_entry (
                time_account_ledger_entry_id, time_account_id, sequence_no,
                entry_type, amount_hours, source_type, source_id,
                business_date, effective_from, expires_on,
                policy_version_id, period_version_id, close_snapshot_id,
                request_id, reversal_of_entry_id, actor_id, occurred_at
            ) VALUES (
                v_return_ledger_id, v_time_account_id, v_sequence,
                'RETURN', p_hours, 'SEEYON_OA_RETURN',
                LEFT(p_revocation_request_id, 128), p_business_date,
                p_business_date,
                CASE WHEN v_account_type = 'TIME_OFF' THEN STR_TO_DATE(
                    CONCAT(v_account_year, '-12-31'), '%Y-%m-%d'
                ) ELSE NULL END,
                v_policy_version_id, NULL, NULL,
                CONCAT('SZSC:LEAVE_RETURN:', SHA2(p_revocation_request_id, 256)),
                NULL, '41000000-0000-0000-0000-000000000001',
                CURRENT_TIMESTAMP(6)
            );

            -- Returned TIME_OFF from an expired account is auditable but must
            -- never become spendable again.
            IF v_account_type = 'TIME_OFF'
               AND CURRENT_DATE() > STR_TO_DATE(
                   CONCAT(v_account_year, '-12-31'), '%Y-%m-%d'
               ) THEN
                SET v_sequence = v_sequence + 1;
                SET v_expiry_ledger_id = UUID();
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    v_expiry_ledger_id, v_time_account_id, v_sequence,
                    'EXPIRY', -p_hours, 'SEEYON_OA_RETURN',
                    LEFT(p_revocation_request_id, 128), p_business_date,
                    p_business_date, STR_TO_DATE(
                        CONCAT(v_account_year, '-12-31'), '%Y-%m-%d'
                    ), v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:RETURN_EXPIRY:', SHA2(p_revocation_request_id, 256)),
                    NULL, '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
                UPDATE time_account
                SET row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE time_account_id = v_time_account_id;
            ELSE
                UPDATE time_account
                SET balance_hours = balance_hours + p_hours,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE time_account_id = v_time_account_id;
            END IF;

            UPDATE oa_time_account_reservation
            SET returned_hours = returned_hours + p_hours,
                updated_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE reservation_id = v_reservation_id;

            INSERT INTO oa_leave_balance_return (
                leave_balance_return_id, source_system,
                source_revocation_request_id, reservation_id,
                returned_hours, business_date, payload_digest,
                return_ledger_entry_id, expiry_ledger_entry_id, returned_at
            ) VALUES (
                UUID(), 'SEEYON_OA', p_revocation_request_id,
                v_reservation_id, p_hours, p_business_date, p_payload_digest,
                v_return_ledger_id, v_expiry_ledger_id, CURRENT_TIMESTAMP(6)
            );
        END IF;

        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'LEAVE_RETURN', p_revocation_request_id,
            p_source_event_id, p_payload_digest, 'RETURNED',
            p_hours, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        p_revocation_request_id, 'RETURNED', p_hours, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_time_off_credit(
    IN p_overtime_line_id VARCHAR(128),
    IN p_employee_number VARCHAR(128),
    IN p_account_year SMALLINT UNSIGNED,
    IN p_hours DECIMAL(12,2),
    IN p_business_date DATE,
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_credit_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_existing_employee_number VARCHAR(128);
    DECLARE v_existing_year SMALLINT UNSIGNED;
    DECLARE v_existing_hours DECIMAL(12,2);
    DECLARE v_existing_date DATE;
    DECLARE v_status VARCHAR(24);
    DECLARE v_employee_id VARCHAR(36);
    DECLARE v_employment_period_id VARCHAR(36);
    DECLARE v_company_id VARCHAR(36);
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_credit_ledger_id VARCHAR(36);
    DECLARE v_expiry_ledger_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_expires_on DATE;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;
    DECLARE v_reverse_event_count INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_overtime_line_id IS NULL OR CHAR_LENGTH(TRIM(p_overtime_line_id)) = 0
       OR p_source_event_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_ID_REQUIRED';
    END IF;
    IF p_employee_number IS NULL
       OR CHAR_LENGTH(TRIM(p_employee_number)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYEE_NUMBER_REQUIRED';
    END IF;
    IF p_hours IS NULL OR p_hours <= 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_POSITIVE';
    END IF;
    IF MOD(p_hours * 100, 50) <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_HOURS_MUST_BE_HALF_HOUR_MULTIPLE';
    END IF;
    IF p_business_date IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BUSINESS_DATE_REQUIRED';
    END IF;
    IF p_account_year IS NULL
       OR p_account_year <> YEAR(p_business_date) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_YEAR_MISMATCH';
    END IF;
    IF p_business_date > CURRENT_DATE() THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_FUTURE_OVERTIME_CANNOT_CREDIT';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    SET v_expires_on = STR_TO_DATE(
        CONCAT(p_account_year, '-12-31'), '%Y-%m-%d'
    );
    START TRANSACTION;

    SELECT time_off_credit_id, employee_number, account_year,
           credited_hours, overtime_business_date, credit_status,
           time_account_id
      INTO v_credit_id, v_existing_employee_number, v_existing_year,
           v_existing_hours, v_existing_date, v_status, v_time_account_id
    FROM oa_time_off_credit
    WHERE source_system = 'SEEYON_OA'
      AND source_overtime_line_id = p_overtime_line_id
    FOR UPDATE;

    IF v_credit_id IS NOT NULL THEN
        IF NOT (BINARY v_existing_employee_number
                    <=> BINARY p_employee_number)
           OR NOT (v_existing_year <=> p_account_year)
           OR NOT (v_existing_hours <=> p_hours)
           OR NOT (v_existing_date <=> p_business_date) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;

        SELECT balance_hours, policy_version_id
          INTO v_balance, v_policy_version_id
        FROM time_account
        WHERE time_account_id = v_time_account_id
        FOR UPDATE;
        SELECT COALESCE(SUM(amount_hours), 0.00)
          INTO v_ledger_balance
        FROM time_account_ledger_entry
        WHERE time_account_id = v_time_account_id;
        IF v_balance <> v_ledger_balance THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
        END IF;
        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');
        IF v_balance < v_reserved THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
        END IF;
    ELSE
        -- A completed cancellation is a tombstone for the overtime line.
        -- A delayed approval callback must not resurrect spendable time off.
        SELECT COUNT(*)
          INTO v_reverse_event_count
        FROM oa_time_account_operation_event
        WHERE operation_kind = 'TIME_OFF_REVERSE'
          AND source_request_id = p_overtime_line_id
        FOR UPDATE;
        IF v_reverse_event_count > 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_OFF_ALREADY_REVERSED';
        END IF;

        CALL szsc_oa_private_resolve_account(
            p_employee_number, 'TIME_OFF', p_account_year,
            p_business_date, TRUE,
            v_employee_id, v_employment_period_id, v_company_id,
            v_time_account_id, v_policy_version_id
        );

        SELECT balance_hours
          INTO v_balance
        FROM time_account
        WHERE time_account_id = v_time_account_id;

        SELECT COALESCE(MAX(sequence_no), 0) + 1
          INTO v_sequence
        FROM time_account_ledger_entry
        WHERE time_account_id = v_time_account_id;

        SET v_credit_ledger_id = UUID();
        INSERT INTO time_account_ledger_entry (
            time_account_ledger_entry_id, time_account_id, sequence_no,
            entry_type, amount_hours, source_type, source_id,
            business_date, effective_from, expires_on,
            policy_version_id, period_version_id, close_snapshot_id,
            request_id, reversal_of_entry_id, actor_id, occurred_at
        ) VALUES (
            v_credit_ledger_id, v_time_account_id, v_sequence,
            'OVERTIME_CREDIT', p_hours, 'SEEYON_OA_OVERTIME',
            LEFT(p_overtime_line_id, 128), p_business_date,
            p_business_date, v_expires_on, v_policy_version_id, NULL, NULL,
            CONCAT('SZSC:OT_CREDIT:', SHA2(p_overtime_line_id, 256)),
            NULL, '41000000-0000-0000-0000-000000000001',
            CURRENT_TIMESTAMP(6)
        );

        -- Approval after the earning year must never create a transient usable
        -- balance. Credit and expiry are persisted in this one transaction.
        IF CURRENT_DATE() > v_expires_on THEN
            SET v_sequence = v_sequence + 1;
            SET v_expiry_ledger_id = UUID();
            INSERT INTO time_account_ledger_entry (
                time_account_ledger_entry_id, time_account_id, sequence_no,
                entry_type, amount_hours, source_type, source_id,
                business_date, effective_from, expires_on,
                policy_version_id, period_version_id, close_snapshot_id,
                request_id, reversal_of_entry_id, actor_id, occurred_at
            ) VALUES (
                v_expiry_ledger_id, v_time_account_id, v_sequence,
                'EXPIRY', -p_hours, 'SEEYON_OA_OVERTIME',
                LEFT(p_overtime_line_id, 128), p_business_date,
                p_business_date, v_expires_on, v_policy_version_id, NULL, NULL,
                CONCAT('SZSC:OT_EXPIRY:', SHA2(p_overtime_line_id, 256)),
                NULL, '41000000-0000-0000-0000-000000000001',
                CURRENT_TIMESTAMP(6)
            );
            UPDATE time_account
            SET row_version = row_version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE time_account_id = v_time_account_id;
            SET v_status = 'EXPIRED';
        ELSE
            UPDATE time_account
            SET balance_hours = balance_hours + p_hours,
                row_version = row_version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE time_account_id = v_time_account_id;
            SET v_status = 'CREDITED';
        END IF;

        SET v_credit_id = UUID();
        INSERT INTO oa_time_off_credit (
            time_off_credit_id, source_system, source_overtime_line_id,
            employee_number, employee_id, employment_period_id, company_id,
            time_account_id, account_year, overtime_business_date,
            credited_hours, expires_on, credit_status, payload_digest,
            credit_ledger_entry_id, expiry_ledger_entry_id,
            credit_reversal_ledger_entry_id,
            expiry_reversal_ledger_entry_id,
            credited_at, reversed_at, row_version
        ) VALUES (
            v_credit_id, 'SEEYON_OA', p_overtime_line_id,
            p_employee_number, v_employee_id, v_employment_period_id,
            v_company_id, v_time_account_id, p_account_year,
            p_business_date, p_hours, v_expires_on, v_status,
            p_payload_digest, v_credit_ledger_id, v_expiry_ledger_id,
            NULL, NULL, CURRENT_TIMESTAMP(6), NULL, 0
        );
    END IF;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'TIME_OFF_CREDIT'
      AND source_request_id = p_overtime_line_id
      AND source_event_id = p_source_event_id
    FOR UPDATE;

    IF v_event_digest IS NOT NULL THEN
        IF v_event_digest <> p_payload_digest THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
    ELSE
        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'TIME_OFF_CREDIT', p_overtime_line_id,
            p_source_event_id, p_payload_digest, v_status,
            p_hours, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        p_overtime_line_id, v_status, p_hours, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_time_off_expire(
    IN p_employee_number VARCHAR(128),
    IN p_account_year SMALLINT UNSIGNED,
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_employee_id VARCHAR(36);
    DECLARE v_employee_count INT DEFAULT 0;
    DECLARE v_account_count INT DEFAULT 0;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_available DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;
    DECLARE v_event_affected DECIMAL(12,2) DEFAULT NULL;
    DECLARE v_request_id VARCHAR(128);
    DECLARE v_year_end DATE;
    DECLARE v_remaining DECIMAL(12,2);
    DECLARE v_credit_done BOOLEAN DEFAULT FALSE;
    DECLARE v_lot_credit_id VARCHAR(36);
    DECLARE v_lot_line_id VARCHAR(128);
    DECLARE v_lot_hours DECIMAL(12,2);
    DECLARE v_lot_expiry_id VARCHAR(36);

    DECLARE credit_cursor CURSOR FOR
        SELECT time_off_credit_id, source_overtime_line_id, credited_hours
        FROM oa_time_off_credit
        WHERE time_account_id = v_time_account_id
          AND credit_status = 'CREDITED'
        ORDER BY credited_at DESC, time_off_credit_id DESC;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_credit_done = TRUE;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_employee_number IS NULL
       OR CHAR_LENGTH(TRIM(p_employee_number)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYEE_NUMBER_REQUIRED';
    END IF;
    IF p_account_year IS NULL OR p_account_year < 1000
       OR p_account_year > 9999 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_ACCOUNT_YEAR_INVALID';
    END IF;
    IF p_source_event_id IS NULL OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_EVENT_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;
    SET v_year_end = STR_TO_DATE(CONCAT(p_account_year, '-12-31'), '%Y-%m-%d');
    IF CURRENT_DATE() <= v_year_end THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_TIME_OFF_NOT_YET_EXPIRED';
    END IF;
    SET v_request_id = CONCAT(
        'TIME_OFF_ACCOUNT:', SHA2(CONCAT(p_employee_number, '|', p_account_year), 256)
    );

    START TRANSACTION;
    -- Expiry is an account close operation, not an employee self-service
    -- operation. It must still close a terminated employee's old account.
    SELECT COUNT(*), MIN(employee_id)
      INTO v_employee_count, v_employee_id
    FROM employee
    WHERE employee_number = p_employee_number;
    IF v_employee_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_EMPLOYEE_NOT_UNIQUE';
    END IF;

    SELECT COUNT(*), MIN(time_account_id), MIN(policy_version_id)
      INTO v_account_count, v_time_account_id, v_policy_version_id
    FROM time_account
    WHERE employee_id = v_employee_id
      AND account_type = 'TIME_OFF'
      AND account_year = p_account_year;
    IF v_account_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING';
    END IF;

    SELECT balance_hours, policy_version_id
      INTO v_balance, v_policy_version_id
    FROM time_account
    WHERE time_account_id = v_time_account_id
    FOR UPDATE;
    SELECT COALESCE(SUM(amount_hours), 0.00)
      INTO v_ledger_balance
    FROM time_account_ledger_entry
    WHERE time_account_id = v_time_account_id;
    IF v_balance <> v_ledger_balance THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
    END IF;

    SELECT payload_digest, affected_hours
      INTO v_event_digest, v_event_affected
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'TIME_OFF_EXPIRY'
      AND source_request_id = v_request_id
      AND source_event_id = p_source_event_id
    FOR UPDATE;

    IF v_event_digest IS NOT NULL THEN
        IF v_event_digest <> p_payload_digest THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
        END IF;
        SET v_available = v_event_affected;
    ELSE
        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');

        IF v_balance < v_reserved THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
        END IF;
        SET v_available = v_balance - v_reserved;

        IF v_available > 0 THEN
            SELECT COALESCE(MAX(sequence_no), 0) + 1
              INTO v_sequence
            FROM time_account_ledger_entry
            WHERE time_account_id = v_time_account_id;
            SET v_remaining = v_available;
            SET v_credit_done = FALSE;
            OPEN credit_cursor;
            credit_loop: LOOP
                FETCH credit_cursor
                  INTO v_lot_credit_id, v_lot_line_id, v_lot_hours;
                IF v_credit_done THEN
                    LEAVE credit_loop;
                END IF;

                -- Newest credits are treated as the unused lots first. A lot
                -- which cannot fit in unreserved balance remains CREDITED and
                -- therefore cannot later be reversed: it was partly used or
                -- is represented by a protected hold.
                IF v_lot_hours <= v_remaining THEN
                    SET v_lot_expiry_id = UUID();
                    INSERT INTO time_account_ledger_entry (
                        time_account_ledger_entry_id, time_account_id,
                        sequence_no, entry_type, amount_hours,
                        source_type, source_id, business_date, effective_from,
                        expires_on, policy_version_id, period_version_id,
                        close_snapshot_id, request_id, reversal_of_entry_id,
                        actor_id, occurred_at
                    ) VALUES (
                        v_lot_expiry_id, v_time_account_id, v_sequence,
                        'EXPIRY', -v_lot_hours, 'SEEYON_OA_EXPIRY',
                        LEFT(v_lot_line_id, 128), v_year_end, v_year_end,
                        v_year_end, v_policy_version_id, NULL, NULL,
                        CONCAT('SZSC:YEAR_LOT_EXPIRY:', SHA2(
                            CONCAT(v_lot_line_id, '|', p_source_event_id), 256
                        )), NULL,
                        '41000000-0000-0000-0000-000000000001',
                        CURRENT_TIMESTAMP(6)
                    );
                    UPDATE oa_time_off_credit
                    SET credit_status = 'EXPIRED',
                        expiry_ledger_entry_id = v_lot_expiry_id,
                        row_version = row_version + 1
                    WHERE time_off_credit_id = v_lot_credit_id
                      AND credit_status = 'CREDITED';
                    SET v_remaining = v_remaining - v_lot_hours;
                    SET v_sequence = v_sequence + 1;
                END IF;
            END LOOP;
            CLOSE credit_cursor;

            -- Any remaining available balance came from opening/manual credit,
            -- or from the unconsumed portion of a partly used OA lot. Expire it
            -- at account level without falsely marking that lot reversible.
            IF v_remaining > 0 THEN
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    UUID(), v_time_account_id, v_sequence,
                    'EXPIRY', -v_remaining, 'SEEYON_OA_EXPIRY',
                    LEFT(v_request_id, 128), v_year_end, v_year_end, v_year_end,
                    v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:YEAR_EXPIRY:', SHA2(
                        CONCAT(v_request_id, '|', p_source_event_id), 256
                    )), NULL,
                    '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
            END IF;

            UPDATE time_account
            SET balance_hours = balance_hours - v_available,
                row_version = row_version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE time_account_id = v_time_account_id;
        END IF;

        INSERT INTO oa_time_account_operation_event (
            operation_event_id, operation_kind, source_request_id,
            source_event_id, payload_digest, result_status,
            affected_hours, recorded_at
        ) VALUES (
            UUID(), 'TIME_OFF_EXPIRY', v_request_id,
            p_source_event_id, p_payload_digest, 'EXPIRED',
            v_available, CURRENT_TIMESTAMP(6)
        );
    END IF;

    COMMIT;
    CALL szsc_oa_private_emit_balance(
        v_request_id, 'EXPIRED', v_available, v_time_account_id
    );
END$$

CREATE PROCEDURE szsc_oa_time_off_can_reverse(
    IN p_overtime_line_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
READS SQL DATA
BEGIN
    DECLARE v_credit_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_hours DECIMAL(12,2);
    DECLARE v_status VARCHAR(24);
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_available DECIMAL(12,2);

    IF p_overtime_line_id IS NULL OR CHAR_LENGTH(TRIM(p_overtime_line_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_REQUEST_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    SELECT time_off_credit_id, time_account_id, credited_hours, credit_status
      INTO v_credit_id, v_time_account_id, v_hours, v_status
    FROM oa_time_off_credit
    WHERE source_system = 'SEEYON_OA'
      AND source_overtime_line_id = p_overtime_line_id;

    IF v_credit_id IS NULL THEN
        SELECT p_overtime_line_id AS source_request_id,
               'NO_CREDIT' AS operation_status,
               'TIME_OFF' AS account_type,
               CAST(NULL AS UNSIGNED) AS account_year,
               CAST(0.00 AS DECIMAL(12,2)) AS affected_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS balance_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS reserved_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS available_hours;
    ELSE
        SELECT balance_hours
          INTO v_balance
        FROM time_account
        WHERE time_account_id = v_time_account_id;
        SELECT COALESCE(SUM(amount_hours), 0.00)
          INTO v_ledger_balance
        FROM time_account_ledger_entry
        WHERE time_account_id = v_time_account_id;
        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');

        IF v_balance <> v_ledger_balance THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
        END IF;
        IF v_balance < v_reserved THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
        END IF;
        SET v_available = v_balance - v_reserved;

        IF v_status = 'CREDITED' AND v_available < v_hours THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_TIME_OFF_CREDIT_NOT_REVERSIBLE';
        END IF;

        CALL szsc_oa_private_emit_balance(
            p_overtime_line_id,
            CASE WHEN v_status = 'REVERSED'
                THEN 'ALREADY_REVERSED' ELSE 'REVERSIBLE' END,
            v_hours, v_time_account_id
        );
    END IF;
END$$

CREATE PROCEDURE szsc_oa_time_off_reverse(
    IN p_overtime_line_id VARCHAR(128),
    IN p_source_event_id VARCHAR(128),
    IN p_payload_digest CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    DECLARE v_credit_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_time_account_id VARCHAR(36);
    DECLARE v_hours DECIMAL(12,2) DEFAULT 0.00;
    DECLARE v_status VARCHAR(24);
    DECLARE v_credit_ledger_id VARCHAR(36);
    DECLARE v_expiry_ledger_id VARCHAR(36);
    DECLARE v_credit_reversal_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_expiry_reversal_id VARCHAR(36) DEFAULT NULL;
    DECLARE v_policy_version_id VARCHAR(96);
    DECLARE v_business_date DATE;
    DECLARE v_account_year SMALLINT UNSIGNED;
    DECLARE v_balance DECIMAL(12,2);
    DECLARE v_ledger_balance DECIMAL(12,2);
    DECLARE v_reserved DECIMAL(12,2);
    DECLARE v_sequence BIGINT UNSIGNED;
    DECLARE v_event_digest CHAR(64) DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF p_overtime_line_id IS NULL OR CHAR_LENGTH(TRIM(p_overtime_line_id)) = 0
       OR p_source_event_id IS NULL
       OR CHAR_LENGTH(TRIM(p_source_event_id)) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_SOURCE_ID_REQUIRED';
    END IF;
    IF p_payload_digest IS NULL
       OR p_payload_digest NOT REGEXP '^[0-9a-f]{64}$' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_PAYLOAD_DIGEST_INVALID';
    END IF;

    START TRANSACTION;
    SELECT time_off_credit_id, time_account_id, credited_hours,
           credit_status, credit_ledger_entry_id, expiry_ledger_entry_id,
           overtime_business_date, account_year
      INTO v_credit_id, v_time_account_id, v_hours, v_status,
           v_credit_ledger_id, v_expiry_ledger_id,
           v_business_date, v_account_year
    FROM oa_time_off_credit
    WHERE source_system = 'SEEYON_OA'
      AND source_overtime_line_id = p_overtime_line_id
    FOR UPDATE;

    SELECT payload_digest
      INTO v_event_digest
    FROM oa_time_account_operation_event
    WHERE operation_kind = 'TIME_OFF_REVERSE'
      AND source_request_id = p_overtime_line_id
      AND source_event_id = p_source_event_id
    FOR UPDATE;

    IF v_event_digest IS NOT NULL
       AND v_event_digest <> p_payload_digest THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZSC_IDEMPOTENCY_CONFLICT';
    END IF;

    IF v_credit_id IS NULL THEN
        IF v_event_digest IS NULL THEN
            INSERT INTO oa_time_account_operation_event (
                operation_event_id, operation_kind, source_request_id,
                source_event_id, payload_digest, result_status,
                affected_hours, recorded_at
            ) VALUES (
                UUID(), 'TIME_OFF_REVERSE', p_overtime_line_id,
                p_source_event_id, p_payload_digest, 'NOOP',
                0.00, CURRENT_TIMESTAMP(6)
            );
        END IF;
        COMMIT;
        SELECT p_overtime_line_id AS source_request_id,
               'NOOP' AS operation_status,
               'TIME_OFF' AS account_type,
               CAST(NULL AS UNSIGNED) AS account_year,
               CAST(0.00 AS DECIMAL(12,2)) AS affected_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS balance_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS reserved_hours,
               CAST(0.00 AS DECIMAL(12,2)) AS available_hours;
    ELSE
        SELECT balance_hours, policy_version_id
          INTO v_balance, v_policy_version_id
        FROM time_account
        WHERE time_account_id = v_time_account_id
        FOR UPDATE;
        SELECT COALESCE(SUM(amount_hours), 0.00)
          INTO v_ledger_balance
        FROM time_account_ledger_entry
        WHERE time_account_id = v_time_account_id;
        SELECT COALESCE(SUM(reserved_hours), 0.00)
          INTO v_reserved
        FROM oa_time_account_reservation
        WHERE time_account_id = v_time_account_id
          AND reservation_status IN ('RESERVED', 'CONFIRMED');

        IF v_balance <> v_ledger_balance THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_BALANCE_LEDGER_MISMATCH';
        END IF;
        IF v_balance < v_reserved THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SZSC_ACCOUNT_BALANCE_CORRUPTED';
        END IF;

        IF v_event_digest IS NULL AND v_status <> 'REVERSED' THEN
            SELECT COALESCE(MAX(sequence_no), 0) + 1
              INTO v_sequence
            FROM time_account_ledger_entry
            WHERE time_account_id = v_time_account_id;

            IF v_status = 'CREDITED' THEN
                IF v_balance - v_reserved < v_hours THEN
                    SIGNAL SQLSTATE '45000'
                        SET MESSAGE_TEXT = 'SZSC_TIME_OFF_CREDIT_NOT_REVERSIBLE';
                END IF;
                SET v_credit_reversal_id = UUID();
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    v_credit_reversal_id, v_time_account_id, v_sequence,
                    'REVERSAL', -v_hours, 'SEEYON_OA_OVERTIME',
                    LEFT(p_overtime_line_id, 128), CURRENT_DATE(),
                    CURRENT_DATE(), NULL, v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:OT_REVERSE:', SHA2(p_overtime_line_id, 256)),
                    v_credit_ledger_id,
                    '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
                UPDATE time_account
                SET balance_hours = balance_hours - v_hours,
                    row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE time_account_id = v_time_account_id;
            ELSEIF v_status = 'EXPIRED' THEN
                -- Immediate cross-year expiry is linked to this credit. Reverse
                -- expiry first and credit second; the net account balance is 0.
                SET v_expiry_reversal_id = UUID();
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    v_expiry_reversal_id, v_time_account_id, v_sequence,
                    'REVERSAL', v_hours, 'SEEYON_OA_OVERTIME',
                    LEFT(p_overtime_line_id, 128), CURRENT_DATE(),
                    CURRENT_DATE(), NULL, v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:OT_UNEXPIRE:', SHA2(p_overtime_line_id, 256)),
                    v_expiry_ledger_id,
                    '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
                SET v_sequence = v_sequence + 1;
                SET v_credit_reversal_id = UUID();
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id, sequence_no,
                    entry_type, amount_hours, source_type, source_id,
                    business_date, effective_from, expires_on,
                    policy_version_id, period_version_id, close_snapshot_id,
                    request_id, reversal_of_entry_id, actor_id, occurred_at
                ) VALUES (
                    v_credit_reversal_id, v_time_account_id, v_sequence,
                    'REVERSAL', -v_hours, 'SEEYON_OA_OVERTIME',
                    LEFT(p_overtime_line_id, 128), CURRENT_DATE(),
                    CURRENT_DATE(), NULL, v_policy_version_id, NULL, NULL,
                    CONCAT('SZSC:OT_REVERSE:', SHA2(p_overtime_line_id, 256)),
                    v_credit_ledger_id,
                    '41000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP(6)
                );
                UPDATE time_account
                SET row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE time_account_id = v_time_account_id;
            END IF;

            UPDATE oa_time_off_credit
            SET credit_status = 'REVERSED',
                credit_reversal_ledger_entry_id = v_credit_reversal_id,
                expiry_reversal_ledger_entry_id = v_expiry_reversal_id,
                reversed_at = CURRENT_TIMESTAMP(6),
                row_version = row_version + 1
            WHERE time_off_credit_id = v_credit_id;
            SET v_status = 'REVERSED';
        END IF;

        IF v_event_digest IS NULL THEN
            INSERT INTO oa_time_account_operation_event (
                operation_event_id, operation_kind, source_request_id,
                source_event_id, payload_digest, result_status,
                affected_hours, recorded_at
            ) VALUES (
                UUID(), 'TIME_OFF_REVERSE', p_overtime_line_id,
                p_source_event_id, p_payload_digest, v_status,
                v_hours, CURRENT_TIMESTAMP(6)
            );
        END IF;

        COMMIT;
        CALL szsc_oa_private_emit_balance(
            p_overtime_line_id, v_status, v_hours, v_time_account_id
        );
    END IF;
END$$

DELIMITER ;

-- Read-only reconciliation for the Seeyon OA / HR time-account boundary.
-- Run against the HR schema. Every result is diagnostic; this script never
-- changes balances and must not be converted into UPDATE statements.

SET @stale_before = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 24 HOUR);

-- 1. Reservations which have not reached a terminal state in the agreed SLA.
SELECT
    reservation.source_request_id,
    reservation.employee_number,
    reservation.account_type,
    reservation.account_year,
    reservation.reserved_hours,
    reservation.reservation_status,
    reservation.reservation_cycle,
    reservation.created_at,
    reservation.updated_at
FROM oa_time_account_reservation reservation
WHERE reservation.reservation_status IN ('RESERVED', 'CONFIRMED')
  AND reservation.updated_at < @stale_before
ORDER BY reservation.updated_at, reservation.source_request_id;

-- 2. Current approved balance-consuming leave facts which never reserved an
-- ANNUAL_LEAVE/TIME_OFF account, or whose reservation never reached CONSUMED.
-- V48 persists the signed leave classification, so non-balance leave types are
-- excluded instead of being guessed from display labels.
WITH ranked_oa_document AS (
    SELECT
        document.*,
        ROW_NUMBER() OVER (
            PARTITION BY
                document.attendance_source_id,
                document.source_business_key
            ORDER BY
                document.knowledge_rank DESC,
                document.created_at DESC,
                document.oa_attendance_document_id DESC
        ) AS version_rank
    FROM oa_attendance_document document
), current_oa_document AS (
    SELECT ranked.*
    FROM ranked_oa_document ranked
    WHERE ranked.version_rank = 1
)
SELECT
    document.source_business_key AS oa_leave_request_id,
    document.leave_type,
    document.source_status,
    document.approved_at,
    reservation.reservation_id,
    reservation.reservation_status,
    reservation.reserved_hours,
    reservation.consumed_at,
    CASE
        WHEN reservation.reservation_id IS NULL
        THEN 'MISSING_RESERVATION'
        ELSE 'RESERVATION_NOT_CONSUMED'
    END AS anomaly_kind
FROM current_oa_document document
LEFT JOIN oa_time_account_reservation reservation
  ON reservation.source_system = 'SEEYON_OA'
 AND reservation.source_request_id = CASE
        WHEN document.source_business_key LIKE 'LEAVE:%'
        THEN SUBSTRING(document.source_business_key, 7)
        WHEN document.source_business_key LIKE 'TIME_OFF:%'
        THEN SUBSTRING(document.source_business_key, 10)
        ELSE document.source_business_key
     END
WHERE document.document_type = 'LEAVE'
  AND document.leave_type IN ('ANNUAL', 'COMPENSATORY')
  AND document.source_status IN ('APPROVED', 'MODIFIED', 'SUPPLEMENTED')
  AND (
        reservation.reservation_id IS NULL
        OR reservation.reservation_status <> 'CONSUMED'
  )
ORDER BY document.approved_at, document.source_business_key;

-- 3. Candidate current approved revocations which have no immutable RETURN
-- fact. V48 leave_type restricts this diagnostic to annual/compensatory leave,
-- avoiding false positives from revocations which never touch a balance.
-- Empty results are meaningful only when the OA synchronization watermark is
-- current and the revocation adapter has classified leave_type.
WITH ranked_oa_document AS (
    SELECT
        document.*,
        ROW_NUMBER() OVER (
            PARTITION BY
                document.attendance_source_id,
                document.source_business_key
            ORDER BY
                document.knowledge_rank DESC,
                document.created_at DESC,
                document.oa_attendance_document_id DESC
        ) AS version_rank
    FROM oa_attendance_document document
), current_oa_document AS (
    SELECT ranked.*
    FROM ranked_oa_document ranked
    WHERE ranked.version_rank = 1
)
SELECT
    document.source_business_key AS oa_revocation_request_id,
    document.leave_type,
    document.source_status,
    document.approved_at
FROM current_oa_document document
LEFT JOIN oa_leave_balance_return balance_return
  ON balance_return.source_system = 'SEEYON_OA'
 AND balance_return.source_revocation_request_id
        = CASE
            WHEN document.source_business_key LIKE 'LEAVE_REVOCATION:%'
            THEN SUBSTRING(document.source_business_key, 18)
            ELSE document.source_business_key
          END
WHERE document.document_type = 'LEAVE_REVOCATION'
  AND document.leave_type IN ('ANNUAL', 'COMPENSATORY')
  AND document.source_status IN ('APPROVED', 'MODIFIED', 'SUPPLEMENTED')
  AND balance_return.leave_balance_return_id IS NULL
ORDER BY document.approved_at, document.source_business_key;

-- 4. Current approved compensatory-overtime facts which have no immutable
-- TIME_OFF credit. The V48 context classification is authoritative; payroll
-- and voluntary overtime are deliberately excluded.
WITH ranked_oa_document AS (
    SELECT
        document.*,
        ROW_NUMBER() OVER (
            PARTITION BY
                document.attendance_source_id,
                document.source_business_key
            ORDER BY
                document.knowledge_rank DESC,
                document.created_at DESC,
                document.oa_attendance_document_id DESC
        ) AS version_rank
    FROM oa_attendance_document document
), current_oa_document AS (
    SELECT ranked.*
    FROM ranked_oa_document ranked
    WHERE ranked.version_rank = 1
)
SELECT
    document.source_business_key AS oa_overtime_line_id,
    document.source_status,
    document.approved_at,
    context.overtime_type,
    credit.time_off_credit_id,
    'MISSING_TIME_OFF_CREDIT' AS anomaly_kind
FROM current_oa_document document
JOIN oa_attendance_document_context context
  ON context.oa_attendance_document_id =
        document.oa_attendance_document_id
LEFT JOIN oa_time_off_credit credit
  ON credit.source_system = 'SEEYON_OA'
 AND credit.source_overtime_line_id = CASE
        WHEN document.source_business_key LIKE 'OVERTIME:%'
        THEN SUBSTRING(document.source_business_key, 10)
        ELSE document.source_business_key
     END
WHERE document.document_type = 'OVERTIME'
  AND document.source_status = 'APPROVED'
  AND context.activation_decision = 'ACTIVATED'
  AND context.overtime_type = 'COMPENSATORY'
  AND credit.time_off_credit_id IS NULL
ORDER BY document.approved_at, document.source_business_key;

-- 5. The invariant required before every write procedure.
SELECT
    account.time_account_id,
    employee.employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours,
    COALESCE(SUM(entry.amount_hours), 0.00) AS ledger_balance_hours
FROM time_account account
JOIN employee employee
  ON employee.employee_id = account.employee_id
 AND employee.company_id = account.company_id
LEFT JOIN time_account_ledger_entry entry
  ON entry.time_account_id = account.time_account_id
WHERE account.account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
GROUP BY
    account.time_account_id,
    employee.employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours
HAVING account.balance_hours <> COALESCE(SUM(entry.amount_hours), 0.00);

-- 6. Accounts whose active reservations already exceed the ledger balance.
SELECT
    account.time_account_id,
    employee.employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours,
    SUM(reservation.reserved_hours) AS reserved_hours,
    account.balance_hours - SUM(reservation.reserved_hours) AS available_hours
FROM time_account account
JOIN employee employee
  ON employee.employee_id = account.employee_id
 AND employee.company_id = account.company_id
JOIN oa_time_account_reservation reservation
  ON reservation.time_account_id = account.time_account_id
 AND reservation.reservation_status IN ('RESERVED', 'CONFIRMED')
GROUP BY
    account.time_account_id,
    employee.employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours
HAVING account.balance_hours < SUM(reservation.reserved_hours);

-- 7. Cumulative RETURN facts must equal the reservation's returned counter
-- and may never exceed the consumed amount.
SELECT
    reservation.source_request_id,
    reservation.reserved_hours,
    reservation.returned_hours,
    COALESCE(SUM(balance_return.returned_hours), 0.00) AS return_fact_hours
FROM oa_time_account_reservation reservation
LEFT JOIN oa_leave_balance_return balance_return
  ON balance_return.reservation_id = reservation.reservation_id
GROUP BY
    reservation.reservation_id,
    reservation.source_request_id,
    reservation.reserved_hours,
    reservation.returned_hours
HAVING reservation.returned_hours
           <> COALESCE(SUM(balance_return.returned_hours), 0.00)
    OR reservation.returned_hours > reservation.reserved_hours;

-- 8. TIME_OFF credit/expiry/reversal pointer and ledger amount anomalies.
SELECT
    credit.source_overtime_line_id,
    credit.employee_number,
    credit.account_year,
    credit.credited_hours,
    credit.credit_status,
    credit.credit_ledger_entry_id,
    credit.expiry_ledger_entry_id,
    credit.credit_reversal_ledger_entry_id,
    credit.expiry_reversal_ledger_entry_id
FROM oa_time_off_credit credit
JOIN time_account_ledger_entry credit_entry
  ON credit_entry.time_account_ledger_entry_id
        = credit.credit_ledger_entry_id
LEFT JOIN time_account_ledger_entry expiry_entry
  ON expiry_entry.time_account_ledger_entry_id
        = credit.expiry_ledger_entry_id
LEFT JOIN time_account_ledger_entry credit_reversal
  ON credit_reversal.time_account_ledger_entry_id
        = credit.credit_reversal_ledger_entry_id
LEFT JOIN time_account_ledger_entry expiry_reversal
  ON expiry_reversal.time_account_ledger_entry_id
        = credit.expiry_reversal_ledger_entry_id
WHERE credit_entry.entry_type <> 'OVERTIME_CREDIT'
   OR credit_entry.amount_hours <> credit.credited_hours
   OR credit_entry.time_account_id <> credit.time_account_id
   OR (
       expiry_entry.time_account_ledger_entry_id IS NOT NULL
       AND expiry_entry.time_account_id <> credit.time_account_id
   )
   OR (
       credit_reversal.time_account_ledger_entry_id IS NOT NULL
       AND credit_reversal.time_account_id <> credit.time_account_id
   )
   OR (
       expiry_reversal.time_account_ledger_entry_id IS NOT NULL
       AND expiry_reversal.time_account_id <> credit.time_account_id
   )
   OR (
       credit.credit_status = 'CREDITED'
       AND (
           credit.expiry_ledger_entry_id IS NOT NULL
           OR credit.credit_reversal_ledger_entry_id IS NOT NULL
           OR credit.expiry_reversal_ledger_entry_id IS NOT NULL
       )
   )
   OR (
       credit.credit_status = 'EXPIRED'
       AND (
           expiry_entry.entry_type <> 'EXPIRY'
           OR expiry_entry.amount_hours <> -credit.credited_hours
           OR credit.credit_reversal_ledger_entry_id IS NOT NULL
           OR credit.expiry_reversal_ledger_entry_id IS NOT NULL
       )
   )
   OR (
       credit.credit_status = 'REVERSED'
       AND (
           credit.credit_reversal_ledger_entry_id IS NULL
           OR (
               credit.expiry_ledger_entry_id IS NOT NULL
               AND credit.expiry_reversal_ledger_entry_id IS NULL
           )
           OR (
               credit.expiry_ledger_entry_id IS NULL
               AND credit.expiry_reversal_ledger_entry_id IS NOT NULL
           )
       )
   )
   OR (
       credit_reversal.time_account_ledger_entry_id IS NOT NULL
       AND (
           credit_reversal.entry_type <> 'REVERSAL'
           OR credit_reversal.amount_hours <> -credit.credited_hours
           OR credit_reversal.reversal_of_entry_id
                <> credit.credit_ledger_entry_id
       )
   )
   OR (
       expiry_reversal.time_account_ledger_entry_id IS NOT NULL
       AND (
           expiry_reversal.entry_type <> 'REVERSAL'
           OR expiry_reversal.amount_hours <> credit.credited_hours
           OR expiry_reversal.reversal_of_entry_id
                <> credit.expiry_ledger_entry_id
       )
   );

-- 9. Prior-year TIME_OFF accounts with positive unreserved balance still need
-- operational attention. A partially used credit lot may legitimately remain
-- CREDITED after account-level expiry, so lot status alone is not an anomaly.
WITH active_reservation AS (
    SELECT
        reservation.time_account_id,
        SUM(reservation.reserved_hours) AS reserved_hours
    FROM oa_time_account_reservation reservation
    WHERE reservation.reservation_status IN ('RESERVED', 'CONFIRMED')
    GROUP BY reservation.time_account_id
), expiry_event_summary AS (
    SELECT
        operation_event.source_request_id,
        COUNT(*) AS expiry_event_count
    FROM oa_time_account_operation_event operation_event
    WHERE operation_event.operation_kind = 'TIME_OFF_EXPIRY'
    GROUP BY operation_event.source_request_id
)
SELECT
    account.time_account_id,
    employee.employee_number,
    account.account_year,
    account.balance_hours,
    COALESCE(active_reservation.reserved_hours, 0.00) AS reserved_hours,
    account.balance_hours
        - COALESCE(active_reservation.reserved_hours, 0.00)
        AS available_hours,
    COALESCE(expiry_event.expiry_event_count, 0) AS expiry_event_count,
    CASE
        WHEN COALESCE(expiry_event.expiry_event_count, 0) = 0
        THEN 'MISSING_TIME_OFF_EXPIRY_EVENT'
        ELSE 'POSITIVE_AVAILABLE_AFTER_EXPIRY_EVENT'
    END AS anomaly_kind
FROM time_account account
JOIN employee employee
  ON employee.employee_id = account.employee_id
 AND employee.company_id = account.company_id
LEFT JOIN active_reservation
  ON active_reservation.time_account_id = account.time_account_id
LEFT JOIN expiry_event_summary expiry_event
  ON expiry_event.source_request_id = CONCAT(
        'TIME_OFF_ACCOUNT:',
        SHA2(CONCAT(employee.employee_number, '|', account.account_year), 256)
     )
WHERE account.account_type = 'TIME_OFF'
  AND account.account_year < YEAR(CURRENT_DATE())
  AND account.balance_hours
        - COALESCE(active_reservation.reserved_hours, 0.00) > 0
ORDER BY account.account_year, employee.employee_number,
         account.time_account_id;

-- 10. Recorded year-end failures and unattempted items after lease loss retain
-- the affected work number. A normal IDEMPOTENT_REPLAY is not an anomaly.
SELECT
    run.time_off_year_end_run_id,
    run.account_year,
    run.run_status,
    item.employee_number,
    item.item_status,
    item.source_event_id,
    item.payload_digest,
    item.result_code,
    item.result_detail,
    item.completed_at
FROM time_off_year_end_run run
JOIN time_off_year_end_run_item item
  ON item.time_off_year_end_run_id = run.time_off_year_end_run_id
WHERE item.item_status = 'FAILED'
   OR (
        item.item_status = 'SKIPPED'
        AND COALESCE(item.result_code, '') <> 'IDEMPOTENT_REPLAY'
      )
ORDER BY item.completed_at DESC, item.employee_number;

-- 11. Run-level failures and stale ownership, including runs with no item row.
-- A FAILED run may have an unaccounted difference when audit persistence itself
-- failed; the difference is deliberately visible rather than relabeled skipped.
-- SKIPPED_LOCKED is expected cluster contention and is not an anomaly here.
SELECT
    run.time_off_year_end_run_id,
    run.account_year,
    run.run_status,
    run.lock_owner,
    run.total_count,
    run.success_count,
    run.failure_count,
    run.skipped_count,
    run.total_count
        - run.success_count
        - run.failure_count
        - run.skipped_count AS unprocessed_count,
    run.failure_summary,
    run.started_at,
    run.completed_at
FROM time_off_year_end_run run
WHERE (
        run.run_status = 'RUNNING'
        AND run.started_at < @stale_before
      )
   OR run.run_status = 'FAILED'
ORDER BY run.started_at DESC, run.time_off_year_end_run_id;

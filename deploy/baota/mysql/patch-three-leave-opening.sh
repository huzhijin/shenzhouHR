#!/bin/bash
# 只补花名册 3 人，并只导入这 3 人的 2026-08-01 开口额度。其他人 515 条不动。
# 用法：拷到服务器后执行  bash /root/patch-three-leave-opening.sh
set -euo pipefail

MYSQL=(
  mysql -h127.0.0.1 -P3306 -uroot --password='e0e17f3df673a9f8'
  --default-character-set=utf8mb4 --table shenzhou_hr
)

SQL=/tmp/patch-three-leave-opening.sql
cat > "$SQL" << 'SQL'
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS patch_leave_opening_three_roster_people;
DROP PROCEDURE IF EXISTS patch_leave_opening_ensure_szsz_employee;

DELIMITER $$

CREATE PROCEDURE patch_leave_opening_ensure_szsz_employee(
    IN p_empno VARCHAR(32),
    IN p_name VARCHAR(100),
    IN p_hire DATE,
    IN p_company_id VARCHAR(36),
    IN p_org_id VARCHAR(36)
)
BEGIN
    DECLARE v_employee_id VARCHAR(36);
    DECLARE v_version_id VARCHAR(36);
    DECLARE v_period_id VARCHAR(36);
    DECLARE v_now DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6);

    SET v_employee_id = (
        SELECT employee_id FROM employee WHERE employee_number = p_empno LIMIT 1
    );

    IF v_employee_id IS NULL THEN
        SET v_employee_id = UUID();
        SET v_version_id = UUID();
        SET v_period_id = UUID();

        INSERT INTO employee (
            employee_id, company_id, employee_number, display_name,
            employment_status, onboard_date, row_version, created_at, updated_at
        ) VALUES (
            v_employee_id, p_company_id, p_empno, p_name,
            'ACTIVE', p_hire, 0, v_now, v_now
        );

        INSERT INTO employee_version (
            employee_version_id, employee_id, employee_number, display_name,
            status, external_employee_id, effective_from, effective_to,
            source_authority, source_import_batch_id, row_version, change_reason,
            created_by, created_at
        ) VALUES (
            v_version_id, v_employee_id, p_empno, p_name,
            'ACTIVE', NULL, p_hire, NULL,
            'LOCAL', NULL, 0, 'ROSTER_LEAVE_OPENING_PATCH',
            NULL, v_now
        );

        INSERT INTO employee_current_projection (employee_id, current_version_id, projected_at)
        VALUES (v_employee_id, v_version_id, v_now);

        INSERT INTO employment_period_identity (
            employment_period_id, employee_id, company_id, created_at
        ) VALUES (v_period_id, v_employee_id, p_company_id, v_now);

        INSERT INTO employment_assignment (
            assignment_id, employment_period_id, employee_id, organization_id,
            position_id, effective_from, effective_to, termination_date,
            record_status, source_import_batch_id, row_version, change_reason,
            created_by, created_at, version_valid_to
        ) VALUES (
            UUID(), v_period_id, v_employee_id, p_org_id,
            NULL, TIMESTAMP(p_hire), NULL, NULL,
            'ACTIVE', NULL, 0, 'ROSTER_LEAVE_OPENING_PATCH',
            NULL, v_now, NULL
        );
    ELSE
        UPDATE employee
        SET display_name = p_name,
            employment_status = 'ACTIVE',
            onboard_date = COALESCE(onboard_date, p_hire),
            row_version = row_version + 1,
            updated_at = v_now
        WHERE employee_id = v_employee_id;

        UPDATE employee_version
        SET display_name = p_name,
            employee_number = p_empno,
            status = 'ACTIVE'
        WHERE employee_id = v_employee_id
          AND effective_to IS NULL;

        UPDATE employment_assignment
        SET effective_to = NULL,
            termination_date = NULL
        WHERE employee_id = v_employee_id
          AND record_status = 'ACTIVE'
          AND version_valid_to IS NULL
          AND effective_to IS NOT NULL
          AND effective_to <= v_now
          AND TIMESTAMPADD(MICROSECOND, 1, effective_from) = effective_to;
    END IF;
END $$

CREATE PROCEDURE patch_leave_opening_three_roster_people()
BEGIN
    DECLARE v_yang_id VARCHAR(36);
    DECLARE v_yang_count INT DEFAULT 0;
    DECLARE v_company_id VARCHAR(36);
    DECLARE v_root_org_id VARCHAR(36);
    DECLARE v_wx_org_id VARCHAR(36);
    DECLARE v_wx_ver_id VARCHAR(36);
    DECLARE v_proj_batch VARCHAR(36);
    DECLARE v_org_type VARCHAR(32);
    DECLARE v_now DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6);
    DECLARE v_szst0709_taken INT DEFAULT 0;
    DECLARE v_login_taken INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    SELECT COUNT(*) INTO v_yang_count
    FROM employee
    WHERE employee_number IN ('SZT0709', 'SZST0709');

    IF v_yang_count = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'yangling SZT0709/SZST0709 not found';
    END IF;
    IF v_yang_count > 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'yangling SZT0709 and SZST0709 both exist';
    END IF;

    SET v_yang_id = (
        SELECT employee_id FROM employee
        WHERE employee_number IN ('SZT0709', 'SZST0709') LIMIT 1
    );

    SELECT COUNT(*) INTO v_szst0709_taken
    FROM employee
    WHERE employee_number = 'SZST0709' AND employee_id <> v_yang_id;

    IF v_szst0709_taken > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'SZST0709 already used by another employee';
    END IF;

    UPDATE employee
    SET employee_number = 'SZST0709',
        display_name = '杨玲',
        employment_status = 'ACTIVE',
        row_version = row_version + 1,
        updated_at = v_now
    WHERE employee_id = v_yang_id
      AND employee_number <> 'SZST0709';

    UPDATE employee_version
    SET employee_number = 'SZST0709',
        display_name = '杨玲',
        status = 'ACTIVE'
    WHERE employee_id = v_yang_id
      AND effective_to IS NULL
      AND employee_number <> 'SZST0709';

    SELECT COUNT(*) INTO v_login_taken
    FROM local_account
    WHERE normalized_username = 'SZST0709'
      AND principal_id NOT IN (
            SELECT principal_id FROM auth_principal WHERE employee_id = v_yang_id
        );

    IF v_login_taken = 0 THEN
        UPDATE local_account account
        JOIN auth_principal principal
          ON principal.principal_id = account.principal_id
        SET account.username = 'SZST0709',
            account.normalized_username = 'SZST0709',
            account.updated_at = v_now
        WHERE principal.employee_id = v_yang_id
          AND account.username IN ('SZT0709', 'SZST0709')
          AND account.username <> 'SZST0709';
    END IF;

    SET v_company_id = (
        SELECT company_id FROM company
        WHERE code = 'SZSZ' OR name = '上海昇州半导体科技有限公司'
        ORDER BY CASE WHEN code = 'SZSZ' THEN 0 ELSE 1 END
        LIMIT 1
    );
    IF v_company_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Shanghai Shengzhou company not found';
    END IF;

    SET v_root_org_id = (
        SELECT ov.organization_id
        FROM organization_version ov
        JOIN organization_identity oi ON oi.organization_id = ov.organization_id
        WHERE oi.company_id = v_company_id AND ov.effective_to IS NULL
        ORDER BY
            CASE WHEN ov.org_type = 'COMPANY' THEN 0 ELSE 1 END,
            CASE WHEN ov.parent_organization_id IS NULL THEN 0 ELSE 1 END,
            ov.name
        LIMIT 1
    );
    IF v_root_org_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Shanghai Shengzhou org root not found';
    END IF;

    SET v_wx_org_id = (
        SELECT ov.organization_id
        FROM organization_version ov
        JOIN organization_identity oi ON oi.organization_id = ov.organization_id
        WHERE oi.company_id = v_company_id
          AND ov.effective_to IS NULL
          AND ov.name = '维修部'
        LIMIT 1
    );

    IF v_wx_org_id IS NULL THEN
        SET v_wx_org_id = UUID();
        SET v_wx_ver_id = UUID();
        SET v_proj_batch = UUID();
        SET v_org_type = COALESCE((
            SELECT ov.org_type
            FROM organization_version ov
            JOIN organization_identity oi ON oi.organization_id = ov.organization_id
            WHERE oi.company_id = v_company_id
              AND ov.effective_to IS NULL
              AND ov.parent_organization_id IS NOT NULL
              AND ov.org_type <> 'COMPANY'
            LIMIT 1
        ), 'DEPARTMENT');

        INSERT INTO organization_identity (organization_id, company_id, identity_status, created_at)
        VALUES (v_wx_org_id, v_company_id, 'ACTIVE', v_now);

        INSERT INTO organization_version (
            organization_version_id, organization_id, parent_organization_id,
            code, name, org_type, status, effective_from, effective_to,
            row_version, source_authority, change_reason, created_by, created_at
        ) VALUES (
            v_wx_ver_id, v_wx_org_id, v_root_org_id,
            'SZSZ_DEPT_维修部', '维修部', v_org_type, 'ACTIVE',
            '2021-11-09 00:00:00.000000', NULL,
            0, 'LOCAL', 'ROSTER_LEAVE_OPENING_PATCH', NULL, v_now
        );

        INSERT INTO organization_current_projection (
            organization_id, current_version_id, projection_batch_id, projected_at
        ) VALUES (v_wx_org_id, v_wx_ver_id, v_proj_batch, v_now);

        INSERT INTO organization_current_closure (
            ancestor_organization_id, descendant_organization_id, depth, projection_batch_id
        )
        SELECT v_wx_org_id, v_wx_org_id, 0, v_proj_batch
        UNION ALL
        SELECT closure.ancestor_organization_id, v_wx_org_id, closure.depth + 1, v_proj_batch
        FROM organization_current_closure closure
        WHERE closure.descendant_organization_id = v_root_org_id;
    END IF;

    CALL patch_leave_opening_ensure_szsz_employee(
        'SZSZ0002', '赵子奇', DATE '2021-11-09', v_company_id, v_wx_org_id
    );
    CALL patch_leave_opening_ensure_szsz_employee(
        'SZSZ0003', '张衡', DATE '2022-03-01', v_company_id, v_wx_org_id
    );

    COMMIT;

    SELECT 'patched_people' AS report_kind,
           employee.employee_number,
           employee.display_name,
           employee.employment_status,
           DATE(employment.effective_from) AS assignment_from,
           employment.effective_to AS assignment_to,
           org_version.name AS org_name
    FROM employee
    LEFT JOIN employment_assignment employment
      ON employment.employee_id = employee.employee_id
     AND employment.record_status = 'ACTIVE'
     AND employment.version_valid_to IS NULL
     AND employment.current_version_marker = 1
     AND employment.effective_from <= CURRENT_TIMESTAMP(6)
     AND (employment.effective_to IS NULL OR employment.effective_to > CURRENT_TIMESTAMP(6))
    LEFT JOIN organization_version org_version
      ON org_version.organization_id = employment.organization_id
     AND org_version.effective_to IS NULL
    WHERE employee.employee_number IN ('SZST0709', 'SZSZ0002', 'SZSZ0003')
    ORDER BY employee.employee_number;
END $$

DELIMITER ;

CALL patch_leave_opening_three_roster_people();
DROP PROCEDURE IF EXISTS patch_leave_opening_three_roster_people;
DROP PROCEDURE IF EXISTS patch_leave_opening_ensure_szsz_employee;

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_leave_opening_cutover (
    employee_number VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    account_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_hours DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (employee_number, account_type)
);

INSERT INTO tmp_leave_opening_cutover (employee_number, account_type, amount_hours) VALUES
('SZSZ0002','ANNUAL_LEAVE',256.00),
('SZSZ0003','ANNUAL_LEAVE',192.00),
('SZST0709','TIME_OFF',7.00),
('SZSZ0002','TIME_OFF',108.50),
('SZSZ0003','TIME_OFF',140.00);

DROP TEMPORARY TABLE IF EXISTS tmp_leave_opening_resolved;
CREATE TEMPORARY TABLE tmp_leave_opening_resolved AS
SELECT
    src.employee_number,
    src.account_type,
    src.amount_hours,
    unique_employment.employee_id,
    unique_employment.employment_period_id,
    unique_employment.company_id,
    CASE
        WHEN src.account_type = 'ANNUAL_LEAVE' THEN 'ANNUAL_LEAVE_DEFAULT_V1'
        ELSE 'TIME_OFF_DEFAULT_V1'
    END AS policy_version_id,
    CONCAT('CUTOVER20260801:', src.account_type, ':', src.employee_number) AS request_id
FROM tmp_leave_opening_cutover src
JOIN (
    SELECT
        employee.employee_number,
        employee.employee_id,
        MAX(employment.employment_period_id) AS employment_period_id,
        employee.company_id
    FROM employee employee
    JOIN employment_assignment employment
      ON employment.employee_id = employee.employee_id
     AND employment.current_version_marker = 1
     AND employment.record_status = 'ACTIVE'
     AND employment.effective_from <= CURRENT_TIMESTAMP(6)
     AND (employment.effective_to IS NULL OR employment.effective_to > CURRENT_TIMESTAMP(6))
    WHERE employee.employment_status = 'ACTIVE'
    GROUP BY employee.employee_number, employee.employee_id, employee.company_id
    HAVING COUNT(*) = 1
) unique_employment
  ON unique_employment.employee_number = src.employee_number;

ALTER TABLE tmp_leave_opening_resolved
    ADD PRIMARY KEY (employee_number, account_type);

INSERT INTO time_account (
    time_account_id, employee_id, employment_period_id, company_id,
    account_type, account_year, balance_hours, policy_version_id,
    row_version, created_at, updated_at
)
SELECT
    UUID(), resolved.employee_id, resolved.employment_period_id, resolved.company_id,
    resolved.account_type, 2026, 0.00, resolved.policy_version_id,
    0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tmp_leave_opening_resolved resolved
LEFT JOIN time_account existing
  ON existing.employee_id = resolved.employee_id
 AND existing.employment_period_id = resolved.employment_period_id
 AND existing.account_type = resolved.account_type
 AND existing.account_year = 2026
WHERE existing.time_account_id IS NULL;

INSERT IGNORE INTO time_account_ledger_entry (
    time_account_ledger_entry_id, time_account_id, sequence_no, entry_type,
    amount_hours, source_type, source_id, business_date, effective_from, expires_on,
    policy_version_id, period_version_id, close_snapshot_id, request_id,
    reversal_of_entry_id, actor_id, occurred_at
)
SELECT
    UUID(), account.time_account_id, COALESCE(seq.max_seq, 0) + 1, 'OPENING',
    resolved.amount_hours, 'HR_OPENING_IMPORT', resolved.request_id,
    DATE '2026-08-01', DATE '2026-08-01', DATE '2026-12-31',
    account.policy_version_id, NULL, NULL, resolved.request_id,
    NULL, 'SYSTEM', CURRENT_TIMESTAMP(6)
FROM tmp_leave_opening_resolved resolved
JOIN time_account account
  ON account.employee_id = resolved.employee_id
 AND account.employment_period_id = resolved.employment_period_id
 AND account.account_type = resolved.account_type
 AND account.account_year = 2026
LEFT JOIN (
    SELECT time_account_id, MAX(sequence_no) AS max_seq
    FROM time_account_ledger_entry
    GROUP BY time_account_id
) seq ON seq.time_account_id = account.time_account_id;

UPDATE time_account account
JOIN (
    SELECT entry.time_account_id, SUM(entry.amount_hours) AS ledger_hours
    FROM time_account_ledger_entry entry
    GROUP BY entry.time_account_id
) ledger ON ledger.time_account_id = account.time_account_id
JOIN tmp_leave_opening_resolved resolved
  ON resolved.employee_id = account.employee_id
 AND resolved.employment_period_id = account.employment_period_id
 AND resolved.account_type = account.account_type
SET
    account.balance_hours = ledger.ledger_hours,
    account.row_version = account.row_version + 1,
    account.updated_at = CURRENT_TIMESTAMP(6)
WHERE account.account_year = 2026;

SELECT 'three_person_imported' AS report_kind,
       employee.employee_number,
       employee.display_name,
       account.account_type,
       account.balance_hours,
       ROUND(account.balance_hours / 8, 2) AS balance_days
FROM time_account account
JOIN employee employee ON employee.employee_id = account.employee_id
WHERE account.account_year = 2026
  AND employee.employee_number IN ('SZST0709', 'SZSZ0002', 'SZSZ0003')
  AND account.account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
ORDER BY employee.employee_number, account.account_type;

SELECT 'unmatched_employee_number' AS report_kind,
       src.employee_number, src.account_type, src.amount_hours
FROM tmp_leave_opening_cutover src
LEFT JOIN employee employee ON employee.employee_number = src.employee_number
WHERE employee.employee_id IS NULL
ORDER BY src.account_type, src.employee_number;

SELECT 'inactive_or_ambiguous_employment' AS report_kind,
       src.employee_number, src.account_type, src.amount_hours
FROM tmp_leave_opening_cutover src
JOIN employee employee ON employee.employee_number = src.employee_number
LEFT JOIN tmp_leave_opening_resolved resolved
  ON resolved.employee_number = src.employee_number
 AND resolved.account_type = src.account_type
WHERE resolved.employee_number IS NULL
ORDER BY src.account_type, src.employee_number;

SELECT 'cutover_total' AS report_kind, COUNT(*) AS n
FROM time_account_ledger_entry
WHERE request_id LIKE 'CUTOVER20260801:%';

COMMIT;
SQL

echo "=== patch 3 people + import 5 openings ==="
"${MYSQL[@]}" < "$SQL"
echo "=== done ==="

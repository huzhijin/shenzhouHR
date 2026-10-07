-- Post-V30 local/deployment finalization.
--
-- This file intentionally remains outside Flyway until the authoritative V29
-- and V30 source files are restored to the repository. Run it only after the
-- target database has successfully applied V30. It is repeat-safe.

-- Refuse to delete a role that is still assigned. The explicit SIGNAL makes
-- this fail closed even in SQL clients that continue after a statement error.
DROP PROCEDURE IF EXISTS finalize_usability_post_v30;

DELIMITER $$

CREATE PROCEDURE finalize_usability_post_v30()
BEGIN
    DECLARE synthetic_employee_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_principal_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_account_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_organization_id VARCHAR(36) DEFAULT NULL;
    DECLARE baseline_company_id VARCHAR(36) DEFAULT NULL;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    IF (SELECT COUNT(*) FROM flyway_schema_history
        WHERE version = '30' AND success = 1) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V30 Flyway history is required before finalization';
    END IF;

    SELECT company_id INTO baseline_company_id
    FROM company
    WHERE company_id = '30000000-0000-0000-0000-000000000001'
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
    FOR UPDATE;

    IF baseline_company_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'W3 baseline company shape changed; manual review required';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM auth_principal_role_assignment assignment
        JOIN auth_role role ON role.role_id = assignment.role_id
        WHERE role.role_code IN (
            'MANUFACTURING_SUPERVISOR',
            'MANUFACTURING_CENTER_SUPERVISOR',
            'MANUFACTURING_DIRECTOR',
            'MANUFACTURING_CENTER_DIRECTOR'
        )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Cannot remove manufacturing role: assignments still exist';
    END IF;

    -- Historical local acceptance administrators must not remain usable after
    -- the W3 company is retired. Match only the known synthetic account shape
    -- and fail closed if any such principal was later bound to a real employee
    -- or granted a scope outside the W3 baseline company.
    IF EXISTS (
        SELECT 1
        FROM local_account account
        JOIN auth_principal principal
          ON principal.principal_id = account.principal_id
        WHERE account.display_name = 'WAVE-1 本地合成管理员'
          AND (
              account.username IN (
                  'synthetic.local.admin',
                  'synthetic.acceptance.20260801',
                  'synthetic.acceptance.20260803'
              )
              OR account.username REGEXP
                 '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
          )
          AND (
              principal.employee_id IS NOT NULL
              OR EXISTS (
                  SELECT 1
                  FROM auth_principal_role_assignment assignment
                  JOIN auth_data_scope scope
                    ON scope.scope_id = assignment.data_scope_id
                  WHERE assignment.principal_id = principal.principal_id
                    AND NOT (
                        scope.scope_type = 'COMPANY'
                        AND scope.company_id = baseline_company_id
                        AND scope.organization_id IS NULL
                    )
              )
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Synthetic admin scope changed; manual review required';
    END IF;

    UPDATE local_account account
    JOIN auth_principal principal
      ON principal.principal_id = account.principal_id
    SET account.status = 'DISABLED',
        account.locked_until = NULL,
        account.session_epoch = account.session_epoch + 1,
        account.row_version = account.row_version + 1,
        account.updated_by = 'POST_V30_FINALIZATION',
        account.updated_at = CURRENT_TIMESTAMP(6)
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND account.status <> 'DISABLED'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE auth_principal principal
    JOIN local_account account
      ON account.principal_id = principal.principal_id
    SET principal.status = 'INACTIVE',
        principal.row_version = principal.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND principal.status <> 'INACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE password_reset_grant grant_record
    JOIN local_account account
      ON account.account_id = grant_record.account_id
    SET grant_record.used_at = CURRENT_TIMESTAMP(6),
        grant_record.row_version = grant_record.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND grant_record.used_at IS NULL
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    INSERT INTO session_revocation (
        revocation_id, session_id, account_id, reason,
        revoked_by, request_id, revoked_at
    )
    SELECT UUID(), session_record.session_id, session_record.account_id,
           'W3_BASELINE_ACCOUNT_DISABLED',
           'POST_V30_FINALIZATION',
           'post-v30-finalization',
           CURRENT_TIMESTAMP(6)
    FROM user_session session_record
    JOIN local_account account
      ON account.account_id = session_record.account_id
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND session_record.status = 'ACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE user_session session_record
    JOIN local_account account
      ON account.account_id = session_record.account_id
    SET session_record.status = 'REVOKED',
        session_record.revoked_at = CURRENT_TIMESTAMP(6),
        session_record.revocation_reason = 'W3_BASELINE_ACCOUNT_DISABLED',
        session_record.row_version = session_record.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND session_record.status = 'ACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    SELECT employee_id INTO synthetic_employee_id
    FROM employee
    WHERE employee_number = 'SYNTHETIC-EMP-001'
      AND display_name = '合成员工甲'
      AND company_id = baseline_company_id
    FOR UPDATE;

    SELECT organization.organization_id INTO synthetic_organization_id
    FROM organization_identity organization
    JOIN organization_current_projection projection
      ON projection.organization_id = organization.organization_id
    JOIN organization_version version
      ON version.organization_version_id = projection.current_version_id
    WHERE version.code = 'SYNTHETIC_ACCEPTANCE_DEPT'
      AND version.name = '合成验收部门'
      AND organization.company_id = baseline_company_id
    FOR UPDATE;

    IF (synthetic_employee_id IS NULL AND synthetic_organization_id IS NOT NULL)
       OR (synthetic_employee_id IS NOT NULL AND synthetic_organization_id IS NULL) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Synthetic acceptance data is incomplete; manual review required';
    END IF;

    IF synthetic_employee_id IS NULL
       AND (
           EXISTS (
               SELECT 1
               FROM attendance_report_projection
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
                 AND period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_report_refresh_record
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
           )
           OR EXISTS (
               SELECT 1
               FROM people_idempotency_record record
               WHERE record.action_code = 'EMPLOYEE_CREATE'
                 AND record.resource_id IS NOT NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM employee
                     WHERE employee.employee_id = record.resource_id
                 )
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_setup_idempotency record
               WHERE record.operation_code = 'CREATE_ASSIGNMENT'
                 AND record.resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
                 AND record.resource_id IS NOT NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM employee
                     WHERE employee.employee_id = record.resource_id
                 )
           )
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Orphaned acceptance data requires manual review';
    END IF;

    IF synthetic_employee_id IS NOT NULL THEN
        SELECT principal_id INTO synthetic_principal_id
        FROM auth_principal
        WHERE employee_id = synthetic_employee_id
          AND status = 'ACTIVE'
        FOR UPDATE;

        SELECT account_id INTO synthetic_account_id
        FROM local_account
        WHERE principal_id = synthetic_principal_id
          AND username = 'synthetic.employee.20260801'
          AND normalized_username = 'synthetic.employee.20260801'
          AND status = 'ACTIVE'
        FOR UPDATE;

        IF synthetic_principal_id IS NULL
           OR synthetic_account_id IS NULL
           OR (
               SELECT COUNT(*)
               FROM employment_assignment
               WHERE employee_id = synthetic_employee_id
                 AND organization_id = synthetic_organization_id
           ) <> 1
           OR (SELECT COUNT(*) FROM employment_assignment
               WHERE employee_id = synthetic_employee_id) <> 1
           OR EXISTS (
               SELECT 1
               FROM employment_assignment
               WHERE organization_id = synthetic_organization_id
                 AND employee_id <> synthetic_employee_id
           )
           OR EXISTS (
               SELECT 1
               FROM organization_version
               WHERE parent_organization_id = synthetic_organization_id
           )
           OR EXISTS (
               SELECT 1
               FROM auth_data_scope
               WHERE organization_id = synthetic_organization_id
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Synthetic acceptance data shape changed; manual review required';
        END IF;

        -- This acceptance fixture has a known, bounded footprint. Any count
        -- drift means it has been reused and must be reviewed instead of
        -- being silently deleted by a deployment script.
        IF (SELECT COUNT(*) FROM attendance_feedback
            WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_feedback_reply reply
               JOIN attendance_feedback feedback
                 ON feedback.attendance_feedback_id = reply.attendance_feedback_id
               WHERE feedback.employee_id = synthetic_employee_id
           ) <> 1
           OR (SELECT COUNT(*) FROM user_session
               WHERE account_id = synthetic_account_id) <> 15
           OR (SELECT COUNT(*) FROM session_revocation
               WHERE account_id = synthetic_account_id) <> 13
           OR (SELECT COUNT(*) FROM login_failure_window
               WHERE account_id = synthetic_account_id) <> 1
           OR (SELECT COUNT(*) FROM password_credential
               WHERE account_id = synthetic_account_id) <> 1
           OR (SELECT COUNT(*) FROM password_reset_grant
               WHERE account_id = synthetic_account_id) <> 0
           OR (SELECT COUNT(*) FROM auth_principal_role_assignment
               WHERE principal_id = synthetic_principal_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM auth_principal_role_assignment assignment
               JOIN auth_role role ON role.role_id = assignment.role_id
               JOIN auth_data_scope scope
                 ON scope.scope_id = assignment.data_scope_id
               WHERE assignment.principal_id = synthetic_principal_id
                 AND role.role_code = 'EMPLOYEE_SELF'
                 AND scope.scope_type = 'SELF'
                 AND scope.company_id IS NULL
                 AND scope.organization_id IS NULL
           ) <> 1
           OR (SELECT COUNT(*) FROM attendance_report_daily_fact
               WHERE employee_id = synthetic_employee_id) <> 5
           OR (SELECT COUNT(*) FROM attendance_report_exception_fact
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_report_time_account_fact
               WHERE employee_id = synthetic_employee_id) <> 4
           OR (SELECT COUNT(*) FROM attendance_report_oa_fact
               WHERE employee_id = synthetic_employee_id) <> 0
           OR (SELECT COUNT(*) FROM annual_leave_entitlement_projection
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM time_account
               WHERE employee_id = synthetic_employee_id) <> 2
           OR (
               SELECT COUNT(*)
               FROM time_account
               WHERE employee_id = synthetic_employee_id
                 AND account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
                 AND balance_hours = 0
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM time_account_ledger_entry ledger
               JOIN time_account account
                 ON account.time_account_id = ledger.time_account_id
               WHERE account.employee_id = synthetic_employee_id
           ) <> 0
           OR (SELECT COUNT(*) FROM attendance_group_assignment
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_assignment_timeline
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_group_assignment assignment
               JOIN attendance_group_revision revision
                 ON revision.attendance_group_revision_id
                    = assignment.attendance_group_revision_id
               JOIN attendance_group attendance_group
                 ON attendance_group.attendance_group_id
                    = revision.attendance_group_id
               JOIN attendance_assignment_timeline timeline
                 ON timeline.attendance_group_assignment_id
                    = assignment.attendance_group_assignment_id
               WHERE assignment.employee_id = synthetic_employee_id
                 AND attendance_group.company_id = baseline_company_id
                 AND attendance_group.group_code = 'DEFAULT_ATTENDANCE'
                 AND timeline.state = 'ACTIVE'
                 AND timeline.business_effective_from = '2026-08-01'
           ) <> 1
           OR (SELECT COUNT(*) FROM employee_current_projection
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM employee_version
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM employee_current_projection projection
               JOIN employee_version version
                 ON version.employee_version_id = projection.current_version_id
               WHERE projection.employee_id = synthetic_employee_id
                 AND version.employee_id = synthetic_employee_id
                 AND version.status = 'ACTIVE'
                 AND version.source_authority = 'LOCAL'
                 AND version.current_marker = 1
           ) <> 1
           OR (SELECT COUNT(*) FROM employee_source_binding
               WHERE employee_id = synthetic_employee_id) <> 0
           OR (SELECT COUNT(*) FROM employment_period_identity
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM people_idempotency_record
               WHERE action_code = 'EMPLOYEE_CREATE'
                 AND resource_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_setup_idempotency
               WHERE operation_code = 'CREATE_ASSIGNMENT'
                 AND resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
                 AND resource_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM organization_current_projection
               WHERE organization_id = synthetic_organization_id) <> 1
           OR (SELECT COUNT(*) FROM organization_version
               WHERE organization_id = synthetic_organization_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM organization_current_projection projection
               JOIN organization_version version
                 ON version.organization_version_id = projection.current_version_id
               WHERE projection.organization_id = synthetic_organization_id
                 AND version.organization_id = synthetic_organization_id
                 AND version.parent_organization_id IS NULL
                 AND version.org_type = 'DEPARTMENT'
                 AND version.status = 'ACTIVE'
                 AND version.source_authority = 'LOCAL'
                 AND version.current_marker = 1
           ) <> 1
           OR (SELECT COUNT(*) FROM organization_source_binding
               WHERE organization_id = synthetic_organization_id) <> 0
           OR (
               SELECT COUNT(*)
               FROM organization_current_closure
               WHERE ancestor_organization_id = synthetic_organization_id
                  OR descendant_organization_id = synthetic_organization_id
           ) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_report_projection
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
                 AND period_end_exclusive = '2026-09-01'
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM attendance_report_refresh_record
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM attendance_report_daily_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 5
           OR (
               SELECT COUNT(*)
               FROM attendance_report_exception_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_report_time_account_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 4
           OR (
               SELECT COUNT(*)
               FROM attendance_report_oa_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 0
           OR EXISTS (
               SELECT 1
               FROM attendance_close_snapshot snapshot
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = snapshot.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_period_precheck precheck
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = precheck.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_report_refresh_record refresh
               WHERE refresh.company_id = baseline_company_id
                 AND refresh.period_start = '2026-08-01'
                 AND (
                     NOT EXISTS (
                         SELECT 1
                         FROM attendance_report_projection current_projection
                         WHERE current_projection.attendance_report_projection_id
                               = refresh.attendance_report_projection_id
                           AND current_projection.company_id = baseline_company_id
                           AND current_projection.period_start = '2026-08-01'
                           AND current_projection.period_end_exclusive = '2026-09-01'
                     )
                     OR (
                         refresh.source_projection_id IS NOT NULL
                         AND NOT EXISTS (
                             SELECT 1
                             FROM attendance_report_projection source_projection
                             WHERE source_projection.attendance_report_projection_id
                                   = refresh.source_projection_id
                               AND source_projection.company_id = baseline_company_id
                               AND source_projection.period_start = '2026-08-01'
                               AND source_projection.period_end_exclusive = '2026-09-01'
                         )
                     )
                 )
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Synthetic acceptance row counts changed; manual review required';
        END IF;
    END IF;

    UPDATE company
    SET status = 'INACTIVE'
    WHERE company_id = baseline_company_id
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
      AND status = 'ACTIVE';

    IF synthetic_employee_id IS NOT NULL THEN
        DELETE reply
        FROM attendance_feedback_reply reply
        JOIN attendance_feedback feedback
          ON feedback.attendance_feedback_id = reply.attendance_feedback_id
        WHERE feedback.employee_id = synthetic_employee_id;

        DELETE FROM attendance_feedback
        WHERE employee_id = synthetic_employee_id;

        DELETE FROM session_revocation WHERE account_id = synthetic_account_id;
        DELETE FROM user_session WHERE account_id = synthetic_account_id;
        DELETE FROM login_failure_window WHERE account_id = synthetic_account_id;
        DELETE FROM password_reset_grant WHERE account_id = synthetic_account_id;
        DELETE FROM password_credential WHERE account_id = synthetic_account_id;
        DELETE FROM auth_principal_role_assignment
        WHERE principal_id = synthetic_principal_id;
        DELETE FROM local_account WHERE account_id = synthetic_account_id;

        DELETE FROM attendance_report_exception_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_oa_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_time_account_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_daily_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM annual_leave_entitlement_projection
        WHERE employee_id = synthetic_employee_id;

        DELETE ledger
        FROM time_account_ledger_entry ledger
        JOIN time_account account
          ON account.time_account_id = ledger.time_account_id
        WHERE account.employee_id = synthetic_employee_id;
        DELETE FROM time_account WHERE employee_id = synthetic_employee_id;

        DELETE timeline
        FROM attendance_assignment_timeline timeline
        JOIN attendance_group_assignment assignment
          ON assignment.attendance_group_assignment_id
             = timeline.attendance_group_assignment_id
        WHERE assignment.employee_id = synthetic_employee_id;
        DELETE FROM attendance_group_assignment
        WHERE employee_id = synthetic_employee_id;

        DELETE FROM attendance_setup_idempotency
        WHERE operation_code = 'CREATE_ASSIGNMENT'
          AND resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
          AND resource_id = synthetic_employee_id;
        DELETE FROM people_idempotency_record
        WHERE action_code = 'EMPLOYEE_CREATE'
          AND resource_id = synthetic_employee_id;

        DELETE FROM attendance_report_refresh_record
        WHERE company_id = baseline_company_id
          AND period_start = '2026-08-01';
        DELETE FROM attendance_report_projection
        WHERE company_id = baseline_company_id
          AND period_start = '2026-08-01'
          AND period_end_exclusive = '2026-09-01';

        DELETE FROM employee_current_projection
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employee_source_binding
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employee_version
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employment_assignment
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employment_period_identity
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM auth_principal WHERE principal_id = synthetic_principal_id;
        DELETE FROM employee WHERE employee_id = synthetic_employee_id;

        DELETE FROM organization_current_closure
        WHERE ancestor_organization_id = synthetic_organization_id
           OR descendant_organization_id = synthetic_organization_id;
        DELETE FROM organization_current_projection
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_source_binding
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_version
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_identity
        WHERE organization_id = synthetic_organization_id;

        -- Audit events intentionally remain append-only. Their string
        -- references can outlive a deleted acceptance fixture without
        -- blocking referential cleanup.
    END IF;

    DELETE role_capability
    FROM auth_role_capability role_capability
    JOIN auth_role role ON role.role_id = role_capability.role_id
    WHERE role.role_code IN (
        'MANUFACTURING_SUPERVISOR',
        'MANUFACTURING_CENTER_SUPERVISOR',
        'MANUFACTURING_DIRECTOR',
        'MANUFACTURING_CENTER_DIRECTOR'
    );

    DELETE FROM auth_role
    WHERE role_code IN (
        'MANUFACTURING_SUPERVISOR',
        'MANUFACTURING_CENTER_SUPERVISOR',
        'MANUFACTURING_DIRECTOR',
        'MANUFACTURING_CENTER_DIRECTOR'
    );

    COMMIT;
END$$

DELIMITER ;

CALL finalize_usability_post_v30();
DROP PROCEDURE finalize_usability_post_v30;

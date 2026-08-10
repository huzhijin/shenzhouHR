-- V32 corrected the ATTENDANCE_RATE template enum and published a corrected
-- system-default version 3, but it did not touch company-scoped versions.  Any
-- company scope that was seeded before V32 still carries version 2 with
-- REQUIRED_ATTENDANCE_DAYS_DIVIDED_BY_ACTUAL_ATTENDANCE_DAYS, a value V32
-- removed from the template's allowed enum.  Those rows therefore violate the
-- template contract they are validated against.
--
-- AttendanceReportCalculator has always divided confirmed attendance by
-- scheduled attendance, so no published report number is affected.  This is a
-- forward-only metadata correction: it appends version 3 per affected scope and
-- leaves versions 1 and 2 byte-for-byte intact for audit.
--
-- On a database with no company scopes (fresh install, test schema) the
-- INSERT ... SELECT matches zero rows and the migration is a no-op.

INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT
    CONCAT(
        SUBSTRING(derived.digest, 1, 8), '-',
        SUBSTRING(derived.digest, 9, 4), '-',
        SUBSTRING(derived.digest, 13, 4), '-',
        SUBSTRING(derived.digest, 17, 4), '-',
        SUBSTRING(derived.digest, 21, 12)),
    derived.scope_id,
    3,
    derived.corrected_parameters,
    '1970-01-01',
    NULL,
    JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
    JSON_OBJECT(
        'scopeId', derived.scope_id,
        'companyId', derived.company_id,
        'parameters', derived.corrected_parameters,
        'policyKind', 'ATTENDANCE_RATE',
        'templateId', '25000000-0000-0000-0000-000000000008',
        'effectiveTo', NULL,
        'effectiveFrom', '1970-01-01',
        'versionNumber', 3),
    SHA2(
        CONCAT(
            'SCOPED:ATTENDANCE_RATE:3:ACTUAL_OVER_REQUIRED:',
            derived.scope_id),
        256),
    NULL,
    0,
    'V34 修正公司作用域出勤率公式方向（补 V32 遗漏）',
    '20000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP(6)
FROM (
    SELECT
        scope.scope_id AS scope_id,
        scope.company_id AS company_id,
        SHA2(
            CONCAT('V34:ATTENDANCE_RATE:', scope.scope_id),
            256) AS digest,
        JSON_OBJECT(
            'enabled', TRUE,
            'paidLeaveCountsAsAttendance', TRUE,
            'departmentFromEmployeeOrganization', TRUE,
            'formulaStatus', 'CONFIRMED',
            'formulaCode',
                'ACTUAL_ATTENDANCE_DAYS_DIVIDED_BY_REQUIRED_ATTENDANCE_DAYS',
            'zeroActualDaysTreatment', 'NOT_APPLICABLE'
        ) AS corrected_parameters
    FROM attendance_policy_scope scope
    WHERE scope.policy_template_id
              = '25000000-0000-0000-0000-000000000008'
      AND EXISTS (
          SELECT 1
          FROM attendance_policy_scoped_version stale
          WHERE stale.scope_id = scope.scope_id
            AND JSON_UNQUOTE(
                    JSON_EXTRACT(stale.parameters_json, '$.formulaCode'))
                = 'REQUIRED_ATTENDANCE_DAYS_DIVIDED_BY_ACTUAL_ATTENDANCE_DAYS'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM attendance_policy_scoped_version corrected
          WHERE corrected.scope_id = scope.scope_id
            AND JSON_UNQUOTE(
                    JSON_EXTRACT(corrected.parameters_json, '$.formulaCode'))
                = 'ACTUAL_ATTENDANCE_DAYS_DIVIDED_BY_REQUIRED_ATTENDANCE_DAYS'
      )
) derived;

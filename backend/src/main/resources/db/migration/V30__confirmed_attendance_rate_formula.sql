-- Publish the user-confirmed attendance-rate formula without rewriting the
-- immutable V24 pending-confirmation defaults or historical report snapshots.

UPDATE attendance_policy_template
SET description = '应出勤天数除以实际出勤天数；带薪假计入实际出勤，零实际出勤显示不适用',
    field_definitions_json = JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE,
            'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'paidLeaveCountsAsAttendance',
            'label', '带薪假计出勤', 'valueType', 'BOOLEAN',
            'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'departmentFromEmployeeOrganization',
            'label', '部门取员工组织', 'valueType', 'BOOLEAN',
            'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'formulaStatus', 'label', '公式状态',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('CONFIRMED')),
        JSON_OBJECT('key', 'formulaCode', 'label', '公式口径',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY(
                'REQUIRED_ATTENDANCE_DAYS_DIVIDED_BY_ACTUAL_ATTENDANCE_DAYS')),
        JSON_OBJECT('key', 'zeroActualDaysTreatment',
            'label', '零分母处理', 'valueType', 'ENUM',
            'required', TRUE,
            'enumValues', JSON_ARRAY('NOT_APPLICABLE'))
    )
WHERE policy_template_id = '25000000-0000-0000-0000-000000000008'
  AND template_code = 'ATTENDANCE_RATE';

INSERT INTO attendance_policy_system_default (
    system_default_id, policy_template_id, version_number, parameters_json,
    effective_from, effective_to, snapshot_digest, change_reason,
    created_by, created_at
) VALUES (
    '25400000-0000-0000-0000-000000000009',
    '25000000-0000-0000-0000-000000000008',
    2,
    JSON_OBJECT(
        'enabled', TRUE,
        'paidLeaveCountsAsAttendance', TRUE,
        'departmentFromEmployeeOrganization', TRUE,
        'formulaStatus', 'CONFIRMED',
        'formulaCode',
            'REQUIRED_ATTENDANCE_DAYS_DIVIDED_BY_ACTUAL_ATTENDANCE_DAYS',
        'zeroActualDaysTreatment', 'NOT_APPLICABLE'),
    '1970-01-01', NULL,
    SHA2('SYSTEM_DEFAULT:ATTENDANCE_RATE:2:REQUIRED_OVER_ACTUAL', 256),
    'V30 已确认出勤率公式系统初始值',
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
);

-- Upgrade only the untouched V24 pending-confirmation company baseline.  A
-- customer-authored version is deliberately left immutable; when it still has
-- PENDING_CONFIRMATION the resolver ignores it and falls back to this confirmed
-- system default until an administrator publishes an explicit confirmed
-- successor.
INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT UUID(), scope.scope_id, legacy.version_number + 1,
       defaults.parameters_json, defaults.effective_from,
       defaults.effective_to,
       JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
       JSON_OBJECT(
           'companyId', scope.company_id,
           'effectiveFrom', defaults.effective_from,
           'effectiveTo', defaults.effective_to,
           'parameters', defaults.parameters_json,
           'policyKind', template.template_code,
           'provenance', 'SYSTEM_DEFAULT_CONFIRMED_FORMULA_SUCCESSOR',
           'scopeId', scope.scope_id,
           'templateId', template.policy_template_id,
           'versionNumber', legacy.version_number + 1
       ),
       SHA2(CONCAT(
           'V30:ATTENDANCE_RATE:CONFIRMED:', scope.scope_id, ':',
           legacy.version_number + 1), 256),
       NULL, 1,
       'V30 已确认出勤率公式公司后继版本',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM attendance_policy_scope scope
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
JOIN attendance_policy_scoped_version legacy
  ON legacy.scope_id = scope.scope_id
 AND legacy.version_number = 1
 AND legacy.snapshot_digest =
     SHA2('SYSTEM_DEFAULT:ATTENDANCE_RATE:1', 256)
JOIN attendance_policy_system_default defaults
  ON defaults.policy_template_id = template.policy_template_id
 AND defaults.version_number = 2
WHERE template.template_code = 'ATTENDANCE_RATE'
  AND 1 = (
      SELECT COUNT(*)
      FROM attendance_policy_lifecycle_event baseline_history
      WHERE baseline_history.scope_id = scope.scope_id
        AND baseline_history.scoped_version_id = legacy.scoped_version_id
        AND baseline_history.action = 'PUBLISHED'
  )
  AND 1 = (
      SELECT COUNT(*)
      FROM attendance_policy_lifecycle_event scope_history
      WHERE scope_history.scope_id = scope.scope_id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_scoped_version newer
      WHERE newer.scope_id = scope.scope_id
        AND newer.version_number > legacy.version_number
  );

-- Close the untouched predecessor at the same business boundary before the
-- confirmed successor is published.  This mirrors the regular lifecycle
-- publish operation and guarantees one active version per scope.
INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    action, business_effective_from, predecessor_event_id, reason,
    actor_id, request_id, recorded_at
)
SELECT UUID(), legacy.scope_id, legacy.scoped_version_id,
       COALESCE((
           SELECT MAX(existing.event_sequence)
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = legacy.scope_id
       ), 0) + 1,
       'DEACTIVATE_SCHEDULED', successor.effective_from,
       (
           SELECT existing.lifecycle_event_id
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = legacy.scope_id
           ORDER BY existing.event_sequence DESC
           LIMIT 1
       ),
       'V30 已确认出勤率公式替代待确认基线',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V30-AR-DEACTIVATE-', legacy.scoped_version_id),
       CURRENT_TIMESTAMP(6)
FROM attendance_policy_scoped_version successor
JOIN attendance_policy_scoped_version legacy
  ON legacy.scope_id = successor.scope_id
 AND legacy.version_number + 1 = successor.version_number
 AND legacy.snapshot_digest =
     SHA2('SYSTEM_DEFAULT:ATTENDANCE_RATE:1', 256)
WHERE successor.change_reason = 'V30 已确认出勤率公式公司后继版本'
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_lifecycle_event existing
      WHERE existing.scope_id = legacy.scope_id
        AND existing.scoped_version_id = legacy.scoped_version_id
        AND existing.action = 'DEACTIVATE_SCHEDULED'
  );

INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    action, business_effective_from, predecessor_event_id, reason,
    actor_id, request_id, recorded_at
)
SELECT UUID(), successor.scope_id, successor.scoped_version_id,
       COALESCE((
           SELECT MAX(existing.event_sequence)
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = successor.scope_id
       ), 0) + 1,
       'PUBLISHED', successor.effective_from,
       (
           SELECT existing.lifecycle_event_id
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = successor.scope_id
           ORDER BY existing.event_sequence DESC
           LIMIT 1
       ),
       'V30 已确认出勤率公式公司后继版本发布',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V30-AR-PUBLISH-', successor.scoped_version_id),
       CURRENT_TIMESTAMP(6)
FROM attendance_policy_scoped_version successor
JOIN attendance_policy_scope scope
  ON scope.scope_id = successor.scope_id
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
WHERE template.template_code = 'ATTENDANCE_RATE'
  AND successor.change_reason = 'V30 已确认出勤率公式公司后继版本'
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_lifecycle_event existing
      WHERE existing.scoped_version_id = successor.scoped_version_id
  );

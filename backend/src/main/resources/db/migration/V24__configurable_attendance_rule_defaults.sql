-- Configurable attendance-rule catalog.  Existing values become published
-- initial values; changing a value creates a new scoped version and never
-- rewrites a previously published snapshot.

ALTER TABLE attendance_policy_template
    DROP CHECK ck_attendance_policy_template_code;

ALTER TABLE attendance_policy_template
    ADD CONSTRAINT ck_attendance_policy_template_code CHECK (
        template_code IN (
            'MEAL_DEDUCTION',
            'LATE_GRACE',
            'MONTHLY_LATE_EXEMPTION',
            'EARLY_DEPARTURE',
            'MISSING_PUNCH',
            'PUNCH_ARBITRATION',
            'OVERTIME_RECOGNITION',
            'ATTENDANCE_RATE'
        )
    );

ALTER TABLE attendance_policy_binding_family
    DROP CHECK ck_attendance_policy_binding_family_kind;

ALTER TABLE attendance_policy_binding_family
    ADD CONSTRAINT ck_attendance_policy_binding_family_kind CHECK (
        policy_kind IN (
            'MEAL_DEDUCTION',
            'LATE_GRACE',
            'MONTHLY_LATE_EXEMPTION',
            'EARLY_DEPARTURE',
            'MISSING_PUNCH',
            'PUNCH_ARBITRATION',
            'OVERTIME_RECOGNITION',
            'ATTENDANCE_RATE'
        )
    );

-- A single immutable version can select the Summer/Winter dinner window by
-- business-date month/day. This avoids recurring effective-date rows while
-- keeping the selector, boundaries and both windows administratively editable.
UPDATE attendance_policy_template
SET description = '按日期类型及可配置冬夏令区间扣除午餐和晚餐休息时间',
    field_definitions_json = JSON_ARRAY_APPEND(
        field_definitions_json,
        '$', JSON_OBJECT(
            'key', 'winterDinnerWindowStart',
            'label', '冬令晚餐窗口开始', 'valueType', 'LOCAL_TIME',
            'required', FALSE, 'enumValues', JSON_ARRAY()),
        '$', JSON_OBJECT(
            'key', 'winterDinnerWindowEnd',
            'label', '冬令晚餐窗口结束', 'valueType', 'LOCAL_TIME',
            'required', FALSE, 'enumValues', JSON_ARRAY()),
        '$', JSON_OBJECT(
            'key', 'summerDinnerWindowStart',
            'label', '夏令晚餐窗口开始', 'valueType', 'LOCAL_TIME',
            'required', FALSE, 'enumValues', JSON_ARRAY()),
        '$', JSON_OBJECT(
            'key', 'summerDinnerWindowEnd',
            'label', '夏令晚餐窗口结束', 'valueType', 'LOCAL_TIME',
            'required', FALSE, 'enumValues', JSON_ARRAY())
    )
WHERE template_code = 'MEAL_DEDUCTION';

UPDATE attendance_policy_template
SET description = '迟到宽限分钟；初始值15分钟，可通过版本化策略调整',
    field_definitions_json = JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE,
            'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'graceMinutes', 'label', '宽限分钟',
            'valueType', 'INTEGER', 'required', TRUE,
            'enumValues', JSON_ARRAY(), 'minimum', 0, 'maximum', 240)
    )
WHERE template_code = 'LATE_GRACE';

UPDATE attendance_policy_template
SET description = '自然月迟到豁免次数；迟到分钟边界统一取迟到规则，初始每月1次且换组不重置，可版本化调整',
    field_definitions_json = JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE,
            'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'monthlyUses', 'label', '每月次数',
            'valueType', 'INTEGER', 'required', TRUE,
            'enumValues', JSON_ARRAY(), 'minimum', 0, 'maximum', 31),
        JSON_OBJECT('key', 'resetOnGroupChange', 'label', '换组重置',
            'valueType', 'BOOLEAN', 'required', TRUE,
            'enumValues', JSON_ARRAY())
    )
WHERE template_code = 'MONTHLY_LATE_EXEMPTION';

INSERT INTO attendance_policy_template (
    policy_template_id, template_code, name, description,
    field_definitions_json, created_by, created_at
) VALUES
(
    '25000000-0000-0000-0000-000000000004',
    'EARLY_DEPARTURE', '早退规则',
    '初始宽限0分钟；可版本化调整',
    JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'graceMinutes', 'label', '宽限分钟',
            'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
            'minimum', 0, 'maximum', 240)
    ),
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
),
(
    '25000000-0000-0000-0000-000000000005',
    'MISSING_PUNCH', '缺卡与补签规则',
    '初始补签窗口7个完整自然日，仅影响缺卡工作段',
    JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'correctionWindowDays', 'label', '补签窗口天数',
            'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
            'minimum', 0, 'maximum', 365),
        JSON_OBJECT('key', 'deadlineMode', 'label', '截止方式',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('NEXT_DAY_START_AFTER_FULL_DAYS')),
        JSON_OBJECT('key', 'overdueScope', 'label', '逾期影响范围',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('AFFECTED_SEGMENT_ONLY'))
    ),
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
),
(
    '25000000-0000-0000-0000-000000000006',
    'PUNCH_ARBITRATION', '打卡归属与配对规则',
    '初始跨日切点06:00、完成等待30分钟、最短打卡跨度30分钟',
    JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'crossDayCutoff', 'label', '跨日切点',
            'valueType', 'LOCAL_TIME', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'completionGraceMinutes', 'label', '完成等待分钟',
            'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
            'minimum', 0, 'maximum', 1440),
        JSON_OBJECT('key', 'minimumPunchSpanMinutes', 'label', '最短打卡跨度分钟',
            'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
            'minimum', 0, 'maximum', 1440),
        JSON_OBJECT('key', 'pairingMode', 'label', '配对模式',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('STRICT_OR_AUTO'))
    ),
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
),
(
    '25000000-0000-0000-0000-000000000007',
    'OVERTIME_RECOGNITION', '加班认定规则',
    '初始要求审批、48小时内提交；无已审批加班单按0加班，义务加班不计加班费',
    JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'approvalRequired', 'label', '要求审批',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'submissionDeadlineHours', 'label', '提交时限小时',
            'valueType', 'INTEGER', 'required', TRUE, 'enumValues', JSON_ARRAY(),
            'minimum', 0, 'maximum', 720),
        JSON_OBJECT('key', 'unsignedTreatment', 'label', '无单处理',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('ZERO')),
        JSON_OBJECT('key', 'dutyOvertimePaid', 'label', '义务加班计加班费',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY())
    ),
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
),
(
    '25000000-0000-0000-0000-000000000008',
    'ATTENDANCE_RATE', '出勤率口径',
    '已确认带薪假计出勤、部门取员工组织；完整公式待确认前不进入正式计算',
    JSON_ARRAY(
        JSON_OBJECT('key', 'enabled', 'label', '是否启用',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'paidLeaveCountsAsAttendance', 'label', '带薪假计出勤',
            'valueType', 'BOOLEAN', 'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'departmentFromEmployeeOrganization',
            'label', '部门取员工组织', 'valueType', 'BOOLEAN',
            'required', TRUE, 'enumValues', JSON_ARRAY()),
        JSON_OBJECT('key', 'formulaStatus', 'label', '公式状态',
            'valueType', 'ENUM', 'required', TRUE,
            'enumValues', JSON_ARRAY('PENDING_CONFIRMATION'))
    ),
    '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
);

CREATE TABLE attendance_policy_system_default (
    system_default_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number INT UNSIGNED NOT NULL,
    parameters_json JSON NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    snapshot_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (system_default_id),
    UNIQUE KEY uq_attendance_policy_system_default_version
        (policy_template_id, version_number),
    KEY ix_attendance_policy_system_default_resolution
        (policy_template_id, effective_from, effective_to),
    CONSTRAINT fk_attendance_policy_system_default_template
        FOREIGN KEY (policy_template_id)
        REFERENCES attendance_policy_template (policy_template_id),
    CONSTRAINT fk_attendance_policy_system_default_actor
        FOREIGN KEY (created_by) REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_attendance_policy_system_default_period CHECK (
        effective_to IS NULL OR effective_to > effective_from
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO attendance_policy_system_default (
    system_default_id, policy_template_id, version_number, parameters_json,
    effective_from, effective_to, snapshot_digest, change_reason,
    created_by, created_at
) VALUES
('25400000-0000-0000-0000-000000000001', '25000000-0000-0000-0000-000000000001', 1,
 JSON_OBJECT(
    'applicableDayTypes', JSON_ARRAY(
        'WORKDAY', 'SPECIAL_WORKDAY', 'WEEKEND', 'PUBLIC_HOLIDAY'),
    'deductionMinutes', 30, 'enabled', TRUE,
    'mealWindowStart', '17:30', 'mealWindowEnd', '18:00',
    'triggerMinutes', 0,
    'seasonMode', 'SUMMER_MONTH_DAY_RANGE',
    'summerStartMonth', 5, 'summerStartDay', 1,
    'summerEndMonth', 10, 'summerEndDay', 1,
    'winterDinnerWindowStart', '17:30',
    'winterDinnerWindowEnd', '18:00',
    'summerDinnerWindowStart', '18:00',
    'summerDinnerWindowEnd', '18:30',
    'saturdayMealWindowStart', '17:30',
    'saturdayMealWindowEnd', '18:00',
    'saturdayDeductionMinutes', 30, 'saturdayTriggerMinutes', 0,
    'saturdayLunchWindowStart', '12:00',
    'saturdayLunchWindowEnd', '13:00',
    'saturdayLunchDeductionMinutes', 60,
    'saturdayLunchTriggerMinutes', 0,
    'sundayMealWindowStart', '17:30',
    'sundayMealWindowEnd', '18:00',
    'sundayDeductionMinutes', 30, 'sundayTriggerMinutes', 0,
    'sundayLunchWindowStart', '12:00',
    'sundayLunchWindowEnd', '13:00',
    'sundayLunchDeductionMinutes', 60,
    'sundayLunchTriggerMinutes', 0,
    'publicHolidayMealWindowStart', '17:30',
    'publicHolidayMealWindowEnd', '18:00',
    'publicHolidayDeductionMinutes', 30,
    'publicHolidayTriggerMinutes', 0,
    'publicHolidayLunchWindowStart', '12:00',
    'publicHolidayLunchWindowEnd', '13:00',
    'publicHolidayLunchDeductionMinutes', 60,
    'publicHolidayLunchTriggerMinutes', 0),
 '1970-01-01', NULL,
 SHA2('SYSTEM_DEFAULT:MEAL_DEDUCTION:1:SEASONAL', 256),
 'V24 冬夏令餐休系统初始值',
 '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000002', '25000000-0000-0000-0000-000000000002', 1,
 JSON_OBJECT('enabled', TRUE, 'graceMinutes', 15),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:LATE_GRACE:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000003', '25000000-0000-0000-0000-000000000003', 1,
 JSON_OBJECT('enabled', TRUE, 'graceMinutes', 15, 'monthlyUses', 1,
    'resetOnGroupChange', FALSE),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:MONTHLY_LATE_EXEMPTION:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000004', '25000000-0000-0000-0000-000000000004', 1,
 JSON_OBJECT('enabled', TRUE, 'graceMinutes', 0),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:EARLY_DEPARTURE:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000005', '25000000-0000-0000-0000-000000000005', 1,
 JSON_OBJECT('enabled', TRUE, 'correctionWindowDays', 7,
    'deadlineMode', 'NEXT_DAY_START_AFTER_FULL_DAYS',
    'overdueScope', 'AFFECTED_SEGMENT_ONLY'),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:MISSING_PUNCH:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000006', '25000000-0000-0000-0000-000000000006', 1,
 JSON_OBJECT('enabled', TRUE, 'crossDayCutoff', '06:00',
    'completionGraceMinutes', 30, 'minimumPunchSpanMinutes', 30,
    'pairingMode', 'STRICT_OR_AUTO'),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:PUNCH_ARBITRATION:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000007', '25000000-0000-0000-0000-000000000007', 1,
 JSON_OBJECT('enabled', TRUE, 'approvalRequired', TRUE,
    'submissionDeadlineHours', 48, 'unsignedTreatment', 'ZERO',
    'dutyOvertimePaid', FALSE),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:OVERTIME_RECOGNITION:1', 256),
 'V24 系统初始值', '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)),
('25400000-0000-0000-0000-000000000008', '25000000-0000-0000-0000-000000000008', 1,
 JSON_OBJECT('enabled', TRUE, 'paidLeaveCountsAsAttendance', TRUE,
    'departmentFromEmployeeOrganization', TRUE,
    'formulaStatus', 'PENDING_CONFIRMATION'),
 '1970-01-01', NULL, SHA2('SYSTEM_DEFAULT:ATTENDANCE_RATE:1', 256),
 'V24 初始值；完整出勤率公式待确认，不参与正式计算',
 '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6));

-- Give every company present at migration time an editable scope for the new
-- policy kinds.  The system defaults remain the fail-closed fallback for a
-- company that has not run provisioning yet.
INSERT INTO attendance_policy_scope (
    scope_id, policy_template_id, company_id, row_version,
    created_by, created_at
)
SELECT UUID(), template.policy_template_id, company.company_id, 0,
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM company
CROSS JOIN attendance_policy_template template
WHERE template.template_code IN (
    'MEAL_DEDUCTION', 'EARLY_DEPARTURE', 'MISSING_PUNCH', 'PUNCH_ARBITRATION',
    'OVERTIME_RECOGNITION', 'ATTENDANCE_RATE'
)
AND NOT EXISTS (
    SELECT 1
    FROM attendance_policy_scope existing
    WHERE existing.policy_template_id = template.policy_template_id
      AND existing.company_id = company.company_id
);

INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT UUID(), scope.scope_id, 1, defaults.parameters_json,
       defaults.effective_from, defaults.effective_to,
       JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
       JSON_OBJECT(
           'companyId', scope.company_id,
           'effectiveFrom', defaults.effective_from,
           'effectiveTo', defaults.effective_to,
           'parameters', defaults.parameters_json,
           'policyKind', template.template_code,
           'scopeId', scope.scope_id,
           'templateId', template.policy_template_id,
           'versionNumber', 1
       ),
       defaults.snapshot_digest, NULL, 1,
       'V24 公司可编辑初始值',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM attendance_policy_scope scope
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
JOIN attendance_policy_system_default defaults
  ON defaults.policy_template_id = template.policy_template_id
 AND defaults.version_number = 1
WHERE template.template_code IN (
    'MEAL_DEDUCTION', 'EARLY_DEPARTURE', 'MISSING_PUNCH', 'PUNCH_ARBITRATION',
    'OVERTIME_RECOGNITION', 'ATTENDANCE_RATE'
)
AND NOT EXISTS (
    SELECT 1 FROM attendance_policy_scoped_version existing
    WHERE existing.scope_id = scope.scope_id
);

INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    action, business_effective_from, predecessor_event_id, reason,
    actor_id, request_id, recorded_at
)
SELECT UUID(), version.scope_id, version.scoped_version_id, 1,
       'PUBLISHED', version.effective_from, NULL,
       'V24 公司可编辑初始值',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V24-', version.scoped_version_id), CURRENT_TIMESTAMP(6)
FROM attendance_policy_scoped_version version
JOIN attendance_policy_scope scope ON scope.scope_id = version.scope_id
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
WHERE version.version_number = 1
  AND template.template_code IN (
      'MEAL_DEDUCTION', 'EARLY_DEPARTURE', 'MISSING_PUNCH', 'PUNCH_ARBITRATION',
      'OVERTIME_RECOGNITION', 'ATTENDANCE_RATE'
  )
  AND NOT EXISTS (
      SELECT 1 FROM attendance_policy_lifecycle_event existing
      WHERE existing.scope_id = version.scope_id
  );

-- Upgrade only the untouched V7 company baseline. Customer-authored meal
-- versions are deliberately excluded and remain immutable.
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
           'provenance', 'SYSTEM_DEFAULT_SEASONAL_SUCCESSOR',
           'scopeId', scope.scope_id,
           'templateId', template.policy_template_id,
           'versionNumber', legacy.version_number + 1
       ),
       defaults.snapshot_digest, NULL, 1,
       'V24 冬夏令餐休公司初始值',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM attendance_policy_scope scope
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
JOIN attendance_policy_scoped_version legacy
  ON legacy.scope_id = scope.scope_id
 AND legacy.version_number = 1
 AND legacy.snapshot_digest =
     '4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981'
JOIN attendance_policy_system_default defaults
  ON defaults.policy_template_id = template.policy_template_id
 AND defaults.version_number = 1
WHERE template.template_code = 'MEAL_DEDUCTION'
  AND 1 = (
      SELECT COUNT(*)
      FROM attendance_policy_lifecycle_event baseline_event
      WHERE baseline_event.scope_id = scope.scope_id
        AND baseline_event.scoped_version_id = legacy.scoped_version_id
        AND baseline_event.action = 'PUBLISHED'
  )
  AND 1 = (
      SELECT COUNT(*)
      FROM attendance_policy_lifecycle_event baseline_history
      WHERE baseline_history.scope_id = scope.scope_id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_scoped_version newer
      WHERE newer.scope_id = scope.scope_id
        AND newer.version_number > legacy.version_number
  );

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
       'V24 冬夏令餐休公司初始值替代旧基线',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V24-SEASONAL-DEACTIVATE-', legacy.scoped_version_id),
       CURRENT_TIMESTAMP(6)
FROM attendance_policy_scoped_version successor
JOIN attendance_policy_scoped_version legacy
  ON legacy.scope_id = successor.scope_id
 AND legacy.version_number + 1 = successor.version_number
 AND legacy.snapshot_digest =
     '4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981'
WHERE successor.change_reason = 'V24 冬夏令餐休公司初始值'
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
SELECT UUID(), version.scope_id, version.scoped_version_id,
       COALESCE((
           SELECT MAX(existing.event_sequence)
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = version.scope_id
       ), 0) + 1,
       'PUBLISHED', version.effective_from,
       (
           SELECT existing.lifecycle_event_id
           FROM attendance_policy_lifecycle_event existing
           WHERE existing.scope_id = version.scope_id
           ORDER BY existing.event_sequence DESC
           LIMIT 1
       ),
       'V24 冬夏令餐休公司初始值发布',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V24-SEASONAL-', version.scoped_version_id),
       CURRENT_TIMESTAMP(6)
FROM attendance_policy_scoped_version version
JOIN attendance_policy_scope scope ON scope.scope_id = version.scope_id
JOIN attendance_policy_template template
  ON template.policy_template_id = scope.policy_template_id
WHERE template.template_code = 'MEAL_DEDUCTION'
  AND version.change_reason = 'V24 冬夏令餐休公司初始值'
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_lifecycle_event existing
      WHERE existing.scoped_version_id = version.scoped_version_id
  );

-- Two new administratively editable rule templates confirmed by the customer
-- on 2026-08-09.
--
-- PUNCH_WINDOW closes a real correctness gap.  ScheduledWorkSegment requires
-- arrivalWindow and departureWindow, but those two components existed only
-- inside the calculation domain (AttendanceCalculationModels record components,
-- DeterministicAttendanceCalculator.selectPunches, CanonicalAttendanceDigests)
-- with no column, no policy parameter and no mapper behind them.  The record
-- constructor only requires each window to OVERLAP its segment, so setting a
-- window equal to the segment interval passes validation -- and then every
-- employee who clocks in before the segment start falls outside the window,
-- selectPunches returns no arrival, and the whole segment is routed to
-- MISSING_PUNCH with zero attendance minutes.  That failure is silent.  Making
-- the window width a managed rule gives the parameter a single authoritative
-- source instead of leaving it to be invented per caller.
--
-- PERIOD_CLOSE parameterises month-end close so the close day, reopen
-- permission, approval requirement and reopen limit are administratively
-- editable rather than hard-coded.
--
-- Both templates follow the V24 pattern: widen the two CHECK constraints,
-- insert the template, seed a system default, then create one scope plus one
-- PUBLISHED scoped version per existing company.  On a database with no
-- companies (fresh install, test schema) the per-company INSERT ... SELECT
-- statements match zero rows and the migration stays a no-op.  Every statement
-- is guarded so replay changes nothing.

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
            'ATTENDANCE_RATE',
            'PUNCH_WINDOW',
            'PERIOD_CLOSE'
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
            'ATTENDANCE_RATE',
            'PUNCH_WINDOW',
            'PERIOD_CLOSE'
        )
    );

-- PUNCH_WINDOW template.  Customer-confirmed initial values: arrival window
-- spans 60 minutes before to 60 minutes after the segment start; departure
-- window spans 60 minutes before to 120 minutes after the segment end, the
-- wider tail leaving room for overtime departures.
INSERT INTO attendance_policy_template (
    policy_template_id, template_code, name, description,
    field_definitions_json, created_by, created_at
)
SELECT '25000000-0000-0000-0000-000000000009',
       'PUNCH_WINDOW',
       '打卡取卡窗口',
       '每个班段认领上下班打卡的时间窗口宽度；窗口过窄会导致提前到岗的打卡不被认领并判为缺卡',
       JSON_ARRAY(
           JSON_OBJECT('key', 'enabled', 'label', '是否启用',
               'valueType', 'BOOLEAN', 'required', TRUE,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'arrivalBeforeMinutes', 'label', '上班窗口提前分钟',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 0, 'maximum', 720,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'arrivalAfterMinutes', 'label', '上班窗口延后分钟',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 0, 'maximum', 720,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'departureBeforeMinutes', 'label', '下班窗口提前分钟',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 0, 'maximum', 720,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'departureAfterMinutes', 'label', '下班窗口延后分钟',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 0, 'maximum', 720,
               'enumValues', JSON_ARRAY())
       ),
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_template existing
    WHERE existing.template_code = 'PUNCH_WINDOW'
);

-- PERIOD_CLOSE template.  Customer-confirmed initial values: close on day 5 of
-- the following month, reopen allowed, no approval required, unlimited reopens
-- (maxReopenCount 0 means unlimited).
INSERT INTO attendance_policy_template (
    policy_template_id, template_code, name, description,
    field_definitions_json, created_by, created_at
)
SELECT '25000000-0000-0000-0000-00000000000a',
       'PERIOD_CLOSE',
       '月结封账',
       '月结封账日、是否允许重开、重开是否需要审批及重开次数上限',
       JSON_ARRAY(
           JSON_OBJECT('key', 'enabled', 'label', '是否启用',
               'valueType', 'BOOLEAN', 'required', TRUE,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'closeDayOfNextMonth', 'label', '次月封账日',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 1, 'maximum', 28,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'reopenAllowed', 'label', '允许重开',
               'valueType', 'BOOLEAN', 'required', TRUE,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'reopenRequiresApproval', 'label', '重开需要审批',
               'valueType', 'BOOLEAN', 'required', TRUE,
               'enumValues', JSON_ARRAY()),
           JSON_OBJECT('key', 'maxReopenCount', 'label', '重开次数上限（0=不限）',
               'valueType', 'INTEGER', 'required', TRUE,
               'minimum', 0, 'maximum', 99,
               'enumValues', JSON_ARRAY())
       ),
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_template existing
    WHERE existing.template_code = 'PERIOD_CLOSE'
);

INSERT INTO attendance_policy_system_default (
    system_default_id, policy_template_id, version_number, parameters_json,
    effective_from, effective_to, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT '25400000-0000-0000-0000-00000000000b',
       '25000000-0000-0000-0000-000000000009',
       1,
       JSON_OBJECT(
           'enabled', TRUE,
           'arrivalBeforeMinutes', 60,
           'arrivalAfterMinutes', 60,
           'departureBeforeMinutes', 60,
           'departureAfterMinutes', 120),
       '1970-01-01', NULL,
       SHA2('SYSTEM_DEFAULT:PUNCH_WINDOW:1:60_60_60_120', 256),
       'V35 打卡取卡窗口初始值（客户 2026-08-09 确认）',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
WHERE EXISTS (
    SELECT 1 FROM attendance_policy_template template
    WHERE template.policy_template_id
              = '25000000-0000-0000-0000-000000000009'
) AND NOT EXISTS (
    SELECT 1 FROM attendance_policy_system_default existing
    WHERE existing.policy_template_id
              = '25000000-0000-0000-0000-000000000009'
);

INSERT INTO attendance_policy_system_default (
    system_default_id, policy_template_id, version_number, parameters_json,
    effective_from, effective_to, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT '25400000-0000-0000-0000-00000000000c',
       '25000000-0000-0000-0000-00000000000a',
       1,
       JSON_OBJECT(
           'enabled', TRUE,
           'closeDayOfNextMonth', 5,
           'reopenAllowed', TRUE,
           'reopenRequiresApproval', FALSE,
           'maxReopenCount', 0),
       '1970-01-01', NULL,
       SHA2('SYSTEM_DEFAULT:PERIOD_CLOSE:1:DAY5_REOPEN_NOAPPROVAL', 256),
       'V35 月结封账初始值（客户 2026-08-09 确认）',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
WHERE EXISTS (
    SELECT 1 FROM attendance_policy_template template
    WHERE template.policy_template_id
              = '25000000-0000-0000-0000-00000000000a'
) AND NOT EXISTS (
    SELECT 1 FROM attendance_policy_system_default existing
    WHERE existing.policy_template_id
              = '25000000-0000-0000-0000-00000000000a'
);

-- Per-company scopes.  attendance_policy_system_default currently has no reader
-- in production code, so the fail-closed fallback is unreachable and a scoped
-- version per company is what actually resolves.  Scope ids are derived
-- deterministically from the template and company so replay collides with the
-- NOT EXISTS guard instead of inserting duplicates.
INSERT INTO attendance_policy_scope (
    scope_id, policy_template_id, company_id, row_version, created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(derived.digest, 1, 8), '-',
           SUBSTRING(derived.digest, 9, 4), '-',
           SUBSTRING(derived.digest, 13, 4), '-',
           SUBSTRING(derived.digest, 17, 4), '-',
           SUBSTRING(derived.digest, 21, 12)),
       derived.policy_template_id,
       derived.company_id,
       0,
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT template.policy_template_id AS policy_template_id,
           company.company_id AS company_id,
           SHA2(CONCAT('V35:SCOPE:', template.template_code, ':',
                       company.company_id), 256) AS digest
    FROM attendance_policy_template template
    CROSS JOIN company company
    WHERE template.template_code IN ('PUNCH_WINDOW', 'PERIOD_CLOSE')
      AND NOT EXISTS (
          SELECT 1 FROM attendance_policy_scope existing
          WHERE existing.policy_template_id = template.policy_template_id
            AND existing.company_id = company.company_id
      )
) derived;

-- Per-company scoped versions carrying the same initial values as the system
-- default.
INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(derived.digest, 1, 8), '-',
           SUBSTRING(derived.digest, 9, 4), '-',
           SUBSTRING(derived.digest, 13, 4), '-',
           SUBSTRING(derived.digest, 17, 4), '-',
           SUBSTRING(derived.digest, 21, 12)),
       derived.scope_id,
       1,
       derived.parameters,
       '1970-01-01',
       NULL,
       JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
       JSON_OBJECT(
           'scopeId', derived.scope_id,
           'companyId', derived.company_id,
           'parameters', derived.parameters,
           'policyKind', derived.template_code,
           'templateId', derived.policy_template_id,
           'effectiveTo', NULL,
           'effectiveFrom', '1970-01-01',
           'versionNumber', 1),
       SHA2(CONCAT('V35:SCOPED:', derived.template_code, ':1:',
                   derived.scope_id), 256),
       NULL,
       0,
       'V35 新增规则模板初始版本（客户 2026-08-09 确认）',
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT scope.scope_id AS scope_id,
           scope.company_id AS company_id,
           template.policy_template_id AS policy_template_id,
           template.template_code AS template_code,
           SHA2(CONCAT('V35:VERSION:', template.template_code, ':',
                       scope.scope_id), 256) AS digest,
           CASE template.template_code
               WHEN 'PUNCH_WINDOW' THEN JSON_OBJECT(
                   'enabled', TRUE,
                   'arrivalBeforeMinutes', 60,
                   'arrivalAfterMinutes', 60,
                   'departureBeforeMinutes', 60,
                   'departureAfterMinutes', 120)
               ELSE JSON_OBJECT(
                   'enabled', TRUE,
                   'closeDayOfNextMonth', 5,
                   'reopenAllowed', TRUE,
                   'reopenRequiresApproval', FALSE,
                   'maxReopenCount', 0)
           END AS parameters
    FROM attendance_policy_scope scope
    JOIN attendance_policy_template template
      ON template.policy_template_id = scope.policy_template_id
    WHERE template.template_code IN ('PUNCH_WINDOW', 'PERIOD_CLOSE')
      AND NOT EXISTS (
          SELECT 1 FROM attendance_policy_scoped_version existing
          WHERE existing.scope_id = scope.scope_id
      )
) derived;

-- PUBLISHED lifecycle events.  AttendancePolicyMapper.xml#resolveBindings only
-- resolves a scoped version that has a PUBLISHED event not followed by
-- DEACTIVATE_SCHEDULED or ROLLED_BACK, so inserting the version row alone would
-- leave these templates unresolvable.
INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    action, business_effective_from, predecessor_event_id, reason,
    actor_id, request_id, recorded_at
)
SELECT CONCAT(
           SUBSTRING(derived.digest, 1, 8), '-',
           SUBSTRING(derived.digest, 9, 4), '-',
           SUBSTRING(derived.digest, 13, 4), '-',
           SUBSTRING(derived.digest, 17, 4), '-',
           SUBSTRING(derived.digest, 21, 12)),
       derived.scope_id,
       derived.scoped_version_id,
       1,
       'PUBLISHED',
       '1970-01-01',
       NULL,
       'V35 新增规则模板初始发布',
       '20000000-0000-0000-0000-000000000001',
       CONCAT('V35-PUBLISH-', derived.scoped_version_id),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT version.scope_id AS scope_id,
           version.scoped_version_id AS scoped_version_id,
           SHA2(CONCAT('V35:EVENT:', version.scoped_version_id), 256) AS digest
    FROM attendance_policy_scoped_version version
    JOIN attendance_policy_scope scope
      ON scope.scope_id = version.scope_id
    JOIN attendance_policy_template template
      ON template.policy_template_id = scope.policy_template_id
    WHERE template.template_code IN ('PUNCH_WINDOW', 'PERIOD_CLOSE')
      AND NOT EXISTS (
          SELECT 1 FROM attendance_policy_lifecycle_event existing
          WHERE existing.scoped_version_id = version.scoped_version_id
      )
) derived;

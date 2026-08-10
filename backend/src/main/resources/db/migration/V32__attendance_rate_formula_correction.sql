-- Correct the attendance-rate formula direction published by V30.  V30 recorded
-- REQUIRED_ATTENDANCE_DAYS_DIVIDED_BY_ACTUAL_ATTENDANCE_DAYS, which inverts the
-- ratio and reports above 100% whenever an employee attends fewer days than
-- required.  AttendanceReportCalculator has always divided confirmed attendance
-- by scheduled attendance, so the calculation was correct and only this
-- policy-template metadata was wrong.  V30 stays byte-for-byte intact so its
-- Flyway checksum still validates; this is a forward-only correction that
-- publishes a new confirmed system-default version.

UPDATE attendance_policy_template
SET description = '实际出勤天数除以应出勤天数（≤100%）；带薪假计入实际出勤，零应出勤显示不适用',
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
                'ACTUAL_ATTENDANCE_DAYS_DIVIDED_BY_REQUIRED_ATTENDANCE_DAYS')),
        JSON_OBJECT('key', 'zeroActualDaysTreatment',
            'label', '零分母处理', 'valueType', 'ENUM',
            'required', TRUE,
            'enumValues', JSON_ARRAY('NOT_APPLICABLE'))
    )
WHERE policy_template_id = '25000000-0000-0000-0000-000000000008'
  AND template_code = 'ATTENDANCE_RATE';

-- Version 3 supersedes V30's version 2 with the corrected direction.  The guard
-- keeps the migration idempotent when it is replayed against a database that
-- already carries the correction.
INSERT INTO attendance_policy_system_default (
    system_default_id, policy_template_id, version_number, parameters_json,
    effective_from, effective_to, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT '25400000-0000-0000-0000-00000000000a',
       template.policy_template_id,
       3,
       JSON_OBJECT(
           'enabled', TRUE,
           'paidLeaveCountsAsAttendance', TRUE,
           'departmentFromEmployeeOrganization', TRUE,
           'formulaStatus', 'CONFIRMED',
           'formulaCode',
               'ACTUAL_ATTENDANCE_DAYS_DIVIDED_BY_REQUIRED_ATTENDANCE_DAYS',
           'zeroActualDaysTreatment', 'NOT_APPLICABLE'),
       '1970-01-01', NULL,
       SHA2('SYSTEM_DEFAULT:ATTENDANCE_RATE:3:ACTUAL_OVER_REQUIRED', 256),
       'V32 修正 V30 出勤率公式方向',
       '20000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP(6)
FROM attendance_policy_template template
WHERE template.policy_template_id = '25000000-0000-0000-0000-000000000008'
  AND template.template_code = 'ATTENDANCE_RATE'
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_policy_system_default existing
      WHERE existing.version_number = 3
        AND existing.policy_template_id
            = '25000000-0000-0000-0000-000000000008'
  );

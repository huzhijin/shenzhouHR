#!/usr/bin/env bash
# 黄凯 SZST0667 / 张晨阳 SZST0663
# 得力月报里两人的工号都写成了 SZST0663，账号不同。
# 本脚本：先核对花名册和 8 月打卡；MODE=apply 时写入得力账号绑定。
# 不删打卡、不改月报钉住。绑定后必须重算 2026-08。
#
# 宝塔：
#   bash /root/fix-huangkai-zhangchenyang-deli-binding.sh
#   bash /root/fix-huangkai-zhangchenyang-deli-binding.sh apply

set -euo pipefail
MODE="${1:-preview}"
HR_DB="${HR_DB:-shenzhou_hr}"
export MYSQL_PWD="${MYSQL_PWD:-e0e17f3df673a9f8}"
HR_MYSQL=/www/server/mysql/bin/mysql
test -x "$HR_MYSQL" || HR_MYSQL=mysql

hr() {
  "$HR_MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table "$HR_DB" -e "$1"
}

echo "==== 花名册 ===="
hr "
SELECT CONVERT(employee.employee_id USING utf8mb4) AS employee_id,
       employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       organization_version.name AS 部门
FROM employee employee
JOIN employee_current_projection projection
  ON projection.employee_id = employee.employee_id
JOIN employee_version employee_version
  ON employee_version.employee_version_id = projection.current_version_id
LEFT JOIN employment_assignment employment
  ON employment.employee_id = employee.employee_id
 AND employment.version_valid_to IS NULL
 AND employment.record_status = 'ACTIVE'
LEFT JOIN organization_version organization_version
  ON organization_version.organization_id = employment.organization_id
 AND organization_version.status = 'ACTIVE'
 AND organization_version.effective_to IS NULL
WHERE employee_version.employee_number IN ('SZST0663', 'SZST0667')
ORDER BY employee_version.employee_number;
"

echo "==== 现有得力绑定 ===="
hr "
SELECT CONVERT(binding.employee_id USING utf8mb4) AS employee_id,
       employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.deli_employee_num USING utf8mb4) AS deli_empno,
       CONVERT(binding.binding_status USING utf8mb4) AS 状态,
       CONVERT(binding.deli_attendance_source_id USING utf8mb4) AS source_id
FROM employee_source_binding binding
JOIN employee employee
  ON employee.employee_id = binding.employee_id
JOIN employee_current_projection projection
  ON projection.employee_id = employee.employee_id
JOIN employee_version employee_version
  ON employee_version.employee_version_id = projection.current_version_id
WHERE employee_version.employee_number IN ('SZST0663', 'SZST0667')
   OR binding.deli_user_id IN (
        '1278767800065257473',
        '1278766765745680384'
      )
ORDER BY employee_version.employee_number, binding.effective_from;
"

echo "==== 2026-08 已入库打卡（上海时间）===="
hr "
SELECT employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       DATE_FORMAT(CONVERT_TZ(event.point_instant, '+00:00', '+08:00'), '%Y-%m-%d') AS 日,
       DATE_FORMAT(CONVERT_TZ(MIN(event.point_instant), '+00:00', '+08:00'), '%H:%i') AS 最早,
       DATE_FORMAT(CONVERT_TZ(MAX(event.point_instant), '+00:00', '+08:00'), '%H:%i') AS 最晚,
       COUNT(*) AS 卡数
FROM effective_attendance_event event
JOIN employee employee
  ON employee.employee_id = event.employee_id
JOIN employee_current_projection projection
  ON projection.employee_id = employee.employee_id
JOIN employee_version employee_version
  ON employee_version.employee_version_id = projection.current_version_id
JOIN effective_event_lifecycle_fact lifecycle
  ON lifecycle.effective_attendance_event_id = event.effective_attendance_event_id
 AND lifecycle.lifecycle_type = 'ACTIVATED'
WHERE employee_version.employee_number IN ('SZST0663', 'SZST0667')
  AND event.event_kind = 'PUNCH_POINT'
  AND CONVERT_TZ(event.point_instant, '+00:00', '+08:00') >= '2026-08-01 00:00:00'
  AND CONVERT_TZ(event.point_instant, '+00:00', '+08:00') <  '2026-09-01 00:00:00'
GROUP BY employee_version.employee_number, employee_version.display_name,
         DATE_FORMAT(CONVERT_TZ(event.point_instant, '+00:00', '+08:00'), '%Y-%m-%d')
ORDER BY 工号, 日;
"

if [[ "$MODE" != "apply" ]]; then
  echo "preview 结束。确认花名册是 663=张晨阳、667=黄凯 后执行："
  echo "  bash /root/fix-huangkai-zhangchenyang-deli-binding.sh apply"
  exit 0
fi

echo "==== 写入得力账号绑定 ===="
hr "
START TRANSACTION;

SET @source_id = (
  SELECT attendance_source_id
  FROM attendance_source
  WHERE source_type = 'DELI_CLOUD'
    AND status = 'ACTIVE'
  ORDER BY created_at DESC, attendance_source_id DESC
  LIMIT 1
);
SET @chenyang_id = (
  SELECT employee.employee_id
  FROM employee employee
  JOIN employee_current_projection projection
    ON projection.employee_id = employee.employee_id
  JOIN employee_version employee_version
    ON employee_version.employee_version_id = projection.current_version_id
  WHERE employee_version.employee_number = 'SZST0663'
    AND employee_version.display_name = '张晨阳'
  LIMIT 1
);
SET @huangkai_id = (
  SELECT employee.employee_id
  FROM employee employee
  JOIN employee_current_projection projection
    ON projection.employee_id = employee.employee_id
  JOIN employee_version employee_version
    ON employee_version.employee_version_id = projection.current_version_id
  WHERE employee_version.employee_number = 'SZST0667'
    AND employee_version.display_name = '黄凯'
  LIMIT 1
);

SELECT @source_id AS deli_source, @chenyang_id AS 张晨阳, @huangkai_id AS 黄凯;

UPDATE employee_source_binding
   SET effective_to = UTC_TIMESTAMP(6),
       deli_row_version = deli_row_version + 1
 WHERE employee_id IN (@chenyang_id, @huangkai_id)
   AND effective_to IS NULL
   AND @source_id IS NOT NULL
   AND @chenyang_id IS NOT NULL
   AND @huangkai_id IS NOT NULL;

INSERT INTO employee_source_binding (
    binding_id, employee_id, seeyon_person_id, seeyon_oa_code,
    deli_user_id, deli_ext_id, deli_employee_num, deli_attendance_source_id,
    binding_status, effective_from, effective_to, source, confirmation_ref,
    deli_row_version, deli_confirmed_by, deli_confirmed_at, deli_change_reason
) VALUES
(
    UUID(), @chenyang_id, NULL, NULL,
    '1278767800065257473', NULL, 'SZST0663', @source_id,
    'CONFIRMED', '2026-01-01 00:00:00.000000', NULL, 'OPERATOR',
    'DELI-ACCOUNT-ZHANGCHENYANG-20260825',
    0, 'SYSTEM', UTC_TIMESTAMP(6),
    '张晨阳得力账号1278767800065257473绑定SZST0663'
),
(
    UUID(), @huangkai_id, NULL, NULL,
    '1278766765745680384', NULL, NULL, @source_id,
    'CONFIRMED', '2026-01-01 00:00:00.000000', NULL, 'OPERATOR',
    'DELI-ACCOUNT-HUANGKAI-20260825',
    0, 'SYSTEM', UTC_TIMESTAMP(6),
    '黄凯得力账号1278766765745680384绑定SZST0667；不得写入撞车工号SZST0663'
);

INSERT INTO deli_employee_binding_revision (
    deli_employee_binding_revision_id, binding_id, attendance_source_id,
    employee_id, deli_ext_id, deli_user_id, revision_action,
    binding_row_version, confirmation_ref, changed_by, changed_at, change_reason
)
SELECT UUID(), binding.binding_id, binding.deli_attendance_source_id,
       binding.employee_id, binding.deli_ext_id, binding.deli_user_id,
       'CONFIRMED', binding.deli_row_version, binding.confirmation_ref,
       'SYSTEM', UTC_TIMESTAMP(6), binding.deli_change_reason
FROM employee_source_binding binding
WHERE binding.confirmation_ref IN (
        'DELI-ACCOUNT-ZHANGCHENYANG-20260825',
        'DELI-ACCOUNT-HUANGKAI-20260825'
      )
  AND binding.effective_to IS NULL;

COMMIT;
"

echo "绑定完成。请重算 2026-08："
echo "  nohup env COMPANY_ID=41000000-0000-0000-0000-000000000003 \\"
echo "    HR_USER=szsc_admin_faa41d5bd802 HR_PASSWORD='Shenzhou@2026Dev' \\"
echo "    bash /root/recalculate-open-month.sh >> /root/recalculate-js.log 2>&1 &"
echo "  tail -f /root/recalculate-js.log"

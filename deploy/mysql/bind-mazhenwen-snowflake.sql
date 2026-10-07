-- Bind 马振雯 E+ 帐号 / CHECKIN user_id snowflake.
-- Directory employee/query.id=929 is a short E+ id and must not be bound.
-- Screenshot 帐号 1293987480203825152 is the punch user_id.
--
-- Preview:
--   mysql --default-character-set=utf8mb4 -uroot shenzhou_hr \
--     < /root/bind-mazhenwen-snowflake.sql
-- Apply:
--   mysql --default-character-set=utf8mb4 -uroot shenzhou_hr \
--     -e "SET @apply := 1; SOURCE /root/bind-mazhenwen-snowflake.sql;"
SET NAMES utf8mb4;
SET @apply := IFNULL(@apply, 0);
SET @effective_from := '2026-01-01 00:00:00.000000';
SET @source_id := (
    SELECT source.attendance_source_id
    FROM attendance_source source
    WHERE source.source_type = 'DELI_CLOUD'
      AND source.status = 'ACTIVE'
    ORDER BY source.created_at, source.attendance_source_id
    LIMIT 1
);
SET @actor_id := (
    SELECT principal.principal_id
    FROM auth_principal principal
    WHERE principal.principal_id = 'SYSTEM'
    LIMIT 1
);

DROP TEMPORARY TABLE IF EXISTS tmp_mazhenwen_bind;
CREATE TEMPORARY TABLE tmp_mazhenwen_bind (
    display_name VARCHAR(100) NOT NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (deli_user_id)
) ENGINE=InnoDB;
INSERT INTO tmp_mazhenwen_bind VALUES
('马振雯', '1293987480203825152', 'SZSTSX109');

SELECT 'preview_roster' AS section,
       version.employee_number,
       version.display_name,
       employee.employee_id,
       CONVERT(binding.deli_user_id USING utf8mb4) AS current_deli_user_id
FROM tmp_mazhenwen_bind map
JOIN employee_version version
  ON version.employee_number = map.target_employee_number
 AND version.display_name = map.display_name
JOIN employee_current_projection projection
  ON projection.current_version_id = version.employee_version_id
 AND projection.employee_id = version.employee_id
JOIN employee employee
  ON employee.employee_id = version.employee_id
LEFT JOIN employee_source_binding binding
  ON binding.employee_id = employee.employee_id
 AND binding.effective_to IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_mazhenwen_ready;
CREATE TEMPORARY TABLE tmp_mazhenwen_ready (
    display_name VARCHAR(100) NOT NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (deli_user_id)
) ENGINE=InnoDB;

INSERT INTO tmp_mazhenwen_ready
SELECT map.display_name,
       map.deli_user_id,
       map.target_employee_number,
       MIN(version.employee_id)
FROM tmp_mazhenwen_bind map
JOIN employee_version version
  ON version.employee_number = map.target_employee_number
 AND version.display_name = map.display_name
JOIN employee_current_projection projection
  ON projection.current_version_id = version.employee_version_id
 AND projection.employee_id = version.employee_id
WHERE @source_id IS NOT NULL
  AND @actor_id IS NOT NULL
  AND CHAR_LENGTH(map.deli_user_id) >= 16
  AND map.deli_user_id REGEXP '^[0-9]+$'
  AND NOT EXISTS (
        SELECT 1
        FROM employee_source_binding other
        WHERE other.deli_attendance_source_id = @source_id
          AND other.effective_to IS NULL
          AND other.deli_user_id = map.deli_user_id
          AND other.employee_id <> version.employee_id
  )
GROUP BY map.display_name, map.deli_user_id, map.target_employee_number
HAVING COUNT(DISTINCT version.employee_id) = 1;

SELECT 'ready' AS section, * FROM tmp_mazhenwen_ready;

UPDATE employee_source_binding binding
JOIN tmp_mazhenwen_ready ready
  ON ready.employee_id = binding.employee_id
SET binding.deli_user_id = ready.deli_user_id,
    binding.deli_ext_id = ready.deli_user_id,
    binding.deli_employee_num = ready.target_employee_number,
    binding.deli_attendance_source_id = @source_id,
    binding.binding_status = 'CONFIRMED',
    binding.confirmation_ref = CONCAT('SCREENSHOT_ACCOUNT:', ready.deli_user_id),
    binding.deli_confirmed_by = @actor_id,
    binding.deli_confirmed_at = UTC_TIMESTAMP(6),
    binding.deli_change_reason = '得力E+人员详情帐号=打卡user_id雪花，按截图确认绑定',
    binding.effective_from = IF(
        binding.effective_from IS NULL OR binding.effective_from > @effective_from,
        @effective_from,
        binding.effective_from),
    binding.deli_row_version = binding.deli_row_version + 1
WHERE @apply = 1
  AND binding.effective_to IS NULL;

INSERT INTO employee_source_binding (
    binding_id, employee_id, deli_user_id, deli_ext_id, deli_employee_num,
    deli_attendance_source_id, binding_status, effective_from, effective_to,
    source, confirmation_ref, deli_row_version, deli_confirmed_by,
    deli_confirmed_at, deli_change_reason
)
SELECT UUID(), ready.employee_id, ready.deli_user_id, ready.deli_user_id,
       ready.target_employee_number, @source_id, 'CONFIRMED', @effective_from,
       NULL, 'DELI', CONCAT('SCREENSHOT_ACCOUNT:', ready.deli_user_id), 1,
       @actor_id, UTC_TIMESTAMP(6),
       '得力E+人员详情帐号=打卡user_id雪花，按截图确认绑定'
FROM tmp_mazhenwen_ready ready
WHERE @apply = 1
  AND NOT EXISTS (
        SELECT 1
        FROM employee_source_binding binding
        WHERE binding.employee_id = ready.employee_id
          AND binding.effective_to IS NULL
      );

INSERT INTO deli_employee_binding_revision (
    deli_employee_binding_revision_id, binding_id, attendance_source_id,
    employee_id, deli_ext_id, deli_user_id, revision_action,
    binding_row_version, confirmation_ref, changed_by, changed_at, change_reason
)
SELECT UUID(), binding.binding_id, binding.deli_attendance_source_id,
       binding.employee_id, binding.deli_ext_id, binding.deli_user_id,
       'CONFIRMED', binding.deli_row_version, binding.confirmation_ref,
       @actor_id, UTC_TIMESTAMP(6), binding.deli_change_reason
FROM employee_source_binding binding
JOIN tmp_mazhenwen_ready ready
  ON ready.employee_id = binding.employee_id
WHERE @apply = 1
  AND binding.effective_to IS NULL
  AND binding.confirmation_ref = CONCAT('SCREENSHOT_ACCOUNT:', ready.deli_user_id);

SELECT 'current' AS section,
       version.employee_number,
       version.display_name,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.binding_status USING utf8mb4) AS binding_status,
       CONVERT(binding.confirmation_ref USING utf8mb4) AS confirmation_ref
FROM employee_source_binding binding
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE binding.effective_to IS NULL
  AND version.employee_number = 'SZSTSX109'
  AND version.display_name = '马振雯';

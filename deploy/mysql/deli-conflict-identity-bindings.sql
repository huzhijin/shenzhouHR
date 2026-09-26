-- Optional SQL overlay for July-verified collisions (intern/old 工号 vs HR).
-- Live seeding binds the whole Deli directory against the HR roster; this
-- file is only a fallback when the API directory is unavailable.
-- Source of ids: outputs/attendance-import-202607/attendance-import-202607-conflict-supplement.sql
--
-- Cross-checks employee_number + display_name. Rows that do not match the
-- roster, or whose snowflake is already confirmed on another employee, are
-- listed and not written.
-- Does not change Deli 00:00/12:00 watermarks.
--
-- Preview (default):
--   mysql --default-character-set=utf8mb4 shenzhou_hr \
--     < deploy/mysql/deli-conflict-identity-bindings.sql
-- Apply:
--   mysql --default-character-set=utf8mb4 shenzhou_hr \
--     -e "SET @apply := 1; SOURCE deploy/mysql/deli-conflict-identity-bindings.sql;"

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

DROP TEMPORARY TABLE IF EXISTS tmp_deli_conflict_identity;
CREATE TEMPORARY TABLE tmp_deli_conflict_identity (
    display_name VARCHAR(100) NOT NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    device_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (deli_user_id),
    UNIQUE KEY uq_target (target_employee_number)
) ENGINE=InnoDB;

INSERT INTO tmp_deli_conflict_identity VALUES
('陆玉蕾', '932303205680513024', 'SZST0285', 'SZST0284'),
('彭伟', '939805188834107393', 'SZST0289', 'SZST0335'),
('李扬', '947095089162633216', 'SZST0293', 'SZST0291'),
('王善源', '1248197527100440576', 'SZSTSX77', 'SZST0694'),
('方鹏', '1142820074358341634', 'SZST0497', 'SZST0491'),
('张晨阳', '1278767800065257473', 'SZST0668', 'SZST0663'),
('仇容轩', '1290356442827120641', 'SZST0713', 'SZST0714'),
('居军', '646292787763654657', 'SZTD0019', 'SZST0017');

SELECT 'preview_source' AS section,
       @source_id AS source_id,
       @actor_id AS actor_id,
       @apply AS apply_flag;

SELECT 'preview_name_mismatch_hold' AS section,
       map.target_employee_number,
       map.display_name,
       map.deli_user_id
FROM tmp_deli_conflict_identity map
WHERE NOT EXISTS (
    SELECT 1
    FROM employee_current_projection projection
    JOIN employee_version version
      ON version.employee_version_id = projection.current_version_id
    WHERE version.employee_number = map.target_employee_number
      AND version.display_name = map.display_name
);

SELECT 'preview_snowflake_already_bound_elsewhere' AS section,
       map.target_employee_number,
       map.display_name,
       map.deli_user_id,
       CONVERT(binding.employee_id USING utf8mb4) AS bound_employee_id,
       other_version.employee_number AS bound_employee_number
FROM tmp_deli_conflict_identity map
JOIN employee_source_binding binding
  ON binding.deli_attendance_source_id = @source_id
 AND binding.effective_to IS NULL
 AND (
        binding.deli_user_id = map.deli_user_id
        OR binding.deli_ext_id = map.deli_user_id
 )
JOIN employee_current_projection other_projection
  ON other_projection.employee_id = binding.employee_id
JOIN employee_version other_version
  ON other_version.employee_version_id = other_projection.current_version_id
WHERE other_version.employee_number <> map.target_employee_number;

DROP TEMPORARY TABLE IF EXISTS tmp_deli_conflict_ready;
CREATE TEMPORARY TABLE tmp_deli_conflict_ready (
    display_name VARCHAR(100) NOT NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    device_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (target_employee_number)
) ENGINE=InnoDB;

INSERT INTO tmp_deli_conflict_ready
SELECT map.display_name,
       map.deli_user_id,
       map.device_employee_number,
       map.target_employee_number,
       MIN(version.employee_id)
FROM tmp_deli_conflict_identity map
JOIN employee_version version
  ON version.employee_number = map.target_employee_number
 AND version.display_name = map.display_name
JOIN employee_current_projection projection
  ON projection.current_version_id = version.employee_version_id
 AND projection.employee_id = version.employee_id
WHERE @source_id IS NOT NULL
  AND @actor_id IS NOT NULL
  AND NOT EXISTS (
        SELECT 1
        FROM employee_source_binding other_binding
        JOIN employee_current_projection other_projection
          ON other_projection.employee_id = other_binding.employee_id
        JOIN employee_version other_version
          ON other_version.employee_version_id = other_projection.current_version_id
        WHERE other_binding.deli_attendance_source_id = @source_id
          AND other_binding.effective_to IS NULL
          AND (
                other_binding.deli_user_id = map.deli_user_id
                OR other_binding.deli_ext_id = map.deli_user_id
          )
          AND other_version.employee_number <> map.target_employee_number
      )
GROUP BY map.display_name,
         map.deli_user_id,
         map.device_employee_number,
         map.target_employee_number;

SELECT 'preview_will_write' AS section, ready.*
FROM tmp_deli_conflict_ready ready
ORDER BY ready.target_employee_number;

UPDATE employee_source_binding binding
JOIN tmp_deli_conflict_ready ready
  ON ready.employee_id = binding.employee_id
SET binding.deli_user_id = ready.deli_user_id,
    binding.deli_ext_id = ready.deli_user_id,
    binding.deli_employee_num = ready.target_employee_number,
    binding.deli_attendance_source_id = @source_id,
    binding.binding_status = 'CONFIRMED',
    binding.confirmation_ref = CONCAT('JULY_CONFLICT_MAP:', ready.deli_user_id),
    binding.deli_confirmed_by = @actor_id,
    binding.deli_confirmed_at = UTC_TIMESTAMP(6),
    binding.deli_change_reason = '得力设备工号错挂，按七月映射确认雪花人员绑定',
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
       NULL, 'DELI', CONCAT('JULY_CONFLICT_MAP:', ready.deli_user_id), 1,
       @actor_id, UTC_TIMESTAMP(6),
       '得力设备工号错挂，按七月映射确认雪花人员绑定'
FROM tmp_deli_conflict_ready ready
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
JOIN tmp_deli_conflict_ready ready
  ON ready.employee_id = binding.employee_id
WHERE @apply = 1
  AND binding.effective_to IS NULL
  AND binding.confirmation_ref = CONCAT('JULY_CONFLICT_MAP:', ready.deli_user_id);

SELECT 'current_bindings' AS section,
       version.employee_number,
       version.display_name,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.deli_ext_id USING utf8mb4) AS deli_ext_id,
       CONVERT(binding.binding_status USING utf8mb4) AS binding_status,
       binding.effective_from,
       CONVERT(binding.confirmation_ref USING utf8mb4) AS confirmation_ref
FROM employee_source_binding binding
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
JOIN tmp_deli_conflict_identity map
  ON map.target_employee_number = version.employee_number
WHERE binding.effective_to IS NULL
ORDER BY version.employee_number;

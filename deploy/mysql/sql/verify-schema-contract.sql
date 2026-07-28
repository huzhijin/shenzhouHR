SET SESSION time_zone = '+00:00';

SELECT
    SCHEMA_NAME,
    DEFAULT_CHARACTER_SET_NAME,
    DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
ORDER BY SCHEMA_NAME;

SELECT
    TABLE_SCHEMA,
    ENGINE,
    COUNT(*) AS table_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND TABLE_TYPE = 'BASE TABLE'
GROUP BY TABLE_SCHEMA, ENGINE
ORDER BY TABLE_SCHEMA, ENGINE;

SELECT
    TABLE_SCHEMA,
    TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_SCHEMA, TABLE_NAME;

SELECT
    TABLE_SCHEMA,
    TABLE_NAME,
    CONSTRAINT_NAME,
    CONSTRAINT_TYPE
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
ORDER BY TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_TYPE, CONSTRAINT_NAME;

SELECT
    TABLE_SCHEMA,
    TABLE_NAME,
    INDEX_NAME,
    NON_UNIQUE,
    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME;

SELECT
    CONSTRAINT_SCHEMA,
    TABLE_NAME,
    CONSTRAINT_NAME,
    COLUMN_NAME,
    REFERENCED_TABLE_NAME,
    REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE CONSTRAINT_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;

SELECT
    TABLE_SCHEMA,
    COUNT(*) AS wave3_table_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND TABLE_NAME IN (
      'location',
      'attendance_group',
      'attendance_group_assignment',
      'shift_template',
      'shift_version',
      'work_calendar',
      'work_calendar_day',
      'attendance_policy_binding'
  )
GROUP BY TABLE_SCHEMA
ORDER BY TABLE_SCHEMA;

SELECT
    TABLE_SCHEMA,
    INDEX_NAME,
    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
  AND INDEX_NAME IN (
      'ix_attendance_assignment_employee_period',
      'ix_attendance_group_resolution',
      'ix_shift_version_resolution',
      'ix_work_calendar_scope_year',
      'ix_attendance_policy_resolution'
  )
GROUP BY TABLE_SCHEMA, INDEX_NAME
ORDER BY TABLE_SCHEMA, INDEX_NAME;

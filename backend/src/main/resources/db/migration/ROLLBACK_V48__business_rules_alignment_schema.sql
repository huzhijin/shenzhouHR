-- ROLLBACK V48: Business Rules Alignment 2026-08 - Schema Changes
--
-- WARNING: This rollback script will DROP tables and columns.
-- Ensure no application code is referencing these structures before executing.
--
-- Execution order (reverse of migration):
--   1. Drop OA enum mapping data
--   2. Drop overtime classification columns
--   3. Drop deli_sync_log table
--   4. Drop oa_enum_mapping table
--   5. Drop punch_correction_request table
--   6. Drop leave_type column

-- ============================================================================
-- 6. Remove OA enum mapping data (reverse of step 6)
-- ============================================================================

DELETE FROM oa_enum_mapping
WHERE oa_table_name = 'formson_0172'
  AND oa_field_name = 'field0096'
  AND business_meaning IN (
    'OVERTIME_PAID',
    'OVERTIME_COMPENSATORY',
    'OVERTIME_VOLUNTARY'
);

-- ============================================================================
-- 5. Remove overtime classification columns (reverse of step 5)
-- ============================================================================

ALTER TABLE attendance_report_daily_fact
    DROP CHECK ck_att_report_daily_overtime_total,
    DROP COLUMN total_overtime_minutes,
    DROP COLUMN voluntary_overtime_minutes,
    DROP COLUMN compensatory_overtime_minutes,
    DROP COLUMN paid_overtime_minutes;

ALTER TABLE oa_attendance_document_context
    DROP CHECK ck_oa_document_overtime_type,
    DROP INDEX ix_oa_document_overtime_type,
    DROP COLUMN overtime_type;

-- ============================================================================
-- 4. Drop deli_sync_log table (reverse of step 4)
-- ============================================================================

DROP TABLE IF EXISTS deli_sync_log;

-- ============================================================================
-- 3. Drop oa_enum_mapping table (reverse of step 3)
-- ============================================================================

DROP TABLE IF EXISTS oa_enum_mapping;

-- ============================================================================
-- 2. Drop punch_correction_request table (reverse of step 2)
-- ============================================================================

DROP TABLE IF EXISTS punch_correction_request;

DELETE role_capability
FROM auth_role_capability role_capability
JOIN auth_capability capability
  ON capability.capability_id = role_capability.capability_id
WHERE capability.capability_id IN (
    '48000000-0000-4000-8000-000000000001',
    '48000000-0000-4000-8000-000000000002',
    '48000000-0000-4000-8000-000000000003'
);

DELETE FROM auth_capability
WHERE capability_id IN (
    '48000000-0000-4000-8000-000000000001',
    '48000000-0000-4000-8000-000000000002',
    '48000000-0000-4000-8000-000000000003'
);

-- ============================================================================
-- 1. Remove leave_type column (reverse of step 1)
-- ============================================================================

ALTER TABLE oa_attendance_document
    DROP CHECK ck_oa_document_leave_type,
    DROP INDEX ix_oa_document_leave_type,
    DROP COLUMN leave_type;

ALTER TABLE attendance_report_daily_fact
    DROP CHECK ck_att_report_daily_leave_type,
    DROP INDEX ix_att_report_daily_leave_type;

ALTER TABLE attendance_report_daily_fact
    DROP COLUMN leave_type;

-- ============================================================================
-- Post-rollback verification queries (for manual testing)
-- ============================================================================

-- Verify leave_type column removed
-- SELECT COUNT(*) FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'attendance_report_daily_fact'
--   AND COLUMN_NAME = 'leave_type';
-- Expected: 0

-- Verify punch_correction_request table dropped
-- SELECT COUNT(*) FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'punch_correction_request';
-- Expected: 0

-- Verify oa_enum_mapping table dropped
-- SELECT COUNT(*) FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'oa_enum_mapping';
-- Expected: 0

-- Verify deli_sync_log table dropped
-- SELECT COUNT(*) FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'deli_sync_log';
-- Expected: 0

-- Verify overtime_type column removed
-- SELECT COUNT(*) FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'oa_attendance_document_context'
--   AND COLUMN_NAME = 'overtime_type';
-- Expected: 0

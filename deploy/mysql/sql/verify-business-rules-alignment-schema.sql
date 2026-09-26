-- Read-only V48 business-rules-alignment schema verifier.
-- Run after Flyway migrate. Every detail row and overall_status must be PASS.

WITH verification AS (
    SELECT 'flyway_v48_success' AS check_name,
           COUNT(*) = 1 AS passed
    FROM flyway_schema_history
    WHERE version = '48' AND success = 1

    UNION ALL

    SELECT 'daily_fact_business_columns', COUNT(*) = 7
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'attendance_report_daily_fact'
      AND column_name IN (
          'leave_type',
          'scheduled_attendance_days',
          'actual_attendance_days',
          'paid_overtime_minutes',
          'compensatory_overtime_minutes',
          'voluntary_overtime_minutes',
          'total_overtime_minutes'
      )

    UNION ALL

    SELECT 'business_rule_tables', COUNT(*) = 3
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN (
          'punch_correction_request',
          'oa_enum_mapping',
          'deli_sync_log'
      )

    UNION ALL

    SELECT 'oa_overtime_context_column', COUNT(*) = 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_attendance_document_context'
      AND column_name = 'overtime_type'

    UNION ALL

    SELECT 'oa_leave_classification_column', COUNT(*) = 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_attendance_document'
      AND column_name = 'leave_type'

    UNION ALL

    SELECT 'punch_correction_monthly_quota_unique',
           COUNT(*) = 3
               AND SUM(seq_in_index = 1
                       AND column_name = 'employee_id') = 1
               AND SUM(seq_in_index = 2
                       AND column_name = 'request_month') = 1
               AND SUM(seq_in_index = 3
                       AND column_name = 'quota_consuming_marker') = 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'punch_correction_request'
      AND index_name = 'uq_punch_correction_month_quota'
      AND non_unique = 0

    UNION ALL

    SELECT 'oa_enum_value_unique_indexes',
           COUNT(*) = 6
               AND SUM(index_name = 'uq_oa_enum_table_field_bigint'
                       AND seq_in_index = 3
                       AND column_name = 'enum_id_bigint') = 1
               AND SUM(index_name = 'uq_oa_enum_table_field_varchar'
                       AND seq_in_index = 3
                       AND column_name = 'enum_id_varchar') = 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_enum_mapping'
      AND index_name IN (
          'uq_oa_enum_table_field_bigint',
          'uq_oa_enum_table_field_varchar'
      )
      AND non_unique = 0

    UNION ALL

    SELECT 'business_rule_check_constraints', COUNT(*) = 13
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND constraint_name IN (
          'ck_att_report_daily_leave_type',
          'ck_oa_document_leave_type',
          'ck_punch_correction_status',
          'ck_punch_correction_side',
          'ck_punch_correction_review',
          'ck_punch_correction_month_alignment',
          'ck_oa_enum_id_exclusive',
          'ck_deli_sync_log_status',
          'ck_deli_sync_log_completion',
          'ck_att_report_daily_overtime_total',
          'ck_oa_document_overtime_type',
          'ck_att_report_daily_scheduled_days',
          'ck_att_report_daily_actual_days'
      )

    UNION ALL

    SELECT 'confirmed_overtime_enum_mappings',
           COUNT(*) = 3
               AND SUM(verification_record_count) = 117177
    FROM oa_enum_mapping
    WHERE oa_table_name = 'formson_0172'
      AND oa_field_name = 'field0096'
      AND is_active = TRUE

    UNION ALL

    SELECT 'system_automation_principal', COUNT(*) = 1
    FROM auth_principal
    WHERE principal_id = 'SYSTEM'
      AND employee_id IS NULL
      AND status = 'ACTIVE'

    UNION ALL

    SELECT 'punch_correction_capability_ownership', COUNT(*) = 3
    FROM auth_capability
    WHERE (capability_id, capability_code) IN (
        ('48000000-0000-4000-8000-000000000001',
         'ATTENDANCE_PUNCH_CORRECTION:READ'),
        ('48000000-0000-4000-8000-000000000002',
         'ATTENDANCE_PUNCH_CORRECTION:CREATE'),
        ('48000000-0000-4000-8000-000000000003',
         'ATTENDANCE_PUNCH_CORRECTION:APPROVE')
    )
)
SELECT check_name,
       CASE WHEN passed THEN 'PASS' ELSE 'FAIL' END AS check_status
FROM verification
ORDER BY check_name;

WITH verification AS (
    SELECT COUNT(*) = 1 AS passed
    FROM flyway_schema_history
    WHERE version = '48' AND success = 1
    UNION ALL
    SELECT COUNT(*) = 7
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'attendance_report_daily_fact'
      AND column_name IN (
          'leave_type', 'scheduled_attendance_days',
          'actual_attendance_days', 'paid_overtime_minutes',
          'compensatory_overtime_minutes', 'voluntary_overtime_minutes',
          'total_overtime_minutes'
      )
    UNION ALL
    SELECT COUNT(*) = 3
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN (
          'punch_correction_request', 'oa_enum_mapping', 'deli_sync_log'
      )
    UNION ALL
    SELECT COUNT(*) = 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_attendance_document_context'
      AND column_name = 'overtime_type'
    UNION ALL
    SELECT COUNT(*) = 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_attendance_document'
      AND column_name = 'leave_type'
    UNION ALL
    SELECT COUNT(*) = 3
               AND SUM(seq_in_index = 1
                       AND column_name = 'employee_id') = 1
               AND SUM(seq_in_index = 2
                       AND column_name = 'request_month') = 1
               AND SUM(seq_in_index = 3
                       AND column_name = 'quota_consuming_marker') = 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'punch_correction_request'
      AND index_name = 'uq_punch_correction_month_quota'
      AND non_unique = 0
    UNION ALL
    SELECT COUNT(*) = 6
               AND SUM(index_name = 'uq_oa_enum_table_field_bigint'
                       AND seq_in_index = 3
                       AND column_name = 'enum_id_bigint') = 1
               AND SUM(index_name = 'uq_oa_enum_table_field_varchar'
                       AND seq_in_index = 3
                       AND column_name = 'enum_id_varchar') = 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'oa_enum_mapping'
      AND index_name IN (
          'uq_oa_enum_table_field_bigint',
          'uq_oa_enum_table_field_varchar'
      )
      AND non_unique = 0
    UNION ALL
    SELECT COUNT(*) = 13
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND constraint_name IN (
          'ck_att_report_daily_leave_type',
          'ck_oa_document_leave_type',
          'ck_punch_correction_status',
          'ck_punch_correction_side',
          'ck_punch_correction_review',
          'ck_punch_correction_month_alignment',
          'ck_oa_enum_id_exclusive',
          'ck_deli_sync_log_status',
          'ck_deli_sync_log_completion',
          'ck_att_report_daily_overtime_total',
          'ck_oa_document_overtime_type',
          'ck_att_report_daily_scheduled_days',
          'ck_att_report_daily_actual_days'
      )
    UNION ALL
    SELECT COUNT(*) = 3 AND SUM(verification_record_count) = 117177
    FROM oa_enum_mapping
    WHERE oa_table_name = 'formson_0172'
      AND oa_field_name = 'field0096'
      AND is_active = TRUE
    UNION ALL
    SELECT COUNT(*) = 1
    FROM auth_principal
    WHERE principal_id = 'SYSTEM'
      AND employee_id IS NULL
      AND status = 'ACTIVE'
    UNION ALL
    SELECT COUNT(*) = 3
    FROM auth_capability
    WHERE (capability_id, capability_code) IN (
        ('48000000-0000-4000-8000-000000000001',
         'ATTENDANCE_PUNCH_CORRECTION:READ'),
        ('48000000-0000-4000-8000-000000000002',
         'ATTENDANCE_PUNCH_CORRECTION:CREATE'),
        ('48000000-0000-4000-8000-000000000003',
         'ATTENDANCE_PUNCH_CORRECTION:APPROVE')
    )
)
SELECT CASE WHEN MIN(passed) = 1 THEN 'PASS' ELSE 'FAIL' END
       AS overall_status
FROM verification;

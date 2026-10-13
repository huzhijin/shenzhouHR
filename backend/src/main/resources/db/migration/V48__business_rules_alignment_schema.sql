-- V48: Business Rules Alignment 2026-08 - Schema Changes
--
-- Context: Aligning database schema with confirmed business decisions:
--   Q4: Preserve leave types for sick leave reporting
--   Q7c: Punch correction quota (1 per month per employee)
--   Q10: Deli sync monitoring (display-only)
--   Q13: Overtime classification (paid/compensatory/voluntary)
--
-- Related: business-rules-alignment-2026-08 change

-- ============================================================================
-- 1. Add leave_type to daily fact for separate sick leave reporting (Q4)
-- ============================================================================

ALTER TABLE attendance_report_daily_fact
    ADD COLUMN leave_type VARCHAR(50) NULL
        COMMENT '请假类型：ANNUAL（年假）、SICK（病假）、MARRIAGE（婚假）等，用于分类统计'
        AFTER leave_or_time_off_minutes,
    ADD INDEX ix_att_report_daily_leave_type (leave_type),
    ADD CONSTRAINT ck_att_report_daily_leave_type CHECK (
        leave_type IS NULL OR leave_type IN (
            'ANNUAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'PATERNITY',
            'BEREAVEMENT', 'WORK_INJURY', 'PRENATAL_NURSING',
            'PERSONAL', 'COMPENSATORY'
        )
    );

-- Preserve the source classification beside its immutable OA document so the
-- calculation projection never needs to reinterpret a display label later.
ALTER TABLE oa_attendance_document
    ADD COLUMN leave_type VARCHAR(32) NULL
        COMMENT 'OA请假类型：LeaveType 枚举，未知值不进入有效证据'
        AFTER document_type,
    ADD INDEX ix_oa_document_leave_type (leave_type),
    ADD CONSTRAINT ck_oa_document_leave_type CHECK (
        leave_type IS NULL OR leave_type IN (
            'ANNUAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'PATERNITY',
            'BEREAVEMENT', 'WORK_INJURY', 'PRENATAL_NURSING',
            'PERSONAL', 'COMPENSATORY'
        )
    );

-- ============================================================================
-- 2. Punch correction requests table (Q7c: 1 per month per employee)
-- ============================================================================

CREATE TABLE punch_correction_request (
    punch_correction_request_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    employee_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_month DATE NOT NULL
        COMMENT '请求所属月份（当月1号），用于配额检查',
    business_date DATE NOT NULL
        COMMENT '需要补卡的具体日期',
    punch_side VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '补卡方向：ENTRY（上班）、EXIT（下班）、BOTH（上下班）',
    correction_reason VARCHAR(500) NOT NULL
        COMMENT '补卡原因',
    status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'PENDING（待审批）、APPROVED（已批准）、REJECTED（已拒绝）、CANCELLED（已取消）',
    quota_consuming_marker TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('PENDING', 'APPROVED') THEN 1
                ELSE NULL
            END
        ) STORED
        COMMENT '仅待审批/已批准占用月度配额；NULL允许拒绝后再次申请',
    requested_at DATETIME(6) NOT NULL,
    requested_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reviewed_at DATETIME(6) NULL,
    reviewed_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    review_notes VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (punch_correction_request_id),
    UNIQUE KEY uq_punch_correction_month_quota
        (employee_id, request_month, quota_consuming_marker),
    KEY ix_punch_correction_employee_date
        (employee_id, business_date, status),
    KEY ix_punch_correction_employee_month
        (employee_id, request_month, status),
    KEY ix_punch_correction_status
        (status, requested_at),
    CONSTRAINT fk_punch_correction_employee
        FOREIGN KEY (employee_id)
        REFERENCES employee (employee_id),
    CONSTRAINT fk_punch_correction_requester
        FOREIGN KEY (requested_by)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT fk_punch_correction_reviewer
        FOREIGN KEY (reviewed_by)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_punch_correction_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_punch_correction_side
        CHECK (punch_side IN ('ENTRY', 'EXIT', 'BOTH')),
    CONSTRAINT ck_punch_correction_review
        CHECK (
            (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL)
            OR (status IN ('APPROVED', 'REJECTED')
                AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
            OR (status = 'CANCELLED')
        ),
    CONSTRAINT ck_punch_correction_month_alignment
        CHECK (
            request_month = DATE_FORMAT(business_date, '%Y-%m-01')
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='补卡申请记录，用于每月1次配额限制';

INSERT INTO auth_capability (
    capability_id, capability_code, permission_domain, action_code
)
SELECT capability_id, capability_code, 'ATTENDANCE', action_code
FROM (
    SELECT '48000000-0000-4000-8000-000000000001' AS capability_id,
           'ATTENDANCE_PUNCH_CORRECTION:READ' AS capability_code,
           'READ' AS action_code
    UNION ALL
    SELECT '48000000-0000-4000-8000-000000000002',
           'ATTENDANCE_PUNCH_CORRECTION:CREATE', 'CREATE'
    UNION ALL
    SELECT '48000000-0000-4000-8000-000000000003',
           'ATTENDANCE_PUNCH_CORRECTION:APPROVE', 'APPROVE'
) required_capability
WHERE NOT EXISTS (
    SELECT 1
    FROM auth_capability existing
    WHERE existing.capability_code = required_capability.capability_code
);

INSERT IGNORE INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_id IN (
      '48000000-0000-4000-8000-000000000001',
      '48000000-0000-4000-8000-000000000002'
  )
WHERE role.role_code IN (
    'EMPLOYEE_SELF', 'DEPARTMENT_HEAD', 'HR_ADMIN', 'SYSTEM_ADMIN'
);

INSERT IGNORE INTO auth_role_capability (role_id, capability_id)
SELECT role.role_id, capability.capability_id
FROM auth_role role
JOIN auth_capability capability
  ON capability.capability_id =
        '48000000-0000-4000-8000-000000000003'
WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN');

-- ============================================================================
-- 3. OA enum mappings configuration table (Q13: overtime classification)
-- ============================================================================

CREATE TABLE oa_enum_mapping (
    oa_enum_mapping_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    oa_table_name VARCHAR(100)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'OA表名，如 formson_0172',
    oa_field_name VARCHAR(100)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'OA字段名，如 field0096',
    enum_id_bigint BIGINT NULL
        COMMENT 'OA枚举ID（BIGINT类型）',
    enum_id_varchar VARCHAR(100)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'OA枚举ID（VARCHAR类型）',
    business_meaning VARCHAR(100)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '业务含义：OVERTIME_PAID、OVERTIME_COMPENSATORY、OVERTIME_VOLUNTARY等',
    display_label VARCHAR(200) NOT NULL
        COMMENT '显示名称：加班费、调休、义务加班',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    verified_at DATE NULL
        COMMENT '验证日期：记录字段映射验证时间',
    verification_record_count INT UNSIGNED NULL
        COMMENT '验证记录数：OA中该枚举值的记录数量',
    notes VARCHAR(500) NULL,
    created_by VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (oa_enum_mapping_id),
    UNIQUE KEY uq_oa_enum_table_field_bigint
        (oa_table_name, oa_field_name, enum_id_bigint),
    UNIQUE KEY uq_oa_enum_table_field_varchar
        (oa_table_name, oa_field_name, enum_id_varchar),
    UNIQUE KEY uq_oa_enum_business_meaning
        (oa_table_name, oa_field_name, business_meaning),
    KEY ix_oa_enum_lookup
        (oa_table_name, oa_field_name, is_active),
    CONSTRAINT fk_oa_enum_mapping_creator
        FOREIGN KEY (created_by)
        REFERENCES auth_principal (principal_id),
    CONSTRAINT ck_oa_enum_id_exclusive
        CHECK (
            (enum_id_bigint IS NOT NULL AND enum_id_varchar IS NULL)
            OR (enum_id_bigint IS NULL AND enum_id_varchar IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OA枚举值映射配置表，记录字段验证证据';

-- ============================================================================
-- 4. Deli sync log table (Q10: display-only monitoring)
-- ============================================================================

CREATE TABLE deli_sync_log (
    deli_sync_log_id VARCHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sync_started_at DATETIME(6) NOT NULL
        COMMENT '同步开始时间',
    sync_completed_at DATETIME(6) NULL
        COMMENT '同步完成时间',
    sync_status VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'SUCCESS（成功）、FAILED（失败）、IN_PROGRESS（进行中）',
    record_count INT UNSIGNED NULL
        COMMENT '本次同步获取的记录数',
    new_record_count INT UNSIGNED NULL
        COMMENT '本次同步新增的记录数',
    duplicate_count INT UNSIGNED NULL
        COMMENT '本次同步跳过的重复记录数',
    error_message TEXT NULL
        COMMENT '错误信息（失败时记录）',
    sync_time_range_start DATETIME(6) NULL
        COMMENT '本次同步的时间范围起点',
    sync_time_range_end DATETIME(6) NULL
        COMMENT '本次同步的时间范围终点',
    execution_duration_ms INT UNSIGNED NULL
        COMMENT '执行耗时（毫秒）',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (deli_sync_log_id),
    KEY ix_deli_sync_log_status_time
        (sync_status, sync_started_at DESC),
    KEY ix_deli_sync_log_latest
        (sync_started_at DESC, deli_sync_log_id),
    CONSTRAINT ck_deli_sync_log_status
        CHECK (sync_status IN ('SUCCESS', 'FAILED', 'IN_PROGRESS')),
    CONSTRAINT ck_deli_sync_log_completion
        CHECK (
            (sync_status = 'IN_PROGRESS' AND sync_completed_at IS NULL)
            OR (sync_status IN ('SUCCESS', 'FAILED')
                AND sync_completed_at IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='得力打卡同步日志，用于显示同步状态';

-- ============================================================================
-- 5. Add overtime classification to the real OA evidence context and daily fact
--    tables (Q13)
-- ============================================================================

ALTER TABLE oa_attendance_document_context
    ADD COLUMN overtime_type VARCHAR(20) NULL
        COMMENT '加班类型：PAID（计薪加班）、COMPENSATORY（转调休）、VOLUNTARY（义务加班）'
        AFTER overtime_treatment,
    ADD INDEX ix_oa_document_overtime_type (overtime_type),
    ADD CONSTRAINT ck_oa_document_overtime_type CHECK (
        overtime_type IS NULL
        OR overtime_type IN ('PAID', 'COMPENSATORY', 'VOLUNTARY')
    );

ALTER TABLE attendance_report_daily_fact
    ADD COLUMN paid_overtime_minutes BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '计薪加班分钟数'
        AFTER recognized_overtime_minutes,
    ADD COLUMN compensatory_overtime_minutes BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '转调休加班分钟数'
        AFTER paid_overtime_minutes,
    ADD COLUMN voluntary_overtime_minutes BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '义务加班分钟数'
        AFTER compensatory_overtime_minutes,
    ADD COLUMN total_overtime_minutes BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '三类已分类加班分钟数之和'
        AFTER voluntary_overtime_minutes,
    ADD CONSTRAINT ck_att_report_daily_overtime_total CHECK (
        total_overtime_minutes = paid_overtime_minutes
            + compensatory_overtime_minutes
            + voluntary_overtime_minutes
    );

-- ============================================================================
-- 6. Insert initial OA enum mapping data for overtime classification (Q13)
-- ============================================================================

-- Evidence from: docs/verification/oa-live/2026-08-10/EVIDENCE.md
-- Evidence ID: OA-B5-03C
-- Verified: 2026-08-10
-- Table: formson_0172 (加班申请)
-- Field: field0096 (加班类别)

INSERT INTO oa_enum_mapping (
    oa_enum_mapping_id,
    oa_table_name,
    oa_field_name,
    enum_id_bigint,
    enum_id_varchar,
    business_meaning,
    display_label,
    is_active,
    verified_at,
    verification_record_count,
    notes,
    created_by,
    created_at
) VALUES
-- PAID: 加班费
(
    UUID(),
    'formson_0172',
    'field0096',
    -6539634143789166714,
    NULL,
    'OVERTIME_PAID',
    '加班费',
    TRUE,
    '2026-08-10',
    112022,
    'Evidence: OA-B5-03C verified on 2026-08-10 with 112,022 records',
    'SYSTEM',
    NOW(6)
),
-- COMPENSATORY: 调休
(
    UUID(),
    'formson_0172',
    'field0096',
    5912806790045781226,
    NULL,
    'OVERTIME_COMPENSATORY',
    '调休',
    TRUE,
    '2026-08-10',
    5066,
    'Evidence: OA-B5-03C verified on 2026-08-10 with 5,066 records',
    'SYSTEM',
    NOW(6)
),
-- VOLUNTARY: 义务加班
(
    UUID(),
    'formson_0172',
    'field0096',
    4337518111002608138,
    NULL,
    'OVERTIME_VOLUNTARY',
    '义务加班',
    TRUE,
    '2026-08-10',
    89,
    'Evidence: OA-B5-03C verified on 2026-08-10 with 89 records',
    'SYSTEM',
    NOW(6)
);

-- ============================================================================
-- Post-migration verification queries (for manual testing)
-- ============================================================================

-- Verify leave_type column added
-- SELECT COUNT(*) FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'attendance_report_daily_fact'
--   AND COLUMN_NAME = 'leave_type';

-- Verify punch_correction_request table created
-- SELECT COUNT(*) FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'punch_correction_request';

-- Verify oa_enum_mapping data inserted
-- SELECT business_meaning, display_label, verification_record_count
-- FROM oa_enum_mapping
-- WHERE oa_table_name = 'formson_0172'
--   AND oa_field_name = 'field0096'
-- ORDER BY verification_record_count DESC;

-- Verify deli_sync_log table created
-- SELECT COUNT(*) FROM information_schema.TABLES
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'deli_sync_log';

-- Verify overtime_type column added to the existing OA evidence context
-- SELECT COUNT(*) FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'oa_attendance_document_context'
--   AND COLUMN_NAME = 'overtime_type';

-- Verify classified overtime fields added to daily facts
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'attendance_report_daily_fact'
--   AND COLUMN_NAME IN (
--       'paid_overtime_minutes',
--       'compensatory_overtime_minutes',
--       'voluntary_overtime_minutes',
--       'total_overtime_minutes'
--   );

-- OA 实库 B-5 截图采集脚本（只读、脱敏、逐条执行）
--
-- 使用方法：
--   1. 先切换到 szoa 数据库；
--   2. 每次执行一个以分号结束的完整 statement；
--      OA-B5-05C 必须连同 UNION ALL 的两支一起执行；
--   3. 截图必须包含 evidence_id、全部列名、全部结果行和执行成功/行数；
--   4. 不要截出连接密码、配置文件、姓名或工号明文；
--   5. 按 docs/verification/oa-live/README.md 的顺序逐张回传。
--
-- 本脚本不包含 INSERT、UPDATE、DELETE、DDL、临时表或会话变量修改。

-- OA-B5-00：运行环境（1 行）
SELECT
    'OA-B5-00'       AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    VERSION()        AS mysql_version,
    DATABASE()       AS current_db,
    @@time_zone      AS session_time_zone,
    @@system_time_zone AS system_time_zone;

-- OA-B5-01A：加班主/明细表的本轮相关列（预期不超过 10 行）
SELECT
    'OA-B5-01A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    table_name,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_key,
    column_default
FROM information_schema.columns
WHERE table_schema = 'szoa'
  AND (
      (table_name = 'formmain_0171'
       AND column_name IN ('id', 'field0102'))
      OR
      (table_name = 'formson_0172'
       AND column_name IN (
           'id', 'formmain_id', 'field0093', 'field0094',
           'field0096', 'field0099', 'field0100'
       ))
  )
ORDER BY table_name, ordinal_position;

-- OA-B5-01C：声明式外键摘要；0 个时仍固定返回 1 行
SELECT
    'OA-B5-01C' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    COUNT(*) AS declared_fk_count,
    COALESCE(
        GROUP_CONCAT(
            CONCAT(
                table_name, '.', column_name, ' -> ',
                referenced_table_name, '.', referenced_column_name
            )
            ORDER BY table_name, column_name
            SEPARATOR '; '
        ),
        '<NONE>'
    ) AS declared_fk_mappings
FROM information_schema.key_column_usage
WHERE table_schema = 'szoa'
  AND table_name IN ('formmain_0171', 'formson_0172')
  AND referenced_table_name IS NOT NULL;

-- OA-B5-01D：候选 formmain_id 的覆盖率（1 行）
SELECT
    'OA-B5-01D' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    (SELECT COUNT(*) FROM formmain_0171) AS main_rows,
    (SELECT COUNT(*) FROM formson_0172) AS detail_rows,
    (SELECT COUNT(DISTINCT formmain_id)
       FROM formson_0172
      WHERE formmain_id IS NOT NULL) AS distinct_nonnull_parent_refs,
    (SELECT COUNT(*)
       FROM formson_0172
      WHERE formmain_id IS NULL) AS null_parent_detail_rows,
    (SELECT COUNT(*)
       FROM formson_0172 s
       LEFT JOIN formmain_0171 m ON m.id = s.formmain_id
      WHERE s.formmain_id IS NOT NULL
        AND m.id IS NULL) AS nonnull_orphan_detail_rows;

-- OA-B5-01E：候选 formmain_id 的 1:N 基数摘要（1 行）
SELECT
    'OA-B5-01E' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    COALESCE(SUM(CASE WHEN detail_count = 0 THEN 1 ELSE 0 END), 0)
        AS main_rows_without_details,
    COALESCE(SUM(CASE WHEN detail_count = 1 THEN 1 ELSE 0 END), 0)
        AS documents_with_one_detail,
    COALESCE(SUM(CASE WHEN detail_count > 1 THEN 1 ELSE 0 END), 0)
        AS documents_with_multiple_details,
    COALESCE(MAX(detail_count), 0) AS max_details_per_document
FROM (
    SELECT m.id, COUNT(s.formmain_id) AS detail_count
    FROM formmain_0171 m
    LEFT JOIN formson_0172 s ON s.formmain_id = m.id
    GROUP BY m.id
) detail_counts;

-- OA-B5-02A：审批关联列/状态列 metadata（预期 2 行）
SELECT
    'OA-B5-02A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_key,
    column_default
FROM information_schema.columns
WHERE table_schema = 'szoa'
  AND table_name = 'col_summary'
  AND column_name IN ('form_recordid', 'state')
ORDER BY ordinal_position;

-- OA-B5-02B：col_summary.state 全量分布；NULL 显式显示为 <NULL>
SELECT
    'OA-B5-02B' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    CASE
        WHEN state IS NULL THEN '<NULL>'
        ELSE CAST(state AS CHAR)
    END AS raw_state,
    CASE WHEN state IS NULL THEN 1 ELSE 0 END AS state_is_null,
    COUNT(*) AS row_count
FROM col_summary
GROUP BY state
ORDER BY row_count DESC, raw_state;

-- OA-B5-02C：加班主单关联覆盖率，并暴露一单多 summary 行（1 行）
SELECT
    'OA-B5-02C' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    COUNT(*) AS overtime_main_rows,
    COALESCE(SUM(CASE WHEN summary_row_count > 0 THEN 1 ELSE 0 END), 0)
        AS matched_documents,
    COALESCE(SUM(CASE WHEN summary_row_count = 0 THEN 1 ELSE 0 END), 0)
        AS unmatched_documents,
    COALESCE(SUM(CASE WHEN summary_row_count > 1 THEN 1 ELSE 0 END), 0)
        AS documents_with_multiple_summary_rows,
    COALESCE(MAX(summary_row_count), 0) AS max_summary_rows_per_document
FROM (
    SELECT
        m.id,
        SUM(CASE WHEN c.form_recordid IS NULL THEN 0 ELSE 1 END)
            AS summary_row_count
    FROM formmain_0171 m
    LEFT JOIN col_summary c ON c.form_recordid = m.id
    GROUP BY m.id
) coverage;

-- OA-B5-02D：加班单按 state 分布；同时显示 join 行数与去重单据数
SELECT
    'OA-B5-02D' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    CASE
        WHEN c.state IS NULL THEN '<NULL>'
        ELSE CAST(c.state AS CHAR)
    END AS raw_state,
    CASE WHEN c.state IS NULL THEN 1 ELSE 0 END AS state_is_null,
    COUNT(*) AS joined_rows,
    COUNT(DISTINCT m.id) AS distinct_overtime_documents
FROM formmain_0171 m
JOIN col_summary c ON c.form_recordid = m.id
GROUP BY c.state
ORDER BY distinct_overtime_documents DESC, raw_state;

-- OA-B5-03A：枚举关联关键列 metadata（预期 2 行）
SELECT
    'OA-B5-03A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_key,
    character_set_name,
    collation_name
FROM information_schema.columns
WHERE table_schema = 'szoa'
  AND table_name = 'ctp_enum_item'
  AND column_name IN ('id', 'showvalue')
ORDER BY ordinal_position;

-- OA-B5-03C：加班类别封闭映射；NULL/未映射值显式显示
SELECT
    'OA-B5-03C' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    CASE
        WHEN s.field0096 IS NULL THEN '<NULL>'
        ELSE CAST(s.field0096 AS CHAR)
    END AS raw_enum_id,
    CASE
        WHEN s.field0096 IS NULL THEN '<RAW_NULL>'
        WHEN e.id IS NULL THEN '<UNMAPPED>'
        WHEN e.showvalue IS NULL THEN '<NULL_LABEL>'
        WHEN e.showvalue = ''
          OR e.showvalue REGEXP '^[[:space:]]+$'
            THEN '<EMPTY_OR_BLANK_LABEL>'
        ELSE e.showvalue
    END AS label,
    COUNT(*) AS row_count
FROM formson_0172 s
LEFT JOIN ctp_enum_item e ON e.id = s.field0096
GROUP BY s.field0096, e.id, e.showvalue
ORDER BY row_count DESC, raw_enum_id, label;

-- OA-B5-03D：加班类别 NULL 与未映射统计（1 行）
SELECT
    'OA-B5-03D' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    COALESCE(SUM(CASE WHEN s.field0096 IS NULL THEN 1 ELSE 0 END), 0)
        AS raw_null_rows,
    COALESCE(SUM(CASE
            WHEN s.field0096 IS NOT NULL AND e.id IS NULL
            THEN 1 ELSE 0
        END), 0) AS missing_enum_item_rows,
    COALESCE(SUM(CASE
            WHEN e.id IS NOT NULL AND e.showvalue IS NULL
            THEN 1 ELSE 0
        END), 0) AS null_label_rows,
    COALESCE(SUM(CASE
            WHEN e.showvalue IS NOT NULL
             AND (e.showvalue = ''
                  OR e.showvalue REGEXP '^[[:space:]]+$')
            THEN 1 ELSE 0
        END), 0) AS empty_or_blank_label_rows
FROM formson_0172 s
LEFT JOIN ctp_enum_item e ON e.id = s.field0096;

-- OA-B5-04A：请假类别封闭映射；NULL/未映射值显式显示
SELECT
    'OA-B5-04A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    CASE
        WHEN m.field0089 IS NULL THEN '<NULL>'
        ELSE CAST(m.field0089 AS CHAR)
    END AS raw_enum_id,
    CASE
        WHEN m.field0089 IS NULL THEN '<RAW_NULL>'
        WHEN e.id IS NULL THEN '<UNMAPPED>'
        WHEN e.showvalue IS NULL THEN '<NULL_LABEL>'
        WHEN e.showvalue = ''
          OR e.showvalue REGEXP '^[[:space:]]+$'
            THEN '<EMPTY_OR_BLANK_LABEL>'
        ELSE e.showvalue
    END AS label,
    COUNT(*) AS row_count
FROM formmain_0170 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
GROUP BY m.field0089, e.id, e.showvalue
ORDER BY row_count DESC, raw_enum_id, label;

-- OA-B5-04B：请假类别 NULL 与未映射统计（1 行）
SELECT
    'OA-B5-04B' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    COALESCE(SUM(CASE WHEN m.field0089 IS NULL THEN 1 ELSE 0 END), 0)
        AS raw_null_rows,
    COALESCE(SUM(CASE
            WHEN m.field0089 IS NOT NULL AND e.id IS NULL
            THEN 1 ELSE 0
        END), 0) AS missing_enum_item_rows,
    COALESCE(SUM(CASE
            WHEN e.id IS NOT NULL AND e.showvalue IS NULL
            THEN 1 ELSE 0
        END), 0) AS null_label_rows,
    COALESCE(SUM(CASE
            WHEN e.showvalue IS NOT NULL
             AND (e.showvalue = ''
                  OR e.showvalue REGEXP '^[[:space:]]+$')
            THEN 1 ELSE 0
        END), 0) AS empty_or_blank_label_rows
FROM formmain_0170 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0089;

-- OA-B5-05A：org_member.id/code 及候选生命周期列 metadata
SELECT
    'OA-B5-05A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_key,
    character_set_name,
    collation_name
FROM information_schema.columns
WHERE table_schema = 'szoa'
  AND table_name = 'org_member'
  AND (
      column_name IN ('id', 'code')
      OR LOWER(column_name) REGEXP
          'state|status|enable|disable|delete|valid|active'
  )
ORDER BY ordinal_position;

-- OA-B5-05B：code 形状摘要（不返回任何 code 明文）
SELECT
    'OA-B5-05B' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    metric_names.metric,
    CASE metric_names.sort_order
        WHEN 1 THEN CAST(summary.total_members AS CHAR)
        WHEN 2 THEN CAST(summary.distinct_codes_db_collation AS CHAR)
        WHEN 3 THEN CAST(summary.distinct_codes_byte_exact AS CHAR)
        WHEN 4 THEN CAST(summary.null_codes AS CHAR)
        WHEN 5 THEN CAST(summary.zero_length_codes AS CHAR)
        WHEN 6 THEN CAST(summary.whitespace_only_codes AS CHAR)
        WHEN 7 THEN COALESCE(CAST(summary.min_usable_len AS CHAR), '<NULL>')
        WHEN 8 THEN COALESCE(CAST(summary.max_usable_len AS CHAR), '<NULL>')
        WHEN 9 THEN CAST(summary.leading_zero_codes AS CHAR)
        WHEN 10 THEN CAST(summary.non_numeric_codes AS CHAR)
    END AS metric_value
FROM (
    SELECT
        COUNT(*) AS total_members,
        COUNT(DISTINCT code) AS distinct_codes_db_collation,
        COUNT(DISTINCT BINARY code) AS distinct_codes_byte_exact,
        COALESCE(SUM(CASE WHEN code IS NULL THEN 1 ELSE 0 END), 0)
            AS null_codes,
        COALESCE(SUM(CASE
                WHEN code IS NOT NULL AND OCTET_LENGTH(code) = 0
                THEN 1 ELSE 0
            END), 0)
            AS zero_length_codes,
        COALESCE(SUM(CASE
                WHEN code IS NOT NULL
                 AND OCTET_LENGTH(code) > 0
                 AND code REGEXP '^[[:space:]]+$'
                THEN 1 ELSE 0
            END), 0) AS whitespace_only_codes,
        MIN(CASE
                WHEN code IS NOT NULL
                 AND OCTET_LENGTH(code) > 0
                 AND NOT (code REGEXP '^[[:space:]]+$')
                THEN CHAR_LENGTH(code)
            END) AS min_usable_len,
        MAX(CASE
                WHEN code IS NOT NULL
                 AND OCTET_LENGTH(code) > 0
                 AND NOT (code REGEXP '^[[:space:]]+$')
                THEN CHAR_LENGTH(code)
            END) AS max_usable_len,
        COALESCE(SUM(CASE WHEN code REGEXP '^0' THEN 1 ELSE 0 END), 0)
            AS leading_zero_codes,
        COALESCE(SUM(CASE WHEN code REGEXP '[^0-9]' THEN 1 ELSE 0 END), 0)
            AS non_numeric_codes
    FROM org_member
) summary
CROSS JOIN (
    SELECT 1 AS sort_order, 'total_members' AS metric
    UNION ALL SELECT 2, 'distinct_codes_db_collation'
    UNION ALL SELECT 3, 'distinct_codes_byte_exact'
    UNION ALL SELECT 4, 'null_codes'
    UNION ALL SELECT 5, 'zero_length_codes'
    UNION ALL SELECT 6, 'whitespace_only_codes'
    UNION ALL SELECT 7, 'min_usable_len'
    UNION ALL SELECT 8, 'max_usable_len'
    UNION ALL SELECT 9, 'leading_zero_codes'
    UNION ALL SELECT 10, 'non_numeric_codes'
) metric_names
ORDER BY metric_names.sort_order;

-- OA-B5-05C：两种比较口径下的重复摘要（固定返回 2 行，不返回 code）
SELECT
    'OA-B5-05C' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    'DATABASE_COLLATION' AS comparison_mode,
    COALESCE(SUM(CASE WHEN dup_count > 1 THEN 1 ELSE 0 END), 0)
        AS duplicated_code_groups,
    COALESCE(SUM(CASE WHEN dup_count > 1 THEN dup_count ELSE 0 END), 0)
        AS members_in_duplicate_groups,
    COALESCE(MAX(dup_count), 0) AS max_occurrences_of_one_code
FROM (
    SELECT code, COUNT(*) AS dup_count
    FROM org_member
    WHERE code IS NOT NULL
      AND OCTET_LENGTH(code) > 0
      AND NOT (code REGEXP '^[[:space:]]+$')
    GROUP BY code
) db_collation_counts
UNION ALL
SELECT
    'OA-B5-05C' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    'BYTE_EXACT' AS comparison_mode,
    COALESCE(SUM(CASE WHEN dup_count > 1 THEN 1 ELSE 0 END), 0)
        AS duplicated_code_groups,
    COALESCE(SUM(CASE WHEN dup_count > 1 THEN dup_count ELSE 0 END), 0)
        AS members_in_duplicate_groups,
    COALESCE(MAX(dup_count), 0) AS max_occurrences_of_one_code
FROM (
    SELECT BINARY code AS code_key, COUNT(*) AS dup_count
    FROM org_member
    WHERE code IS NOT NULL
      AND OCTET_LENGTH(code) > 0
      AND NOT (code REGEXP '^[[:space:]]+$')
    GROUP BY BINARY code
) byte_exact_counts
ORDER BY comparison_mode;

-- OA-B5-06A：加班开始/结束字段 metadata（预期 2 行）
SELECT
    'OA-B5-06A' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'szoa'
  AND table_name = 'formson_0172'
  AND column_name IN ('field0099', 'field0100')
ORDER BY ordinal_position;

-- OA-B5-06B：加班时间范围与异常摘要（纵向 7 行）
SELECT
    'OA-B5-06B' AS evidence_id,
    LEFT(SHA2(CONCAT_WS('|', @@hostname, @@port, DATABASE()), 256), 16)
        AS instance_fingerprint,
    metric_names.metric,
    CASE metric_names.sort_order
        WHEN 1 THEN COALESCE(CAST(summary.earliest_start AS CHAR), '<NULL>')
        WHEN 2 THEN COALESCE(CAST(summary.latest_start AS CHAR), '<NULL>')
        WHEN 3 THEN COALESCE(CAST(summary.earliest_end AS CHAR), '<NULL>')
        WHEN 4 THEN COALESCE(CAST(summary.latest_end AS CHAR), '<NULL>')
        WHEN 5 THEN CAST(summary.rows_with_missing_endpoint AS CHAR)
        WHEN 6 THEN CAST(summary.non_positive_intervals AS CHAR)
        WHEN 7 THEN CAST(summary.cross_midnight_rows AS CHAR)
    END AS metric_value
FROM (
    SELECT
        MIN(field0100) AS earliest_start,
        MAX(field0100) AS latest_start,
        MIN(field0099) AS earliest_end,
        MAX(field0099) AS latest_end,
        COALESCE(SUM(CASE
                WHEN field0099 IS NULL OR field0100 IS NULL
                THEN 1 ELSE 0
            END), 0) AS rows_with_missing_endpoint,
        COALESCE(SUM(CASE
                WHEN field0099 IS NOT NULL
                 AND field0100 IS NOT NULL
                 AND field0099 <= field0100
                THEN 1 ELSE 0
            END), 0) AS non_positive_intervals,
        COALESCE(SUM(CASE
                WHEN field0099 IS NOT NULL
                 AND field0100 IS NOT NULL
                 AND DATE(field0099) <> DATE(field0100)
                THEN 1 ELSE 0
            END), 0) AS cross_midnight_rows
    FROM formson_0172
) summary
CROSS JOIN (
    SELECT 1 AS sort_order, 'earliest_start' AS metric
    UNION ALL SELECT 2, 'latest_start'
    UNION ALL SELECT 3, 'earliest_end'
    UNION ALL SELECT 4, 'latest_end'
    UNION ALL SELECT 5, 'rows_with_missing_endpoint'
    UNION ALL SELECT 6, 'non_positive_intervals'
    UNION ALL SELECT 7, 'cross_midnight_rows'
) metric_names
ORDER BY metric_names.sort_order;

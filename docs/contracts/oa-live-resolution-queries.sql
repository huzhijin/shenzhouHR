-- OA 实库只读确认脚本 — 解决 B-5 加班认定的三类阻塞
--
-- 用途：在能访问内网 192.168.2.169:3308 的机器上运行，把输出回传。
-- 目标：把 oa-attendance-form-mapping-signoff-matrix.md 里的
--       OA-FK-01、OA-ENUM-01/02、OA-STATUS-01/02、OA-MEMBER-02
--       从 NOT_VERIFIED 转为可签字。
--
-- 安全边界（逐条已核对）：
--   * 全文只有 SELECT / SHOW / DESCRIBE，没有 INSERT/UPDATE/DELETE/DDL；
--   * 不建临时表、不建视图、不改会话变量；
--   * 所有人员相关查询只返回计数或 md5 摘要，不返回姓名、工号明文；
--   * 可以在生产 OA 上直接运行。
--
-- 运行方式（凭据放仓库外 0600 文件，不要写进命令行）：
--
--   cat > ~/.oa-readonly.cnf <<'EOF'
--   [client]
--   host=192.168.2.169
--   port=3308
--   user=kaoqin2026
--   password=<你的密码>
--   EOF
--   chmod 600 ~/.oa-readonly.cnf
--
--   mysql --defaults-extra-file=~/.oa-readonly.cnf \
--     --database=szoa --table < docs/contracts/oa-live-resolution-queries.sql \
--     > ~/oa-resolution-output.txt 2>&1
--
-- 回传 ~/oa-resolution-output.txt 前请先自己扫一眼，确认没有人员明细。

SELECT '=== 0. 环境 ===' AS section;
SELECT VERSION() AS mysql_version, DATABASE() AS current_db, @@time_zone AS tz,
       @@system_time_zone AS system_tz;

-- ---------------------------------------------------------------------------
-- 1. OA-FK-01：加班单主表与明细表的真实关联列
--    候选列名 formmain_id 按签字矩阵明确不得直接使用，必须先证明。
-- ---------------------------------------------------------------------------

SELECT '=== 1a. formmain_0171 列结构 ===' AS section;
SELECT column_name, data_type, is_nullable, column_key, column_default
FROM information_schema.columns
WHERE table_schema = 'szoa' AND table_name = 'formmain_0171'
ORDER BY ordinal_position;

SELECT '=== 1b. formson_0172 列结构 ===' AS section;
SELECT column_name, data_type, is_nullable, column_key, column_default
FROM information_schema.columns
WHERE table_schema = 'szoa' AND table_name = 'formson_0172'
ORDER BY ordinal_position;

SELECT '=== 1c. 声明式外键（可能没有，OA 常靠约定）===' AS section;
SELECT table_name, column_name, referenced_table_name, referenced_column_name
FROM information_schema.key_column_usage
WHERE table_schema = 'szoa'
  AND table_name IN ('formmain_0171','formson_0172')
  AND referenced_table_name IS NOT NULL;

SELECT '=== 1d. 基数证明：候选列能否形成 1:N ===' AS section;
-- 如果 formson_0172 里有 formmain_id，这段给出匹配率。
-- 匹配率必须接近 100% 才能作为关联列；孤儿行数必须能解释。
SELECT
    (SELECT COUNT(*) FROM formson_0172)                       AS detail_rows,
    (SELECT COUNT(*) FROM formmain_0171)                      AS main_rows,
    (SELECT COUNT(DISTINCT s.formmain_id) FROM formson_0172 s) AS distinct_parent_refs,
    (SELECT COUNT(*) FROM formson_0172 s
       LEFT JOIN formmain_0171 m ON m.id = s.formmain_id
      WHERE m.id IS NULL)                                     AS orphan_details;

SELECT '=== 1e. 每单明细条数分布（判断 1:N 是否成立）===' AS section;
SELECT detail_count, COUNT(*) AS num_documents
FROM (
    SELECT s.formmain_id, COUNT(*) AS detail_count
    FROM formson_0172 s
    GROUP BY s.formmain_id
) t
GROUP BY detail_count
ORDER BY detail_count
LIMIT 20;

-- ---------------------------------------------------------------------------
-- 2. OA-STATUS-01/02：审批状态
--    你给的事实：formmain_xxxx.id = col_summary.form_recordid，
--    col_summary.state：3=结束（有效）、0=发起中、2=撤销、NULL=保存待发。
--    这一段是去证实它，而不是假定它。
-- ---------------------------------------------------------------------------

SELECT '=== 2a. col_summary 列结构 ===' AS section;
SELECT column_name, data_type, is_nullable, column_key
FROM information_schema.columns
WHERE table_schema = 'szoa' AND table_name = 'col_summary'
ORDER BY ordinal_position;

SELECT '=== 2b. state 的全量 distinct 值与分布 ===' AS section;
SELECT state, COUNT(*) AS row_count
FROM col_summary
GROUP BY state
ORDER BY row_count DESC;

SELECT '=== 2c. 加班单能否关联到 col_summary（关联覆盖率）===' AS section;
SELECT
    COUNT(*)                                     AS overtime_main_rows,
    SUM(CASE WHEN c.form_recordid IS NOT NULL
             THEN 1 ELSE 0 END)                  AS matched_to_col_summary,
    SUM(CASE WHEN c.form_recordid IS NULL
             THEN 1 ELSE 0 END)                  AS unmatched
FROM formmain_0171 m
LEFT JOIN col_summary c ON c.form_recordid = m.id;

SELECT '=== 2d. 加班单按 state 的分布（确认 3 是否等于最终批准）===' AS section;
SELECT c.state, COUNT(*) AS overtime_documents
FROM formmain_0171 m
JOIN col_summary c ON c.form_recordid = m.id
GROUP BY c.state
ORDER BY overtime_documents DESC;

-- ---------------------------------------------------------------------------
-- 3. OA-ENUM-02：加班类别 field0096 → 义务加班 / 加班费 / 调休
--    你给的方法：field0096 存枚举 id，去 ctp_enum_item 按 id 查 showvalue。
--    3c 段直接产出可签字的映射表。
-- ---------------------------------------------------------------------------

SELECT '=== 3a. ctp_enum_item 列结构 ===' AS section;
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'szoa' AND table_name = 'ctp_enum_item'
ORDER BY ordinal_position;

SELECT '=== 3b. field0096 原始值分布 ===' AS section;
SELECT field0096 AS raw_value, COUNT(*) AS row_count
FROM formson_0172
GROUP BY field0096
ORDER BY row_count DESC;

SELECT '=== 3c. ★ 加班类别封闭映射（这段是 B-5 的关键产出）★ ===' AS section;
SELECT
    s.field0096                AS raw_enum_id,
    e.showvalue                AS label,
    COUNT(*)                   AS row_count
FROM formson_0172 s
LEFT JOIN ctp_enum_item e ON e.id = s.field0096
GROUP BY s.field0096, e.showvalue
ORDER BY row_count DESC;

SELECT '=== 3d. 有没有原始值查不到 showvalue（必须为 0，否则要 fail-closed）===' AS section;
SELECT COUNT(*) AS unmapped_rows
FROM formson_0172 s
LEFT JOIN ctp_enum_item e ON e.id = s.field0096
WHERE s.field0096 IS NOT NULL AND e.showvalue IS NULL;

-- ---------------------------------------------------------------------------
-- 4. OA-ENUM-01：请假类别 field0089（同一方法）
-- ---------------------------------------------------------------------------

SELECT '=== 4. 请假类别封闭映射 ===' AS section;
SELECT
    m.field0089                AS raw_enum_id,
    e.showvalue                AS label,
    COUNT(*)                   AS row_count
FROM formmain_0170 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
GROUP BY m.field0089, e.showvalue
ORDER BY row_count DESC;

-- ---------------------------------------------------------------------------
-- 5. OA-MEMBER-02：org_member.code ↔ 得力 employee_number 绑定
--    只回计数和摘要，不回工号明文。
-- ---------------------------------------------------------------------------

SELECT '=== 5a. org_member 列结构 ===' AS section;
SELECT column_name, data_type, is_nullable, column_key
FROM information_schema.columns
WHERE table_schema = 'szoa' AND table_name = 'org_member'
ORDER BY ordinal_position;

SELECT '=== 5b. code 的形状（不含明文）===' AS section;
SELECT
    COUNT(*)                                              AS total_members,
    COUNT(DISTINCT code)                                  AS distinct_codes,
    SUM(CASE WHEN code IS NULL
              OR OCTET_LENGTH(code) = 0
              OR code REGEXP '^[[:space:]]+$'
             THEN 1 ELSE 0 END)
                                                            AS blank_codes,
    MIN(CASE WHEN code IS NOT NULL
              AND OCTET_LENGTH(code) > 0
              AND NOT (code REGEXP '^[[:space:]]+$')
             THEN CHAR_LENGTH(code) END)                    AS min_len,
    MAX(CASE WHEN code IS NOT NULL
              AND OCTET_LENGTH(code) > 0
              AND NOT (code REGEXP '^[[:space:]]+$')
             THEN CHAR_LENGTH(code) END)                    AS max_len,
    SUM(CASE WHEN code REGEXP '^0' THEN 1 ELSE 0 END)     AS leading_zero_codes,
    SUM(CASE WHEN code REGEXP '[^0-9]' THEN 1 ELSE 0 END) AS non_numeric_codes
FROM org_member;

SELECT '=== 5c. code 是否唯一（决定能否做 1:1 绑定）===' AS section;
SELECT dup_count, COUNT(*) AS num_codes
FROM (
    SELECT code, COUNT(*) AS dup_count
    FROM org_member
    WHERE code IS NOT NULL
      AND OCTET_LENGTH(code) > 0
      AND NOT (code REGEXP '^[[:space:]]+$')
    GROUP BY code
) t
WHERE dup_count > 1
GROUP BY dup_count
ORDER BY dup_count;

-- ---------------------------------------------------------------------------
-- 6. OA-TIME-01：时间字段的时区解释
-- ---------------------------------------------------------------------------

SELECT '=== 6. 加班明细时间字段的实际类型与范围 ===' AS section;
SELECT
    MIN(field0100) AS earliest_start,
    MAX(field0100) AS latest_start,
    MIN(field0099) AS earliest_end,
    MAX(field0099) AS latest_end,
    SUM(CASE WHEN field0099 <= field0100 THEN 1 ELSE 0 END) AS non_positive_intervals,
    SUM(CASE WHEN DATE(field0099) <> DATE(field0100)
             THEN 1 ELSE 0 END)                            AS cross_midnight_rows
FROM formson_0172;

SELECT '=== 完 ===' AS section;

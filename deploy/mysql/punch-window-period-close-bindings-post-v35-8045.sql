-- ============================================================
-- 神州HR 考勤策略绑定初始数据
-- PUNCH_WINDOW + PERIOD_CLOSE 各9个考勤组 = 18条绑定
-- 依赖：V35 迁移（scoped_version + PUBLISHED lifecycle 已存在）
-- 可重放：所有INSERT 均有 NOT EXISTS 保护，重复执行不产生重复行
-- ============================================================

-- ──────────────────────────────────────────────
-- 1. PUNCH_WINDOW 绑定家族（9条）
-- ──────────────────────────────────────────────
INSERT INTO attendance_policy_binding_family (
    binding_family_id, attendance_group_id, policy_kind, created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(d.sha, 1, 8), '-',
           SUBSTRING(d.sha, 9, 4), '-',
           SUBSTRING(d.sha, 13, 4), '-',
           SUBSTRING(d.sha, 17, 4), '-',
           SUBSTRING(d.sha, 21, 12)),
       grp.attendance_group_id,
       'PUNCH_WINDOW',
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM attendance_group grp
JOIN (SELECT grp2.attendance_group_id,
             SHA2(CONCAT('V35:BF:PUNCH_WINDOW:', grp2.attendance_group_id), 256) AS sha
      FROM attendance_group grp2) d
    ON d.attendance_group_id = grp.attendance_group_id
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_binding_family existing
    WHERE existing.attendance_group_id = grp.attendance_group_id
      AND existing.policy_kind = 'PUNCH_WINDOW'
);

-- ──────────────────────────────────────────────
-- 2. PERIOD_CLOSE 绑定家族（9条）
-- ──────────────────────────────────────────────
INSERT INTO attendance_policy_binding_family (
    binding_family_id, attendance_group_id, policy_kind, created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(d.sha, 1, 8), '-',
           SUBSTRING(d.sha, 9, 4), '-',
           SUBSTRING(d.sha, 13, 4), '-',
           SUBSTRING(d.sha, 17, 4), '-',
           SUBSTRING(d.sha, 21, 12)),
       grp.attendance_group_id,
       'PERIOD_CLOSE',
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM attendance_group grp
JOIN (SELECT grp2.attendance_group_id,
             SHA2(CONCAT('V35:BF:PERIOD_CLOSE:', grp2.attendance_group_id), 256) AS sha
      FROM attendance_group grp2) d
    ON d.attendance_group_id = grp.attendance_group_id
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_binding_family existing
    WHERE existing.attendance_group_id = grp.attendance_group_id
      AND existing.policy_kind = 'PERIOD_CLOSE'
);

-- ──────────────────────────────────────────────
-- 3. PUNCH_WINDOW 绑定版本（9条）
-- 指向V35创建的公司级scoped_version（version_number=1）
-- 使用"末梢"考勤组revision（不被任何其他revision supersede的那条）
-- ──────────────────────────────────────────────
INSERT INTO attendance_policy_binding_revision (
    binding_revision_id, binding_family_id, attendance_group_revision_id,
    attendance_policy_scoped_version_id, revision_number, effective_from,
    supersedes_binding_revision_id, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(d.sha, 1, 8), '-',
           SUBSTRING(d.sha, 9, 4), '-',
           SUBSTRING(d.sha, 13, 4), '-',
           SUBSTRING(d.sha, 17, 4), '-',
           SUBSTRING(d.sha, 21, 12)),
       bf.binding_family_id,
       tip_rev.attendance_group_revision_id,
       sv.scoped_version_id,
       1,
       '2026-01-01',
       NULL,
       SHA2(CONCAT('V35:BR_DIGEST:PUNCH_WINDOW:', grp.attendance_group_id, ':', sv.scoped_version_id), 256),
       'V35 打卡取卡窗口策略初始绑定',
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM attendance_group grp
-- 末梢group revision（未被任何其他revision supersede）
JOIN attendance_group_revision tip_rev
    ON tip_rev.attendance_group_id = grp.attendance_group_id
   AND NOT EXISTS (
       SELECT 1 FROM attendance_group_revision newer
       WHERE newer.supersedes_attendance_group_revision_id = tip_rev.attendance_group_revision_id
   )
-- binding family（刚刚创建或已存在）
JOIN attendance_policy_binding_family bf
    ON bf.attendance_group_id = grp.attendance_group_id
   AND bf.policy_kind = 'PUNCH_WINDOW'
-- V35 PUNCH_WINDOW scoped_version，按公司匹配
JOIN attendance_policy_scope sc
    ON sc.company_id = grp.company_id
JOIN attendance_policy_template t
    ON t.policy_template_id = sc.policy_template_id
   AND t.template_code = 'PUNCH_WINDOW'
JOIN attendance_policy_scoped_version sv
    ON sv.scope_id = sc.scope_id
   AND sv.version_number = 1
-- SHA2 digest（避免重复计算）
JOIN (SELECT grp2.attendance_group_id,
             SHA2(CONCAT('V35:BR:PUNCH_WINDOW:', grp2.attendance_group_id), 256) AS sha
      FROM attendance_group grp2) d
    ON d.attendance_group_id = grp.attendance_group_id
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_binding_revision existing
    WHERE existing.binding_family_id = bf.binding_family_id
      AND existing.revision_number = 1
);

-- ──────────────────────────────────────────────
-- 4. PERIOD_CLOSE 绑定版本（9条）
-- ──────────────────────────────────────────────
INSERT INTO attendance_policy_binding_revision (
    binding_revision_id, binding_family_id, attendance_group_revision_id,
    attendance_policy_scoped_version_id, revision_number, effective_from,
    supersedes_binding_revision_id, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT CONCAT(
           SUBSTRING(d.sha, 1, 8), '-',
           SUBSTRING(d.sha, 9, 4), '-',
           SUBSTRING(d.sha, 13, 4), '-',
           SUBSTRING(d.sha, 17, 4), '-',
           SUBSTRING(d.sha, 21, 12)),
       bf.binding_family_id,
       tip_rev.attendance_group_revision_id,
       sv.scoped_version_id,
       1,
       '2026-01-01',
       NULL,
       SHA2(CONCAT('V35:BR_DIGEST:PERIOD_CLOSE:', grp.attendance_group_id, ':', sv.scoped_version_id), 256),
       'V35 月结封账策略初始绑定',
       '20000000-0000-0000-0000-000000000001',
       CURRENT_TIMESTAMP(6)
FROM attendance_group grp
JOIN attendance_group_revision tip_rev
    ON tip_rev.attendance_group_id = grp.attendance_group_id
   AND NOT EXISTS (
       SELECT 1 FROM attendance_group_revision newer
       WHERE newer.supersedes_attendance_group_revision_id = tip_rev.attendance_group_revision_id
   )
JOIN attendance_policy_binding_family bf
    ON bf.attendance_group_id = grp.attendance_group_id
   AND bf.policy_kind = 'PERIOD_CLOSE'
JOIN attendance_policy_scope sc
    ON sc.company_id = grp.company_id
JOIN attendance_policy_template t
    ON t.policy_template_id = sc.policy_template_id
   AND t.template_code = 'PERIOD_CLOSE'
JOIN attendance_policy_scoped_version sv
    ON sv.scope_id = sc.scope_id
   AND sv.version_number = 1
JOIN (SELECT grp2.attendance_group_id,
             SHA2(CONCAT('V35:BR:PERIOD_CLOSE:', grp2.attendance_group_id), 256) AS sha
      FROM attendance_group grp2) d
    ON d.attendance_group_id = grp.attendance_group_id
WHERE NOT EXISTS (
    SELECT 1 FROM attendance_policy_binding_revision existing
    WHERE existing.binding_family_id = bf.binding_family_id
      AND existing.revision_number = 1
);

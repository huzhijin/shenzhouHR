-- 大连考勤组晚餐扣减触发门槛：0 → 120 分钟（客户 2026-08-09 确认）
--
-- 客户原话：「大连考勤时间 7:30-12:00 13:00-16:30（加班晚上根据实际情况不一定扣
-- 晚餐 0.5 小时），最好也是可以自定义规则」。经确认「实际情况」指按加班时长自动
-- 判断，门槛 2 小时 = 120 分钟，扣除仍为 30 分钟。适用范围经确认为**仅大连 13 人**，
-- 其余 SZSC 各组（成都/上海/默认，共 553 人）保持门槛 0 不变。
--
-- 为什么放在 deploy/mysql 而不是 Flyway 迁移：
-- 这是公司与考勤组特定的运营数据。全新库不存在 SZSC 公司和大连考勤组，写成迁移
-- 会在新库上匹配 0 行（无害但无意义），且会把客户特定配置混入产品迁移序列。
--
-- 隔离性依据（已实测）：AttendancePolicyMapper.xml#resolveBindings 按
--   WHERE family.attendance_group_id = #{groupId}
--     AND revision.attendance_group_revision_id = #{groupRevisionId}
-- 过滤，且策略版本号取自绑定行的 attendance_policy_scoped_version_id。
-- 因此在 SZSC 公司作用域下新建 v2、只让大连的 binding_family 指向它，
-- 其余组的绑定仍指向 v1，不会受影响。
--
-- 同一查询还要求：版本必须有 action='PUBLISHED' 的生命周期事件才会被解析到。
-- 只插 scoped_version 行是不生效的，所以本脚本同时写入 PUBLISHED 事件。
-- 新增 v2 的 PUBLISHED 事件不会让 v1 失效——解析器只在存在更高 event_sequence 的
-- DEACTIVATE_SCHEDULED 或 ROLLED_BACK 时才排除版本。
--
-- 幂等：每条 INSERT 均由 NOT EXISTS 保护，ID 由 SHA2 确定性派生，重复执行无变化。
-- v1 与绑定 revision 1 原样保留供审计，不做 UPDATE、不删除任何历史行。
--
-- 回滚（按依赖倒序）：
--   DELETE FROM attendance_policy_binding_revision
--     WHERE binding_revision_id = (SELECT ... rev2 id);
--   DELETE FROM attendance_policy_lifecycle_event
--     WHERE scoped_version_id = (SELECT ... v2 id) AND action='PUBLISHED';
--   DELETE FROM attendance_policy_scoped_version
--     WHERE scoped_version_id = (SELECT ... v2 id);

SET @actor = '20000000-0000-0000-0000-000000000001';
SET @now = CURRENT_TIMESTAMP(6);
SET @effective_from = '2026-01-01';
-- 绑定修订 2 必须使用不同于 rev1 的生效日：唯一键
-- uq_attendance_policy_binding_revision_effective 是 (binding_family_id, effective_from)，
-- 而 rev1 已占用 2026-01-01。取 2026-01-02 是安全的：1 月 1 日为法定节假日，
-- 当天生效的是 publicHolidayTriggerMinutes，与本次改动的 triggerMinutes 无关，
-- 因此这一天的行为不受影响，全年其余日期均由 rev2 覆盖。
SET @binding_effective_from = '2026-01-02';
SET @reason = '2026-08-09 客户确认：大连晚餐扣减触发门槛 120 分钟（仅大连考勤组）';

SET @company_id = '41000000-0000-0000-0000-000000000003';
SET @group_id = '42040000-0000-0000-0000-000000000001';
SET @group_revision_id = '42041000-0000-0000-0000-000000000001';
SET @binding_family_id = '92be8a57-ad90-ce30-21a4-0a010e8ae264';

-- SZSC 的 MEAL_DEDUCTION 作用域与其 v1 版本（v2 从 v1 复制参数）
SET @scope_id = (
    SELECT s.scope_id
    FROM attendance_policy_scope s
    JOIN attendance_policy_template t
      ON t.policy_template_id = s.policy_template_id
     AND t.template_code = 'MEAL_DEDUCTION'
    WHERE s.company_id = @company_id
);
SET @v1_id = (
    SELECT v.scoped_version_id
    FROM attendance_policy_scoped_version v
    WHERE v.scope_id = @scope_id AND v.version_number = 1
);

-- 确定性派生 ID，保证重放时命中 NOT EXISTS 守卫而不是插入重复行
SET @v2_digest = SHA2(CONCAT('DALIAN_MEAL_V2:', @scope_id), 256);
SET @v2_id = CONCAT(
    SUBSTRING(@v2_digest, 1, 8), '-', SUBSTRING(@v2_digest, 9, 4), '-',
    SUBSTRING(@v2_digest, 13, 4), '-', SUBSTRING(@v2_digest, 17, 4), '-',
    SUBSTRING(@v2_digest, 21, 12));

SET @evt_digest = SHA2(CONCAT('DALIAN_MEAL_V2_PUB:', @scope_id), 256);
SET @evt_id = CONCAT(
    SUBSTRING(@evt_digest, 1, 8), '-', SUBSTRING(@evt_digest, 9, 4), '-',
    SUBSTRING(@evt_digest, 13, 4), '-', SUBSTRING(@evt_digest, 17, 4), '-',
    SUBSTRING(@evt_digest, 21, 12));

SET @rev_digest = SHA2(CONCAT('DALIAN_MEAL_BIND_R2:', @binding_family_id), 256);
SET @rev2_id = CONCAT(
    SUBSTRING(@rev_digest, 1, 8), '-', SUBSTRING(@rev_digest, 9, 4), '-',
    SUBSTRING(@rev_digest, 13, 4), '-', SUBSTRING(@rev_digest, 17, 4), '-',
    SUBSTRING(@rev_digest, 21, 12));

-- 1) v2：复制 v1 的全部 33 个键，只改 triggerMinutes = 120
INSERT INTO attendance_policy_scoped_version (
    scoped_version_id, scope_id, version_number, parameters_json,
    effective_from, effective_to, validation_json, snapshot_json,
    snapshot_digest, rollback_of_scoped_version_id, row_version,
    change_reason, created_by, created_at
)
SELECT @v2_id, @scope_id, 2,
       JSON_SET(v1.parameters_json, '$.triggerMinutes', 120),
       '1970-01-01', NULL,
       JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
       JSON_OBJECT(
           'scopeId', @scope_id,
           'companyId', @company_id,
           'parameters', JSON_SET(v1.parameters_json, '$.triggerMinutes', 120),
           'policyKind', 'MEAL_DEDUCTION',
           'templateId', '25000000-0000-0000-0000-000000000001',
           'effectiveTo', NULL,
           'effectiveFrom', '1970-01-01',
           'versionNumber', 2),
       SHA2(CONCAT('SCOPED:MEAL_DEDUCTION:2:DALIAN_TRIGGER_120:', @scope_id), 256),
       NULL, 0, @reason, @actor, @now
FROM attendance_policy_scoped_version v1
WHERE v1.scoped_version_id = @v1_id
  AND NOT EXISTS (
      SELECT 1 FROM attendance_policy_scoped_version existing
      WHERE existing.scope_id = @scope_id AND existing.version_number = 2
  );

-- 2) v2 的 PUBLISHED 事件（resolveBindings 的硬性要求）
INSERT INTO attendance_policy_lifecycle_event (
    lifecycle_event_id, scope_id, scoped_version_id, event_sequence,
    action, business_effective_from, predecessor_event_id,
    reason, actor_id, request_id, recorded_at
)
SELECT @evt_id, @scope_id, @v2_id, 2,
       'PUBLISHED', @effective_from, NULL,
       @reason, @actor,
       CONCAT('DALIAN-MEAL-120-', SUBSTRING(@v2_id, 1, 8)), @now
WHERE EXISTS (
      SELECT 1 FROM attendance_policy_scoped_version v
      WHERE v.scoped_version_id = @v2_id
  )
  AND NOT EXISTS (
      SELECT 1 FROM attendance_policy_lifecycle_event e
      WHERE e.scoped_version_id = @v2_id AND e.action = 'PUBLISHED'
  );

-- 3) 大连绑定 revision 2 指向 v2（其余组的绑定不动，仍指向 v1）
INSERT INTO attendance_policy_binding_revision (
    binding_revision_id, binding_family_id, attendance_group_revision_id,
    attendance_policy_scoped_version_id, revision_number, effective_from,
    supersedes_binding_revision_id, snapshot_digest, change_reason,
    created_by, created_at
)
SELECT @rev2_id, @binding_family_id, @group_revision_id,
       @v2_id, 2, @binding_effective_from,
       prev.binding_revision_id,
       SHA2(CONCAT('BINDING:MEAL_DEDUCTION:DALIAN:2:', @v2_id), 256),
       @reason, @actor, @now
FROM attendance_policy_binding_revision prev
WHERE prev.binding_family_id = @binding_family_id
  AND prev.revision_number = 1
  AND EXISTS (
      SELECT 1 FROM attendance_policy_scoped_version v
      WHERE v.scoped_version_id = @v2_id
  )
  AND NOT EXISTS (
      SELECT 1 FROM attendance_policy_binding_revision existing
      WHERE existing.binding_family_id = @binding_family_id
        AND existing.revision_number = 2
  );

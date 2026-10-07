-- V46: 创建 SYSTEM 审计主体
--
-- 问题：定时任务以字面量 'SYSTEM' 写入 created_by/updated_by 字段
--       这些字段外键到 auth_principal，但 V1-V44 未创建该主体
-- 影响：启用得力定时同步会因外键失败而无法写入
--
-- 解决方案：创建专用系统主体，供自动化任务使用

INSERT INTO auth_principal (
    principal_id,
    employee_id,
    status,
    row_version,
    created_at
) VALUES (
    'SYSTEM',
    NULL,
    'ACTIVE',
    0,
    CURRENT_TIMESTAMP(6)
) ON DUPLICATE KEY UPDATE principal_id = principal_id;

-- 说明：
-- - principal_id 使用字面量 'SYSTEM'，与代码中传入的值匹配
-- - employee_id 留空，表示非员工自动化主体
-- - 不创建关联的 local_account，因为系统主体不需要登录
-- - 不分配角色，因为系统主体不通过权限检查
-- - ON DUPLICATE KEY UPDATE 保证幂等性

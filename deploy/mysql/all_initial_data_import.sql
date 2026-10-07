-- ============================================================
-- 神州HR 期初数据完整导入脚本
-- 生成时间: 2026-08-11 20:35:10
-- 包含: V5.1 人员数据(620人) + customer-8045 客户定制数据(5个文件)
-- 导入顺序已固定，按顺序执行即可
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

-- ============================================================
-- 1/6: V5.1 人员数据 (620人 + 132部门 + 4公司)
-- ============================================================
-- ============================================================================
-- 神州HR - 完整人员期初数据导入 (622 人)
-- 生成时间: 2026-08-11 16:59:12
-- 数据来源: 人员列表_seeyon1.xls + departments_seeyon1.xls
-- ============================================================================

SET @system_principal_id = '00000000-0000-0000-0000-000000000001';
SET @now = CURRENT_TIMESTAMP(6);

-- ============================================================================
-- 1. 确保所有部门存在
-- ============================================================================

-- 部门: 1级部门 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1eb6b739-6864-669b-7f63-95e414dc836e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_1级部门', '1级部门',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海昇州半导体科技有限公司 (上海昇州半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5f025d51-5ae3-9a4f-8933-f4d617257f57', '2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', 'SZSZ_DEPT_上海昇州半导体科技有', '上海昇州半导体科技有限公司',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海昇州半导体科技有限公司/总经办 (上海昇州半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5079f3b0-fdbe-42ce-cd77-ba13cf9d353d', '2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', 'SZSZ_DEPT_上海昇州半导体科技有', '上海昇州半导体科技有限公司/总经办',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'beffba68-fa24-dc5a-8931-a281a80ea1c0', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海人事行政部 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'aac4828c-5fe1-637b-1ee5-72a17d200e4e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海人事行政部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海人事行政部/上海人事行政部（审批） (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '19f30158-e523-37bf-4da0-f35d616a260d', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海人事行政部/上海人事行政部（审批）',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海总经办 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '22e2a7fa-e8ce-d1b5-807b-b39c39f191a1', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海总经办',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海总经办/上海总经办（审批2） (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1611fec3-8097-2952-9142-fcca4ca64510', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海总经办/上海总经办（审批2）',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海总经办/上海总经办（审批） (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '87060fb9-3604-3642-10c3-4c5afd473899', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海总经办/上海总经办（审批）',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ee90212f-5906-c292-169f-6afe2183a207', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海射频组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f5a4690a-5b90-2d37-2eed-bc0020b5f471', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海射频组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海测试组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b927fc64-cea4-4af3-35cf-320e4c1569a9', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海硬件设计组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '8668845b-77df-2265-bce7-87faeee7b944', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海硬件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海结构组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '021f1780-e986-b95a-d890-72db283dfe91', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海结构组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海软件设计组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '4590d641-6df4-e16c-6a9f-914172f099c7', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海软件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海项目组 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '35ba9eb4-f6e9-d5e5-244f-8a3afac6e778', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海研发部/上海RD1/上海项目组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 上海晟州聚能半导体科技有限公司/上海销售部 (上海晟州聚能半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '0456fc3e-e7db-ed65-ecac-6f428630668e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN_DEPT_上海晟州聚能半导体科', '上海晟州聚能半导体科技有限公司/上海销售部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 模板使用规则(使用前须仔细阅读)：
基本规则说明：
1. 部门各级次名称：按照部门级次依次罗列，例如：六级部门、七级部门等可在【部门代码】列前依次增加。同部门下的所有部门必须连续罗列
2. 部门代码：选填，如果选择部门代码作为数据唯一标识则必填，否则导入失败
3. 部门级次：必填，部门所在的级次(可使用COUNTA函数计算部门级次)，例如：”营销中心“的部门级次是1，”营销中心-销售部-华北区域营销部“的部门级次是3                       
4. 排序号：必填，正整数，系统中同级子部门按照排序号排列
5. 描述：选填
6. 部门角色：【描述】列后是部门角色列，请填写系统中存在的、且状态为启用的部门角色名称
     部门角色规则说明：1）导入部门的角色时，必须保证【部门各级次名称】【部门代码】【部门级次】【排序号】【描述】列完整
                                       2）只支持导入系统中已有人员，多个人员时，姓名以顿号“、”隔开
                                       3）当部门角色人员有重名时，需在人员姓名后增加登录名，如王东(wangd1)，否则会导入失败
7.导入时可选择，部门名称或者部门代码作为数据唯一标识，导入的数据会根据选择唯一标识覆盖或跳过 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'c38594fc-4282-7fac-0ac8-b7654ab8ed4d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_模板使用规则(使用前', '模板使用规则(使用前须仔细阅读)：
基本规则说明：
1. 部门各级次名称：按照部门级次依次罗列，例如：六级部门、七级部门等可在【部门代码】列前依次增加。同部门下的所有部门必须连续罗列
2. 部门代码：选填，如果选择部门代码作为数据唯一标识则必填，否则导入失败
3. 部门级次：必填，部门所在的级次(可使用COUNTA函数计算部门级次)，例如：”营销中心“的部门级次是1，”营销中心-销售部-华北区域营销部“的部门级次是3                       
4. 排序号：必填，正整数，系统中同级子部门按照排序号排列
5. 描述：选填
6. 部门角色：【描述】列后是部门角色列，请填写系统中存在的、且状态为启用的部门角色名称
     部门角色规则说明：1）导入部门的角色时，必须保证【部门各级次名称】【部门代码】【部门级次】【排序号】【描述】列完整
                                       2）只支持导入系统中已有人员，多个人员时，姓名以顿号“、”隔开
                                       3）当部门角色人员有重名时，需在人员姓名后增加登录名，如王东(wangd1)，否则会导入失败
7.导入时可选择，部门名称或者部门代码作为数据唯一标识，导入的数据会根据选择唯一标识覆盖或跳过',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '0bca50fd-b765-9c36-0199-cf8289aa22c1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '229540b2-5cd1-43e7-12b3-b6cbd5f14dec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造工程部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5b45289b-5364-a75d-1e04-68c160a94204', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造工程部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '7bb1b23e-460f-bf22-5756-d7cab5a580b6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '06ca0f02-5d21-2453-d64e-978f0042eaf1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd20bda8b-3529-2af6-ba01-998bc3ba1243', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-焊接装配组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '57638b6f-78f4-f4c4-5b4d-83fc847b7117', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-焊接装配组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-装配组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'afb90a69-a46b-7b77-c65c-cabe45d92c01', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造一部/制造一部-装配组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd1248e71-f3b2-cbc5-c070-79928f60ac06', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '7ea98b08-aeec-b026-b928-66b61cbcc248', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-焊接装配组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ea81a5c9-a571-2f1d-45ba-110a51160d71', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-焊接装配组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-装配组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '73a395cc-d910-7905-e96e-9217f4a831fd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/制造中心/制造中心-制造部/制造部-制造二部/制造二部-装配组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/总经办 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '4953930c-5a0a-2bea-308e-a4953fb9d821', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/总经办',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/总经办/关务部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a18be899-039f-0217-78e3-720e14c1ddec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/总经办/关务部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/总经办/基建部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '53ac7b8a-b274-71e3-b247-efda0df35d22', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/总经办/基建部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/总经办/总经办 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b1f274aa-b223-1e4d-3201-2e6834142180', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/总经办/总经办',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/总经理 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'cfcc9650-87f7-01bc-8a46-9826545f1f6e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/总经理',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f4da2f35-d7e5-7d6e-a30f-007a57e1a616', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-DC技术组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ad8bcd2b-429e-c8ce-43ea-4452dcf8f02f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-DC技术组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-MATCH技术组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '59b4f46b-fad7-aec4-716b-904addab8189', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-MATCH技术组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-RF技术组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a24266db-a078-9c87-600c-484bf9e83dc1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-RF技术组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-RPS技术组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'be6c8d2c-e6b3-9341-d971-d9cf237f3569', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-RPS技术组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-电子技术组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f8f04886-cfef-f3db-0149-ee354339c81f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-产品技术支持部/技术支持中心-产品技术支持部-电子技术组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-现场服务部/技术支持中心-现场服务部-武汉产品服务组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '66be2178-5bdc-6467-ed3d-3025cb595022', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-现场服务部/技术支持中心-现场服务部-武汉产品服务组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-现场服务部/技术支持中心-现场服务部-现场技术服务组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '9ff30190-808b-6a97-3f62-f29b95ce6620', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-现场服务部/技术支持中心-现场服务部-现场技术服务组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-生管部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ce634182-fa27-6b58-6356-095c849ad4ea', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/技术支持中心/技术支持中心-生管部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'eab0b10a-501e-2edf-fa9f-9f6d17c649f1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-PCB部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-PCB部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-厂务部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-厂务部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-厂务部/服务中心-厂务部-上海昇州 (上海昇州半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd3022065-a80d-98b5-4f85-3db96e119b86', '2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', 'SZSZ_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-厂务部/服务中心-厂务部-上海昇州',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-合肥办事处 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a4f3f7f2-b3e3-87a7-39fc-fd9a7409bc79', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-合肥办事处',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-大连办事处 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'bda51b24-1962-1e0c-9383-75fdaa819c6a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-大连办事处',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-武汉办事处 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'c3679ea0-41e0-02e3-dfd9-f28a62ddd982', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-武汉办事处',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-深圳办事处 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'af797e91-9e62-9d6e-dde1-c4c96807131e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-客户现场服务部/客户现场服务部-深圳办事处',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '6d0b0028-8754-555e-5695-7249e6999d09', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-DC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd1ff01c5-adc6-e027-e096-6cb857003cdb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-DC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '8320ece9-a81c-a710-8dd6-4de0b7c3b697', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组/工程一部-MATCH组-MATCH1组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f0182236-7f25-c349-ee23-e73328e05767', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组/工程一部-MATCH组-MATCH1组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组/工程一部-MATCH组-MATCH2组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-MATCH组/工程一部-MATCH组-MATCH2组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-RPS组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '7c85e1e8-398b-0d6c-75c7-94f777ed9003', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程一部/工程一部-RPS组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5cca47c4-88c1-4edc-ea5c-8a5d299bb9f9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-A组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '0b5083df-4f27-f4e2-5890-1f0fef7d759e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-A组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-B组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '122bcce6-f526-ce6d-247a-97612ba983aa', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-B组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-E组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd6840d19-e044-f127-cb8b-9b38978b7881', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-E组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-F组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ef015ccc-db1c-4983-323a-bf7a617777e2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-F组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-G组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '66e76b74-3be4-e9ea-c717-5a79c8f253ec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-工程二部/工程二部-RF-G组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-服务技改部/服务技改部-技改组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-服务技改部/服务技改部-技改组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-服务技改部/服务技改部-测台开发组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a113e144-5120-9ef2-1912-207fde631279', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-服务技改部/服务技改部-测台开发组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/服务中心/服务中心-生管部/生管部-PMC部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/服务中心/服务中心-生管部/生管部-PMC部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b7b6cc72-ad9c-583f-5ebd-6fe14681eb4c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-工艺组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '6e9c0cc5-d84d-c361-a714-e4108f14373c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-工艺组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-硬件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '937d2188-d785-4be9-6a96-62988c8c2ef3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-硬件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-结构组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '2e377986-558d-7db4-6d8e-0c2285cf5196', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-结构组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-软件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'baa6456f-3d84-9b00-4024-a51aea8fc606', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-软件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-项目组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '9725fda0-6734-512f-ff0e-83d1ab112cb6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-DC部/DC部-项目组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '252e20e4-05a8-a94e-9708-eedd92b6d64e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-工艺组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'debadcb0-5b8c-f39e-15ee-4a7cf2c73fff', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-工艺组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-总体组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '58c67682-b975-08fc-7a6f-951c8e98ea9a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-总体组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5e240fef-491a-37f8-efbd-e51ebb82b45f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-硬件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '021b7fb0-4b1d-4a2b-d4cf-9637d4e7bb87', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-硬件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-结构组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '53420bc3-6d35-7443-ac5d-37d6281f1913', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-结构组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-软件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-软件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-项目组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd5a97fc7-142e-4a54-21f9-9cae982538be', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-MATCH部/MATCH部-项目组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '97a443ef-4d4e-3943-7aaa-12e180c47490', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-射频组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-射频组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-工艺组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-工艺组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1731601e-ab3e-3858-c335-b036ddc426b5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-硬件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '315e300d-1533-71a1-be5f-d7bd30eaf17d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-硬件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-结构组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b3d90cf8-4410-1ee2-d4e4-706c1ac1d539', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-结构组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-软件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a0d771a6-87fe-a34b-1bcd-51b3860c8536', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-软件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-项目组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '0ccddfef-18a9-960d-4c3e-862d35c8895f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RF部/RF部-项目组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '92c4e059-248a-bdcf-73c3-e22503cf2da0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-工艺组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '66e53ea3-40ac-c4c9-671c-e7d3e8e417e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-工艺组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-测试组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '36058f33-a125-e6fc-6520-cd4820f07ccd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-测试组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-硬件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '419bbf47-8da2-83b4-8efe-a9023fe14df2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-硬件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-结构组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-结构组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-软件设计组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '243122f3-450f-3406-c7fe-cbbb2df4f99c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-软件设计组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-项目组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'bfbb87f3-942d-b5ec-47f1-5248ffafd231', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/研发中心/研发中心-RPS部/RPS部-项目组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/董事长室 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '36b3c042-3317-4a9a-1590-31fd95bba73e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/董事长室',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/财务中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '607ce9f9-4cc1-13c6-b022-c44276d5a4ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/财务中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/财务中心/财务中心-会计核算部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '6e409529-dfce-856e-236c-8390e449e535', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/财务中心/财务中心-会计核算部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/财务中心/财务中心-投资部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '864f3d24-541c-1d22-293d-e049c392bdb4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/财务中心/财务中心-投资部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1b216a4c-931d-5ef4-96e9-5a58429164a6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-FQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '7cb952b0-60c3-b6af-9c12-94f95878f74a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-FQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-IQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '65f47257-faa8-080e-ceda-78bfdf0289a3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-IQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-PQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a3c53269-90cf-116e-2f95-3262b80f1ad8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-PQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-QE组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '6cdbfced-5ca9-413f-a231-b6386321b980', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-制造Q部/制造Q部-QE组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1de78752-c845-a05f-7609-1edf8a7d4ceb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-DCC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b0107888-5576-a116-187a-57a883141f18', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-DCC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-FQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '8d5f3692-12d2-64b3-ecad-f40b8fbd232c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-FQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-IQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '586432bb-19cd-f65e-a084-395e8744cdae', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-IQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-PQC组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5797de5d-16a5-ece1-4554-32b5dcd6359f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-PQC组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-SE组 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd9b90066-8752-14f8-2f06-61d9b016acaa', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/质量中心/质量中心-服务Q部/服务Q部-SE组',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/资材中心/资材中心-PMC部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '65ca6a1e-ed4f-0f1a-24ba-f015dd9fc68d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/资材中心/资材中心-PMC部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/资材中心/资材中心-仓储管理部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '98d12062-d479-9427-783f-7d3d1300062e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/资材中心/资材中心-仓储管理部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/资材中心/资材中心-采购部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '3e622e47-f435-d91b-871e-848669da7a0c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/资材中心/资材中心-采购部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/运营中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd2750418-df89-6f56-b58b-bbc8f8fa4b10', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/运营中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/运营中心/运营中心-IT部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/运营中心/运营中心-IT部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/运营中心/运营中心-人事部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '9bdf65bb-c4a0-9310-02b6-affdd2cf633a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/运营中心/运营中心-人事部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/运营中心/运营中心-物流部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'a51e3d81-a18c-7706-f477-4e7f17895bca', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/运营中心/运营中心-物流部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/运营中心/运营中心-行政部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'd194254c-56df-0908-188a-6e27576cffcd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/运营中心/运营中心-行政部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '8f042b93-515c-6150-a47c-2c06ddce287c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心/销售中心-新品部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心/销售中心-新品部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部/服务部-国内销售部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '53cf8a70-2e33-7838-24d4-7b25e017f7df', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部/服务部-国内销售部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部/服务部-国际销售部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '08121ea8-e7b7-7dd0-7e5e-8026ae4784f0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心/销售中心-服务部/服务部-国际销售部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏神州半导体科技股份有限公司/销售中心/销售中心-贸易部 (江苏神州半导体科技股份有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '1c90b073-d088-7acd-74a6-caa0c3f8420a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSC_DEPT_江苏神州半导体科技股', '江苏神州半导体科技股份有限公司/销售中心/销售中心-贸易部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'e1500ae7-f25d-088a-09b0-aea357a5584a', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司/人事行政部 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '5e21214d-7a3d-aff7-e376-f34cba660011', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司/人事行政部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司/工程部 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'f4b80a62-8db2-59cb-ba4f-c458c74f34ca', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司/工程部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司/计划部 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b1ab2e1d-4021-d43f-5d1e-191eee9bd50a', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司/计划部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司/财务部 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '26e4aa3b-ea17-8493-b9c9-dd1276399c04', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司/财务部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- 部门: 江苏芯越半导体科技有限公司/销售部 (江苏芯越半导体科技有限公司)
INSERT IGNORE INTO department (
  department_id, company_id, department_code, department_name,
  parent_department_id, level, display_order, is_active,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  'b185c673-d8c5-3e14-ae2e-9b9497eb2ed2', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY_DEPT_江苏芯越半导体科技有', '江苏芯越半导体科技有限公司/销售部',
  NULL, 1, 1, TRUE,
  0, @system_principal_id, @now, @system_principal_id, @now
);

-- ============================================================================
-- 2. 批量插入人员 (620 人)
-- ============================================================================

-- 上海昇州半导体科技有限公司 (2 人)

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('7cdedf0b-18fb-ff35-4a73-05449a58ebc2', '2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', 'SZST0240', '王盼',
   'd3022065-a80d-98b5-4f85-3db96e119b86', '阀门维修工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0489a32e-603d-4b49-c47e-56f8f8acca9d', '2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', 'SZSZ0000', '叶剑',
   '5079f3b0-fdbe-42ce-cd77-ba13cf9d353d', '总经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

-- 上海晟州聚能半导体科技有限公司 (35 人)

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('db4acbac-ad95-9b86-3051-f575a203f4e3', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZST0002', '耿微',
   '22e2a7fa-e8ce-d1b5-807b-b39c39f191a1', '副总经理', '副总经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('75e1f456-519c-471f-e17f-bf1a631fa579', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZST0019', '马雨',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('48a57241-bc10-7210-757a-7ddce695bdf1', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZST0285', '汪立青',
   'ee90212f-5906-c292-169f-6afe2183a207', '研发经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2dac8184-a00a-a0a8-cc0f-1c1d129df861', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0002', '周步新',
   'b927fc64-cea4-4af3-35cf-320e4c1569a9', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5051d895-5def-941e-09f6-7e1a30eb7db3', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0006', '江忠朗',
   '021f1780-e986-b95a-d890-72db283dfe91', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('83b94dbe-1714-c136-1677-2d688dee35d6', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0007', '施昆卫',
   'f5a4690a-5b90-2d37-2eed-bc0020b5f471', '射频工程师', '员工', '13262506241', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('503c76ad-c96d-2d70-789a-20589d0514e9', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0010', '杨彬尉',
   'b927fc64-cea4-4af3-35cf-320e4c1569a9', '测试工程师', '员工', '15900440839', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bce20c90-7daf-0087-316f-74581a76ece6', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0013', '吴杰骁',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售专员', '员工', '17621922387', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b121e98c-f3c1-0fc0-8106-6db8cb9130a2', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0012', '赵俊君',
   'ee90212f-5906-c292-169f-6afe2183a207', '研发经理', '经理', '13167008960', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('74403d69-54ac-72fc-db52-8b151a44238e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0005', '吴思仪',
   'ee90212f-5906-c292-169f-6afe2183a207', '研发助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b7d6fe62-d6dc-4f7f-282a-a0802acd2510', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0015', '陈书傲',
   '35ba9eb4-f6e9-d5e5-244f-8a3afac6e778', '项目管理工程师', '员工', '16602129386', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cebfaaa1-b36a-562c-2138-007bf3bbda69', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0016', '周兴银',
   '4590d641-6df4-e16c-6a9f-914172f099c7', '算法工程师', '员工', '18017588269', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('483f842e-40b4-df97-c614-03c9b9c99b4e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0017', '曲宗鹏',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售工程师', '员工', '15201915232', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('77ab75c2-32c1-d19f-db49-36e507f90cd5', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0018', '刘浩南',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售工程师', '员工', '18621838846', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8069eea2-3580-3ed7-88f9-1f6f3b170e7f', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0020', '徐芳芳',
   'aac4828c-5fe1-637b-1ee5-72a17d200e4e', '人事专员', '员工', '13585752320', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eb5d850e-e6ca-755a-f51a-59133e0d90b1', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0004', '李莉',
   '22e2a7fa-e8ce-d1b5-807b-b39c39f191a1', '财务行政经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c867b998-64ca-3157-7337-608a31ba1e25', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0021', '时晨',
   '87060fb9-3604-3642-10c3-4c5afd473899', '证券事务代表', '员工', '18921230007', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2f7fa6a0-bb84-befc-ce32-3c564b8cbb7e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0023', '徐超明',
   '1611fec3-8097-2952-9142-fcca4ca64510', '采购助理', '员工', '13381916231', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ba75bd9d-6300-8a46-a9a7-950f74c51451', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0024', '刘东',
   '4590d641-6df4-e16c-6a9f-914172f099c7', 'FPGA软件开发工程师', '员工', '18817384506', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('61433e49-f23c-9e9c-2da6-c12f00611c66', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0025', '董柯涵',
   '4590d641-6df4-e16c-6a9f-914172f099c7', 'FPGA软件开发工程师', '员工', '19117336526', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('808f57ff-2c57-2756-c8b5-070f2874de05', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0026', '陈惠',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售助理', '员工', '18817828900', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cef4b0a1-3fa2-5144-e491-160db493fdb7', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0033', '吴凯',
   '021f1780-e986-b95a-d890-72db283dfe91', '结构助理工程师', '员工', '15050341052', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2944a172-a156-2058-6830-f33f252ee50b', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0028', '唐郁雯',
   '19f30158-e523-37bf-4da0-f35d616a260d', '公共关系专员', '员工', '13928837101', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('96a0edcb-8cfb-bf71-f567-b8544ca4c30f', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0029', '顾光耀',
   '19f30158-e523-37bf-4da0-f35d616a260d', '行政商务司机', '员工', '13761644658', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('04be6f9e-4bea-2069-07c0-d4c139ea7fbf', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0030', '王颂雅',
   '87060fb9-3604-3642-10c3-4c5afd473899', '证券文员', '员工', '18710220021', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7fa1b0ba-502b-140f-3a27-0782d0b3d9d1', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0040', '袁萌',
   'b927fc64-cea4-4af3-35cf-320e4c1569a9', '测试助理工程师', '员工', '19308820286', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6e0811e8-154f-36a6-bb45-628454783edf', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJNSX05', '周坤',
   '4590d641-6df4-e16c-6a9f-914172f099c7', '嵌入式助理工程师', '员工', '15861900356', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('44dde8e8-cded-c1a2-fd26-509b426d096b', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0034', '张佰亿',
   'b927fc64-cea4-4af3-35cf-320e4c1569a9', '测试助理工程师', '员工', '18198158169', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bc153355-eaed-4b33-ab73-5e19d15a6adf', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0032', '徐剑',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售工程师', '员工', '13585833024', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('00a9cd28-7bba-5b31-158e-f422417909e7', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0031', '徐赛杰',
   '8668845b-77df-2265-bce7-87faeee7b944', '硬件工程师', '员工', '17621683491', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3195e0d9-e6d7-e3c8-3207-66b3ce60c1e9', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0036', '卢鑫雨',
   'b927fc64-cea4-4af3-35cf-320e4c1569a9', '测试助理工程师', '员工', '19526066357', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0b26850d-acfd-f61f-d2a9-ed6720d4852c', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0035', '金洋',
   'f5a4690a-5b90-2d37-2eed-bc0020b5f471', '射频助理工程师', '员工', '15952399323', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4a8eb1a6-651c-5258-ad38-f27cd3724b4e', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0037', '张程志',
   'f5a4690a-5b90-2d37-2eed-bc0020b5f471', '射频助理工程师', '员工', '13023311405', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1d8332d4-2bb2-2b12-39b2-84aadf7ff72a', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0038', '丁元旭',
   'f5a4690a-5b90-2d37-2eed-bc0020b5f471', '射频工程师', '员工', '18761980141', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ee6d9a78-05cf-fc76-3e65-9069e117ce30', '3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', 'SZJN0039', '贾静',
   '0456fc3e-e7db-ed65-ecac-6f428630668e', '销售助理', '员工', '17339756424', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

-- 江苏神州半导体科技股份有限公司 (575 人)

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('cf60468b-d3b0-4e1e-b4a7-eb591aa808ca', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0000', '陈觉晓',
   '36b3c042-3317-4a9a-1590-31fd95bba73e', '董事长', '董事长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cd93436b-0f42-dbaf-c4f4-8b712c84c8e3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0001', '朱培文',
   'cfcc9650-87f7-01bc-8a46-9826545f1f6e', '总经理', '总经理', '13912124999', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c619a197-b043-67f1-341f-9fd5399a1c4c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZWX0003', '孙静',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '基建专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1504c1fa-3f07-d376-43d8-e35845f743f9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZWX0006', '财务审计',
   '607ce9f9-4cc1-13c6-b022-c44276d5a4ad', '出纳', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7457c29f-edd4-6937-59f1-72238f5847e6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0003', '熊都',
   '08121ea8-e7b7-7dd0-7e5e-8026ae4784f0', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('24eb580c-8139-0439-ae34-a5f3fbc5c4e4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0004', '丁书龙',
   '8f042b93-515c-6150-a47c-2c06ddce287c', '销售总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3ae0d684-aa4a-b2dc-97aa-dd1934ea66af', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0006', '朱国俊',
   '92c4e059-248a-bdcf-73c3-e22503cf2da0', '研发RPS总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('363f7cb7-4e14-a295-af36-74cfbc0ee65b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0007', '张梅菊',
   'd2750418-df89-6f56-b58b-bbc8f8fa4b10', '运营总监', '总监', '13626128188', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ea54f45e-704a-82c1-0cbd-03eb36455b39', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0008', '刘锐',
   'eab0b10a-501e-2edf-fa9f-9f6d17c649f1', '服务总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0a032d76-7b28-a52d-380d-081b711ccc2d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0009', '吴根银',
   'd194254c-56df-0908-188a-6e27576cffcd', '行政专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('10424fa6-8dc7-544c-d7df-48dcd27dc535', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0010', '卞骏',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ad836664-ef1c-2d85-cd57-74140caaf4ef', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0011', '吴纪权',
   '229540b2-5cd1-43e7-12b3-b6cbd5f14dec', '制造总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6488e6c7-76b0-003d-87d3-6db9eb2800b1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0012', '朱天成',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d6e1cf9a-90e6-bad8-712e-45f9744a2a4e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0013', '许晓云',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购部经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5d8bf9b7-3c37-4f30-6864-411b8ab76f1f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0014', '屠阳月',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('76d4a63f-eba1-f4e1-b8fb-bb828b2bdd8f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0015', '孙鹏',
   '5cca47c4-88c1-4edc-ea5c-8a5d299bb9f9', '工程二部经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dd140a94-c52b-7d54-d997-7f4cdff22f6f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0016', '李瑞平',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储部经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b03cdb78-359c-8811-b4d6-dd2ea4f57943', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0017', '居军',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('57d00f95-a4d5-c2be-0763-d97501b9fb54', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0018', '徐远波',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ab6a6a47-c328-1d76-2bc6-cd0f956cf8ce', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0020', '刘建华',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bbfa39ec-b910-aa77-5e78-24fdbb0561a3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0021', '张莉丽',
   '6e409529-dfce-856e-236c-8390e449e535', '采购会计', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2f8bafc8-d393-1ab7-c751-43fcb5b32007', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0022', '王雷',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5245f0e3-fb7d-cdbc-f6be-fe607e88a5d5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0023', '马磊',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d8376287-54cc-29e3-2da4-01674f6cf111', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0024', '尹巧星',
   '252e20e4-05a8-a94e-9708-eedd92b6d64e', '研发MATCH总监', '总监', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1d22d820-7ab8-2ac6-0744-2cb8a8e7d3da', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0025', '王揽涛',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('63c2d80e-ae35-6cc2-ed2f-3565aae60106', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0026', '唐勇',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4cdb1076-ff52-cb8d-87cd-51b9110f27dd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0027', '赵加佳',
   '252e20e4-05a8-a94e-9708-eedd92b6d64e', '技研部经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2637f0ed-8ab1-2ef8-cb3b-c926e1c84234', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0028', '葛琴',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', 'PMC部主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ce0311e0-0fab-6487-644a-412b1d153552', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0029', '唐正全',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6988665c-cffe-44bb-fffa-7f0d682204ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0030', '张理想',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务部主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e085e98f-1446-2129-9bf3-17fd5334f8bd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0031', '王鲲鹏',
   'b7b6cc72-ad9c-583f-5ebd-6fe14681eb4c', '研发DC经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2696cae0-0f2e-b37f-3fea-dd2e9d109302', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0032', '李明亮',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组副主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e1aaed4f-091b-7fac-4c78-bf53c65430ab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0033', '徐立',
   '1de78752-c845-a05f-7609-1edf8a7d4ceb', '质量经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3feb98d8-8883-ecdd-6fef-8b7898673833', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0034', '姚王雨',
   '6d0b0028-8754-555e-5695-7249e6999d09', '工程一部经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('19b1ee90-ee46-0d71-97bf-242da03a4614', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0035', '陈园园',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b4b22177-daaa-bb7a-6880-eb0fa0943dde', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0036', '颜倩',
   '9bdf65bb-c4a0-9310-02b6-affdd2cf633a', '人事组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('827b66fd-a03f-bb45-cafe-4417ca421457', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0037', '董登云',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a548db3b-88d0-2127-8356-e1ca08094786', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0038', '赵磊',
   'd1248e71-f3b2-cbc5-c070-79928f60ac06', '制造主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d5befbac-531e-1f70-3fb8-ef3130131301', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0039', '洪超',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', '18762779221', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('58c58024-f50e-ab52-9b8a-952a3aef439b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0040', '姚芳伟',
   '8320ece9-a81c-a710-8dd6-4de0b7c3b697', 'MATCH组副主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('85583f41-0bc4-c4af-bfd1-13d8362986ee', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0041', '杜建',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4d33a209-7230-e253-b35b-ed013979c6d1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0042', '梁贵禧',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e6f641ab-9051-5236-ad4d-561f80f72427', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0043', '许正伟',
   '1c90b073-d088-7acd-74a6-caa0c3f8420a', '电商助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('79c85a0a-81d7-23f6-2771-5db5ef197e85', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0044', '刘海洋',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务组长', '员工', '19962590667', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2b5af902-e6c2-75a0-3d69-5307770c4608', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0045', '冯雯',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('49e490ec-5d14-90e1-987d-d9f02d8a7a6b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0046', '解辉',
   '36058f33-a125-e6fc-6520-cd4820f07ccd', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1a8b6a10-70d7-3cd4-b72f-b6718e8e1140', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0047', '叶民权',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '项目助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1dab800a-79ac-fad5-a120-ab6f122c7f1f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0048', '张珍珍',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d2b28924-8b3f-8443-36c8-aeaa07d1e59a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0049', '闵陂',
   '1c90b073-d088-7acd-74a6-caa0c3f8420a', '贸易部经理', '员工', '18260691916', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('04d21088-6631-8e07-d94c-dd9dd08df2fd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0050', '张斌',
   '36058f33-a125-e6fc-6520-cd4820f07ccd', '硬件工程师', '员工', '19962590614', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('a09654f8-140e-a812-7692-4683fb2784bb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0051', '林莹',
   'd194254c-56df-0908-188a-6e27576cffcd', '行政专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f0ac9717-4164-6762-6667-4e21f5a133f3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0052', '刘本涛',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('60e0fc5b-bdb9-82c2-644f-aa0f043700d2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0053', '韩勇',
   '8320ece9-a81c-a710-8dd6-4de0b7c3b697', 'MATCH组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('101fdc6a-0a0a-def5-2d5b-01778d279850', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0054', '陈宇',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('45b4018e-6f20-25b4-f3e8-8fbccd2101b9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0055', '徐玉杰',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d3c6a9dd-b507-67cc-c25f-4bc147f8c17c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0056', '朱健',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', 'IT专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('52b77144-627e-8681-7d7f-47dc7607c482', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0057', '郭晨',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('98d57e7a-8489-b691-375e-c5d0a7156e1f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0058', '蒋峰',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组副主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('930e37c3-2383-a1ea-0ffc-9350f0d3e7dc', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0059', '李景芮',
   'f4da2f35-d7e5-7d6e-a30f-007a57e1a616', '技术支持经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e71deea3-30d7-bc3b-1ff5-b2b1abbcb4eb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0060', '黄磊',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组代主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('59dca93a-ed29-6e34-6410-d8679b91dfb1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0061', '王焕雯',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8c6b55a4-7026-3b09-5af1-f7c00f9530a5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0062', '陈国勇',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dcfa1255-17f8-306e-da17-53dff6022ea2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0063', '张冉',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d90e1967-b807-c097-909e-1512e0e99eff', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0064', '何昆',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('723ef72e-4b9d-9cf8-1725-bf52ee8b617e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0065', '王飞',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2a27a446-1ecf-7ef3-587e-00479d1865a8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0066', '戚银晟',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('99e773fa-a84b-c00f-cd65-784fe896b1d7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0067', '赵雪梅',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', '生产助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('af322f48-74e1-85be-43c7-3a5ba87c8714', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0068', '徐小俊',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ca8324b5-b4f2-70fe-51ac-6e106b3e93ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0069', '龚怀彪',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组技术助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f26b67fa-be86-8b09-519e-24f5452dcb1d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0070', '王超G',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('797ff3b1-c6a2-8f41-7017-d4b069e88e7b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0073', '朱涛',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组副组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a29edd37-e599-5958-68a1-6994ed8e31a6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0074', '路昊',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('374282d2-8d00-8f01-1a86-ab5f90a37d14', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0075', '管弦',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('17518b44-f6af-13dd-480f-5fdc8a1d7eb5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0076', '张金贵',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('764ccbd1-399c-93aa-683b-ebf4ac4fb52b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0077', '孔祥秋',
   'd1248e71-f3b2-cbc5-c070-79928f60ac06', '制造组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('04eaf4bf-f2e6-3c0e-7429-0ca4d185ccec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0078', '陈威',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组副组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f9d1a7e6-4912-cfff-c44e-d151d831f26f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0079', '李琳',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '19962590970', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7f5864be-1523-9e42-9246-fdcea6283396', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0080', '缪建',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('412b560f-c8a3-1b9b-a887-47e161e2a257', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0081', '陈超',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组副组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('62080ecd-d959-245b-fbcc-8d301314cf8e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0082', '陈丽',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('28dcb9e0-2684-4b6c-ae6e-d83c406d8f9d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0083', '舒勇',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eeeac7be-eab1-4ec4-35e4-8afe7fd10318', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0084', '张驰',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a3db7a60-3c69-2181-e45b-7fab41094762', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0085', '俞威',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('29535eaf-8703-d4dc-34af-4313da457674', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0086', '余玥含',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', '生产助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1e2e2e7e-6738-154c-b52c-43749863e667', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0087', '佘芸芸',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('191e4d70-93e3-b44d-0e5f-64264d6bb210', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0088', '吉丽',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7e0a1ee1-e0ea-b04d-64ad-25218bb13d97', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0089', '成华',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6bb06139-1d1e-9c14-d4e4-bc223bc4b558', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0090', '陈素梅',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8e75425d-c2f2-6dc4-8515-ef58037725ca', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0091', '殷爽',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ce49bcee-d539-44f2-33f2-fe54d5e88db6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0092', '宦玉标',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB部主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8d33e100-7baa-fc0c-17d8-f62f413b73e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0093', '刘传琴',
   '57638b6f-78f4-f4c4-5b4d-83fc847b7117', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('de03a8bc-f5c5-7c51-5852-3f16f49558a1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0095', '王雪冰',
   'b0107888-5576-a116-187a-57a883141f18', 'DCC文控', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3c3fda17-b38b-50d2-498f-33e945d68c2e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0096', '张婷婷',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9a3a3d35-250e-1904-0c51-7e60a5e63434', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0097', '龚子瑞',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('491a68da-5bd6-c098-a300-ce1f75c42977', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0098', '尹双',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2acd5e3c-d6df-7704-fb1c-fd366b8cd534', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0099', '赵伟',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('76dc071e-207a-a1a9-067b-7d95d843aba6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0100', '魏伟',
   '6e409529-dfce-856e-236c-8390e449e535', '销售会计', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b61001c1-27e5-cd17-dad4-b57b7fb457e4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0101', '居培培',
   '2e377986-558d-7db4-6d8e-0c2285cf5196', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e4437c8e-499e-054d-1401-2e70e2a6b0e3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0102', '赵铭',
   'a18be899-039f-0217-78e3-720e14c1ddec', '关务专员', '员工', '19962590979', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ec511c18-da3c-ae71-8967-54e0769cb6ec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0103', '湛金晨',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('cf19d668-6930-d0c5-5e70-1105d2c5d92f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0104', '石家琦',
   'ad8bcd2b-429e-c8ce-43ea-4452dcf8f02f', 'DC技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b4bd3651-fee9-e300-f99a-fcc3d7b0a285', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0105', '季佳男',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c84db495-a8da-1346-80a4-75f5b8014a0b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0106', '朱能军',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2130a5e4-4041-dc88-3378-f4bec987418a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0107', '张晶晶',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2829ceb9-4a47-8758-a0d2-89f24d1ec055', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0108', '张家骏',
   '5e240fef-491a-37f8-efbd-e51ebb82b45f', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1075ccc5-bd9f-e080-3366-668976ed48ff', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0109', '戚华烨',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1916eedd-07cf-7af6-d14e-c682ee0f2249', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0110', '刘云琴',
   '586432bb-19cd-f65e-a084-395e8744cdae', 'IQC检验员', '员工', '19962590602', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('aa45b2b7-9e0d-a0f3-09c6-176924cd8b41', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0111', '颜琴',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', 'PMC部技术助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('921a0d48-ed8c-00f7-53a5-5afa83dc6604', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0112', '胡永俊',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('12b8ca0f-06c2-0875-d3c0-88bba3a2f15d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0113', '李忠',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c125dbd5-92f9-234c-3253-c721f02d177e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0115', '戴振扬',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1f5cc633-c2db-b0b4-4f88-7dcd8aa18c51', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0116', '张娟',
   '6e409529-dfce-856e-236c-8390e449e535', '成本会计', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d002a47d-7246-5bb6-ad1c-942baede30bf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0118', '徐桂林',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a47b022e-8db1-3c33-9e21-77842d0285f4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0119', '周罗坚',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f1b8e003-28ca-acb5-51dc-898ea299cb28', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0120', '谢宜婷',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('162a0de4-cd28-4934-80dc-24e864e2664c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0121', '高攀',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b42d46be-1899-6913-1e3e-db53bdbfdee8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0122', '刘建军',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5975bd07-9174-becc-4f48-470129f42022', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0123', '曹阳',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组副组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b5819f3f-b528-fe8a-1bae-2e0f2bdc8466', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0124', '翁风雷',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('937eb23f-a89f-ae86-2f1f-b71621e0bd9c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0126', '王超A',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1733b868-0519-14e1-5832-c21891a2c517', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0128', '蒋伟程',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5edc593f-62e4-a642-43d4-06f665eae678', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0131', '周旋',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2acd169a-4ab9-fe7f-1d9e-ec2df38f995a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0132', '史敏华',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7a863a84-24dd-63ea-fa37-144bb64678cd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0133', '高赵勇',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cbdd7914-57ad-3588-5659-9f284db59630', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0134', '张明',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '17352483629', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('54add9de-028e-df35-fe8e-21179885ba4a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0135', '殷志诚',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '结构工程师', '员工', '19962590572', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4c7dfbdd-a813-3058-a63f-e5f21f5c31d8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0136', '谢洁晨',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('13bdf07c-0e56-986d-c854-e6afdb8586b8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0137', '陈樱宜',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('705a6cd1-f196-7807-524d-78fd64f210d8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0138', '杜超',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9de9a863-2257-a0ea-44ab-d5b90ec3927a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0139', '曾梦林',
   '8d5f3692-12d2-64b3-ecad-f40b8fbd232c', 'FQC检验员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f108f495-4804-a9c2-f626-1b70f470b401', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0140', '顾小军',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件设计组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f0a899d6-ee78-3268-01aa-0dc5ac176945', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0141', '聂世歆',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('078cc9af-fd50-bad4-1d10-b99d0bc099a2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0142', '冯晓芬',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1b8f0f29-ec33-1fd1-8354-1019dcfd3432', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0143', '潘小刚',
   '92c4e059-248a-bdcf-73c3-e22503cf2da0', '研发RPS经理', '经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('50c7193f-b429-ffd8-f95a-76f94467675f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0144', '陈禹昂',
   'af797e91-9e62-9d6e-dde1-c4c96807131e', '深圳办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('015071ff-6356-08e0-2d6c-28d5c8b92693', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0145', '夏平',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8e6ff4c4-baa1-aa78-05eb-1cf24a40c33f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0146', '卫娟',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9017bb5d-3309-038d-6fe4-079074116ba4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0147', '刘丹',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', '生产助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b477bb91-0c1e-0189-e089-d00c66ffb0f7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0148', '张文樑',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('94de4e3b-02c5-7e70-d044-857cc05782d3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0149', '张敏',
   '3b15ff3f-56f3-9766-ef1b-eccf22ae86d9', '生产助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('66f3e2b1-afe8-fef2-1aed-e8fa06a10d34', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0150', '张俊',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8e56fdc3-94ec-c10e-f593-99f07c668bab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0151', '黄旭',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('15bb89e3-ad2d-7b13-37f0-2e6b67011a1d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0152', '周萍',
   '7cb952b0-60c3-b6af-9c12-94f95878f74a', 'FQC检验员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8a7949b8-65a3-2248-5809-cba8088b34e8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0153', '丁建成',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2722ef8f-94b9-e57b-cd86-89a9759ebf43', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0154', '金亮',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4bf9b146-023a-5e22-c35e-4c3a07447135', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0156', '徐东亚',
   '252e20e4-05a8-a94e-9708-eedd92b6d64e', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a18a7c5b-8410-3c74-0467-0a4b36d18d30', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0159', '魏九佳',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组技术助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('19e5fc27-d001-74ca-5318-d085ec5fd328', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0161', '徐飞',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('87c0e9aa-9af3-c71a-1974-d304d6d0db29', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0162', '项羽',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2040adc5-c9c5-5b03-b506-6bbcbeb73193', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0163', '李政',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('c6741054-cf74-8b41-c546-509291826f30', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0164', '姬凯',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('24d3d986-07bd-7f44-823c-ec3d914c8e38', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0165', '袁龙虎',
   'c3679ea0-41e0-02e3-dfd9-f28a62ddd982', '武汉办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('18ab8f8d-304b-3ff5-f353-a8994a23ebac', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0166', '程康',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件设计组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b45e32b4-350c-6ba7-3d54-04960f284825', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0168', '陈政',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('713b6610-cb24-4870-1ab3-9eae203da38e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0171', '周祥',
   'd20bda8b-3529-2af6-ba01-998bc3ba1243', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2dbeac13-be59-2def-293b-6ae50bc677ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0173', '谢天泓',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'FPGA算法工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('76968f6d-3604-841b-7703-9e18b16dc65b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0174', '胡刚',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术组长', '组长', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('79b5629f-4c41-7fbb-d216-be65ef0ea448', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0175', '姚世文',
   'a4f3f7f2-b3e3-87a7-39fc-fd9a7409bc79', '合肥办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2a42b6ca-f1a0-d492-6692-88fc97ddfb10', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0178', '李新元',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1b3cbba7-dd28-aed4-ba2d-d089bf7462d2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0180', '顾慧娟',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('773ec35f-3354-f8e7-870c-3a63c2d39a6c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0181', '梁兴宇',
   'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '售后专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('98be12fc-4372-229c-7f78-95d562d85a0b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0183', '袁礼',
   '1c90b073-d088-7acd-74a6-caa0c3f8420a', '贸易部经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e8c971a1-f238-14f8-4e78-cdd8fb5d39e6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0184', '时梦雨',
   '6e409529-dfce-856e-236c-8390e449e535', '出纳', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('00c4439c-7d59-1831-7b7d-793edc84fe17', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0185', '梅福禹',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d139eecd-cc8e-d33f-c19b-18448357c606', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0186', '薛平',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2bf5a94d-ae62-d3b3-9b62-9e07b6ef6b0c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0187', '仝威',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5e903f77-ee67-f33f-eef3-b3643cf46e49', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0188', '徐磊',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ef4ba550-0523-5b55-0b41-276ae983b574', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0189', '褚泽旋',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2030e97c-c72a-c0ea-7032-51bdb7add083', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0191', '徐秀云',
   '607ce9f9-4cc1-13c6-b022-c44276d5a4ad', '副总经理', '副总经理', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a8923767-1a10-ba0a-50b0-131845f667b0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0192', '陈志立',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eadf17e2-ec14-8058-6438-06ec79b0f1d0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0193', '杨静',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eb675782-ab0f-4e20-9067-1624c0e87f3a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0194', '俞健',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0ff80a5d-4bc3-bec8-3367-2ee3b5292866', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0196', '宋成',
   'd20bda8b-3529-2af6-ba01-998bc3ba1243', '测试组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f6f4510b-0271-2c03-09bb-f2a163799ea1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0197', '林郁',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('19c29f0e-dd39-1839-5c53-d1138712f92a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0198', '窦振华',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e19e7cc8-ce7d-7b71-c814-ed6e1bdf2607', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0199', '于倩',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bd20ccaa-5c8d-cb70-b6e1-08ae89855102', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0200', '成秀芝',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('84f50a03-970a-e88d-aaed-d522cb8fa7c1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0202', '张宇',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f3d8a8b5-4944-3fde-4472-32a3a3581b02', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0203', '潜世军',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '结构组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2bb56591-790a-b49b-5278-f47c912f264f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0205', '刘玉',
   'd2750418-df89-6f56-b58b-bbc8f8fa4b10', '运营助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('01c9a876-b646-0b86-fb63-f2fbf387f62a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0207', '王小龙',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dd7e863b-70b9-c941-2d3b-77b3e5667efb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0209', '束寅志',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f6bdb3fc-ba1b-4cdf-3a39-2b4560fe80a8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0210', '周峰',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('303190da-f212-c5cd-8fb7-643319966f4f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0211', '吴俊',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '技改组副主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c1d60b2b-55fa-42ee-4ca2-219f6b91a68a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0212', '李康健',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d39482f2-e605-3d28-8a20-bba071746eef', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0214', '胡成',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('287145ae-685c-b366-7a5e-c1e19ba45ff0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0215', '江为骏',
   '36058f33-a125-e6fc-6520-cd4820f07ccd', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eaa829b3-f1a0-f703-3f80-ab7e004a2aae', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0216', '王文睿',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '13270001652', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('776f887c-e821-a627-7975-38c55e993740', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0220', '王基隐',
   'd20bda8b-3529-2af6-ba01-998bc3ba1243', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('731e8acb-0e20-8adb-fb6a-a9e958a422a4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0221', '潘智杰',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1f59e7c7-b86d-ae4c-7bf2-443688d6997e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0222', '李荣平',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'ARM算法工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2522caa5-a9dc-b4df-e788-6f3fe2e5dd96', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0223', '刘晨旭',
   '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', '嵌入式软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('718f785d-69ff-6c84-e5e2-13b5ddeb30e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0224', '董卉',
   '57638b6f-78f4-f4c4-5b4d-83fc847b7117', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('be4a6fc8-27b6-9bfc-2a3b-41aa7b018a41', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0225', '郭松',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件设计组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7b8d7032-f6d3-bbcc-3622-541e294bc875', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0226', '周健',
   'b3d90cf8-4410-1ee2-d4e4-706c1ac1d539', '结构组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4ca39421-0e84-50eb-f3b6-5982bdc806c0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0227', '周国亮',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5094a93a-d860-d046-a7b1-811c7d90d6a5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0228', '田健云',
   '6e9c0cc5-d84d-c361-a714-e4108f14373c', '工艺工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('44c83a16-3900-5383-f769-89225f8598d1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0229', '陈马林',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', '软件设计组组长', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1fa3524d-4c06-f084-cc4a-1f8e86d51d9d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0230', '王乐杰',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('937c8cbd-08da-8a20-5cf8-b42c36c78662', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0231', '陈保林',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '13720232806', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('40f2de10-fc50-dd6a-ce41-c621e5ed548a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0232', '李剑',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', 'IT专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8670bdae-62e9-e330-6d4d-7b461c85ead6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0233', '周宇',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('889a18e7-3ebb-6e5e-c20b-d8c25470cdde', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0236', '常旭',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('409d7840-afce-927f-8c4d-bf12902921bc', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0238', '汤国凯',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e0997f80-b011-1031-7686-54eaeda437da', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0239', '仇健平',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ea650d60-0f05-d189-84e8-a679a759c4f2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0242', '王杰',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9d72afc9-4921-e5bf-7322-8b525930f316', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0246', '汪新宇',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('82eb4582-beba-b629-8074-f74e47b1aeae', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0247', '田慧',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('911b1cd9-5004-0ff5-224d-ee19d995ea8c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0250', '丁文荣',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('175799bf-ced2-8819-c518-5834c3b65096', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0252', '方开鹏',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2a27ca7a-5216-2c20-d46c-3369afa06862', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0253', '许滔',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9581fee0-8624-827c-58ac-29b7958b58d1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0254', '袁冬野',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5f0c19e8-a933-525c-e47d-c63f3cbaba35', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0256', '周强',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6f14cd41-1f97-e900-cd5d-5b416eac97a9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0257', '魏超',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4b504003-5c7a-92e4-c000-2f56f320b0c0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0258', '耿涛',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8c6300a0-7ec0-5d65-8509-e17fe504f976', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0259', '彭一鹏',
   '419bbf47-8da2-83b4-8efe-a9023fe14df2', '硬件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3690fec9-6030-683e-c67d-20365180f119', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0260', '韩向西',
   'b3d90cf8-4410-1ee2-d4e4-706c1ac1d539', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6a1883bb-494a-8a50-fdc3-ab7425e4bace', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0261', '程素杰',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '射频功放工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d77f9c79-6af7-8b2b-6388-43e92f6776ac', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0263', '李谭',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '电磁仿真工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9abe4851-6bf9-2a4f-d6a1-7938b92a0c99', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0264', '俞世祥',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '项目经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2d8dc9ae-eb73-98dc-ce86-9f14643e3319', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0265', '许存',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0871c7f4-0c7d-89bd-d02a-8ffa3bd3d04a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0266', '田梦贤',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ebf189ac-0690-46ec-5390-1856387c91fb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'seeyon', 'seeyon',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', '致远顾问', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('70e30975-4410-3e46-eeff-36ce5827f344', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'AUTO_0229', 'seeyon1',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', '致远顾问', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b23b1388-ce57-967d-c770-685f690615e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'AUTO_0230', 'seeyon2',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', '致远顾问', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('296d4549-8be5-6359-e2d9-ac25a8c9765c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'AUTO_0231', 'rest',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', '致远顾问', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7d1d204b-0f97-3a4c-f005-137f2f5874b3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0267', '李越',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', '13776426335', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f8596ed6-8d5c-8c85-519b-0fd2927bb5bf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0271', '胡巧银',
   '65ca6a1e-ed4f-0f1a-24ba-f015dd9fc68d', 'PMC部经理', '经理', '15150815805', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6318560d-089b-8072-a7b4-f4fee6a6a26d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0276', '赵建波',
   'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '项目管理专员', '员工', '19975011670', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dd48271f-fb5d-1087-bf09-21bb31cd14ed', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0269', '张杰',
   '243122f3-450f-3406-c7fe-cbbb2df4f99c', '软件工程师', '员工', '15705274295', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f0bc0b84-2289-f5fb-13a8-d985b18a1397', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0278', '周雨琪',
   'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '销售助理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f7ff92ad-d27e-3bc2-ce4b-71e3b6376062', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0279', '葛静',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('02913e51-4037-a0a9-941a-8c875022fe40', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0280', '刘楚涛',
   '0ccddfef-18a9-960d-4c3e-862d35c8895f', '项目助理', '员工', '15358860421', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1fbd70eb-bda8-f24c-7217-95d832fb2981', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0281', '徐祥和',
   'b3d90cf8-4410-1ee2-d4e4-706c1ac1d539', '结构工程师', '员工', '17826268658', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('786e48c7-5632-e37d-f4bd-d534b9376f49', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0282', '肖田辉',
   'a3c53269-90cf-116e-2f95-3262b80f1ad8', 'PQC检验员', '员工', '17680499436', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('abeec13f-f3af-8290-edd7-040dc57a71f6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0283', '郭远飞',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '18912132310', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2194db8b-2ca5-2973-daf7-d3d647a2ad73', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0284', '陆玉蕾',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '15358853736', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('40718d56-99ff-bc18-84f8-bf100407d6b1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0286', '申奥',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'FPGA软件开发工程师', '员工', '18435506316', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2fec9f2f-6186-16f2-1836-e4433db6735e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0335', '彭伟',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试组长', '组长', '18652507059', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b7d9a648-48c4-cbc0-8011-29a4f9402cb4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0289', '赵艺娴',
   '6e409529-dfce-856e-236c-8390e449e535', '税务会计', '员工', '18706136921', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8dd0e48f-5beb-64c9-79bf-3a8f90d60ac1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0291', '李扬',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '嵌入式软件工程师', '员工', '17365373239', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('809b46fe-d744-221e-59f0-8c533b88b2cd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0364', '赵立波',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '装配组组长', '组长', '15101977757', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('064df4a1-09d2-8b89-2485-5ed8f7504b4d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0292', '冯龙',
   '6cdbfced-5ca9-413f-a231-b6386321b980', 'PQE工程师', '员工', '18952594132', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('20846405-b0c1-2cdb-95e5-29d6304625df', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0293', '纪建芳',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '19975026618', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b6f40aa0-ef97-6816-5e2a-98305728f790', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0295', '徐月芬',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', '13160314520', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a849c5fc-5c56-ea56-c050-2f86770f141e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0296', '徐晓玲',
   '7bb1b23e-460f-bf22-5756-d7cab5a580b6', '物料员', '员工', '15995129029', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a125c5a9-8cb5-dbeb-efaf-e6ddd1808884', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0297', '茆永存',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '18352716661', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4de9f21d-061f-29d4-1727-6cfe587f0b23', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0300', '卞玮毅',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '15952701796', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2264f833-fbdb-968a-4875-ba7ab0db8fe7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0301', '陈楼',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '18921907752', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('40f463b4-1993-036c-870b-1605530e2957', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0303', '张海兰',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '13338846830', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('34972771-9a7c-d55a-edf8-37bc8bcf4459', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0302', '姜长波',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '15940898007', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('294d8ff0-78f5-65b7-70b3-a5a65679d64f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0305', '李思源',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '15050770260', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('94c91b48-0421-8aeb-188d-78d61ed0ef34', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0306', '朱美玲',
   '65ca6a1e-ed4f-0f1a-24ba-f015dd9fc68d', 'PMC工程师', '员工', '15861342841', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4e75a373-fc6c-19fd-5d1e-ce2a49c30f70', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0309', '张志扬',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13773458086', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e2be3944-e77e-a293-0cf2-148ab8755803', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0310', '姚志淼',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', '18051064037', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1d1f5481-d979-3bbe-e154-f00386875898', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0359', '崔苏扬',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', '15952574543', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d9b98dab-7045-fbb1-b5c5-87c73dbc94c7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0311', '刘娟',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', '15052510358', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dd6730d8-6eac-e9d1-9858-3f6e2de48d7f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0314', '伏恒柯',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '18817569218', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4e467eff-5601-ec71-d70d-a158f1023f7b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0313', '李雪莲',
   '92c4e059-248a-bdcf-73c3-e22503cf2da0', '研发助理', '员工', '15852875089', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c581afe3-7c77-3a9c-b68d-b31b38399364', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0312', '陈亦善',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', '18762307048', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c4323102-c170-265e-6e52-81cfcfc6cb52', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0360', '朱兴意',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '18066087853', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8cebecad-d78d-866c-d97e-b8f594afa0db', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0319', '赵航',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '15062803206', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6211db45-12ad-acad-7086-4a7d7b17aca2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0318', '陈悦',
   '9bdf65bb-c4a0-9310-02b6-affdd2cf633a', '人事专员', '员工', '18652527598', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d3709c2b-82cc-471d-8cf8-c8599b2a3108', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0316', '房福兵',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '13115178386', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('673c9192-90b2-a2e6-916c-7efb1d4a8144', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0320', '居宵宵',
   'a113e144-5120-9ef2-1912-207fde631279', '软件工程师', '员工', '19962506430', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9ce49e6b-dbbf-426b-9e0d-f9bd6b18be77', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0322', '徐洁',
   'ea81a5c9-a571-2f1d-45ba-110a51160d71', '制造工程师', '员工', '15161447682', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('eae6cdcb-6227-fc42-5726-bc51061d7d57', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0371', '刘梦伟',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17733389746', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('32f87edd-b0e0-4ee7-484d-68606cc0fa1c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0326', '徐凯',
   '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', '嵌入式软件工程师', '员工', '15651863089', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a961af10-f1cf-30a5-bc0b-5aef231680bd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0329', '陈思立',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'DSP算法工程师', '员工', '17718139675', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1f192cf6-e425-2858-534e-571cad8f3527', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0331', '唐宇',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '17712350352', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a5f974f8-20d0-22c1-087a-9bbcabf6f433', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0332', '徐来相',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '13151127646', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bde709bc-bbfd-4ff8-c8e7-2f3d07e6a0a2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0334', '王伟',
   'a3c53269-90cf-116e-2f95-3262b80f1ad8', 'PQC检验员', '员工', '18952733098', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0dc63b0d-e371-7b15-9282-8c5db192a5ec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0333', '杜雪飞',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '13511740850', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8cf55c35-3f60-bf4a-583c-7bc42fc97dd2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0337', '缪文光',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '项目经理', '员工', '13305277272', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4ab4df59-318d-0326-2f91-4a4fcdc617d7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0336', '朱俊',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '基建部总监', '总监', '13905273904', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('84e1175e-4fd3-6ec0-cde2-83b1be1d97f2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0339', '孙志成',
   '66e53ea3-40ac-c4c9-671c-e7d3e8e417e0', '工艺工程师', '员工', '18086769788', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('41b5d743-72ed-5f52-843a-2fc50fb6f973', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0338', '田明亮',
   'b3d90cf8-4410-1ee2-d4e4-706c1ac1d539', '结构工程师', '员工', '17715847543', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a43d3058-df34-3e72-3c0a-bfe9651a7a51', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0377', '王誉谆',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '17798961139', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e588a94c-9738-37e4-ef5c-ef84947752c9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0340', '杨静R',
   'd5a97fc7-142e-4a54-21f9-9cae982538be', '项目经理', '员工', '18952750937', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('961e8e0d-8df1-8e0e-44f8-45128d95edb5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0341', '徐春琴',
   'd9b90066-8752-14f8-2f06-61d9b016acaa', 'SE工程师', '员工', '18061172848', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b8cab28e-6f7f-d312-ce4d-f5633bcdbe7a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0343', '胡骎',
   '97a443ef-4d4e-3943-7aaa-12e180c47490', '研发助理', '员工', '13056352881', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('62c3a8dc-9d2e-e470-5548-1b74ecb4b6ed', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0344', '沈阳',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', '18556211343', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('014e38c1-ef2e-7ffe-95d4-30dc3965b145', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0342', '韩徐州',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB工程师', '员工', '13819142816', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8d3b0886-ebc6-38f7-8d10-aa39b64f4e23', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0346', '刘旭东',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18652593992', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5a67d6fb-98af-a56e-95c4-82985abf30a1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0348', '杨博涵',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '13304755454', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e291af9b-df06-c22f-8e9d-4ca2c704e5ec', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0350', '陆健',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e240f053-a8dd-6236-09d3-85f18686f829', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0351', '陆相岑',
   '53420bc3-6d35-7443-ac5d-37d6281f1913', '结构工程师', '员工', '15262518792', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4020602e-9b45-99dd-094d-2b2cc44141be', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0353', '王艳',
   '229540b2-5cd1-43e7-12b3-b6cbd5f14dec', '生产助理', '员工', '13004326655', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4da2f6a2-c356-5fdc-47e8-51fdcf310e4f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0354', '孔书文',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '18361313967', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0119c48d-4672-1dec-044e-a3c6afc47478', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0349', '李立',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15671166454', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fc57ecbe-2c08-47c4-03d7-0c18d9ff4804', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0497', '陈可可',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '硬件工程师', '员工', '17368452095', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c31c7d33-3cb3-ce12-52c0-e4bdd84afa1a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0496', '杨汉鑫',
   '36058f33-a125-e6fc-6520-cd4820f07ccd', '测试工程师', '员工', '17851930906', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('516427ef-f112-6938-a688-c0f146d54606', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0495', '张梦达',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', '17766406523', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b9587edc-a42c-c0e4-2675-3e4617f55ff6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0494', '蒲发鑫',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17368450717', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a1928512-0abc-cb55-04c4-4764ed709e21', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0493', '董令',
   '66e53ea3-40ac-c4c9-671c-e7d3e8e417e0', '工艺工程师', '员工', '13408797682', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('95a10367-aa3b-028e-0401-b10083236d04', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0368', '谭钏',
   '06ca0f02-5d21-2453-d64e-978f0042eaf1', '制造主管', '主管', '15252517561', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7b313472-a2d9-9b89-efe0-acf040e34ad9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0369', '李鑫',
   'a3c53269-90cf-116e-2f95-3262b80f1ad8', 'PQC检验员', '员工', '15161873901', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f48342c1-8bc7-ed67-5d41-277a18411387', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0376', '殷琚杰',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '机电工程师', '员工', '15252570648', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0f995a93-0ef7-2530-50c3-cd522b68b183', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0375', '赵溯',
   '021b7fb0-4b1d-4a2b-d4cf-9637d4e7bb87', '硬件工程师', '员工', '18351980620', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('55d363e9-6a7e-1c49-faa4-5183b4df5e9f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0380', '嵇超',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '18936480782', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('0178db4f-19e7-4ce8-b844-6cbc0529f432', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0381', '徐丽丽',
   '9bdf65bb-c4a0-9310-02b6-affdd2cf633a', '招聘专员', '员工', '15195554933', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('229a41cf-339f-50cc-cb62-d01609f8f1fe', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0383', '马权',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '15952692117', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a81c5baf-daa6-e808-d6d4-4fe7915ff7c2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0386', '刘婷',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '15952716926', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('110d4a03-b882-99d5-9c2e-f901e5e9d942', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0388', '王杰S',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '13478769989', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ef08c015-22f9-81fd-d1da-f49a0aed2f6f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0392', '吴芸',
   '9bdf65bb-c4a0-9310-02b6-affdd2cf633a', '招聘专员', '员工', '18662369790', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('22fd8ddd-9fdd-4724-dc83-260b4bb73d39', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0393', '陈昊',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '13645273148', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2160a047-7e78-5eb5-7e2c-4de306ef8570', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0394', '朱宝晨',
   '5e240fef-491a-37f8-efbd-e51ebb82b45f', '测试工程师', '员工', '13952547353', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dbb8a0dd-1689-e6db-a469-52fefeb85722', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0395', '黄晨煜',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件工程师', '员工', '19825302995', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('370574ee-05f0-7107-daba-7b731d127d95', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0396', '张瑜',
   '252e20e4-05a8-a94e-9708-eedd92b6d64e', '研发助理', '员工', '18652572860', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('24ccc233-997c-22fe-db2d-394fabba8c28', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0397', '孙晨',
   '021b7fb0-4b1d-4a2b-d4cf-9637d4e7bb87', 'PMC工程师', '员工', '13665275672', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4ef261e6-c357-25c9-623e-3177b5075aa6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0401', '赵伟R',
   '021b7fb0-4b1d-4a2b-d4cf-9637d4e7bb87', '硬件工程师', '员工', '15811317608', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('53afb566-6c94-fa4b-eb07-776fc06b910f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0398', '金玉亮',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '材料工程师', '员工', '17625852076', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fc680dab-fc02-73c3-6fc8-48e65501956c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0400', '贾昕阳',
   '021b7fb0-4b1d-4a2b-d4cf-9637d4e7bb87', '硬件助理工程师', '员工', '18306175377', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c94fbea9-890c-c36b-839f-49f68dcf67ce', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0402', '刘亚鹏',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17762696864', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a39fcc5c-71b1-f918-64b0-f21de8aec590', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0399', '张晨',
   '53420bc3-6d35-7443-ac5d-37d6281f1913', '结构工程师', '员工', '15262244862', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3f7f9d4b-66f7-fea9-4181-321750c45b72', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0404', '王鑫',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '13057300231', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('03a30b35-f344-c394-093e-cf700c93d9a8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0405', '胡宇',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15962986029', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c8563fbc-793f-6b52-bf2f-4533a2accd16', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0406', '邵泽祥',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '18252755568', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4afbdc63-47c3-0ff8-ff3b-720c834a8c85', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0502', '颜聪',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '13092033138', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dc9f34e5-1914-fb36-5ec3-234e5b410eb1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0412', '周忍',
   '6e409529-dfce-856e-236c-8390e449e535', '会计核算部经理', '经理', '13588718422', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3947d545-3c5e-30e5-cbe7-6a40a3d18771', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0408', '陈俊',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '软件工程师', '员工', '13813396998', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e52f246b-8758-ea1d-ca03-6ab85da967c7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0410', '顾昊',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '15371304960', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('94f8faa3-810b-e612-6abf-c635c27df170', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0413', '张文思',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购主管', '主管', '18905257373', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('04288125-97e0-34bd-3bb7-1baba1e5d975', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0414', '李春江',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '13252980781', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fb0fe2b9-3045-1e53-f6f8-a5830961d812', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0415', '李雪峰',
   '58c67682-b975-08fc-7a6f-951c8e98ea9a', '射频工程师', '员工', '18852575138', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d0ddafa3-1bd2-90e1-1781-9ecca75a6553', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0420', '魏立',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '19975029236', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('19d17e8e-4c3d-2da0-a4b5-d3ebef2499ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0419', '朱泽成',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '软件工程师', '员工', '18862223911', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9582295c-89f6-7594-1607-549a5a843926', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0421', '邓学城',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', '14778583840', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6d60c09c-7861-c98b-7929-277f9dc3f480', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0422', '李发成',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18986122568', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8bda0c84-8810-fd70-447f-5d657b445895', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0423', '王翰林',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18855086679', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5ab06c1a-f42b-67c5-2f65-a63abb3d7429', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0427', '唐晨',
   '9725fda0-6734-512f-ff0e-83d1ab112cb6', '项目管理工程师', '员工', '18352752880', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c7dce0d7-032d-6783-854a-5659b3bb95cd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0425', '李紫晶',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', '15150838201', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ba9eb078-cc93-c54f-d64a-44cb6c678f15', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0424', '王松',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '13998527350', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a8f8e193-45cf-4fba-d9cb-a5ad277a1f04', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0431', '王凯祥',
   '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', '嵌入式软件工程师', '员工', '18852543211', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ba26a8bf-9373-3609-81db-728f81e2b89d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0498', '葛倩荣',
   'a3c53269-90cf-116e-2f95-3262b80f1ad8', 'PQC检验员', '员工', '19551762314', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d3441703-1fac-4031-3bb2-1775234d5911', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0492', '李子寒',
   '1c90b073-d088-7acd-74a6-caa0c3f8420a', '电商专员', '员工', '13813174850', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5a1e97bd-fe4c-3866-3011-47dff253c577', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0434', '田磊',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', '17766333420', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9e037779-0303-ed31-a4b3-dbde21f9e4f0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0435', '许鹏',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '17305145727', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d9b264cd-6ce0-1bb4-a92a-bb9a25a6e9a4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0433', '孙丹',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '15896416771', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('13291220-6d93-cbd9-352c-17ae74103abc', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0437', '刘长杰',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', '15861376278', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('03a2cb9b-5b25-0b22-37b4-84a8aec31b3c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0439', '张东杰',
   '6cdbfced-5ca9-413f-a231-b6386321b980', 'QE工程师', '员工', '18136602691', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('beaa7c96-f932-d54b-1d17-a5413d819f37', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0442', '张静',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', '18856277228', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7e97a1b4-fc48-241f-85ac-bae29be66e4d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0444', '衡扬',
   '56a63a66-96d5-fa1f-cb0a-b9afb3506822', '厂务工程师', '员工', '17558752124', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1c20442f-fae0-7157-ad24-084265088fe5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0443', '尚仁超',
   '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', 'DSP软件开发工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ee7378f8-ea83-fb93-2df3-aec40e74b806', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0445', '霍岩',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '15842650016', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9ef117df-ed62-1acb-bf3d-13274f9e2956', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0446', '刘伟',
   '9725fda0-6734-512f-ff0e-83d1ab112cb6', 'BOM工程师', '员工', '13805270407', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4c7beb6c-75a1-c9b9-45f7-f1701e58e859', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0447', '韦庆长',
   'af797e91-9e62-9d6e-dde1-c4c96807131e', '深圳办事处工程师', '员工', '18376755101', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('98871300-84ff-e313-3b72-b383b402973a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0450', '尹华凌',
   '4953930c-5a0a-2bea-308e-a4953fb9d821', '总经理助理', '员工', '18679687030', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9a90e324-b4cb-673e-e406-5d22fd46e4e1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0451', '楚涛',
   '58c67682-b975-08fc-7a6f-951c8e98ea9a', '总体工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('aa7249df-05e7-9bc4-877c-02db3f4fb092', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0454', '赵越',
   'a18be899-039f-0217-78e3-720e14c1ddec', '关务专员', '员工', '18895612659', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('3da06de3-9543-3769-e492-84d0c201d0d2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0455', '王亚群',
   '53420bc3-6d35-7443-ac5d-37d6281f1913', '结构工程师', '员工', '17326058911', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('058ede5c-8037-c433-2eaa-3a72b162fb71', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0457', '陆庆阳',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '15150844460', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('82f8ee88-28de-793a-21e9-99eaf36572dc', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0456', '唐家轩',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '射频工程师', '员工', '18855037650', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a712667b-aefb-e2e6-b01a-9c2914df7ab6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0458', '彭嘉垟',
   'a51e3d81-a18c-7706-f477-4e7f17895bca', '物流专员', '员工', '19352711391', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3b2a1303-c9d2-c9d5-d8cc-545ca26bc9a8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0459', '桑宗铖',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '13815808623', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('5c1012ec-a6b2-06f1-943f-cca909cf6fef', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0462', '王亮',
   'debadcb0-5b8c-f39e-15ee-4a7cf2c73fff', '工艺工程师', '员工', '15050746452', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c6ea2245-34af-106e-67e5-f5c15e550fe4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0464', '朱加俊',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18672296073', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('59622ba0-3569-575d-ab29-7b4af939814d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0463', '李尹',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', 'PCB Layout绘图员', '员工', '19816367098', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('085db60a-c02e-0afc-9fd8-c76f92c51f1c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0525', '张舒涵',
   '5e240fef-491a-37f8-efbd-e51ebb82b45f', '测试工程师', '员工', '15861366766', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('17707298-8410-09c9-a1d9-caf7520a0b9b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0468', '张宇钦',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13952781156', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('214cd34d-3b17-f577-1370-1caa0ffeb088', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0470', '莫哲',
   '2e377986-558d-7db4-6d8e-0c2285cf5196', '结构工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f6a4934a-92b8-442a-5bfc-ebb40f92f559', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0471', '高冠宁',
   '78b6e4db-9cb2-66e4-0fb0-e4f603282ae2', 'FPGA软件开发工程师', '员工', '13260721567', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3fffdb95-ba13-f0bb-ef0e-6e2e33a9131a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0474', '徐佳伟',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', '13921902021', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c30061a5-2c32-e45f-573d-5f31b1647cdf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0475', '胡军',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '18037504563', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0e039f40-a4db-a44e-c21e-e1acb13b9e26', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0476', '朱意',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '15071672748', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('09d34132-bf29-770f-8241-e410dd5b54ab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0477', '季君',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', '13813192380', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9de61b08-94c7-b54c-7ce5-426f0a3c0127', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0478', '苑磊',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '区域经理', '经理', '13261090537', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0713f9e6-84d3-fad0-0550-96aaef0d3e11', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0480', '杨国庆',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', '15951049694', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ece26a26-c55f-7760-e09d-46182dc14e08', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0479', '朱建勋',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '19952451925', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7f236cbd-fd6c-2385-6870-bb29273d14c3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0482', '吴世豪',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', '18652760273', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('901a4911-e683-88c5-f49d-acf8fc217baf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0484', '强素霞',
   '7bb1b23e-460f-bf22-5756-d7cab5a580b6', '物料员', '员工', '18952742735', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('834b4e5a-a6f7-83b1-dd4b-603cd5b55969', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0483', '张明宇',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '18662319786', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1725d6f9-50c1-5f23-6064-41204deb18e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0485', '吴炜',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '18118254137', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8f5bd3fe-3133-1056-77be-9ce785a8c1e8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0491', '方鹏',
   '1731601e-ab3e-3858-c335-b036ddc426b5', 'Python工程师', '员工', '18861646102', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6e78de76-af0c-5d27-a3c7-d46db2a1757c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0490', '孙钰圳',
   'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '售后专员', '员工', '15641302656', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d4b38f6e-3fe7-a004-7a4e-b4761045e34b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0488', '刘益民',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '硬件工程师', '员工', '15252504078', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('60176245-5a44-9227-bc45-a2c909a91b23', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0489', '朱宏宇',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '18094200972', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('303d812d-549d-c1a6-7cfd-c7279a4dcbc7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0487', '赵俊杰',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '嵌入式软件工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a4348b54-797c-fef8-90c8-264de9915eae', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0501', '艾兵洁',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '13317238981', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('58683c05-0064-f063-c5d4-a38be2d5cf7f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0499', '张文响',
   '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '测试工程师', '员工', '18066075932', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2c5a2bfc-5a64-24bf-279c-7252867c1123', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0504', '朱淇成',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '15380340418', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c4d37f5a-9e66-256b-856f-6967391cbf51', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0506', '盛彬',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '18951445358', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('68ba6719-e3e5-6d33-facb-e6eb6693c290', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0505', '沈海建',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18622026957', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('644e2a94-0333-ddbd-f9bb-24ff364d8e5a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0513', '舒川江',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17389357417', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c739169d-48e9-bd17-7f38-4f90dbbe8165', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0515', '刘康',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '13338869101', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cff6f295-f5fb-cbfb-5186-c89f91aa7d75', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0516', '王荣晨',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', '13665266551', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6aa1d83f-1a76-cd74-367c-a064cc509032', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0517', '向佳文',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17671065652', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('09139203-cee2-2983-8cb0-39cf01a6f45c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0520', '王赵程',
   '6e9c0cc5-d84d-c361-a714-e4108f14373c', '工艺工程师', '员工', '13125741576', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b62a0712-ad3d-bee4-f4b1-9e74806af05e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0518', '高洙莹',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '文档质量工程师', '员工', '19906136052', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2bd17c32-a0de-858f-c6f0-fdd9f2274d18', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0519', '梁峰',
   'ad8bcd2b-429e-c8ce-43ea-4452dcf8f02f', 'DC技术工程师', '员工', '15697823035', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('03eac285-79e6-9e09-4731-1ce2f4c963e8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0508', '郭一兵',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '射频功放工程师', '员工', '13509618201', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3d7914f4-5f3b-2d92-92a5-a290de875bb0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0509', '郭清玉',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '射频功放工程师', '员工', '17346105443', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e94c7775-d4bc-51ee-c5d7-8eeaeac9712c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0512', '周彦沛',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'FPGA算法工程师', '员工', '13540838830', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('272cfb71-febc-b915-2cc9-ff180a620abe', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0511', '周文武',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', 'FPGA算法工程师', '员工', '17381594720', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('82f707d4-22be-bd63-454b-7423b98bb28c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0522', '张小鹏',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', '13261446743', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('626d020a-d762-802c-ba89-055e2b3a5772', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0521', '武海波',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', '13269390861', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0bde694e-ddd9-9c85-b39c-4135246c7158', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0523', '龚永彪',
   '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '测试工程师', '员工', '16605233689', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('734de54a-4173-2c3f-a10e-baa1592473f0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0524', '丁昊',
   'b1f274aa-b223-1e4d-3201-2e6834142180', '董事会秘书', '员工', '13162555286', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3dbe17c1-f6fa-673a-2c71-15abc9edbaf3', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0529', '郑长凤',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', '18252709686', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0628fad4-9912-4bee-e902-88c8f442f5fe', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0530', '周相廷',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', '18752757732', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('89267872-8932-0fbc-8e77-b4a6e2949c7a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0531', '杨旭涛',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', '13665277555', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c8533472-5b90-cf8a-8cf1-b5587023bf6b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0554', '程兆俊',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '材料工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cd48a594-6a8e-e1fd-3c3e-812e5728f02e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0533', '王旭',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', '软件工程师', '员工', '15358511062', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('629c0c76-2ee7-8ad8-2ecb-7ab608601eb0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0534', '姜嵩',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '硬件工程师', '员工', '13375284806', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a2c6eb56-73ce-b396-6c89-6928d8166b10', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0536', '周道亮',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '18052560507', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('509c868b-1803-3227-807a-6cceeb596c3c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0537', '唐浩',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', '嵌入式软件工程师', '员工', '18281611696', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('58506331-f297-1659-3204-d59f68a66b4f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0538', '赵磊F',
   '864f3d24-541c-1d22-293d-e049c392bdb4', '核算主管', '主管', '18136615584', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dbb3e16d-284d-91db-9cb1-c2f8b4368b29', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0539', '徐海飞',
   '66e53ea3-40ac-c4c9-671c-e7d3e8e417e0', '工艺工程师', '员工', '15126133174', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1898e6a3-6b65-8647-b90a-83a88763540a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0540', '刘杜娟',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', 'BOM工程师', '员工', '15262240703', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('06ceffd1-1272-e45a-7272-4ffd5cd6d6a4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0542', '张海松',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', '19895323397', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8b6b9277-2ed0-9c94-1af2-88ff54850677', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0543', '俞晌',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件工程师', '员工', '17730303631', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1b8102fb-a433-29bc-3899-c51495ce1f65', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0544', '丁燕飞',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '18061167749', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('49ae391c-2478-c280-8d02-e5b29c95d2cf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0695', '朱嘉晖',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件助理工程师', '员工', '13653659051', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a0f3da20-79dd-cb22-0b25-25d5dc15ee70', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0689', '黄子杰',
   '5e240fef-491a-37f8-efbd-e51ebb82b45f', '测试助理工程师', '员工', '13382301171', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('18d44bb8-17ac-2136-755b-1ebbc1d9d73a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0545', '江山',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '13007165520', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bd278add-9b4c-c446-7f67-8a990d97e10d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0677', '张国庆',
   'a24266db-a078-9c87-600c-484bf9e83dc1', 'RF技术工程师', '员工', '19941535890', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('954035e7-4ef5-8ff3-e695-237c91b7b785', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0546', '叶其英',
   '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '测试工程师', '员工', '17701451999', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('91aa0b12-1b32-cbee-d39d-9368ba7df798', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0547', '张羽迪',
   'bfbb87f3-942d-b5ec-47f1-5248ffafd231', '项目助理', '员工', '15905258706', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('86fa5f66-b8b5-3848-5e2e-99aa10899886', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0548', '张晓冬',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13382709938', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e2c63bbc-9dc0-cfd7-fd79-377744d4de79', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0549', '许一鸣',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '15371255853', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f1b1c37a-5d02-866a-87b5-58a7da2db606', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0551', '鞠志彬',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13921904432', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a669e8c0-fc49-140c-1d3d-4d9d8e0e8ef7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0553', '薛一翔',
   'f8a64d1d-3e52-a89c-d96f-22f7b9c82e99', '材料工程师', '员工', '13338846867', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2dc62236-20b5-fac3-88d5-08f2f672066a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0555', '刘亮',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB工程师', '员工', '13155408616', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('00677a72-1b08-6092-d72a-2a9f44e39aeb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0558', '王志凌',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '19551661060', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c9c6a81e-4af4-9f43-a4fe-5b4c42399f0d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0561', '胡严冰',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '硬件工程师', '员工', '17366373312', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('58e7221d-0d09-ec26-d444-bd9ece5d456d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0654', '蔡小钰',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '15052871080', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('37428732-138b-9c7c-d41a-5e77db403658', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0678', '孙颢菘',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '15106367075', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b51f06bd-a8b7-a93a-7714-26a078882685', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0679', '常世奥',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '15166109210', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3d9e3e68-7693-0532-d3cf-1a1e3bf5d76d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0563', '王硕',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '15396787385', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('74af57ce-13cc-74d2-c934-24265ea00647', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0564', '徐耀凤',
   '5b45289b-5364-a75d-1e04-68c160a94204', 'IE工程师', '主管', '13852401675', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7c38ec31-bce6-b9ab-010b-d4b406873eea', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0691', '庄硕',
   '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '测试工程师', '员工', '15695192371', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b8946c42-5b85-56ba-2edf-77da84ab6e18', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0690', '朱久兰',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺助理工程师', '员工', '15605186373', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('efe24f80-5a45-9488-25d1-09fb4866fe14', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0566', '程世龙',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '硬件工程师', '员工', '18955900992', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e764b72b-e811-9134-60b2-fde5bd11aa10', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0565', '胡林波',
   'c3679ea0-41e0-02e3-dfd9-f28a62ddd982', '项目经理', '员工', '18062070822', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4172b616-222f-814f-7cf3-028aa9fa2b8e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0569', '王德平',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13952529571', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d49b0539-0793-e35b-9b69-e0d1cdd570b6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0571', '王哲',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17600555270', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ff332bde-b685-655e-4f00-6d128eb55794', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0575', '丁海洋',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '15205252819', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('70b793a8-ec06-25d0-4eaf-802767ec0300', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0576', '潘陈金灿',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '17315274890', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d6be6cf0-e942-3578-7ee5-05cdc278c858', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0574', '黎远杰',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', 'IT部经理', '经理', '19305145333', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6ae23aa1-111f-c6f7-50c4-20d31df75d3d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0578', '张天豪',
   '6e9c0cc5-d84d-c361-a714-e4108f14373c', '工艺工程师', '员工', '18205095015', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a239c102-3a28-392f-1551-aa8e3e64cf52', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0579', '张媛',
   'b7b6cc72-ad9c-583f-5ebd-6fe14681eb4c', '研发助理', '员工', '13681695145', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dca23826-3b9f-7f74-51cc-89acef822082', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0580', '曹霞',
   '9725fda0-6734-512f-ff0e-83d1ab112cb6', '项目管理工程师', '员工', '18762320553', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9882f499-3120-17f4-1c98-314c2e030bc8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0581', '王康',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '软件工程师', '员工', '15150801215', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9cc0d53c-579c-5f34-58fb-2e004b512b1b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0583', '王建军',
   '53ac7b8a-b274-71e3-b247-efda0df35d22', '项目经理', '经理', '15005271558', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b9f1acd5-b7f1-e2d6-ef44-79cf06fe40ab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0584', '高杰',
   '6a2579e6-3b7b-80f6-6a05-d754a707fbcc', '测试工程师', '员工', '13852728851', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3fbb2a19-a4c7-9c70-96c7-89b663561602', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0585', '丁泽宇',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '软件工程师', '员工', '18168657158', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bad1c932-9005-34de-70b2-b70f84c8ff9b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0586', '徐福',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '19951199221', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('12b03e1e-0e0a-9101-c0b5-a70132c30f4a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0589', '史福亮',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '15952597483', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c9e87b9a-a102-d21c-2527-967e11089bb8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0588', '殷嘉炎',
   '5797de5d-16a5-ece1-4554-32b5dcd6359f', 'PQC检验员', '员工', '13665211369', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('52e79f08-1608-8920-91b6-cceea195cda4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0680', '赵建浩',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '13270010298', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('be089bf2-0154-56f6-ba9a-72b9fb289429', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0591', '朱育成',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '硬件工程师', '员工', '19996623891', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('349d1042-599e-d8c7-bda5-09d09807936d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0596', '张远志',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '19872217985', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('997b0197-05bd-0c4c-8e5f-03d0f816c58f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0593', '王凯',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '18136971160', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('dc7ae68e-07c9-bb54-a6e8-fb149b2b44de', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX72', '朱勇',
   'baa6456f-3d84-9b00-4024-a51aea8fc606', '软件助理工程师', '员工', '13851773120', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6d43fcc3-3c82-816a-c854-e973390e2498', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0592', '李腾云',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '15052523603', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ef76f8f2-f906-ae20-ce8a-616b4f83551d', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0617', '王毅',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '硬件工程师', '员工', '18061847267', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e9f86361-0db8-e3c1-93d3-4c5d2eb84de9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0594', '许小春',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '19962545358', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('476e2221-22ce-dbf4-9e09-13797b00af22', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0692', '张谦',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '13035324340', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('23b65ffa-a375-015a-2cf1-7a59e0473935', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0597', '鞠园',
   'd194254c-56df-0908-188a-6e27576cffcd', 'EHS工程师', '员工', '13585235705', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c44eb2d3-753d-2898-735a-63a9c2a834b0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0601', '金燚',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', '18112135379', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('150a22c3-edf8-1682-783d-422670d0c214', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0599', '王星旭',
   '6e9c0cc5-d84d-c361-a714-e4108f14373c', '工艺助理工程师', '员工', '13773546837', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c6c5298f-7317-22dc-6a6d-faed06ba420f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0681', '朱志国',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '13961413792', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ae4bb72b-e786-5f75-cbaf-34f2cfc5c2eb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0600', '鲍献银',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '13263773661', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('715ecac1-41b8-a606-d3e3-71b207d9efa9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0598', '范康搏',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '19505278120', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7a48a4e4-06b8-b8f9-247e-78df2b4479b2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0602', '孙启通',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '18252722204', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0e6c27e4-a134-e581-9af1-0a54d9047f86', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0694', '王善源',
   '73a395cc-d910-7905-e96e-9217f4a831fd', '制造工程师', '员工', '15189660226', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6760e666-9d4b-e578-110b-80d45e401fbe', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0693', '方静',
   'debadcb0-5b8c-f39e-15ee-4a7cf2c73fff', '工艺助理工程师', '员工', '15769192655', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6c10f99a-57b9-07e1-cb10-289c13aeaac9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0604', '肖龙',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15053239269', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1d5cbaf7-ead0-953c-86bd-386b35441a2a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0605', '史文杰',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13665242361', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('76f31155-c289-0ac8-b100-e0d7ff54c2f0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0606', '程雷洪',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '15972101912', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fba6858e-ec00-8b05-9dbb-b3f9b7a89ee2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0607', '金菊',
   '6e409529-dfce-856e-236c-8390e449e535', '成本会计', '员工', '18115105449', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0c99f3e5-b88e-dad0-dfae-03932344f990', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0608', '王振尧',
   '315e300d-1533-71a1-be5f-d7bd30eaf17d', '硬件工程师', '员工', '15605178072', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('175fe922-220d-3349-397a-14d6e841e03a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0612', '娄浩',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '19071464304', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('20115196-25d5-ce05-dea8-400612a4cd33', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0615', '高文磊',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', 'IT专员', '员工', '15195562475', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7a5b6961-b1c2-aef4-44aa-502a45e9a7df', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0616', '刘俊',
   'a113e144-5120-9ef2-1912-207fde631279', 'PLC软件开发工程师', '员工', '17394775726', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('893de8ba-4a32-3ccf-60cf-12edca2df692', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0613', '张昊',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '18014051908', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('58dd03e9-e4d0-0c7a-d6e3-44dac3f99260', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0699', '汪婷婷',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '13615565925', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bfbc3c49-77e6-6c30-7284-eb37471d4421', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0614', '徐锦豪',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '17683816160', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3880b7af-d812-b629-1f1e-7f31dd1e4c2a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0700', '徐贵龙',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '13585126905', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bf108dfe-a094-5ea8-70f5-12d9a3b05442', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0701', '陈希雨',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC检验员', '员工', '15183808633', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1b87e61c-f4d1-7bc5-e551-214673f5b4e0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0618', '彭帆',
   'a0d771a6-87fe-a34b-1bcd-51b3860c8536', '嵌入式软件工程师', '员工', '15760367252', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('966c494d-72e2-bc09-9999-92604f1992ad', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0622', '满翔',
   '5b45289b-5364-a75d-1e04-68c160a94204', '工艺工程师', '员工', '13952780686', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e1359b24-e67f-d832-4b0a-f3b1b4d439c4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0620', '王燕',
   'ce634182-fa27-6b58-6356-095c849ad4ea', '生产助理', '员工', '18066017163', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f3480562-258f-45ef-7a4d-bd1c13fe97d7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0623', '刘晔',
   'ab4fe68a-fb47-7e61-c57b-6df659dd9026', '销售助理', '员工', '13665213968', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('576bcbc0-6f66-f205-7546-bbec9e815b16', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0624', '朱思义',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '18752711432', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('23abcef9-ecf9-0c54-dd97-c89f5cb88b3e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0627', '方华驭驰',
   '7ea98b08-aeec-b026-b928-66b61cbcc248', '测试工程师', '员工', '18896771062', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b8162aaf-dbe3-3ded-33a2-b177c4d53534', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0625', '张云龙',
   'f8f04886-cfef-f3db-0149-ee354339c81f', '电子技术工程师', '员工', '18130282075', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9913e030-acc6-013a-9c6e-054e8825649a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0626', '胡思奇',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15040131835', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e259bede-88e1-dee6-f258-26f755c20d3e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0628', '梁彪',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '18726403241', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a9279597-721b-dfc5-8e32-2169e79f8a8f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0629', '张扬',
   '5b45289b-5364-a75d-1e04-68c160a94204', 'IE工程师', '员工', '19975072632', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('667d9681-0e45-0092-3473-5e8ea5c35286', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0632', '韩丛轩',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '19952459963', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a936f5e3-4312-a706-89ae-4acbd22c77d9', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0630', '龙隆',
   'af797e91-9e62-9d6e-dde1-c4c96807131e', '深圳办事处工程师', '员工', '13071309161', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2f768de9-6270-e77f-54a8-87e74ca73548', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0702', '许朱成',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '17766000912', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9504cf4d-2d50-b320-c8c6-6e713c132108', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0635', '徐琨',
   'd6840d19-e044-f127-cb8b-9b38978b7881', 'RF-E组工程师', '员工', '18168665850', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('48a24a92-faf2-aa89-b5be-0e280d10e5e7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0636', '谢昆泉',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '15189888710', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('670753f2-3cc4-8dca-9b02-04958916acfb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0634', '薛亮',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '射频工程师', '员工', '15380308513', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d9fffe51-79df-3b48-29ed-3033ecd103c0', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0641', '陈柏宇',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '15295217266', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('233c25ae-7751-b3f0-2347-bf891ec567ab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0643', '吴清华',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '18852558298', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('acae3179-4afb-3e29-024e-15c9c8a23278', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0640', '林延聪',
   '2e377986-558d-7db4-6d8e-0c2285cf5196', '结构工程师', '员工', '15850657990', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('06932c82-7fb1-3f38-aed9-dfcc2092f8e4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0644', '帅伟成',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '15052589539', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('6bb609c1-eaea-51cd-ad37-d139a728cec2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0638', '刘梓轩',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '13593925122', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4ab602d3-edae-2175-ad51-72212dcca15b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0639', '熊士强',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15623539337', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('54dbf3e8-9597-d088-6d33-faf7bcb60883', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0637', '李翔宇',
   '66e53ea3-40ac-c4c9-671c-e7d3e8e417e0', '工艺工程师', '员工', '18014171709', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('ddb37e7f-e20a-d771-8375-09aa56c67c4a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0642', '蒋志浩',
   '42cce929-1b8a-eb3e-17f2-c3b2e65edae0', 'PCB工程师', '员工', '19986329570', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0c58753f-23f7-54f4-a882-18cebaeb920e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0647', '丁伟',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '17648209267', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('7d53cefa-db91-607f-bd79-7ba52b99a59c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0645', '张泽',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '19824333994', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b2f476ab-762e-4117-5b95-ae136497a47a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0646', '温慧杰',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '15904967563', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fd620a61-c59d-7edf-24e3-ecbb3560cc7b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0649', '吴新晨',
   'd20bda8b-3529-2af6-ba01-998bc3ba1243', '测试工程师', '员工', '19816288023', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e92b7c15-f2e8-d995-aaa7-d83094121a37', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0651', '周晨',
   '0ccddfef-18a9-960d-4c3e-862d35c8895f', '项目管理工程师', '员工', '13511730837', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('48448c78-d755-03cc-56cb-d86d08453af6', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0650', '申建武',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '17353488060', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3bd4bee4-0d8e-d30b-14df-aaeb84fadf18', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0648', '巨铭铭',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18992037948', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e06bf07f-07af-4afa-240b-e41220bed376', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0653', '李心妍',
   '989fe9f5-dc05-f61d-41a7-407eee5dc63c', '销售助理', '员工', '15623352368', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a7597601-43ac-0972-c7df-1922c759bdbf', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0652', '江梦圆',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '15366197858', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d51b9480-4f36-70a6-acda-bf69e8fbf6e5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0666', '乔乐',
   'a3c53269-90cf-116e-2f95-3262b80f1ad8', 'PQC检验员', '员工', '18352709320', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a0b99536-4abe-3c59-e8db-e62d011acb0e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0656', '钟承远',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', '18852722028', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bf8211d0-7c01-27e7-d5c2-654129988d76', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0657', '杨明达',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '13952703031', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('40303e66-20dd-5048-05d3-b03032c8dbac', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0665', '何卓慰',
   'd20bda8b-3529-2af6-ba01-998bc3ba1243', '测试助理工程师', '员工', '18352796051', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c75172eb-6462-e3cb-bd52-0e49d4352a67', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0662', '李恩琪',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15797788108', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c028863e-d1c1-a525-7e60-fab8f7b0e06b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0664', '周易钊',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18838287962', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bff2a671-7f82-46f5-56a5-ba543f48ec5c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0661', '张乾',
   '937d2188-d785-4be9-6a96-62988c8c2ef3', '硬件工程师', '员工', '17714933182', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c30a2a3d-2535-cf52-7d6e-c83638f4841b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0658', '李晓磊',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '18762770137', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('37ed2d1b-08dd-95d3-4c4b-9c913fac5935', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0660', '马锦涵',
   'b05a261c-ca05-5c6f-e176-5312c9ec46a5', '射频工程师', '员工', '18071831232', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d1cb0690-43c2-c48b-84cf-6d7f765aa12f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0659', '林贵奇',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', '13773521901', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0df81a51-6a6f-946f-8164-b2c0dbb5772b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0663', '张晨阳',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '13193763197', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1812ade7-7c8d-8814-8ff1-43f7cd71f6b8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0672', '吴有磊',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', '18020158125', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('bb51d7c5-1fe1-e287-4c2a-96b854e92d69', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0674', '徐利民',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '15107015204', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e7a65ca4-29c2-4ec6-edc6-d9db896d6322', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0676', '孔凡玉',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', '19901444985', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('cbf04034-83e0-1bbd-0a2f-34a8808c48b4', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0673', '黄金鑫',
   'ebd9d4ce-7633-02dc-9c5d-69bddb29de5c', 'IT专员', '员工', '18862296205', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c1d48621-f762-1d4c-2bc7-fc01addf4f25', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0675', '杨晨',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15850433438', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('06b8d487-3f1e-bfc4-7cba-589e771e3ca5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0669', '张清雅',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '15904246931', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fadb78b5-c464-b023-b482-5612d31be202', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX92', '毛坤',
   '66e76b74-3be4-e9ea-c717-5a79c8f253ec', 'RF-G组工程师', '员工', '19515776443', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('acce243d-a72d-153d-063a-4382987c1ecb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX101', '周思恬',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', '15896322669', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('c6b056e2-f283-7c6c-c86d-4346fe2ebcbb', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX91', '刘至宽',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '电子工程师', '员工', '18451331571', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('29f7beb8-c830-1cd8-bd87-281a1767a886', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX97', '林轩',
   '0b5083df-4f27-f4e2-5890-1f0fef7d759e', 'RF-A组工程师', '员工', '18909979396', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b27ef5fe-a481-e1c0-3e44-b8920dabddbd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX95', '姚韩',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '18585896877', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('870f69c3-1d24-179a-14e8-e7915944b67c', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX102', '陈雨阳',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', '18018113680', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8f006f74-ca23-bcac-c883-655177d8ecfa', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX96', '吴文迪',
   'be6c8d2c-e6b3-9341-d971-d9cf237f3569', 'RPS技术工程师', '员工', '13075260890', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('339f1dcb-cda9-8a29-1b1c-918f968b9d48', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX104', '郑家辉',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', '17788301531', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('22cf1d2c-d418-f4f7-dbe3-de708403dbc8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX103', '朱佳浚',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', '13389948154', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('4574f67a-7f18-e909-d448-91580190fd4a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX105', '陈达',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', '18482253364', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3ffe7af8-0e76-d3a2-d147-34f78703bf95', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX98', '龚金锐',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '18718766887', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1f65e9db-6686-3a86-7582-c7331543fd62', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX100', '阚明阳',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', '19805097786', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f2652ddb-fa5d-14c5-7ffc-86865378ae65', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX99', '王锖',
   '120d14d4-2e0c-d118-d7a2-ecdcaefd0955', '工艺工程师', '员工', '17883618734', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('08259c89-effd-3c66-1c12-ea5668f3b44b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX89', '朱安旭',
   'f153fe97-c8ff-ae3e-7ef1-c7609d63250f', 'MATCH2组工程师', '员工', '15262223327', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('14579266-8e94-a216-fab1-3690da80fa7f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX108', '吕刘恩',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC工程师', '员工', '15252761023', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('0326aae5-faa2-9968-35be-3169b8f08fc7', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX93', '朱尚帅',
   'ef015ccc-db1c-4983-323a-bf7a617777e2', 'RF-F组工程师', '员工', '15705255023', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fba6737c-fac6-849a-f91f-756d3a4f6f88', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX94', '李欣蓬',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '13585489189', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d83d6183-300e-75a2-2668-51d314aa4fb2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX90', '孙业超',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '15250363303', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a7bf353f-46de-227a-c78d-bfc96e4cf7cd', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX106', '何佳乐',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC工程师', '员工', '13564124376', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('57732f87-81fb-38d0-74fc-9961457c0127', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZSTSX107', '付丹丹',
   '65f47257-faa8-080e-ceda-78bfdf0289a3', 'IQC工程师', '员工', '18352794067', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('d1fc0294-5ccf-9b39-7d74-04d187128de8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0671', '于跃',
   'afb90a69-a46b-7b77-c65c-cabe45d92c01', '制造工程师', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('f69243bf-efad-ad40-bcfd-0737ca83935e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0668', '李杨杰',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '18616339263', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2981ca6d-e462-4061-b027-6fd19ed0c536', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0684', '崔景',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '硬件工程师', '员工', '15951660188', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d256a70a-123c-2897-c98e-1d717aa31988', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0685', '何青',
   'af797e91-9e62-9d6e-dde1-c4c96807131e', '深圳办事处工程师', '员工', '15211698267', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('db0ee053-d901-5545-774b-dfdb178fa870', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0683', '黄琦',
   '1731601e-ab3e-3858-c335-b036ddc426b5', '测试工程师', '员工', '15926413128', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('75d39505-a208-45b8-439b-79bed1887949', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZT0687', '吕加军',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '19516720253', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d9e196c2-5aab-80a7-0d09-ca4d9a2225f1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0686', '吉润阳',
   '7c85e1e8-398b-0d6c-75c7-94f777ed9003', 'RPS组工程师', '员工', '19501108227', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d94331cc-7c8c-894e-52c3-2ccb896ab00e', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0688', '茅宁',
   'f0182236-7f25-c349-ee23-e73328e05767', 'MATCH1组工程师', '员工', '13585233679', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e671aecd-2acd-37e7-009e-de352aba8d5b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0682', '陈乐',
   '3e622e47-f435-d91b-871e-848669da7a0c', '采购专员', '员工', '18360265985', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('e37acb9e-f904-e67c-ac26-019cbc0df24f', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0667', '黄凯',
   '53cf8a70-2e33-7838-24d4-7b25e017f7df', '销售工程师', '员工', '13296609335', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('1a3e4d01-7ae8-9088-afe5-ed6f76425e42', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0696', '赵浩旭',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '15588201702', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('89d03193-7d6f-99c0-90ae-cd00760b7eb8', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0697', '胡鑫',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '13797513601', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d15da0e2-66ff-b531-7fe7-c16b5d723710', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0698', '丁建权',
   '59b4f46b-fad7-aec4-716b-904addab8189', 'MATCH技术工程师', '员工', '15995120827', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('2ad85652-25ff-af4a-4e0e-b89a03ab73b5', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0704', '罗毅',
   '66be2178-5bdc-6467-ed3d-3025cb595022', '产品服务工程师', '员工', '18071461006', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('13b8b289-4cbf-a190-fb34-4d2af5a1f485', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0706', '姜志鹏',
   '59b4f46b-fad7-aec4-716b-904addab8189', 'MATCH技术工程师', '员工', '15331934112', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3b0f1d52-db21-e645-96af-8fedcefde01a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0705', '周杰',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '15252547128', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6c1b86ef-faaa-d644-1c6c-ce0070ddf579', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0707', '张叶彬',
   'ad8bcd2b-429e-c8ce-43ea-4452dcf8f02f', 'DC技术工程师', '员工', '15249997978', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('b8b07aaf-d07e-0e23-9b2a-db3ee8c6eb6b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0703', '彭旭',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '18855469553', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('3708dd58-54d1-8c4f-b2ed-72d3f55dc0a1', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0708', '戢昱',
   'bda51b24-1962-1e0c-9383-75fdaa819c6a', '大连办事处工程师', '员工', '15508502715', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('047002cd-b7a5-543d-621e-4fc9dd2576ab', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0714', '仇容轩',
   '98d12062-d479-9427-783f-7d3d1300062e', '仓储管理员', '员工', '17768540821', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('8b68669c-797b-080d-6699-39797401ed66', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0710', '王威',
   '9ff30190-808b-6a97-3f62-f29b95ce6620', '现场服务工程师', '员工', '19547428790', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('72f575f4-dedb-e235-ac14-b86b0ee00771', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZT0709', '杨玲',
   '1b216a4c-931d-5ef4-96e9-5a58429164a6', '质量主管', '主管', '15952720015', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('abd693cf-ae94-8099-c17a-15476d3b340b', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0713', '王俊杰',
   'd1ff01c5-adc6-e027-e096-6cb857003cdb', 'DC组工程师', '员工', '17388093789', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9663f13d-1afe-f795-0e0d-45b2c3aebad2', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0711', '李汪智',
   'ff3668ba-5cb5-d5fd-ebf0-a307247c9d9d', '测试工程师', '员工', '13665202941', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('9c153585-d8b0-19d7-c63c-342236143d0a', '4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', 'SZST0712', '张海尔',
   '122bcce6-f526-ce6d-247a-97612ba983aa', 'RF-B组工程师', '员工', '18168653940', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

-- 江苏芯越半导体科技有限公司 (8 人)

INSERT INTO person (
  person_id, company_id, employee_number, full_name,
  department_id, position, mobile, email, hire_date, status,
  row_version, created_by, created_at, updated_by, updated_at
) VALUES
  ('4cb64923-0587-fc5c-8514-b8e29605471c', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0001', '吴金鑫',
   '5e21214d-7a3d-aff7-e376-f34cba660011', '人事行政专员', '主管', '18061155618', NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('a6a1897f-13a7-640b-c719-baa00470570a', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0002', '皮昌全',
   'b185c673-d8c5-3e14-ae2e-9b9497eb2ed2', '销售主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('d991c39f-4812-b724-aacb-6940af289408', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0008', '谢芸',
   'b1ab2e1d-4021-d43f-5d1e-191eee9bd50a', '计划主管', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6e322250-2576-139e-e7ad-3714eeb26b11', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0004', '张超',
   'f4b80a62-8db2-59cb-ba4f-c458c74f34ca', '项目经理', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('fd48b253-fdb9-a09c-4a86-c705cae56fe5', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0017', '刘婷',
   'f4b80a62-8db2-59cb-ba4f-c458c74f34ca', '工程文员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('37b6f57a-30d3-6e8c-ecbd-be2abc139e79', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0018', '乔文婷',
   'b185c673-d8c5-3e14-ae2e-9b9497eb2ed2', '销售员', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('83067002-6234-b904-ebf1-f6aa539d3cb4', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0007', '罗锐',
   '26e4aa3b-ea17-8493-b9c9-dd1276399c04', '出纳', '员工', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now),
  ('6b30c9a1-4ba9-71f2-8fc5-2f10ee02707e', '5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', 'SZXY0024', '李燕红',
   '26e4aa3b-ea17-8493-b9c9-dd1276399c04', '总账', '主管', NULL, NULL, '在职',
   0, @system_principal_id, @now, @system_principal_id, @now);

-- ============================================================================
-- 3. 验证统计
-- ============================================================================

SELECT 
  c.company_code,
  c.company_name,
  COUNT(p.person_id) AS person_count
FROM company c
LEFT JOIN person p ON p.company_id = c.company_id
WHERE c.company_code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
GROUP BY c.company_code, c.company_name
ORDER BY c.company_code;

-- 预期总人数: 620 人
-- ============================================================
-- 2/6: four-company-finalization (四公司基础考勤配置)
-- ============================================================
-- Four-company finalization for the confirmed 2026-08-05 local data shape.
--
-- Safety contract:
--   * caller must explicitly select a controlled ShenzhouHR target schema;
--   * run on MySQL 8.0.45 after successful Flyway V31 (V31 is DDL-only);
--   * stop application writes and take a verified backup first;
--   * the three mixed Excel imports and the original configuration remain on
--     an INACTIVE archive company; no historical import/audit row is rewritten;
--   * all four companies use Yangzhou as the default location/time. Only
--     Jiangsu Shenzhou Dalian and Chengdu have independent fixed shifts;
--     its Shanghai group copies the Yangzhou seasonal shift. Other cities are
--     selectable location records only and do not create implicit assignments;
--   * every new identity is fixed (literal or deterministic UUID-shaped MD5);
--   * no INSERT IGNORE / REPLACE / disabled foreign-key checks;
--   * a partial, changed or future non-empty business shape raises SIGNAL and
--     rolls back. A fully finalized shape is verified and becomes a no-op.

DROP PROCEDURE IF EXISTS finalize_four_companies_post_v30;

DELIMITER $$

CREATE PROCEDURE finalize_four_companies_post_v30()
main: BEGIN
    DECLARE lock_acquired INT DEFAULT 0;
    DECLARE target_company_rows INT DEFAULT 0;
    DECLARE migration_needed BOOLEAN DEFAULT FALSE;
    DECLARE original_group_concat_max_len BIGINT UNSIGNED DEFAULT NULL;
    DECLARE source_location_id VARCHAR(36);
    DECLARE source_location_revision_id VARCHAR(36);
    DECLARE source_shift_template_id VARCHAR(36);
    DECLARE source_calendar_id VARCHAR(36);
    DECLARE source_group_id VARCHAR(36);
    DECLARE source_group_revision_id VARCHAR(36);
    DECLARE admin_principal_id VARCHAR(36);
    DECLARE admin_account_id VARCHAR(36);
    DECLARE admin_session_epoch BIGINT UNSIGNED;
    DECLARE hr_role_id VARCHAR(36);
    DECLARE system_role_id VARCHAR(36);
    DECLARE old_scope_id VARCHAR(36);

    DECLARE archive_company_id VARCHAR(36)
        DEFAULT '4dcb5ee1-b186-58d3-b538-6661a3dd435d';
    DECLARE baseline_company_id VARCHAR(36)
        DEFAULT '30000000-0000-0000-0000-000000000001';
    DECLARE bootstrap_actor_id VARCHAR(36)
        DEFAULT '20000000-0000-0000-0000-000000000001';
    DECLARE cutover_date DATE DEFAULT '2026-08-05';
    DECLARE cutover_at DATETIME(6) DEFAULT '2026-08-05 00:00:00.000000';

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF original_group_concat_max_len IS NOT NULL THEN
            SET SESSION group_concat_max_len = original_group_concat_max_len;
        END IF;
        DO RELEASE_LOCK('shenzhouhr:four-company-finalization:v1');
        RESIGNAL;
    END;

    SET original_group_concat_max_len = @@SESSION.group_concat_max_len;
    SELECT GET_LOCK('shenzhouhr:four-company-finalization:v1', 0)
      INTO lock_acquired;
    IF lock_acquired <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four-company finalization lock is already held';
    END IF;

    SET SESSION group_concat_max_len = 4000000;
    START TRANSACTION;

    IF DATABASE() IS NULL
       OR SUBSTRING_INDEX(VERSION(), '-', 1) <> '8.0.45' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'requires an explicitly selected schema on MySQL 8.0.45';
    END IF;
    IF (SELECT COUNT(*) FROM flyway_schema_history
        WHERE version = '31' AND success = 1) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'successful Flyway V31 is required before finalization';
    END IF;
    IF (SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'company', 'location', 'location_revision', 'location_timeline',
              'shared_location', 'shared_location_revision',
              'company_location_availability', 'employee',
              'organization_identity', 'auth_principal'
          )) <> 10 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'selected schema is not a complete V31 ShenzhouHR target';
    END IF;
    IF (SELECT COUNT(*) FROM company
        WHERE company_id = baseline_company_id
          AND code = 'W3_BASELINE_LEGAL_ENTITY'
          AND status = 'INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'W3 baseline company shape changed';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS fc_company_map;
    DROP TEMPORARY TABLE IF EXISTS fc_org_map;
    DROP TEMPORARY TABLE IF EXISTS fc_org_map_ancestor;
    DROP TEMPORARY TABLE IF EXISTS fc_employee_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_predecessor_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_summer_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_winter_h2_map;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_version_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_shift_timeline_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_map;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_predecessor_map;
    DROP TEMPORARY TABLE IF EXISTS fc_calendar_version_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_policy_map;
    DROP TEMPORARY TABLE IF EXISTS fc_binding_map;
    DROP TEMPORARY TABLE IF EXISTS fc_assignment_map;
    DROP TEMPORARY TABLE IF EXISTS fc_city_map;
    DROP TEMPORARY TABLE IF EXISTS fc_special_map;
    DROP TEMPORARY TABLE IF EXISTS fc_named_employee_expectation;
    DROP TEMPORARY TABLE IF EXISTS fc_special_employee_map;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_raw;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_raw_copy;
    DROP TEMPORARY TABLE IF EXISTS fc_special_shift_stage;
    DROP TEMPORARY TABLE IF EXISTS fc_special_binding_map;

    CREATE TEMPORARY TABLE fc_company_map (
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        company_code VARCHAR(64) NOT NULL,
        company_name VARCHAR(200) NOT NULL,
        short_name VARCHAR(32) NOT NULL,
        root_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        expected_orgs INT NOT NULL,
        expected_employees INT NOT NULL,
        root_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        location_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_schedule_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        calendar_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        provisioning_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        annual_policy_version_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        annual_lifecycle_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        auth_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
    ) ENGINE=InnoDB;

    INSERT INTO fc_company_map VALUES
      ('41000000-0000-0000-0000-000000000001', 'SZSZ',
       '上海昇州半导体科技有限公司', '上海昇州',
       'b0d252af-5e5f-567c-a58e-f625e84fd530', 2, 1,
       '41001000-0000-0000-0000-000000000001',
       '41010000-0000-0000-0000-000000000001',
       '41011000-0000-0000-0000-000000000001',
       '41012000-0000-0000-0000-000000000001',
       '41020000-0000-0000-0000-000000000001',
       '41022000-0000-0000-0000-000000000001',
       '41023000-0000-0000-0000-000000000001',
       '41030000-0000-0000-0000-000000000001',
       '41040000-0000-0000-0000-000000000001',
       '41041000-0000-0000-0000-000000000001',
       '41042000-0000-0000-0000-000000000001',
       '41050000-0000-0000-0000-000000000001',
       '41060000-0000-0000-0000-000000000001',
       '41061000-0000-0000-0000-000000000001',
       '41090000-0000-0000-0000-000000000001'),
      ('41000000-0000-0000-0000-000000000002', 'SZJN',
       '上海晟州聚能半导体科技有限公司', '上海晟州聚能',
       '277137ee-0e50-5dcc-a6b5-504168fbab29', 17, 35,
       '41001000-0000-0000-0000-000000000002',
       '41010000-0000-0000-0000-000000000002',
       '41011000-0000-0000-0000-000000000002',
       '41012000-0000-0000-0000-000000000002',
       '41020000-0000-0000-0000-000000000002',
       '41022000-0000-0000-0000-000000000002',
       '41023000-0000-0000-0000-000000000002',
       '41030000-0000-0000-0000-000000000002',
       '41040000-0000-0000-0000-000000000002',
       '41041000-0000-0000-0000-000000000002',
       '41042000-0000-0000-0000-000000000002',
       '41050000-0000-0000-0000-000000000002',
       '41060000-0000-0000-0000-000000000002',
       '41061000-0000-0000-0000-000000000002',
       '41090000-0000-0000-0000-000000000002'),
      ('41000000-0000-0000-0000-000000000003', 'SZSC',
       '江苏神州半导体科技股份有限公司', '江苏神州',
       '00fd5358-f068-5b13-b0cc-1a41390e201c', 130, 571,
       '41001000-0000-0000-0000-000000000003',
       '41010000-0000-0000-0000-000000000003',
       '41011000-0000-0000-0000-000000000003',
       '41012000-0000-0000-0000-000000000003',
       '41020000-0000-0000-0000-000000000003',
       '41022000-0000-0000-0000-000000000003',
       '41023000-0000-0000-0000-000000000003',
       '41030000-0000-0000-0000-000000000003',
       '41040000-0000-0000-0000-000000000003',
       '41041000-0000-0000-0000-000000000003',
       '41042000-0000-0000-0000-000000000003',
       '41050000-0000-0000-0000-000000000003',
       '41060000-0000-0000-0000-000000000003',
       '41061000-0000-0000-0000-000000000003',
       '41090000-0000-0000-0000-000000000003'),
      ('41000000-0000-0000-0000-000000000004', 'SZXY',
       '江苏芯越半导体科技有限公司', '江苏芯越',
       'b1570eff-0f9a-527e-b7c4-fa201c022c73', 7, 8,
       '41001000-0000-0000-0000-000000000004',
       '41010000-0000-0000-0000-000000000004',
       '41011000-0000-0000-0000-000000000004',
       '41012000-0000-0000-0000-000000000004',
       '41020000-0000-0000-0000-000000000004',
       '41022000-0000-0000-0000-000000000004',
       '41023000-0000-0000-0000-000000000004',
       '41030000-0000-0000-0000-000000000004',
       '41040000-0000-0000-0000-000000000004',
       '41041000-0000-0000-0000-000000000004',
       '41042000-0000-0000-0000-000000000004',
       '41050000-0000-0000-0000-000000000004',
       '41060000-0000-0000-0000-000000000004',
       '41061000-0000-0000-0000-000000000004',
       '41090000-0000-0000-0000-000000000004');

    CREATE TEMPORARY TABLE fc_city_map (
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        city_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        city_name VARCHAR(100) NOT NULL,
        location_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        location_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        location_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        PRIMARY KEY(company_id,city_code),
        UNIQUE KEY(location_id),UNIQUE KEY(location_revision_id),
        UNIQUE KEY(location_timeline_id)
    ) ENGINE=InnoDB;
    INSERT INTO fc_city_map
    SELECT company.company_id,city.city_code,city.city_name,
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(location_hash,1,8),'-',SUBSTR(location_hash,9,4),'-',
                        SUBSTR(location_hash,13,4),'-',SUBSTR(location_hash,17,4),'-',
                        SUBSTR(location_hash,21,12))),
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(revision_hash,1,8),'-',SUBSTR(revision_hash,9,4),'-',
                        SUBSTR(revision_hash,13,4),'-',SUBSTR(revision_hash,17,4),'-',
                        SUBSTR(revision_hash,21,12))),
           LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(timeline_hash,1,8),'-',SUBSTR(timeline_hash,9,4),'-',
                        SUBSTR(timeline_hash,13,4),'-',SUBSTR(timeline_hash,17,4),'-',
                        SUBSTR(timeline_hash,21,12)))
    FROM fc_company_map company
    CROSS JOIN (
        SELECT 'CHENGDU' city_code,'成都' city_name UNION ALL
        SELECT 'DALIAN','大连' UNION ALL SELECT 'SHANGHAI','上海' UNION ALL
        SELECT 'HEFEI','合肥' UNION ALL SELECT 'WUHAN','武汉' UNION ALL
        SELECT 'SHENZHEN','深圳'
    ) city
    CROSS JOIN LATERAL (SELECT
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location|',
                   company.company_id,'|',
                   city.city_code)) location_hash,
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location-revision|',
                   company.company_id,'|',
                   city.city_code)) revision_hash,
        MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|city-location-timeline|',
                   company.company_id,'|',
                   city.city_code)) timeline_hash
    ) seed;

    CREATE TEMPORARY TABLE fc_special_map (
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        shift_code VARCHAR(64) NOT NULL,
        group_code VARCHAR(64) NOT NULL,
        group_name VARCHAR(100) NOT NULL,
        expected_employees INT NOT NULL,
        shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        group_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
        seasonal_schedule_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
        seasonal_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE
    ) ENGINE=InnoDB;
    INSERT INTO fc_special_map VALUES
      ('DALIAN','DALIAN_FIXED','DALIAN_ATTENDANCE','大连考勤组',13,
       '42020000-0000-0000-0000-000000000001',
       '42040000-0000-0000-0000-000000000001',
       '42041000-0000-0000-0000-000000000001',
       '42042000-0000-0000-0000-000000000001',NULL,NULL),
      ('CHENGDU','CHENGDU_FIXED','CHENGDU_ATTENDANCE','成都考勤组',4,
       '42020000-0000-0000-0000-000000000002',
       '42040000-0000-0000-0000-000000000002',
       '42041000-0000-0000-0000-000000000002',
       '42042000-0000-0000-0000-000000000002',NULL,NULL),
      ('SHANGHAI','SHANGHAI_SEASONAL','SHANGHAI_ATTENDANCE','上海考勤组',1,
       '42020000-0000-0000-0000-000000000003',
       '42040000-0000-0000-0000-000000000003',
       '42041000-0000-0000-0000-000000000003',
       '42042000-0000-0000-0000-000000000003',
       '42022000-0000-0000-0000-000000000003',
       '42023000-0000-0000-0000-000000000003');

    SELECT COUNT(*) INTO target_company_rows
    FROM company c JOIN fc_company_map m ON m.company_id = c.company_id;
    IF target_company_rows NOT IN (0, 4) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial target company set detected';
    END IF;
    SET migration_needed = target_company_rows = 0;

    IF migration_needed THEN
        IF (SELECT COUNT(*) FROM company
            WHERE company_id = archive_company_id
              AND code = 'SZSC'
              AND name = '江苏神州半导体科技股份有限公司'
              AND status = 'ACTIVE') <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source company is not the approved initial shape';
        END IF;
    ELSEIF (SELECT COUNT(*) FROM company c JOIN fc_company_map m
            ON m.company_id = c.company_id
            WHERE c.code = m.company_code AND c.name = m.company_name
              AND c.status = 'ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company
           WHERE company_id = archive_company_id
             AND code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
             AND name = '历史导入归档（原四公司混合数据）'
             AND status = 'INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target company set exists with a changed shape';
    END IF;

    -- Lock authoritative source rows before deriving any mapping.
    SELECT company_id FROM company
    WHERE company_id IN (archive_company_id, baseline_company_id)
       OR company_id IN (SELECT company_id FROM fc_company_map)
    ORDER BY company_id FOR UPDATE;

    IF (SELECT COUNT(*) FROM auth_principal
        WHERE principal_id = bootstrap_actor_id AND status = 'ACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'bootstrap actor is unavailable';
    END IF;
    SELECT p.principal_id, a.account_id, a.session_epoch
      INTO admin_principal_id, admin_account_id, admin_session_epoch
    FROM auth_principal p JOIN local_account a ON a.principal_id = p.principal_id
    WHERE a.username = 'szsc_admin_faa41d5bd802'
      AND a.normalized_username = 'szsc_admin_faa41d5bd802'
      AND a.status = 'ACTIVE' AND p.status = 'ACTIVE'
    FOR UPDATE;
    IF admin_principal_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'active initial administrator is unavailable';
    END IF;
    SELECT role_id INTO hr_role_id FROM auth_role
      WHERE role_code = 'HR_ADMIN' FOR UPDATE;
    SELECT role_id INTO system_role_id FROM auth_role
      WHERE role_code = 'SYSTEM_ADMIN' FOR UPDATE;
    IF hr_role_id IS NULL OR system_role_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'required administrator roles are unavailable';
    END IF;

    SELECT scope_id INTO old_scope_id FROM auth_data_scope
    WHERE scope_type = 'COMPANY' AND company_id = archive_company_id
      AND organization_id IS NULL
    ORDER BY valid_from DESC LIMIT 1 FOR UPDATE;
    IF old_scope_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source administrator company scope is unavailable';
    END IF;
    IF migration_needed AND (
        SELECT COUNT(*)
        FROM auth_principal_role_assignment a
        WHERE a.principal_id = admin_principal_id
          AND a.data_scope_id = old_scope_id
          AND a.role_id IN (hr_role_id, system_role_id)
          AND a.valid_from <= cutover_at
          AND (a.valid_to IS NULL OR a.valid_to > cutover_at)
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source administrator archive grants changed';
    END IF;

    -- The four fixed roots must still be disjoint current trees.
    CREATE TEMPORARY TABLE fc_org_map (
        organization_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        root_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        depth INT NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_org_map
    WITH RECURSIVE tree AS (
        SELECT m.root_id AS organization_id, m.root_id, m.company_id, 0 depth
        FROM fc_company_map m
        UNION ALL
        SELECT child.organization_id, tree.root_id, tree.company_id, tree.depth + 1
        FROM tree
        JOIN organization_version child
          ON child.parent_organization_id = tree.organization_id
         AND child.current_marker = 1
         AND child.status = 'ACTIVE'
    )
    SELECT organization_id, root_id, company_id, depth FROM tree;

    CREATE TEMPORARY TABLE fc_org_map_ancestor LIKE fc_org_map;
    INSERT INTO fc_org_map_ancestor SELECT * FROM fc_org_map;

    IF (SELECT COUNT(*) FROM fc_org_map) <> 156 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN fc_org_map om ON om.root_id = m.root_id
        GROUP BY m.company_id, m.expected_orgs
        HAVING COUNT(om.organization_id) <> m.expected_orgs
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id = m.root_id
        JOIN organization_version v ON v.organization_version_id = p.current_version_id
        WHERE v.parent_organization_id IS NOT NULL OR v.status <> 'ACTIVE'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF (SELECT COUNT(*) FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id = m.root_id
        JOIN organization_version v ON v.organization_version_id = p.current_version_id
        WHERE v.name = m.company_name) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'four root organization trees changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM organization_current_closure cl
        JOIN fc_org_map_ancestor a
          ON a.organization_id = cl.ancestor_organization_id
        JOIN fc_org_map d ON d.organization_id = cl.descendant_organization_id
        WHERE a.root_id <> d.root_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'cross-root organization closure detected';
    END IF;

    CREATE TEMPORARY TABLE fc_employee_map (
        employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        employment_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
    ) ENGINE=InnoDB;
    INSERT INTO fc_employee_map
    SELECT a.employee_id, om.company_id, a.assignment_id
    FROM employment_assignment a
    JOIN fc_org_map om ON om.organization_id = a.organization_id
    WHERE a.record_status = 'ACTIVE' AND a.current_version_marker = 1;

    IF (SELECT COUNT(*) FROM fc_employee_map) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN fc_employee_map em ON em.company_id = m.company_id
        GROUP BY m.company_id, m.expected_employees
        HAVING COUNT(em.employee_id) <> m.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF (SELECT COUNT(*) FROM employee) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF (SELECT COUNT(*) FROM employment_assignment
        WHERE record_status = 'ACTIVE' AND current_version_marker = 1) <> 615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;
    IF EXISTS (
        SELECT employee_id FROM employment_assignment
        WHERE record_status = 'ACTIVE' AND current_version_marker = 1
        GROUP BY employee_id HAVING COUNT(*) <> 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'employee/current-employment root mapping changed';
    END IF;

    CREATE TEMPORARY TABLE fc_named_employee_expectation (
        employee_number VARCHAR(128) PRIMARY KEY,
        display_name VARCHAR(100) NOT NULL,
        expected_company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
        expected_organization_name VARCHAR(200) NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_named_employee_expectation VALUES
      ('SZST0074','路昊','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0121','高攀','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0163','李政','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0207','王小龙','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0302','姜长波','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0388','王杰S','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0414','李春江','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0424','王松','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0445','霍岩','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0645','张泽','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0646','温慧杰','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0669','张清雅','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0708','戢昱','41000000-0000-0000-0000-000000000003','DALIAN','客户现场服务部-大连办事处'),
      ('SZST0511','周文武','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0512','周彦沛','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0618','彭帆','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0537','唐浩','41000000-0000-0000-0000-000000000003','CHENGDU',NULL),
      ('SZST0442','张静','41000000-0000-0000-0000-000000000003','SHANGHAI',NULL),
      ('SZJN0012','赵俊君','41000000-0000-0000-0000-000000000002',NULL,NULL),
      ('SZJN0021','时晨','41000000-0000-0000-0000-000000000002',NULL,NULL),
      ('SZJN0030','王颂雅','41000000-0000-0000-0000-000000000002',NULL,NULL);

    IF EXISTS (
        SELECT 1
        FROM fc_named_employee_expectation expected
        LEFT JOIN employee e
          ON e.employee_number=expected.employee_number
         AND e.display_name=expected.display_name
        LEFT JOIN fc_employee_map em
          ON em.employee_id=e.employee_id
         AND em.company_id=expected.expected_company_id
        GROUP BY expected.employee_number,expected.display_name,
                 expected.expected_company_id
        HAVING COUNT(em.employee_id)<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'named special/default employee mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM fc_named_employee_expectation expected
        LEFT JOIN employee e
          ON e.employee_number=expected.employee_number
         AND e.display_name=expected.display_name
        LEFT JOIN employment_assignment assignment
          ON assignment.employee_id=e.employee_id
         AND assignment.current_version_marker=1
         AND assignment.record_status='ACTIVE'
        LEFT JOIN organization_current_projection projection
          ON projection.organization_id=assignment.organization_id
        LEFT JOIN organization_version version
          ON version.organization_version_id=projection.current_version_id
         AND version.name=expected.expected_organization_name
        WHERE expected.special_code='DALIAN'
        GROUP BY expected.employee_number,expected.display_name,
                 expected.expected_organization_name
        HAVING COUNT(version.organization_version_id)<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'named Dalian employee organization mapping changed';
    END IF;

    CREATE TEMPORARY TABLE fc_special_employee_map (
        employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
        special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO fc_special_employee_map
    SELECT em.employee_id,expected.special_code
    FROM fc_named_employee_expectation expected
    JOIN employee e
      ON e.employee_number=expected.employee_number
     AND e.display_name=expected.display_name
    JOIN fc_employee_map em
      ON em.employee_id=e.employee_id
     AND em.company_id=expected.expected_company_id
    WHERE expected.special_code IS NOT NULL;

    IF (SELECT COUNT(*) FROM fc_special_employee_map)<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance employee mapping changed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_special_map special
        LEFT JOIN fc_special_employee_map employee_map
          ON employee_map.special_code=special.special_code
        GROUP BY special.special_code,special.expected_employees
        HAVING COUNT(employee_map.employee_id)<>special.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance employee counts changed';
    END IF;

    -- No populated composite company graph can be safely re-keyed under the
    -- current NO ACTION foreign keys. This approved database has none.
    IF EXISTS (SELECT 1 FROM employment_period_identity)
       OR EXISTS (SELECT 1 FROM annual_leave_entitlement_projection)
       OR EXISTS (SELECT 1 FROM leave_request)
       OR EXISTS (SELECT 1 FROM time_account)
       OR EXISTS (SELECT 1 FROM attendance_evidence_subject_lock)
       OR EXISTS (SELECT 1 FROM attendance_feedback)
       OR EXISTS (SELECT 1 FROM attendance_source)
       OR EXISTS (SELECT 1 FROM raw_attendance_fact)
       OR EXISTS (SELECT 1 FROM effective_attendance_event)
       OR EXISTS (SELECT 1 FROM evidence_interval_slice)
       OR EXISTS (SELECT 1 FROM duplicate_review_group)
       OR EXISTS (SELECT 1 FROM attendance_recalculation_intent)
       OR EXISTS (SELECT 1 FROM punch_import_batch)
       OR EXISTS (SELECT 1 FROM attendance_report_projection)
       OR EXISTS (SELECT 1 FROM attendance_close_snapshot) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-dependent business facts exist; dedicated graph migration required';
    END IF;

    IF (SELECT COUNT(*) FROM people_import_batch
        WHERE company_id = archive_company_id AND status = 'PUBLISHED') <> 3
       OR (SELECT COUNT(*) FROM people_import_publication
           WHERE company_id = archive_company_id) <> 3
       OR (SELECT COUNT(*) FROM people_import_file) <> 3
       OR (SELECT COUNT(*) FROM people_import_diff) <> 1386
       OR (SELECT COUNT(*) FROM people_import_issue) <> 33 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'mixed historical import archive shape changed';
    END IF;

    SELECT l.location_id, lr.location_revision_id
      INTO source_location_id, source_location_revision_id
    FROM location l JOIN location_revision lr ON lr.location_id = l.location_id
    WHERE l.company_id = archive_company_id
      AND l.location_code = 'DEFAULT_SHANGHAI'
    ORDER BY lr.revision_number DESC LIMIT 1 FOR UPDATE;
    SELECT shift_template_id INTO source_shift_template_id
    FROM shift_template WHERE company_id = archive_company_id
      AND template_code = 'STANDARD_SEASONAL' FOR UPDATE;
    SELECT work_calendar_id INTO source_calendar_id
    FROM work_calendar WHERE company_id = archive_company_id
      AND calendar_code = 'STANDARD_2026' FOR UPDATE;
    SELECT g.attendance_group_id, r.attendance_group_revision_id
      INTO source_group_id, source_group_revision_id
    FROM attendance_group g JOIN attendance_group_revision r
      ON r.attendance_group_id = g.attendance_group_id
    WHERE g.company_id = archive_company_id
      AND g.group_code = 'DEFAULT_ATTENDANCE'
    ORDER BY r.revision_number DESC LIMIT 1 FOR UPDATE;

    IF source_location_id IS NULL OR source_shift_template_id IS NULL
       OR source_calendar_id IS NULL OR source_group_id IS NULL
       OR (SELECT COUNT(*) FROM location_revision
           WHERE location_id = source_location_id) <> 1
       OR (SELECT COUNT(*) FROM location_timeline
           WHERE location_id = source_location_id
             AND event_sequence = 1 AND state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*) FROM shift_version
           WHERE shift_template_id = source_shift_template_id) <> 3
       OR (SELECT COUNT(*) FROM shift_publication_timeline
           WHERE shift_template_id = source_shift_template_id
             AND state = 'PUBLISHED') <> 3
       OR (SELECT COUNT(*) FROM shift_seasonal_schedule
           WHERE shift_template_id = source_shift_template_id
             AND schedule_year = 2026) <> 1
       OR (SELECT COUNT(*) FROM shift_seasonal_schedule_revision revision
           JOIN shift_seasonal_schedule schedule
             ON schedule.shift_seasonal_schedule_id =
                revision.shift_seasonal_schedule_id
           WHERE schedule.shift_template_id = source_shift_template_id) <> 1
       OR (SELECT COUNT(*) FROM work_calendar_version
           WHERE work_calendar_id = source_calendar_id) <> 2
       OR (SELECT COUNT(*) FROM work_calendar_day d
           JOIN work_calendar_version v
             ON v.work_calendar_version_id = d.work_calendar_version_id
           WHERE v.work_calendar_id = source_calendar_id) <> 365
       OR EXISTS (
           SELECT 1 FROM work_calendar_day d
           JOIN work_calendar_version v
             ON v.work_calendar_version_id = d.work_calendar_version_id
           WHERE v.work_calendar_id = source_calendar_id
             AND d.shift_version_override_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM calendar_publication_timeline
           WHERE work_calendar_id = source_calendar_id
             AND state = 'PUBLISHED') <> 1
       OR (SELECT COUNT(*) FROM attendance_group_revision
           WHERE attendance_group_id = source_group_id) <> 1
       OR (SELECT COUNT(*) FROM attendance_group_timeline
           WHERE attendance_group_id = source_group_id
             AND event_sequence = 1 AND state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*) FROM attendance_company_default_provisioning
           WHERE company_id = archive_company_id
             AND provisioning_year = 2026 AND status = 'READY') <> 1
       OR (SELECT COUNT(*) FROM attendance_policy_scope
           WHERE company_id = archive_company_id) <> 8
       OR (SELECT COUNT(*) FROM attendance_policy_scoped_version v
           JOIN attendance_policy_scope s ON s.scope_id = v.scope_id
           WHERE s.company_id = archive_company_id) <> 8
       OR (SELECT COUNT(*) FROM attendance_policy_lifecycle_event e
           JOIN attendance_policy_scope s ON s.scope_id = e.scope_id
           WHERE s.company_id = archive_company_id
             AND e.action = 'PUBLISHED') <> 8
       OR EXISTS (
           SELECT 1 FROM attendance_policy_scoped_version v
           JOIN attendance_policy_scope s ON s.scope_id = v.scope_id
           WHERE s.company_id = archive_company_id
             AND v.rollback_of_scoped_version_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM attendance_policy_binding_family
           WHERE attendance_group_id = source_group_id) <> 3
       OR (SELECT COUNT(*) FROM attendance_policy_binding_revision r
           JOIN attendance_policy_binding_family f
             ON f.binding_family_id = r.binding_family_id
           WHERE f.attendance_group_id = source_group_id) <> 3
       OR EXISTS (
           SELECT 1 FROM attendance_policy_binding_revision r
           JOIN attendance_policy_binding_family f
             ON f.binding_family_id = r.binding_family_id
           WHERE f.attendance_group_id = source_group_id
             AND r.supersedes_binding_revision_id IS NOT NULL
       )
       OR (SELECT COUNT(*) FROM annual_leave_policy_version
           WHERE company_id = archive_company_id
             AND scope_type = 'COMPANY' AND status = 'PUBLISHED') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source attendance/leave baseline changed';
    END IF;
    IF migration_needed AND (
        (SELECT COUNT(*)
         FROM attendance_group_assignment a
         WHERE a.attendance_group_revision_id = source_group_revision_id
           AND a.effective_from = '2026-08-04'
           AND a.supersedes_assignment_id IS NULL) <> 615
        OR (SELECT COUNT(*)
            FROM attendance_assignment_timeline t
            JOIN attendance_group_assignment a
              ON a.attendance_group_assignment_id =
                 t.attendance_group_assignment_id
            WHERE a.attendance_group_revision_id = source_group_revision_id
              AND t.event_sequence = 1 AND t.state = 'ACTIVE'
              AND t.business_effective_from = '2026-08-04') <> 615
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'source employee attendance assignments changed';
    END IF;

    IF migration_needed THEN
        -- Create four fresh business boundaries; retain all mixed imports and
        -- old defaults on a clearly named inactive archive boundary.
        UPDATE company
        SET code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE',
            name = '历史导入归档（原四公司混合数据）',
            status = 'INACTIVE'
        WHERE company_id = archive_company_id;

        INSERT INTO company (company_id, code, name, status, created_at)
        SELECT company_id, company_code, company_name, 'ACTIVE', cutover_at
        FROM fc_company_map ORDER BY company_id;

        -- Version the four root records instead of rewriting imported history.
        UPDATE organization_version v
        JOIN fc_company_map m ON m.root_id = v.organization_id
        SET v.effective_to = cutover_at,
            v.row_version = v.row_version + 1,
            v.change_reason = '2026-08-05 四公司维度切换：关闭导入根版本'
        WHERE v.current_marker = 1;

        INSERT INTO organization_version (
            organization_version_id, organization_id, parent_organization_id,
            code, name, org_type, effective_from, effective_to,
            formal_sync_batch_id, row_version, status, source_authority,
            source_import_batch_id, change_reason, created_by, created_at
        )
        SELECT m.root_version_id, old.organization_id, NULL,
               old.code, old.name, 'COMPANY', cutover_at, NULL,
               old.formal_sync_batch_id, old.row_version + 1, 'ACTIVE', 'LOCAL',
               NULL, '2026-08-05 四公司维度切换：独立公司根',
               bootstrap_actor_id, cutover_at
        FROM fc_company_map m
        JOIN organization_version old ON old.organization_id = m.root_id
         AND old.effective_to = cutover_at;

        UPDATE organization_current_projection p
        JOIN fc_company_map m ON m.root_id = p.organization_id
        SET p.current_version_id = m.root_version_id,
            p.projection_batch_id = '41002000-0000-0000-0000-000000000001',
            p.projected_at = cutover_at;

        UPDATE organization_identity oi
        JOIN fc_org_map om ON om.organization_id = oi.organization_id
        SET oi.company_id = om.company_id;

        UPDATE employee e JOIN fc_employee_map em ON em.employee_id = e.employee_id
        SET e.company_id = em.company_id,
            e.row_version = e.row_version + 1,
            e.updated_at = cutover_at;

        -- Human-readable company-specific locations.
        INSERT INTO location (
            location_id, company_id, location_code, row_version, created_by, created_at
        )
        SELECT location_id, company_id, 'DEFAULT_LOCATION', 0,
               bootstrap_actor_id, cutover_at
        FROM fc_company_map;

        INSERT INTO location_revision (
            location_revision_id, location_id, revision_number, location_name,
            time_zone, effective_from, supersedes_location_revision_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT m.location_revision_id, m.location_id, 1,
               '扬州', 'Asia/Shanghai', '2026-01-01', NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.company_id,
                           '|DEFAULT_LOCATION|扬州|Asia/Shanghai|2026-01-01|NULL'), 256),
               '2026-08-05 四公司默认地点初始化', bootstrap_actor_id, cutover_at
        FROM fc_company_map m;

        INSERT INTO location_timeline (
            location_timeline_id, location_id, location_revision_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT location_timeline_id, location_id, location_revision_id,
               1, 'ACTIVE', '2026-01-01', NULL, cutover_at,
               bootstrap_actor_id, CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-LOCATION-', company_id)
        FROM fc_company_map;

        INSERT INTO location (
            location_id,company_id,location_code,row_version,created_by,created_at
        )
        SELECT location_id,company_id,city_code,0,bootstrap_actor_id,cutover_at
        FROM fc_city_map;

        INSERT INTO location_revision (
            location_revision_id,location_id,revision_number,location_name,
            time_zone,effective_from,supersedes_location_revision_id,
            snapshot_digest,change_reason,created_by,created_at
        )
        SELECT location_revision_id,location_id,1,city_name,'Asia/Shanghai',
               '2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|',city_code,'|',city_name,
                           '|Asia/Shanghai|2026-01-01|NULL'),256),
               '2026-08-05 城市地点预置',bootstrap_actor_id,cutover_at
        FROM fc_city_map;

        INSERT INTO location_timeline (
            location_timeline_id,location_id,location_revision_id,
            event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT location_timeline_id,location_id,location_revision_id,
               1,'ACTIVE','2026-01-01',NULL,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-CITY-',SUBSTR(MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|',city_code)),1,32))
        FROM fc_city_map;

        INSERT INTO shift_template (
            shift_template_id, company_id, location_id, template_code,
            created_by, created_at
        )
        SELECT shift_template_id, company_id, location_id, 'STANDARD_SEASONAL',
               bootstrap_actor_id, cutover_at
        FROM fc_company_map;

        CREATE TEMPORARY TABLE fc_shift_version_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY (company_id, source_version_id),
            UNIQUE KEY (target_version_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_shift_version_map
        SELECT m.company_id, sv.shift_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
        FROM fc_company_map m
        JOIN shift_version sv ON sv.shift_template_id = source_shift_template_id
        CROSS JOIN LATERAL (
            SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-version|',m.company_id,
                              '|',sv.shift_version_id)) h
        ) digest_seed;

        CREATE TEMPORARY TABLE fc_shift_version_predecessor_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_predecessor_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_summer_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_summer_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_winter_h2_map
            LIKE fc_shift_version_map;
        INSERT INTO fc_shift_version_winter_h2_map
        SELECT * FROM fc_shift_version_map;

        CREATE TEMPORARY TABLE fc_shift_version_stage ENGINE=InnoDB AS
        SELECT vm.target_version_id AS shift_version_id,
               m.shift_template_id AS shift_template_id, sv.version_number,
               sv.effective_from, sv.time_zone_snapshot, sv.segments_json,
               predecessor.target_version_id AS supersedes_shift_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                   'S:17:shift-snapshot-v1',
                   'S:36:',m.shift_template_id,
                   'S:36:',vm.target_version_id,
                   'S:',OCTET_LENGTH(sv.version_number),':',sv.version_number,
                   'S:10:',sv.effective_from,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'S:10:',JSON_UNQUOTE(JSON_EXTRACT(sv.segments_json,'$.effectiveTo'))),
                   'S:',OCTET_LENGTH(sv.time_zone_snapshot),':',sv.time_zone_snapshot,
                   'S:',OCTET_LENGTH(seg.segment_count),':',seg.segment_count,
                   seg.segment_body), 256) AS snapshot_digest,
               '2026-08-05 四公司标准冬夏令班次初始化' AS change_reason,
               bootstrap_actor_id AS created_by, cutover_at AS created_at
        FROM fc_company_map m
        JOIN fc_shift_version_map vm ON vm.company_id = m.company_id
        JOIN shift_version sv ON sv.shift_version_id = vm.source_version_id
        LEFT JOIN fc_shift_version_predecessor_map predecessor
          ON predecessor.company_id = m.company_id
         AND predecessor.source_version_id = sv.supersedes_shift_version_id
        JOIN (
            SELECT source.shift_version_id, COUNT(*) segment_count,
                   GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       'S:',OCTET_LENGTH(j.segment_type),':',j.segment_type,
                       'S:',OCTET_LENGTH(TIME_FORMAT(TIME(j.start_time),'%H:%i')),
                           ':',TIME_FORMAT(TIME(j.start_time),'%H:%i'),
                       'S:',OCTET_LENGTH(j.start_offset),':',j.start_offset,
                       'S:',OCTET_LENGTH(TIME_FORMAT(TIME(j.end_time),'%H:%i')),
                           ':',TIME_FORMAT(TIME(j.end_time),'%H:%i'),
                       'S:',OCTET_LENGTH(j.end_offset),':',j.end_offset)
                       ORDER BY j.ord SEPARATOR '') segment_body
            FROM shift_version source
            JOIN JSON_TABLE(source.segments_json, '$.segments[*]' COLUMNS(
                ord FOR ORDINALITY,
                segment_type VARCHAR(32) PATH '$.segmentType',
                start_time VARCHAR(16) PATH '$.startLocalTime',
                start_offset INT PATH '$.startDayOffset',
                end_time VARCHAR(16) PATH '$.endLocalTime',
                end_offset INT PATH '$.endDayOffset'
            )) AS j ON TRUE
            WHERE source.shift_template_id = source_shift_template_id
            GROUP BY source.shift_version_id
        ) seg ON seg.shift_version_id = sv.shift_version_id;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT shift_version_id, shift_template_id, version_number, effective_from,
               time_zone_snapshot, segments_json, supersedes_shift_version_id,
               snapshot_digest, change_reason, created_by, created_at
        FROM fc_shift_version_stage
        WHERE supersedes_shift_version_id IS NULL;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.shift_version_id, staged.shift_template_id,
               staged.version_number, staged.effective_from,
               staged.time_zone_snapshot, staged.segments_json,
               staged.supersedes_shift_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_shift_version_stage staged
        JOIN shift_version predecessor
          ON predecessor.shift_version_id=staged.supersedes_shift_version_id
        LEFT JOIN shift_version existing
          ON existing.shift_version_id=staged.shift_version_id
        WHERE existing.shift_version_id IS NULL;

        INSERT INTO shift_version (
            shift_version_id, shift_template_id, version_number, effective_from,
            time_zone_snapshot, segments_json, supersedes_shift_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.shift_version_id, staged.shift_template_id,
               staged.version_number, staged.effective_from,
               staged.time_zone_snapshot, staged.segments_json,
               staged.supersedes_shift_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_shift_version_stage staged
        JOIN shift_version predecessor
          ON predecessor.shift_version_id=staged.supersedes_shift_version_id
        LEFT JOIN shift_version existing
          ON existing.shift_version_id=staged.shift_version_id
        WHERE existing.shift_version_id IS NULL;

        CREATE TEMPORARY TABLE fc_shift_timeline_stage ENGINE=InnoDB AS
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
                   AS shift_publication_timeline_id,
               m.shift_template_id AS shift_template_id,
               vm.target_version_id AS shift_version_id,
               source.event_sequence,
               source.state, source.business_effective_from,
               CASE WHEN source.predecessor_timeline_id IS NULL THEN NULL ELSE
                    LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(ph,1,8),'-',SUBSTR(ph,9,4),'-',SUBSTR(ph,13,4),
                                 '-',SUBSTR(ph,17,4),'-',SUBSTR(ph,21,12))) END
                   AS predecessor_timeline_id,
               cutover_at AS recorded_at, bootstrap_actor_id AS actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-SHIFT-',m.company_id,'-',source.event_sequence) AS request_id
        FROM fc_company_map m
        JOIN shift_publication_timeline source
          ON source.shift_template_id = source_shift_template_id
        JOIN fc_shift_version_map vm ON vm.company_id=m.company_id
         AND vm.source_version_id=source.shift_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-timeline|',
            m.company_id,'|',source.shift_publication_timeline_id)) h) a
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|shift-timeline|',
            m.company_id,'|',source.predecessor_timeline_id)) ph) b;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT shift_publication_timeline_id, shift_template_id, shift_version_id,
               event_sequence, state, business_effective_from,
               predecessor_timeline_id, recorded_at, actor_id, request_id
        FROM fc_shift_timeline_stage
        WHERE predecessor_timeline_id IS NULL;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT staged.shift_publication_timeline_id, staged.shift_template_id,
               staged.shift_version_id, staged.event_sequence, staged.state,
               staged.business_effective_from, staged.predecessor_timeline_id,
               staged.recorded_at, staged.actor_id, staged.request_id
        FROM fc_shift_timeline_stage staged
        JOIN shift_publication_timeline predecessor
          ON predecessor.shift_publication_timeline_id=staged.predecessor_timeline_id
        LEFT JOIN shift_publication_timeline existing
          ON existing.shift_publication_timeline_id=staged.shift_publication_timeline_id
        WHERE existing.shift_publication_timeline_id IS NULL;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id, shift_template_id, shift_version_id,
            event_sequence, state, business_effective_from,
            predecessor_timeline_id, recorded_at, actor_id, request_id
        )
        SELECT staged.shift_publication_timeline_id, staged.shift_template_id,
               staged.shift_version_id, staged.event_sequence, staged.state,
               staged.business_effective_from, staged.predecessor_timeline_id,
               staged.recorded_at, staged.actor_id, staged.request_id
        FROM fc_shift_timeline_stage staged
        JOIN shift_publication_timeline predecessor
          ON predecessor.shift_publication_timeline_id=staged.predecessor_timeline_id
        LEFT JOIN shift_publication_timeline existing
          ON existing.shift_publication_timeline_id=staged.shift_publication_timeline_id
        WHERE existing.shift_publication_timeline_id IS NULL;

        INSERT INTO shift_seasonal_schedule (
            shift_seasonal_schedule_id, shift_template_id, schedule_year,
            summer_effective_from, winter_effective_from,
            winter_h1_version_id, summer_version_id, winter_h2_version_id,
            schedule_digest, revision_number, row_version, change_reason,
            created_by, created_at, updated_by, updated_at
        )
        SELECT m.seasonal_schedule_id, m.shift_template_id, source.schedule_year,
               source.summer_effective_from, source.winter_effective_from,
               v1.target_version_id, v2.target_version_id, v3.target_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.shift_template_id,'|',source.schedule_year,'|',
                           source.summer_effective_from,'|',source.winter_effective_from,
                           '|',v1.target_version_id,'|',v2.target_version_id,'|',
                           v3.target_version_id),256),
               1, 0, '2026-08-05 四公司标准冬夏令计划初始化',
               bootstrap_actor_id, cutover_at, bootstrap_actor_id, cutover_at
        FROM fc_company_map m
        JOIN shift_seasonal_schedule source
          ON source.shift_template_id = source_shift_template_id
        JOIN fc_shift_version_map v1 ON v1.company_id=m.company_id
         AND v1.source_version_id=source.winter_h1_version_id
        JOIN fc_shift_version_summer_map v2 ON v2.company_id=m.company_id
         AND v2.source_version_id=source.summer_version_id
        JOIN fc_shift_version_winter_h2_map v3 ON v3.company_id=m.company_id
         AND v3.source_version_id=source.winter_h2_version_id;

        INSERT INTO shift_seasonal_schedule_revision (
            shift_seasonal_schedule_revision_id, shift_seasonal_schedule_id,
            revision_number, winter_h1_version_id, summer_version_id,
            winter_h2_version_id, schedule_digest, change_reason, actor_id, recorded_at
        )
        SELECT m.seasonal_revision_id, s.shift_seasonal_schedule_id, 1,
               s.winter_h1_version_id, s.summer_version_id, s.winter_h2_version_id,
               s.schedule_digest, s.change_reason, bootstrap_actor_id, cutover_at
        FROM fc_company_map m JOIN shift_seasonal_schedule s
          ON s.shift_seasonal_schedule_id=m.seasonal_schedule_id;

        INSERT INTO work_calendar (
            work_calendar_id, company_id, location_id, calendar_code,
            created_by, created_at
        )
        SELECT calendar_id, company_id, location_id, 'STANDARD_2026',
               bootstrap_actor_id, cutover_at FROM fc_company_map;

        CREATE TEMPORARY TABLE fc_calendar_version_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY (company_id, source_version_id), UNIQUE KEY(target_version_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_calendar_version_map
        SELECT m.company_id, v.work_calendar_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12)))
        FROM fc_company_map m
        JOIN work_calendar_version v ON v.work_calendar_id=source_calendar_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-version|',
            m.company_id,'|',v.work_calendar_version_id)) h) d;

        CREATE TEMPORARY TABLE fc_calendar_version_predecessor_map
            LIKE fc_calendar_version_map;
        INSERT INTO fc_calendar_version_predecessor_map
        SELECT * FROM fc_calendar_version_map;

        CREATE TEMPORARY TABLE fc_calendar_version_stage ENGINE=InnoDB AS
        SELECT vm.target_version_id AS work_calendar_version_id,
               m.calendar_id AS work_calendar_id, source.version_number,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历') AS calendar_name,
               source.calendar_year,
               source.time_zone_snapshot, source.effective_from, source.effective_to,
               predecessor.target_version_id AS supersedes_work_calendar_version_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                  'S:',OCTET_LENGTH('work-calendar-snapshot-v1'),':work-calendar-snapshot-v1',
                  'S:36:',m.company_id,'S:36:',m.location_id,
                  'S:36:',m.calendar_id,'S:36:',vm.target_version_id,
                  'S:',OCTET_LENGTH(source.version_number),':',source.version_number,
                  'S:13:STANDARD_2026',
                  'S:',OCTET_LENGTH(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历')),':',
                      CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历'),
                  'S:',OCTET_LENGTH(source.calendar_year),':',source.calendar_year,
                  'S:',OCTET_LENGTH(source.time_zone_snapshot),':',source.time_zone_snapshot,
                  'S:10:',source.effective_from,'S:10:',source.effective_to,
                  'S:',OCTET_LENGTH(days.day_count),':',days.day_count,days.day_body),256)
                   AS snapshot_digest,
               '2026-08-05 四公司2026工作日历初始化' AS change_reason,
               bootstrap_actor_id AS created_by, cutover_at AS created_at
        FROM fc_company_map m
        JOIN fc_calendar_version_map vm ON vm.company_id=m.company_id
        JOIN work_calendar_version source
          ON source.work_calendar_version_id=vm.source_version_id
        LEFT JOIN fc_calendar_version_predecessor_map predecessor
          ON predecessor.company_id=m.company_id
         AND predecessor.source_version_id=source.supersedes_work_calendar_version_id
        JOIN (
            SELECT v.work_calendar_version_id,
                   COUNT(d.work_calendar_day_id) day_count,
                   COALESCE(GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                     'S:10:',d.business_date,
                     'S:',OCTET_LENGTH(d.day_type),':',d.day_type,
                     'N:0:') ORDER BY d.business_date,d.day_type SEPARATOR ''),'') day_body
            FROM work_calendar_version v
            LEFT JOIN work_calendar_day d
              ON d.work_calendar_version_id=v.work_calendar_version_id
            WHERE v.work_calendar_id=source_calendar_id
            GROUP BY v.work_calendar_version_id
        ) days ON days.work_calendar_version_id=source.work_calendar_version_id;

        INSERT INTO work_calendar_version (
            work_calendar_version_id, work_calendar_id, version_number,
            calendar_name, calendar_year, time_zone_snapshot,
            effective_from, effective_to, supersedes_work_calendar_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT work_calendar_version_id, work_calendar_id, version_number,
               calendar_name, calendar_year, time_zone_snapshot,
               effective_from, effective_to, supersedes_work_calendar_version_id,
               snapshot_digest, change_reason, created_by, created_at
        FROM fc_calendar_version_stage
        WHERE supersedes_work_calendar_version_id IS NULL;

        INSERT INTO work_calendar_version (
            work_calendar_version_id, work_calendar_id, version_number,
            calendar_name, calendar_year, time_zone_snapshot,
            effective_from, effective_to, supersedes_work_calendar_version_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT staged.work_calendar_version_id, staged.work_calendar_id,
               staged.version_number, staged.calendar_name, staged.calendar_year,
               staged.time_zone_snapshot, staged.effective_from, staged.effective_to,
               staged.supersedes_work_calendar_version_id, staged.snapshot_digest,
               staged.change_reason, staged.created_by, staged.created_at
        FROM fc_calendar_version_stage staged
        JOIN work_calendar_version predecessor
          ON predecessor.work_calendar_version_id=
             staged.supersedes_work_calendar_version_id
        LEFT JOIN work_calendar_version existing
          ON existing.work_calendar_version_id=staged.work_calendar_version_id
        WHERE existing.work_calendar_version_id IS NULL;

        INSERT INTO work_calendar_day (
            work_calendar_day_id, work_calendar_version_id, business_date,
            day_type, shift_version_override_id, snapshot_digest, created_by, created_at
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               vm.target_version_id, d.business_date, d.day_type, NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,vm.target_version_id,'|',d.business_date,'|',d.day_type,'|NULL'),256),
               bootstrap_actor_id, cutover_at
        FROM fc_calendar_version_map vm
        JOIN work_calendar_day d ON d.work_calendar_version_id=vm.source_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-day|',
            vm.company_id,'|',d.work_calendar_day_id)) h) seed;

        INSERT INTO calendar_publication_timeline (
            calendar_publication_timeline_id, work_calendar_id,
            work_calendar_version_id, event_sequence, state,
            business_effective_from, predecessor_timeline_id,
            recorded_at, actor_id, request_id
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               m.calendar_id, vm.target_version_id, source.event_sequence,
               source.state, source.business_effective_from, NULL,
               cutover_at, bootstrap_actor_id, CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-CALENDAR-',m.company_id)
        FROM fc_company_map m
        JOIN calendar_publication_timeline source
          ON source.work_calendar_id=source_calendar_id
        JOIN fc_calendar_version_map vm ON vm.company_id=m.company_id
         AND vm.source_version_id=source.work_calendar_version_id
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|calendar-timeline|',
            m.company_id,'|',source.calendar_publication_timeline_id)) h) seed;

        INSERT INTO shift_template (
            shift_template_id,company_id,location_id,template_code,created_by,created_at
        )
        SELECT special.shift_template_id,
               '41000000-0000-0000-0000-000000000003',city.location_id,
               special.shift_code,bootstrap_actor_id,cutover_at
        FROM fc_special_map special
        JOIN fc_city_map city
          ON city.company_id='41000000-0000-0000-0000-000000000003'
         AND city.city_code=special.special_code;

        CREATE TEMPORARY TABLE fc_special_shift_raw (
            shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
            special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            shift_template_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            version_number INT NOT NULL,
            effective_from DATE NOT NULL,
            segments_json JSON NOT NULL
        ) ENGINE=InnoDB;
        INSERT INTO fc_special_shift_raw VALUES
          ('42021000-0000-0000-0000-000000000001','DALIAN',
           '42020000-0000-0000-0000-000000000001',1,'2026-01-01',
           CAST('{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"07:30:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"16:30:00","endDayOffset":0}]}' AS JSON)),
          ('42021000-0000-0000-0000-000000000002','CHENGDU',
           '42020000-0000-0000-0000-000000000002',1,'2026-01-01',
           CAST('{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"09:00:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"18:00:00","endDayOffset":0}]}' AS JSON));
        INSERT INTO fc_special_shift_raw
        SELECT CASE source.version_number
                   WHEN 1 THEN '42021000-0000-0000-0000-000000000003'
                   WHEN 2 THEN '42021000-0000-0000-0000-000000000004'
                   WHEN 3 THEN '42021000-0000-0000-0000-000000000005'
               END,
               'SHANGHAI','42020000-0000-0000-0000-000000000003',
               source.version_number,source.effective_from,source.segments_json
        FROM shift_version source
        WHERE source.shift_template_id='41020000-0000-0000-0000-000000000003';

        CREATE TEMPORARY TABLE fc_special_shift_raw_copy LIKE fc_special_shift_raw;
        INSERT INTO fc_special_shift_raw_copy SELECT * FROM fc_special_shift_raw;

        CREATE TEMPORARY TABLE fc_special_shift_stage ENGINE=InnoDB AS
        SELECT raw.shift_version_id,raw.shift_template_id,raw.version_number,
               raw.effective_from,'Asia/Shanghai' AS time_zone_snapshot,
               raw.segments_json,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                   'S:17:shift-snapshot-v1','S:36:',raw.shift_template_id,
                   'S:36:',raw.shift_version_id,
                   'S:',OCTET_LENGTH(raw.version_number),':',raw.version_number,
                   'S:10:',raw.effective_from,
                   'S:10:',JSON_UNQUOTE(JSON_EXTRACT(raw.segments_json,'$.effectiveTo')),
                   'S:13:Asia/Shanghai',
                   'S:',OCTET_LENGTH(segments.segment_count),':',segments.segment_count,
                   segments.segment_body),256) AS snapshot_digest
        FROM fc_special_shift_raw raw
        JOIN (
            SELECT source.shift_version_id,COUNT(*) segment_count,
                   GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       'S:',OCTET_LENGTH(segment.segment_type),':',segment.segment_type,
                       'S:5:',TIME_FORMAT(TIME(segment.start_time),'%H:%i'),
                       'S:',OCTET_LENGTH(segment.start_offset),':',segment.start_offset,
                       'S:5:',TIME_FORMAT(TIME(segment.end_time),'%H:%i'),
                       'S:',OCTET_LENGTH(segment.end_offset),':',segment.end_offset)
                       ORDER BY segment.ord SEPARATOR '') segment_body
            FROM fc_special_shift_raw_copy source
            JOIN JSON_TABLE(source.segments_json,'$.segments[*]' COLUMNS(
                ord FOR ORDINALITY,
                segment_type VARCHAR(32) PATH '$.segmentType',
                start_time VARCHAR(16) PATH '$.startLocalTime',
                start_offset INT PATH '$.startDayOffset',
                end_time VARCHAR(16) PATH '$.endLocalTime',
                end_offset INT PATH '$.endDayOffset'
            )) segment ON TRUE
            GROUP BY source.shift_version_id
        ) segments ON segments.shift_version_id=raw.shift_version_id;

        INSERT INTO shift_version (
            shift_version_id,shift_template_id,version_number,effective_from,
            time_zone_snapshot,segments_json,supersedes_shift_version_id,
            snapshot_digest,change_reason,created_by,created_at
        )
        SELECT shift_version_id,shift_template_id,version_number,effective_from,
               time_zone_snapshot,segments_json,NULL,snapshot_digest,
               '2026-08-05 城市班次初始化',bootstrap_actor_id,cutover_at
        FROM fc_special_shift_stage;

        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000001',
           '42020000-0000-0000-0000-000000000001',
           '42021000-0000-0000-0000-000000000001',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-DALIAN-1'),
          ('42024000-0000-0000-0000-000000000002',
           '42020000-0000-0000-0000-000000000002',
           '42021000-0000-0000-0000-000000000002',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-CHENGDU-1'),
          ('42024000-0000-0000-0000-000000000003',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000003',1,'PUBLISHED','2026-01-01',NULL,
           cutover_at,bootstrap_actor_id,'FCF-SHIFT-SHANGHAI-1');
        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000004',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000004',2,'PUBLISHED','2026-05-01',
           '42024000-0000-0000-0000-000000000003',cutover_at,bootstrap_actor_id,
           'FCF-SHIFT-SHANGHAI-2');
        INSERT INTO shift_publication_timeline (
            shift_publication_timeline_id,shift_template_id,shift_version_id,
            event_sequence,state,business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        ) VALUES
          ('42024000-0000-0000-0000-000000000005',
           '42020000-0000-0000-0000-000000000003',
           '42021000-0000-0000-0000-000000000005',3,'PUBLISHED','2026-10-01',
           '42024000-0000-0000-0000-000000000004',cutover_at,bootstrap_actor_id,
           'FCF-SHIFT-SHANGHAI-3');

        INSERT INTO shift_seasonal_schedule (
            shift_seasonal_schedule_id,shift_template_id,schedule_year,
            summer_effective_from,winter_effective_from,winter_h1_version_id,
            summer_version_id,winter_h2_version_id,schedule_digest,
            revision_number,row_version,change_reason,created_by,created_at,
            updated_by,updated_at
        ) VALUES (
            '42022000-0000-0000-0000-000000000003',
            '42020000-0000-0000-0000-000000000003',2026,'2026-05-01','2026-10-01',
            '42021000-0000-0000-0000-000000000003',
            '42021000-0000-0000-0000-000000000004',
            '42021000-0000-0000-0000-000000000005',
            SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'42020000-0000-0000-0000-000000000003|2026|2026-05-01|2026-10-01|',
                        '42021000-0000-0000-0000-000000000003|',
                        '42021000-0000-0000-0000-000000000004|',
                        '42021000-0000-0000-0000-000000000005'),256),
            1,0,'2026-08-05 上海复制扬州冬夏令班次',bootstrap_actor_id,cutover_at,
            bootstrap_actor_id,cutover_at);
        INSERT INTO shift_seasonal_schedule_revision (
            shift_seasonal_schedule_revision_id,shift_seasonal_schedule_id,
            revision_number,winter_h1_version_id,summer_version_id,
            winter_h2_version_id,schedule_digest,change_reason,actor_id,recorded_at
        )
        SELECT '42023000-0000-0000-0000-000000000003',
               shift_seasonal_schedule_id,1,winter_h1_version_id,summer_version_id,
               winter_h2_version_id,schedule_digest,change_reason,
               bootstrap_actor_id,cutover_at
        FROM shift_seasonal_schedule
        WHERE shift_seasonal_schedule_id='42022000-0000-0000-0000-000000000003';

        INSERT INTO attendance_group (
            attendance_group_id, company_id, group_code, created_by, created_at
        )
        SELECT group_id,company_id,'DEFAULT_ATTENDANCE',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        INSERT INTO attendance_group_revision (
            attendance_group_revision_id, attendance_group_id, revision_number,
            group_name, location_revision_id, work_calendar_id, shift_template_id,
            effective_from, supersedes_attendance_group_revision_id,
            snapshot_digest, change_reason, created_by, created_at
        )
        SELECT group_revision_id,group_id,1,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,short_name,'默认考勤组'),
               location_revision_id,calendar_id,shift_template_id,'2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|DEFAULT_ATTENDANCE|',short_name,
                    '默认考勤组|',location_revision_id,'|Asia/Shanghai|',calendar_id,
                    '|',shift_template_id,'|2026-01-01|NULL'),256),
               '2026-08-05 四公司默认考勤组初始化',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        INSERT INTO attendance_group_timeline (
            attendance_group_timeline_id,attendance_group_id,
            attendance_group_revision_id,event_sequence,state,
            business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        )
        SELECT group_timeline_id,group_id,group_revision_id,1,'ACTIVE','2026-01-01',
               NULL,cutover_at,bootstrap_actor_id,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-GROUP-',company_id)
        FROM fc_company_map;

        INSERT INTO attendance_group (
            attendance_group_id,company_id,group_code,created_by,created_at
        )
        SELECT group_id,'41000000-0000-0000-0000-000000000003',group_code,
               bootstrap_actor_id,cutover_at
        FROM fc_special_map;

        INSERT INTO attendance_group_revision (
            attendance_group_revision_id,attendance_group_id,revision_number,
            group_name,location_revision_id,work_calendar_id,shift_template_id,
            effective_from,supersedes_attendance_group_revision_id,snapshot_digest,
            change_reason,created_by,created_at
        )
        SELECT special.group_revision_id,special.group_id,1,special.group_name,
               city.location_revision_id,
               '41030000-0000-0000-0000-000000000003',special.shift_template_id,
               '2026-01-01',NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'41000000-0000-0000-0000-000000000003|',
                           special.group_code,'|',special.group_name,'|',
                           city.location_revision_id,'|Asia/Shanghai|',
                           '41030000-0000-0000-0000-000000000003|',
                           special.shift_template_id,'|2026-01-01|NULL'),256),
               '2026-08-05 城市考勤组初始化',bootstrap_actor_id,cutover_at
        FROM fc_special_map special
        JOIN fc_city_map city
          ON city.company_id='41000000-0000-0000-0000-000000000003'
         AND city.city_code=special.special_code;

        INSERT INTO attendance_group_timeline (
            attendance_group_timeline_id,attendance_group_id,
            attendance_group_revision_id,event_sequence,state,
            business_effective_from,predecessor_timeline_id,
            recorded_at,actor_id,request_id
        )
        SELECT group_timeline_id,group_id,group_revision_id,1,'ACTIVE','2026-01-01',
               NULL,cutover_at,bootstrap_actor_id,CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-GROUP-',special_code)
        FROM fc_special_map;

        CREATE TEMPORARY TABLE fc_policy_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            template_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_scope_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            source_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_lifecycle_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(company_id,template_code),
            UNIQUE KEY(target_scope_id), UNIQUE KEY(target_version_id),
            UNIQUE KEY(target_lifecycle_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_policy_map
        SELECT m.company_id,t.template_code,s.scope_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               v.scoped_version_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h3,1,8),'-',SUBSTR(h3,9,4),'-',SUBSTR(h3,13,4),
                            '-',SUBSTR(h3,17,4),'-',SUBSTR(h3,21,12)))
        FROM fc_company_map m
        JOIN attendance_policy_scope s ON s.company_id=archive_company_id
        JOIN attendance_policy_template t ON t.policy_template_id=s.policy_template_id
        JOIN attendance_policy_scoped_version v ON v.scope_id=s.scope_id
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-scope|',m.company_id,'|',t.template_code)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-version|',m.company_id,'|',t.template_code)) h2,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|policy-lifecycle|',m.company_id,'|',t.template_code)) h3
        ) seed;

        INSERT INTO attendance_policy_scope (
            scope_id,policy_template_id,company_id,row_version,created_by,created_at
        )
        SELECT pm.target_scope_id,s.policy_template_id,pm.company_id,0,
               bootstrap_actor_id,cutover_at
        FROM fc_policy_map pm JOIN attendance_policy_scope s
          ON s.scope_id=pm.source_scope_id;

        INSERT INTO attendance_policy_scoped_version (
            scoped_version_id,scope_id,version_number,parameters_json,
            effective_from,effective_to,validation_json,snapshot_json,snapshot_digest,
            rollback_of_scoped_version_id,row_version,change_reason,created_by,created_at
        )
        SELECT pm.target_version_id,pm.target_scope_id,v.version_number,v.parameters_json,
               v.effective_from,v.effective_to,v.validation_json,
               canonical.snapshot_json,
               SHA2(canonical.snapshot_json,256),NULL,0,
               '2026-08-05 四公司考勤规则默认值初始化',
               bootstrap_actor_id,cutover_at
        FROM fc_policy_map pm
        JOIN attendance_policy_scoped_version v
          ON v.scoped_version_id=pm.source_version_id
        JOIN attendance_policy_scope source_scope
          ON source_scope.scope_id=pm.source_scope_id
        JOIN (
            SELECT source.scoped_version_id,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'{',GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       JSON_QUOTE(parameter_key.parameter_key),':',
                       REPLACE(CAST(JSON_EXTRACT(
                           source.parameters_json,
                           CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'$.',parameter_key.parameter_key)
                       ) AS CHAR CHARACTER SET utf8mb4),', ',','))
                       ORDER BY CONVERT(parameter_key.parameter_key USING utf8mb4)
                                COLLATE utf8mb4_bin SEPARATOR ','),'}')
                       AS canonical_parameters
            FROM attendance_policy_scoped_version source
            JOIN JSON_TABLE(
                JSON_KEYS(source.parameters_json),
                '$[*]' COLUMNS(parameter_key VARCHAR(128) PATH '$')
            ) parameter_key ON TRUE
            GROUP BY source.scoped_version_id
        ) parameters ON parameters.scoped_version_id=v.scoped_version_id
        CROSS JOIN LATERAL (
            SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                '{"companyId":',JSON_QUOTE(pm.company_id),
                ',"effectiveFrom":',JSON_QUOTE(DATE_FORMAT(v.effective_from,'%Y-%m-%d')),
                ',"effectiveTo":',IF(v.effective_to IS NULL,'null',
                    JSON_QUOTE(DATE_FORMAT(v.effective_to,'%Y-%m-%d'))),
                ',"parameters":',parameters.canonical_parameters,
                ',"policyKind":',JSON_QUOTE(pm.template_code),
                ',"scopeId":',JSON_QUOTE(pm.target_scope_id),
                ',"templateId":',JSON_QUOTE(source_scope.policy_template_id),
                ',"versionNumber":',v.version_number,'}'
            ) AS snapshot_json
        ) canonical;

        INSERT INTO attendance_policy_lifecycle_event (
            lifecycle_event_id,scope_id,scoped_version_id,event_sequence,action,
            business_effective_from,predecessor_event_id,reason,actor_id,request_id,recorded_at
        )
        SELECT pm.target_lifecycle_id,pm.target_scope_id,pm.target_version_id,1,
               'PUBLISHED',source.business_effective_from,NULL,
               '2026-08-05 四公司考勤规则默认值发布',bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-P-',SUBSTR(MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,pm.company_id,'|',pm.template_code)),1,32)),
               cutover_at
        FROM fc_policy_map pm JOIN attendance_policy_lifecycle_event source
          ON source.scope_id=pm.source_scope_id AND source.action='PUBLISHED';

        CREATE TEMPORARY TABLE fc_binding_map (
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            policy_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            family_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(company_id,policy_kind), UNIQUE KEY(family_id), UNIQUE KEY(revision_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_binding_map
        SELECT m.company_id,f.policy_kind,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12)))
        FROM fc_company_map m
        JOIN attendance_policy_binding_family f
          ON f.attendance_group_id=source_group_id
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|binding-family|',m.company_id,'|',f.policy_kind)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|binding-revision|',m.company_id,'|',f.policy_kind)) h2
        ) seed;

        INSERT INTO attendance_policy_binding_family (
            binding_family_id,attendance_group_id,policy_kind,created_by,created_at
        )
        SELECT b.family_id,m.group_id,b.policy_kind,bootstrap_actor_id,cutover_at
        FROM fc_binding_map b JOIN fc_company_map m ON m.company_id=b.company_id;

        INSERT INTO attendance_policy_binding_revision (
            binding_revision_id,binding_family_id,attendance_group_revision_id,
            attendance_policy_scoped_version_id,revision_number,effective_from,
            supersedes_binding_revision_id,snapshot_digest,change_reason,created_by,created_at
        )
        SELECT b.revision_id,b.family_id,m.group_revision_id,pm.target_version_id,
               1,source.effective_from,NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.group_revision_id,'|',pm.target_version_id,'|',
                           v.snapshot_digest,'|',source.effective_from,'|NULL'),256),
               '2026-08-05 四公司默认考勤规则绑定',bootstrap_actor_id,cutover_at
        FROM fc_binding_map b
        JOIN fc_company_map m ON m.company_id=b.company_id
        JOIN fc_policy_map pm ON pm.company_id=b.company_id
         AND pm.template_code=b.policy_kind
        JOIN attendance_policy_scoped_version v
          ON v.scoped_version_id=pm.target_version_id
        JOIN attendance_policy_binding_family sf
          ON sf.attendance_group_id=source_group_id AND sf.policy_kind=b.policy_kind
        JOIN attendance_policy_binding_revision source
          ON source.binding_family_id=sf.binding_family_id;

        CREATE TEMPORARY TABLE fc_special_binding_map (
            special_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            policy_kind VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            family_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            PRIMARY KEY(special_code,policy_kind),UNIQUE KEY(family_id),
            UNIQUE KEY(revision_id)
        ) ENGINE=InnoDB;
        INSERT INTO fc_special_binding_map
        SELECT special.special_code,source_family.policy_kind,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(family_hash,1,8),'-',SUBSTR(family_hash,9,4),'-',
                            SUBSTR(family_hash,13,4),'-',SUBSTR(family_hash,17,4),'-',
                            SUBSTR(family_hash,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(revision_hash,1,8),'-',SUBSTR(revision_hash,9,4),'-',
                            SUBSTR(revision_hash,13,4),'-',SUBSTR(revision_hash,17,4),'-',
                            SUBSTR(revision_hash,21,12)))
        FROM fc_special_map special
        JOIN attendance_policy_binding_family source_family
          ON source_family.attendance_group_id=source_group_id
        CROSS JOIN LATERAL (SELECT
            MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|special-binding-family|',
                       special.special_code,'|',source_family.policy_kind)) family_hash,
            MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|special-binding-revision|',
                       special.special_code,'|',source_family.policy_kind)) revision_hash
        ) seed;

        INSERT INTO attendance_policy_binding_family (
            binding_family_id,attendance_group_id,policy_kind,created_by,created_at
        )
        SELECT binding.family_id,special.group_id,binding.policy_kind,
               bootstrap_actor_id,cutover_at
        FROM fc_special_binding_map binding
        JOIN fc_special_map special
          ON special.special_code=binding.special_code;

        INSERT INTO attendance_policy_binding_revision (
            binding_revision_id,binding_family_id,attendance_group_revision_id,
            attendance_policy_scoped_version_id,revision_number,effective_from,
            supersedes_binding_revision_id,snapshot_digest,change_reason,created_by,created_at
        )
        SELECT binding.revision_id,binding.family_id,special.group_revision_id,
               policy.target_version_id,1,source_revision.effective_from,NULL,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,special.group_revision_id,'|',policy.target_version_id,'|',
                           target_version.snapshot_digest,'|',
                           source_revision.effective_from,'|NULL'),256),
               '2026-08-05 城市考勤组规则绑定',bootstrap_actor_id,cutover_at
        FROM fc_special_binding_map binding
        JOIN fc_special_map special
          ON special.special_code=binding.special_code
        JOIN fc_policy_map policy
          ON policy.company_id='41000000-0000-0000-0000-000000000003'
         AND policy.template_code=binding.policy_kind
        JOIN attendance_policy_scoped_version target_version
          ON target_version.scoped_version_id=policy.target_version_id
        JOIN attendance_policy_binding_family source_family
          ON source_family.attendance_group_id=source_group_id
         AND source_family.policy_kind=binding.policy_kind
        JOIN attendance_policy_binding_revision source_revision
          ON source_revision.binding_family_id=source_family.binding_family_id;

        INSERT INTO annual_leave_policy_version (
            annual_leave_policy_version_id,version_number,scope_type,company_id,
            based_on_version_id,effective_from,effective_to,qualification_required,
            qualification_months,leap_day_rule,tiers_json,status,snapshot_digest,
            row_version,change_reason,created_by,created_at,published_at
        )
        SELECT m.annual_policy_version_id,v.version_number,'COMPANY',m.company_id,
               v.based_on_version_id,v.effective_from,v.effective_to,
               v.qualification_required,v.qualification_months,v.leap_day_rule,
               v.tiers_json,'PUBLISHED',v.snapshot_digest,0,
               '2026-08-05 四公司年假默认策略初始化',bootstrap_actor_id,cutover_at,cutover_at
        FROM fc_company_map m JOIN annual_leave_policy_version v
          ON v.company_id=archive_company_id AND v.scope_type='COMPANY'
         AND v.status='PUBLISHED';

        INSERT INTO annual_leave_policy_lifecycle_event (
            annual_leave_policy_lifecycle_event_id,annual_leave_policy_version_id,
            event_sequence,action,business_effective_from,reason,actor_id,recorded_at
        )
        SELECT m.annual_lifecycle_id,m.annual_policy_version_id,1,'PUBLISHED',
               source.business_effective_from,'2026-08-05 四公司年假默认策略发布',
               bootstrap_actor_id,cutover_at
        FROM fc_company_map m
        JOIN annual_leave_policy_version old ON old.company_id=archive_company_id
         AND old.scope_type='COMPANY' AND old.status='PUBLISHED'
        JOIN annual_leave_policy_lifecycle_event source
          ON source.annual_leave_policy_version_id=old.annual_leave_policy_version_id
         AND source.action='PUBLISHED';

        INSERT INTO attendance_company_default_provisioning (
            provisioning_id,company_id,provisioning_year,location_id,
            shift_template_id,calendar_id,attendance_group_id,status,
            configuration_digest,change_reason,created_by,created_at
        )
        SELECT provisioning_id,company_id,2026,location_id,shift_template_id,
               calendar_id,group_id,'READY',
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,company_id,'|2026|',location_id,'|',shift_template_id,
                           '|',calendar_id,'|',group_id),256),
               '2026-08-05 四公司完整默认考勤基线',bootstrap_actor_id,cutover_at
        FROM fc_company_map;

        -- Versioned cross-company reassignment. The old imported assignments
        -- remain archive history; successors begin strictly later on cutover.
        IF (SELECT COUNT(*) FROM attendance_group_assignment) <> 615 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;
        IF EXISTS (SELECT 1 FROM attendance_group_assignment
                   WHERE supersedes_assignment_id IS NOT NULL) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;
        IF EXISTS (
            SELECT 1 FROM fc_employee_map em
            LEFT JOIN attendance_group_assignment olda
              ON olda.employee_id=em.employee_id
             AND olda.supersedes_assignment_id IS NULL
            GROUP BY em.employee_id
            HAVING COUNT(olda.attendance_group_assignment_id)<>1
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;

        CREATE TEMPORARY TABLE fc_assignment_map (
            employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
            company_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            target_group_revision_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            old_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            old_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            new_assignment_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            old_inactive_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
            new_active_timeline_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
        ) ENGINE=InnoDB;
        INSERT INTO fc_assignment_map
        SELECT em.employee_id,em.company_id,
               COALESCE(special.group_revision_id,company.group_revision_id),
               olda.attendance_group_assignment_id,
               t.attendance_assignment_timeline_id,
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h1,1,8),'-',SUBSTR(h1,9,4),'-',SUBSTR(h1,13,4),
                            '-',SUBSTR(h1,17,4),'-',SUBSTR(h1,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h2,1,8),'-',SUBSTR(h2,9,4),'-',SUBSTR(h2,13,4),
                            '-',SUBSTR(h2,17,4),'-',SUBSTR(h2,21,12))),
               LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h3,1,8),'-',SUBSTR(h3,9,4),'-',SUBSTR(h3,13,4),
                            '-',SUBSTR(h3,17,4),'-',SUBSTR(h3,21,12)))
        FROM fc_employee_map em
        JOIN fc_company_map company ON company.company_id=em.company_id
        LEFT JOIN fc_special_employee_map special_employee
          ON special_employee.employee_id=em.employee_id
        LEFT JOIN fc_special_map special
          ON special.special_code=special_employee.special_code
        JOIN attendance_group_assignment olda
          ON olda.employee_id=em.employee_id
         AND olda.supersedes_assignment_id IS NULL
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=
             olda.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        CROSS JOIN LATERAL (SELECT
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment|',em.employee_id)) h1,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment-old-inactive|',em.employee_id)) h2,
          MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|assignment-new-active|',em.employee_id)) h3
        ) seed;
        IF (SELECT COUNT(*) FROM fc_assignment_map) <> 615 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'source attendance assignments changed';
        END IF;

        INSERT INTO attendance_assignment_timeline (
            attendance_assignment_timeline_id,attendance_group_assignment_id,
            employee_id,event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT old_inactive_timeline_id,old_assignment_id,employee_id,2,'INACTIVE',
               cutover_date,old_timeline_id,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-REASSIGN-',employee_id)
        FROM fc_assignment_map;

        INSERT INTO attendance_group_assignment (
            attendance_group_assignment_id,employee_id,attendance_group_revision_id,
            effective_from,supersedes_assignment_id,snapshot_digest,
            change_reason,created_by,created_at
        )
        SELECT a.new_assignment_id,a.employee_id,a.target_group_revision_id,cutover_date,
               a.old_assignment_id,
               SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,a.target_group_revision_id,'|',a.employee_id,
                           '|',cutover_date,'|NULL'),256),
               '2026-08-05 四公司维度切换：考勤组重分配',
               bootstrap_actor_id,cutover_at
        FROM fc_assignment_map a;

        INSERT INTO attendance_assignment_timeline (
            attendance_assignment_timeline_id,attendance_group_assignment_id,
            employee_id,event_sequence,state,business_effective_from,
            predecessor_timeline_id,recorded_at,actor_id,request_id
        )
        SELECT new_active_timeline_id,new_assignment_id,employee_id,1,'ACTIVE',
               cutover_date,NULL,cutover_at,bootstrap_actor_id,
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'FCF-REASSIGN-',employee_id)
        FROM fc_assignment_map;

        INSERT INTO auth_data_scope (
            scope_id,scope_type,company_id,organization_id,
            include_descendants,valid_from,valid_to
        )
        SELECT auth_scope_id,'COMPANY',company_id,NULL,TRUE,cutover_at,NULL
        FROM fc_company_map;

        INSERT INTO auth_principal_role_assignment (
            assignment_id,principal_id,role_id,data_scope_id,
            valid_from,valid_to,assigned_by,reason
        )
        SELECT LOWER(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,SUBSTR(h,1,8),'-',SUBSTR(h,9,4),'-',SUBSTR(h,13,4),
                            '-',SUBSTR(h,17,4),'-',SUBSTR(h,21,12))),
               admin_principal_id,r.role_id,m.auth_scope_id,cutover_at,NULL,
               bootstrap_actor_id,'2026-08-05 四公司初始管理员授权'
        FROM fc_company_map m
        JOIN auth_role r ON r.role_id IN (hr_role_id,system_role_id)
        CROSS JOIN LATERAL (SELECT MD5(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'four-company-v1|admin-role|',
            m.company_id,'|',r.role_id)) h) seed;

        UPDATE auth_principal_role_assignment a
        SET a.valid_to=cutover_at,
            a.reason='2026-08-05 原混合公司归档，授权结束'
        WHERE a.principal_id=admin_principal_id
          AND a.data_scope_id=old_scope_id
          AND a.role_id IN (hr_role_id,system_role_id)
          AND a.valid_to IS NULL;
        UPDATE auth_data_scope SET valid_to=cutover_at
        WHERE scope_id=old_scope_id AND valid_to IS NULL;
    END IF;

    -- Strong final shape checks. Any drift rolls back the first run and also
    -- prevents a later run from silently accepting a changed final state.
    IF (SELECT COUNT(*) FROM company WHERE status='ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company c JOIN fc_company_map m
           ON m.company_id=c.company_id
           WHERE c.code=m.company_code AND c.name=m.company_name
             AND c.status='ACTIVE') <> 4
       OR (SELECT COUNT(*) FROM company
           WHERE company_id=archive_company_id
             AND code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
             AND name='历史导入归档（原四公司混合数据）'
             AND status='INACTIVE') <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final company directory verification failed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN organization_identity oi ON oi.company_id=m.company_id
        GROUP BY m.company_id,m.expected_orgs
        HAVING COUNT(oi.organization_id)<>m.expected_orgs
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        LEFT JOIN employee e ON e.company_id=m.company_id
        GROUP BY m.company_id,m.expected_employees
        HAVING COUNT(e.employee_id)<>m.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM employment_assignment a
        JOIN employee e ON e.employee_id=a.employee_id
        JOIN organization_identity oi ON oi.organization_id=a.organization_id
        WHERE a.record_status='ACTIVE' AND a.current_version_marker=1
          AND e.company_id<>oi.company_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN organization_current_projection p ON p.organization_id=m.root_id
        JOIN organization_version v ON v.organization_version_id=p.current_version_id
        WHERE p.current_version_id<>m.root_version_id OR v.org_type<>'COMPANY'
          OR v.parent_organization_id IS NOT NULL OR v.current_marker<>1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final organization/employee verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM location l JOIN fc_company_map m
        ON m.location_id=l.location_id
        WHERE l.company_id=m.company_id AND l.location_code='DEFAULT_LOCATION')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_template s JOIN fc_company_map m
        ON m.shift_template_id=s.shift_template_id
        WHERE s.company_id=m.company_id AND s.template_code='STANDARD_SEASONAL')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_version v JOIN fc_company_map m
        ON m.shift_template_id=v.shift_template_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_seasonal_schedule s JOIN fc_company_map m
        ON m.seasonal_schedule_id=s.shift_seasonal_schedule_id)<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar c JOIN fc_company_map m
        ON m.calendar_id=c.work_calendar_id
        WHERE c.company_id=m.company_id AND c.calendar_code='STANDARD_2026')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar_version v JOIN fc_company_map m
        ON m.calendar_id=v.work_calendar_id)<>8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM work_calendar_day d
        JOIN work_calendar_version v ON v.work_calendar_version_id=d.work_calendar_version_id
        JOIN fc_company_map m ON m.calendar_id=v.work_calendar_id)<>1460 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group g JOIN fc_company_map m
        ON m.group_id=g.attendance_group_id
        WHERE g.company_id=m.company_id AND g.group_code='DEFAULT_ATTENDANCE')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_scope s JOIN fc_company_map m
        ON m.company_id=s.company_id)<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_scoped_version v
        JOIN attendance_policy_scope s ON s.scope_id=v.scope_id
        JOIN fc_company_map m ON m.company_id=s.company_id)<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_lifecycle_event e
        JOIN attendance_policy_scope s ON s.scope_id=e.scope_id
        JOIN fc_company_map m ON m.company_id=s.company_id
        WHERE e.action='PUBLISHED')<>32 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM attendance_policy_scoped_version v
        JOIN attendance_policy_scope s ON s.scope_id=v.scope_id
        JOIN attendance_policy_template t
          ON t.policy_template_id=s.policy_template_id
        JOIN fc_company_map m ON m.company_id=s.company_id
        JOIN (
            SELECT source.scoped_version_id,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'{',GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                       JSON_QUOTE(parameter_key.parameter_key),':',
                       REPLACE(CAST(JSON_EXTRACT(
                           source.parameters_json,
                           CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'$.',parameter_key.parameter_key)
                       ) AS CHAR CHARACTER SET utf8mb4),', ',','))
                       ORDER BY CONVERT(parameter_key.parameter_key USING utf8mb4)
                                COLLATE utf8mb4_bin SEPARATOR ','),'}')
                       AS canonical_parameters
            FROM attendance_policy_scoped_version source
            JOIN JSON_TABLE(
                JSON_KEYS(source.parameters_json),
                '$[*]' COLUMNS(parameter_key VARCHAR(128) PATH '$')
            ) parameter_key ON TRUE
            GROUP BY source.scoped_version_id
        ) parameters ON parameters.scoped_version_id=v.scoped_version_id
        CROSS JOIN LATERAL (
            SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                '{"companyId":',JSON_QUOTE(s.company_id),
                ',"effectiveFrom":',JSON_QUOTE(DATE_FORMAT(v.effective_from,'%Y-%m-%d')),
                ',"effectiveTo":',IF(v.effective_to IS NULL,'null',
                    JSON_QUOTE(DATE_FORMAT(v.effective_to,'%Y-%m-%d'))),
                ',"parameters":',parameters.canonical_parameters,
                ',"policyKind":',JSON_QUOTE(t.template_code),
                ',"scopeId":',JSON_QUOTE(s.scope_id),
                ',"templateId":',JSON_QUOTE(s.policy_template_id),
                ',"versionNumber":',v.version_number,'}'
            ) AS snapshot_json
        ) canonical
        WHERE COALESCE(JSON_LENGTH(v.snapshot_json),-1)<>8
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.companyId'))
                   <=> s.company_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.effectiveFrom'))
                   <=> DATE_FORMAT(v.effective_from,'%Y-%m-%d'))
           OR (v.effective_to IS NULL AND
               COALESCE(JSON_TYPE(JSON_EXTRACT(
                   v.snapshot_json,'$.effectiveTo')),'MISSING')<>'NULL')
           OR (v.effective_to IS NOT NULL AND NOT (
               JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.effectiveTo'))
               <=> DATE_FORMAT(v.effective_to,'%Y-%m-%d')))
           OR COALESCE(JSON_CONTAINS(
                  JSON_EXTRACT(v.snapshot_json,'$.parameters'),v.parameters_json),0)=0
           OR COALESCE(JSON_CONTAINS(
                  v.parameters_json,
                  JSON_EXTRACT(v.snapshot_json,'$.parameters')),0)=0
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.policyKind'))
                   <=> t.template_code)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.scopeId'))
                   <=> s.scope_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.templateId'))
                   <=> s.policy_template_id)
           OR NOT (JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.versionNumber'))
                   <=> CAST(v.version_number AS CHAR))
           OR v.snapshot_digest<>SHA2(canonical.snapshot_json,256)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'attendance policy canonical snapshot verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_family f
        JOIN fc_company_map m ON m.group_id=f.attendance_group_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_revision r
        JOIN attendance_policy_binding_family f ON f.binding_family_id=r.binding_family_id
        JOIN fc_company_map m ON m.group_id=f.attendance_group_id)<>12 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM annual_leave_policy_version v
        JOIN fc_company_map m ON m.company_id=v.company_id
        WHERE v.scope_type='COMPANY' AND v.status='PUBLISHED')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM annual_leave_policy_lifecycle_event e
        JOIN fc_company_map m ON m.annual_policy_version_id=e.annual_leave_policy_version_id
        WHERE e.action='PUBLISHED')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_company_default_provisioning p
        JOIN fc_company_map m ON m.provisioning_id=p.provisioning_id
        WHERE p.company_id=m.company_id AND p.provisioning_year=2026
          AND p.status='READY')<>4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'final attendance/leave baseline verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM location location
        JOIN fc_city_map city ON city.location_id=location.location_id
        JOIN location_revision revision
          ON revision.location_revision_id=city.location_revision_id
        JOIN location_timeline timeline
          ON timeline.location_timeline_id=city.location_timeline_id
        WHERE location.company_id=city.company_id
          AND location.location_code=city.city_code
          AND revision.location_name=city.city_name
          AND revision.time_zone='Asia/Shanghai'
          AND timeline.state='ACTIVE' AND timeline.event_sequence=1)<>24 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'city location provisioning verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_template shift_template
        JOIN fc_special_map special
          ON special.shift_template_id=shift_template.shift_template_id
        WHERE shift_template.company_id='41000000-0000-0000-0000-000000000003'
          AND shift_template.template_code=special.shift_code)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM shift_template shift_template
        JOIN location location ON location.location_id=shift_template.location_id
        WHERE location.location_code IN (
                  'CHENGDU','DALIAN','SHANGHAI','HEFEI','WUHAN','SHENZHEN')
          AND NOT (
              shift_template.company_id='41000000-0000-0000-0000-000000000003'
              AND location.location_code IN ('CHENGDU','DALIAN','SHANGHAI')
              AND shift_template.template_code IN (
                  'CHENGDU_FIXED','DALIAN_FIXED','SHANGHAI_SEASONAL'))
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'unapproved city-specific shift detected';
    END IF;
    IF (SELECT COUNT(*) FROM shift_version version
        JOIN fc_special_map special
          ON special.shift_template_id=version.shift_template_id)<>5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM shift_version version
        WHERE version.version_number=1
          AND version.effective_from='2026-01-01'
          AND version.time_zone_snapshot='Asia/Shanghai'
          AND ((
              version.shift_version_id='42021000-0000-0000-0000-000000000001'
              AND version.segments_json=CAST(
                  '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"07:30:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"16:30:00","endDayOffset":0}]}'
                  AS JSON)
          ) OR (
              version.shift_version_id='42021000-0000-0000-0000-000000000002'
              AND version.segments_json=CAST(
                  '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"09:00:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"18:00:00","endDayOffset":0}]}'
                  AS JSON)
          )))<>2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'fixed city shift times verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM shift_version special_version
        JOIN shift_version default_version
          ON default_version.shift_template_id=
             '41020000-0000-0000-0000-000000000003'
         AND default_version.version_number=special_version.version_number
        WHERE special_version.shift_template_id=
              '42020000-0000-0000-0000-000000000003'
          AND special_version.effective_from=default_version.effective_from
          AND special_version.time_zone_snapshot=default_version.time_zone_snapshot
          AND special_version.segments_json=default_version.segments_json)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Shanghai shift does not match Yangzhou shift';
    END IF;
    IF (SELECT COUNT(*) FROM shift_publication_timeline timeline
        JOIN fc_special_map special
          ON special.shift_template_id=timeline.shift_template_id
        WHERE timeline.state='PUBLISHED')<>5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM shift_seasonal_schedule schedule
        WHERE schedule.shift_seasonal_schedule_id=
              '42022000-0000-0000-0000-000000000003')<>1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group attendance_group
        JOIN fc_special_map special
          ON special.group_id=attendance_group.attendance_group_id
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=special.group_revision_id
        JOIN attendance_group_timeline timeline
          ON timeline.attendance_group_timeline_id=special.group_timeline_id
        WHERE attendance_group.company_id=
              '41000000-0000-0000-0000-000000000003'
          AND attendance_group.group_code=special.group_code
          AND revision.group_name=special.group_name
          AND timeline.state='ACTIVE' AND timeline.event_sequence=1)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special shift/group verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_family family
        JOIN fc_special_map special
          ON special.group_id=family.attendance_group_id)<>9 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special policy binding verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_policy_binding_revision revision
        JOIN attendance_policy_binding_family family
          ON family.binding_family_id=revision.binding_family_id
        JOIN fc_special_map special
          ON special.group_id=family.attendance_group_id)<>9 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special policy binding verification failed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN location_revision r ON r.location_revision_id=m.location_revision_id
        WHERE r.location_name<>'扬州'
          OR r.time_zone<>'Asia/Shanghai'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN work_calendar_version v ON v.work_calendar_id=m.calendar_id
        WHERE v.calendar_name<>CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'2026工作日历')
          OR v.time_zone_snapshot<>'Asia/Shanghai'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_company_map m
        JOIN attendance_group_revision r ON r.attendance_group_id=m.group_id
        WHERE r.group_name<>CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,m.short_name,'默认考勤组')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company-specific display names verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM attendance_group_assignment a
        JOIN fc_company_map m ON m.group_revision_id=a.attendance_group_revision_id
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=a.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        WHERE a.effective_from=cutover_date)<>597 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(DISTINCT a.employee_id)
        FROM attendance_group_assignment a
        WHERE a.effective_from=cutover_date
          AND a.supersedes_assignment_id IS NOT NULL)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group_assignment assignment
        JOIN fc_special_map special
          ON special.group_revision_id=assignment.attendance_group_revision_id
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE'
        WHERE assignment.effective_from=cutover_date)<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM fc_special_employee_map expected
        JOIN employee employee ON employee.employee_id=expected.employee_id
        JOIN attendance_group_assignment assignment
          ON assignment.employee_id=employee.employee_id
         AND assignment.supersedes_assignment_id IS NOT NULL
         AND assignment.effective_from=cutover_date
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=
             assignment.attendance_group_revision_id
        JOIN attendance_group attendance_group
          ON attendance_group.attendance_group_id=revision.attendance_group_id
         AND attendance_group.group_code=CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,expected.special_code,'_ATTENDANCE')
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE')<>18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special employee-to-group mapping verification failed';
    END IF;
    IF (SELECT COUNT(*)
        FROM fc_named_employee_expectation expected
        JOIN employee employee
          ON employee.employee_number=expected.employee_number
         AND employee.display_name=expected.display_name
         AND employee.company_id=expected.expected_company_id
        JOIN attendance_group_assignment assignment
          ON assignment.employee_id=employee.employee_id
         AND assignment.supersedes_assignment_id IS NOT NULL
         AND assignment.effective_from=cutover_date
        JOIN attendance_group_revision revision
          ON revision.attendance_group_revision_id=
             assignment.attendance_group_revision_id
        JOIN attendance_group attendance_group
          ON attendance_group.attendance_group_id=revision.attendance_group_id
         AND attendance_group.company_id=expected.expected_company_id
         AND attendance_group.group_code='DEFAULT_ATTENDANCE'
        JOIN attendance_assignment_timeline timeline
          ON timeline.attendance_group_assignment_id=
             assignment.attendance_group_assignment_id
         AND timeline.event_sequence=1 AND timeline.state='ACTIVE'
        WHERE expected.special_code IS NULL)<>3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SZJN named Yangzhou default mapping verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fc_special_map special
        LEFT JOIN attendance_group_assignment assignment
          ON assignment.attendance_group_revision_id=special.group_revision_id
         AND assignment.effective_from=cutover_date
         AND assignment.supersedes_assignment_id IS NOT NULL
        GROUP BY special.special_code,special.expected_employees
        HAVING COUNT(assignment.attendance_group_assignment_id)<>
               special.expected_employees
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'special attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_group_assignment
        WHERE supersedes_assignment_id IS NOT NULL)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF (SELECT COUNT(*) FROM attendance_assignment_timeline t
        JOIN attendance_group_assignment successor
          ON successor.supersedes_assignment_id=
             t.attendance_group_assignment_id
        JOIN fc_employee_map em ON em.employee_id=successor.employee_id
        WHERE t.event_sequence=2 AND t.state='INACTIVE'
          AND t.business_effective_from=cutover_date)<>615 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM attendance_group_assignment a
        JOIN attendance_group_revision r
          ON r.attendance_group_revision_id=a.attendance_group_revision_id
        JOIN attendance_group g ON g.attendance_group_id=r.attendance_group_id
        JOIN employee e ON e.employee_id=a.employee_id
        JOIN attendance_assignment_timeline t
          ON t.attendance_group_assignment_id=a.attendance_group_assignment_id
         AND t.event_sequence=1 AND t.state='ACTIVE'
        WHERE a.supersedes_assignment_id IS NOT NULL
          AND e.company_id<>g.company_id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'current attendance assignment verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM people_import_batch
        WHERE company_id=archive_company_id AND status='PUBLISHED')<>3
       OR EXISTS (SELECT 1 FROM people_import_batch b
                  JOIN fc_company_map m ON m.company_id=b.company_id)
       OR EXISTS (SELECT 1 FROM organization_identity
                  WHERE company_id=archive_company_id)
       OR EXISTS (SELECT 1 FROM employee WHERE company_id=archive_company_id) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'historical import archive isolation verification failed';
    END IF;

    IF (SELECT COUNT(*) FROM auth_principal_role_assignment a
        JOIN auth_data_scope s ON s.scope_id=a.data_scope_id
        JOIN fc_company_map m ON m.auth_scope_id=s.scope_id
        WHERE a.principal_id=admin_principal_id
          AND a.role_id IN (hr_role_id,system_role_id)
          AND a.valid_from<=cutover_at AND a.valid_to IS NULL
          AND s.scope_type='COMPANY' AND s.company_id=m.company_id
          AND s.valid_to IS NULL)<>8
       OR EXISTS (
           SELECT 1 FROM auth_principal_role_assignment a
           JOIN auth_data_scope s ON s.scope_id=a.data_scope_id
           WHERE a.principal_id=admin_principal_id
             AND s.company_id=archive_company_id
             AND a.valid_from<=cutover_at
             AND (a.valid_to IS NULL OR a.valid_to>cutover_at)
       )
       OR (SELECT session_epoch FROM local_account
           WHERE account_id=admin_account_id)<>admin_session_epoch THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'administrator four-company authorization verification failed';
    END IF;

    SET SESSION group_concat_max_len = original_group_concat_max_len;
    COMMIT;
    DO RELEASE_LOCK('shenzhouhr:four-company-finalization:v1');
END$$

DELIMITER ;

CALL finalize_four_companies_post_v30();
DROP PROCEDURE finalize_four_companies_post_v30;

-- ============================================================
-- 3/6: shared-location-convergence (共享地点数据)
-- ============================================================
-- Controlled local/deployment convergence for the shared physical-location model.
-- Flyway V31 owns all DDL.  This script refuses to run until V31 is registered,
-- then validates the complete table shape before any DML is allowed to run.
-- The caller must select the target schema (for example with the mysql database
-- argument).  Keeping this script schema-neutral is required for fresh rehearsal.
DROP PROCEDURE IF EXISTS guard_shared_location_target;
DELIMITER $$
CREATE PROCEDURE guard_shared_location_target()
BEGIN
    IF DATABASE() IS NULL
       OR (SELECT COUNT(*)
           FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name IN (
                 'flyway_schema_history', 'company', 'location',
                 'location_revision', 'location_timeline', 'auth_principal',
                 'shared_location', 'shared_location_revision',
                 'company_location_availability'
             )) <> 9
       OR NOT EXISTS (
           SELECT 1
           FROM flyway_schema_history
           WHERE version = '31' AND success = 1
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target must be a selected ShenzhouHR schema at successful V31';
    END IF;
END$$
DELIMITER ;
CALL guard_shared_location_target();
DROP PROCEDURE guard_shared_location_target;

DROP PROCEDURE IF EXISTS verify_shared_location_schema;
DELIMITER $$
CREATE PROCEDURE verify_shared_location_schema()
BEGIN
    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location'
          AND column_name IN (
              'shared_location_id', 'location_code', 'row_version',
              'created_by', 'created_at'
          )) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared_location has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location_revision'
          AND column_name IN (
              'shared_location_revision_id', 'shared_location_id',
              'revision_number', 'location_name', 'time_zone', 'status',
              'effective_from', 'effective_to',
              'supersedes_shared_location_revision_id', 'snapshot_digest',
              'change_reason', 'created_by', 'created_at'
          )) <> 13 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared_location_revision has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'company_location_availability'
          AND column_name IN (
              'company_location_availability_id', 'shared_location_id',
              'company_id', 'location_id', 'location_code', 'status',
              'effective_from', 'effective_to', 'created_by', 'created_at'
          )) <> 10 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company_location_availability has an incomplete column shape';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'shared_location'
          AND index_name = 'uq_shared_location_code'
          AND non_unique = 0) <> 1
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'shared_location'
             AND index_name = 'uq_shared_location_identity_code'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND index_name = 'uq_company_location_shared'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND index_name = 'uq_company_location_projection'
             AND non_unique = 0) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location unique indexes are incomplete';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.referential_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_name IN (
              'fk_shared_location_created_by',
              'fk_shared_location_revision_location',
              'fk_shared_location_revision_predecessor',
              'fk_shared_location_revision_created_by',
              'fk_company_location_shared_code',
              'fk_company_location_company',
              'fk_company_location_projection_company',
              'fk_company_location_created_by'
          )) <> 8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location foreign keys are incomplete';
    END IF;

    IF (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'location'
             AND index_name = 'uq_location_company_projection'
             AND non_unique = 0) <> 2
       OR (SELECT COUNT(*)
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'location'
             AND index_name = 'uq_location_company_projection_code'
             AND non_unique = 0) <> 3
       OR (SELECT GROUP_CONCAT(
                    column_name ORDER BY ordinal_position SEPARATOR ',')
           FROM information_schema.key_column_usage
           WHERE constraint_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND constraint_name =
                    'fk_company_location_projection_company')
            <> 'company_id,location_id,location_code'
       OR (SELECT GROUP_CONCAT(
                    column_name ORDER BY ordinal_position SEPARATOR ',')
           FROM information_schema.key_column_usage
           WHERE constraint_schema = DATABASE()
             AND table_name = 'company_location_availability'
             AND constraint_name = 'fk_company_location_shared_code')
            <> 'shared_location_id,location_code' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company/location projection constraint is incomplete';
    END IF;

    IF (SELECT COUNT(*)
        FROM information_schema.check_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_name IN (
              'ck_shared_location_revision_number',
              'ck_shared_location_revision_status',
              'ck_shared_location_revision_period',
              'ck_company_location_status',
              'ck_company_location_period'
          )) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared location check constraints are incomplete';
    END IF;
END$$
DELIMITER ;
CALL verify_shared_location_schema();
DROP PROCEDURE verify_shared_location_schema;

DROP PROCEDURE IF EXISTS converge_shared_locations;
DELIMITER $$
CREATE PROCEDURE converge_shared_locations()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    IF (SELECT COUNT(*)
        FROM company
        WHERE code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
          AND status = 'ACTIVE') <> 4
       OR (SELECT COUNT(*)
           FROM location projection
           JOIN company business_company
             ON business_company.company_id = projection.company_id
            AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
            AND business_company.status = 'ACTIVE') <> 28
       OR (SELECT COUNT(DISTINCT projection.location_code)
           FROM location projection
           JOIN company business_company
             ON business_company.company_id = projection.company_id
            AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
            AND business_company.status = 'ACTIVE') <> 7 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'confirmed four-company projection shape must be 4 companies, 28 rows and 7 codes';
    END IF;

    IF EXISTS (
        SELECT projection.location_code
        FROM location projection
        JOIN company business_company
          ON business_company.company_id = projection.company_id
         AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
         AND business_company.status = 'ACTIVE'
        JOIN location_revision revision
          ON revision.location_id = projection.location_id
         AND revision.revision_number = (
             SELECT MAX(latest.revision_number)
             FROM location_revision latest
             WHERE latest.location_id = projection.location_id
         )
        JOIN location_timeline timeline
          ON timeline.location_revision_id = revision.location_revision_id
         AND timeline.event_sequence = (
             SELECT MIN(origin.event_sequence)
             FROM location_timeline origin
             WHERE origin.location_revision_id = revision.location_revision_id
         )
        GROUP BY projection.location_code
        HAVING COUNT(*) <> 4
            OR COUNT(DISTINCT CONCAT(
                revision.location_name, '|', revision.time_zone, '|',
                timeline.state, '|', revision.effective_from, '|',
                COALESCE(
                    (
                        SELECT MIN(boundary.business_effective_from)
                        FROM location_timeline boundary
                        WHERE boundary.location_id = projection.location_id
                          AND boundary.event_sequence > timeline.event_sequence
                          AND boundary.business_effective_from >
                                timeline.business_effective_from
                          AND (
                              boundary.location_revision_id <>
                                    timeline.location_revision_id
                              OR boundary.state = 'INACTIVE'
                          )
                    ),
                    'NULL'
                )
            )) <> 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'company projections disagree on shared name, timezone, status or effective date';
    END IF;

    INSERT INTO shared_location (
        shared_location_id, location_code, row_version, created_by, created_at
    )
    SELECT canonical.location_id, canonical.location_code, 0,
           canonical.created_by, canonical.created_at
    FROM location canonical
    JOIN company canonical_company
      ON canonical_company.company_id = canonical.company_id
     AND canonical_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
    WHERE canonical.location_id = (
        SELECT MIN(candidate.location_id)
        FROM location candidate
        JOIN company candidate_company
          ON candidate_company.company_id = candidate.company_id
         AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        WHERE candidate.location_code = canonical.location_code
    )
      AND NOT EXISTS (
          SELECT 1
          FROM shared_location shared
          WHERE shared.location_code = canonical.location_code
      );

    INSERT INTO shared_location_revision (
        shared_location_revision_id, shared_location_id, revision_number,
        location_name, time_zone, status, effective_from, effective_to,
        supersedes_shared_location_revision_id, snapshot_digest,
        change_reason, created_by, created_at
    )
    SELECT canonical_revision.location_revision_id,
           shared.shared_location_id,
           1,
           canonical_revision.location_name,
           canonical_revision.time_zone,
           canonical_timeline.state,
           canonical_revision.effective_from,
           (
               SELECT MIN(boundary.business_effective_from)
               FROM location_timeline boundary
               WHERE boundary.location_id = canonical.location_id
                 AND boundary.event_sequence >
                        canonical_timeline.event_sequence
                 AND boundary.business_effective_from >
                        canonical_timeline.business_effective_from
                 AND (
                     boundary.location_revision_id <>
                            canonical_timeline.location_revision_id
                     OR boundary.state = 'INACTIVE'
                 )
           ),
           NULL,
           SHA2(CONCAT(
               shared.location_code, '|', canonical_revision.location_name, '|',
               canonical_revision.time_zone, '|', canonical_timeline.state, '|',
               canonical_revision.effective_from, '|',
               COALESCE(
                   (
                       SELECT MIN(boundary.business_effective_from)
                       FROM location_timeline boundary
                       WHERE boundary.location_id = canonical.location_id
                         AND boundary.event_sequence >
                                canonical_timeline.event_sequence
                         AND boundary.business_effective_from >
                                canonical_timeline.business_effective_from
                         AND (
                             boundary.location_revision_id <>
                                    canonical_timeline.location_revision_id
                             OR boundary.state = 'INACTIVE'
                         )
                   ),
                   'NULL'
               )
           ), 256),
           '共享物理地点目录收口（保留公司兼容投影历史）',
           canonical_revision.created_by,
           canonical_revision.created_at
    FROM shared_location shared
    JOIN location canonical
      ON canonical.location_id = (
          SELECT MIN(candidate.location_id)
          FROM location candidate
          JOIN company candidate_company
            ON candidate_company.company_id = candidate.company_id
           AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
          WHERE candidate.location_code = shared.location_code
      )
    JOIN location_revision canonical_revision
      ON canonical_revision.location_id = canonical.location_id
     AND canonical_revision.revision_number = (
         SELECT MAX(latest_revision.revision_number)
         FROM location_revision latest_revision
         WHERE latest_revision.location_id = canonical.location_id
     )
    JOIN location_timeline canonical_timeline
      ON canonical_timeline.location_revision_id =
            canonical_revision.location_revision_id
     AND canonical_timeline.event_sequence = (
         SELECT MIN(origin.event_sequence)
         FROM location_timeline origin
         WHERE origin.location_revision_id =
                canonical_revision.location_revision_id
     )
    WHERE NOT EXISTS (
        SELECT 1
        FROM shared_location_revision existing
        WHERE existing.shared_location_id = shared.shared_location_id
    );

    INSERT INTO company_location_availability (
        company_location_availability_id, shared_location_id,
        company_id, location_id, location_code,
        status, effective_from, effective_to,
        created_by, created_at
    )
    SELECT LOWER(CONCAT(
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 1, 8), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 9, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 13, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 17, 4), '-',
               SUBSTRING(SHA2(CONCAT('COMPANY_LOCATION|', location.location_id), 256), 21, 12)
           )),
           shared.shared_location_id,
           location.company_id,
           location.location_id,
           location.location_code,
           'ACTIVE',
           revision.effective_from,
           NULL,
           location.created_by,
           location.created_at
    FROM location
    JOIN company
      ON company.company_id = location.company_id
     AND company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
    JOIN shared_location shared
      ON shared.location_code = location.location_code
    JOIN location_revision revision
      ON revision.location_id = location.location_id
     AND revision.revision_number = 1
    WHERE NOT EXISTS (
        SELECT 1
        FROM company_location_availability existing
        WHERE existing.location_id = location.location_id
    );

    IF EXISTS (
        SELECT 1
        FROM location projection
        JOIN company projection_company
          ON projection_company.company_id = projection.company_id
         AND projection_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        LEFT JOIN company_location_availability availability
          ON availability.location_id = projection.location_id
        WHERE availability.location_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'one or more location projections lack shared availability';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM company_location_availability availability
        JOIN location projection
          ON projection.location_id = availability.location_id
        JOIN shared_location shared
          ON shared.shared_location_id = availability.shared_location_id
        WHERE projection.company_id <> availability.company_id
           OR projection.location_code <> availability.location_code
           OR availability.location_code <> shared.location_code
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared availability does not match its compatibility projection';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM company_location_availability availability
        JOIN location projection
          ON projection.location_id = availability.location_id
         AND projection.company_id = availability.company_id
        JOIN company business_company
          ON business_company.company_id = availability.company_id
         AND business_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
        WHERE availability.status <> 'ACTIVE'
           OR availability.effective_to IS NOT NULL
           OR availability.effective_from <> (
               SELECT MIN(revision.effective_from)
               FROM location_revision revision
               WHERE revision.location_id = projection.location_id
           )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'target company location availability period drifted';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM shared_location shared
        JOIN shared_location_revision shared_revision
          ON shared_revision.shared_location_id = shared.shared_location_id
         AND shared_revision.revision_number = (
             SELECT MAX(latest.revision_number)
             FROM shared_location_revision latest
             WHERE latest.shared_location_id = shared.shared_location_id
         )
        JOIN location canonical
          ON canonical.location_id = (
              SELECT MIN(candidate.location_id)
              FROM location candidate
              JOIN company candidate_company
                ON candidate_company.company_id = candidate.company_id
               AND candidate_company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
              WHERE candidate.location_code = shared.location_code
          )
        JOIN location_revision canonical_revision
          ON canonical_revision.location_id = canonical.location_id
         AND canonical_revision.revision_number = (
             SELECT MAX(latest_revision.revision_number)
             FROM location_revision latest_revision
             WHERE latest_revision.location_id = canonical.location_id
         )
        JOIN location_timeline canonical_timeline
          ON canonical_timeline.location_revision_id =
                canonical_revision.location_revision_id
         AND canonical_timeline.event_sequence = (
             SELECT MIN(origin.event_sequence)
             FROM location_timeline origin
             WHERE origin.location_revision_id =
                    canonical_revision.location_revision_id
         )
        WHERE shared.row_version + 1 <> shared_revision.revision_number
           OR shared_revision.location_name <>
                canonical_revision.location_name
           OR shared_revision.time_zone <> canonical_revision.time_zone
           OR shared_revision.status <> canonical_timeline.state
           OR shared_revision.effective_from <>
                canonical_revision.effective_from
           OR NOT (
               shared_revision.effective_to <=> (
                   SELECT MIN(boundary.business_effective_from)
                   FROM location_timeline boundary
                   WHERE boundary.location_id = canonical.location_id
                     AND boundary.event_sequence >
                            canonical_timeline.event_sequence
                     AND boundary.business_effective_from >
                            canonical_timeline.business_effective_from
                     AND (
                         boundary.location_revision_id <>
                                canonical_timeline.location_revision_id
                         OR boundary.state = 'INACTIVE'
                     )
               )
           )
           OR shared_revision.snapshot_digest <> SHA2(CONCAT(
               shared.location_code, '|', canonical_revision.location_name, '|',
               canonical_revision.time_zone, '|', canonical_timeline.state, '|',
               canonical_revision.effective_from, '|',
               COALESCE(
                   (
                       SELECT MIN(boundary.business_effective_from)
                       FROM location_timeline boundary
                       WHERE boundary.location_id = canonical.location_id
                         AND boundary.event_sequence >
                                canonical_timeline.event_sequence
                         AND boundary.business_effective_from >
                                canonical_timeline.business_effective_from
                         AND (
                             boundary.location_revision_id <>
                                    canonical_timeline.location_revision_id
                             OR boundary.state = 'INACTIVE'
                         )
                   ),
                   'NULL'
               )
           ), 256)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'shared latest revision drifted from company projections';
    END IF;

    IF (SELECT COUNT(*)
        FROM shared_location_revision revision
        WHERE revision.revision_number = (
            SELECT MAX(latest.revision_number)
            FROM shared_location_revision latest
            WHERE latest.shared_location_id = revision.shared_location_id
        )) <> (SELECT COUNT(*) FROM shared_location) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'each shared location must resolve one latest revision';
    END IF;

    IF (SELECT COUNT(*)
        FROM company
        WHERE code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')) = 4 THEN
        IF (SELECT COUNT(*) FROM shared_location) <> 7
           OR (SELECT COUNT(DISTINCT availability.shared_location_id)
            FROM company_location_availability availability
            JOIN company ON company.company_id = availability.company_id
            WHERE company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
              AND availability.status = 'ACTIVE') <> 7
           OR (SELECT COUNT(*)
               FROM company_location_availability availability
               JOIN company ON company.company_id = availability.company_id
               WHERE company.code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')
                 AND availability.status = 'ACTIVE') <> 28 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'four-company location target must be 7 shared and 28 availability rows';
        END IF;
    END IF;

    COMMIT;
END$$
DELIMITER ;
CALL converge_shared_locations();
DROP PROCEDURE converge_shared_locations;

-- ============================================================
-- 4/6: usability-finalization (可用性优化配置)
-- ============================================================
-- Post-V30 local/deployment finalization.
--
-- This file intentionally remains outside Flyway until the authoritative V29
-- and V30 source files are restored to the repository. Run it only after the
-- target database has successfully applied V30. It is repeat-safe.

-- Refuse to delete a role that is still assigned. The explicit SIGNAL makes
-- this fail closed even in SQL clients that continue after a statement error.
DROP PROCEDURE IF EXISTS finalize_usability_post_v30;

DELIMITER $$

CREATE PROCEDURE finalize_usability_post_v30()
BEGIN
    DECLARE synthetic_employee_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_principal_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_account_id VARCHAR(36) DEFAULT NULL;
    DECLARE synthetic_organization_id VARCHAR(36) DEFAULT NULL;
    DECLARE baseline_company_id VARCHAR(36) DEFAULT NULL;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    IF (SELECT COUNT(*) FROM flyway_schema_history
        WHERE version = '30' AND success = 1) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V30 Flyway history is required before finalization';
    END IF;

    SELECT company_id INTO baseline_company_id
    FROM company
    WHERE company_id = '30000000-0000-0000-0000-000000000001'
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
    FOR UPDATE;

    IF baseline_company_id IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'W3 baseline company shape changed; manual review required';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM auth_principal_role_assignment assignment
        JOIN auth_role role ON role.role_id = assignment.role_id
        WHERE role.role_code IN (
            'MANUFACTURING_SUPERVISOR',
            'MANUFACTURING_CENTER_SUPERVISOR',
            'MANUFACTURING_DIRECTOR',
            'MANUFACTURING_CENTER_DIRECTOR'
        )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Cannot remove manufacturing role: assignments still exist';
    END IF;

    -- Historical local acceptance administrators must not remain usable after
    -- the W3 company is retired. Match only the known synthetic account shape
    -- and fail closed if any such principal was later bound to a real employee
    -- or granted a scope outside the W3 baseline company.
    IF EXISTS (
        SELECT 1
        FROM local_account account
        JOIN auth_principal principal
          ON principal.principal_id = account.principal_id
        WHERE account.display_name = 'WAVE-1 本地合成管理员'
          AND (
              account.username IN (
                  'synthetic.local.admin',
                  'synthetic.acceptance.20260801',
                  'synthetic.acceptance.20260803'
              )
              OR account.username REGEXP
                 '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
          )
          AND (
              principal.employee_id IS NOT NULL
              OR EXISTS (
                  SELECT 1
                  FROM auth_principal_role_assignment assignment
                  JOIN auth_data_scope scope
                    ON scope.scope_id = assignment.data_scope_id
                  WHERE assignment.principal_id = principal.principal_id
                    AND NOT (
                        scope.scope_type = 'COMPANY'
                        AND scope.company_id = baseline_company_id
                        AND scope.organization_id IS NULL
                    )
              )
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Synthetic admin scope changed; manual review required';
    END IF;

    UPDATE local_account account
    JOIN auth_principal principal
      ON principal.principal_id = account.principal_id
    SET account.status = 'DISABLED',
        account.locked_until = NULL,
        account.session_epoch = account.session_epoch + 1,
        account.row_version = account.row_version + 1,
        account.updated_by = 'POST_V30_FINALIZATION',
        account.updated_at = CURRENT_TIMESTAMP(6)
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND account.status <> 'DISABLED'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE auth_principal principal
    JOIN local_account account
      ON account.principal_id = principal.principal_id
    SET principal.status = 'INACTIVE',
        principal.row_version = principal.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND principal.status <> 'INACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE password_reset_grant grant_record
    JOIN local_account account
      ON account.account_id = grant_record.account_id
    SET grant_record.used_at = CURRENT_TIMESTAMP(6),
        grant_record.row_version = grant_record.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND grant_record.used_at IS NULL
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    INSERT INTO session_revocation (
        revocation_id, session_id, account_id, reason,
        revoked_by, request_id, revoked_at
    )
    SELECT UUID(), session_record.session_id, session_record.account_id,
           'W3_BASELINE_ACCOUNT_DISABLED',
           'POST_V30_FINALIZATION',
           'post-v30-finalization',
           CURRENT_TIMESTAMP(6)
    FROM user_session session_record
    JOIN local_account account
      ON account.account_id = session_record.account_id
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND session_record.status = 'ACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    UPDATE user_session session_record
    JOIN local_account account
      ON account.account_id = session_record.account_id
    SET session_record.status = 'REVOKED',
        session_record.revoked_at = CURRENT_TIMESTAMP(6),
        session_record.revocation_reason = 'W3_BASELINE_ACCOUNT_DISABLED',
        session_record.row_version = session_record.row_version + 1
    WHERE account.display_name = 'WAVE-1 本地合成管理员'
      AND session_record.status = 'ACTIVE'
      AND (
          account.username IN (
              'synthetic.local.admin',
              'synthetic.acceptance.20260801',
              'synthetic.acceptance.20260803'
          )
          OR account.username REGEXP
             '^w3_synthetic_bootstrap_[[:xdigit:]]{16,64}$'
      );

    SELECT employee_id INTO synthetic_employee_id
    FROM employee
    WHERE employee_number = 'SYNTHETIC-EMP-001'
      AND display_name = '合成员工甲'
      AND company_id = baseline_company_id
    FOR UPDATE;

    SELECT organization.organization_id INTO synthetic_organization_id
    FROM organization_identity organization
    JOIN organization_current_projection projection
      ON projection.organization_id = organization.organization_id
    JOIN organization_version version
      ON version.organization_version_id = projection.current_version_id
    WHERE version.code = 'SYNTHETIC_ACCEPTANCE_DEPT'
      AND version.name = '合成验收部门'
      AND organization.company_id = baseline_company_id
    FOR UPDATE;

    IF (synthetic_employee_id IS NULL AND synthetic_organization_id IS NOT NULL)
       OR (synthetic_employee_id IS NOT NULL AND synthetic_organization_id IS NULL) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Synthetic acceptance data is incomplete; manual review required';
    END IF;

    IF synthetic_employee_id IS NULL
       AND (
           EXISTS (
               SELECT 1
               FROM attendance_report_projection
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
                 AND period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_report_refresh_record
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
           )
           OR EXISTS (
               SELECT 1
               FROM people_idempotency_record record
               WHERE record.action_code = 'EMPLOYEE_CREATE'
                 AND record.resource_id IS NOT NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM employee
                     WHERE employee.employee_id = record.resource_id
                 )
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_setup_idempotency record
               WHERE record.operation_code = 'CREATE_ASSIGNMENT'
                 AND record.resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
                 AND record.resource_id IS NOT NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM employee
                     WHERE employee.employee_id = record.resource_id
                 )
           )
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Orphaned acceptance data requires manual review';
    END IF;

    IF synthetic_employee_id IS NOT NULL THEN
        SELECT principal_id INTO synthetic_principal_id
        FROM auth_principal
        WHERE employee_id = synthetic_employee_id
          AND status = 'ACTIVE'
        FOR UPDATE;

        SELECT account_id INTO synthetic_account_id
        FROM local_account
        WHERE principal_id = synthetic_principal_id
          AND username = 'synthetic.employee.20260801'
          AND normalized_username = 'synthetic.employee.20260801'
          AND status = 'ACTIVE'
        FOR UPDATE;

        IF synthetic_principal_id IS NULL
           OR synthetic_account_id IS NULL
           OR (
               SELECT COUNT(*)
               FROM employment_assignment
               WHERE employee_id = synthetic_employee_id
                 AND organization_id = synthetic_organization_id
           ) <> 1
           OR (SELECT COUNT(*) FROM employment_assignment
               WHERE employee_id = synthetic_employee_id) <> 1
           OR EXISTS (
               SELECT 1
               FROM employment_assignment
               WHERE organization_id = synthetic_organization_id
                 AND employee_id <> synthetic_employee_id
           )
           OR EXISTS (
               SELECT 1
               FROM organization_version
               WHERE parent_organization_id = synthetic_organization_id
           )
           OR EXISTS (
               SELECT 1
               FROM auth_data_scope
               WHERE organization_id = synthetic_organization_id
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Synthetic acceptance data shape changed; manual review required';
        END IF;

        -- This acceptance fixture has a known, bounded footprint. Any count
        -- drift means it has been reused and must be reviewed instead of
        -- being silently deleted by a deployment script.
        IF (SELECT COUNT(*) FROM attendance_feedback
            WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_feedback_reply reply
               JOIN attendance_feedback feedback
                 ON feedback.attendance_feedback_id = reply.attendance_feedback_id
               WHERE feedback.employee_id = synthetic_employee_id
           ) <> 1
           OR (SELECT COUNT(*) FROM user_session
               WHERE account_id = synthetic_account_id) <> 15
           OR (SELECT COUNT(*) FROM session_revocation
               WHERE account_id = synthetic_account_id) <> 13
           OR (SELECT COUNT(*) FROM login_failure_window
               WHERE account_id = synthetic_account_id) <> 1
           OR (SELECT COUNT(*) FROM password_credential
               WHERE account_id = synthetic_account_id) <> 1
           OR (SELECT COUNT(*) FROM password_reset_grant
               WHERE account_id = synthetic_account_id) <> 0
           OR (SELECT COUNT(*) FROM auth_principal_role_assignment
               WHERE principal_id = synthetic_principal_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM auth_principal_role_assignment assignment
               JOIN auth_role role ON role.role_id = assignment.role_id
               JOIN auth_data_scope scope
                 ON scope.scope_id = assignment.data_scope_id
               WHERE assignment.principal_id = synthetic_principal_id
                 AND role.role_code = 'EMPLOYEE_SELF'
                 AND scope.scope_type = 'SELF'
                 AND scope.company_id IS NULL
                 AND scope.organization_id IS NULL
           ) <> 1
           OR (SELECT COUNT(*) FROM attendance_report_daily_fact
               WHERE employee_id = synthetic_employee_id) <> 5
           OR (SELECT COUNT(*) FROM attendance_report_exception_fact
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_report_time_account_fact
               WHERE employee_id = synthetic_employee_id) <> 4
           OR (SELECT COUNT(*) FROM attendance_report_oa_fact
               WHERE employee_id = synthetic_employee_id) <> 0
           OR (SELECT COUNT(*) FROM annual_leave_entitlement_projection
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM time_account
               WHERE employee_id = synthetic_employee_id) <> 2
           OR (
               SELECT COUNT(*)
               FROM time_account
               WHERE employee_id = synthetic_employee_id
                 AND account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
                 AND balance_hours = 0
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM time_account_ledger_entry ledger
               JOIN time_account account
                 ON account.time_account_id = ledger.time_account_id
               WHERE account.employee_id = synthetic_employee_id
           ) <> 0
           OR (SELECT COUNT(*) FROM attendance_group_assignment
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_assignment_timeline
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_group_assignment assignment
               JOIN attendance_group_revision revision
                 ON revision.attendance_group_revision_id
                    = assignment.attendance_group_revision_id
               JOIN attendance_group attendance_group
                 ON attendance_group.attendance_group_id
                    = revision.attendance_group_id
               JOIN attendance_assignment_timeline timeline
                 ON timeline.attendance_group_assignment_id
                    = assignment.attendance_group_assignment_id
               WHERE assignment.employee_id = synthetic_employee_id
                 AND attendance_group.company_id = baseline_company_id
                 AND attendance_group.group_code = 'DEFAULT_ATTENDANCE'
                 AND timeline.state = 'ACTIVE'
                 AND timeline.business_effective_from = '2026-08-01'
           ) <> 1
           OR (SELECT COUNT(*) FROM employee_current_projection
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM employee_version
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM employee_current_projection projection
               JOIN employee_version version
                 ON version.employee_version_id = projection.current_version_id
               WHERE projection.employee_id = synthetic_employee_id
                 AND version.employee_id = synthetic_employee_id
                 AND version.status = 'ACTIVE'
                 AND version.source_authority = 'LOCAL'
                 AND version.current_marker = 1
           ) <> 1
           OR (SELECT COUNT(*) FROM employee_source_binding
               WHERE employee_id = synthetic_employee_id) <> 0
           OR (SELECT COUNT(*) FROM employment_period_identity
               WHERE employee_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM people_idempotency_record
               WHERE action_code = 'EMPLOYEE_CREATE'
                 AND resource_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM attendance_setup_idempotency
               WHERE operation_code = 'CREATE_ASSIGNMENT'
                 AND resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
                 AND resource_id = synthetic_employee_id) <> 1
           OR (SELECT COUNT(*) FROM organization_current_projection
               WHERE organization_id = synthetic_organization_id) <> 1
           OR (SELECT COUNT(*) FROM organization_version
               WHERE organization_id = synthetic_organization_id) <> 1
           OR (
               SELECT COUNT(*)
               FROM organization_current_projection projection
               JOIN organization_version version
                 ON version.organization_version_id = projection.current_version_id
               WHERE projection.organization_id = synthetic_organization_id
                 AND version.organization_id = synthetic_organization_id
                 AND version.parent_organization_id IS NULL
                 AND version.org_type = 'DEPARTMENT'
                 AND version.status = 'ACTIVE'
                 AND version.source_authority = 'LOCAL'
                 AND version.current_marker = 1
           ) <> 1
           OR (SELECT COUNT(*) FROM organization_source_binding
               WHERE organization_id = synthetic_organization_id) <> 0
           OR (
               SELECT COUNT(*)
               FROM organization_current_closure
               WHERE ancestor_organization_id = synthetic_organization_id
                  OR descendant_organization_id = synthetic_organization_id
           ) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_report_projection
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
                 AND period_end_exclusive = '2026-09-01'
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM attendance_report_refresh_record
               WHERE company_id = baseline_company_id
                 AND period_start = '2026-08-01'
           ) <> 2
           OR (
               SELECT COUNT(*)
               FROM attendance_report_daily_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 5
           OR (
               SELECT COUNT(*)
               FROM attendance_report_exception_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 1
           OR (
               SELECT COUNT(*)
               FROM attendance_report_time_account_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 4
           OR (
               SELECT COUNT(*)
               FROM attendance_report_oa_fact fact
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = fact.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           ) <> 0
           OR EXISTS (
               SELECT 1
               FROM attendance_close_snapshot snapshot
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = snapshot.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_period_precheck precheck
               JOIN attendance_report_projection projection
                 ON projection.attendance_report_projection_id
                    = precheck.attendance_report_projection_id
               WHERE projection.company_id = baseline_company_id
                 AND projection.period_start = '2026-08-01'
                 AND projection.period_end_exclusive = '2026-09-01'
           )
           OR EXISTS (
               SELECT 1
               FROM attendance_report_refresh_record refresh
               WHERE refresh.company_id = baseline_company_id
                 AND refresh.period_start = '2026-08-01'
                 AND (
                     NOT EXISTS (
                         SELECT 1
                         FROM attendance_report_projection current_projection
                         WHERE current_projection.attendance_report_projection_id
                               = refresh.attendance_report_projection_id
                           AND current_projection.company_id = baseline_company_id
                           AND current_projection.period_start = '2026-08-01'
                           AND current_projection.period_end_exclusive = '2026-09-01'
                     )
                     OR (
                         refresh.source_projection_id IS NOT NULL
                         AND NOT EXISTS (
                             SELECT 1
                             FROM attendance_report_projection source_projection
                             WHERE source_projection.attendance_report_projection_id
                                   = refresh.source_projection_id
                               AND source_projection.company_id = baseline_company_id
                               AND source_projection.period_start = '2026-08-01'
                               AND source_projection.period_end_exclusive = '2026-09-01'
                         )
                     )
                 )
           ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Synthetic acceptance row counts changed; manual review required';
        END IF;
    END IF;

    UPDATE company
    SET status = 'INACTIVE'
    WHERE company_id = baseline_company_id
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
      AND status = 'ACTIVE';

    IF synthetic_employee_id IS NOT NULL THEN
        DELETE reply
        FROM attendance_feedback_reply reply
        JOIN attendance_feedback feedback
          ON feedback.attendance_feedback_id = reply.attendance_feedback_id
        WHERE feedback.employee_id = synthetic_employee_id;

        DELETE FROM attendance_feedback
        WHERE employee_id = synthetic_employee_id;

        DELETE FROM session_revocation WHERE account_id = synthetic_account_id;
        DELETE FROM user_session WHERE account_id = synthetic_account_id;
        DELETE FROM login_failure_window WHERE account_id = synthetic_account_id;
        DELETE FROM password_reset_grant WHERE account_id = synthetic_account_id;
        DELETE FROM password_credential WHERE account_id = synthetic_account_id;
        DELETE FROM auth_principal_role_assignment
        WHERE principal_id = synthetic_principal_id;
        DELETE FROM local_account WHERE account_id = synthetic_account_id;

        DELETE FROM attendance_report_exception_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_oa_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_time_account_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM attendance_report_daily_fact
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM annual_leave_entitlement_projection
        WHERE employee_id = synthetic_employee_id;

        DELETE ledger
        FROM time_account_ledger_entry ledger
        JOIN time_account account
          ON account.time_account_id = ledger.time_account_id
        WHERE account.employee_id = synthetic_employee_id;
        DELETE FROM time_account WHERE employee_id = synthetic_employee_id;

        DELETE timeline
        FROM attendance_assignment_timeline timeline
        JOIN attendance_group_assignment assignment
          ON assignment.attendance_group_assignment_id
             = timeline.attendance_group_assignment_id
        WHERE assignment.employee_id = synthetic_employee_id;
        DELETE FROM attendance_group_assignment
        WHERE employee_id = synthetic_employee_id;

        DELETE FROM attendance_setup_idempotency
        WHERE operation_code = 'CREATE_ASSIGNMENT'
          AND resource_type = 'ATTENDANCE_GROUP_ASSIGNMENT'
          AND resource_id = synthetic_employee_id;
        DELETE FROM people_idempotency_record
        WHERE action_code = 'EMPLOYEE_CREATE'
          AND resource_id = synthetic_employee_id;

        DELETE FROM attendance_report_refresh_record
        WHERE company_id = baseline_company_id
          AND period_start = '2026-08-01';
        DELETE FROM attendance_report_projection
        WHERE company_id = baseline_company_id
          AND period_start = '2026-08-01'
          AND period_end_exclusive = '2026-09-01';

        DELETE FROM employee_current_projection
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employee_source_binding
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employee_version
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employment_assignment
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM employment_period_identity
        WHERE employee_id = synthetic_employee_id;
        DELETE FROM auth_principal WHERE principal_id = synthetic_principal_id;
        DELETE FROM employee WHERE employee_id = synthetic_employee_id;

        DELETE FROM organization_current_closure
        WHERE ancestor_organization_id = synthetic_organization_id
           OR descendant_organization_id = synthetic_organization_id;
        DELETE FROM organization_current_projection
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_source_binding
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_version
        WHERE organization_id = synthetic_organization_id;
        DELETE FROM organization_identity
        WHERE organization_id = synthetic_organization_id;

        -- Audit events intentionally remain append-only. Their string
        -- references can outlive a deleted acceptance fixture without
        -- blocking referential cleanup.
    END IF;

    DELETE role_capability
    FROM auth_role_capability role_capability
    JOIN auth_role role ON role.role_id = role_capability.role_id
    WHERE role.role_code IN (
        'MANUFACTURING_SUPERVISOR',
        'MANUFACTURING_CENTER_SUPERVISOR',
        'MANUFACTURING_DIRECTOR',
        'MANUFACTURING_CENTER_DIRECTOR'
    );

    DELETE FROM auth_role
    WHERE role_code IN (
        'MANUFACTURING_SUPERVISOR',
        'MANUFACTURING_CENTER_SUPERVISOR',
        'MANUFACTURING_DIRECTOR',
        'MANUFACTURING_CENTER_DIRECTOR'
    );

    COMMIT;
END$$

DELIMITER ;

CALL finalize_usability_post_v30();
DROP PROCEDURE finalize_usability_post_v30;

-- ============================================================
-- 5/6: punch-window-period-close-bindings (打卡/结算策略)
-- ============================================================
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

-- ============================================================
-- 6/6: dalian-dinner-threshold (大连晚餐考勤规则)
-- ============================================================
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

SET FOREIGN_KEY_CHECKS=1;

-- ============================================================
-- 导入完成，执行验证查询
-- ============================================================
SELECT '=== 验证结果 ===' AS info;
SELECT CONCAT('员工总数: ', COUNT(*)) AS result FROM employee WHERE employment_status = 'ACTIVE';
SELECT CONCAT('公司总数: ', COUNT(*)) AS result FROM company;
SELECT CONCAT('考勤组总数: ', COUNT(*)) AS result FROM attendance_group;
SELECT CONCAT('班次模板总数: ', COUNT(*)) AS result FROM shift_template;

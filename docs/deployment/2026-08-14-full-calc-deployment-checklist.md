# Full Calculation Engine 部署检查清单

## 状态：⚠️ 待验证，不可直接生产部署

### ✅ 已完成
- [x] 代码编译通过
- [x] H2 内存数据库测试通过（369 个测试全部通过）
- [x] 单元测试覆盖新增代码

### ⚠️ 必须验证（部署前）

#### 1. 本地 MySQL 数据验证
**目的**：确认新增的 SQL 查询在真实 MySQL 8.4 上能正确运行

```bash
# 1. 确保本地 shenzhou_hr_dev 数据库已经运行了 V1-V40 迁移
cd backend

# 2. 启动后端（连接真实 MySQL）
export SHENZHOUHR_DB_URL="jdbc:mysql://127.0.0.1:3306/shenzhou_hr_dev"
export SHENZHOUHR_DB_USERNAME="shenzhou_hr_dev_app"
export SHENZHOUHR_DB_PASSWORD="你的密码"
export SHENZHOUHR_FLYWAY_ENABLED=false
./mvnw spring-boot:run
```

**验证项**：
- [ ] 应用能正常启动（健康检查通过）
- [ ] 日志中没有 MyBatis Mapper 错误
- [ ] 访问 `http://127.0.0.1:8080/actuator/health` 返回 UP

#### 2. 数据库查询验证
**目的**：确认新增的 4 个 SQL 查询能返回数据

手动运行以下查询，确认不报错且有合理结果：

```sql
-- 查询 1: 员工身份区间
-- 应该已经存在，只需确认有数据
SELECT COUNT(*) FROM employee WHERE company_id = '你的测试公司ID';

-- 查询 2: 排班工作段
-- 新查询，确认能正常 JOIN
SELECT 
    emp.employee_id,
    cal.business_date,
    seg.start_time,
    seg.end_time
FROM employee emp
JOIN employment_assignment ea ON ea.employee_id = emp.employee_id
JOIN attendance_group_membership agm ON agm.employee_id = emp.employee_id
JOIN attendance_group ag ON ag.attendance_group_id = agm.attendance_group_id
JOIN attendance_group_revision agr ON agr.attendance_group_id = ag.attendance_group_id
JOIN shift_template st ON st.shift_template_id = agr.shift_template_id
JOIN shift_version sv ON sv.shift_template_id = st.shift_template_id
JOIN shift_segment seg ON seg.shift_version_id = sv.shift_version_id
JOIN work_calendar_day cal ON cal.business_date >= CURDATE() - INTERVAL 7 DAY
WHERE emp.company_id = '你的测试公司ID'
LIMIT 5;

-- 查询 3: OA 考勤单据
SELECT 
    oa.source_business_key,
    oa.document_type,
    oa.employee_number,
    oa.start_instant,
    oa.end_instant
FROM oa_attendance_document oa
WHERE oa.effective_candidate = true
LIMIT 5;

-- 查询 4: 考勤策略
-- 当前返回硬编码值，应该能返回 1 行
SELECT 
    15 AS lateGraceMaxMinutes,
    1 AS monthlyLateGraceUses,
    DATE_ADD(LAST_DAY(CURDATE()), INTERVAL 1 DAY) AS correctionDeadline,
    2880 AS overtimeSubmissionDeadlineMinutes
FROM DUAL;
```

**检查项**：
- [ ] 查询 1 返回员工数据
- [ ] 查询 2 不报错（可能返回 0 行，如果没有排班数据）
- [ ] 查询 3 不报错（可能返回 0 行，如果没有 OA 数据）
- [ ] 查询 4 返回 1 行默认策略

#### 3. 报表生成功能测试
**目的**：确认新编排器能生成报表

**前提**：数据库中至少有：
- 1 个公司
- 1 个员工（有雇佣关系、组织归属）
- 1 个月的日历数据
- 一些打卡记录（可选）

**操作**：
1. 前端登录
2. 访问报表页面
3. 选择一个月份
4. 点击"生成报表"或"刷新"

**检查项**：
- [ ] 能成功生成报表（不报错）
- [ ] 报表中有数据行
- [ ] 打开浏览器开发者工具 → Network，检查 API 响应时间（应该 < 30 秒）
- [ ] 检查后端日志，确认使用了 `FullCalculationEngineOrchestrator`

**日志关键字**：
```
FullCalculationEngineOrchestrator
FULL_CALCULATION_V1
ATTENDANCE.FULL_CALC:V1
```

#### 4. 结果准确性抽查
**目的**：确认计算结果符合预期

随机选择 2-3 个员工的 2-3 天数据，人工核对：
- [ ] 首打卡时间 = 实际最早打卡
- [ ] 末打卡时间 = 实际最晚打卡
- [ ] 实际工作分钟数 = 合理值（如 480 分钟 = 8 小时）
- [ ] 缺卡次数 = 合理值（0 或正整数）

**已知限制**：
- ⚠️ 迟到分钟数当前固定为 0（待实现）
- ⚠️ 早退分钟数当前固定为 0（待实现）
- ⚠️ 班次标签显示"计算班次"（待实现）

### ⚠️ 已知问题与限制

#### 功能未完善（不影响基本使用）
1. **迟到/早退时长显示为 0**
   - 原因：需要从 `DailyAttendanceResult.ruleHits` 提取
   - 影响：报表中这两列数据不准确
   - 规避：前端暂时隐藏这两列，或标注"开发中"

2. **班次标签固定显示"计算班次"**
   - 原因：需要从排班数据提取真实班次名称
   - 影响：可读性差
   - 规避：前端补充员工号和日期信息

3. **迟到宽限功能不可用**
   - 原因：`GraceConsumptionSnapshot` 未实现跨天追踪
   - 影响：月度迟到宽限次数不会累计
   - 规避：暂时视为每天都有宽限

#### 性能注意事项
1. **排班查询可能较慢**
   - `findScheduledWorkSegments` 有 7 层 JOIN
   - 建议：在生产环境监控这个查询的执行时间
   - 如果 > 5 秒，需要添加索引或优化查询

2. **计算复杂度增加**
   - 原算法：仅处理打卡首末时间
   - 新算法：完整的排班匹配 + OA 证据整合
   - 预计报表生成时间增加 2-5 倍

### 🚀 部署步骤（验证通过后）

#### 灰度部署建议
```bash
# 阶段 1：开发环境（1-2 天）
# - 在 shenzhou_hr_dev 上验证
# - 至少生成 3 个月的报表并人工核对

# 阶段 2：预发布/测试环境（3-5 天）
# - 使用客户测试数据
# - 让 HR 团队试用并反馈

# 阶段 3：单公司生产试点（1 周）
# - 选择数据量较小的公司
# - 每天生成报表并监控
# - 对比老算法结果（如果有保留）

# 阶段 4：全量发布
# - 所有公司切换到新算法
```

#### 宝塔部署流程
```bash
# 1. 编译打包
cd /path/to/shenzhouHR/backend
./mvnw clean package -DskipTests

# 2. 上传到宝塔服务器
# backend/target/shenzhou-hr-0.0.1-SNAPSHOT.jar

# 3. 停止旧服务
# 在宝塔面板：网站 → Java项目 → 停止

# 4. 替换 jar 文件
# 备份旧 jar
mv shenzhou-hr.jar shenzhou-hr.jar.backup-20260814
# 上传新 jar
# 重命名为 shenzhou-hr.jar

# 5. 启动新服务
# 在宝塔面板：网站 → Java项目 → 启动

# 6. 检查日志
tail -f /www/server/panel/logs/java/shenzhou-hr.log
# 确认启动成功，没有错误
```

#### 数据库迁移
**✅ 无需额外迁移**
- 新代码使用现有表结构
- V1-V40 迁移已经包含所有必需的表

#### 环境变量
**✅ 无需新增环境变量**
- 使用现有的数据库配置
- 使用现有的时区配置 `Asia/Shanghai`

### 🔄 回滚方案

如果部署后发现问题：

#### 快速回滚（5 分钟内）
```bash
# 1. 停止服务
# 在宝塔面板停止 Java 项目

# 2. 恢复旧 jar
mv shenzhou-hr.jar shenzhou-hr.jar.new-failed
mv shenzhou-hr.jar.backup-20260814 shenzhou-hr.jar

# 3. 启动服务
# 在宝塔面板启动 Java 项目
```

#### 代码级回滚（如果需要在本地修改）
```bash
# 编辑 FullCalculationEngineOrchestrator.java
# 注释掉 @Primary 注解

# 或者编辑原有编排器，加上 @Primary
```

### 📊 监控指标

部署后需要监控：
- [ ] 报表生成成功率（目标 > 95%）
- [ ] 报表生成时长（目标 < 30 秒/公司/月）
- [ ] 数据库查询时长（`findScheduledWorkSegments` < 5 秒）
- [ ] CPU/内存使用率（不应显著增加）
- [ ] 错误日志（MyBatis、计算引擎相关）

### ✅ 部署就绪标准

所有以下项必须完成：
- [ ] 本地 MySQL 启动成功
- [ ] 4 个新 SQL 查询都能正常执行
- [ ] 至少成功生成 1 个月的报表
- [ ] 抽查 3 个员工数据准确性
- [ ] 性能可接受（单月 < 30 秒）
- [ ] HR 团队确认报表可用（即使迟到/早退列为 0）

### 📞 支持联系

如果遇到问题：
1. 检查后端日志：`/www/server/panel/logs/java/shenzhou-hr.log`
2. 检查数据库慢查询日志
3. 提供错误堆栈信息

---

**文档版本**: 1.0  
**创建日期**: 2026-08-14  
**最后更新**: 2026-08-14  
**关联**: Full Calculation Engine Integration (方案 B)

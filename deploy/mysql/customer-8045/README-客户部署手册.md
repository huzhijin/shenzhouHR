# 神州HR 客户初始化 SQL 脚本执行手册

**目标环境**：MySQL 8.0.45（宝塔面板）  
**前置条件**：已通过 `install.sh` 完成应用部署和 Flyway 自动迁移（到 V36）  
**执行时机**：首次部署后、应用启动前

---

## 📋 脚本清单与执行顺序

### 1️⃣ 四公司基础数据初始化
**脚本**：`four-company-finalization-post-v30-8045.sql`  
**作用**：创建 4 个公司（江苏神州/晟州/SZSC深圳/SZSC上海）+ 21 名特定员工（大连13人、成都4人、上海1人、晟州3人）+ 班次/考勤组/地点完整配置  
**数据详情**：
- **江苏神州晟州半导体（SZJN）** 3 人：赵俊君、时晨、王颂雅
- **深圳神州深测（SZSC）** 18 人：
  - 大连办事处 13 人：路昊、高攀、李政、王小龙、姜长波、王杰S、李春江、王松、霍岩、张泽、温慧杰、张清雅、戢昱
  - 成都 4 人：周文武、周彦沛、彭帆、唐浩
  - 上海 1 人：张静

### 2️⃣ 共享地点数据收敛
**脚本**：`shared-location-convergence-post-v30-8045.sql`  
**作用**：将旧的 location 表数据迁移到新的 shared_location 体系

### 3️⃣ 可用性优化配置
**脚本**：`usability-finalization-post-v30-8045.sql`  
**作用**：初始化系统默认配置，提升开箱即用体验

### 4️⃣ 打卡/结算策略绑定（V35 必需）
**脚本**：`punch-window-period-close-bindings-post-v35.sql`  
**作用**：为 9 个考勤组创建打卡窗口（PUNCH_WINDOW）和结算（PERIOD_CLOSE）策略绑定（共 18 条）

### 5️⃣ 大连考勤组晚餐扣减规则
**脚本**：`dalian-dinner-threshold-post-v35.sql`  
**作用**：仅大连 13 人：晚餐扣减触发门槛从 0 分钟改为 120 分钟（其他人不受影响）

### 6️⃣ 夏令时下午班次更正（已上线库补丁）
**脚本**：`summer-afternoon-1330-post-v35.sql`  
**回滚**：`ROLLBACK_summer-afternoon-1330-post-v35.sql`  
**作用**：各分公司/办事处夏令时下午由 13:00～18:00 改为 13:30～18:00；午休结束同步改为 13:30。大连、成都固定班次不改。  
**执行时机**：当前已部署库发现夏令时下午仍是 13:00 时执行；可单独跑，不依赖上面 1～5 的顺序。

---

## 🚀 执行步骤

### 方式 A：宝塔面板 phpMyAdmin（推荐新手）

1. 登录宝塔面板 → 数据库 → phpMyAdmin
2. 选择数据库 `shenzhou_hr`
3. 点击「SQL」标签
4. **逐个**复制粘贴上述 5 个脚本内容，**按顺序执行**
5. 每个脚本执行完成后，检查底部是否显示「查询成功」

### 方式 B：MySQL 命令行（推荐运维）

```bash
# 1. 上传所有 SQL 文件到服务器（例如 /root/init-sql/）
scp customer-8045/*.sql root@客户IP:/root/init-sql/

# 2. SSH 登录服务器
ssh root@客户IP

# 3. 按顺序执行（每条命令单独执行，观察输出）
mysql -u root -p shenzhou_hr < /root/init-sql/four-company-finalization-post-v30-8045.sql
mysql -u root -p shenzhou_hr < /root/init-sql/shared-location-convergence-post-v30-8045.sql
mysql -u root -p shenzhou_hr < /root/init-sql/usability-finalization-post-v30-8045.sql
mysql -u root -p shenzhou_hr < /root/init-sql/punch-window-period-close-bindings-post-v35.sql
mysql -u root -p shenzhou_hr < /root/init-sql/dalian-dinner-threshold-post-v35.sql
# 已上线库若夏令时下午仍是 13:00，再执行：
mysql -u root -p shenzhou_hr < /root/init-sql/summer-afternoon-1330-post-v35.sql
```

---

## ✅ 验证步骤

执行完成后，在 MySQL 命令行或 phpMyAdmin 中运行：

```sql
-- 检查公司数量（应为 5：1 个归档 + 4 个正式）
SELECT company_id, code, name, status FROM company ORDER BY code;

-- 检查员工数量（应为 21 人）
SELECT COUNT(*) AS total_employees FROM employee WHERE employment_status = 'ACTIVE';

-- 检查大连考勤组晚餐规则版本（应为 2）
SELECT version_number, trigger_minutes 
FROM attendance_policy_scoped_version v
JOIN attendance_policy_scope s ON v.scope_id = s.scope_id
JOIN attendance_policy_template t ON s.policy_template_id = t.policy_template_id
WHERE t.template_code = 'MEAL_DEDUCTION' 
  AND s.company_id = '41000000-0000-0000-0000-000000000003'
ORDER BY version_number DESC LIMIT 1;
-- 预期结果：version_number=2, trigger_minutes=120
```

---

## ⚠️ 注意事项

1. **幂等性保证**：所有脚本可重复执行，已有数据不会重复插入
2. **版本要求**：脚本已适配 MySQL 8.0.45，其他版本可能报错
3. **执行顺序**：必须按 1→2→3→4→5 顺序执行，脚本 4/5 依赖 1 的公司和考勤组数据
4. **备份建议**：首次执行前建议先备份数据库（宝塔面板 → 数据库 → 备份）
5. **执行耗时**：
   - 脚本 1（四公司初始化）：约 30-60 秒（数据量大）
   - 脚本 2-5：各 1-5 秒

---

## 🔧 故障排查

### 问题：脚本 1 报错「W3 baseline company shape changed」
**原因**：数据库已存在不匹配的公司数据  
**解决**：确认是全新部署（install.sh 刚执行完），如需重置请删库重建后重新运行 install.sh

### 问题：脚本 4/5 报错「考勤组不存在」
**原因**：脚本 1 未成功执行  
**解决**：检查脚本 1 的执行日志，确保无错误后重新执行脚本 4/5

### 问题：版本检查报错「requires MySQL 8.0.45」
**原因**：客户数据库版本不是 8.0.45  
**解决**：
```sql
SELECT VERSION();  -- 查看实际版本
```
如确为 8.0.45 但检测失败，可能是版本字符串带后缀（如 8.0.45-log），请联系技术支持

---

## 📞 技术支持

如遇问题，请提供：
1. 错误信息截图/日志
2. MySQL 版本：`SELECT VERSION();`
3. 已执行到哪个脚本
4. 验证 SQL 的查询结果

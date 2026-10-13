# 考勤系统性能优化任务清单

## 项目背景

**当前问题（已确认的诊断数据）：**
- `attendance_report_daily_fact` 表：1,305,927 行
- 175 个投影，平均每个投影 7,458 条日事实
- 最慢查询：89 秒，扫描 630,753 行返回 1,767 行（扫描比 357:1）
- 问题查询：`findEffectiveOaDocuments` 使用 ROW_NUMBER() 窗口函数在全表上运算
- 每次重算都复制整月数据并新增投影，导致表持续膨胀

**优化目标：**
- 短期（1-2周）：重算时间从 20-30 分钟降到 3-5 分钟
- 中期（1-2月）：重算时间降到 1-2 分钟，表大小稳定在 45 万行
- 长期（2-3月）：重算时间 30 秒-1 分钟，查询 < 500 毫秒

---

## 阶段一：紧急止血（1-2周）

### 任务 1.1：验证并创建 OA 查询索引 ⚡ 最高优先级

**目标：** 将 89 秒的 OA 查询降到 5-10 秒

**负责人：** 待分配

**优先级：** P0（最高）

**预计工时：** 2-3 天

**前置条件：**
- 需要生产数据库访问权限
- 需要预发布环境（如有）

**详细步骤：**

1. **验证数据分布（1 小时）**
   
   在生产数据库执行以下查询，确认索引选择性：
   
   ```sql
   -- 检查 source_status 分布
   SELECT 
     source_status, 
     COUNT(*) as count,
     ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM oa_attendance_document), 2) as pct
   FROM oa_attendance_document
   GROUP BY source_status
   ORDER BY count DESC;
   
   -- 检查 document_type 分布
   SELECT 
     document_type,
     COUNT(*) as count,
     ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM oa_attendance_document), 2) as pct
   FROM oa_attendance_document
   GROUP BY document_type
   ORDER BY count DESC;
   
   -- 检查表大小
   SELECT 
     table_name,
     ROUND(((data_length + index_length) / 1024 / 1024), 2) AS size_mb,
     table_rows
   FROM information_schema.TABLES
   WHERE table_schema = 'shenzhou_hr' 
     AND table_name = 'oa_attendance_document';
   ```
   
   **判断标准：** 如果 `APPROVED/MODIFIED/SUPPLEMENTED/UNKNOWN` 状态总占比 < 30%，索引效果会很好。

2. **预发布环境测试（4-8 小时）**
   
   如果有预发布环境且数据量接近生产：
   
   ```sql
   CREATE INDEX ix_oa_document_status_type_created 
   ON oa_attendance_document(
       source_status, 
       document_type, 
       created_at, 
       oa_attendance_document_id
   );
   ```
   
   - 记录索引创建时间（大致估算：每 1GB 表数据需要 1-3 分钟）
   - 触发一次完整重算
   - 记录重算总耗时
   - 检查慢查询日志，确认 89 秒查询是否降到 10 秒以内

3. **生产环境执行（维护窗口）**
   
   **时机：** 选择凌晨低峰期（建议 2:00-4:00）
   
   **步骤：**
   - 提前通知相关人员维护窗口
   - 如果表超过 5GB，考虑设置正式维护窗口
   - 执行索引创建 SQL（同上）
   - 监控索引创建进度
   - 完成后验证索引是否生效：
     ```sql
     SHOW INDEX FROM oa_attendance_document 
     WHERE Key_name = 'ix_oa_document_status_type_created';
     ```

4. **效果监控（1 周）**
   
   - 持续观察慢查询日志：
     ```bash
     tail -f /www/server/data/mysql-slow.log | grep "oa_attendance_document"
     ```
   - 记录至少 3 次完整重算的耗时
   - 确认是否出现新的慢查询
   - 收集性能数据作为后续优化基准

**验收标准：**
- ✅ OA 查询耗时从 89 秒降到 10 秒以内
- ✅ 重算整体时间减少 50% 以上
- ✅ 慢查询日志中不再出现 89 秒的 OA 查询

**产出物：**
- 数据分布分析报告
- 索引创建脚本
- 性能对比数据（优化前后）

**风险点：**
- 大表加索引可能锁表几分钟到几十分钟
- 如果数据分布不理想（目标状态占比 > 50%），索引效果会打折扣

---

### 任务 1.2：降低查询超时时间

**目标：** 让卡住的查询尽快失败释放连接，避免长时间占用资源

**负责人：** 待分配

**优先级：** P1

**预计工时：** 1 天

**前置条件：**
- 无

**详细步骤：**

1. **修改 Mapper 配置（2 小时）**
   
   编辑文件：`backend/src/main/resources/mappers/AttendanceReportCalculationMapper.xml`
   
   查找所有 `timeout="3600"` 并改为 `timeout="300"`（从 1 小时改为 5 分钟）
   
   涉及的查询方法：
   - `findEffectiveOaDocuments`
   - `findReportableOaDocuments`
   - `findActivatedPunchEvents`
   - `findScheduledWorkSegments`
   - `findAttendancePolicies`
   - `findPublishedCalendarDays`
   - 其他所有标记 `timeout="3600"` 的查询

2. **本地测试（1 小时）**
   
   - 启动后端服务
   - 触发一次重算
   - 确认超时配置生效（如果查询超过 5 分钟，应该抛出超时异常）

3. **预发布环境验证（2 小时）**
   
   - 部署到预发布环境
   - 触发完整重算
   - 监控日志，确认没有查询超过 5 分钟
   - 如果有超时，记录具体是哪个查询，作为后续优化目标

4. **合并到主分支（1 小时）**
   
   - 代码审查
   - 合并到下一次发版分支
   - 更新 CHANGELOG

**验收标准：**
- ✅ 所有计算查询超时时间改为 300 秒
- ✅ 预发布环境测试通过，没有误伤正常查询
- ✅ 代码已合并，等待下次发版

**产出物：**
- 修改后的 Mapper 文件
- 测试报告

**风险点：**
- 低。如果优化后查询仍需 > 5 分钟，说明有更深层的性能问题需要单独解决

---

### 任务 1.3：性能监控和基准数据收集

**目标：** 建立性能基准，为后续优化提供对比数据

**负责人：** 待分配

**优先级：** P1

**预计工时：** 1 周（持续观察）

**前置条件：**
- 任务 1.1 完成

**详细步骤：**

1. **配置监控脚本（2 小时）**
   
   创建监控脚本记录关键指标：
   
   ```bash
   #!/bin/bash
   # perf-monitor.sh
   
   LOG_FILE="/var/log/attendance_perf.log"
   
   while true; do
       echo "=== $(date) ===" >> $LOG_FILE
       
       # 慢查询统计
       mysql -u root -p'password' shenzhou_hr -e "
       SELECT 
         LEFT(sql_text, 100) as query_snippet,
         COUNT(*) as count,
         AVG(query_time) as avg_time,
         MAX(query_time) as max_time
       FROM mysql.slow_log
       WHERE start_time > DATE_SUB(NOW(), INTERVAL 1 HOUR)
       GROUP BY LEFT(sql_text, 100)
       ORDER BY avg_time DESC
       LIMIT 5;
       " >> $LOG_FILE
       
       # 表大小
       mysql -u root -p'password' shenzhou_hr -e "
       SELECT 
         table_name,
         table_rows,
         ROUND(((data_length + index_length) / 1024 / 1024), 2) AS size_mb
       FROM information_schema.TABLES
       WHERE table_schema = 'shenzhou_hr'
         AND table_name LIKE 'attendance_report%'
       ORDER BY table_rows DESC;
       " >> $LOG_FILE
       
       sleep 3600  # 每小时记录一次
   done
   ```

2. **收集重算性能数据（1 周）**
   
   记录每次重算的关键指标：
   - 开始时间和结束时间
   - 总耗时
   - 涉及的公司和员工数量
   - 慢查询日志中的 Top 5 查询
   
   建立 Excel 表格或数据库表记录：
   
   | 日期 | 公司 | 员工数 | 重算类型 | 总耗时（秒） | OA查询耗时 | 打卡查询耗时 | 班次查询耗时 | 落库耗时 |
   |------|------|--------|----------|------------|-----------|------------|------------|---------|
   | ... | ... | ... | 整月 | ... | ... | ... | ... | ... |

3. **分析性能瓶颈分布（1 天）**
   
   根据收集的数据，分析：
   - 哪些查询仍然很慢（Top 3）
   - 重算耗时分布（查询输入 vs 内存计算 vs 落库）
   - 表大小增长速度
   
   生成分析报告，作为阶段二优化的输入

**验收标准：**
- ✅ 监控脚本已部署并运行
- ✅ 至少收集了 1 周的性能数据
- ✅ 有完整的性能分析报告

**产出物：**
- 监控脚本
- 性能数据表格
- 性能分析报告

---

## 阶段二：结构性改进（1-2个月）

### 任务 2.1：批量写入投影数据

**目标：** 减少数据库往返次数，降低落库耗时 70-80%

**负责人：** 待分配

**优先级：** P0

**预计工时：** 1 周

**前置条件：**
- 任务 1.1、1.3 完成，确认落库是性能瓶颈之一

**详细步骤：**

1. **设计批量插入接口（1 天）**
   
   在 `AttendanceReportProjectionWriteMapper.xml` 新增批量方法：
   
   ```xml
   <!-- 批量插入日事实 -->
   <insert id="batchAppendDailyFacts">
       INSERT INTO attendance_report_daily_fact (
           attendance_report_daily_fact_id,
           attendance_report_projection_id,
           company_id,
           employee_id,
           business_date,
           day_type,
           <!-- 其他字段 -->
       ) VALUES
       <foreach collection="facts" item="fact" separator=",">
           (
               UUID(),
               #{fact.projectionId},
               #{fact.companyId},
               #{fact.employeeId},
               #{fact.businessDate},
               #{fact.dayType}
               <!-- 其他字段 -->
           )
       </foreach>
   </insert>
   
   <!-- 同样为其他事实表创建批量方法 -->
   <insert id="batchAppendOaFacts">...</insert>
   <insert id="batchAppendExceptionFacts">...</insert>
   <insert id="batchAppendTimeAccountFacts">...</insert>
   ```

2. **修改 Publisher 实现批量提交（2 天）**
   
   编辑 `AttendanceReportProjectionPublisher.java`：
   
   ```java
   // 当前实现（逐条插入）
   for (DailyFact fact : facts) {
       writer.appendDailyFact(projectionId, fact); // N 次数据库调用
   }
   
   // 改为批量（每 500 条提交一次）
   List<DailyFact> batch = new ArrayList<>();
   for (DailyFact fact : facts) {
       batch.add(fact);
       if (batch.size() >= 500) {
           writer.batchAppendDailyFacts(projectionId, batch);
           batch.clear();
       }
   }
   if (!batch.isEmpty()) {
       writer.batchAppendDailyFacts(projectionId, batch);
   }
   ```
   
   对其他事实表（OA、异常、时间账户）做同样改造。

3. **单元测试（1 天）**
   
   - 测试批量插入逻辑正确性
   - 测试边界情况（空列表、单条记录、501 条记录）
   - 测试事务回滚（批量插入失败时是否正确回滚）

4. **集成测试和性能测试（2 天）**
   
   - 在预发布环境触发完整重算
   - 对比优化前后的落库耗时
   - 验证数据一致性（比对优化前后的投影数据是否完全一致）

**验收标准：**
- ✅ 批量插入方法已实现并测试通过
- ✅ 写入时间减少 70% 以上
- ✅ 数据一致性测试通过（优化前后结果一致）

**产出物：**
- 修改后的 Mapper 和 Publisher 代码
- 单元测试和集成测试
- 性能对比报告

**风险点：**
- 批量插入失败时事务回滚逻辑需要仔细测试
- 大批量（> 1000 条）可能超过 MySQL `max_allowed_packet` 限制

---

### 任务 2.2：投影模型重构 - 设计阶段

**目标：** 从"追加型投影"改为"可变投影"，解决表膨胀根本问题

**负责人：** 待分配

**优先级：** P0

**预计工时：** 2 周

**前置条件：**
- 需要深入理解当前投影模型
- 需要评审委员会参与设计评审

**详细步骤：**

1. **现状分析和问题梳理（2 天）**
   
   分析当前投影模型的问题：
   - 为什么每次重算都新建投影？
   - "不可变投影"的设计初衷是什么？
   - 有哪些业务场景依赖"历史投影"？
   - 查询是否需要读取多个投影版本？
   
   输出《当前投影模型分析报告》

2. **新模型设计（3 天）**
   
   **核心设计思路：**
   ```
   旧模型：一个公司一个月可能有多个投影（每次重算创建一个）
   新模型：一个公司一个月只有一个投影（重算时更新）
   ```
   
   **Schema 变更设计：**
   ```sql
   -- 1. 投影表添加年月字段
   ALTER TABLE attendance_report_projection 
   ADD COLUMN data_year INT NOT NULL,
   ADD COLUMN data_month INT NOT NULL,
   ADD UNIQUE KEY uq_projection_company_month (company_id, data_year, data_month);
   
   -- 2. 事实表添加唯一约束，支持 UPSERT
   ALTER TABLE attendance_report_daily_fact
   ADD UNIQUE KEY uq_daily_fact (attendance_report_projection_id, employee_id, business_date);
   
   ALTER TABLE attendance_report_oa_fact
   ADD UNIQUE KEY uq_oa_fact (attendance_report_projection_id, employee_id, source_business_key);
   
   ALTER TABLE attendance_report_exception_fact
   ADD UNIQUE KEY uq_exception_fact (attendance_report_projection_id, employee_id, business_date, exception_code);
   
   ALTER TABLE attendance_report_time_account_fact
   ADD UNIQUE KEY uq_time_account_fact (attendance_report_projection_id, employee_id, account_type);
   ```
   
   **应用逻辑变更：**
   ```java
   // 旧逻辑：每次新建投影
   ProjectionDraft draft = createDraftProjection();
   writer.copyDailyFactsOutsideRange(oldProjection, draft, windowStart, windowEnd);
   writer.appendDailyFacts(draft, windowFacts);
   writer.publishProjection(draft);
   
   // 新逻辑：查找或创建投影，直接 UPSERT
   StoredProjection projection = writer.findOrCreateProjection(companyId, yearMonth);
   writer.upsertDailyFacts(projection, windowFacts);  // INSERT ... ON DUPLICATE KEY UPDATE
   ```

3. **历史数据迁移策略设计（2 天）**
   
   **方案：只保留最近 3 个月数据，旧数据归档**
   
   ```sql
   -- 创建归档表（结构与原表相同）
   CREATE TABLE attendance_report_projection_archive LIKE attendance_report_projection;
   CREATE TABLE attendance_report_daily_fact_archive LIKE attendance_report_daily_fact;
   CREATE TABLE attendance_report_oa_fact_archive LIKE attendance_report_oa_fact;
   CREATE TABLE attendance_report_exception_fact_archive LIKE attendance_report_exception_fact;
   CREATE TABLE attendance_report_time_account_fact_archive LIKE attendance_report_time_account_fact;
   
   -- 迁移 3 个月以前的数据到归档表
   INSERT INTO attendance_report_projection_archive
   SELECT * FROM attendance_report_projection
   WHERE data_as_of < DATE_SUB(CURDATE(), INTERVAL 3 MONTH);
   
   -- 依次迁移其他事实表...
   
   -- 删除已归档数据
   DELETE FROM attendance_report_daily_fact
   WHERE business_date < DATE_SUB(CURDATE(), INTERVAL 3 MONTH);
   ```
   
   **需要确认的业务问题：**
   - 是否有报表需要查询 3 个月以前的数据？
   - 归档数据的查询频率如何？
   - 是否需要提供"归档数据查询"功能？

4. **编写技术设计文档（3 天）**
   
   文档结构：
   - 背景和目标
   - 当前模型问题分析
   - 新模型设计方案
     - 数据库 Schema 变更
     - 应用代码变更
     - 数据迁移方案
   - 兼容性分析（对现有功能的影响）
   - 风险评估
   - 实施计划（分阶段上线策略）
   - 回滚方案
   
   输出《投影模型重构技术设计文档》

5. **设计评审（2 天）**
   
   召集相关人员评审：
   - 后端开发
   - DBA
   - 测试
   - 产品（确认业务影响）
   
   收集反馈并修订设计

**验收标准：**
- ✅ 技术设计文档完成
- ✅ 设计评审通过，所有关键问题已解答
- ✅ 数据迁移方案经 DBA 确认可行

**产出物：**
- 《当前投影模型分析报告》
- 《投影模型重构技术设计文档》
- 设计评审会议纪要

**风险点：**
- 架构变更影响面大，必须充分测试
- 历史数据迁移可能影响在线服务，需要选择维护窗口
- 如果有功能依赖"多版本投影"，需要重新设计

---

### 任务 2.3：投影模型重构 - 开发阶段

**目标：** 实现新投影模型的所有代码变更

**负责人：** 待分配

**优先级：** P0

**预计工时：** 2 周

**前置条件：**
- 任务 2.2 完成并通过评审

**详细步骤：**

1. **数据库 Schema 变更脚本（1 天）**
   
   编写 Flyway 迁移脚本：
   
   ```sql
   -- V99__refactor_projection_model.sql
   
   -- 1. 创建归档表
   CREATE TABLE attendance_report_projection_archive LIKE attendance_report_projection;
   CREATE TABLE attendance_report_daily_fact_archive LIKE attendance_report_daily_fact;
   -- ... 其他归档表
   
   -- 2. 投影表添加年月字段
   ALTER TABLE attendance_report_projection 
   ADD COLUMN data_year INT,
   ADD COLUMN data_month INT;
   
   -- 3. 回填 data_year 和 data_month
   UPDATE attendance_report_projection
   SET data_year = YEAR(data_as_of),
       data_month = MONTH(data_as_of);
   
   -- 4. 设置 NOT NULL 约束
   ALTER TABLE attendance_report_projection 
   MODIFY COLUMN data_year INT NOT NULL,
   MODIFY COLUMN data_month INT NOT NULL;
   
   -- 5. 添加唯一约束
   ALTER TABLE attendance_report_projection 
   ADD UNIQUE KEY uq_projection_company_month (company_id, data_year, data_month);
   
   -- 6. 事实表添加唯一约束
   ALTER TABLE attendance_report_daily_fact
   ADD UNIQUE KEY uq_daily_fact (attendance_report_projection_id, employee_id, business_date);
   -- ... 其他事实表
   ```

2. **修改 Mapper 实现 UPSERT（2 天）**
   
   在 `AttendanceReportProjectionWriteMapper.xml` 中：
   
   ```xml
   <!-- 查找或创建投影 -->
   <select id="findProjectionByCompanyMonth" resultType="StoredProjection">
       SELECT * FROM attendance_report_projection
       WHERE company_id = #{companyId}
         AND data_year = #{year}
         AND data_month = #{month}
       FOR UPDATE
   </select>
   
   <insert id="createProjection">
       INSERT INTO attendance_report_projection (...)
       VALUES (UUID(), #{companyId}, #{year}, #{month}, ...)
       ON DUPLICATE KEY UPDATE
         updated_at = NOW(),
         data_as_of = #{dataAsOf}
   </insert>
   
   <!-- UPSERT 日事实 -->
   <insert id="upsertDailyFacts">
       INSERT INTO attendance_report_daily_fact (...)
       VALUES
       <foreach collection="facts" item="fact" separator=",">
           (UUID(), #{fact.projectionId}, #{fact.employeeId}, ...)
       </foreach>
       ON DUPLICATE KEY UPDATE
         day_type = VALUES(day_type),
         scheduled_minutes = VALUES(scheduled_minutes),
         <!-- 其他字段 -->
         updated_at = NOW()
   </insert>
   ```

3. **修改 Publisher 实现 find-or-create 逻辑（3 天）**
   
   编辑 `AttendanceReportProjectionPublisher.java`：
   
   ```java
   @Transactional
   public PublicationResult publish(PublishCommand command, LocalDate windowStart, LocalDate windowEnd) {
       // 查找或创建投影
       YearMonth yearMonth = YearMonth.from(command.periodEnd());
       StoredProjection projection = writer.findProjectionByCompanyMonth(
           command.companyId(), 
           yearMonth.getYear(), 
           yearMonth.getMonthValue()
       );
       
       if (projection == null) {
           projection = writer.createProjection(
               command.companyId(),
               yearMonth.getYear(),
               yearMonth.getMonthValue(),
               command.dataAsOf()
           );
       }
       
       // 直接 UPSERT，不需要 copy
       writer.upsertDailyFacts(projection.id(), command.dailyFacts());
       writer.upsertOaFacts(projection.id(), command.oaFacts());
       writer.upsertExceptionFacts(projection.id(), command.exceptionFacts());
       writer.upsertTimeAccountFacts(projection.id(), command.timeAccountFacts());
       
       return PublicationResult.success(projection.id());
   }
   ```

4. **修改查询侧代码（1 天）**
   
   确认所有查询投影的地方不再依赖"找最新投影"逻辑：
   
   ```java
   // 旧逻辑：查找最新投影
   StoredProjection latest = findLatestProjection(companyId, yearMonth);
   
   // 新逻辑：直接查找唯一投影
   StoredProjection projection = findProjectionByCompanyMonth(companyId, year, month);
   ```

5. **单元测试和集成测试（3 天）**
   
   - 测试 find-or-create 逻辑
   - 测试 UPSERT 正确更新已有记录
   - 测试窄窗口重算（只更新 3 天数据）
   - 测试整月重算（更新 30 天数据）
   - 测试并发重算（同一个投影被多个线程同时更新）

**验收标准：**
- ✅ 所有代码变更完成并通过代码审查
- ✅ 单元测试覆盖率 > 80%
- ✅ 集成测试通过，数据一致性验证通过

**产出物：**
- Flyway 迁移脚本
- 修改后的 Mapper 和 Publisher 代码
- 单元测试和集成测试

**风险点：**
- UPSERT 在高并发下可能有死锁风险
- 需要仔细测试事务隔离级别

---

### 任务 2.4：投影模型重构 - 灰度发布

**目标：** 安全上线新模型，确保数据正确性和性能提升

**负责人：** 待分配

**优先级：** P0

**预计工时：** 3 周

**前置条件：**
- 任务 2.3 完成
- 预发布环境测试通过

**详细步骤：**

1. **预发布环境完整测试（1 周）**
   
   - 执行数据迁移脚本
   - 回填历史数据
   - 触发多次重算，验证数据一致性
   - 性能测试，确认重算时间和表大小符合预期
   - 压力测试，确认高并发下无问题

2. **生产环境数据迁移（维护窗口 2-4 小时）**
   
   **时机：** 选择周末凌晨维护窗口
   
   **步骤：**
   - 停止自动重算任务
   - 执行 Schema 变更脚本
   - 执行数据迁移（归档旧数据）
   - 验证数据迁移完成
   - 启动新版本应用
   - 触发一次测试重算
   - 监控日志和性能指标
   - 确认无问题后恢复自动任务

3. **新旧模型并行运行（1 周，可选）**
   
   如果担心风险，可以先双写验证：
   - 新模型写入生产表
   - 旧模型写入临时表
   - 对比两个表的数据是否一致
   - 确认无问题后切换查询到新表

4. **观察和监控（1 周）**
   
   - 每天检查慢查询日志
   - 每天检查表大小变化
   - 每天记录重算耗时
   - 收集用户反馈
   - 如果发现问题，立即回滚

5. **清理旧投影数据（维护窗口）**
   
   确认新模型运行稳定 1 周后：
   - 将旧投影数据完整归档
   - 删除生产表中的冗余投影
   - 回收磁盘空间

**验收标准：**
- ✅ 生产环境数据迁移成功，无数据丢失
- ✅ 重算时间降到 1-2 分钟
- ✅ 日事实表稳定在 45 万行左右（500 人 × 30 天 × 30 月）
- ✅ 查询时间 < 1 秒
- ✅ 运行 1 周无严重问题

**产出物：**
- 数据迁移执行报告
- 性能对比报告（优化前后）
- 上线总结文档

**风险点：**
- 数据迁移失败可能导致服务中断
- 需要准备完整的回滚方案
- 建议先在预发布环境完整演练一遍

---

### 任务 2.5：优化打卡事件查询

**目标：** 消除相关子查询和 OR 条件，提升打卡事件查询性能 5-10 倍

**负责人：** 待分配

**优先级：** P1

**预计工时：** 1 周

**前置条件：**
- 任务 1.3 完成，确认打卡事件查询是瓶颈之一

**详细步骤：**

1. **分析当前查询问题（1 天）**
   
   当前查询（`AttendanceReportCalculationMapper.xml` 第 123-138 行）：
   
   ```sql
   SELECT roster_employee.employee_id, event.point_instant
   FROM effective_attendance_event event
   JOIN employee roster_employee
     ON roster_employee.company_id = #{companyId}
    AND (
           roster_employee.employee_id = event.employee_id
           OR roster_employee.employee_number = (
               SELECT punch_employee.employee_number
               FROM employee punch_employee
               WHERE punch_employee.employee_id = event.employee_id
           )
    )
   WHERE event.event_kind = 'PUNCH_POINT' ...
   ```
   
   **问题：**
   - `OR` 条件让索引失效
   - 相关子查询对每一行都执行一次 SELECT
   - 大表 JOIN 会非常慢

2. **设计优化方案（1 天）**
   
   **方案 A：用 CTE 预连接**
   
   ```sql
   WITH matched_employees AS (
       SELECT DISTINCT
           event_emp.employee_id as event_employee_id,
           roster_emp.employee_id as roster_employee_id
       FROM employee roster_emp
       JOIN employee event_emp 
         ON roster_emp.employee_number = event_emp.employee_number
       WHERE roster_emp.company_id = #{companyId}
   )
   SELECT me.roster_employee_id, event.point_instant
   FROM effective_attendance_event event
   JOIN matched_employees me 
     ON me.event_employee_id = event.employee_id
   WHERE event.event_kind = 'PUNCH_POINT' ...
   ```
   
   **方案 B：用临时表（如果 CTE 性能仍不够）**
   
   先创建临时表存储员工映射，再 JOIN。

3. **实现和测试（2 天）**
   
   - 修改 `findActivatedPunchEvents` 查询
   - 在测试环境执行新旧查询，对比性能
   - 验证员工匹配逻辑正确性（特别是跨公司打卡的情况）

4. **集成测试（2 天）**
   
   - 在预发布环境触发完整重算
   - 对比优化前后的重算结果是否一致
   - 确认打卡事件查询耗时减少 5-10 倍

**验收标准：**
- ✅ 打卡事件查询快 5-10 倍
- ✅ 员工匹配逻辑测试通过（包括边界情况）
- ✅ 重算结果与优化前完全一致

**产出物：**
- 优化后的查询 SQL
- 性能对比报告
- 测试报告

**风险点：**
- 员工匹配逻辑比较复杂，需要仔细验证
- 如果 CTE 性能仍不够，可能需要改用临时表或者物化视图

---

### 任务 2.6：反规范化班次模板名称

**目标：** 避免从审计表反推班次名称，提升班次查询性能 10-20 倍

**负责人：** 待分配

**优先级：** P2

**预计工时：** 1 周

**前置条件：**
- 任务 1.3 完成，确认班次查询是瓶颈之一

**详细步骤：**

1. **Schema 变更（1 天）**
   
   ```sql
   ALTER TABLE scheduled_work_segment 
   ADD COLUMN shift_template_name VARCHAR(255) COMMENT '班次模板名称',
   ADD COLUMN is_active BOOLEAN DEFAULT TRUE COMMENT '是否启用';
   
   -- 为新字段创建索引（如果需要按班次名称查询）
   CREATE INDEX ix_shift_template_name ON scheduled_work_segment(shift_template_name);
   ```

2. **编写回填脚本（2 天）**
   
   从审计表一次性回填现有数据：
   
   ```sql
   -- 这个查询逻辑从当前的 findScheduledWorkSegments 中提取
   UPDATE scheduled_work_segment sws
   JOIN (
       SELECT 
           segment_id,
           template_name,
           is_active
       FROM (
           SELECT 
               segment.scheduled_work_segment_id as segment_id,
               audit.template_name,
               audit.is_active,
               ROW_NUMBER() OVER (
                   PARTITION BY segment.scheduled_work_segment_id
                   ORDER BY CAST(audit.policy_version AS DECIMAL) DESC
               ) as rn
           FROM scheduled_work_segment segment
           JOIN audit_event audit ON ...
       ) ranked
       WHERE rn = 1
   ) latest
     ON latest.segment_id = sws.scheduled_work_segment_id
   SET sws.shift_template_name = latest.template_name,
       sws.is_active = latest.is_active;
   ```

3. **修改班次创建/更新逻辑（2 天）**
   
   找到所有创建或更新 `scheduled_work_segment` 的地方，同步更新新字段：
   
   ```java
   // 创建班次时
   scheduleWorkSegment.setShiftTemplateName(template.getName());
   scheduleWorkSegment.setIsActive(template.isActive());
   
   // 更新班次时
   scheduleWorkSegment.setShiftTemplateName(updatedTemplate.getName());
   scheduleWorkSegment.setIsActive(updatedTemplate.isActive());
   ```

4. **改写 findScheduledWorkSegments 查询（1 天）**
   
   ```sql
   -- 旧查询：从审计表反推（复杂的 CTE + ROW_NUMBER）
   SELECT ... FROM (
       SELECT ..., ROW_NUMBER() OVER (...) FROM audit_event ...
   ) ...
   
   -- 新查询：直接读取字段
   SELECT 
       segment.scheduled_work_segment_id,
       segment.shift_template_name,
       segment.is_active,
       segment.start_time,
       segment.end_time
   FROM scheduled_work_segment segment
   WHERE ...
   ```

5. **测试验证（1 天）**
   
   - 验证回填数据正确性（抽样对比）
   - 测试新建班次时字段正确填充
   - 测试修改班次时字段正确更新
   - 性能测试，确认查询快 10-20 倍

**验收标准：**
- ✅ 班次查询快 10-20 倍
- ✅ 回填完成，所有现有班次都有正确的模板名称
- ✅ 新建/修改班次时字段正确填充

**产出物：**
- Schema 变更脚本
- 回填脚本
- 修改后的业务代码
- 性能对比报告

**风险点：**
- 回填逻辑必须与当前查询逻辑完全一致
- 需要确保所有创建/更新班次的代码路径都更新了

---

## 阶段三：长期架构优化（2-3个月）

### 任务 3.1：表分区

**目标：** 为长期数据增长做准备，实现快速分区裁剪

**负责人：** 待分配

**优先级：** P2

**预计工时：** 2 周

**前置条件：**
- 任务 2.4 完成，投影模型重构已上线
- MySQL 版本支持分区（≥ 5.5）

**详细步骤：**

1. **分区方案设计（2 天）**
   
   按 `business_date` 月份分区：
   
   ```sql
   ALTER TABLE attendance_report_daily_fact
   PARTITION BY RANGE (YEAR(business_date) * 100 + MONTH(business_date)) (
       PARTITION p202401 VALUES LESS THAN (202402),
       PARTITION p202402 VALUES LESS THAN (202403),
       PARTITION p202403 VALUES LESS THAN (202404),
       -- ... 创建到未来 12 个月
       PARTITION p999999 VALUES LESS THAN MAXVALUE
   );
   ```
   
   同样对其他事实表和归档表分区。

2. **评估分区开销（1 天）**
   
   - 测试环境验证分区裁剪效果
   - 评估分区后的查询性能
   - 评估分区维护成本（每月需要添加新分区）

3. **编写分区脚本（2 天）**
   
   ```sql
   -- 分区变更脚本（需要停机维护）
   ALTER TABLE attendance_report_daily_fact
   PARTITION BY RANGE (YEAR(business_date) * 100 + MONTH(business_date)) (...);
   
   -- 或者使用在线 DDL（MySQL 8.0+）
   ALTER TABLE attendance_report_daily_fact
   PARTITION BY RANGE (YEAR(business_date) * 100 + MONTH(business_date)) (...),
   ALGORITHM=INPLACE, LOCK=NONE;
   ```

4. **维护窗口执行（4-8 小时）**
   
   选择周末或长假期间的维护窗口：
   - 停止应用服务
   - 执行分区变更
   - 验证分区创建成功
   - 验证查询仍正常工作
   - 启动应用服务
   - 监控性能指标

5. **自动化分区管理（2 天）**
   
   创建定时任务，每月自动添加下个月的分区：
   
   ```sql
   -- 每月 1 号自动执行
   DELIMITER $$
   CREATE PROCEDURE add_next_month_partition()
   BEGIN
       DECLARE next_year_month INT;
       SET next_year_month = (YEAR(DATE_ADD(NOW(), INTERVAL 1 MONTH)) * 100 + 
                              MONTH(DATE_ADD(NOW(), INTERVAL 1 MONTH)));
       
       SET @sql = CONCAT('ALTER TABLE attendance_report_daily_fact ADD PARTITION ',
                         '(PARTITION p', next_year_month, ' VALUES LESS THAN (', next_year_month + 1, '))');
       PREPARE stmt FROM @sql;
       EXECUTE stmt;
       DEALLOCATE PREPARE stmt;
   END$$
   DELIMITER ;
   ```

**验收标准：**
- ✅ 表分区成功，查询带月份条件时能裁剪分区
- ✅ 查询性能没有退化
- ✅ 自动化分区管理脚本已部署

**产出物：**
- 分区方案设计文档
- 分区变更脚本
- 自动化分区管理脚本
- 分区效果验证报告

**风险点：**
- 大表分区可能需要几小时，必须在维护窗口执行
- 分区后某些查询可能性能下降（如不带分区键的查询）
- 需要仔细测试验证

---

### 任务 3.2：独立连接池

**目标：** 重算和 Web 请求互不影响，避免连接池饥饿

**负责人：** 待分配

**优先级：** P1

**预计工时：** 3 天

**前置条件：**
- 无

**详细步骤：**

1. **配置两个 DataSource（1 天）**
   
   创建配置类 `DataSourceConfig.java`：
   
   ```java
   @Configuration
   public class DataSourceConfig {
       
       @Bean("webDataSource")
       @Primary
       @ConfigurationProperties(prefix = "spring.datasource.web")
       public DataSource webDataSource() {
           HikariConfig config = new HikariConfig();
           config.setMaximumPoolSize(8);  // Web 请求用 8 个连接
           config.setConnectionTimeout(5000);
           config.setIdleTimeout(300000);
           return new HikariDataSource(config);
       }
       
       @Bean("batchDataSource")
       @ConfigurationProperties(prefix = "spring.datasource.batch")
       public DataSource batchDataSource() {
           HikariConfig config = new HikariConfig();
           config.setMaximumPoolSize(6);  // 批处理用 6 个连接
           config.setConnectionTimeout(30000);
           config.setIdleTimeout(600000);
           return new HikariDataSource(config);
       }
   }
   ```
   
   在 `application.yml` 中配置：
   
   ```yaml
   spring:
     datasource:
       web:
         jdbc-url: jdbc:mysql://localhost:3306/shenzhou_hr
         username: root
         password: ${DB_PASSWORD}
       batch:
         jdbc-url: jdbc:mysql://localhost:3306/shenzhou_hr
         username: root
         password: ${DB_PASSWORD}
   ```

2. **修改批处理服务使用 batchDataSource（1 天）**
   
   ```java
   @Service
   public class AttendanceReportProjectionPublisher {
       
       private final AttendanceReportProjectionWriter writer;
       
       public AttendanceReportProjectionPublisher(
           @Qualifier("batchDataSource") DataSource dataSource) {
           // 使用 batch 连接池
           SqlSessionFactory sessionFactory = createSessionFactory(dataSource);
           this.writer = new MyBatisAttendanceReportProjectionWriter(sessionFactory);
       }
   }
   
   // 同样修改其他批处理服务
   @Service
   public class FullCalculationEngineOrchestrator {
       public FullCalculationEngineOrchestrator(
           @Qualifier("batchDataSource") DataSource dataSource) {
           ...
       }
   }
   ```

3. **测试验证（1 天）**
   
   - 启动应用，确认两个连接池都创建成功
   - 触发重算，监控 `batchDataSource` 连接数
   - 同时发起 Web 请求，监控 `webDataSource` 连接数
   - 确认两个池互不影响

**验收标准：**
- ✅ 两个连接池独立工作
- ✅ 重算期间 Web 请求不再饥饿，响应时间稳定
- ✅ 监控显示连接池使用情况正常

**产出物：**
- DataSource 配置代码
- 测试报告

**风险点：**
- 低。这是标准的 Spring Boot 多数据源配置

---

### 任务 3.3：真正的增量计算（研究探针）

**目标：** 让窄窗口重算只加载窗口所需输入，实现秒级重算

**负责人：** 待分配

**优先级：** P2

**预计工时：** 2 周（研究探针）+ 4 周（完整实现，如果可行）

**前置条件：**
- 需要深入理解计算依赖关系

**详细步骤：**

1. **研究计算依赖关系（1 周）**
   
   分析 `DeterministicAttendanceCalculator` 的计算逻辑：
   - 哪些计算只依赖当天数据？
   - 哪些计算需要回溯几天（如周加班累计）？
   - 最大回溯期是多少天？
   
   绘制依赖关系图：
   ```
   日事实计算
   ├─ 打卡事件（当天 + 前后1天，跨天班次）
   ├─ OA 单据（当天）
   ├─ 班次计划（当天）
   ├─ 考勤政策（当天）
   └─ 周加班累计（当周，最多回溯 6 天）
   ```

2. **设计"上下文窗口"加载策略（3 天）**
   
   ```java
   // 当前实现：加载整月
   public CalculationInputs loadInputs(Company company, LocalDate periodStart, LocalDate periodEnd) {
       // 无论窗口多小，都加载整月数据
   }
   
   // 新实现：只加载上下文窗口
   public CalculationInputs loadInputs(Company company, LocalDate windowStart, LocalDate windowEnd) {
       int maxLookback = 7;  // 最大回溯期（根据策略确定）
       LocalDate contextStart = windowStart.minusDays(maxLookback);
       LocalDate contextEnd = windowEnd.plusDays(1);  // 跨天班次需要多看1天
       
       // 只加载 [contextStart, contextEnd] 范围的数据
       return CalculationInputs.builder()
           .oaDocuments(loadOaDocuments(company, contextStart, contextEnd))
           .punchEvents(loadPunchEvents(company, contextStart, contextEnd))
           .shifts(loadShifts(company, contextStart, contextEnd))
           .policies(loadPolicies(company, contextStart, contextEnd))
           .build();
   }
   ```

3. **原型验证（4 天）**
   
   - 实现上下文窗口加载逻辑
   - 在测试环境触发 3 天窗口重算
   - 对比优化前后的加载数据量
   - 对比优化前后的重算结果是否一致
   - 测量性能提升

4. **评估可行性（1 天）**
   
   如果原型验证效果好（3 天重算 < 30 秒），继续完整实现。
   如果效果一般或有数据一致性问题，记录问题并暂缓实施。

5. **完整实现（4 周，如果可行）**
   
   - 所有输入查询改为支持上下文窗口
   - 完整的单元测试和集成测试
   - 性能测试
   - 灰度发布

**验收标准：**
- ✅ 研究探针完成，有明确的可行性结论
- ✅ 如果可行，3 天重算耗时降到 30 秒以内

**产出物：**
- 计算依赖关系分析文档
- 原型代码
- 可行性评估报告

**风险点：**
- 计算依赖关系复杂，可能存在隐含的长距离依赖
- 需要非常仔细的测试确保数据一致性

---

## 总结

### 任务优先级

**P0（必须做）：**
- 任务 1.1：OA 查询索引
- 任务 2.1：批量写入
- 任务 2.2-2.4：投影模型重构

**P1（应该做）：**
- 任务 1.2：降低查询超时
- 任务 1.3：性能监控
- 任务 2.5：优化打卡事件查询
- 任务 3.2：独立连接池

**P2（可以做）：**
- 任务 2.6：反规范化班次名称
- 任务 3.1：表分区
- 任务 3.3：增量计算（研究探针）

### 预期效果

| 里程碑 | 重算时间 | 查询时间 | 表大小 |
|--------|---------|----------|--------|
| 当前 | 20-30 分钟 | 4-10 秒 | 130 万行持续增长 |
| 阶段一后 | 3-5 分钟 | <1 秒 | 130 万行持续增长 |
| 阶段二后 | 1-2 分钟 | <1 秒 | 稳定在 45 万行 |
| 阶段三后 | 30 秒-1 分钟 | <500 毫秒 | 已分区，长期可控 |

### 总工时估算

- 阶段一：约 2 周
- 阶段二：约 6-8 周
- 阶段三：约 4-6 周

**总计：** 约 3-4 个月（如果全职投入 1 人）

### 历史数据处理方案

**推荐方案：** 只迁移最近 3 个月数据，旧数据归档到 `*_archive` 表

**理由：**
- 业务通常只查询最近几个月数据
- 迁移风险最低，数据量可控
- 新表从一开始就保持合理规模
- 旧数据不丢失，需要时仍可查询

**具体操作：**
1. 创建归档表（与原表结构相同）
2. 迁移 3 个月以前的数据到归档表
3. 删除原表中的已归档数据
4. 提供"归档数据查询"功能（如需要）

---

## 附录

### A. 关键 SQL 脚本

#### A.1 数据分布验证脚本

见任务 1.1 步骤 1

#### A.2 索引创建脚本

见任务 1.1 步骤 2

#### A.3 投影模型 Schema 变更脚本

见任务 2.3 步骤 1

#### A.4 数据归档脚本

见任务 2.2 步骤 3

### B. 监控指标

#### B.1 性能指标

- 重算总耗时
- 各查询阶段耗时（OA、打卡、班次、政策）
- 落库耗时
- 表大小和行数

#### B.2 数据库指标

- 慢查询次数和平均耗时
- 连接池使用率
- 磁盘空间使用

#### B.3 业务指标

- 用户查询响应时间
- 重算失败率
- 数据一致性检查结果

### C. 回滚方案

每个阶段都需要准备回滚方案：

**阶段一：**
- 回滚索引：`DROP INDEX ix_oa_document_status_type_created ON oa_attendance_document`
- 回滚超时配置：还原 Mapper 文件

**阶段二：**
- 投影模型重构：还原 Schema，从归档表恢复数据，回滚应用代码

**阶段三：**
- 表分区：删除分区，还原为普通表
- 连接池：还原为单一连接池配置

---

**文档版本：** 1.0
**创建日期：** 2026-09-08
**最后更新：** 2026-09-08

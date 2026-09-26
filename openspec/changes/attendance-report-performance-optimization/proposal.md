## Why

生产上一次公司月重算要 20–30 分钟，最慢的 OA 有效单据查询约 89 秒（扫描约 63 万行只返回约 1,767 行）。`attendance_report_daily_fact` 已约 130 万行、175 个投影，每次重算都新建投影并复制窗口外整月事实，表会继续膨胀。查询页已经要求只读 pin 且第一屏约 1 秒；现在慢的是重算和投影写入，不先止血后续核算会拖垮连接池和磁盘。

## What Changes

- 按真实执行计划优化重算输入加载：`findEffectiveOaDocuments` / `findReportableOaDocuments`、打卡事件、班次段。禁止在未 `EXPLAIN` 的情况下直接采用「`source_status, document_type, created_at`」那条猜测索引；窗口函数是在过滤 `APPROVED` 之前对 `created_at <= dataAsOf` 全量排序的，V59 已有 `(attendance_source_id, knowledge_rank, created_at, id)`，新索引必须对上分区键和排序键。
- 投影写入改为有界批量提交，替代 Publisher 里逐条 `append*Fact`。
- **BREAKING（相对 pin 的历史 token）**：每个公司月只保留一份**当前最新完整 pin** 作为默认查询来源。重算对窗口内事实 UPSERT，不再为每次重算复制整月并追加一个新投影。被替代的旧投影归档后，普通查询不再保证按旧 snapshot token 读回历史版本。钉住语义保留：GET 仍不核算，显式/自动重算成功后默认查询切到新的最新 pin。
- 单条计算查询的 MyBatis `timeout="3600"` 在输入查询稳定低于 5 分钟之前不得降到 300 秒，避免把现在还能跑完的重算打失败。
- 重算与 Web 请求使用隔离的数据库连接池，避免重算饿死报表 GET。
- 表分区、真正的上下文窗口增量加载作为后续阶段，本 change 先完成探针与可行性结论，不把未验证的增量计算当验收项。
- 本 change **不**按「只留最近 3 个月业务日」做在线裁剪。第一刀是去掉被替代投影；在线保留各公司月的**最新 pin**。3 个月归档是可选后续，需产品确认查询窗口后再做。

## Capabilities

### New Capabilities

- `attendance-report-recalc-performance`: 重算输入查询、索引、批量落库、超时策略、连接池隔离、性能基准与慢查询验收。
- `attendance-report-projection-lifecycle`: 公司月一份最新 pin、窗口 UPSERT、禁止整月复制、被替代投影归档、表规模上限。

### Modified Capabilities

- `realtime-report-query`: 默认查询仍读最新完整 pin；重算仍覆盖所请求窗口并得到一份完整最新 pin；**不再要求**被替代 pin 的原 token 长期可读。
- `report-date-range-and-partial-recalc`: 3 天/7 天/整月窗口重算只重写窗口内事实并重汇总，不得再 copy 窗口外整月行到新投影。
- `sync-auto-recalculate`: 后台自动重算受同一套耗时与连接隔离约束；失败时读者继续读旧的最新 pin。

## Impact

- 后端：`AttendanceReportCalculationMapper.xml` 及编排器加载路径、`AttendanceReportProjectionPublisher` / WriteMapper、Flyway 索引与投影唯一约束、Hikari 多数据源、OpenAPI 仅在去掉历史 token 可读性时触及 snapshot 字段说明。
- 数据库：`oa_attendance_document` 及相关输入表索引；`attendance_report_projection` 与四类事实表的唯一键、UPSERT、归档表。大表 DDL 需要维护窗口。
- 前端：普通查询合同不变。若 UI 暴露了旧 projection/snapshot token 回放，需改为只展示最新 pin。
- 运维：慢查询与重算耗时基准、生产加索引窗口、归档作业。不改得力/OA 的 00:00 与 12:00 同步 cron。
- 权限：`ATTENDANCE_REPORT:REFRESH` 与查询能力不变。
- 测试：重算结果与优化前逐人日一致；窄窗口重算不丢窗口外事实；并发重算同一公司月不产生双最新 pin。

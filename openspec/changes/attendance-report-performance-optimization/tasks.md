## 1. 基准与 OA 查询（禁止盲加 status 前导索引）

- [ ] 1.1 在生产级数据上对 `findEffectiveOaDocuments` / `findReportableOaDocuments` 做 `EXPLAIN ANALYZE`，保存计划与扫描行数
- [x] 1.2 按计划设计索引或改写 SQL（分区键含 `attendance_source_id, source_business_key`，排序含 `knowledge_rank, created_at, id`）；窗口仍先排全部状态再滤有效状态
- [x] 1.3 明确拒绝以 `source_status, document_type, created_at` 为前导、且计划仍先全量窗口的索引
- [x] 1.4 写 Flyway 迁移（V66 之后）只加入 1.2 选定的索引；生产用低峰窗口，MySQL 8 优先 `ALGORITHM=INPLACE, LOCK=NONE`
- [ ] 1.5 对比优化前后：该查询 < 10 秒；有效单据集合与优化前一致（含 pending/revoked 压掉旧 approved）
- [x] 1.6 给重算打阶段耗时日志：OA / 打卡 / 班次 / 内存计算 / 落库 / 总时长、公司、人数、窗口类型

## 2. 批量写入（仍追加投影，不改生命周期）

- [x] 2.1 WriteMapper 增加日事实、异常、OA、时间账户的批量 insert（默认 500 行，可配，不超过 `max_allowed_packet`）
- [x] 2.2 `AttendanceReportProjectionPublisher` 改为批量提交；失败整笔回滚，读者仍读上一份最新 pin
- [x] 2.3 单测：0 / 1 / 500 / 501 行；失败回滚；与逐条写入结果一致
- [ ] 2.4 预发或生产级对比落库耗时，作为阶段一基准

## 3. 打卡查询（仅当 1.6 显示它仍是瓶颈）

- [ ] 3.1 用 1.6 的耗时日志确认 `findActivatedPunchEvents` 是否仍占重算大头；不是则跳过 3.2–3.4 并在任务里注明
- [ ] 3.2 去掉相关子查询和 `OR`：先 CTE/映射表对齐花名册工号与事件员工，再 JOIN 生命周期最新 ACTIVATED
- [ ] 3.3 单测：本企业员工 id 命中、跨公司同工号命中、已撤销打卡不进入投影
- [ ] 3.4 对比优化前后打卡集合一致，查询明显快于改前

## 4. 投影生命周期：一月一最新 pin

- [ ] 4.1 Flyway：投影表 `data_year` / `data_month`，回填，`(company_id, data_year, data_month)` 唯一
- [ ] 4.2 Flyway：四类事实表自然键唯一约束（先在副本上查重并去重，再生产加唯一键）
- [ ] 4.3 Publisher 改为 find-or-create 当月 pin，窗口内 UPSERT；窄窗口不再 `copyFactsOutsideRange` 到新投影 id
- [ ] 4.4 查询侧只读该公司月最新 pin；请求携带已作废 snapshot token 时返回已有的 snapshot-changed，禁止混行
- [ ] 4.5 自动重算 last-3-days 与手动近 3 天 / 近 7 天 / 本月都写入同一最新 pin；失败保留原 pin
- [ ] 4.6 同一公司月并发重算串行化（pin 行 `FOR UPDATE`），结束后只剩一个 live 最新 pin
- [ ] 4.7 OpenAPI / 前端：默认查询与导出走最新 pin；不要再承诺旧 token 长期可读
- [ ] 4.8 单测：两次整月重算 live 日事实约为人数×当月天数，不是 2 倍；近 3 天不改窗口外日事实且月合计等于保留日+新窗口日；迟到宽限仍计入窗口前已消耗

## 5. 归档被替代投影（不按「只留 3 个月业务日」删最新 pin）

- [ ] 5.1 创建归档表或等价归档路径；只归档「不是该公司月最新 pin」的投影及其事实
- [ ] 5.2 维护窗口演练：统计将归档行数、归档、校验各公司月仍有且仅有一份 live 最新 pin
- [ ] 5.3 生产执行归档后核对：live 日事实规模由最新 pin 解释；查询旧月份仍返回该月最新 pin，而不是空

## 6. 连接池隔离与超时（超时最后做）

- [ ] 6.1 配置 web / batch 两个 Hikari 池；报表与查询 GET 走 web，重算与自动重算走 batch
- [ ] 6.2 验证重算进行中、已有最新 pin 时 GET 仍由 web 池服务
- [ ] 6.3 仅当输入查询 p95 < 60 秒后，把对应 Mapper `timeout` 从 3600 改为 300；未达标的语句保持 3600

## 7. 阶段一/二验收

- [ ] 7.1 阶段一（查询+批量写入）后：生产级整月重算 ≤ 5 分钟，单条输入查询 ≤ 10 秒，暖查询第一屏 p95 ≤ 1 秒
- [ ] 7.2 阶段二（一月一 pin + 归档）后：整月重算目标 1–2 分钟；重复重算不再按整月倍增 live 行数
- [ ] 7.3 回归：GET 不核算；无 `ATTENDANCE_REPORT:REFRESH` 不能重算；关账月跳过；预览/失败不得覆盖最新 pin

## 8. 后续探针（本 change 只出结论，不当阶段二出门条件）

- [ ] 8.1 班次查询若仍慢：评估给 `scheduled_work_segment` 落模板名/`is_active` 并回填；否则注明跳过
- [ ] 8.2 分区：仅在唯一键与 UPSERT 上线后设计按 `business_date` 分区的方案，不在本阶段对生产做 PARTITION
- [ ] 8.3 增量加载探针：画清计算回溯（跨天班次、月迟到宽限、周加班），原型只加载窗口±回溯，对比人日结果；写出可行/暂缓结论

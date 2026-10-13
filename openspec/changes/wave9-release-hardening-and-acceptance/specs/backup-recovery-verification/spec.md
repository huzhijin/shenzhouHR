## ADDED Requirements

### Requirement: 备份必须同时支持全量和时间点恢复
备份流程 SHALL 创建一致性全量备份并记录 GTID 或 binlog 起止位点、数据库身份、MySQL 精确版本、UTC 时间、工具版本、artifact SHA-256 和加密/存储责任；仅每日全量备份不得满足 RPO 门。

#### Scenario: 全量备份缺少增量位点
- **WHEN** 备份 artifact 可校验但没有连续 binlog/等效 PITR 位点
- **THEN** 备份可用性叶子 MAY 记录成功，但 RPO 叶子 MUST 为 `NOT_VERIFIED`

### Requirement: 恢复只能进入隔离测试目标
仓库内恢复工具 MUST 只允许 loopback MySQL 上新建的 `_restore` 测试库，并要求匹配 run ID 与精确库名的确认令牌；工具 MUST 拒绝 dev、生产、未知主机、符号链接凭据和宽权限凭据文件。

#### Scenario: 目标库不是恢复沙箱
- **WHEN** 操作者指定 `shenzhou_hr_dev`、`shenzhou_hr_test`、空库名或不以 `_restore` 结尾的库
- **THEN** 工具 MUST 在调用 MySQL 客户端前拒绝

### Requirement: 恢复验收必须验证业务一致性
恢复后 SHALL 验证 Flyway 历史、表/约束/索引、规则版本、月结 snapshot、同步水位、关键清单和 artifact checksum，并记录丢失窗口与总恢复时长。

#### Scenario: dump 可导入但业务清单不一致
- **WHEN** SQL 导入完成但任一规则、月结、同步水位或关键计数校验失败
- **THEN** 恢复门 MUST 为 `FAIL`

### Requirement: 最终恢复门必须满足 RPO 和 RTO
最终发布候选 MUST 通过人工参与的 MySQL 8.4 LTS 隔离恢复演练，证明 RPO ≤15 分钟且 RTO ≤4 小时。

#### Scenario: 仅自动化 fake client 测试通过
- **WHEN** 脚本防护和 fake client 测试通过但未执行真实 MySQL 8.4 人工恢复
- **THEN** 最终恢复门 MUST 为 `NOT_VERIFIED`

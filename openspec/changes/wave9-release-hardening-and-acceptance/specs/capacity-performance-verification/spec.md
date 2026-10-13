## ADDED Requirements

### Requirement: 性能场景必须版本化且可重复
性能 harness SHALL 从版本化场景读取请求、并发、持续时间、预热、阈值和数据集身份，并输出可机器校验的样本数、状态码、成功率、吞吐、P50、P95 和 P99。

#### Scenario: 本地 mock 验证统计与阈值
- **WHEN** 自动化测试以可控延迟和状态码运行本地 mock server
- **THEN** harness MUST 产生确定结构的指标并正确判定通过、失败和无有效样本

#### Scenario: 非 loopback 目标未经授权
- **WHEN** 当前独立实现被要求向非 loopback URL 发起压测
- **THEN** harness MUST 在发送请求前拒绝并保持运行结果为 `NOT_VERIFIED`

### Requirement: V1.9 性能阈值必须分别判定
最终候选 SHALL 在 50 并发下证明常用列表 P95 ≤2 秒、个人/部门看板 P95 ≤3 秒、单条详情 P95 ≤1 秒，且 50,000 行以内导出 ≤60 秒；每个场景 MUST 独立报告，不得用平均值代替 P95。

#### Scenario: 单个场景超过阈值
- **WHEN** 任一必需场景的 P95、导出耗时或成功率未达到合同
- **THEN** 性能门 MUST 为 `FAIL`，即使其他场景平均值达标

### Requirement: 容量结论必须绑定真实基线和 36 个月数据
最终容量证据 MUST 记录上线前在职人数、日峰值打卡、月末并发、数据库现存量、36 个月累积模型、MySQL 精确版本、schema/Flyway 状态和 integrated commit。

#### Scenario: 缺少现网基线或 36 个月 POC
- **WHEN** 仅运行小型合成 smoke、H2、mock server 或未记录数据规模
- **THEN** 容量门 MUST 为 `NOT_VERIFIED`，不得冻结生产资源规格

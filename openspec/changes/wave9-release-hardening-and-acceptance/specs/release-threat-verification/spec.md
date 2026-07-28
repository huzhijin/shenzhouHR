## ADDED Requirements

### Requirement: 发布威胁门必须覆盖 P0-A 高风险攻击面
发布候选 MUST 对 BOLA/IDOR、对象枚举、跨组织范围、批量查询、导出创建与下载、缓存恢复、会话撤销、开发入口、敏感日志和安全响应头建立可执行检查，且使用合成身份和数据。

#### Scenario: 未授权对象替换被拒绝
- **WHEN** 授权测试身份替换 URL、请求体或下载令牌中的员工、组织、位置或导出对象标识
- **THEN** 服务端拒绝请求、不泄露对象存在性或敏感字段，并生成不含敏感值的安全审计

#### Scenario: 开发入口进入发布候选
- **WHEN** 发布候选启用 `dev` profile、demo mode、`X-Development-Principal` 或合成管理员 bootstrap
- **THEN** 威胁门 MUST 失败并阻止发布

### Requirement: 高危发现必须阻断发布
最终安全门 MUST 要求 Critical 和 High 未关闭发现数均为零，且不得把未执行的授权动态测试视为通过。

#### Scenario: 动态威胁测试未执行
- **WHEN** 静态合同通过但 W7 FINAL 环境、授权或合成身份缺失
- **THEN** 安全门结果 MUST 为 `NOT_VERIFIED` 而非 `PASS`

#### Scenario: 存在高危发现
- **WHEN** 任一 Critical 或 High 发现未关闭或复测失败
- **THEN** 安全门结果 MUST 为 `FAIL` 并阻止发布

### Requirement: 安全测试证据必须可追溯且不含秘密
每个安全用例 SHALL 记录 run ID、integrated commit、测试身份类别、请求关联标识、预期、实际、工具版本和证据 SHA-256；证据 MUST NOT 包含密码、session、token、精确坐标、假因或真实员工数据。

#### Scenario: 证据包含敏感值
- **WHEN** 证据校验器检测到凭据、会话材料或禁止的真实敏感字段
- **THEN** 对应叶子 MUST 失败且证据不得进入发布包

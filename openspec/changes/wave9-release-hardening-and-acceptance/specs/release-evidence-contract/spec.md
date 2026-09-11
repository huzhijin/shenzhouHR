## ADDED Requirements

### Requirement: 发布证据必须绑定同一来源
release manifest MUST 记录唯一 run ID、干净的 integrated commit、W7 FINAL marker 与摘要、环境身份、工具版本、证据相对路径和 SHA-256；校验器 MUST 拒绝绝对路径、路径逃逸、缺失文件、摘要不符、跨 run 或跨 commit 证据。

#### Scenario: 拼接不同提交的证据
- **WHEN** 任一必需叶子的 commit、run ID 或环境身份与 manifest 不同
- **THEN** 校验器 MUST 拒绝该证据且总门不得为 `PASS`

### Requirement: 发布结论必须使用严格三态
叶子和总门只允许 `PASS`、`FAIL`、`NOT_VERIFIED`。总门 SHALL 先传播任一 `FAIL`，否则在任一必需叶子未通过时返回 `NOT_VERIFIED`，只有全部必需叶子通过时返回 `PASS`。

#### Scenario: 依赖证据缺失但没有测试失败
- **WHEN** W7 FINAL、MySQL 8.4、真实浏览器或人工恢复演练任一缺失
- **THEN** 总门 MUST 为 `NOT_VERIFIED`，不得用 `PASS_WITH_CAVEATS`、`SKIPPED` 或自由文本替代

### Requirement: W7 是必需前置且 W8 不是前置
W9 最终判定 MUST 要求 W7 FINAL 在同一 integrated commit 的祖先链中且 marker 为 `PASS`；判定 MUST NOT 要求 W8 存在或通过。

#### Scenario: W7 FINAL 尚未同步
- **WHEN** 当前源码没有可验证的 W7 FINAL marker 或 commit
- **THEN** W9 总门 MUST 为 `NOT_VERIFIED`

#### Scenario: W8 未实施
- **WHEN** 全部 P0-A 必需门和 W7 FINAL 均通过但 W8 不存在
- **THEN** 校验器 MUST NOT 因 W8 缺失降低 W9 判定

### Requirement: 当前独立实现不得宣称上线通过
在最终 integrated commit、MySQL 8.4 LTS、真实浏览器矩阵和人工恢复演练未完成前，任何自动生成摘要 MUST 明示 `NOT_VERIFIED` 和剩余外部验证清单。

#### Scenario: 本 worktree 的静态与单元测试全部通过
- **WHEN** W9 工具自身测试通过但外部门尚未补证
- **THEN** 摘要 SHALL 报告 harness ready 且 release verdict 为 `NOT_VERIFIED`

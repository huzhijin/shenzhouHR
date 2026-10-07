# Open Design 同项目切换到 V1.9｜旧分步流程（已停用）

> 当前入口统一改为 `docs/open-design-restart-v1.9.md`。下面“先 PRECHECK、再粘贴总提示词”的两步流程仅保留为历史说明，不再执行；无论是否已经出现 DOCX 转换器缺失、改用 macOS 原生解析、`NOT_ACTIVE`、找不到 Design System 导入入口或 PRECHECK 中断，都直接使用一键重启提示词。

> 用法：在当前已有 V1.7 页面和 artifact 的同一个 Open Design 项目/会话中，先单独发送下面这段。它完成后，再发送 `docs/open-design-prompts-v1.9.md` 全文。

```text
Use open-design.

继续使用当前“神州 HR”Open Design 项目，不创建新项目，不删除、覆盖或批量重画现有 artifact。

从本条消息起，当前正式产品基线切换为“神州 HR V1.9”。此前 V1.7 的 brief、页面地图、artifact-index、页面和薪资设计只作为历史资产，不能继续决定当前信息架构、业务规则、权限或页面范围。下一条消息会提供完整的 V1.9 长期主 brief。

【命名与历史保护】
1. 不修改旧 artifact 的实际内容和原始编号。
2. 在新的 artifact-index 中，将旧产物统一引用为 LEGACY-V17-OD-xx。
3. 所有 V1.9 新产物统一使用 V19-OD-xx，例如 V19-OD-00、V19-OD-01。
4. 旧产物只能标记为 KEEP、MODIFY、RETIRE 或 FUTURE_RESERVED，不得直接视为当前已完成页面。
5. KEEP 表示视觉或组件可复用，不表示旧业务规则继续有效；任何复用必须重新对照 V1.9。

【本轮只做迁移审计】
本轮不要生成新产品页面，不要修改现有 artifact，不要开始 V19-OD-01，也不要输出多个原型。

只创建一个名为“V19-OD-00-PRECHECK｜同项目基线切换审计”的审计 artifact，包含：
1. 当前项目全部已有 artifact 清单；
2. 旧编号及新的历史引用名；
3. 每个旧 artifact 的 KEEP/MODIFY/RETIRE/FUTURE_RESERVED 建议；
4. 其中仍包含的 V1.7 失效口径；
5. 预计对应的 V1.9 新 artifact；
6. 当前 Design System、Logo、tokens、公共壳层和组件中可以继续复用的内容；
7. 不能复用的业务结构和交互；
8. 缺失、无法读取或状态不明的 artifact，标为 MISSING/UNKNOWN，不得猜测。

【已确定的 V1.9 纠偏方向】
- 登录改为本系统独立账号密码，不再是 SSO/IdP 待定。
- 致远组织架构仅做期初 Excel 导入，之后本系统独立维护；取消组织持续同步页面和入口。
- OA 请假、加班、外出、出差、补卡等考勤业务单据仍是只读来源，不要误删。
- 新增离线/异构考勤机原始打卡 Excel 导入，包含模板、字段映射、设备人员匹配、预检、跨来源去重、错误报告、发布和批次追溯。
- 夏令时、冬令时和特殊人员统一通过考勤组、班次版本、生效期及作用范围配置。
- 所有考勤组默认扣除晚餐，相关窗口、时长和日期类型可配置。
- 每人每自然月默认 1 次、15 分钟内迟到宽限，规则可配置。
- 单边缺卡默认只影响缺卡工作段，补正期限和逾期结果可配置。
- 所有假别配置化；年假资格与累计工龄档位分开，周年发放，二次入职重算。
- 薪资当前前台完全不可发现。旧薪资后台和工资条设计全部标记 FUTURE_RESERVED，不进入当前导航、路由、首页、搜索和员工端。

完成 V19-OD-00-PRECHECK 后停止，不要继续生成页面。最后只提示用户：
“同项目迁移审计已完成，请发送 V1.9 完整总提示词。”
```

## 第二步

将 `docs/open-design-prompts-v1.9.md` 全文发送给同一项目。它应生成 `V19-OD-00`，而不是覆盖旧 `OD-00`。

## 后续继续提示词

```text
继续当前同一个神州 HR 项目。先读取最新 V1.9 artifact-index、design-handoff 和 V19-OD-00，再生成下一个未完成的 V19-OD artifact。每轮只生成一个完整 artifact，不修改 LEGACY-V17 产物；若复用旧视觉或组件，必须注明来源及已移除的 V1.7 业务口径。严格保持薪资前台完全不可发现。完成 critique/refine、状态补齐和进度更新后停止。
```

## 界面没有 Design System 导入入口时

```text
当前 Open Design 界面没有提供自定义 Design System 的导入或激活入口。以本条消息覆盖此前“未激活就停止”的门禁。

请不要再要求我手动激活，也不要让我通过“添加附件”导入。请读取当前项目已经生成的 shenzhou-hr 设计系统包；如果可以读取其中的 DESIGN.md 和 tokens，则以 PROJECT_EMBEDDED 模式作为当前 V1.9 设计规范。如果无法读取该包，则直接使用 V1.9 总提示词中的品牌、Logo、tokens、响应式和无障碍规则，以 INLINE_FALLBACK 模式继续。

请在 design-handoff 中如实记录采用的模式，不能声称平台已激活。现在只生成 V19-OD-00 项目设计 brief，完成旧 artifact 审计、页面地图、角色矩阵、组件/状态矩阵和 artifact-index；不要生成登录页或后续页面，完成后停止。
```

## 旧页面改造提示词

```text
本轮只处理 V19-OD-XX。可以参考 LEGACY-V17-OD-YY 的视觉和组件，但不得直接覆盖旧 artifact，也不得继承其失效业务规则。请先列出“复用内容、删除内容、新增内容、V1.9 规则来源”，再生成新的 V19 artifact。覆盖正常、加载、空、失败、无权限、冲突、冻结和成功等适用状态；完成后更新 V1.9 artifact-index 和 design-handoff，并停止。
```

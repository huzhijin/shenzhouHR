# V1.9 Open Design 接入修订提示词

当前 `docs_confirm` 暂不批准进入源码实施。本轮只授权读取、核验并汇总已经导入仓库的 V1.9 Open Design 交付，形成可实施的前端设计规格；继续停在 `docs_confirm`。不得修改 `frontend/src`、`backend/src`、`api/openapi.yaml` 或任何数据库迁移。

必须实际读取：

- `docs/神州HR考勤与薪资核算系统_PRD_V1.9.docx` 与 V1.9 已确认事实；
- `docs/open-design-prompts-v1.9.md`；
- `design/DESIGN-MANIFEST.json`、`design/DESIGN-HANDOFF.md`；
- `design/open-design/v1.9/IMPORT.md`；
- `design/open-design/v1.9/` 下 artifact-index、design-handoff、route-to-artifact、demo-flow、demo-data-registry、component-inventory、page-state-matrix、QA、React 实施说明、响应式清单、设计系统以及全部 `V19-OD-00～16` 页面；
- 当前 `frontend/src` 路由、AppShell、公共组件、tokens、状态页、演示数据和测试；
- 当前 `api/openapi.yaml`、后端权限与既有 V1/V2 迁移，仅用于差异分析。

事实边界：

1. Open Design 只决定已确认范围内的视觉、布局、交互和响应式表达，不是业务、安全、权限、API、数据库或真实集成的事实来源；冲突按 V1.9 事实优先级裁决并登记。
2. 不得把多个静态 HTML 拼成一个页面，不得整体复制 HTML/JS 进 React，不得引入 Open Design chrome、QA 页面或 `PROTOTYPE_ONLY` 控制条到生产导航。
3. `PROTOTYPE_SIMULATION` 仅作为交互验收参考；上传、预检、发布、重算、月结、权限、持久化和外部接口必须在实施阶段按真实契约开发和测试。
4. `ENGINEERING_ASSUMPTION`、`NOT_VERIFIED` 和 `VISUAL_CAPTURE_NOT_AVAILABLE` 必须保留为待工程验证项，不能写成已完成。
5. 薪资前端完全不可发现；旧 V1.7 薪资页面和口径只能标记为 `FUTURE_RESERVED`/历史，不进入当前菜单、路由、工作台、搜索、通知、报表或员工端。

输出到 `docs/design-v1.9-integration/`：

1. `README.md`：接入结论、事实优先级、范围和使用方式；
2. artifact 索引及 `V19-OD-00～16` 完整性核验；
3. route-to-artifact-to-existing-route 矩阵，包含角色、权限域、数据范围、入口、返回路径和生产/原型边界；
4. 设计 token 差异表与唯一 token 合并方案；
5. 公共 AppShell、导航、表格、筛选、表单、向导、弹窗、抽屉、状态、图表和移动组件清单；
6. 页面状态矩阵：正常、加载、空、失败、403、冲突、冻结、处理中、部分成功和成功；
7. PC、平板、手机、大屏响应式实施规则与像素截图验收清单；
8. 五条客户演示链路的生产路由映射、共享上下文和工程化状态变化；
9. PRD/设计/现有代码冲突清单、裁决、影响页面/API/表/测试；
10. 现有页面的 `KEEP/MODIFY/REPLACE/RETIRE/FUTURE_RESERVED` 方案；
11. 可独立验收的前端纵向实施切片、依赖和完成标准；
12. 离线打卡 Excel 导入专项矩阵：模板下载、上传、字段映射、设备人员匹配、预检、错误报告、API/Excel 跨来源重复、冻结保护、发布、部分成功、批次、作废/冲正及原始行追溯；
13. `PROTOTYPE_SIMULATION → API/领域服务/数据库/权限/测试` 工程映射；
14. `MISSING/BLOCKED/NOT_VERIFIED` 和生产上线前必须关闭事项。

验收标准必须可直接转为自动化测试和 `preview_confirm` 人工检查。未实际读取或无法验证的内容必须明确标记，不得猜测或虚报。完成后汇总新增/修订文件并继续停在 `docs_confirm`，等待人工批准。

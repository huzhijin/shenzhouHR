# 神州 HR V1.9 · 响应式截图清单

更新日期：2026-07-22  
本轮状态：`STATIC_LAYOUT_VERIFIED / VISUAL_CAPTURE_NOT_AVAILABLE`。宿主环境未提供可用的 Open Design HTML 导出命令，因此没有伪报已生成截图；下列是交付 UmaDev 与后续人工验收必须补齐的截图清单。

## PC

| 尺寸 | 代表 artifact | 必查内容 | 当前状态 |
|---|---|---|---|
| 1366×768 | OD02、OD03、OD07、OD10A、OD12、OD14 | 标题与主操作不重叠；高密表格关键列、筛选摘要、确认层完整 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 1440×900 | OD01、OD04、OD05、OD08、OD11 | 左导航、面包屑、详情层、解释链、焦点态 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 1920×1080 | OD00、OD06、OD09、OD10B、OD16 | 最大内容宽度、密度、空白与表格扫描 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |

## 平板

| 尺寸 | 代表 artifact | 必查内容 | 当前状态 |
|---|---|---|---|
| 1024×768 横屏 | OD02、OD03、OD06、OD10A、OD12 | 左导航转抽屉；四列转两列；复杂筛选与对话框不溢出 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 768×1024 竖屏 | OD01、OD04、OD08、OD09、OD14 | 抽屉导航、单列详情、触控目标、键盘焦点、底部安全区 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |

## 手机

| 尺寸 | 代表 artifact | 必查内容 | 当前状态 |
|---|---|---|---|
| 360×800 | OD01、OD03、OD10A、OD13 | 无横向滚动；表格转字段卡；键盘不遮挡提交；底栏安全区 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 390×844 | OD01、OD11、OD12、OD13 | 弹窗转底部层；解释链、异常操作、返回路径和焦点恢复 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 430×932 | OD04、OD08、OD09、OD13 | 员工卡、年假解释、账户流水、渐进披露和 44px 目标 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |

## 独立大屏

| 尺寸 | 代表 artifact | 必查内容 | 当前状态 |
|---|---|---|---|
| 1920×1080 | OD15 | 16:9 逻辑画布、六项 KPI、趋势、部门对比、来源水位、页脚口径 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |
| 3840×2160 | OD15 | 2× 等比缩放、文字与线条清晰、无二次布局漂移 | STATIC_PASS / SCREENSHOT_NOT_CAPTURED |

## 状态截图补充

每个代表页面至少补齐正常、加载、空、失败、403；OD03/09/10A 补齐处理中、部分成功、成功；OD05～08/12 补齐冲突与冻结；OD15 补齐陈旧与部分来源不可用。

## 人工截图验收标准

- 页面宽度无非预期横向滚动、裁切、重叠或文字溢出。
- 焦点环清晰，状态包含文字，危险按钮与普通保存不混淆。
- 手机端不缩小桌面表格；平板采用抽屉；大屏严格 16:9。
- 截图中只能使用合成数据，不出现真实姓名、工资、精确位置或敏感假别原因。
- 任何截图若出现被排除模块入口，立即判定 P0 失败。


# Deploy

HR 与 OA 插件必须同一窗口发布。

1. 跑 Flyway `V55__attendance_days_half_and_fake_overtime.sql`。
2. 发布 HR 后端（公式目录 `FULL_CALCULATION_OVERNIGHT_HOURS_V3`）。
3. 覆盖 `szsc-plugin-overlay.zip` 并重启 OA。
4. HR 管理员对未关账月份点「重新计算」。旧钉快照不会自动换公式。

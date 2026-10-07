-- V47: 添加出勤天数字段以支持天数口径出勤率
--
-- 背景：客户确认出勤率公式为 "实际出勤天数 ÷ 应出勤天数 × 100%"
--       当前日事实表只有分钟字段，无法直接计算天数口径
--
-- 方案：添加两个天数字段，由计算器填充 0 或 1

ALTER TABLE attendance_report_daily_fact
    ADD COLUMN scheduled_attendance_days TINYINT NOT NULL DEFAULT 0
        COMMENT '应出勤天数：当日有适用排班时为1；请假、免打卡、外出均不减少分母'
        AFTER actual_work_minutes,
    ADD COLUMN actual_attendance_days TINYINT NOT NULL DEFAULT 0
        COMMENT '实际出勤天数：当日有打卡或有批准覆盖时为1，否则为0'
        AFTER scheduled_attendance_days;

-- 约束：每个字段只能是 0 或 1
ALTER TABLE attendance_report_daily_fact
    ADD CONSTRAINT ck_att_report_daily_scheduled_days
        CHECK (scheduled_attendance_days IN (0, 1)),
    ADD CONSTRAINT ck_att_report_daily_actual_days
        CHECK (actual_attendance_days IN (0, 1));

-- 说明：
-- scheduled_attendance_days = 1 的判定条件：
--   - 当日有适用班次（scheduled_minutes > 0）
--   - 批准的带薪假、事假、免打卡或外出均不减少应出勤天数
--
-- actual_attendance_days = 1 的判定条件：
--   - 当日有适用班次，且有有效物理/批准补卡记录
--   - 或有批准的带薪假覆盖（包括病假；事假不计出勤）
--   - 或有批准的免打卡覆盖
--   - 批准外出还必须同时存在有效打卡；出差不提供出勤覆盖
--
-- 出勤率计算：
--   SUM(actual_attendance_days) / NULLIF(SUM(scheduled_attendance_days), 0) × 100

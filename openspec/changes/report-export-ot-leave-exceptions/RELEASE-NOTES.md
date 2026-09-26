# 发布说明 report-export-ot-leave-exceptions

- OPEN 月必须重新计算，补签、哺乳假切片、午前下班显示、批准后异常事实才会进入钉住结果。
- 关账月不自动重开。
- 审批中已入库 OA（UNKNOWN/DRAFT）只从异常总览隐藏，不改工时、出勤、哺乳假小时。
- 上海缺卡与 OA 请假缺口走现有 Excel 导入和 `outputs/check-oa-szoa-vs-hr.sh`，不改得力/OA cron。

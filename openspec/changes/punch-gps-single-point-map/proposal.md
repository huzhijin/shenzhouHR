## Why

大连等地已有人用得力手机打卡，人事要核对「这一次卡是在哪里打的」。系统能识别 `check_type=gps`，但同步时丢掉了地点和坐标，报表也只有当天上班/下班时刻，点不进某一次打卡。看板和大屏继续禁止铺坐标；需要的是受控的单点地图，不是轨迹。

## What Changes

- 得力同步对 GPS/外勤卡保留地址摘要和经纬度原文，写入已有 `raw_attendance_fact` 坐标列；照片等其它 `check_data` 仍丢弃。
- 考勤日报、考勤明细的「某日」详情列出当天每一张原始卡（时间、手机/考勤机）。仅 `gps`/`out_work` 且允许查看时显示「查看位置」。
- 「查看位置」打开单点地图抽屉：默认地址文字，不展示经纬度数字；坐标合法且坐标系已确认才画点。管理者要人员范围 + `ATTENDANCE_LOCATION:READ` + 填写原因；员工只看本人。每次查看写审计。
- 未知坐标系不转换、不画点，只出地址（若有）和「地图暂不可用」。
- 月历格子、签到排行、公司大屏、查询页 Excel 全表导出仍不含精确坐标。

不在本 change：

- 大连中秋/国庆日历（配置，见 `docs/deployment/2026-09-15-dalian-holiday-calendar.md`）
- 实时定位、轨迹、批量地图、围栏考勤
- 改打卡/人事调整写路径
- 默认可视化历史已入库、当时未存坐标的手机卡（若现场要历史，另做受控回放）

## Capabilities

### New Capabilities

- `deli-gps-location-ingest`: 得力 GPS/外勤打卡把地址与坐标写入原始事实，其它敏感载荷仍丢弃。
- `punch-location-map`: 日详情列出各次打卡，点进一次查看受控单点地图。

### Modified Capabilities

- （主库 `openspec/specs/` 尚无对应能力；本 change 只引入上述两条。）

## Impact

- 同步：`DeliEplusClient` / `DeliPunchPageTransaction` 不再把 GPS 地点一律丢空；`locationSummary`、经纬度、坐标系校验状态要落 `raw_attendance_fact`。
- API：新增受控 `GET`（或等价）按打卡事实 ID 取位置视图；日详情接口增加当日原始卡列表（无坐标数字）。
- 前端：查询报表考勤日报、考勤明细日列表/抽屉；权限与原因框；地图失败回退地址。
- 权限：复用已有 `ATTENDANCE_LOCATION:READ`；查看事件进审计，不含精确坐标明文到普通日志。
- 地图底图：V22 已标明外部配置；未配密钥时只出地址。
- 不改：加班认定、日历、工资、得力水位初始化。

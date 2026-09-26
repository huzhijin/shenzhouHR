## Context

得力 CHECKIN 记录里 `check_type=gps` 时，`check_data` 可带 `location`、照片，以及（现场样本待确认）经纬度。当前 `DeliEplusClient.toPortRecord` 把 `locationSummary` 固定写成 `null`，`coordinateSystemTag=UNKNOWN`，`forbiddenPayloadDropped=true`。V22 已在 `raw_attendance_fact` 加了坐标列和 `ATTENDANCE_LOCATION:READ`，但适配器从不填写，查询页只有日级上班/下班时刻。

人事要的是：点进**某一次**手机打卡看位置。原 Open Design：只核验一次 GPS/外勤卡；管理者要组织范围 + 独立 LOCATION 权限 + 原因；默认不显示数字坐标；未知坐标系不转换不画点。看板/排行/大屏继续禁坐标。

大连中秋国庆日历是另一条现场配置，见 `docs/deployment/2026-09-15-dalian-holiday-calendar.md`。

## Goals / Non-Goals

**Goals:**

- 新同步的 GPS/外勤卡留下地址和原始坐标，照片仍丢。
- 考勤日报、考勤明细某日能列出当天每张卡，手机卡可「查看位置」。
- 单点地图受控：权限、原因、审计；无点则地址 +「地图暂不可用」。

**Non-Goals:**

- 不改加班、日历、工资。
- 不做轨迹、围栏、实时定位、批量地图。
- 不把坐标铺进月历格子、排行、大屏、查询 Excel。
- 不默认回放历史已入库、当时没存坐标的卡。
- 不把「改打卡」当成看地图入口。

## Decisions

### Decision 1: Keep dropping photo; stop dropping location

**Choice:** `check_data` 继续不算进普通日志。解析白名单：`location`/`address` 类文本 → `locationSummary`（截断）；`lng`/`lat`/`lgt`/`longitude`/`latitude` 等 → `longitude_raw`/`latitude_raw`。照片、wifi、完整 JSON 仍只进 digest。`forbiddenPayloadDropped` 在丢弃照片时仍为 true。

备选：整段 `check_data` 入库。拒绝，和现有敏感载荷合同冲突。

### Decision 2: Coordinate system stays UNKNOWN until a live sample is classified

**Choice:** 实现时先用一张真实 GPS 卡样本钉字段名。在官方/样本确认前，`source_coordinate_system=UNKNOWN`，不填 `map_*`，抽屉只出地址。确认是 GCJ-02 后再走 V22 的转换约束（`VALID` + `CONVERTED`/`IDENTITY` 才给地图点）。底图密钥保持外部配置（高德/腾讯，不写进仓库）。

备选：默认当 WGS84 画点。拒绝，国内会偏几百米，变成假证据。

### Decision 3: Punch list on day detail; location is a second request

**Choice:** 日详情（日报抽屉、考勤明细某日列表）返回 `punches[]`：`rawFactId`、时刻、`method`（gps/fp/…）、`sourceLabel`。不含经纬度。点「查看位置」再 `GET /api/v1/checkins/{rawFactId}/location`，body/query 带 `reason`（管理者必填）。响应：`locationText`、`mapPointAvailable`、可选已转换点、`mapUnavailableReason`。前端默认不渲染数字坐标。

挂载：查询报表 `daily-journal` 行详情；`matrix` 日列表把上班/下班从「两个时刻」扩成可点的卡列表。本人「我的考勤」某日可看自己的卡，不填原因。

备选：把地址直接写在日报上班列。拒绝，用户已定为「点进某次打卡再看地图」，也避免列表泄露。

### Decision 4: Reuse ATTENDANCE_LOCATION:READ; audit every view

**Choice:** 不新增 capability。HR 角色里 V22 已授该码，实现时核对现场角色是否真有；没有就只加授权，不改码。员工走 `EMPLOYEE_SELF` + 本人匹配。审计记 actor、fact id、employee id、reason、result；普通日志禁止经纬度明文。

备选：复用 `ATTENDANCE_REPORT_QUERY:READ` 就能看地图。拒绝，和 AC-LOCATION-01 冲突。

### Decision 5: Forward-only ingest; no in-place backfill

**Choice:** 本 change 只让**新同步**的 GPS 卡带地点。已提交、当时没坐标的行不 UPDATE。同一 `source_business_key` + `source_version` 碰撞保持现状。若现场要历史地图，另开受控回放（新 source version），不塞进本任务。

备选：部署时全量 replay 得力。拒绝，水位和碰撞风险大，且用户未要求历史。

## Risks / Trade-offs

- [Risk] 得力字段名与夹具不一致 → 第一个任务用生产脱敏样本钉解析；解析不到坐标时仍存地址，地图按钮可出但无点。
- [Risk] 未知坐标系画偏 → 未确认不填 map 列，UI 强制无点。
- [Risk] 查询页日详情变慢 → 卡列表按员工+日查已激活事实，不把坐标 join 进列表 SQL。
- [Risk] 地图密钥未配 → 抽屉降级为地址文字，功能仍可验收「查看位置」。
- [Risk] 有 LOCATION:READ 的人导出 Excel 想带地址 → 本 change 明确导出不含地点；以后要另开需求。

## Migration Plan

1. 先拿一张真实 GPS `check_data`（脱敏）补测试夹具。
2. 发后端：ingest + 日卡列表 + location GET。未配底图时地址文字可用。
3. 发前端：日报/明细抽屉。
4. 得力按现有增量同步即可，不必 `checkin_query_init`。
5. 回滚：停 location 入口；已写入的坐标列可留，列表不展示即回到现在。

## Open Questions

- 得力 GPS `check_data` 的准确字段名（实现第一个任务钉死）。
- 现场 HR 角色是否已有 `ATTENDANCE_LOCATION:READ`（V22 种子 vs 后来自定义角色）。
- 底图供应商与密钥何时进 `/etc/shenzhouhr/shenzhouhr.env`；未配不影响文字地址验收。

## 2026-09-16 审查修订

- 管理者按 LOCATION capability 对应角色分配的数据范围校验，禁止借用 REPORT_QUERY 范围；日列表用同样判定决定按钮。
- `findLocation` 只使用最新生命周期为 ACTIVATED 的事件关联；历史 SUPERSEDED/RETRACTED 关联不参与员工身份判断。
- 本人通过 `/api/v1/me/attendance/day-punches` 获取列表，员工ID由登录身份决定；`reasonRequired=false`，前端直接查看本人位置。
- 已登录位置请求由服务层执行权限判定及审计，避免方法权限拦截绕过位置审计。V70保留最多500字查看原因及员工ID，其他审计行为不变。
- 当前底图实现选择AMAP：已确认并转换为WGS84的有效点经高德官方接口转换后获取单点静态PNG，位置响应附 `mapImageDataUrl`；密钥和外部URL仅留后端。`mapPointAvailable` 只有取得图片后才为true，图片加载失败也降级地址。
- 供应商调用使用合成响应测试，不等同真实地图Key验收；得力样本仍未确认，入库继续UNKNOWN。

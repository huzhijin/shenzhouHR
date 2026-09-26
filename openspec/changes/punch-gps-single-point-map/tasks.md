## 1. 得力 GPS 样本与入库

- [ ] 1.1 【待现场证据：现有夹具为合成数据】取一张脱敏的真实 GPS `check_data`，钉死地址/经纬度字段名，补测试夹具
- [x] 1.2 `DeliEplusClient` 对 `gps`/`out_work` 解析 `locationSummary` 与 raw lat/lng；照片仍丢，`toString` 不含地址原文
- [x] 1.3 写入 `raw_attendance_fact` 坐标列：有坐标但未确认坐标系时 `UNKNOWN_SYSTEM`，不填 `map_*`
- [x] 1.4 单测：现有 gps 夹具不再要求 `locationSummary` 为空；fp/card 仍无地点；碰撞不覆盖旧行坐标

## 2. 日卡列表与受控位置 API

- [x] 2.1 日详情（日报/考勤明细）返回当天 `punches[]`：fact id、时刻、method、sourceLabel，不含经纬度
- [x] 2.2 `GET /api/v1/checkins/{rawFactId}/location`：本人可看自己的；管理者要 scope + `ATTENDANCE_LOCATION:READ` + reason；缺权限 403
- [x] 2.3 位置响应只给 `locationText`、`mapPointAvailable`、可选已转换点、`mapUnavailableReason`；UNKNOWN 无点
- [x] 2.4 每次查看写审计；普通日志不含经纬度数字
- [ ] 2.5 【待现场核对：迁移只覆盖内置角色】核对现场 HR 角色是否已有 `ATTENDANCE_LOCATION:READ`，缺的只补授权不改码名
- [x] 2.6 合同测试：排行/大屏/日报 Excel SQL 或编码器仍不含 latitude/longitude

## 3. 查询页点进某次打卡看地图

- [x] 3.1 考勤日报行详情列出当日各卡；gps/out_work 且有 LOCATION:READ 时出「查看位置」
- [x] 3.2 考勤明细某日列表与日报同一套卡列表，不把入口放在「改打卡」
- [x] 3.3 查看位置先填原因（管理者）再拉 location API；抽屉默认地址文字，有点才画单点；无点或无底图显示「地图暂不可用」
- [x] 3.4 无 LOCATION:READ 时隐藏按钮；员工本人入口不填原因、不能看别人
- [x] 3.5 前端测试：多卡列表、无坐标不画点、无权限无按钮、导出列不含地点

## 4. 底图配置与验收

- [x] 4.1 底图密钥走外部 env，仓库不进密钥；未配置时文字地址仍可用
- [x] 4.2 坐标系确认后（若样本为 GCJ-02）再打开转换与画点，未确认保持 UNKNOWN
- [x] 4.3 部署说明：不必 `checkin_query_init`；新 GPS 卡随现有增量同步带地点；历史卡不回放

## 5. 审查修复

- [x] 5.1 位置权限独立解析人员范围，覆盖多角色交叉授权回归测试
- [x] 5.2 位置查询只使用当前激活关联，覆盖身份重匹配与撤销回归测试
- [x] 5.3 覆盖 HTTP 拒绝审计、本人免原因、非demo前端请求与地图失败降级

## 本地验证记录（2026-09-16）

- 后端15个相关测试类共136项通过，包含位置服务、HTTP方法权限、H2执行原始SQL、位置权限范围、审计迁移及完整原因持久化、地图客户端、得力同步和Excel导出回归。
- 前端4个相关测试文件共66项通过，覆盖非demo请求、本人免原因、管理者原因校验、地图图片及失败降级、日期切换晚到响应、报表和本人页面、审计标签。
- OpenSpec严格校验及修改文件ESLint通过。
- 地图供应商使用合成HTTP响应，数据库使用H2；不等同生产MySQL、高德Key或真实得力样本验收。1.1、2.5保持未完成。

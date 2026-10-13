# V1.9 可自动化验收标准

> 本目录描述未来实现的可执行验收合同，不代表当前代码已经通过。测试数据必须合成；外部联调缺失时标记 `NOT_VERIFIED`。本机 MySQL 建库、迁移和真实联调当前统一为 `NOT_RUN`。

## 1. 账号、session 与权限

| ID | 可自动化验收 |
|---|---|
| AC-AUTH-01 | 正确账号密码建立 session；响应不含密码/哈希；cookie 为 host-only、`Path=/`、`HttpOnly`、`SameSite=Lax`，生产/HTTPS 必须 `Secure`，仅显式 loopback 开发 profile 可关闭 `Secure`。 |
| AC-AUTH-02 | 临时密码账号登录后只能进入强制改密；改密成功后旧 session 和 reset grant 失效。 |
| AC-AUTH-03 | 15 分钟窗口内第 5 次失败触发 15 分钟锁定；阈值可配置；成功登录原子清零。 |
| AC-AUTH-04 | 用户名不存在与密码错误返回等价外部响应，避免账号枚举。 |
| AC-AUTH-05 | 重置 token 一次性、短时、摘要保存；重复使用失败且写审计。 |
| AC-AUTH-06 | 空闲 30 分钟或绝对 12 小时后 session 失效；参数可配置。 |
| AC-AUTH-07 | 停用、离职、改密、重置或管理员强退后所有相关 session 失效。 |
| AC-AUTH-08 | 普通员工、HR、系统管理员均不能因角色自动获得 PAYROLL；未授权 API 返回拒绝且不泄露数据。 |
| AC-AUTH-09 | 员工本人 API 忽略客户端伪造 employee id，只从 session 推导主体。 |
| AC-AUTH-10 | 上传、预检、发布、部分发布、作废/冲正、原文件、原始行、错误报告和重算分别鉴权并应用地点范围。 |
| AC-AUTH-11 | 所有状态变更请求验证 CSRF、capability、scope、record version 和 period status。 |
| AC-AUTH-12 | 登录/失败/锁定/重置/退出/强退/权限变更均写带 request_id 的审计。 |

## 2. 组织、员工、期初导入与任职

| ID | 可自动化验收 |
|---|---|
| AC-PEOPLE-01 | 可下载版本化组织/员工模板和字段说明，上传后先预检，未发布前不写正式组织/员工。 |
| AC-PEOPLE-02 | 预检输出新增/修改/不变/冲突/错误及可下载错误行；阻断错误禁止发布。 |
| AC-PEOPLE-03 | 同一文件或同一幂等键重复发布不新增组织、员工或任职。 |
| AC-PEOPLE-04 | 批次发布后本地组织成为权威；系统不创建定时、手动持续、双向或组织变更同步任务。 |
| AC-PEOPLE-05 | 本地组织/员工写操作产生新版本/审计，不覆盖来源文件和发布快照。 |
| AC-PEOPLE-06 | 二次入职新建任职周期，第一次任职仍可按历史时点查询。 |
| AC-PEOPLE-07 | 同员工任职周期不得重叠；离职空档打卡匹配失败而不是猜测。 |
| AC-PEOPLE-08 | 入职前累计工龄导入/维护保存发生额、原因、操作者和时间；重算可复现。 |
| AC-PEOPLE-09 | 姓名和部门不能作为唯一员工匹配键；多人命中进入错误报告。 |
| AC-PEOPLE-10 | 旧 `MASTER_DATA:SYNC_PREVIEW` 不授予新角色，且没有可发现的组织同步 route/button/job。 |
| AC-PEOPLE-11 | 未发布草稿可作废；已发布批次不得物理删除。仅当该批次仍为最新版本且无考勤、假期、账户或审计外的下游业务引用时，授权撤销才生成恢复上一快照的新版本；有引用或后续版本时返回 409 并要求前向更正。 |
| AC-PEOPLE-12 | 任职统一为 `[start_date,end_exclusive)`；业务离职日转换为 `end_exclusive=离职日+1`，离职日匹配有效、次日起匹配无效。 |

## 3. 统一规则与考勤设置

| ID | 可自动化验收 |
|---|---|
| AC-POL-01 | 规则只接受已登记类型和枚举；脚本、未知字段和表达式被拒绝。 |
| AC-POL-02 | 生命周期为草稿→校验→发布→停用；已发布版本不能原位编辑。 |
| AC-POL-03 | 发布前返回作用范围冲突、优先级冲突、影响员工/日期、冻结期间和样例试算。 |
| AC-POL-04 | 回滚发布新版本并引用历史快照，不删除旧版本。 |
| AC-POL-05 | 公司/地点/考勤组/政策组 + 生效期解析确定；同优先级冲突必须阻断。 |
| AC-POL-06 | 个人特殊情况只能通过有期限专用组表达，源码/配置不存在 employee/city 硬编码分支。 |
| AC-POL-07 | 冻结结果始终引用原规则快照；新规则不改变旧 close snapshot。 |
| AC-ATTSET-01 | 夏冬令是同一考勤组下无空档/重叠的班次版本；12 月 31 日命中有效版本。 |
| AC-ATTSET-02 | 新建考勤组默认启用晚餐扣除，且工作日/周末/节假日窗口、时长、触发分别可配。 |
| AC-ATTSET-03 | `late=0` 不命中宽限；`0<late<=15` 可命中；`late>15` 不消耗次数。 |
| AC-ATTSET-04 | 同员工自然月默认仅 1 次宽限，跨考勤组不重置；并发计算不能双重消费。 |
| AC-ATTSET-05 | 单边缺卡在 7 自然日内为待补正；逾期只将缺失卡对应工作段计旷工。 |
| AC-ATTSET-06 | 有效且及时的请假/外出/补卡/出差/加班证据按优先级和重叠切分抵消相应异常。 |
| AC-ATTSET-07 | 加班餐扣默认适用全部班组，工作日/周末/节假日分别试算。 |
| AC-ATTSET-08 | 临时加班结束后 47:59 提交且通过可核算，48:01 提交认可加班为 0；次日 02:00（默认 06:00 截止前）回挂前日且不能复用为次日上班卡；晚退无单据的认可/计薪/调休均为 0；义务加班保留认可工作时长但计薪/调休均为 0。 |
| AC-ATTSET-09 | 已批准 HR 裁定/调整、销假/撤销/冲销、有效业务单据、原始打卡、系统异常按固定顺序逐段解析；同级互斥证据重叠产生 `EVIDENCE_CONFLICT` 而非最后写入覆盖。 |

## 4. 离线/异构考勤打卡 Excel

| ID | 可自动化验收 |
|---|---|
| AC-PUNCH-01 | 版本化 `.xlsx` 模板包含说明、打卡、设备人员映射、字段说明、枚举和合成示例。 |
| AC-PUNCH-02 | 仅接受原始打卡字段；迟到/旷工/加班时长等计算结果列被拒绝。 |
| AC-PUNCH-03 | 20MB/50,000 行为受控默认上限；宏、公式执行、外链和危险内容被拒绝。 |
| AC-PUNCH-04 | 厂商/型号/地点 mapping profile 可保存、版本化并绑定上传批次。 |
| AC-PUNCH-05 | 工号优先匹配有效任职；否则按地点+设备+设备人员有效绑定；姓名只核对。 |
| AC-PUNCH-06 | 相同内容换文件名重传命中文件哈希，不重复发布。 |
| AC-PUNCH-07 | 原始记录 ID 优先幂等；缺失时稳定指纹阻止完全重复且不丢正常多次打卡。 |
| AC-PUNCH-08 | Excel 与得力 API 同流水或同员工同精确时间只生成一个有效事件，全部 raw fact 保留。 |
| AC-PUNCH-09 | 同员工同方向、不同来源且相差 `1～60` 秒默认进入 `PENDING_DUPLICATE_REVIEW`，裁决前 0 个 active event；确认同一事实后恰有 1 个，确认不同事实后恰有 2 个，全部 raw fact 保留。 |
| AC-PUNCH-10 | 默认严格发布；有阻断错误不能发布；受控部分发布产生 `PARTIALLY_PUBLISHED` 和完整错误行。 |
| AC-PUNCH-11 | 开放期发布只重算受影响员工/日期；无关员工 calculation version 不变。 |
| AC-PUNCH-12 | 冻结/月结期可上传预检但不能发布；重开后产生新计算版本、差异和审计。 |
| AC-PUNCH-13 | 已发布批次只能作废/冲正，不能物理删除；原文件、行、哈希、事件和审计可查。 |
| AC-PUNCH-14 | 批次/行保存模板与 mapping version、raw/normalized values、match、error、request_id 和 event id。 |
| AC-PUNCH-15 | 预检失败、冻结阻断、发布事务失败分别为 `VALIDATION_FAILED`、`BLOCKED_BY_FROZEN_PERIOD`、`PUBLISH_FAILED`；每一状态的重试前置不同且 OpenAPI/UI 枚举一致。 |

## 5. 统一证据、计算、异常与月结

| ID | 可自动化验收 |
|---|---|
| AC-CALC-01 | 得力、Excel、OA 分别创建 raw fact，但共享 normalized/effective/calculation 表和服务。 |
| AC-CALC-02 | 原始事实只追加；撤销、补录、调整产生新事实/版本，不 UPDATE 覆盖来源。 |
| AC-CALC-03 | 跨日工作段按班次归属，不按自然日截断。 |
| AC-CALC-04 | 重叠单据切分为不重叠时段后逐段应用证据优先级。 |
| AC-CALC-05 | 任一日结果可下钻到班次、规则快照、打卡、单据、调整、计算版本和 request_id。 |
| AC-CALC-06 | 人工调整要求原因、能力、范围和审计；不能改原始打卡。 |
| AC-CALC-07 | 相同输入/规则快照重算得到相同结果；不同版本生成可解释差异。 |
| AC-CLOSE-01 | 月结前置检查阻断未解决硬错误、运行中导入/重算和版本冲突。 |
| AC-CLOSE-02 | close 保存组织/任职/规则/事实/结果 snapshot，关闭后普通发布与重算返回 409。 |
| AC-CLOSE-03 | reopen 需独立权限和原因，生成新 period version；旧 snapshot 可审计。 |
| AC-CLOSE-04 | 报表汇总与同版本明细重算差异为 0。 |

## 6. 假别、周年年假与时间账户

| ID | 可自动化验收 |
|---|---|
| AC-LEAVE-01 | 每个假别配置单位、工作日/自然日口径、最小粒度、资格、材料、发放、有效期、结转、失效、销假、取消、冲突和余额不足处理。 |
| AC-LEAVE-02 | 未显式配置时按工作日计算；规则默认值来自已发布版本。 |
| AC-LEAVE-03 | 未批准申请取消不写余额流水；已批准申请的销假/取消只对获批未休时段生成返还冲销流水并重算，原申请和原扣减流水不删除。 |
| AC-LEAVE-04 | 年假/调休余额不足时拒绝或按已确认策略提示拆单，不得产生负余额、静默改成其他假别；冻结/月结期间返还须先重开。 |
| AC-ANNUAL-01 | 最新任职周期本公司连续不足 12 完整日历月无资格，满 12 月于周年日取得资格。 |
| AC-ANNUAL-02 | 累计工龄 = 最新任职周期本公司工龄 + 入职前累计工龄。 |
| AC-ANNUAL-03 | 累计工龄 `[1,10)`、`[10,20)`、`[20,+)` 分别发 5/10/15 天和 40/80/120 小时。 |
| AC-ANNUAL-04 | 周年日前一日不发放；周年日先失效旧周期剩余量再生成新 grant；grant 有效区间为 `[anniversary,next_anniversary)`。 |
| AC-ANNUAL-05 | 周期内跨档默认不补差，下一周年采用新档；行为可配置。 |
| AC-ANNUAL-06 | 二次入职从最新入职日重算资格和周年；旧任职发放历史不覆盖。 |
| AC-ANNUAL-07 | 2 月 29 日入职者默认在非闰年 2 月 28 日周年；2024-02-29 入职者在 2025-02-27 为 0、2025-02-28 获得 40 小时。 |
| AC-ANNUAL-08 | 离职日仍在任职期；次日起禁止新申请并按离职策略生成失效流水。再入职使用新周年，旧 grant/使用/失效流水不复活。 |
| AC-ANNUAL-09 | 周期中途规则切换不改变既有 grant；下一周年使用当日生效版本；周年业务日已冻结/月结时不自动补写，重开后生成新版本、差异和审计。 |
| AC-TIME-01 | 期初工时/调休/年假 Excel 经模板、匹配、单位换算、预检、发布和错误报告。 |
| AC-TIME-02 | 同批重复发布不重复入账；作废通过冲正流水恢复。 |
| AC-TIME-03 | 余额由期初+发放+返还+调整-使用-失效±冲正汇总，与流水重算一致。 |
| AC-TIME-04 | 每条流水保存来源、发生额、余额语义、业务日期、有效期、冻结和版本。 |

## 7. 员工自助、看板、位置、报表与来源运行

| ID | 可自动化验收 |
|---|---|
| AC-SELF-01 | 员工今日/记录/假期只返回 session 本人数据，并显示班次、有效打卡、状态、数据截至时间、余额来源与可下钻解释。 |
| AC-FEEDBACK-01 | 员工可按本人考勤日期提交纯文本反馈、查看 HR 回复/处理中/已解决状态和关联调整；越权日期/员工返回拒绝，内容输出编码并写审计。 |
| AC-RANK-01 | 签到排行仅在独立发布开关和 capability 命中时返回名次、展示名、允许时间粒度及本人可见留言；不得返回原始打卡 ID、坐标、设备、请假原因或未签到名单。 |
| AC-RANK-02 | 留言支持纯文本、回复、举报和本人删除；删除保留审计，举报内容仅授权人员可见，脚本/富文本不执行。 |
| AC-POLICYCTR-01 | 制度中心只显示本人当前/未来适用且已发布的考勤、加班、请假制度，支持搜索和版本生效日；草稿、越权范围和薪资策略不可见。 |
| AC-LOCATION-01 | 员工仅查看本人单次打卡位置；管理者同时满足人员 scope + `LOCATION:READ` 并填写原因，才可查看一次；无坐标显示地点文字，未知坐标系不转换，查看事件写审计。 |
| AC-DASH-01 | 公司/部门/个人看板分别按 capability/scope 汇总，并显示数据截至时间；公司大屏和签到排行均不含精确坐标、薪资或敏感假因。 |
| AC-REPORT-01 | 报表汇总可下钻到同一版本明细；导出复用页面 scope/筛选/字段白名单，创建和下载均重验权限；`<=50,000` 行同步，超过阈值异步。 |
| AC-SOURCE-01 | 得力/OA 适配器失败不删除水位或既有事实；重试从最后已提交水位继续，同一业务键/撤销只追加新事实，任务状态、错误和 request_id 可查。 |

## 8. 薪资边界

| ID | 可自动化验收 |
|---|---|
| AC-PAY-01 | 默认 feature flag 关闭时，无菜单、route、首页卡片、搜索、通知、导出、员工入口和埋点。 |
| AC-PAY-02 | 普通会话调用任何 payroll API 均在查询数据前拒绝；通用 DTO 不含工资字段。 |
| AC-PAY-03 | PAYROLL 与 ATTENDANCE/HR/SYSTEM_ADMIN 角色隔离，默认拒绝。 |
| AC-PAY-04 | 后端预留只读取明确关闭的考勤 snapshot，不生成工资条、税社保数值或银行文件。 |

## 9. UI、可访问性与非功能

| ID | 可自动化验收 |
|---|---|
| AC-UI-01 | 每个异步页面具备 loading、empty、error、401/session-expired、403；session 失效时清空受保护缓存、跳转登录并在重新鉴权前不闪现旧 capability/scope 数据；导入有 processing/partial/success，规则/月结有 conflict/frozen。 |
| AC-UI-02 | 所有交互有 hover、focus-visible、active、disabled；焦点不丢失，dialog 支持 trap/Escape/restore。 |
| AC-UI-03 | 360～430px 无页面横向滚动；管理表格卡片化或受控局部滚动；触控目标至少 44px。 |
| AC-UI-04 | 只使用 Tabler Icons、2px stroke；无 emoji 功能图标、无混合 SVG 图标体系。 |
| AC-UI-05 | 人工确认后，组件只使用生效的 canonical semantic tokens，无 raw hex、直接白/黑 utility 或一锤子 inline style。 |
| AC-UI-06 | surface/on-surface 对比度正文 >=4.5:1，UI/大字 >=3:1；状态不只依赖颜色。 |
| AC-UI-07 | `prefers-reduced-motion` 下无非必要动画；状态动效 <=150ms 且仅 transform/opacity。 |
| AC-NFR-01 | 所有请求返回/记录 correlation id，日志不含密码、token、工资或非必要敏感字段。 |
| AC-NFR-02 | 原始文件/错误报告/导出采用独立授权、短时访问和审计。 |
| AC-NFR-03 | 前端 `npm run check`、后端 `./mvnw test`、OpenAPI 合同和架构测试实际通过。 |
| AC-NFR-04 | MySQL 8.4 空库 V1→当前、V2→当前升级和 `flyway validate` 实际通过。 |
| AC-NFR-05 | 使用上线前实测峰值与 36 个月数据量、50 并发压测：常用列表 P95 `<=2s`、个人/部门看板 P95 `<=3s`、单条详情 P95 `<=1s`；样本数、热身、失败率和 P95 原始报告留存。 |
| AC-NFR-06 | PC/移动核心页首个可用内容 `<=2.5s`；上游正常时来源数据 95% 在 10 分钟内、99% 在 30 分钟内可见；现网容量全月重算/月结 `<=30min`。 |
| AC-NFR-07 | `<=50,000` 行同步导出 `<=60s`，超过阈值必须异步；月度可用性 `>=99.5%`（排除已公告计划维护）。 |
| AC-NFR-08 | 在隔离环境从备份恢复并校验数据/附件/审计可读：RPO `<=15min`、RTO `<=4h`，每季度至少完成一次；只生成备份不算通过。 |
| AC-NFR-09 | `preview_confirm` 在规定桌面/平板/手机/4K 视口完成真实浏览器截图，无裁切、重叠或敏感数据。 |
| AC-METRIC-01 | 正式期初导入发布成功行/拟导入行为 100%，阻断错误必须在预检解决；月结关闭前硬阻断异常为 0；余额展示值与流水重算差异恒为 0。 |
| AC-METRIC-02 | 自动计算率按员工日计算，试运行第 2 个月 `>=95%`；越权测试路径阻断率 100%；手工调整率 `>5%` 触发规则复盘事件。 |
| AC-METRIC-03 | 数据新鲜度 P95 `<=10min`；每月记录是否在次月第 2 个工作日内关闭，逾期生成可观测告警但不伪改月份状态。 |
| AC-AUDIT-01 | 八类事件字段固定为：`org_import_validated(batch_id,total,error_count,hash)`、`opening_balance_published(batch_id,account_count,total_hours)`、`attendance_recalculated(employee_id,date,reason,version,result)`、`exception_resolved(type,source,resolution,elapsed_hours)`、`attendance_month_closed(month,version,employee_count,hash)`、`time_ledger_adjusted(account,type,hours,reason)`、`location_viewed(viewer,employee,punch_id,reason,result)`、`payroll_access_denied(subject,route,reason,request_id)`；均不得含凭据或越权敏感值。 |

## 10. 本机 MySQL、Flyway 与真实联调

| ID | 可自动化验收 |
|---|---|
| AC-DB-01 | 只创建/连接 `shenzhou_hr_dev` 和 `shenzhou_hr_test`；两库均为 InnoDB、`utf8mb4`、`utf8mb4_0900_ai_ci`，数据库 session 使用 UTC，业务日期按 `Asia/Shanghai` 解释；任何其他库名使保护检查失败。 |
| AC-DB-02 | 本机迁移账号仅对两库拥有迁移所需权限；`shenzhou_hr_dev_app` 只对 dev、`shenzhou_hr_test_app` 只对 test 拥有业务 DML；两者不能建库、建表、授权或访问其他 schema，Spring Boot 日常运行主体不是 root。 |
| AC-DB-03 | 数据库凭据只从仓库外权限为当前用户可读写的环境文件或本机凭据存储注入；仓库、前端构建、日志、进程参数、测试报告和最终报告的 secret scan 均无凭据值。 |
| AC-DB-04 | V1/V2 文件名和 checksum 与确认基线一致；所有 V1.9 正式 DDL/DML 只存在于新的 V3+ 迁移，启动时没有 JPA/Hibernate/MyBatis 自动建表。 |
| AC-DB-05 | 全新 `shenzhou_hr_test` 从 V1 迁移到当前最高版本，`flyway_schema_history` 顺序、成功标志和实际表/索引与该波合同一致。 |
| AC-DB-06 | 具有 V1/V2 schema 和合成数据的测试快照升级到当前最高版本后，历史数据、外键/等效引用、精确外部 ID 和已发布审计仍可查询且未被截断或改写。 |
| AC-DB-07 | `flyway validate` 通过；同一目标第二次 migrate 为 no-op，表数、关键目录行数和 checksum 不变；已发布迁移缺陷只能由更高版本前向修复。 |
| AC-DB-08 | Spring Boot 分别以非 root 应用账号连接 dev/test 并通过 health；应用账号执行 DDL、授权或跨 schema 查询均失败。 |
| AC-DB-09 | 每波至少一个写 API 把合成数据提交到本机 MySQL，并由对应读 API 和数据库断言读回；响应、数据库记录与 correlation id 可关联，事务失败不留部分业务状态。 |
| AC-DB-10 | 主键、唯一、外键/等效完整性、受控状态、生效期、`row_version`、审计字段、查询/排序/分页索引和幂等/去重索引均有命名测试；致远 19 位及以上 ID、设备原始 ID 和外部标识往返保持 string/精确值。 |
| AC-DB-11 | 权限负向测试在查询或写入前阻断越权主体，返回约定的 401/403/404/409，且数据库行数、版本和审计可见范围符合合同。 |
| AC-DB-12 | 前端普通开发模式通过 `/api` 代理显示真实 API/MySQL 数据，并可机械验证 loading、empty、error、403、conflict 和 pagination；浏览器不持有任何数据库凭据。 |
| AC-DB-13 | demo 模式不访问后端且不写 dev/test；真实模式不读取 demo 数据。切换前后用网络请求计数和数据库行数证明互不污染。 |
| AC-DB-14 | 测试可精确删除并重建 `shenzhou_hr_test`；`shenzhou_hr_dev` 默认保留，任何破坏性操作前都有精确库名复核、影响说明、备份校验和恢复步骤；报告测试库结束状态但不含凭据。 |
| AC-DB-15 | 上线前在 MySQL 8.4 LTS 独立重跑 V1→最新、V2→最新、validate、二次 migrate、约束、并发锁、关键查询计划、最小权限、API 和隔离恢复；本机其他版本的通过结果不能替代该证据。 |

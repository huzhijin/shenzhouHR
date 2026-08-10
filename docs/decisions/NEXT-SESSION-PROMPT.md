# 新会话提示词（2026-08-11 客户拍板后）

> 把下面「提示词正文」整段粘贴到新窗口即可开工。
> 详细实测凭据、环境陷阱、REST 路径全清单见 `docs/decisions/HANDOFF-2026-08-11.md`（639 行）。

---

## 提示词正文（从这里开始复制）

继续神州 HR 考勤模块。项目在 `/Users/huzhijin/Downloads/shenzhouHR`。

**第一件事：读 `docs/decisions/HANDOFF-2026-08-11.md`**（639 行，14 章节）。里面有实测过的启动命令、
登录账号、数据库连接方式、REST 路径全清单、已踩过的环境陷阱。不要重复踩。

### 工作纪律（上一轮出现 10 次误报，必须遵守）

上一轮会话有 **10 次**把未完成的工作报告为已完成——编造了迁移文件、测试通过数、部署手册、
代码改动、甚至编造了「提示词文件已保存 239 行」。这些误报**全部发生在工具环境失联期间**，
当时把记忆当成了验证结果。

- 每报告一项「已完成」，必须附实际命令输出或数据库查询结果
- Write/Edit 返回成功后，**仍要独立查询**确认文件真实存在且内容正确
- 工具返回空或乱码时，明确说「未验证」，不要推断
- **连续 2 次工具调用返回空 → 立即停止报告，说明环境失效**
- 「测试通过」不是验收标准。目标是页面上能看到真实数据

---

## 客户 2026-08-11 已拍板的四件事

### 1. 得力凭据是生产可用的，一定能拉到数据

```
key    = a3aefec1264c0028f06b7eceb1e41ea6
secret = c64bihjmqahkkywgaizmdh9kpx8kxey8
文档   = http://doc.delicloud.com/v3/integration/oa.html
```

客户明确：「就是我刚提供的 key 那些，绝对是可以并获取数据的。」

**上一轮我判断这是沙箱租户（只返回 1 员工 + 1 打卡），这个结论很可能是错的。** 三个原因：

1. **我从头到尾没读过客户给的 v3 文档**，只按代码里现成的 v2 写法发请求
2. 响应里 `has_next: true` 本身就说明**后面还有数据**，是我把游标字段取错了
   （代码读 `next_id`，v3 可能是别的字段名）
3. 请求可能缺必填参数（企业/部门范围、时间窗口的正确参数名）

**所以第一步是：完整读一遍 v3 文档**，确认正确的接口路径、必填参数、翻页字段名、签名方式，
然后按文档重发请求。**在读完文档并按文档重试之前，不要再对数据量下任何结论。**

已实测可用的部分（可作为起点，但要用 v3 文档校对）：

```
POST https://v2-api.delicloud.com/v2.0/employee/query   → HTTP 200, code:0 success
POST https://v2-api.delicloud.com/v2.0/cloudappapi      → HTTP 200, code:0 success
签名 = MD5(appKey + appSecret + timestamp) 小写十六进制
头   = App-Key / App-Timestamp / App-Sig
       打卡端点额外需 Api-Module: deli.eplus.attendance.checkin.query
       员工端点不需要 Api-Module
```

客户端代码：`backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/`
（`DeliEplusClient.java` 639 行；常量 `API_PATH=/v2.0/cloudappapi`、`EMPLOYEE_PATH=/v2.0/employee/query`）

凭据已在 `.env.deli.local`（已确认被 Git 忽略），与客户本次给的值**完全一致**，无需更新。
但 `DELI_EPLUS_ENABLED=false`，且**从未注册过任何数据源**
（实测 `attendance_source`、`deli_source_connection_probe`、`deli_source_operation`、
`deli_employee_binding_revision` 全部 0 行）。

**工号对齐问题仍需处理**：得力上一轮返回 `H0001`，本系统 615 人的工号前缀是
`SZ(119) / C(108) / D(93) / N(93)`，**没有 H 开头的**。拉到完整员工列表后，
先统计工号匹配率再写绑定。列名是 **`employee_code`**（不是 `employee_number`）。

得力同步的 REST 端点（已存在）：

```
POST /api/v1/attendance-sources                              注册数据源
POST /api/v1/attendance-sources/{sourceId}/probes            连通探测
POST /api/v1/attendance-sources/{sourceId}/activation         激活
POST /api/v1/attendance-sources/{sourceId}/syncs              发起同步
POST /api/v1/attendance-sources/{sourceId}/employee-bindings  得力ID ↔ 本系统工号
GET  /api/v1/attendance-sources/{sourceId}/employee-bindings
```

### 2. 任务 3 选「甲」，现在就干

只开放本轮真正需要的 2 条：**打卡取卡窗口（PUNCH_WINDOW）**、**月结封账（PERIOD_CLOSE）**。
其余 5 条继续走系统默认。

库里 V35 已经落了这两条模板（实测规则模板 10 条），但**后端只支持 3 种**，所以前台只显示 3 条。

实测源码现状（任务 3 一行代码都没改过）：

```
后端 PolicyKind 枚举        → 3 个值（MEAL_DEDUCTION, LATE_GRACE, MONTHLY_LATE_EXEMPTION）
AttendancePolicyCatalog     → 3 条（PUNCH_WINDOW 出现 0 次）
Validator RULES             → 3 类（PUNCH_WINDOW 出现 0 次）
前端 union 类型              → 3 项
前端 Segmented 选项          → PUNCH_WINDOW 出现 0 次
/api/v1/attendance-setup/policy-catalog 实测返回 3 条
```

枚举 3 → 5 会同时破坏 **4 处穷尽性契约**，必须一起改：

| 位置 | 现有逻辑 | 不改的后果 |
|---|---|---|
| `AttendancePolicyService:406` | `bindings.size() != PolicyKind.values().length` | 试算全报 `POLICY_MISSING` |
| `AttendancePolicyService:483` | 遍历所有枚举值，缺一个就抛错 | **9 个考勤组全部解析失败** |
| `AttendanceGroupService:53` | 硬编码 3 项 `ROLLOVER_POLICY_KIND_ORDER` | 换组时漏配新类型 |
| `AttendancePolicyParameterValidator` | `RULES` 只登记 3 类，缺失则 `POLICY_KIND_UNSUPPORTED` | 新类型一律被拒 |

**建议顺手改掉这个设计缺陷**：把穷尽校验改成只校验试算真正用到的 3 类
（MEAL_DEDUCTION / LATE_GRACE / MONTHLY_LATE_EXEMPTION），新增的管理类规则
「有绑定则透传、无绑定不阻断」。否则以后每加一条规则都会打挂全部考勤组。

还要补 `AttendancePolicyCatalog`（它是 REST `/policy-catalog` 的数据源，前端照它动态渲染输入框）。
**必须保证三方键名完全一致**：catalog 的 `field()` 键、Validator 的 `RULES` 键、
V35 迁移里的 `field_definitions_json` 键。不一致的话前端提交会被后端拒。

V35 里两条模板的字段（照抄，别自己发明）：

```
PUNCH_WINDOW:  enabled, arrivalBeforeMinutes, arrivalAfterMinutes,
               departureBeforeMinutes, departureAfterMinutes        (0~720)
PERIOD_CLOSE:  enabled, closeDayOfNextMonth(1~28), reopenAllowed,
               reopenRequiresApproval, maxReopenCount(0~99, 0=不限)
```

前端要改 3 处：`attendanceSetupTypes.ts` 的 union、`AttendancePolicyPage.tsx` 的
Segmented options（约 418-422 行）、同文件的 `policyKindLabel`（约 669-675 行）。

最后补 **18 条绑定**（9 组 × 2 类）。写成部署 SQL 放 `deploy/mysql/`，不要写 Flyway 迁移
（这是客户特定运营数据，全新库上匹配 0 行没意义）。

**两个已踩过的坑**：

- 绑定表唯一键是 `(binding_family_id, effective_from)`。现有 rev1 占用了 `2026-01-01`，
  新绑定必须换日期（上一轮大连用了 `2026-01-02`，报错原文 `ERROR 1062`）
- `AttendancePolicyMapper.xml#resolveBindings` 只解析**有 `action='PUBLISHED'` 生命周期事件**
  的版本。只插 `scoped_version` 行不生效，必须同时插 PUBLISHED 事件

### 3. 大连周六/周日/节假日的晚餐门槛：不改

客户明确「3 不改」。上一轮只改了工作日 `triggerMinutes` 0 → 120（仅大连 13 人，已生效并验证）。
`saturdayTriggerMinutes` / `sundayTriggerMinutes` / `publicHolidayTriggerMinutes` **保持 0**。

### 4. 新需求：615 名员工的账号全部按人员默认分配，并列入期初数据

客户原话：「所有的账号都默认根据人员分配好啊。也要列入期初数据。」

实测现状：

```
在职员工                      615
本地账号总数                    5
启用账号                        1
已绑员工的账号（employee_id 非空） 0     ← 一个都没绑
EMPLOYEE_SELF 角色              存在
```

已核实的可用条件：

```
GET  /api/v1/access/account-provisioning/candidates    候选员工查询（已存在）
POST /api/v1/access/account-provisioning/accounts      批量开通（已存在）
     ↑ 需要请求头 Provisioning-Recovery-Key
auth_principal.employee_id                              列存在
auth_role                                               6 个角色齐全
前端 EmployeeAccountProvisioningDialog.tsx              已有开通对话框，
     登录名用工号、角色固定「员工本人」、生成一次性密码清单
```

**要做的**：

1. 先读 `AccountController.java` 的 `/account-provisioning/accounts` **请求体契约**
   和 `Provisioning-Recovery-Key` 的取值来源。**契约未验证过，不要凭猜调用**
2. 615 人默认只开 `EMPLOYEE_SELF`，登录名 = 工号，数据范围 = 仅本人
3. 部门负责人 / 公司 HR / 高管 **不默认分配**，由管理员在账号管理里单独指定
4. 只给在职且有有效任职的员工开通
5. 写成**可重放**的期初数据脚本（放 `deploy/mysql/` 或 `scripts/`），重复执行不产生重复账号
6. 临时密码**不明文存库、不进 Git**。一次性清单文件权限 0600
7. 开通后实测验证：`auth_principal.employee_id` 非空的数量应等于开通人数，
   并用其中一个工号真实登录一次，确认能看到「我的考勤」

---

## 开发侧优先级（客户明确：考勤数据和报表第一位）

1. **读 v3 文档 → 按文档重发得力请求 → 拉到完整打卡数据**（客户确认凭据可用，这是第一位）
2. **补三段适配器** —— 让报表出数字的唯一路径，也是最大一块工作
3. **任务 3「甲」** —— 枚举 3→5 + 4 处契约 + catalog + 前端 3 处 + 18 条绑定
4. **615 人账号期初数据**
5. 任务 4（新增 `ATTENDANCE_REPORT_REFRESH` 常量、改 3 处引用）
6. 修报表权限下拉缺陷
7. SITE_PORT 改造 → 宝塔部署手册

### 第 2 项的缺口（实测）

```
得力API → 注册源+绑定员工+同步 ⬜ → raw_attendance_fact(0)
  → 归一化器 ❌无实现 → evidence_*(0) → 快照Port ❌无适配器
  → 计算器 ✅完整(47个测试) → orchestrator ❌ → 报表投影(0) → 页面出数字
```

```
AttendanceNormalizer                    → 无实现
PunchNormalizer                         → 无实现
FrozenAttendanceSnapshotPort            → 只有 payroll/application 里的接口定义，无适配器
AttendanceReportCalculationOrchestrator → 未完成核实
```

计算器本身完整且有 47 个测试，发布器校验严密到摘要级幂等。缺的是把它接到数据上的胶水层。
**这三段无论数据来自得力还是 Excel 都是必经之路。**

替代验证路径：`punch_import_*` 整条链路（上传→预检→归一→发布）代码是齐的，
可以用 Excel 导一个月真实打卡在本机端到端验证工时算法。

### 第 6 项的缺陷定位（实测）

服务端是**对的**：`GET /api/v1/attendance-reports/scopes` 返回 4 条，全是「公司 HR」，
只是公司不同（该账号确实在 4 家公司持有 HR_ADMIN），且有 `ATTENDANCE_REPORT:READ` 守卫。
篡改 `scopeReference`（`SELF` / `ORG:fake-org-id` / 别家公司 ID / `bogus-reference`）
四次均返回同一个 409，服务端没有采纳传入值。

缺陷在**前端**：`frontend/src/features/reports/customerReportAccess.ts` 第 33/41/49/57 行
硬编码了「公司HR / 高管 / 部门负责人 / 本人」四个预设，查询失败(409)时兜底渲染出来
——就是客户截图看到的样子。该文件注释自己写着 `falls back to these presets only when that lookup fails`。
Select 在 `CustomerReportCenterPage.tsx:445-457`。

修复：删掉兜底清单（scope 只能来自服务端，失败时置灰提示）、只有一个授权时隐藏下拉、
核实 `AccountsPage.tsx`/`AccountDetailPage.tsx` 的角色分配**是否真支持多选**（未核实）。

**⚠️ 有真实数据后必须重做篡改测试** —— 上一轮测试时投影表为空，请求没走到取数那一步，
不足以证明服务端在有数据时也不采纳。

---

## 薪资：本轮不做

客户明确：「薪资本次不考虑，有也隐藏不显示。」

实测**无需隐藏，本来就看不到**：库中无薪资表、迁移中无薪资表、`payroll` 包 10 个文件
无 `@Service` 无 `@RestController`、前端对 payroll/薪资/工资/salary **零引用**。

---

## 启动方法（实测可用）

### 后端

根因：启动失败是缺 `SHENZHOUHR_PROVISIONING_PEPPER`，**与数据库无关**。
报错原文 `SHENZHOUHR_PROVISIONING_PEPPER must be 32 random bytes encoded as base64url`。

```bash
cd /Users/huzhijin/Downloads/shenzhouHR/backend
set -a; . "$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env"; set +a

PEPPER=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")

export SPRING_PROFILES_ACTIVE=dev
export SHENZHOUHR_SERVER_PORT=8080
export SHENZHOUHR_DB_URL='jdbc:mysql://127.0.0.1:13306/shenzhou_hr_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true'
export SHENZHOUHR_DB_USERNAME=shenzhou_hr_dev_app
export SHENZHOUHR_DB_PASSWORD="$SHENZHOUHR_DEV_DB_PASSWORD"
export SHENZHOUHR_FLYWAY_ENABLED=false
export SHENZHOUHR_FLYWAY_USERNAME=shenzhou_hr_local_migrator
export SHENZHOUHR_FLYWAY_PASSWORD="$SHENZHOUHR_FLYWAY_PASSWORD"
export SHENZHOUHR_SESSION_COOKIE_SECURE=false
export SHENZHOUHR_PROVISIONING_PEPPER="$PEPPER"
export SHENZHOUHR_PROVISIONING_KEY_ID=v1

./mvnw -o -q spring-boot:run
```

启动约 48 秒，health 应返回 `{"groups":["liveness","readiness"],"status":"UP"}`。

### 前端

```bash
cd /Users/huzhijin/Downloads/shenzhouHR/frontend
export VITE_API_PROXY_TARGET=http://127.0.0.1:8080
npm run dev
```

### 登录

`http://127.0.0.1:5173/login` — `szsc_admin_faa41d5bd802` / `Shenzhou@2026Dev`

密码是上一轮重置的，原哈希备份在 `/tmp/szhr-dev/old-hash.bak`（0600）可回滚。
该账号有 HR_ADMIN + SYSTEM_ADMIN，覆盖 SZJN/SZSC/SZSZ/SZXY 四家公司。

**dev 旁路的陷阱**：`DevelopmentPrincipalFilter` 只授予 `List.of()` **空权限**，
能登进但看不到报表。要看数据必须走正常登录。

### 数据库

```bash
set -a; . "$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env"; set +a
CNF=$(mktemp); chmod 600 "$CNF"
printf '[client]\nhost=127.0.0.1\nport=13306\nuser=shenzhou_hr_dev_app\npassword=%s\n' \
  "$SHENZHOUHR_DEV_DB_PASSWORD" > "$CNF"
"$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/install/bin/mysql" \
  --defaults-extra-file="$CNF" shenzhou_hr_dev -N -B -e "SELECT 1;"
rm -f "$CNF"
```

必须显式 `127.0.0.1:13306`（另有 MySQL 在 3306，凭据不同）。
`shenzhou_hr_dev_app` 只有 DML；**DDL/迁移用 `shenzhou_hr_local_migrator`**。
跑 Flyway：`bash deploy/mysql/flyway-maven.sh -configFiles=<conf> -target=NN migrate`
（wrapper 必须同时给 configFiles 和命令）。

---

## 环境陷阱（已踩过，别重复）

1. **出网能力会变**。上一轮早期 curl 得力/GitHub/8.8.8.8 全部超时（exit 28），
   后期 `v2-api.delicloud.com` 返回 200。**每次用网络前重新探测**，不要沿用旧结论
2. **`nc -z` 会假阳性** —— 对确定关闭的端口也返回 OPEN。判断连通性**只能用 curl**
3. **工具环境会成段失联** —— `echo ok`、`printf`、`true`、Read 已知存在的文件全返回空。
   10 次误报全部发生在这些时段。**连续 2 次空返回就停下来说明**
4. Read 偶发返回重复行/乱码。用 `python3` 逐行打印可拿准确内容；**编译和测试是最可靠的仲裁**
5. zsh 里 `--include` 通配符要加引号（`--include='*.java'`），否则报 `no matches found`
6. 已核实的列名：员工工号 **`employee_code`**（非 `employee_number`）；
   账号表 **`local_account`**（非 `account`）；`auth_role` 用 **`display_name`**（非 `name`）；
   `auth_principal` 有 `employee_id`；`auth_principal` **没有** `username` 列
7. REST 路径别猜，`HANDOFF-2026-08-11.md` 第 7 节有全清单

---

## 验收标准（缺一不算完成）

- 后端连真实 dev 库启动（非 demo、非 mock）
- 前端能登录进去
- `/attendance/reports` 的 9 张报表能打开且有真实数据，不是空态、不是 409
- 考勤大屏和工作台指标卡有真实数字
- 至少一个完整月份的真实出勤工时和加班工时能在页面上点开看到明细
- 615 名员工的账号已按人员分配好，能用工号登录看到「我的考勤」

---

## 提示词正文结束

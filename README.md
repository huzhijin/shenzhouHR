# 神州 HR 考勤与薪资核算系统

当前正式需求基线为 V1.9 `docs_confirm`。本仓库正在实施 WAVE-1：本地账号、登录会话、服务端权限、审计与版本化通用规则配置底座；在人工确认前不得进入后续考勤或薪资业务波次。

## 技术基线

- 前端：React 19、TypeScript、Vite、Ant Design、Tabler Icons
- 后端：OpenJDK 21、Spring Boot 4.1、Maven、MyBatis
- 数据库：MySQL 8.4 LTS 生产基线、Flyway 前向迁移
- 本地联调：直接连接 `127.0.0.1:3306`，不使用 Docker、Podman 或 Testcontainers

## WAVE-1 路由

- 登录：`/login`
- 规则中心：`/rules`、`/rules/templates`、模板详情与版本详情
- 访问控制：`/access/accounts`、账号详情、`/access/roles`
- 审计：`/access/audit`、审计详情

菜单与生产路由由服务端返回的 capability 控制；前端隐藏不代替服务端鉴权。WAVE-1 前端不提供任何薪资入口。

## 本机 MySQL

数据库控制脚本只允许管理：

- `shenzhou_hr_dev`
- `shenzhou_hr_test`

先把 [`deploy/mysql/wave1-local.env.example`](deploy/mysql/wave1-local.env.example) 复制到仓库外的绝对路径，填入本机凭据并设置为当前用户所有的 `0600` 普通文件。凭据值不得写入仓库、命令参数、日志或报告。

默认命令只显示计划，不连接数据库：

```bash
bash deploy/mysql/wave1-local-mysql.sh
```

只读探测：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  probe
```

执行建库、最小权限账号、V1 到最新迁移、V1/V2 升级、validate、重复 migrate no-op 与负向权限验证：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  --execute all
```

脚本不会删除、清空或重建 `shenzhou_hr_dev`；只允许在破坏性预检通过后重建 `shenzhou_hr_test`。完整边界和最终测试库状态说明见 [`deploy/mysql/README.md`](deploy/mysql/README.md)。

## 后端运行

仓库中的 [`.env.example`](.env.example) 只声明变量名和无密码模板。通过终端、IDE 或本机进程管理器注入应用与 Flyway 环境变量，且不得回显值：

- Flyway 使用 `shenzhou_hr_local_migrator`
- Spring Boot 使用 `shenzhou_hr_dev_app`
- Spring Boot 日常运行禁止使用 `root`
- 数据库会话时区为 UTC，业务时区为 `Asia/Shanghai`

```bash
cd backend
export JAVA_HOME=/path/to/openjdk-21
./mvnw spring-boot:run
```

开发合成管理员仅在 `dev` profile 且显式设置 `SHENZHOUHR_BOOTSTRAP_ENABLED=true` 时引导。用户名由 `SHENZHOUHR_BOOTSTRAP_ADMIN_USERNAME` 提供，初始密码只通过运行环境中的 `SHENZHOUHR_BOOTSTRAP_ADMIN_PASSWORD` 注入；不会进入迁移、源码或日志。引导完成后关闭该开关。默认合成用户名为 `synthetic.local.admin`，不得与真实员工账号混用。

后端监听 `http://127.0.0.1:8080`，健康检查为 `http://127.0.0.1:8080/actuator/health`。

## 前端运行

普通开发模式通过 Vite 把 `/api` 代理到 `127.0.0.1:8080`，使用真实 Spring Boot 与 MySQL 数据流：

```bash
cd frontend
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。

演示模式只使用明确标记的合成前端数据，不发出后端请求，也不写 MySQL：

```bash
cd frontend
npm run demo
```

## 质量门

```bash
cd backend
./mvnw test

cd ../frontend
npm run lint
npm run test
npm run build
npm run build:demo
npm run check
```

H2 仅用于快速自动化测试，不能替代本机 MySQL 迁移、权限和真实浏览器联调验收。OpenAPI 合同位于 [`api/openapi.yaml`](api/openapi.yaml)，正式迁移只位于 `backend/src/main/resources/db/migration`。

## 目录

- `api/openapi.yaml`：V1.9 WAVE-1 API 契约
- `backend`：Spring Boot 模块化单体、MyBatis 与 Flyway
- `frontend`：产品型 React SPA
- `docs/docs-confirm-v1.9`：已批准的 V1.9 规格与 Token 合同
- `design/open-design/v1.9`：确认的视觉、布局、状态与响应式参考
- `deploy/mysql`：本机 MySQL 安全控制与验证
- `docs/decisions/OPEN-DECISIONS.md`：追加式待决策和外部条件登记

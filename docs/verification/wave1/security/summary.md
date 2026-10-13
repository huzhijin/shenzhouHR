# WAVE-1 安全审计摘要

- 审计日期：2026-07-24
- 审计范围：`backend/src/main`、`frontend/src`、`api/openapi.yaml`、`deploy/mysql`、前后端依赖清单与无密码配置模板
- 结论边界：已执行的源码、秘密、配置、前端依赖和官方公告检查未发现未处置问题；Trivy vulnerability 数据库首次同步未完成，因此不能据此声称 Java/Node 全依赖零漏洞。

## 已执行证据

| 检查 | 结果 | 覆盖 |
| --- | --- | --- |
| Semgrep 1.156.0 `--config auto` | PASS，158 targets、357 rules、0 findings | Java、TypeScript、YAML、Bash、JSON |
| Gitleaks targeted directory scan | PASS，0 leaks | `backend/src`、`frontend/src`、`api`、`deploy`、`README.md`、`.env.example` |
| Trivy 0.69.3 secret/misconfiguration | PASS，0 secrets、0 misconfigurations | `backend`、`frontend`、`api`、`deploy`，排除生成目录 |
| `npm audit --json` | PASS，0 vulnerabilities | 298 dependencies：75 prod、224 dev、33 optional、11 peer |
| Maven dependency tree | PASS，依赖可解析 | Spring Boot 4.1.0、Spring Security 7.1.0、Flyway 12.4.0、MyBatis starter 4.0.0、MySQL Connector/J 9.5.0 |
| Redocly CLI 2.40.0 | PASS，0 warnings | `api/openapi.yaml` |
| 后端自动化 | PASS，53/53 | 认证、CSRF、账号、权限、审计、规则、OpenAPI、bootstrap、架构和 MyBatis |
| 前端自动化 | PASS，98/98 | 路由、API/CSRF、能力、demo 隔离、状态、响应式源码合同 |

Semgrep 首轮报告 `deploy/mysql/wave1-local-mysql.sh` 的全局 `IFS` 低置信度警告；已移除全局状态，最终复扫为 0 findings。最终扫描没有 taint fixpoint timeout。

Gitleaks 的全仓尝试因历史 `.umadev/checkpoints.git` 大对象耗时而中止；随后对本轮允许修改范围和所有凭据相关模板执行了精确扫描，均无发现。历史 UmaDev 检查点不属于本轮源码安全结论。

## 依赖公告核对

- Spring Boot 4.1.0 是 2026-06 发布版并包含 4.0.7 的安全修复；解析出的 Spring Security 为 7.1.0，对应 2026-06 安全修复版本：[Spring Boot 4.1.0](https://spring.io/blog/2026/06/10/spring-boot-4/)、[Spring Security 2026.06](https://spring.io/blog/2026/06/09/spring-security-releases-2026-06/)。
- React Server Components 的 CVE-2025-55182 影响 `react-server-dom-*` 19.2.0；本项目为客户端 SPA，使用 React 19.2.7，依赖树不含这些 RSC 包：[React 官方公告](https://github.com/facebook/react/security/advisories/GHSA-fv66-9v8q-g76r)。
- Vite CVE-2026-39363 影响 8.0.0–8.0.4；本项目为 8.1.5，且开发服务只绑定本机：[Vite 官方公告](https://github.com/vitejs/vite/security/advisories/GHSA-p9ff-h696-f583)。

## 人工安全复核

- 密码使用 BCrypt cost 12；数据库只存哈希。
- session/reset token 使用 256-bit 随机值，数据库只存 SHA-256 摘要。
- session 仅通过 HttpOnly、SameSite Cookie 传递；前端不把敏感值写入 URL、LocalStorage 或 SessionStorage。
- 真实 session Cookie 写请求使用从 session token 派生的 CSRF token；未认证写请求进入 401 认证入口。
- capability 和 data scope 在服务端执行；缺 capability 返回 403，对象不存在和越范围保持不可区分的 404。
- 安全与规则操作写入 request ID、correlation ID、操作人、时间、结果和追加式审计。
- dev 身份能力仅在明确 profile/开关下存在，不能覆盖有效真实会话；非 dev 默认拒绝。
- MySQL 控制脚本使用精确 host/port/schema allowlist、仓库外 `0600` 凭据文件、临时私有 client config 和应用账号负向权限验证。

## NOT_RUN / NOT_VERIFIED

- `Trivy vulnerability`：NOT_RUN。默认 OCI 漏洞库首次同步未在执行窗口完成；已登记 `OPEN-DECISIONS`。
- `OWASP Dependency-Check`：NOT_RUN。`NVD_API_KEY` 状态为 missing，首次 NVD 数据同步不具备稳定执行条件。
- `shellcheck`：NOT_RUN。当前环境未安装；Bash 已通过 `bash -n`、Semgrep 和无连接 dry-run。
- 真实 MySQL、运行态 API、浏览器 Cookie/网络/控制台：尚待本机 MySQL 外部凭据注入后验证，不能由 H2 或静态扫描替代。

## 当前风险判断

在已执行范围内没有检测到 critical/high/medium/low finding。该结果是有范围的工程证据，不是“系统绝对安全”的保证；真实 MySQL 与浏览器运行态门未完成前，WAVE-1 仍不能声明完成。

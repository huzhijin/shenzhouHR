# Ubuntu 原生部署与 W9 发布模板

如果客户使用宝塔面板，请直接使用 [deploy/baota/README.md](baota/README.md) 及其一键发布脚本；本页仍保留给 Ubuntu 原生发布流程。

目标环境为 Ubuntu Server 24.04 LTS，不使用 Docker、Kubernetes、Redis 或消息队列。

本目录是发布合同，不是已完成的生产部署。当前 W9 worktree 禁止连接生产、安装服务或切流；`scripts/release/native_preflight.py` 默认只做静态和 artifact 计划检查，真实 Ubuntu 命令保持 `NOT_VERIFIED`。

## 文件布局

- `/opt/shenzhouhr/web`：前端 `dist` 内容，只读
- `/opt/shenzhouhr/app/shenzhou-hr.jar`：后端可执行 JAR，只读
- `/opt/shenzhouhr/db/migration`：与发布版本一致的 Flyway SQL，只允许发布流程读取
- `/etc/shenzhouhr/shenzhouhr.env`：root 所有、权限 `0600`，仅保存环境变量
- `/etc/shenzhouhr/tls`：企业批准的证书与私钥
- `/var/lib/shenzhouhr`：应用运行时受控文件目录

Nginx 文件分为：

- `deploy/nginx/shenzhouhr-http.conf`：复制到 `/etc/nginx/conf.d/`，定义无 query string 的安全日志格式与限流 zone；
- `deploy/nginx/shenzhouhr.conf`：复制到站点目录，定义 TLS server、API 反向代理、CSP nonce、no-store 与静态哈希资产缓存。

`shenzhouhr.env` 至少提供 `SHENZHOUHR_DB_URL`、`SHENZHOUHR_DB_USERNAME`、`SHENZHOUHR_DB_PASSWORD`、`SHENZHOUHR_FLYWAY_ENABLED=false`、`SPRING_PROFILES_ACTIVE=prod`。真实值不得写入仓库、安装脚本、命令历史或普通日志。

## 安装边界

1. 使用 Ubuntu 官方 `openjdk-21-jre-headless`，MySQL 使用已批准的 8.4 LTS Community 仓库。
2. 创建无登录 shell 的 `shenzhouhr` 系统账号；应用账号无 MySQL DDL 权限。发布流程使用独立临时迁移账号，先执行与仓库公司切换合同一致的精确 V10 preflight，再分段执行 Flyway `validate → target=11 migrate → validate`；禁止直接从旧版本无门禁迁移到 V11。成功后撤销该账号，再以 `SHENZHOUHR_FLYWAY_ENABLED=false` 启动应用。
3. 将模板复制到系统目录前，由基础设施负责人替换内部域名与企业证书，并验证 CSP、出站白名单和反向代理可信地址。
4. 执行 `nginx -t`、`systemd-analyze verify`、Flyway `validate/migrate`、健康检查和回滚演练后才能切流。
5. `dev` profile 和 `X-Development-Principal` 头严禁出现在预发布或生产。

## 只读预检

在仓库中运行只读计划，不连接网络、不修改系统：

```bash
python3 scripts/release/native_preflight.py
```

如需校验候选 artifact，先由构建流水线生成只含相对路径与 SHA-256 的 manifest：

```bash
python3 scripts/release/native_preflight.py \
  --artifact-root /absolute/immutable/release \
  --artifact-manifest /absolute/immutable/release/artifacts.json
```

环境文件检查只读取变量名，不输出值。它只适用于当前用户持有的非生产检查文件；真实 `/etc/shenzhouhr/shenzhouhr.env` 由基础设施负责人在 Ubuntu 主机上检查。

## 迁移、切流与回滚

1. 对不可变候选执行 artifact SHA、`nginx -t`、`systemd-analyze verify`。
2. 使用临时迁移账号先迁移到精确 V10，执行 29 张边界表/29 个公司列、28 张依赖表/29 条关系边、范围 CHECK、索引、孤儿记录和在途事务 preflight；通过后再执行 `target=11 migrate → validate`。随后撤销账号；应用始终以 `SHENZHOUHR_FLYWAY_ENABLED=false` 启动。
3. 以 loopback actuator 确认健康，并完成权限/缓存/开发入口 smoke 后才切流。
4. 回滚只把应用和前端不可变版本指针切回上一候选；数据库禁止 `clean`、逆向 SQL 或删除业务数据。
5. 如果迁移后 schema 与上一应用不兼容，必须在切流前停止发布，改用新的前向修复迁移。

上述原生命令、真实健康检查和切流/回滚在当前 worktree 均为 `NOT_VERIFIED`。

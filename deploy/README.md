# Ubuntu 原生部署模板

目标环境为 Ubuntu Server 24.04 LTS，不使用 Docker、Kubernetes、Redis 或消息队列。

## 文件布局

- `/opt/shenzhouhr/web`：前端 `dist` 内容，只读
- `/opt/shenzhouhr/app/shenzhou-hr.jar`：后端可执行 JAR，只读
- `/opt/shenzhouhr/db/migration`：与发布版本一致的 Flyway SQL，只允许发布流程读取
- `/etc/shenzhouhr/shenzhouhr.env`：root 所有、权限 `0600`，仅保存环境变量
- `/etc/shenzhouhr/tls`：企业批准的证书与私钥
- `/var/lib/shenzhouhr`：应用运行时受控文件目录

`shenzhouhr.env` 至少提供 `SHENZHOUHR_DB_URL`、`SHENZHOUHR_DB_USERNAME`、`SHENZHOUHR_DB_PASSWORD`、`SHENZHOUHR_FLYWAY_ENABLED=false`、`SPRING_PROFILES_ACTIVE=prod`。真实值不得写入仓库、安装脚本、命令历史或普通日志。

## 安装边界

1. 使用 Ubuntu 官方 `openjdk-21-jre-headless`，MySQL 使用已批准的 8.4 LTS Community 仓库。
2. 创建无登录 shell 的 `shenzhouhr` 系统账号；应用账号无 MySQL DDL 权限。发布流程使用独立临时迁移账号对 `/opt/shenzhouhr/db/migration` 执行 Flyway `validate/migrate`，成功后撤销该账号，再以 `SHENZHOUHR_FLYWAY_ENABLED=false` 启动应用。
3. 将模板复制到系统目录前，由基础设施负责人替换内部域名与企业证书，并验证 CSP、出站白名单和反向代理可信地址。
4. 执行 `nginx -t`、`systemd-analyze verify`、Flyway `validate/migrate`、健康检查和回滚演练后才能切流。
5. `dev` profile 和 `X-Development-Principal` 头严禁出现在预发布或生产。

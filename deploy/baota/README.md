# 神州 HR 宝塔傻瓜式部署包

这个目录提供给交付人员使用。开发机只负责生成一个压缩包，客户服务器只需要解压并执行一次 `install.sh`。

## 一、开发机生成交付包

在项目根目录执行：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

脚本会自动完成：

- 前端 `npm ci` 和生产构建；
- 后端 Java 21 fat jar 构建；
- 打包 V1 到 V11 数据库迁移；
- 生成 SHA-256 校验清单；
- 输出 `shenzhouhr-release-*.tar.gz`。

把生成的压缩包上传给客户，或者上传到客户服务器后解压。

## 二、客户宝塔面板准备

在宝塔面板安装：

1. Nginx；
2. MySQL 8.0.45；
3. JDK 21；
4. 对外网访问申请并启用 HTTPS 证书；纯内网使用可以不申请证书。

在“网站”中先添加客户域名，网站根目录使用 `/www/wwwroot/客户域名`，不需要创建数据库，PHP 版本选择纯静态/无 PHP 即可。外网模式进入该站点的“SSL”页面申请或粘贴证书并启用 HTTPS；内网模式可以跳过 SSL。安装脚本会自动复用宝塔为该域名生成的站点配置，不需要再创建宝塔 Java 项目；后端由本包的 systemd 服务管理。

只开放 80/443。后端 8080 只监听本机，MySQL 3306 不要开放给公网。

如果服务器的 Java 21 不是 `/usr/bin/java`，安装时可以这样指定：

```bash
JAVA_BIN=/www/server/java/jdk-21/bin/java sudo -E bash install.sh
```

## 三、客户只需执行一次

进入解压后的发布目录：

```bash
sudo bash install.sh
```

安装脚本会依次询问：

1. 客户域名；
2. MySQL 地址、端口、数据库名；
3. MySQL root 账号和密码；
4. 是否创建第一个系统管理员；
5. 管理员账号、公司信息和初始密码。

脚本会自动：

- 创建数据库 `shenzhou_hr`；
- 创建 `shenzhouhr_app` 应用账号，只授予业务 CRUD 权限；
- 创建 `shenzhouhr_migrator` 迁移账号，只在安装/升级时使用；
- 把账号密码写入 root 可读的 `/etc/shenzhouhr/`；
- 生成 32 字节的账号批量开通恢复密钥，以 43 位无填充 base64url 保存，并把同一值写入应用和迁移 env；
- 执行 Flyway 数据库迁移；
- 部署 systemd 后端服务；
- 部署宝塔 Nginx 站点并反向代理 `/api/`；
- 启动服务、检查健康状态并打印访问地址。

管理员初始密码、数据库密码和账号开通恢复密钥都不会被打印到日志中。首次登录后必须修改管理员密码。

安装时选择 `Enable HTTPS for this site? [y/N]`：内部系统输入回车或 `N`，访问地址是 `http://客户域名`；对外服务输入 `Y`，并确保宝塔证书已经配置。内网 HTTP 模式会自动关闭 Secure Cookie，保证登录正常，但不要把 80 端口暴露到公网。

## 四、后续版本升级

以后有新代码时，不要手工覆盖客户服务器目录，也不要再次执行首次安装脚本。重新生成新的发布包：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

把新压缩包上传并解压后执行：

```bash
sudo bash upgrade.sh
```

升级脚本会保留客户的数据库、数据库账号、`/etc/shenzhouhr/` 环境变量和 HTTPS 配置，只备份并替换后端 JAR、前端文件，并执行新版本 Flyway 迁移。备份保存在 `/opt/shenzhouhr/backups/upgrade-<时间>/`。

旧版本首次升级时，脚本会在停止服务和运行 Flyway 之前检查应用 env 与迁移 env：

- 两边都没有账号开通配置时，只生成一次；脚本会先完整生成两个临时文件，再分别原子替换，第二次替换失败时回滚第一次；
- 一边已有有效值、另一边缺失时，复制现有值，不会轮换；
- 两边值不一致、值格式错误或恢复窗口超过 24 小时时，立即停止升级且不输出密钥；
- 重复执行升级不会重新生成密钥。

不要手工修改 `SHENZHOUHR_PROVISIONING_PEPPER`。确需轮换时，要同时更新两个 env、递增 `SHENZHOUHR_PROVISIONING_KEY_ID`，并让尚未完成的批次改走管理员密码重置。

如果升级涉及数据库结构，必须新增 `V12__...sql`、`V13__...sql` 这样的前向迁移文件；不能修改已经执行过的旧迁移，也不能使用 `flyway clean`。数据库迁移失败时不会自动回滚数据库，需要根据日志处理。

## 五、数据库约束

本交付包按客户的 MySQL **8.0.45** 制作。数据库必须是全新库，或者已经处于本发布包可以继续迁移的状态。不要直接指向开发机上来源不明的旧库；如果历史库的 `flyway_schema_history` 版本高于当前发布包的 V11，先备份并单独评估升级路径。

安装脚本会严格校验 MySQL 核心版本，不是 8.0.45 就停止，不会继续写库。数据库默认使用 `utf8mb4` 和 `utf8mb4_0900_ai_ci`。

升级前先做完整备份。这个项目没有自动回滚业务数据的反向迁移，禁止使用 `flyway clean`。

## 六、日常运维命令

```bash
# 查看状态
systemctl status shenzhouhr

# 重启后端
systemctl restart shenzhouhr

# 查看后端日志
journalctl -u shenzhouhr -f

# 检查 Nginx 配置并重载
/www/server/nginx/sbin/nginx -t
systemctl reload nginx

# 检查后端健康
curl http://127.0.0.1:8080/actuator/health

# 同时核对健康、数据库和两个 env 中的账号开通恢复配置（不会输出密钥）
sudo bash deploy/baota/scripts/verify.sh \
  --env-file /etc/shenzhouhr/shenzhouhr.env \
  --migrator-env-file /etc/shenzhouhr/shenzhouhr-migrator.env
```

## 七、没有 SSH 时

在宝塔“终端”中执行同样的命令即可。不要把数据库密码写进 Nginx 配置、前端代码、Git 仓库或聊天截图。

## 八、手动恢复

安装前如果服务器已经存在同名应用，脚本会在 `/opt/shenzhouhr/backups/<时间>/` 保存旧 jar、旧前端和旧站点配置。恢复前先停止服务，再从对应备份目录恢复文件，最后执行 `systemctl daemon-reload && systemctl restart shenzhouhr`。

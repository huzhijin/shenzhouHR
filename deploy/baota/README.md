# 神州 HR 宝塔傻瓜式部署包

这个目录提供给交付人员使用。开发机只负责生成一个压缩包，客户服务器只需要解压并执行一次 `install.sh`。

## 一、开发机生成交付包

在项目根目录执行：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

正式客户包只能从已经提交且工作树干净的源码生成；脚本默认会拒绝 `README.md`、
`api`、`backend`、`frontend`、`deploy`、`docs` 或 `scripts` 下存在未提交内容的工作树，
确保 `BUILD-MANIFEST.txt` 的 `source_commit` 能唯一对应源码。只有本地排错、且生成物绝不交付
客户时，才可显式设置 `ALLOW_DIRTY_RELEASE=true`；该包会在清单中标记
`source_tree_state=modified`。

脚本会自动完成：

- 前端 `npm ci` 后执行 lint、自动化测试和生产构建；
- 后端 Java 21 完整测试和 fat jar 构建；
- 自动检查迁移版本连续，并打包当前完整的 V1 到 V31 数据库迁移；
- 仅以普通 Markdown 文件打包同版本客户使用说明和部署就绪度记录，不携带文档目录中的临时文件、软链接或其他格式附件；
- 生成 SHA-256 校验清单；
- 输出 `shenzhouhr-release-*.tar.gz`。

把生成的压缩包和同名 `.tar.gz.sha256` 上传给客户。校验文件的期望值还应通过
独立的可信渠道交付；不要只依赖与压缩包放在同一位置、但未经确认的校验文件。

在客户服务器上先核对压缩包，再解压并核对包内所有文件。下面的文件名必须替换为
本次实际收到的精确文件名，不要用可能同时匹配多个历史包的通配符：

```bash
sha256sum -c shenzhouhr-release-20260806000000.tar.gz.sha256
tar -xzf shenzhouhr-release-20260806000000.tar.gz
cd shenzhouhr-release-20260806000000
sha256sum -c SHA256SUMS
```

任一校验失败都必须停止，不能继续执行 root 权限的安装或升级脚本。`install.sh` 和
`upgrade.sh` 也会在加载包内辅助脚本、写数据库或替换文件前自动复核 `SHA256SUMS`。

## 二、客户宝塔面板准备

在宝塔面板安装：

1. Nginx；
2. MySQL 8.0.45；
3. JDK 21；
4. 对外网访问申请并启用 HTTPS 证书；纯内网使用可以不申请证书。

在“网站”中先添加站点。纯内网不需要域名，可以直接把服务器内网 IP（例如 `192.168.1.20`）作为站点名称，网站根目录使用 `/www/wwwroot/内网IP`；不需要在宝塔创建数据库，PHP 版本选择纯静态/无 PHP 即可。外网模式进入该站点的“SSL”页面申请或粘贴证书并启用 HTTPS；内网模式可以跳过 SSL。安装脚本会自动复用宝塔为该域名或 IP 生成的站点配置，不需要再创建宝塔 Java 项目；后端由本包的 systemd 服务管理。

安装和升级只接受精确的 `/www/wwwroot/域名或IP` 作为前端目录，并拒绝 `/www`、
`/www/wwwroot`、符号链接或外部传入的其他目录。不要用软链接把站点指向其他路径。

只开放 80/443。后端默认使用 `127.0.0.1:18080`，不要在防火墙开放 18080；MySQL 3306 也不要开放给公网。本交付方案要求应用与 MySQL 安装在同一台宝塔服务器，MySQL 地址使用默认的 `127.0.0.1`。安装器会在写数据库或配置文件前检查后端端口是否空闲；如确需改用其他空闲端口，可在首次安装命令前设置 `BACKEND_PORT`。

如果服务器的 Java 21 不是 `/usr/bin/java`，安装时可以这样指定：

```bash
JAVA_BIN=/www/server/java/jdk-21.0.2/bin/java \
JAR_BIN=/www/server/java/jdk-21.0.2/bin/jar \
MYSQL_BIN=/www/server/mysql/bin/mysql \
BACKEND_PORT=18080 \
sudo -E bash install.sh
```

## 三、客户只需执行一次

进入已经完成上述两层 SHA-256 校验的发布目录：

```bash
sudo bash install.sh
```

安装脚本会依次询问：

1. 客户域名，或纯内网服务器的固定 IP；
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

特别注意：代码部署包只包含数据库结构/系统目录、程序和生产前端，不包含开发机或浏览器演示中的组织、员工、任职、考勤配置和报表样例。首次安装创建的业务数据只有操作时填写的公司和管理员。经客户批准的期初组织、员工、任职必须使用单独的客户专用数据包，按“组织 → 员工 → 任职”顺序逐批预检并发布；不得把开发库整库备份、测试账号或演示前端直接复制到生产库。

管理员初始密码、数据库密码和账号开通恢复密钥都不会被打印到日志中。首次登录后必须修改管理员密码。

安装时选择 `Enable HTTPS for this site? [y/N]`：内部系统输入回车或 `N`，访问地址是 `http://客户域名`；对外服务输入 `Y`，并确保宝塔证书已经配置。内网 HTTP 模式会自动关闭 Secure Cookie，保证登录正常，但不要把 80 端口暴露到公网。

如果首次安装中途出现 `ERROR`，先不要再次执行 `install.sh`，也不要删除
`/etc/shenzhouhr/` 或数据库。保留宝塔终端中的完整报错，并执行
`systemctl status shenzhouhr --no-pager`、`journalctl -u shenzhouhr -n 100 --no-pager`
和 `/www/server/nginx/sbin/nginx -t` 收集诊断信息。安装器会拒绝覆盖已经生成的数据库凭据，
避免重复执行时意外轮换密码；应根据首次失败位置恢复或继续，而不是强行重装。

## 四、后续版本升级

以后有新代码时，不要手工覆盖客户服务器目录，也不要再次执行首次安装脚本。重新生成新的发布包：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

生成前先提交本次发布范围并确认上述目录工作树干净；不要使用
`ALLOW_DIRTY_RELEASE=true` 生成升级交付包。

把新压缩包和同名 `.sha256` 上传后，按第一节的顺序先校验外层压缩包、解压、再校验
包内 `SHA256SUMS`，然后执行：

```bash
sudo bash upgrade.sh
```

升级脚本会先检查目标数据库必须正好是 MySQL 8.0.45，且 Flyway 历史是无失败、无非法版本、从 V1 连续到当前版本并且不高于发布包版本。该预检通过后才允许停服、替换后端 JAR 和执行新迁移。脚本会保留客户的数据库、数据库账号、`/etc/shenzhouhr/` 环境变量和 HTTPS 配置，只备份并替换后端 JAR、前端文件，并执行新版本 Flyway 迁移。备份保存在 `/opt/shenzhouhr/backups/upgrade-<时间>/`。

旧版本首次升级时，脚本会在停止服务和运行 Flyway 之前检查应用 env 与迁移 env：

- 两边都没有账号开通配置时，只生成一次；脚本会先完整生成两个临时文件，再分别原子替换，第二次替换失败时回滚第一次；
- 一边已有有效值、另一边缺失时，复制现有值，不会轮换；
- 两边值不一致、值格式错误或恢复窗口超过 24 小时时，立即停止升级且不输出密钥；
- 重复执行升级不会重新生成密钥。

不要手工修改 `SHENZHOUHR_PROVISIONING_PEPPER`。确需轮换时，要同时更新两个 env、递增 `SHENZHOUHR_PROVISIONING_KEY_ID`，并让尚未完成的批次改走管理员密码重置。

如果升级涉及数据库结构，必须在当前最高版本之后新增前向迁移文件；不能修改已经执行过的旧迁移，也不能使用 `flyway clean`。数据库迁移失败时不会自动回滚数据库，需要根据日志处理。

## 五、数据库约束

本交付包按客户的 MySQL **8.0.45** 制作。数据库必须是全新库，或者已经处于本发布包可以继续迁移的状态。不要直接指向开发机上来源不明的旧库；如果历史库的 `flyway_schema_history` 版本高于 `BUILD-MANIFEST.txt` 中记录的最高迁移版本，先备份并单独评估升级路径。

安装脚本会严格校验 MySQL 核心版本，不是 8.0.45 就停止，不会继续写库。数据库默认使用 `utf8mb4` 和 `utf8mb4_0900_ai_ci`。

升级前先做完整备份。这个项目没有自动回滚业务数据的反向迁移，禁止使用 `flyway clean`。

## 六、得力、OA 和正式报表不是安装后自动启用

安装器会把 `DELI_EPLUS_ENABLED` 和 `OA_MYSQL_ENABLED` 保持为 `false`。这是安全默认值：得力仍需客户租户初始化确认、员工绑定、调度和真实样本验收；OA 仍需只读授权、六类表单映射签字、固定 SQL、同步任务和影子对账。不要因为健康检查通过就直接把两个开关改为 `true`。

完整变量名见 `/etc/shenzhouhr/shenzhouhr.env` 对应的发布包模板 `deploy/baota/env/shenzhouhr.env.example`。真实 AppSecret 和 OA 密码只写入服务器根权限环境文件或密钥管理器，不回传仓库。修改环境后应由变更负责人重启服务并验证日志中没有凭据。

即使外部来源已有原始记录，首页和九类报表仍需人员匹配、考勤配置、日计算、异常处理和正式投影发布。当前自动发布链尚未完整接通，详见同版本客户手册第 09～11 章。没有完成这些业务门禁时，只能判定技术环境可运行，不能判定考勤生产功能已上线。

## 七、日常运维命令

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
curl http://127.0.0.1:18080/actuator/health

# 同时核对健康、数据库和两个 env 中的账号开通恢复配置（不会输出密钥）
sudo bash deploy/baota/scripts/verify.sh \
  --env-file /etc/shenzhouhr/shenzhouhr.env \
  --migrator-env-file /etc/shenzhouhr/shenzhouhr-migrator.env
```

## 八、没有 SSH 时

在宝塔“终端”中执行同样的命令即可。不要把数据库密码写进 Nginx 配置、前端代码、Git 仓库或聊天截图。

## 九、手动恢复

安装前如果服务器已经存在同名应用，脚本会在 `/opt/shenzhouhr/backups/<时间>/` 保存旧 jar、旧前端和旧站点配置。恢复前先停止服务，再从对应备份目录恢复文件，最后执行 `systemctl daemon-reload && systemctl restart shenzhouhr`。

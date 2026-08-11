# 神州 HR 宝塔面板交付包

本目录面向当前客户宝塔部署。正式拓扑固定为：

- 服务器内网 IP：`192.168.160.226`；
- Nginx/前端：监听 `23272`，网络映射为外部 `23272` → 内部 `23272`；
- Java/Spring Boot：监听 `0.0.0.0:8080`，网络映射为外部 `23273` → 内部 `8080`；
- MySQL：仅本机 `127.0.0.1:3306`，不对外开放；
- 本项目不监听、不映射 `80` 端口。

Java 必须登记到宝塔「Java 项目」并由宝塔唯一管理；本客户包会拒绝 systemd 模式。
宝塔面板必须至少为本交付已验证的 **11.8**，并在 Java 项目页支持从环境文件加载变量；
版本不足时要先另行备份、审批和升级验证，不能在正式停机窗口临时升级。
旧数据库清理时可在面板 11.4+ 使用「读取本地数据库 → 从服务器同步到面板」接管；
全新安装则必须先在宝塔创建一个**空的** `shenzhou_hr`。不要再用 systemd 服务
冒充宝塔 Java 项目，也不要让 systemd 和宝塔同时启动同一个 JAR。

完整的小白操作步骤、旧半部署清理、面板表单填写、Nginx 全量配置、验收和升级见：

```text
docs/deployment/baota-deployment-guide.md
```

发布包中也会包含同一路径的手册。没有逐项完成手册中的备份和旧部署确认前，不要删除
`/etc/shenzhouhr`、`/opt/shenzhouhr` 或 `shenzhou_hr` 数据库。

## 一、开发机生成交付包

在项目根目录执行：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

正式客户包只能从已经提交且工作树干净的源码生成。脚本会自动完成前端检查和构建、
Java 21 后端测试和打包、Flyway 迁移连续性检查、客户文档打包和 SHA-256 清单生成。
只有本地排错且产物绝不交付客户时，才允许设置 `ALLOW_DIRTY_RELEASE=true`。

不要使用仓库中旧的 `release/shenzhouhr2026080702`：它只包含 V1～V31、后端默认
`18080`，并且是旧 systemd/80 端口方案。迁移版本会随修复继续增加，正式包必须从当前
已提交源码重新生成，并以包内 `BUILD-MANIFEST.txt` 为准。

## 二、客户服务器校验发布包

文件名必须替换为本次收到的精确文件名，不要使用 `*` 通配历史包：

```bash
shr_prepare_release() {
  local name=shenzhouhr-release-20260810000000
  cd /opt/shenzhouhr-releases || return 1
  sha256sum -c "$name.tar.gz.sha256" || return 1
  [ ! -e "$name" ] && [ ! -L "$name" ] || return 1
  tar --no-same-owner -xzf "$name.tar.gz" || return 1
  [[ "$name" =~ ^shenzhouhr-release-[A-Za-z0-9._-]+$ \
    && "$name" != *..* ]] || return 1
  test -d "$name" && test ! -L "$name" || return 1
  test -z "$(find "$name" -type l -print -quit)" || return 1
  chown -R root:root "$name" || return 1
  chmod -R go-w "$name" || return 1
  cd "$name" || return 1
  sha256sum -c SHA256SUMS || return 1
  echo 'PASS：发布包两层校验和权限检查均通过'
}
shr_prepare_release || echo 'STOP：发布包准备失败，不得继续安装或升级'
unset -f shr_prepare_release
```

任一校验失败都必须停止。外层 `.sha256` 的正确值应通过独立可信渠道确认。

## 三、首次安装只用宝塔托管模式

在已经备份并清理旧半部署、通过宝塔创建数据库和 `192.168.160.226:23272` 纯静态
站点后，还必须在该站点「设置 → 反向代理」创建唯一规则 `kaoqin-api`：高级目录 `/api`、
目标 `http://127.0.0.1:8080/api`、发送域名 `$host`、缓存关闭、内容替换为空。不要创建
顶层独立反向代理项目。规则在面板可见后，按完整手册执行：

先用 `id -u` 确认当前是 root（输出 `0`），再运行：

```bash
PROCESS_MANAGER=baota \
BACKEND_ADDRESS=0.0.0.0 \
BACKEND_PORT=8080 \
SITE_PORT=23272 \
JAVA_BIN=/www/server/java/实际JDK21目录/bin/java \
JAR_BIN=/www/server/java/实际JDK21目录/bin/jar \
MYSQL_BIN=/www/server/mysql/bin/mysql \
bash install.sh
```

此模式只负责发布包校验、数据库最小权限账号、Flyway、首个管理员、JAR/前端文件和
Nginx 站点配置；它不会创建或启动 `shenzhouhr.service`。脚本完成后，必须按手册把
`/opt/shenzhouhr/app/shenzhou-hr.jar` 添加到宝塔「Java 项目」，环境文件选择
`/etc/shenzhouhr/shenzhouhr.env`，再从面板启动。

安装脚本会创建：

- `shenzhouhr_app`：应用运行账号，仅业务 CRUD；
- `shenzhouhr_migrator`：仅安装/升级时执行结构迁移；
- `/etc/shenzhouhr/shenzhouhr.env`：应用环境，`root:shenzhouhr`、`0640`；
- `/etc/shenzhouhr/shenzhouhr-migrator.env`：迁移环境，`root:root`、`0600`；
- `/opt/shenzhouhr/app/shenzhou-hr.jar`：宝塔 Java 项目使用的 JAR；
- `/www/wwwroot/192.168.160.226`：前端静态文件；
- 宝塔站点 Nginx 配置：监听 `23272`；站点内可见规则 `kaoqin-api` 把 `/api/` 代理到
  `127.0.0.1:8080/api/`。

真实数据库密码和账号开通恢复密钥不会打印到日志，不要把环境文件发到聊天、截图或
Git 仓库。

## 四、后续升级

先在宝塔备份数据库和文件，并从宝塔停止 Java 项目 `kaoqinweb`，再进入已完成两层校验的
新发布目录执行：

```bash
PROCESS_MANAGER=baota \
JAVA_BIN=/www/server/java/实际JDK21目录/bin/java \
MYSQL_BIN=/www/server/mysql/bin/mysql \
bash upgrade.sh
```

脚本会拒绝在 `8080` 仍被监听时升级，保留数据库和环境文件，备份旧 JAR/前端，执行
前向迁移并替换文件。完成后回到宝塔 Java 项目列表启动 `kaoqinweb`，再执行健康和登录验收。
数据库迁移没有自动反向回滚，禁止 `flyway clean` 或修改已经执行过的旧迁移。

## 五、重要边界

- MySQL 必须与发布合同完全一致：当前为 **8.0.45**。宝塔源没有该精确版本时停止，
  不要自行换成“任意 8.0”、8.4 或 MariaDB。
- Java 必须是完整 **JDK 21**，同时存在 `java` 和 `jar`。
- Java 项目的「绑定域名／外网映射」保持为空；该功能会额外生成 Nginx 配置，可能重新
  占用 80 端口。
- 站点内只允许一条 `kaoqin-api` 目录代理；不要新增根 `/` 代理、第二条 `/api` 规则，
  也不要修改其目标、缓存或内容替换。
- 前端正常调用通过 Nginx 同源 `/api/`。客户要求的外部 `23273` → 内部 `8080` 会绕过
  Nginx；至少在路由或防火墙限制来源为批准的 IP/VPN，不要对所有公网地址开放。
- 当前 HTTP 口径只允许受控内网、VPN 或专线；如果 WAN1 可被公共互联网访问，必须先
  落实 TLS/安全网关，不能仅靠固定 IP 白名单保护登录凭据和员工数据。
- 3306 不开放；数据库访问权限选「本地服务器」。
- 本包不包含开发机或浏览器演示中的组织、员工、任职、考勤和报表样例。经客户批准的
  期初数据必须按“组织 → 员工 → 任职”顺序另行导入，不得复制开发库、测试账号或演示
  数据到生产。

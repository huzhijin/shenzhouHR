# 神州 HR 宝塔面板完整部署手册（小白版）

> 适用日期：2026-08-10  
> 适用服务器：`192.168.160.226`  
> 目标：先安全清理之前执行到一半的命令部署，再让客户在宝塔面板中直接管理
> Nginx 网站、Java 项目和 MySQL 数据库。

本手册是当前客户的唯一部署口径。以前文档中的 `192.168.1.20`、80 端口、
`18080` 后端端口和 systemd 托管方式全部作废。

---

## 0. 先看懂最终结果

完成后，宝塔面板里必须能看到四个对象：

| 宝塔位置 | 应看到的对象 | 谁负责启停/管理 |
|---|---|---|
| 软件商店 → 已安装 | Nginx、MySQL 8.0.45、完整 JDK 21 | 宝塔 |
| 网站 → PHP 项目/HTML 项目 | 站点名 `192.168.160.226`，备注 `kaoqin`；绑定 `192.168.160.226:23272` | 宝塔/Nginx |
| 网站 → Java 项目 | `kaoqinweb`，状态“运行中” | **宝塔 Java 项目** |
| 数据库 → MySQL | `shenzhou_hr` | 宝塔/phpMyAdmin |

端口必须严格按下表：

| 用途 | 外部端口 | 服务器内部监听 | 服务器程序 |
|---|---:|---:|---|
| 前端 `kaoqin` | 23272 | `*:23272` | Nginx |
| 后端 `kaoqinweb` | 23273 | `192.168.160.226:8080`（Java 实际绑定 `0.0.0.0:8080`） | Java/Spring Boot |

必须记住：

- Nginx 监听的是 **23272**，不是 80；
- Java 监听的是 **8080**，不是 23273；
- 23273 是路由器外部端口，服务器本机不监听 23273；
- Nginx 的 `/api/` 在服务器内部代理到 `127.0.0.1:8080`；
- Java 必须由宝塔「Java 项目」唯一管理，不能再保留 `shenzhouhr.service`；
- MySQL 3306 只允许本机访问，绝不开放到公网。

内部访问地址：

```text
前端：http://192.168.160.226:23272
后端健康：http://192.168.160.226:8080/actuator/health
```

外部访问地址中的 `<WAN地址>` 由客户网络管理员提供；以下 HTTP 地址只允许用于受控内网、
VPN 或专线：

```text
前端：http://<WAN地址>:23272
后端：http://<WAN地址>:23273
```

不要在内网浏览器里访问 `http://192.168.160.226:23273`；服务器没有监听这个端口。
若 WAN1 可从公共互联网到达，必须停止本手册的 HTTP 部署，改用经批准的 TLS 网关/证书
方案；固定 IP 白名单只能限制来源，不能防止登录密码和员工数据被明文窃听。

---

## 1. 操作前的红线

1. **先定位，后停机；停机后做最终备份；验证能恢复后才删除。** 不要一上来执行
   `rm -rf /etc/shenzhouhr`，里面保存着旧数据库和端口的定位信息。
2. 旧数据库名不一定是 `shenzhou_hr`。旧安装允许现场改名，也可能误指向已有共享库；
   任何删除都必须以旧环境文件里的实际库名和 DBA 核验结果为准。
3. “备份成功、文件非 0 KB”不代表能恢复。必须把最终备份恢复到临时库并核对后，
   才能删除旧库。
4. 不执行 `pkill java`、`pkill nginx`，不卸载整机的 Nginx、MySQL、JDK，不删除
   `/www/wwwroot` 整体；这些对象可能被其他项目共用。
5. 数据库密码、MySQL root 密码、HR 导出文件和 `/etc/shenzhouhr/*.env` 不得截图、
   发群或提交 Git。备份应加密保存、限制人员访问，并约定销毁日期。
6. 任一步出现“STOP/停止”、对象归属不清、权限不足或输出与手册不符，立即停下；
   不要为了继续而跳过检查。
7. 在删除任何旧对象前，先在宝塔首页确认面板版本至少为本交付已验证的 **11.8**，并确认
   Java 新建页有“从环境文件加载”字段。版本更低或字段不存在时，不要在停机窗口临时升级；
   先备份面板配置/数据库并取得客户变更授权，完成单独升级和回退验证后再开始本手册。

建议由两个人共同操作：一人执行，一人逐项复核并填写“旧部署清理记录”。

### 1.1 先进入 root 终端

登录宝塔 →「终端」，执行：

```bash
id -u
```

输出必须是 `0`。不是 `0` 时，由有权限的服务器管理员先执行 `sudo -i`；系统没有
`sudo` 或没有授权时就停止。**本手册后续终端命令均假定当前已经是 root，因此不再写
`sudo`。**

创建本次清理的唯一编号和小型配置备份目录。下面整段会先检查目录冲突和磁盘使用率；
任一文件系统已使用 80% 或以上就不会创建本次清理状态：

```bash
shr_begin_cleanup() {
  local tag dir
  tag="$(date +%Y%m%d-%H%M%S)"
  dir="/root/shenzhouhr-cleanup-$tag"
  if [ -e "$dir" ]; then
    echo "STOP：清理目录已存在：$dir"
    return 1
  fi
  if ! df -P / /opt /www | awk '
    NR > 1 {
      used=$5; gsub(/%/, "", used)
      if (used >= 80) bad=1
    }
    END { exit bad ? 1 : 0 }
  '; then
    echo 'STOP：/、/opt 或 /www 已使用 80% 以上，请先由管理员扩容或清理'
    return 1
  fi
  mkdir -m 0700 "$dir" || return 1
  export SHR_CLEAN_TAG="$tag"
  export SHR_CLEAN_DIR="$dir"
  {
    printf 'export SHR_CLEAN_TAG=%q\n' "$SHR_CLEAN_TAG"
    printf 'export SHR_CLEAN_DIR=%q\n' "$SHR_CLEAN_DIR"
  } > "$SHR_CLEAN_DIR/resume.env"
  chmod 0600 "$SHR_CLEAN_DIR/resume.env"
  printf 'PASS：本次清理编号：%s\n' "$SHR_CLEAN_TAG"
  df -h / /opt /www
  du -sh /etc/shenzhouhr /opt/shenzhouhr /var/lib/shenzhouhr \
    /www/wwwroot/* 2>/dev/null | sort -h | tail -30
}
shr_begin_cleanup
unset -f shr_begin_cleanup
```

必须看到 `PASS`。把编号抄到清理记录上，并尽量保持此终端不关闭。若终端断线，找到本次
**同一个**目录后只恢复非敏感变量，不要生成新编号混用：

```bash
source /root/shenzhouhr-cleanup-实际编号/resume.env
printf '%s\n' "$SHR_CLEAN_DIR"
```

输出必须是原清理目录。数据库备份还要在宝塔查看旧库大小；备份盘剩余空间至少应大于旧库
预计备份的 2 倍，否则停止并让管理员扩容。

---

# 第一部分：清理之前部署到一半的旧版本

## 2. 只读盘点，锁定精确对象

本节只查看，不停止、不删除。

### 2.1 服务、进程、目录和端口

逐条执行并在屏幕上核对。只把服务名、JAR 路径、端口、PID 等**脱敏结论**写入清理记录；
`systemctl cat` 或进程参数可能含明文密码，不截图、不粘贴到聊天或普通文档：

```bash
systemctl status shenzhouhr.service --no-pager || true
systemctl cat shenzhouhr.service || true
systemctl show -p FragmentPath --value shenzhouhr.service || true
systemctl show -p DropInPaths --value shenzhouhr.service || true
ss -lntp | grep -E ':(80|443|8080|18080|23272|23273)\b' || true
ps -ef | grep -E '[s]henzhou-hr\.jar|[j]ava.*shenzhou' || true
ls -ld /etc/shenzhouhr /opt/shenzhouhr /var/lib/shenzhouhr \
  /run/shenzhouhr 2>/dev/null || true
find /opt -maxdepth 1 -name 'shenzhouhr2026*' -print 2>/dev/null
getent passwd shenzhouhr || true
getent group shenzhouhr || true
id shenzhouhr 2>/dev/null || true
```

`systemctl cat` 中必须能看到神州 HR 的 JAR、环境文件或 `/opt/shenzhouhr`。如果内容属于
其他系统，立即停止。80、443 可能属于其他网站，只记录归属，不能停掉整个 Nginx。

### 2.2 从旧 env 只提取数据库位置，绝不输出密码

如果 `/etc/shenzhouhr/shenzhouhr.env` 存在，执行下面整段。它只按文本读取 URL，不会
`source` 环境文件，也不会显示用户名或密码：

```bash
shr_parse_old_db() {
  local line url endpoint hostport database
  [ -f /etc/shenzhouhr/shenzhouhr.env ] || {
    echo 'STOP：旧环境文件不存在'
    return 1
  }
  line="$(grep -m1 '^SHENZHOUHR_DB_URL=' \
    /etc/shenzhouhr/shenzhouhr.env)" || {
    echo 'STOP：旧环境文件没有数据库 URL'
    return 1
  }
  url="${line#*=}"
  url="${url#\'}"; url="${url%\'}"
  url="${url#\"}"; url="${url%\"}"
  case "$url" in
    jdbc:mysql://*) ;;
    *) echo 'STOP：旧数据库 URL 无法识别'; return 1 ;;
  esac
  endpoint="${url#jdbc:mysql://}"
  endpoint="${endpoint%%\?*}"
  case "$endpoint" in
    *@*) echo 'STOP：数据库 URL 含 userinfo，禁止解析或显示'; return 1 ;;
    */*) ;;
    *) echo 'STOP：数据库 URL 缺少库名'; return 1 ;;
  esac
  hostport="${endpoint%%/*}"
  database="${endpoint#*/}"
  case "$hostport" in
    ''|*[!A-Za-z0-9.:-]*) echo 'STOP：数据库主机/端口不安全'; return 1 ;;
  esac
  case "$database" in
    ''|*[!A-Za-z0-9_]*) echo 'STOP：旧数据库名不安全或无法识别'; return 1 ;;
  esac
  export SHR_OLD_DB_HOSTPORT="$hostport"
  export SHR_OLD_DB="$database"
  {
    printf 'export SHR_OLD_DB_HOSTPORT=%q\n' "$SHR_OLD_DB_HOSTPORT"
    printf 'export SHR_OLD_DB=%q\n' "$SHR_OLD_DB"
  } >> "$SHR_CLEAN_DIR/resume.env"
  chmod 0600 "$SHR_CLEAN_DIR/resume.env"
  printf 'PASS：旧数据库主机/端口：%s\nPASS：旧数据库名：%s\n' \
    "$SHR_OLD_DB_HOSTPORT" "$SHR_OLD_DB"
}
if ! shr_parse_old_db; then
  echo 'STOP：没有导出任何数据库变量，请交给 DBA 定位'
fi
unset -f shr_parse_old_db
```

把显示的主机/端口、实际库名写进清理记录。若 env 不存在、输出 `STOP`，或地址不是预期的
本机 MySQL，不允许猜成 `shenzhou_hr`，交给 DBA 根据旧服务启动参数和 MySQL 实例定位。

还要只提取旧部署实际使用过的数据库用户名，避免漏掉现场改过名的账号。下面不会读取或输出
密码：

```bash
shr_capture_old_db_usernames() {
  local file line value output="$SHR_CLEAN_DIR/old-db-usernames.txt"
  : > "$output" || return 1
  for file in \
    /etc/shenzhouhr/shenzhouhr.env \
    /etc/shenzhouhr/shenzhouhr-migrator.env
  do
    [ -e "$file" ] || continue
    if [ ! -f "$file" ] || [ -L "$file" ]; then
      echo "STOP：旧环境文件不是安全普通文件：$file"
      return 1
    fi
    while IFS= read -r line; do
      value="${line#*=}"
      value="${value#\'}"; value="${value%\'}"
      value="${value#\"}"; value="${value%\"}"
      case "$value" in
        ''|*[!A-Za-z0-9_]*)
          echo "STOP：$file 中的数据库用户名无法安全识别"
          return 1
          ;;
      esac
      printf '%s\n' "$value" >> "$output" || return 1
    done < <(grep -E '^(SHENZHOUHR_DB_USERNAME|SHENZHOUHR_FLYWAY_USERNAME)=' \
      "$file" || true)
  done
  sort -u -o "$output" "$output" || return 1
  chmod 0600 "$output" || return 1
  if [ ! -s "$output" ]; then
    echo 'STOP：没有识别到旧数据库用户名，请 DBA 从启动配置定位'
    return 1
  fi
  nl -ba "$output"
  echo 'PASS：旧数据库用户名清单已保存（不含密码）'
}
shr_capture_old_db_usernames
unset -f shr_capture_old_db_usernames
```

必须看到 `PASS`。清单里的每个用户名及其所有 Host 都要在第 6.2 节逐一检查，不能只处理
默认的 `shenzhouhr_app` 和 `shenzhouhr_migrator`。

### 2.3 找出全部候选 Nginx 配置

先锁定当前真正运行的 Nginx master，避免检查或重载了另一套 Nginx。执行整段：

```bash
shr_detect_running_nginx() {
  local pid candidate_real cmdline compiled_config owner mode
  local -a master_candidates=() site_pids=()
  mapfile -t master_candidates < <(
    ps -eo pid=,args= | awk '/[n]ginx: master process/ {print $1}'
  )
  for pid in "${master_candidates[@]}"; do
    candidate_real="$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)"
    [ "$(basename -- "$candidate_real")" = nginx ] || continue
    site_pids+=("$pid")
  done
  if [ "${#site_pids[@]}" -ne 1 ]; then
    echo "STOP：检测到 ${#site_pids[@]} 个站点 Nginx master，请服务器管理员处理"
    return 1
  fi
  export SHR_NGINX_MASTER_PID="${site_pids[0]}"
  export SHR_NGINX_BIN="$(readlink -f "/proc/$SHR_NGINX_MASTER_PID/exe")"
  cmdline="$(tr '\0' ' ' < "/proc/$SHR_NGINX_MASTER_PID/cmdline")"
  if [ ! -x "$SHR_NGINX_BIN" ]; then
    echo 'STOP：无法确定运行中的 Nginx 可执行文件'
    return 1
  fi
  export SHR_NGINX_CONFIG=""
  export SHR_NGINX_PREFIX=""
  if [[ " $cmdline " =~ [[:space:]]-c[[:space:]]+([^[:space:]]+) ]]; then
    SHR_NGINX_CONFIG="${BASH_REMATCH[1]}"
  fi
  if [[ " $cmdline " =~ [[:space:]]-p[[:space:]]+([^[:space:]]+) ]]; then
    SHR_NGINX_PREFIX="${BASH_REMATCH[1]}"
  fi
  if [ -z "$SHR_NGINX_CONFIG" ]; then
    compiled_config="$($SHR_NGINX_BIN -V 2>&1 \
      | sed -n 's/.*--conf-path=\([^ ]*\).*/\1/p')"
    SHR_NGINX_CONFIG="$compiled_config"
  fi
  case "$SHR_NGINX_CONFIG" in
    /*) ;;
    *) echo 'STOP：无法得到绝对 Nginx 配置路径'; return 1 ;;
  esac
  if [ ! -f "$SHR_NGINX_CONFIG" ] || [ -L "$SHR_NGINX_CONFIG" ]; then
    echo "STOP：Nginx 主配置不是安全普通文件：$SHR_NGINX_CONFIG"
    return 1
  fi
  owner="$(stat -c %u "$SHR_NGINX_CONFIG")"
  mode="$(stat -c %a "$SHR_NGINX_CONFIG")"
  if [ "$owner" != 0 ] || (( (8#$mode & 022) != 0 )); then
    echo "STOP：Nginx 主配置必须 root 所有且不可被组/其他用户写：$SHR_NGINX_CONFIG"
    return 1
  fi
  SHR_NGINX_ARGS=()
  if [ -n "$SHR_NGINX_PREFIX" ]; then
    SHR_NGINX_ARGS+=( -p "$SHR_NGINX_PREFIX" )
  fi
  SHR_NGINX_ARGS+=( -c "$SHR_NGINX_CONFIG" )
  export SHR_NGINX_MASTER_PID SHR_NGINX_BIN SHR_NGINX_CONFIG SHR_NGINX_PREFIX
  {
    printf 'export SHR_NGINX_MASTER_PID=%q\n' "$SHR_NGINX_MASTER_PID"
    printf 'export SHR_NGINX_BIN=%q\n' "$SHR_NGINX_BIN"
    printf 'export SHR_NGINX_CONFIG=%q\n' "$SHR_NGINX_CONFIG"
    printf 'export SHR_NGINX_PREFIX=%q\n' "$SHR_NGINX_PREFIX"
    declare -p SHR_NGINX_ARGS
  } >> "$SHR_CLEAN_DIR/resume.env"
  chmod 0600 "$SHR_CLEAN_DIR/resume.env"
  printf 'PASS：运行中的 Nginx：PID=%s BIN=%s CONFIG=%s\n' \
    "$SHR_NGINX_MASTER_PID" "$SHR_NGINX_BIN" "$SHR_NGINX_CONFIG"
}
shr_detect_running_nginx
unset -f shr_detect_running_nginx
```

必须看到一个 `PASS`。检测会排除宝塔面板自身的 `webserver`，只计算可执行文件名为
`nginx` 的站点 master，并沿用其 `-c/-p`；0 个、多个或配置权限不安全时交给服务器管理员，
绝不能自行 `pkill nginx`。随后搜索旧命令可能写入的所有目录：

```bash
for SHR_VHOST_DIR in \
  /www/server/panel/vhost/nginx \
  /www/server/nginx/conf/vhost \
  /etc/nginx/conf.d \
  /etc/nginx/sites-enabled \
  /etc/nginx/sites-available
do
  [ -d "$SHR_VHOST_DIR" ] || continue
  grep -RIlE 'shenzhouhr|/opt/shenzhouhr|proxy_pass[[:space:]]+http://127\.0\.0\.1:(8080|18080)' \
    "$SHR_VHOST_DIR" 2>/dev/null || true
done
unset SHR_VHOST_DIR
"$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -T 2>&1 \
  | grep -nE 'configuration file|listen[[:space:]]+(80|443|8080|18080)|proxy_pass.*(8080|18080)|/opt/shenzhouhr' \
  || true
```

对每个候选文件用 `sed -n '1,240p' /实际路径.conf` 查看，并记录：完整配置路径、
`server_name`、`listen`、`root`、`proxy_pass`、rewrite/证书引用。旧站点未必使用现在的
IP，也可能是旧安装覆盖的客户原有站点，所以“文件名看起来像 HR”不能作为删除依据。
特别检查未登记在宝塔网站列表中的 `shenzhouhr.conf`：只要它仍被 `nginx -T` 加载，里面的
`listen 80` 或旧代理就仍然生效，必须按第 5 节备份并隔离精确文件。

同时在宝塔页面记录：

- 「网站」里的旧站点名称、域名/端口和根目录；
- 「Java 项目」里是否已有同名项目；
- 「数据库」里是否已经显示旧实际数据库；
- 「安全」中的 80、443、8080、18080、23272、23273 规则；
- 客户路由器中旧 80、443、8080、18080 映射（包括“外部 8080 → 内部 8080”）。

## 3. 先停止旧写入，但暂时不删 unit 和文件

### 3.0 宝塔里如果已经有旧 Java 项目

在「网站 → Java 项目」中检查 `kaoqinweb`、`kaoqin`，以及端口为 8080/18080 的项目。
旧项目也可能指向 `/opt/shenzhouhr2026080702/...`、其他解压目录或改过名的 JAR；必须结合
JAR `realpath`/SHA-256、`BUILD-MANIFEST.txt`、工作目录、环境文件和监听 PID 共同证明归属，
不能只凭项目名。只有已经证明属于旧神州 HR 时：

1. 只把项目名、JDK、端口、JAR 路径、环境文件路径等**脱敏字段**写入清理记录；完整启动
   命令可能含密码，不截图、不粘贴到普通记录；
2. 在面板点击「停止」，确认状态不再是运行中；
3. **暂时保留已停止的项目管理记录**，此时绝不点击删除；
4. 回到终端用 `ps`、`ss` 再核对进程已经停止。

若同名项目指向其他 JAR，立即停止，不能按名称处理。旧 Java 项目生成的反向代理配置和
项目记录要先按第 5 节完成备份；数据库恢复验证通过后，才能删除管理记录。

### 3.1 旧 systemd 服务存在时

执行下面整段；它会重新校验路径、先备份 unit/drop-in，再停服并确认 `inactive`，最后才禁用：

```bash
shr_stop_legacy_systemd() {
  local state path dropin_dir=/etc/systemd/system/shenzhouhr.service.d
  export SHR_UNIT_PATH
  export SHR_DROPIN_PATHS
  SHR_UNIT_PATH="$(systemctl show -p FragmentPath --value shenzhouhr.service)"
  SHR_DROPIN_PATHS="$(systemctl show -p DropInPaths --value shenzhouhr.service)"
  if [ "$SHR_UNIT_PATH" != /etc/systemd/system/shenzhouhr.service ] \
      || [ ! -f "$SHR_UNIT_PATH" ] || [ -L "$SHR_UNIT_PATH" ]; then
    echo "STOP：unit 路径不是预期安全文件：$SHR_UNIT_PATH"
    return 1
  fi
  for path in $SHR_DROPIN_PATHS; do
    case "$path" in
      "$dropin_dir"/*) ;;
      *) echo "STOP：发现非预期 drop-in：$path"; return 1 ;;
    esac
    [ -f "$path" ] && [ ! -L "$path" ] || {
      echo "STOP：drop-in 不是安全普通文件：$path"
      return 1
    }
  done
  if [ -L "$dropin_dir" ]; then
    echo 'STOP：旧 drop-in 目录是符号链接，请管理员处理'
    return 1
  fi
  if [ -d "$dropin_dir" ] \
      && find "$dropin_dir" -mindepth 1 ! -type f -print -quit | grep -q .; then
    echo 'STOP：drop-in 目录含普通配置文件以外的对象，请管理员处理'
    return 1
  fi
  [ ! -e "$SHR_CLEAN_DIR/legacy-systemd-unit.service" ] || return 1
  cp -a "$SHR_UNIT_PATH" "$SHR_CLEAN_DIR/legacy-systemd-unit.service" || return 1
  if [ -d "$dropin_dir" ]; then
    [ ! -e "$SHR_CLEAN_DIR/legacy-systemd-dropins" ] || return 1
    cp -a "$dropin_dir" "$SHR_CLEAN_DIR/legacy-systemd-dropins" || return 1
  fi
  systemctl stop shenzhouhr.service || return 1
  state="$(systemctl is-active shenzhouhr.service 2>/dev/null || true)"
  if [ "$state" != inactive ]; then
    echo "STOP：旧服务停止后状态仍为：${state:-unknown}"
    return 1
  fi
  systemctl disable shenzhouhr.service || return 1
  printf 'PASS：旧 systemd 已备份、停止并禁用；unit 暂未删除\n'
}
shr_stop_legacy_systemd
unset -f shr_stop_legacy_systemd
```

必须看到 `PASS`。出现 `STOP`、没有 PASS 或命令报错时不要继续；本节仍不会删除 unit 文件。

### 3.2 unit 不存在但仍有旧 Java 时

若 `systemctl` 找不到服务，但 `ps` 显示旧 JAR 仍在运行，先核对 PID 对应的完整 JAR 路径、
父进程和启动方式，由服务器管理员用原管理方式停止。不要执行 `pkill java` 或按端口杀进程。

### 3.3 确认写入已经停止

```bash
ss -lntp | grep -E ':(8080|18080)\b' || true
ps -ef | grep -E '[s]henzhou-hr\.jar|[j]ava.*shenzhou' || true
```

两条都不应再显示旧神州 HR。若端口属于另一个应用，也必须先解决端口冲突；否则不能安装
新后端。

## 4. 停服后做最终数据库备份，并实际恢复验证

### 4.1 证明实际库属于旧神州 HR

用第 2.2 节记录的**实际库名**操作，不要直接套用 `shenzhou_hr`。用 phpMyAdmin 或 MySQL
管理员选中该库，先执行只读查询：

```sql
SELECT DATABASE() AS actual_database;

SELECT COUNT(*) AS table_count,
       COALESCE(SUM(table_rows), 0) AS approximate_rows
FROM information_schema.tables
WHERE table_schema = DATABASE();

SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'flyway_schema_history', 'legal_entity', 'employee',
    'local_account', 'attendance_group'
  )
ORDER BY table_name;
```

对上一步确实存在的关键业务表分别执行精确计数；不存在的表不要硬查，记录“未创建”：

```sql
SELECT COUNT(*) AS legal_entity_rows FROM legal_entity;
SELECT COUNT(*) AS employee_rows FROM employee;
SELECT COUNT(*) AS local_account_rows FROM local_account;
SELECT COUNT(*) AS attendance_group_rows FROM attendance_group;
```

四张关键表只是快速识别，**不能**作为删除原库的充分条件。还必须对全部基础表生成并执行
精确 `COUNT(*)`（只修改当前会话变量，不修改业务数据）：

```sql
SET SESSION group_concat_max_len = 1048576;

SELECT GROUP_CONCAT(
  CONCAT(
    'SELECT ', QUOTE(TABLE_NAME),
    ' AS table_name, COUNT(*) AS exact_rows FROM `',
    REPLACE(TABLE_NAME, '`', '``'), '`'
  )
  ORDER BY TABLE_NAME
  SEPARATOR ' UNION ALL '
) INTO @all_count_sql
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE';

PREPARE all_count_stmt FROM @all_count_sql;
EXECUTE all_count_stmt;
DEALLOCATE PREPARE all_count_stmt;
```

把完整结果下载为受控 CSV 并记录 SHA-256。**相同行数不代表数据相同**，所以 DBA 还必须
为原库生成以下两组受控证据：

1. 先列出全部基础表，再对每张表保存完整 `SHOW CREATE TABLE`；规范化时只允许把实际库名
   替换为统一占位符，不得删除列、索引、外键、默认值、生成列、字符集或分区信息；
2. 对每张基础表的**全部列、全部行**做二进制安全、确定性排序的内容导出或内容摘要，并对
   每张表分别计算 SHA-256。优先按主键排序；没有主键/唯一键的表必须由 DBA 定义不会丢失
   `NULL`、二进制和换行差异的规范顺序，不能使用 `table_rows`、抽样或简单行数代替。

用于生成清单的只读查询为：

```sql
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;

SELECT TABLE_NAME,
       GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS primary_key_columns
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND CONSTRAINT_NAME = 'PRIMARY'
GROUP BY TABLE_NAME
ORDER BY TABLE_NAME;

-- 对上面列出的每一张基础表分别执行，表名必须取自清单：
SHOW CREATE TABLE `替换为真实基础表名`;
```

内容导出必须由 DBA 使用受控工具和只读凭据完成，密码不得写进命令行或清理记录。保存“表名
→ DDL SHA-256 → 全量内容 SHA-256”的清单；任何无法生成确定性全量内容摘要的表都视为恢复
验证未通过。随后再导出需要逐项一致的数据库对象清单：

```sql
SELECT TABLE_NAME,
       SHA2(REPLACE(COALESCE(VIEW_DEFINITION, ''), DATABASE(), '<SCHEMA>'), 256)
         AS definition_sha256
FROM information_schema.VIEWS
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME;

SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_TIMING,
       SHA2(REPLACE(COALESCE(ACTION_STATEMENT, ''), DATABASE(), '<SCHEMA>'), 256)
         AS definition_sha256
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
ORDER BY TRIGGER_NAME;

SELECT ROUTINE_NAME, ROUTINE_TYPE,
       SHA2(REPLACE(COALESCE(ROUTINE_DEFINITION, ''), DATABASE(), '<SCHEMA>'), 256)
         AS definition_sha256
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = DATABASE()
ORDER BY ROUTINE_NAME, ROUTINE_TYPE;

SELECT EVENT_NAME, STATUS,
       SHA2(REPLACE(COALESCE(EVENT_DEFINITION, ''), DATABASE(), '<SCHEMA>'), 256)
         AS definition_sha256
FROM information_schema.EVENTS
WHERE EVENT_SCHEMA = DATABASE()
ORDER BY EVENT_NAME;
```

四份对象结果即使是 0 行也要记录；恢复库必须得到完全相同的名称、属性和定义哈希。若任一
清单非 0 行，DBA 还必须对每个对象分别保存并比对规范化后的对应 `SHOW CREATE` 输出，
覆盖 DEFINER、SQL SECURITY、CHECK OPTION、确定性、数据访问、
事件时区/调度/完成策略等元数据；仅比较上面的定义哈希仍不足以授权删库。

如果存在 `flyway_schema_history`，再执行：

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

记录库名、表数、约数行数、最高 Flyway 版本、失败迁移数和关键表精确行数。`table_rows`
只是 InnoDB 估算值，恢复前后可略有差异，不能单独拿它判定成功。没有
`flyway_schema_history`、混有明显非 HR 表、存在其他系统数据，或无法证明归属时，停止，
不得删库。

管理员再检查谁拥有该库权限：

```sql
SELECT GRANTEE, PRIVILEGE_TYPE
FROM information_schema.SCHEMA_PRIVILEGES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY GRANTEE, PRIVILEGE_TYPE;
```

若 phpMyAdmin 报权限不足，交给 MySQL root/DBA；不要把 phpMyAdmin 或 3306 临时开放公网。

### 4.2 让数据库先出现在宝塔

如果宝塔数据库列表已经有第 2.2 节的实际库，直接进入下一步。列表没有时：

1. 进入「数据库 → MySQL → 读取本地数据库」；
2. 输入 MySQL root 密码；
3. 选择「从服务器同步到面板」；
4. 核对同步出来的是实际库名。

当前面板没有该功能时，使用现有 phpMyAdmin 或由 DBA 导出。**不要为了本次清理临时升级
宝塔**，也不要因面板看不见就认定数据库不存在。

### 4.3 导出、下载、校验、恢复

旧 Java 已停止后，才执行最终备份：

1. 在宝塔该数据库行点击「备份」，等待成功；
2. 下载备份到受控操作电脑，记录文件名、大小和 SHA-256；
3. **保持原备份只读不改。** 由 DBA 在受控副本中检查是否包含 `CREATE DATABASE`、
   `DROP DATABASE`、`USE 旧库` 或 ``旧库名`.`表名`` 这类跨库语句；
4. 只使用 DBA 已确认能重定向恢复的副本。若含上述语句，先由 DBA 生成明确只指向临时库的
   恢复副本，绝不能把原文件直接点“导入”；
5. 创建唯一临时库名和同名临时本地账号：

   ```bash
   export SHR_RESTORE_DB="shr_restore_${SHR_CLEAN_TAG//-/}"
   printf '临时恢复库/账号：%s\n' "$SHR_RESTORE_DB"
   ```

   先在宝塔确认该名字完全不存在，再添加数据库；访问权限只选“本地服务器”；
6. 选中该临时库，先执行 `SELECT DATABASE();`，必须返回 `$SHR_RESTORE_DB` 对应的实际值；
   再导入 DBA 确认过的恢复副本。导入过程只要出现旧库名、切库或跨库警告就立即停止；
7. 导入后再次执行 `SELECT DATABASE();`，再重复 4.1 的 Flyway、**全部基础表精确计数、
   每张基础表完整 DDL 和全量内容摘要**，以及 views/triggers/routines/events 对象清单；
8. 原库与恢复库的基础表名称/精确行数、逐表 DDL SHA-256、逐表全量内容 SHA-256、Flyway
   记录、对象名称/属性/定义哈希全部一致，且两份受控结果均已记录后，才由业务负责人确认
   “允许删除旧库”；
9. 在宝塔同时删除临时验证库和它的临时账号，再查询确认两者均不存在；保留最终备份。

导入失败、查询不一致、备份仅有表结构没有业务数据，或业务负责人没有确认，均不得删除
原库。**如果旧库里的客户业务数据要在新系统继续使用，就不能按本手册删库并新建空库；
必须在这里停止，另行制定“保留原库或受控迁移”的方案。** HR 备份包含个人信息，应加密
保存并按客户的数据保留期限销毁。

## 5. 证明旧站点独占后再处理

### 5.1 备份候选配置，检查共享引用

重新生成候选清单。清单和归档都只允许 root 读取，并保留每个文件的原目录结构，因此不同
目录里的同名 `shenzhouhr.conf` 不会互相覆盖：

```bash
export SHR_NGINX_PATHS="$SHR_CLEAN_DIR/nginx-related-paths.txt"
: > "$SHR_NGINX_PATHS"
SHR_NGINX_DUMP_OK=0
if ! SHR_NGINX_DUMP="$("$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -T 2>&1)"; then
  echo 'STOP：无法读取实际 Nginx 配置，候选清单未生成'
else
  SHR_NGINX_DUMP_OK=1
  printf '%s\n' "$SHR_NGINX_DUMP" \
    | sed -n 's/^# configuration file \(.*\):$/\1/p' \
    > "$SHR_CLEAN_DIR/nginx-loaded-paths.txt"
  while IFS= read -r SHR_LOADED_PATH; do
    [ -e "$SHR_LOADED_PATH" ] || [ -L "$SHR_LOADED_PATH" ] || continue
    if grep -IlE 'shenzhouhr|/opt/shenzhouhr|proxy_pass[[:space:]]+http://(127\.0\.0\.1|localhost):(8080|18080)' \
        "$SHR_LOADED_PATH" >/dev/null 2>&1; then
      printf '%s\n' "$SHR_LOADED_PATH" >> "$SHR_NGINX_PATHS"
      if [ -L "$SHR_LOADED_PATH" ]; then
        readlink -f "$SHR_LOADED_PATH" >> "$SHR_NGINX_PATHS"
      fi
    fi
  done < "$SHR_CLEAN_DIR/nginx-loaded-paths.txt"
fi
for SHR_VHOST_DIR in \
  /www/server/panel/vhost/nginx \
  /www/server/nginx/conf/vhost \
  /etc/nginx/conf.d \
  /etc/nginx/sites-enabled \
  /etc/nginx/sites-available
do
  [ -d "$SHR_VHOST_DIR" ] || continue
  while IFS= read -r -d '' SHR_CANDIDATE_PATH; do
    if grep -IlE 'shenzhouhr|/opt/shenzhouhr|proxy_pass[[:space:]]+http://(127\.0\.0\.1|localhost):(8080|18080)' \
        "$SHR_CANDIDATE_PATH" >/dev/null 2>&1; then
      printf '%s\n' "$SHR_CANDIDATE_PATH" >> "$SHR_NGINX_PATHS"
      if [ -L "$SHR_CANDIDATE_PATH" ]; then
        readlink -f "$SHR_CANDIDATE_PATH" >> "$SHR_NGINX_PATHS"
      fi
    fi
  done < <(find "$SHR_VHOST_DIR" \( -type f -o -type l \) -print0)
done
sort -u -o "$SHR_NGINX_PATHS" "$SHR_NGINX_PATHS"
chmod 0600 "$SHR_NGINX_PATHS"
nl -ba "$SHR_NGINX_PATHS"
unset SHR_VHOST_DIR SHR_CANDIDATE_PATH SHR_LOADED_PATH SHR_NGINX_DUMP
```

逐个打开清单中的候选，记录其 `include`、rewrite、证书和私钥路径。把每个**实际存在且已
确认关联**的 rewrite 文件、证书文件或证书目录追加到 `nginx-related-paths.txt`，每行一个
绝对路径；不要把无关站点证书加入。然后逐项验证并归档：

```bash
export SHR_NGINX_ARCHIVE="$SHR_CLEAN_DIR/nginx-related.tar"
SHR_RELATED_MISSING=0
while IFS= read -r SHR_RELATED_PATH; do
  if [ ! -e "$SHR_RELATED_PATH" ]; then
    echo "STOP：关联路径不存在：$SHR_RELATED_PATH"
    SHR_RELATED_MISSING=1
  fi
done < "$SHR_NGINX_PATHS"
if [ "${SHR_NGINX_DUMP_OK:-0}" -ne 1 ]; then
  echo 'STOP：活动配置未成功解析，禁止创建归档或删除站点'
elif [ ! -s "$SHR_NGINX_PATHS" ]; then
  echo 'INFO：关联路径清单为空，无需归档；继续执行下面的“无配置确认”分支'
elif [ "$SHR_RELATED_MISSING" -ne 0 ]; then
  echo 'STOP：未创建归档，请先修正路径清单'
else
  if ! tar --acls --xattrs -cpf "$SHR_NGINX_ARCHIVE" \
      --files-from "$SHR_NGINX_PATHS"; then
    echo 'STOP：Nginx 归档失败，禁止删除站点'
    rm -f -- "$SHR_NGINX_ARCHIVE"
  elif ! chmod 0600 "$SHR_NGINX_ARCHIVE" \
      || ! tar -tf "$SHR_NGINX_ARCHIVE" \
        > "$SHR_CLEAN_DIR/nginx-archive-manifest.txt" \
      || ! chmod 0600 "$SHR_CLEAN_DIR/nginx-archive-manifest.txt"; then
    echo 'STOP：Nginx 归档无法复读或加固，禁止删除站点'
  else
    sed -n '1,240p' "$SHR_CLEAN_DIR/nginx-archive-manifest.txt"
    echo 'PASS：Nginx 关联配置归档已创建并可复读'
  fi
fi
unset SHR_RELATED_MISSING SHR_RELATED_PATH
```

若因活动配置解析失败、路径缺失或归档失败而出现 `STOP`，不要继续；先修正清单。清单为空
时应看到 `INFO`，并执行下面的“无配置确认”分支。私钥只存在 root `0600` 归档中，不下载、
不截图。恢复时也不能把整个归档直接解压到 `/`，只能由服务器管理员按清单恢复精确文件。

如果旧命令确实在写 vhost 之前就中断，候选清单可能为空。此时不能伪造归档；必须由两名
操作人再次确认宝塔网站/Java 项目均没有旧 HR 入口，并执行下面整段生成“确实没有关联配置”
的受控记录：

```bash
shr_confirm_no_old_nginx_paths() {
  local dump digest answer marker="$SHR_CLEAN_DIR/nginx-no-related-paths.confirmed"
  [ "${SHR_NGINX_DUMP_OK:-0}" -eq 1 ] && [ ! -s "$SHR_NGINX_PATHS" ] || {
    echo 'STOP：只有活动配置解析成功且候选清单为空时才能走无配置分支'
    return 1
  }
  dump="$("$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -T 2>&1)" || return 1
  if printf '%s\n' "$dump" \
      | grep -Eq 'shenzhouhr|/opt/shenzhouhr|proxy_pass[[:space:]]+http://(127\.0\.0\.1|localhost):(8080|18080)'; then
    echo 'STOP：实际活动配置仍含旧 HR 特征，不能确认“无配置”'
    return 1
  fi
  read -r -p '两人已确认面板和活动配置都没有旧 HR 入口；请输入 NO-OLD-HR-NGINX：' answer
  [ "$answer" = NO-OLD-HR-NGINX ] || return 1
  digest="$(printf '%s\n' "$dump" | sha256sum | awk '{print $1}')" || return 1
  printf '%s|%s\n' "$SHR_CLEAN_TAG" "$digest" > "$marker" || return 1
  chmod 0600 "$marker" || return 1
  echo 'PASS：已记录旧半部署没有 Nginx 关联配置'
}
shr_confirm_no_old_nginx_paths
unset -f shr_confirm_no_old_nginx_paths
```

只有看到此处 `PASS` 才能跳过 5.2、5.3 中不存在的旧 Java 项目/站点；有任何候选路径时仍
必须走归档和逐项处置分支。

再从每份候选配置中取得真实 `root`。对每一个根目录分别设置变量并查引用，示例路径必须
替换：

```bash
export SHR_OLD_WEB_ROOT="/www/wwwroot/实际旧目录"
if [ "$SHR_OLD_WEB_ROOT" = /www/wwwroot/实际旧目录 ]; then
  echo 'STOP：尚未替换真实旧网站目录'
else
  grep -RIlF "$SHR_OLD_WEB_ROOT" \
    /www/server/panel/vhost/nginx \
    /www/server/nginx/conf/vhost \
    /etc/nginx 2>/dev/null | sort -u \
    | tee "$SHR_CLEAN_DIR/web-root-references.txt"
  printf '引用数量：'
  wc -l < "$SHR_CLEAN_DIR/web-root-references.txt"
fi
```

候选自身会产生一条引用；只有逐项解释了全部引用，才能判定独占。检查
`/opt/shenzhouhr/backups/` 下是否有旧安装前保存的同名 Nginx 配置。出现以下任一情况，
都不能直接删站：

- 其他 vhost 也引用同一根目录；
- 候选配置原本还承载其他域名/业务；
- 旧备份证明安装脚本覆盖了已有站点；
- 无法确认域名、证书或 rewrite 的原归属。

此时应由服务器管理员恢复安装前的原配置，而不是删除整个站点。

### 5.2 此时才删除已停止的旧 Java 项目记录

只有第 4 节数据库恢复验证通过、第 5.1 节项目关联的 vhost/rewrite/证书已经归档，才回到
宝塔「Java 项目」删除第 3.0 节已停止的精确旧项目。弹窗中不勾选删除 JAR、网站根目录、
数据库、域名/反向代理或其他文件；这些对象分别按本手册核验。删除后再次用 `ps`、`ss`
确认没有被面板守护器重新拉起。若面板删除动作无法做到“只删管理记录”，停止并由宝塔
管理员处理。

### 5.3 只处理确认独占的旧 HR 站点

如果已经证明站点和根目录只属于旧 HR，对
`nginx-related-paths.txt` 中的**每个活跃候选配置**逐一处理：

1. 在宝塔删除精确旧站点，**不要勾选“同时删除网站根目录”**；
2. 若它不在宝塔列表，只把精确 vhost 文件改名为不以 `.conf` 结尾的隔离名；
3. 先不要删除旧网站根目录，稍后在原文件系统内改名隔离；
4. 执行：

```bash
if "$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -t; then
  if "$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -s reload; then
    echo 'PASS：Nginx 已按实际参数安全重载'
  else
    echo 'STOP：Nginx reload 失败，立即恢复精确备份'
  fi
else
  echo 'STOP：Nginx 配置检查失败，未 reload，立即恢复精确备份'
fi
```

第一条必须同时显示 `syntax is ok` 和 `test is successful`。随后抽查服务器上其他已有网站；
任何站点异常都应立即恢复备份配置并停止。

### 5.4 第一次删除数据库前，先确认回滚材料和顺序

在继续第 6 节前，先保存尚未生成的目录 metadata 和小型 env 快照；不要等删库后才做：

```bash
shr_capture_rollback_material() {
  local path found=0
  local metadata="$SHR_CLEAN_DIR/old-top-level-metadata.txt"
  local expected="$SHR_CLEAN_DIR/old-top-level-expected.txt"
  : > "$metadata" || return 1
  : > "$expected" || return 1
  for path in \
    /etc/shenzhouhr /opt/shenzhouhr /var/lib/shenzhouhr /run/shenzhouhr
  do
    [ -e "$path" ] || continue
    if [ ! -d "$path" ] || [ -L "$path" ]; then
      echo "STOP：旧路径不是普通目录：$path"
      return 1
    fi
    stat -c '%n|%U|%G|%a' "$path" >> "$metadata" || return 1
    printf '%s\n' "$path" >> "$expected" || return 1
    found=1
  done
  [ "$found" -eq 1 ] || printf '# no legacy top-level directories present\n' > "$metadata"
  chmod 0600 "$metadata" "$expected" || return 1
  if [ -d /etc/shenzhouhr ]; then
    [ ! -e "$SHR_CLEAN_DIR/pre-delete-etc-shenzhouhr" ] || return 1
    cp -a /etc/shenzhouhr "$SHR_CLEAN_DIR/pre-delete-etc-shenzhouhr" || return 1
  fi
  find "$SHR_CLEAN_DIR" -type f ! -name SHA256SUMS -print0 \
    | sort -z | xargs -0 -r sha256sum > "$SHR_CLEAN_DIR/SHA256SUMS" \
    || return 1
  sha256sum -c "$SHR_CLEAN_DIR/SHA256SUMS" || return 1
  echo 'PASS：删库前的小型回滚材料已生成并复读'
}
shr_capture_rollback_material
unset -f shr_capture_rollback_material
```

对第 5.1 节确认独占的**每一个**旧 web root，也在此时重复执行下面命令（先替换真实目录），
只记录、不改名：

```bash
shr_record_old_web_root() {
  local root resolved target
  root="$1"
  resolved="$(realpath -e -- "$root")" || return 1
  case "$resolved" in
    /www/wwwroot/*) ;;
    *) echo "STOP：旧网站目录不在 /www/wwwroot 下：$resolved"; return 1 ;;
  esac
  [ "$root" = "$resolved" ] && [ -d "$resolved" ] && [ ! -L "$resolved" ] || {
    echo 'STOP：旧网站目录必须使用无符号链接、无 .. 的真实绝对路径'
    return 1
  }
  if grep -Fq "${resolved}|" "$SHR_CLEAN_DIR/old-web-root-metadata.txt" 2>/dev/null; then
    echo "STOP：旧网站目录已记录过：$resolved"
    return 1
  fi
  target="${resolved}.disabled-${SHR_CLEAN_TAG}"
  [ ! -e "$target" ] && [ ! -L "$target" ] || {
    echo "STOP：旧网站隔离目标已存在：$target"
    return 1
  }
  stat -c '%n|%U|%G|%a' "$resolved" \
    >> "$SHR_CLEAN_DIR/old-web-root-metadata.txt" || return 1
  printf '%s|%s\n' "$resolved" "$target" \
    >> "$SHR_CLEAN_DIR/old-web-root-moves.txt" || return 1
  chmod 0600 "$SHR_CLEAN_DIR/old-web-root-"*.txt || return 1
  echo "PASS：已记录旧网站目录：$resolved"
}
shr_record_old_web_root /www/wwwroot/替换为真实旧目录
# 有多个已证明独占的旧根目录时，逐个重复上一行；全部 PASS 后再执行：
unset -f shr_record_old_web_root
```

两名操作人共同确认当前已有材料都能读取：最终数据库备份及 SHA-256、
临时库恢复验证记录、Nginx 归档/manifest、旧 unit/drop-in、目录 metadata、env 快照和旧
Java 项目脱敏配置。第 6.2 节还会补齐全部账号 `SHOW GRANTS`；真正点击删库前再做最终门禁。
失败时不要“边试边恢复”，由服务器管理员按以下顺序操作：

1. 保持新旧 Java 都停止；
2. 在宝塔重新创建**原实际库名**，只把已验证的最终备份恢复回该库并复核全部计数/对象；
3. DBA 按受控授权记录恢复旧专用账号或共享账号的精确授权；
4. 按 moves/metadata 把旧 env、JAR、状态和网站根目录反向改名并恢复原属主/权限；
5. 把 Nginx 归档解到 root `0700` 临时目录，逐个比对后恢复精确文件，禁止直接向 `/` 整包
   解压；用第 2.3 节记录的真实 Nginx 参数执行 `-t`，成功后才 reload；
6. 最后恢复旧 unit/drop-in **或**旧宝塔 Java 项目中的一种管理方式，禁止双管理器；启动后
   重新核对端口、日志、数据库和其他站点。

任一当前材料缺失或无法复读，就不能继续；账号授权材料缺失则不能执行第 6.3 节。

## 6. 删除已获确认的旧数据库和专用账号

### 6.1 确认实际旧库可以删除，但先不要点删除

只有第 4 节全部通过且业务负责人已书面确认，才允许继续处理第 2.2 节记录的**实际旧库**。
不要按手册默认值猜库名。如果实际库包含非 HR 表、被其他项目使用或无法证明独占，不能整库
删除；由 DBA 制定表级迁移/保留方案。此时先处理旧授权，因为 MySQL 删除数据库并不会自动
清除指向该库名的全部授权；以后同名建库时，旧账号可能重新获得访问权。

### 6.2 处理指向旧实际库的全部授权，不只看两个默认账号

以 MySQL root/DBA 在 phpMyAdmin 中选中旧实际库，执行：

```sql
SELECT DISTINCT GRANTEE
FROM (
  SELECT GRANTEE FROM information_schema.SCHEMA_PRIVILEGES
    WHERE TABLE_SCHEMA = DATABASE()
  UNION ALL
  SELECT GRANTEE FROM information_schema.TABLE_PRIVILEGES
    WHERE TABLE_SCHEMA = DATABASE()
  UNION ALL
  SELECT GRANTEE FROM information_schema.COLUMN_PRIVILEGES
    WHERE TABLE_SCHEMA = DATABASE()
  UNION ALL
  SELECT GRANTEE FROM information_schema.ROUTINE_PRIVILEGES
    WHERE ROUTINE_SCHEMA = DATABASE()
) AS old_grants
ORDER BY GRANTEE;

SELECT User, Host
FROM mysql.user
WHERE User IN (
  'shenzhouhr_app', 'shenzhouhr_migrator', 'shenzhouhr_panel'
)
ORDER BY User, Host;

SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE
FROM information_schema.USER_PRIVILEGES
WHERE PRIVILEGE_TYPE <> 'USAGE'
ORDER BY GRANTEE, PRIVILEGE_TYPE;
```

先在 root 终端复读第 2.2 节的实际旧用户名清单：

```bash
sed -n '1,240p' "$SHR_CLEAN_DIR/old-db-usernames.txt"
```

对库级/表级/列级/存储过程查询得到的账号、三个常见账号，以及清单中**每一个实际旧用户名
的所有 Host**逐一查询 `mysql.user`，再对每个精确 `User@Host` 执行 `SHOW GRANTS`。下面
Host 只是示例，必须按真实结果替换：

```sql
SHOW GRANTS FOR 'shenzhouhr_app'@'127.0.0.1';
SHOW GRANTS FOR 'shenzhouhr_migrator'@'127.0.0.1';
SHOW GRANTS FOR 'shenzhouhr_panel'@'localhost';
```

把脱敏后的授权范围保存到受控清理记录。专属于旧 HR 的账号可按真实 Host 删除；共享账号
不得删除，只能由 DBA 按 `SHOW GRANTS` 的真实范围撤销旧库的 database/table/column/routine
授权。不要只撤销数据库级授权后漏掉表级或存储过程权限。

`USER_PRIVILEGES` 是 `*.*` 全局授权：它不会出现在前四类“指向某个库”的清单中，但同样能
访问以后重建的同名库。客户书面批准的 MySQL 管理员账号可保留并记录；任何实际旧服务账号、
自定义旧账号或不明账号如果拥有全局数据权限，必须由 DBA 撤销对应全局权限或删除专用账号，
不能把它当成“与旧库无关”跳过。默认专用账号的示例为：

```sql
DROP USER 'shenzhouhr_app'@'127.0.0.1';
DROP USER 'shenzhouhr_migrator'@'127.0.0.1';
```

若真实 Host 不是 `127.0.0.1`，必须精确替换。宝塔旧站点关联的面板数据库账号由宝塔删除
旧数据库时一并处理，但删除后仍要查询确认。权限不足时停止，不要扩大 phpMyAdmin 权限或
开放公网访问。

### 6.3 最后删除实际旧库并确认没有遗留授权

完成 6.2 后，两人再次确认第 5.4 节全部材料、实际旧用户名清单、**每个账号的 SHOW GRANTS**
和全局授权审计均已保存。点击删除前，再执行下面的机器门禁：

```bash
shr_verify_pre_drop_material() {
  local path expected="$SHR_CLEAN_DIR/old-top-level-expected.txt"
  local metadata="$SHR_CLEAN_DIR/old-top-level-metadata.txt"
  local ready="$SHR_CLEAN_DIR/PRE-DROP-MATERIAL-READY"
  local sources=(
    /etc/shenzhouhr /opt/shenzhouhr /var/lib/shenzhouhr /run/shenzhouhr
  )
  [ -f "$expected" ] && [ ! -L "$expected" ] \
    && [ -s "$metadata" ] && [ ! -L "$metadata" ] || {
      echo 'STOP：缺少删库前的目录清单或 metadata'
      return 1
    }
  sha256sum -c "$SHR_CLEAN_DIR/SHA256SUMS" || return 1
  for path in "${sources[@]}"; do
    if grep -Fxq "$path" "$expected"; then
      [ -d "$path" ] && [ ! -L "$path" ] \
        && grep -Fq "${path}|" "$metadata" || {
          echo "STOP：待隔离目录与删库前记录不一致：$path"
          return 1
        }
    elif [ -e "$path" ] || [ -L "$path" ]; then
      echo "STOP：记录完成后出现了未登记目录：$path"
      return 1
    fi
  done
  if grep -Fxq /etc/shenzhouhr "$expected"; then
    [ -d "$SHR_CLEAN_DIR/pre-delete-etc-shenzhouhr" ] \
      && [ ! -L "$SHR_CLEAN_DIR/pre-delete-etc-shenzhouhr" ] || {
        echo 'STOP：/etc/shenzhouhr 存在，但它的删库前快照缺失'
        return 1
      }
  fi
  [ -s "$SHR_CLEAN_DIR/old-db-usernames.txt" ] || {
    echo 'STOP：实际旧数据库用户名清单缺失'
    return 1
  }
  if [ -s "$SHR_CLEAN_DIR/nginx-related-paths.txt" ]; then
    [ -s "$SHR_CLEAN_DIR/nginx-related.tar" ] \
      && [ -s "$SHR_CLEAN_DIR/nginx-archive-manifest.txt" ] || {
        echo 'STOP：有旧 Nginx 关联路径，但归档或 manifest 缺失'
        return 1
      }
  elif [ ! -s "$SHR_CLEAN_DIR/nginx-no-related-paths.confirmed" ]; then
    echo 'STOP：没有 Nginx 候选路径，也没有两人确认的无配置记录'
    return 1
  fi
  if [ -e "$SHR_CLEAN_DIR/old-web-root-metadata.txt" ] \
      || [ -e "$SHR_CLEAN_DIR/old-web-root-moves.txt" ]; then
    [ -s "$SHR_CLEAN_DIR/old-web-root-metadata.txt" ] \
      && [ -s "$SHR_CLEAN_DIR/old-web-root-moves.txt" ] \
      && [ "$(wc -l < "$SHR_CLEAN_DIR/old-web-root-metadata.txt")" \
        = "$(wc -l < "$SHR_CLEAN_DIR/old-web-root-moves.txt")" ] || {
          echo 'STOP：旧网站根目录 metadata/moves 不完整'
          return 1
        }
  fi
  printf '%s\n' "$SHR_CLEAN_TAG" > "$ready" || return 1
  chmod 0600 "$ready" || return 1
  (
    set -o pipefail
    find "$SHR_CLEAN_DIR" -type f \
      ! -name SHA256SUMS ! -name FINAL-SHA256SUMS -print0 \
      | sort -z | xargs -0 -r sha256sum \
      > "$SHR_CLEAN_DIR/FINAL-SHA256SUMS"
  ) || { rm -f -- "$ready"; return 1; }
  chmod 0600 "$SHR_CLEAN_DIR/FINAL-SHA256SUMS" || return 1
  sha256sum -c "$SHR_CLEAN_DIR/FINAL-SHA256SUMS" || return 1
  echo 'PASS：删库前回滚材料机器门禁通过'
}
shr_verify_pre_drop_material
unset -f shr_verify_pre_drop_material
```

必须看到最后的 `PASS`，并由两名操作人确认数据库恢复等价性和账号授权记录也已通过，才在
宝塔删除实际旧库。若宝塔询问是否同时删除关联数据库账号，只选择已经证明专属于旧 HR 的
精确账号。删除后由 DBA 把下列 `实际旧库名` 替换为第 2.2 节值并查询：

```sql
SELECT GRANTEE, PRIVILEGE_TYPE
FROM information_schema.SCHEMA_PRIVILEGES
WHERE TABLE_SCHEMA = '实际旧库名';

SELECT GRANTEE, TABLE_NAME, PRIVILEGE_TYPE
FROM information_schema.TABLE_PRIVILEGES
WHERE TABLE_SCHEMA = '实际旧库名';

SELECT GRANTEE, TABLE_NAME, COLUMN_NAME, PRIVILEGE_TYPE
FROM information_schema.COLUMN_PRIVILEGES
WHERE TABLE_SCHEMA = '实际旧库名';

SELECT GRANTEE, ROUTINE_NAME, ROUTINE_TYPE, PRIVILEGE_TYPE
FROM information_schema.ROUTINE_PRIVILEGES
WHERE ROUTINE_SCHEMA = '实际旧库名';

SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE
FROM information_schema.USER_PRIVILEGES
WHERE PRIVILEGE_TYPE <> 'USAGE'
ORDER BY GRANTEE, PRIVILEGE_TYPE;
```

前四条必须都是 0 行；第五条允许的只能是已书面批准并记录的全局 DBA/系统账号。第 2.2 节
发现的每个实际旧用户名及三个常见账号都必须逐 Host 复查：需删除的专用账号已经不存在，
需保留的共享账号不再有旧库授权，且没有遗漏的全局数据权限。否则由 DBA 继续清理，不能创建
同名新库。

## 7. 隔离旧文件，最后才删除 unit

### 7.1 小配置留一份校验清单

第 5.4 节已经在删库前复制了环境目录并生成校验清单；这里不要再复制整份
`/opt/shenzhouhr`，只复读校验：

```bash
sha256sum -c "$SHR_CLEAN_DIR/SHA256SUMS"
```

校验必须全部 `OK`。该目录含密码，只能 root 读取，不得下载到普通电脑或发给他人。

### 7.2 在各自文件系统内改名隔离

这样不会把大目录跨磁盘复制到 `/root`。下面整段会先检查**所有**源和目标；只要有一个
冲突，就一个也不移动。移动完成后只把隔离目录顶层冻结为 root `0700`，新 Java 运行用户
无法读取旧密码或改写清理证据；原始属主和权限会写入受控记录：

```bash
shr_isolate_old_dirs() {
  local src dst
  local sources=(
    /etc/shenzhouhr
    /opt/shenzhouhr
    /var/lib/shenzhouhr
    /run/shenzhouhr
  )
  [ -f "$SHR_CLEAN_DIR/PRE-DROP-MATERIAL-READY" ] \
    && [ "$(cat "$SHR_CLEAN_DIR/PRE-DROP-MATERIAL-READY")" = "$SHR_CLEAN_TAG" ] \
    && sha256sum -c "$SHR_CLEAN_DIR/FINAL-SHA256SUMS" || {
      echo 'STOP：删库前机器门禁标记或最终校验清单无效'
      return 1
    }
  [ -s "$SHR_CLEAN_DIR/old-top-level-metadata.txt" ] || {
    echo 'STOP：缺少删库前生成的顶层目录 metadata'
    return 1
  }
  for src in "${sources[@]}"; do
    [ -e "$src" ] || continue
    grep -Fxq "$src" "$SHR_CLEAN_DIR/old-top-level-expected.txt" \
      && grep -Fq "${src}|" "$SHR_CLEAN_DIR/old-top-level-metadata.txt" || {
        echo "STOP：源目录没有匹配的删库前记录：$src"
        return 1
      }
    if [ -L "$src" ] || [ ! -d "$src" ]; then
      echo "STOP：源不是普通目录：$src"
      return 1
    fi
    dst="${src}.disabled-${SHR_CLEAN_TAG}"
    if [ -e "$dst" ] || [ -L "$dst" ]; then
      echo "STOP：隔离目标已存在：$dst"
      return 1
    fi
  done
  for src in "${sources[@]}"; do
    [ -d "$src" ] || continue
    dst="${src}.disabled-${SHR_CLEAN_TAG}"
    mv -- "$src" "$dst" || {
      echo "STOP：移动失败：$src；不要继续，由管理员按 metadata 记录回滚"
      return 1
    }
    chown root:root "$dst" && chmod 0700 "$dst" || {
      echo "STOP：无法冻结隔离目录：$dst"
      return 1
    }
  done
  echo 'PASS：旧环境、应用和状态目录均已原地隔离并冻结'
}
shr_isolate_old_dirs
unset -f shr_isolate_old_dirs
```

任一输出 `STOP`，说明目标已存在，立即停下核对，不能覆盖。对于第 5 节已证明独占的每个
旧网站根目录，先复读第 5.4 节在删库前保存的 metadata/moves：

```bash
test -s "$SHR_CLEAN_DIR/old-web-root-metadata.txt" \
  && test -s "$SHR_CLEAN_DIR/old-web-root-moves.txt" \
  && sed -n '1,240p' "$SHR_CLEAN_DIR/old-web-root-metadata.txt" \
  && sed -n '1,240p' "$SHR_CLEAN_DIR/old-web-root-moves.txt"
```

四条都成功并确认路径无误后，才在宝塔「文件」中把精确目录改名为
`原目录.disabled-本次清理编号`，最后将这个精确隔离目录的属主设为 `root:root`、权限设为
`0700`。若需要回滚，按 `old-web-root-moves.txt` 反向改名，并按 metadata 恢复原属主/权限；
不确定是否共享就保留原样。

旧发布包和敏感临时文件只做精确盘点：

```bash
find /opt -maxdepth 1 -name 'shenzhouhr2026*' -print 2>/dev/null
find /tmp -maxdepth 1 \
  \( -name 'shenzhouhr-mysql.*.cnf' \
  -o -name 'shenzhouhr-migrate.*.log' \
  -o -name 'shenzhouhr-admin.*' \
  -o -name 'shenzhou_hr_export_*.sql' \) -print
```

确认没有安装/迁移进程使用后：`/opt` 下的大目录或发布归档只在 `/opt` 原地追加
`.disabled-本次编号` 并设为 root-only；小型 `.cnf/.log` 可逐个使用完整路径移入
`$SHR_CLEAN_DIR`；大型 SQL 先确认是否为必须保留的业务备份，再原地改名并设为 `0600`。
不要使用宽泛通配符移动或删除。SQL 可能含个人信息，`.cnf` 可能含 MySQL root 密码。

### 7.3 删除已停用的精确 unit

如果第 2、3 节已经确认该 unit 从未存在，本小节无需执行，直接到第 8 节验证。若 unit
存在过，再按下面步骤删除。

再次确认服务不是 active：

```bash
systemctl is-active shenzhouhr.service
```

必须显示 `inactive`。然后执行下面整段；它会重新读取实际 unit 和 drop-in，只允许处理
`/etc/systemd/system/shenzhouhr.service` 及其精确 `.service.d` 目录：

```bash
shr_remove_legacy_unit() {
  local unit_path dropins path dropin_dir dropin_target
  unit_path="$(systemctl show -p FragmentPath --value shenzhouhr.service)"
  dropins="$(systemctl show -p DropInPaths --value shenzhouhr.service)"
  if [ "$unit_path" != /etc/systemd/system/shenzhouhr.service ]; then
    echo "STOP：unit 路径不是预期值：$unit_path"
    return 1
  fi
  for path in $dropins; do
    case "$path" in
      /etc/systemd/system/shenzhouhr.service.d/*) ;;
      *) echo "STOP：发现非预期 drop-in：$path"; return 1 ;;
    esac
  done
  dropin_dir=/etc/systemd/system/shenzhouhr.service.d
  dropin_target="${dropin_dir}.disabled-${SHR_CLEAN_TAG}"
  if [ -L "$dropin_dir" ]; then
    echo "STOP：drop-in 目录是符号链接：$dropin_dir"
    return 1
  fi
  if [ -d "$dropin_dir" ]; then
    if [ -e "$dropin_target" ] || [ -L "$dropin_target" ]; then
      echo "STOP：drop-in 隔离目标已存在：$dropin_target"
      return 1
    fi
    mv -- "$dropin_dir" "$dropin_target" || return 1
    chown root:root "$dropin_target" && chmod 0700 "$dropin_target" || return 1
  fi
  rm -f -- /etc/systemd/system/shenzhouhr.service
  systemctl daemon-reload
  systemctl reset-failed shenzhouhr.service || true
  echo 'PASS：旧 unit 已删除，旧 drop-in 已隔离'
}
shr_remove_legacy_unit
unset -f shr_remove_legacy_unit
```

只有路径和 drop-in 校验全部成功时才会删除固定的精确文件；出现 `STOP` 时停止。保留系统用户/组
`shenzhouhr`：新宝塔 Java 项目仍会使用
它。不要删除 Nginx、MySQL、JDK，也不要删除其他项目的 80/443 防火墙规则。

## 8. 清理后逐项 PASS/FAIL 验收

执行：

```bash
SHR_CLEAN_FAILED=0
if systemctl cat shenzhouhr.service >/dev/null 2>&1; then
  echo 'FAIL：旧 systemd unit 仍存在'
  SHR_CLEAN_FAILED=1
else
  echo 'PASS：旧 systemd unit 已移除'
fi

if [ -e /etc/systemd/system/shenzhouhr.service.d ] \
    || [ -L /etc/systemd/system/shenzhouhr.service.d ]; then
  echo 'FAIL：旧 systemd drop-in 仍在原位'
  SHR_CLEAN_FAILED=1
else
  echo 'PASS：旧 systemd drop-in 已移除或隔离'
fi

if ! SHR_LISTENERS="$(ss -H -lntp 2>&1)"; then
  echo "FAIL：ss 执行失败：$SHR_LISTENERS"
  SHR_CLEAN_FAILED=1
elif printf '%s\n' "$SHR_LISTENERS" | grep -Eq ':(8080|18080)([[:space:]]|$)'; then
  echo 'FAIL：8080/18080 仍被监听'
  printf '%s\n' "$SHR_LISTENERS" | grep -E ':(8080|18080)([[:space:]]|$)'
  SHR_CLEAN_FAILED=1
else
  echo 'PASS：旧 Java 端口已释放'
fi

if [ ! -e /etc/shenzhouhr ] && [ ! -L /etc/shenzhouhr ] \
    && [ ! -e /opt/shenzhouhr ] && [ ! -L /opt/shenzhouhr ]; then
  echo 'PASS：旧环境和应用目录已隔离'
else
  echo 'FAIL：旧环境或应用目录仍在原位'
  SHR_CLEAN_FAILED=1
fi

if ! "$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -t; then
  echo 'FAIL：实际运行的 Nginx 配置检查失败'
  SHR_CLEAN_FAILED=1
elif ! SHR_NGINX_DUMP="$("$SHR_NGINX_BIN" "${SHR_NGINX_ARGS[@]}" -T 2>&1)"; then
  echo 'FAIL：无法读取实际运行的 Nginx 配置'
  SHR_CLEAN_FAILED=1
elif printf '%s\n' "$SHR_NGINX_DUMP" \
    | grep -Eq 'shenzhouhr|/opt/shenzhouhr|(127\.0\.0\.1|localhost):(8080|18080)'; then
  echo 'FAIL：宝塔正在加载旧 HR 配置'
  SHR_CLEAN_FAILED=1
else
  echo 'PASS：实际运行的 Nginx 未加载旧 HR 配置'
fi

if [ "${SHR_CLEAN_FAILED:-0}" -ne 0 ]; then
  echo 'STOP：清理终验失败，不能进入重新部署'
else
  echo 'PASS：终端自动检查全部通过；继续完成下面的人工检查'
fi
unset SHR_LISTENERS SHR_NGINX_DUMP
```

再人工逐项确认：

- 最终数据库备份已恢复验证，且 SHA-256、表数、Flyway/关键表结果已记录；
- 已获授权删除的实际旧库确实不再存在，临时恢复库也已删除；
- `shenzhouhr_app`、`shenzhouhr_migrator` 的每个 Host 已删除，或因共享授权已由 DBA
  精确撤销旧库权限；
- 活跃的 `.conf` 中不再有旧 HR 的根目录、旧域名或 8080/18080 代理；
- 服务器上其他 Nginx 网站仍正常；
- 旧网站目录、旧包和敏感 `/tmp` 文件已精确隔离并登记；
- 宝塔防火墙、系统防火墙和路由器中，专属于旧 HR 的 80/443/18080 以及旧“外部 8080 →
  内部 8080”映射已移除；
  共享规则未动；
- `shenzhouhr` 用户/组保留，且没有旧 Java 进程。
- 宝塔 Java 项目列表中没有指向旧 JAR 的残留项目记录。

上面任何一项为 FAIL 或无法确认，都不能进入第二部分。隔离目录在新部署、备份恢复演练和
业务验收全部通过至少一周后，才由客户书面确认永久销毁；销毁前仍按敏感数据管理。

---

# 第二部分：全部按宝塔可视化方式重新部署

## 9. 在宝塔安装并核对软件

进入「软件商店 → 运行环境/已安装」，安装或确认：

1. Nginx；
2. MySQL **8.0.45**；
3. Java 项目管理能力和完整 **JDK 21**；
4. phpMyAdmin（客户需要在网页查看表和数据时安装）。

再次确认宝塔版本至少 11.8；若客户安装了厂商定制版，应先用一个不含密码的测试 JAR 验证
Java 项目确实能保存并加载环境文件，再删除测试项目。不要在正式数据库准备完成后才升级面板。

若 `ss -lntp` 显示已有命令行安装的 Nginx，但「软件商店 → 已安装」看不到它，不要再装
第二套并抢端口。先备份全部 vhost，由服务器管理员使用宝塔“接管本地 Nginx”功能；
接管后必须能在软件商店启停/重载，并存在 `/www/server/panel/vhost/nginx`。接管条件不满足时
停止部署，不要并行运行两套 Nginx。

版本核对：

```bash
/www/server/mysql/bin/mysql --version
```

JDK 路径以宝塔页面显示为准，例如 `/www/server/java/jdk-21.x.x`，再执行：

```bash
/www/server/java/实际JDK21目录/bin/java -version
/www/server/java/实际JDK21目录/bin/jar --version
```

应该分别看到 MySQL `8.0.45`、Java `21`、jar `21`。如果宝塔源没有精确 MySQL 8.0.45，
停止部署并联系交付人员；不要自行选 8.4、MariaDB 或其他“差不多的 8.0”。

## 10. 生成并上传新的发布包

### 10.1 交付人员在开发机生成

工作树必须已经完成、验证并提交，然后在项目根目录执行：

```bash
bash deploy/baota/build-release.sh /absolute/path/to/release-output
```

不要交付旧包 `shenzhouhr2026080702`：它只有 V1～V31，仍属于旧 systemd/80 端口方案。
本手册编写时当前迁移已到 V35，最终仍以新包内 `BUILD-MANIFEST.txt` 为准。

### 10.2 客户上传并校验

先在 root 终端创建不可被普通账号替换的发布父目录：

```bash
install -d -o root -g root -m 0755 /opt/shenzhouhr-releases
```

再从宝塔「文件」进入该目录，上传新 `.tar.gz` 和同名 `.tar.gz.sha256`。以下示例文件名
必须替换成本次收到的**完整精确文件名**：

```bash
export SHR_RELEASE_ARCHIVE=/opt/shenzhouhr-releases/shenzhouhr-release-20260810000000.tar.gz
export SHR_RELEASE_CHECKSUM="$SHR_RELEASE_ARCHIVE.sha256"
export SHR_RELEASE_DIR=/opt/shenzhouhr-releases/shenzhouhr-release-20260810000000
shr_secure_release() {
  local resolved release_name parent_owner parent_mode
  parent_owner="$(stat -c %u /opt/shenzhouhr-releases)"
  parent_mode="$(stat -c %a /opt/shenzhouhr-releases)"
  if [ "$parent_owner" != 0 ] || (( (8#$parent_mode & 022) != 0 )); then
    echo 'STOP：/opt/shenzhouhr-releases 必须 root 所有且不可被组/其他用户写'
    return 1
  fi
  resolved="$(realpath -e -- "$SHR_RELEASE_DIR")" || {
    echo 'STOP：发布目录无法解析'
    return 1
  }
  release_name="$(basename -- "$resolved")"
  if [ "$(dirname -- "$resolved")" != /opt/shenzhouhr-releases ] \
      || [[ ! "$release_name" =~ ^shenzhouhr-release-[A-Za-z0-9._-]+$ ]] \
      || [[ "$release_name" == *..* ]]; then
    echo 'STOP：发布目录必须是固定父目录下的安全单层名称'
    return 1
  fi
  SHR_RELEASE_DIR="$resolved"
  if [ ! -d "$SHR_RELEASE_DIR" ] || [ -L "$SHR_RELEASE_DIR" ]; then
    echo 'STOP：发布目录不存在或是符号链接'
    return 1
  fi
  if find "$SHR_RELEASE_DIR" -type l -print -quit | grep -q .; then
    echo 'STOP：发布包内含符号链接'
    return 1
  fi
  chown -R root:root "$SHR_RELEASE_DIR" || return 1
  chmod -R go-w "$SHR_RELEASE_DIR" || return 1
  echo 'PASS：发布目录已收敛为 root 所有且不可被组/其他用户写'
}
shr_unpack_release() {
  local archive_name checksum_name release_name listing
  cd /opt/shenzhouhr-releases || return 1
  archive_name="$(basename -- "$SHR_RELEASE_ARCHIVE")"
  checksum_name="$(basename -- "$SHR_RELEASE_CHECKSUM")"
  release_name="$(basename -- "$SHR_RELEASE_DIR")"
  if [ ! -f "$SHR_RELEASE_ARCHIVE" ] || [ -L "$SHR_RELEASE_ARCHIVE" ] \
      || [ ! -f "$SHR_RELEASE_CHECKSUM" ] || [ -L "$SHR_RELEASE_CHECKSUM" ]; then
    echo 'STOP：外层归档或校验文件不存在，或不是安全普通文件'
    return 1
  fi
  if ! sha256sum -c "$checksum_name"; then
    echo 'STOP：外层 SHA-256 失败，未解压任何内容'
    return 1
  fi
  if [ -e "$SHR_RELEASE_DIR" ] || [ -L "$SHR_RELEASE_DIR" ]; then
    echo 'STOP：目标发布目录已经存在，禁止覆盖或混合解压'
    return 1
  fi
  listing="$(tar -tzf "$archive_name")" || {
    echo 'STOP：无法读取发布归档目录'
    return 1
  }
  if ! printf '%s\n' "$listing" | awk -v root="$release_name" '
    $0 ~ /^\// || $0 ~ /(^|\/)\.\.(\/|$)/ { bad=1 }
    $0 != root && index($0, root "/") != 1 { bad=1 }
    END { exit bad ? 1 : 0 }
  '; then
    echo 'STOP：归档包含目标发布目录以外的路径，禁止解压'
    return 1
  fi
  if ! tar --no-same-owner -xzf "$archive_name"; then
    echo 'STOP：解压失败；保留现场，不要覆盖重试'
    return 1
  fi
  shr_secure_release || return 1
  cd -- "$SHR_RELEASE_DIR"
  if ! sha256sum -c SHA256SUMS; then
    echo 'STOP：发布包内层 SHA-256 失败'
    return 1
  fi
  sed -n '1,30p' BUILD-MANIFEST.txt
  echo 'PASS：发布包外层、路径、权限和内层校验全部通过'
}
if shr_unpack_release; then
  :
else
  echo 'STOP：发布包未通过安全准备，不得运行 install.sh/upgrade.sh'
fi
unset -f shr_unpack_release shr_secure_release
```

`SHR_RELEASE_DIR` 和 `cd` 的目录名必须是本次收到的同一个精确名字。所有命令必须看到
`PASS`/`OK`，否则停止。然后必须在 manifest 看到：

```text
mysql_expected=8.0.45
baota_min_version=11.8
customer_domain=192.168.160.226
backend_address_default=0.0.0.0
backend_port_default=8080
site_port_default=23272
process_manager_default=baota
session_cookie_secure_default=false
archive_owner=root:root
```

并确认 `migration_files` 到达当前发布的最高版本。任一 SHA-256 失败，立即停止。

## 11. 先在宝塔创建数据库，让客户能看到并管理

进入「数据库 → MySQL → 添加数据库」：

| 字段 | 填写值 |
|---|---|
| 数据库名 | `shenzhou_hr` |
| 用户名 | `shenzhouhr_panel` |
| 密码 | 使用宝塔随机生成的强密码并妥善保存 |
| 字符集 | `utf8mb4` |
| 访问权限 | **本地服务器** |
| 添加至 | 本地服务器 |

点击确定后，数据库列表必须立即出现 `shenzhou_hr`。`shenzhouhr_panel` 是客户在面板中
管理/备份数据库的账号；Java 日常运行不会使用它。安装脚本稍后会另外创建最小权限的
`shenzhouhr_app` 和迁移专用 `shenzhouhr_migrator`。

用 MySQL root/DBA 核对新库此时只有一个本地面板账号拥有库级权限：

```sql
SELECT User, Host
FROM mysql.user
WHERE User IN (
  'shenzhouhr_panel', 'shenzhouhr_app', 'shenzhouhr_migrator'
)
ORDER BY User, Host;

SELECT GRANTEE, PRIVILEGE_TYPE
FROM information_schema.SCHEMA_PRIVILEGES
WHERE TABLE_SCHEMA = 'shenzhou_hr'
ORDER BY GRANTEE, PRIVILEGE_TYPE;
```

第一条可看到 `localhost`，也可能同时看到宝塔创建的 `127.0.0.1`，但只能是这 1～2 个
本地 `shenzhouhr_panel`，不能已经有两个服务账号；第二条中的每个 GRANTEE 也只能对应这
1～2 个本地面板账号。出现任何其他 `GRANTEE`、通配 Host 或旧服务账号就停止并回第 6 节
让 DBA 清理。安装器还会机器检查这些条件。

不要选择「所有人」，不要开放 3306，不要导入旧开发库或演示库。首次安装会在任何迁移
前强制检查：该数据库必须存在且为 0 张表、只有预期本地面板账号拥有权限，并且两个服务
用户名在任何 Host 都必须不存在；否则脚本停止，不能靠改密码或清权限强行覆盖。

## 12. 先在宝塔创建 23272 的纯静态站点

先在网站列表搜索站点名 `192.168.160.226`。若它已经属于其他业务，立即停止并联系交付人员；
不能继续创建，因为宝塔可能自动改名为 `192.168.160.226_23272`，而客户专用安装器只接受
已经核验的精确配置 `192.168.160.226.conf`。只有确认同名旧 HR 站点已按第一部分安全清理、
且当前没有同名记录后才继续。

进入「网站 → PHP 项目/HTML 项目 → 添加站点」：

| 字段 | 填写值 |
|---|---|
| 域名 | `192.168.160.226:23272` |
| 备注 | `kaoqin` |
| 根目录 | `/www/wwwroot/192.168.160.226` |
| FTP | 不创建 |
| 数据库 | 不创建（第 11 步已经建好） |
| PHP 版本 | 纯静态 |

自定义端口必须直接写在域名栏中。不要先创建 80 端口站点再修改，也不要开启 SSL。

创建后点「设置 → 配置文件」，先确认已有 `listen 23272;`，不能出现 `listen 80;`。
宝塔网站列表通常只显示站点名 `192.168.160.226` 和备注 `kaoqin`，端口在绑定域名中显示；
后面都按这个站点名查找。

终端再核对宝塔已经登记了精确配置和根目录：

```bash
test -f /www/server/panel/vhost/nginx/192.168.160.226.conf \
  && echo 'PASS：宝塔站点配置存在' || echo 'STOP：站点配置不存在'
grep -E '^[[:space:]]*(listen|root)[[:space:]]' \
  /www/server/panel/vhost/nginx/192.168.160.226.conf
```

必须看到 `listen 23272;` 和 `root /www/wwwroot/192.168.160.226;`。安装器找不到这个面板
配置就会提前拒绝，不会另建面板外的 `shenzhouhr.conf`。宝塔生成的 `.user.ini` 不要手工
删除，安装器会只对这个精确文件临时解除保护，更新前端后恢复。

## 13. 运行一次“宝塔托管模式”的准备脚本

若旧清理保留了 `shenzhouhr` 用户/组，先执行：

```bash
getent passwd shenzhouhr || true
getent group shenzhouhr || true
id shenzhouhr 2>/dev/null || true
```

账号不存在时安装器会创建；账号存在时必须是 UID 1～999、home 为 `/opt/shenzhouhr`、
 shell 为 `nologin`，只能属于唯一的 `shenzhouhr` 组。该组不能还有其他显式成员，也不能被
其他账号用作主组；系统装有 `sudo` 时，再由管理员执行 `sudo -l -U shenzhouhr`，若显示任何
可执行命令就先撤销。
安装器会再次检查账号/组隔离，不符合就停止，不能把运行账号加入 `www`、`sudo` 或其他组。

仍在新发布目录中，把 JDK 路径替换成第 9 步看到的真实路径，执行：

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

必须显式带上 `PROCESS_MANAGER=baota`。如果脚本提示不认识该模式，说明上传的是旧发布包，
不要继续。

脚本已经把客户 IP、前后端端口、数据库名和本机 MySQL 地址固定，不能在现场改成其他值。
它只会询问以下内容：

| 脚本问题 | 填写 |
|---|---|
| 管理员/公司信息 | 按客户确认内容填写，密码至少 12 位且含大小写、数字、符号 |
| MySQL root user | `root` |
| MySQL root password | 宝塔 MySQL root 密码，输入时屏幕不显示字符 |

脚本会在任何数据库写入前先收集并校验管理员/公司信息，再询问 MySQL root 凭据。全新库
必须创建首个生产管理员，客户专用脚本不会提供跳过选项；中途不要关闭终端。

脚本会执行：

- 再次校验发布包和 MySQL 8.0.45；
- 强制确认宝塔已经创建并登记的 `shenzhou_hr` 存在且为空；
- 把数据库默认字符集/排序规则设为 `utf8mb4/utf8mb4_0900_ai_ci`；
- 创建 `shenzhouhr_app` 和 `shenzhouhr_migrator`；
- 执行 Flyway V1 到当前发布最高版本；
- 创建首个生产管理员；
- 安装 JAR 到 `/opt/shenzhouhr/app/shenzhou-hr.jar`；
- 复制前端到 `/www/wwwroot/192.168.160.226`；
- 把 Nginx 配置改为监听 23272、代理到 8080；
- **不创建、不启动 systemd 服务**。

结束时应该看到：

```text
Baota preparation completed. Add and start the Java project in the panel now.
Open: http://192.168.160.226:23272
Backend listener: 0.0.0.0:8080
Baota Java project name: kaoqinweb
Domain / external mapping: leave blank
```

此时后端还没有启动，这是正常的；下一步才由宝塔启动。

如果脚本中途失败：不要再次执行 `install.sh`，也不要手工删除 env。若脚本明确说明“尚未
完成数据库准备”，它会自动恢复旧 vhost 并清理本次新建的应用目录，修正报错后才可重试；
若看到“database provisioning completed”警告，说明账号、env、迁移或管理员数据可能已经
写入，必须保留 `/etc/shenzhouhr`、`/opt/shenzhouhr` 和日志，按第一部分把它当作一次新的
“中断部署”盘点、备份和验证，再由交付人员决定修复还是重新清理。空库检查和 env 保护会
阻止盲目重跑，这是正常的安全保护。

## 14. 启动 Java 前先收紧防火墙和网络映射

必须在 Java 监听 `0.0.0.0:8080` **之前**完成本节，避免已有 NAT 规则造成短暂暴露。

1. 进入宝塔「安全 → 系统防火墙」，确认防火墙已启用；
2. 删除或禁用所有“来源=所有 IP、端口=8080”的旧允许规则；
3. 先添加批准来源的允许规则；若系统默认策略为允许，再在其后添加拒绝其他来源的规则；
4. 与网络管理员确认规则顺序，不能让“全部允许”排在白名单之前；
5. MySQL 3306 只允许本机，不得做 NAT。

| 策略 | 协议 | 服务器端口 | 来源 | 备注 |
|---|---|---:|---|---|
| 允许 | TCP | 23272 | 客户批准的办公网/VPN/专线网段 | 神州HR前端 |
| 允许 | TCP | 8080 | **仅批准的固定 IP、VPN 或网关来源** | 神州HR后端 |
| 拒绝 | TCP | 8080 | 所有 IP（默认策略已拒绝时无需重复） | 兜底拒绝后端直连 |

宝塔“来源”下拉通常只有“所有 IP / 指定 IP / 指定域名”，所以白名单行选择“指定 IP”并
填写批准地址，兜底拒绝行才选择“所有 IP”。若当前防火墙不能保证指定 IP 允许规则优先于
兜底拒绝，停止并改在客户路由器/上游防火墙实现白名单；不能删掉兜底拒绝后直接启动。
此时 Java 尚未启动，只核对规则已保存、顺序正确；正反向连通性必须等第 15 步项目启动后
立即测试。

不要为本项目开放 80、23273、3306：23273 是网络设备的外部端口，转发到服务器 8080；
服务器自身不监听 23273。

请网络管理员核对映射恰好为：

| 名称 | 线路 | 外部端口 | 内部端口 | 内部 IP | 协议 |
|---|---|---:|---:|---|---|
| kaoqin | WAN1 | 23272 | 23272 | 192.168.160.226 | TCP |
| kaoqinweb | WAN1 | 23273 | 8080 | 192.168.160.226 | TCP |

外部 23273 直达 Java 会绕过 Nginx 的安全头和代理控制，并暴露同端口 Actuator，所以
必须限制为批准来源。前端自身只通过 Nginx `/api/` 就能工作；若没有系统必须直连后端，
由客户网络管理员评估删除 `kaoqinweb` 映射。

本手册的 HTTP 模式只适用于受控内网、VPN 或专线。若 WAN1 面向公共互联网，不能继续
启动 Java：应先由网络安全负责人落实 TLS 和网关方案；仅做 IP 白名单并不能加密 HR 数据。

## 15. 在宝塔添加真正可管理的 Java 项目

进入「网站 → Java 项目/Spring Boot → 添加项目」：

| 字段 | 填写值 |
|---|---|
| 项目类型 | Spring Boot / JAR |
| 项目名称 | `kaoqinweb` |
| 项目 JAR | `/opt/shenzhouhr/app/shenzhou-hr.jar` |
| 项目 JDK | 第 9 步的 JDK 21 |
| 运行用户 | `shenzhouhr` |
| 项目端口 | `8080` |
| 端口放行/防火墙放行 | **关闭、不勾选**（第 14 步已按来源配置） |
| 工作目录 | 界面有此项时填 `/opt/shenzhouhr/app`；没有则由 JAR 目录自动确定 |
| 环境变量 | 选择“从文件加载” |
| 环境文件 | `/etc/shenzhouhr/shenzhouhr.env` |
| 开机启动 | 新建页有此项就开启；否则创建后在项目设置中开启 |
| 项目日志 | 新建页有此项就开启；否则创建后在项目设置中开启 |
| 绑定域名 | **留空** |
| 外网映射/反向代理 | **关闭，不要点击** |

如果当前面板没有“从文件加载环境变量”，说明第 1、9 步的版本/功能门禁没有通过：停止，
不要创建项目，也不要把数据库密码拼进启动命令。面板升级必须另做配置/数据库备份、审批和
回退验证；本停机窗口内不能临时升级。确需使用多行环境变量框时，只能由客户管理员另行做
安全评审，本手册默认不允许。

启动命令中的 JDK 路径替换为实际值：

```text
/www/server/java/实际JDK21目录/bin/java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -jar /opt/shenzhouhr/app/shenzhou-hr.jar
```

创建前可以安全核对端口配置，不会显示密码：

```bash
grep -E '^SHENZHOUHR_SERVER_(ADDRESS|PORT)=' /etc/shenzhouhr/shenzhouhr.env
```

必须看到：

```text
SHENZHOUHR_SERVER_ADDRESS=0.0.0.0
SHENZHOUHR_SERVER_PORT=8080
```

点击「确定/创建」后，当前宝塔版本通常会立即启动项目；若列表状态不是“运行中”，再点击
「启动」。随后在项目设置中核对开机启动和日志均已开启，并查看日志直到出现
`Started ShenzhouHrApplication`。

创建后立即回到「安全 → 系统防火墙」复查：不得出现“策略=允许、端口=8080、来源=所有
IP”的新规则。若宝塔自动添加了该规则，先在 Java 项目页停止 `kaoqinweb`，删除这条全开放
规则并恢复第 14 步白名单/拒绝规则，再启动项目。不要在后端全开放的状态下继续验收。

规则复查通过后，立即做两次外部测试：批准来源访问
`http://<WAN地址>:23273/actuator/health` 必须成功；未批准来源必须超时或被拒绝。若未批准
来源也能访问，立即在宝塔停止 `kaoqinweb`，由网络管理员修正规则后再启动。

为什么“绑定域名/外网映射”必须留空：该功能会让宝塔额外生成一套 Nginx 反向代理，
容易重新占用 80 或覆盖现有站点。当前外部 23273 → 内部 8080 由客户网络设备完成，
不是由宝塔 Java 外网映射完成。

## 16. 核对 Nginx 完整配置

进入宝塔「网站」，找到站点名 `192.168.160.226`（备注 `kaoqin`），再进入「设置 →
配置文件」。绑定域名显示为 `192.168.160.226:23272`。安装脚本已经生成配置；逐项核对
以下关键值：

```nginx
server {
    listen 23272;
    server_name 192.168.160.226;
    server_tokens off;

    root /www/wwwroot/192.168.160.226;
    index index.html;
    # 默认 combined 日志会记录查询串；HR 站点关闭访问日志以免写入人员查询条件。
    access_log off;
    error_log /www/wwwlogs/192.168.160.226.error.log;
    client_max_body_size 21m;
    client_body_timeout 15s;
    client_header_timeout 15s;
    keepalive_timeout 30s;
    send_timeout 60s;

    set $shenzhouhr_cache_control "no-store, max-age=0";

    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "same-origin" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
    add_header Cross-Origin-Opener-Policy "same-origin" always;
    add_header Cross-Origin-Resource-Policy "same-origin" always;
    add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; img-src 'self' data:; font-src 'self'; style-src 'self' 'nonce-$request_id'; script-src 'self'; connect-src 'self'" always;
    add_header Cache-Control $shenzhouhr_cache_control always;

    location ~ (^|/)\.(?:user\.ini|env(?:[./]|$)|git(?:/|$)|svn(?:/|$)|hg(?:/|$)) {
        return 404;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_hide_header Cache-Control;
        proxy_hide_header Expires;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Correlation-ID $request_id;
        proxy_set_header Connection "";
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
        proxy_buffering off;
    }

    location /assets/ {
        try_files $uri =404;
        set $shenzhouhr_cache_control "public, max-age=31536000, immutable";
    }

    location / {
        try_files $uri $uri/ /index.html;
        etag off;
        sub_filter __CSP_NONCE__ $request_id;
        sub_filter_once off;
    }
}
```

配置中不能出现 `listen 80`，也不能把 `proxy_pass` 写成外部端口 23273。保存后点击
Nginx「重载配置」，或在终端执行：

```bash
if /www/server/nginx/sbin/nginx \
    -c /www/server/nginx/conf/nginx.conf -t; then
  /www/server/nginx/sbin/nginx \
    -c /www/server/nginx/conf/nginx.conf -s reload
else
  echo 'STOP：Nginx 配置检查失败，未 reload'
fi
```

第一条必须显示 `syntax is ok` 和 `test is successful`。

本客户站点使用经过校验的完整固定配置，日常可在宝塔查看、编辑和重载；不要点击站点的
“SSL”“伪静态/重写”“反向代理”向导，它们可能重写固定的 23272/8080 拓扑。公网 TLS 或
新增 rewrite 必须先由交付与安全负责人出新配置并重新验收，不能在面板中临时打开。

---

# 第三部分：验收、日常管理和升级

## 17. 技术验收

### 17.1 面板验收

逐项确认：

- 软件商店：Nginx、MySQL、JDK 21 均“运行中/已安装”；
- 网站：站点名 `192.168.160.226`（备注 `kaoqin`），绑定 `192.168.160.226:23272`，状态正常；
- Java 项目：`kaoqinweb` 状态“运行中”，能从面板停止、启动、重启、查看日志；
- 数据库：`shenzhou_hr` 能从面板点「管理」「备份」。

### 17.2 端口验收

```bash
ss -lntp | grep -E ':(8080|18080|23272|23273|80)\b' || true
```

本项目的正确结果：

- Nginx 监听 `*:23272`；
- Java 监听 `0.0.0.0:8080`；
- 没有旧 Java 监听 18080；
- 本项目没有进程监听 23273；
- 本项目没有使用 80。服务器如果有其他网站使用 80，只记录归属，不要擅自停止。

### 17.3 本机健康验收

```bash
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error http://192.168.160.226:23272/ >/dev/null
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  http://192.168.160.226:23272/api/v1/auth/session
```

第一条应返回包含 `"status":"UP"`；第二条不报错；第三条未登录时通常返回 401，说明
Nginx 已经能把 `/api/` 转给 Java，而不是 502。

再运行发布包自带验证：

```bash
cd /opt/shenzhouhr-releases/本次精确发布目录
JAVA_BIN=/www/server/java/实际JDK21目录/bin/java \
MYSQL_BIN=/www/server/mysql/bin/mysql \
bash deploy/baota/scripts/verify.sh \
  --env-file /etc/shenzhouhr/shenzhouhr.env \
  --migrator-env-file /etc/shenzhouhr/shenzhouhr-migrator.env \
  --health-url http://127.0.0.1:8080/actuator/health
```

### 17.4 浏览器验收

1. 内网打开 `http://192.168.160.226:23272`；
2. 使用第 13 步创建的管理员登录；
3. 首次登录修改密码；
4. 至少打开首页、员工、组织、考勤配置和报表页面；
5. 打开浏览器开发者工具，确认 `/api/v1/...` 请求发往前端同一地址的 23272，且没有 502。

由网络外部且来源已授权的电脑测试：

```text
http://<WAN地址>:23272
http://<WAN地址>:23273/actuator/health
```

第二个地址只应对批准来源可达。

## 18. 日常操作全部从宝塔完成

### Java

进入「网站 → Java 项目 → kaoqinweb」：

- 启动、停止、重启；
- 查看实时日志和历史日志；
- 查看 CPU、内存和进程状态；
- 修改 JDK、启动参数或环境文件后再重启。

不要再执行 `systemctl start/restart shenzhouhr`；新方案没有这个服务。

### 数据库

进入「数据库 → MySQL → shenzhou_hr」：

- 「管理」进入 phpMyAdmin；
- 「备份」立即备份；
- 查看数据库大小；
- 修改面板管理账号密码时，不要误改 Java 使用的 `shenzhouhr_app`。

### Nginx/前端

进入「网站 → 站点名 192.168.160.226（备注 kaoqin）」：

- 配置文件：查看 23272 和 `/api/` 代理；
- 日志：查看 Nginx 错误日志；访问日志为避免记录 HR 查询串已关闭，业务操作查 Java
  审计/项目日志；
- 文件：前端目录 `/www/wwwroot/192.168.160.226`。

## 19. 配置自动备份

进入「计划任务 → 添加任务」：

### 数据库每日备份

| 字段 | 值 |
|---|---|
| 任务类型 | 备份数据库 |
| 数据库 | `shenzhou_hr` |
| 周期 | 每天 02:00 |
| 保留份数 | 至少 14 份 |

保存后立即点一次「执行」，再到数据库备份列表确认产生了非空备份并下载抽查。

### 网站文件每周备份

| 字段 | 值 |
|---|---|
| 任务类型 | 备份网站 |
| 网站 | `192.168.160.226`（备注 `kaoqin`） |
| 周期 | 每周日 03:00 |
| 保留份数 | 至少 4 份 |

JAR 和环境文件在每次升级前单独备份。环境备份含密码，只允许 root/受控备份系统读取。

## 20. 后续版本升级

升级仍由宝塔管理 Java，不能直接运行旧 systemd 版升级脚本。

1. 上传新包和 `.sha256`；
2. 按第 10.2 节完成外层、内层两次 SHA-256；
3. 在宝塔备份并下载 `shenzhou_hr`；
4. 备份 `/opt/shenzhouhr/app`、前端目录和 `/etc/shenzhouhr`；
5. 在宝塔「Java 项目 → kaoqinweb」点击停止；
6. 确认 `ss -lntp | grep ':8080\b'` 没有输出；
7. 进入新发布目录执行：

```bash
PROCESS_MANAGER=baota \
JAVA_BIN=/www/server/java/实际JDK21目录/bin/java \
MYSQL_BIN=/www/server/mysql/bin/mysql \
bash upgrade.sh
```

脚本会在任何写入前确认旧 Java 已停止，再检查 MySQL/Flyway 历史并备份旧 JAR/前端；
它先用待发布 JAR 执行前向迁移，成功后才原子替换活动 JAR。迁移失败时旧 JAR 文件虽然
保持不动，但某些 MySQL DDL 可能已经部分落库，**绝不能因此启动旧 JAR**；保留数据库备份、
新包和日志，交给交付人员判断恢复或继续迁移。
完成后：

1. 回到宝塔「Java 项目 → kaoqinweb」点击启动；
2. 查看日志直到启动成功；
3. 重做第 17 节全部验收；
4. 确认新版本稳定后再清理旧发布包。

禁止事项：

- 不再次执行首次安装；
- 不使用 `flyway clean`；
- 不修改已经执行过的旧迁移 SQL；
- 不把开发数据库整库覆盖客户库；
- 数据库迁移失败时不要反复点启动，保留日志并联系交付人员。

## 21. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Java 项目列表没有 `kaoqinweb` | 仍在用旧 systemd 方案 | 按第 15 步添加，先确认旧 service 已删除 |
| 数据库实际存在但列表没有 | CLI 建库未登记到面板 | 「读取本地数据库 → 从服务器同步到面板」 |
| 安装提示 legacy service exists | 旧 `shenzhouhr.service` 未清干净 | 回第 3、7、8 步核对，不能绕过保护 |
| 安装提示 database is not empty | 新库已有表或选错库 | 停止；回第 4、6、11 步核对，禁止覆盖 |
| 安装提示 service account found | 旧账号未清或账号被共享 | 由 DBA 按第 6.2 步处理，脚本不会改密/清权 |
| 安装提示 Baota site was not found/root/listen 不符 | 面板站点未按第 12 步登记 | 回宝塔修正站点，不要手建 `shenzhouhr.conf` |
| 安装提示 backend port in use | 8080 有旧 Java/其他服务 | 用 `ss` 查 PID 和 JAR，不能直接杀所有 Java |
| 前端打不开 | Nginx 未监听 23272或防火墙未放行 | 查站点状态、`nginx -t`、23272 规则 |
| 页面打开但接口 502 | Java 未启动或 Nginx 代理写错 | Java 日志；确认代理为 `127.0.0.1:8080` |
| 外部 23273 不通 | Java 只监听 127.0.0.1、NAT/来源规则错误 | env 必须 `0.0.0.0:8080`，再查网络规则 |
| 登录后仍跳回登录页 | HTTP 环境误启 Secure Cookie | `SHENZHOUHR_SESSION_COOKIE_SECURE=false` 后重启 Java |
| MySQL 版本不符 | 面板安装的不是精确 8.0.45 | 停止，不要绕过版本检查 |
| Nginx 提示端口占用 | 23272 已被其他站点监听 | 在网站列表核对归属，不能随意停其他站点 |
| 升级提示 8080 still listening | Java 项目未从宝塔停止 | 回宝塔停止 `kaoqinweb` 后重试 |

收集故障信息时只提供：报错文本、Java 项目日志中不含密码的部分、Nginx 错误日志、
`ss` 结果和 `BUILD-MANIFEST.txt`。不要提供 env 文件或数据库密码。

---

## 22. 宝塔官方操作参考

- [Linux Java/Spring Boot 项目部署](https://docs.bt.cn/practical-tutorials/Java-Project-Deployment-Tutorial)
- [已有 JAR 纳入 Java 项目管理](https://docs.bt.cn/api/java/create_spring_boot_project)
- [Java 项目从环境文件加载变量](https://docs.bt.cn/api/java/check_env_for_project)
- [添加 MySQL 数据库](https://docs.bt.cn/user-guide/database/mysql/add)
- [接管并同步服务器已有 MySQL](https://docs.bt.cn/user-guide/database/mysql/takeover-mysql)
- [用自定义端口添加站点](https://docs.bt.cn/user-guide/site/php/create-web)
- [接管服务器已有 Nginx](https://docs.bt.cn/user-guide/site/nginx-takeover)
- [宝塔系统防火墙端口规则](https://docs.bt.cn/user-guide/security/firewall/port-rule)

这些官方页面的界面文字可能随宝塔版本略有变化；本项目的 IP、端口、JDK、MySQL、
Nginx 和进程管理要求以本手册为准。

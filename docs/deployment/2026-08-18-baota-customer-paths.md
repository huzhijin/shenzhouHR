# 宝塔部署（按客户现网路径）

适用本机发布包 `shenzhouhr-release-20260818-login-vision.tar.gz`。  
**按你机器上已经在用的目录写，不要另建一套路径。**

| 用途 | 现网路径 |
|---|---|
| 后端目录 | `/opt/shenzhouhr` |
| 后端 jar | `/opt/shenzhouhr/app/shenzhou-hr.jar` |
| 后端日志 | `/opt/shenzhouhr/logs` |
| 数据库备份 | `/opt/shenzhouhr/backups` |
| 前端网站 | `192.168.160.226` |
| 前端根目录 | `/www/wwwroot/192.168.160.226` |

密钥只写在服务器脚本里。不要发到聊天或 git。

---

## 0. 这次必须改的两件事

旧启动参数是错的，得力和 OA 不会启用：

| 不要再用 | 必须改成 |
|---|---|
| `--deli-eplus.enabled` / `app-key` / `app-secret` | `--shenzhouhr.integrations.deli-eplus.enabled` / `app-key` / `app-secret` |
| `--shenzhouhr.oa.datasource.url` / `username` / `password` | `--shenzhouhr.integrations.oa-mysql.jdbc-url` / `username` / `password` |
| `--spring.flyway.enabled=false` | **`true`**。否则库永远停在 V35 |

禁止 `checkin_query_init`。年结任务保持关。不要给 OA 插件授余额过程。

---

## 1. 上传发布包

1. 打开宝塔 **文件**。
2. 进入 `/opt/shenzhouhr`。
3. **上传** `shenzhouhr-release-20260818-login-vision.tar.gz`。
4. 点文件右侧 **解压**，解到 `/opt/shenzhouhr/release/20260818-login-vision/`。

解压后至少应有：

```text
backend/shenzhou-hr.jar
web/                 （含 index.html 和 assets/）
db/migration/        （V1–V49）
deploy/baota/env/start-prod.sh.example
docs/deployment/2026-08-18-baota-customer-paths.md
```

也可在 **终端** 解压：

```bash
mkdir -p /opt/shenzhouhr/release
cd /opt/shenzhouhr/release
tar --no-same-owner -xzf /opt/shenzhouhr/shenzhouhr-release-20260818-login-vision.tar.gz
```

---

## 2. 停后端

宝塔 **终端**：

```bash
ps aux | grep shenzhou-hr.jar | grep -v grep
kill -15 <PID>
sleep 2
ps aux | grep shenzhou-hr.jar | grep -v grep
```

没有进程后再继续。若你是用宝塔 **Java 项目管理** 或 **Supervisor** 起的，在对应页面点停止。

---

## 3. 备份数据库

现网备份目录是 `/opt/shenzhouhr/backups`（不是 backup）。

宝塔 **数据库** → 选 `shenzhou_hr` → **备份**。  
或终端：

```bash
mkdir -p /opt/shenzhouhr/backups
mysqldump -uroot -p --single-transaction --routines --triggers \
  shenzhou_hr > /opt/shenzhouhr/backups/shenzhou_hr-before-v49-$(date +%Y%m%d%H%M).sql
ls -lh /opt/shenzhouhr/backups/
```

dump 为空或只有几 KB 就停，不要换 jar、不要迁库。

---

## 4. 核对当前 Flyway

宝塔 **数据库** → `shenzhou_hr` → **管理**，执行：

```sql
SELECT version, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 15;
```

现在最高应是 **35**。已经高于 35 先停下来对一下，不要盲目再迁。

---

## 5. 覆盖后端 jar

```bash
cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
  /opt/shenzhouhr/backups/shenzhou-hr.jar.before-20260818
cp /opt/shenzhouhr/release/shenzhouhr-release-20260818-login-vision/backend/shenzhou-hr.jar \
  /opt/shenzhouhr/app/shenzhou-hr.jar
ls -lh /opt/shenzhouhr/app/shenzhou-hr.jar
```

---

## 6. 覆盖前端网站

网站根目录是 `/www/wwwroot/192.168.160.226`。先备份再整目录替换，避免浏览器继续吃旧 hash 文件。

```bash
TS=$(date +%Y%m%d%H%M)
mkdir -p /opt/shenzhouhr/backups
tar -czf /opt/shenzhouhr/backups/web-192.168.160.226-$TS.tar.gz \
  -C /www/wwwroot 192.168.160.226

rm -rf /www/wwwroot/192.168.160.226/*
cp -a /opt/shenzhouhr/release/shenzhouhr-release-20260818-login-vision/web/. \
  /www/wwwroot/192.168.160.226/
ls /www/wwwroot/192.168.160.226/index.html \
   /www/wwwroot/192.168.160.226/assets/szst-vision-panel-*.jpg
```

宝塔 **网站** 里站点 `192.168.160.226` 不用删、不用重建。  
**设置 → 配置文件** 里应已有 `/api/` 反代到本机 Java（常见是 `127.0.0.1:9090` 或 `8080`）。端口必须和下面启动脚本一致。

反代片段参考（端口按你现网改）：

```nginx
location ^~ /api/ {
    proxy_pass http://127.0.0.1:9090/api/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Connection "";
}
```

站点应保留 SPA 回退：非文件请求回到 `index.html`。

---

## 7. 用正确参数启动（这次打开 Flyway）

把包里的模板拷到现网并改密钥：

```bash
mkdir -p /opt/shenzhouhr
cp /opt/shenzhouhr/release/shenzhouhr-release-20260818-login-vision/deploy/baota/env/start-prod.sh.example \
  /opt/shenzhouhr/start-prod.sh
chmod 700 /opt/shenzhouhr/start-prod.sh
```

用宝塔 **文件** 编辑 `/opt/shenzhouhr/start-prod.sh`，只改占位符：

- `REPLACE_HR_DB_PASSWORD`
- `REPLACE_PEPPER`
- `REPLACE_DELI_APP_KEY` / `REPLACE_DELI_APP_SECRET`
- `REPLACE_OA_PASSWORD`

核对这几项已经是 **对的**：

```text
--spring.flyway.enabled=true
--shenzhouhr.integrations.deli-eplus.enabled=true
--shenzhouhr.deli.auto-sync-enabled=true
--shenzhouhr.integrations.oa-mysql.enabled=true
--shenzhouhr.oa.auto-sync-enabled=true
```

`--server.port` 必须和网站反代目标一致（现网若一直是 9090，就保持 9090）。

启动：

```bash
/opt/shenzhouhr/start-prod.sh
tail -f /opt/shenzhouhr/logs/shenzhouhr.log
```

日志里应出现 `Migrating schema to version "36"` … `"49"`。  
不要出现 `INTEGRATION_DISABLED`。

---

## 8. 迁完核对

```sql
SELECT version, success FROM flyway_schema_history
WHERE version IN ('41','42','43','44','48','49');

SHOW TABLES LIKE 'punch_correction_request';
SHOW COLUMNS FROM oa_attendance_document LIKE '%serial%';
SELECT principal_id, status FROM auth_principal
WHERE principal_id IN ('SYSTEM','41000000-0000-0000-0000-000000000001');
```

`41–49` 都是 `success=1`，上面对象都在，才继续。

---

## 9. 浏览器验收

1. 打开 `http://192.168.160.226/`，**Ctrl+F5** 强刷。
2. 登录页右侧是愿景 / 使命 / 价值观 / 精神海报，左侧接缝处不应再露出残字。
3. 用现网账号登录。
4. **考勤机数据**：看上次失败原因，点 **手动同步** 得力和 OA。
5. **OA 单据** 条数必须 > 0。
6. 部门大约 55，人员大约 597。
7. **考勤报表** 选 2026-08：未关账会标「暂算」；班次是真名；抽 3–5 人对打卡、请假、销假、加班。

---

## 10. 今天不要做

- 不要开 `SHENZHOUHR_TIME_OFF_YEAR_END_ENABLED`
- 不要给 OA 插件授 `szsc_oa_*` 过程，不要绑请假/加班余额事件
- 不要在未备份时迁库
- 不要把 key/secret 写进网站目录或 git

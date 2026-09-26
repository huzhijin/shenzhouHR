# 宝塔升级包 20260911-roster-import

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。不要重跑 `20260903-szt-oa-rematch`。

本包同时包含：

- 人员/部门新建修好（建人必须选部门；部门编码可空）
- 旧「期初导入」前台隐藏，换成花名册格式导入（部门和人一张表）
- 9 月神州 + 聚能新人导入文件（部署后上传发布）
- session `01a08182` 的重算加速：V67 OA 窗口索引、投影批量写入
- 360 浏览器 SVG 图标兼容（数字 size，去掉 meta `frame-ancestors`）

发布包：`release-candidates/shenzhouhr-release-20260911-roster-import.tar.gz`

SHA256：`2ab1f5563075f8246a12ada1750166e0824dfd14cc22d24adee973161af56c85`

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260911-roster-import.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/release-candidates/upgrade-roster-import.sh \
    root@服务器:/root/
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260911-roster-import.tar.gz
# 必须是
# 2ab1f5563075f8246a12ada1750166e0824dfd14cc22d24adee973161af56c85

chmod +x /root/upgrade-roster-import.sh
bash /root/upgrade-roster-import.sh
```

启动会跑 Flyway：**V67**（若上次重算试跑已跑过会跳过）、**V68**（花名册导入批次表）。V67 加索引若还没做过，可能要几分钟，不要中途杀进程。

健康检查出现 `401` 后浏览器 **Ctrl+F5**（360 和 Chrome 各开一次更好）。

## 3. 导入 9 月新人（必须）

脚本会把花名册放到 `/root/september-2026-hires.xlsx`。

1. 打开 **组织与员工 → 导入人员**
2. 下载模板可核对表头：序号、公司名称、工号、姓名、一级部门、二级部门、三级部门、组别、职位、入职日期
3. 上传 `/root/september-2026-hires.xlsx`（也可本机传这个文件），原因填「9月入职导入」
4. 预检无阻断冲突后再 **确认发布**

发布后查询：

| 人 | 期望 |
|---|---|
| 张立强 `SZST0743` | 江苏神州 · 技术支持中心 / 现场服务部 / 武汉产品服务组 |
| 高露浩 `SZJN0042`、王明鑫 `SZJN0044`、马泽成 `SZJN0045`、韦天宇 `SZJN0046` | 聚能 · 研发一部（对齐原 RD1）下硬件组/测试组 |
| 金凯雯 `SZJN0043` | 聚能 · 人事行政部 |

聚能不要出现第二棵「研发一部」根。这批人**不会自动开登录账号**。

## 4. 不要做

- 不要覆盖 `start-prod.sh`
- 不要回拨 OA 水位
- 不要改得力/OA cron
- 不要 `run-oa-resync.sh`
- 关账月不要重开
- 人员导入本身不要求整月重算

若要顺带看重算是否变快，另开窗口只试江苏一家 OPEN 月，不要一次全公司：

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  nohup bash /root/recalculate-open-month.sh \
  >> /root/recalculate-20260911-roster.log 2>&1 &
grep -E 'recalc-stage|recalc-persist|Migrating schema to version' \
  /opt/shenzhouhr/logs/shenzhouhr.log | tail -30
```

## 回滚

```bash
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 2
ls /opt/shenzhouhr/backups/shenzhou-hr.jar.before-roster-import-* | tail -1
cp -a /opt/shenzhouhr/backups/shenzhou-hr.jar.before-roster-import-时间戳 \
  /opt/shenzhouhr/app/shenzhou-hr.jar
# 前端备份：
# ls /opt/shenzhouhr/backups/web-*-before-roster-import-*.tar.gz | tail -1
/opt/shenzhouhr/start-prod.sh
```

V67/V68 若已迁库，留着无害，不必删。已发布进库的人员/部门不会随回滚 jar 自动消失。

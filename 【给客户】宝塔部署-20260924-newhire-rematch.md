# 宝塔升级包 20260924-newhire-rematch

新人晚录入后，自动补取得力里已经打过、当时对不上人的卡，并重算涉及到的公司当月。只换 jar。

**不覆盖** `start-prod.sh`。不覆盖前端。不要回拨得力或 OA 水位。不要把 `SHENZHOUHR_DELI_REPLAY_ENABLED` 打开。不要手点整月重算。

发布包：`release-candidates/shenzhouhr-release-20260924-newhire-rematch.tar.gz`

## 本包做什么

| 项 | 说明 |
| --- | --- |
| 新建员工 / 花名册导入 / 期初导入 | 保存成功后约 2 分钟，向得力重读从最早生效日到今天的打卡。同一批人只跑一次。 |
| 隔离卡 | 只补还没生效的卡。已经记在别人名下的卡不动。水位不回拨。 |
| 重算 | 只重算这次补上了卡的公司当月。 |
| 每天 21:30 | 再扫最近 31 天，补上当时没进考勤组、或进程重启丢掉的那次。窗口最长 62 天。 |
| 开关 | jar 默认打开。现场 `start-prod.sh` 不读 env，所以不能只改 env。脚本仍会把开关写进 env。 |

生效日必须是真实入职日，并且入职当天已经在考勤组里、有班次。写成今天，前几天的卡对不上。人先保存、组后绑的，当次可能仍隔离，21:30 会再认一次。

保存之后新打的卡，仍靠原来的 10 分钟增量同步。

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260924-newhire-rematch.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/docs/deployment/2026-09-24-newhire-rematch-upgrade.sh \
    root@服务器:/root/
```

服务器上改名：

```bash
mv /root/2026-09-24-newhire-rematch-upgrade.sh /root/upgrade-newhire-rematch.sh
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260924-newhire-rematch.tar.gz
# 必须是
# 064b569c178de1b5a875ecae88c6bcda502fcb5093f293b3bcdb6d0411c9c4ff

chmod +x /root/upgrade-newhire-rematch.sh
bash /root/upgrade-newhire-rematch.sh
```

健康检查出现 `401`，并且日志有 `Deli quarantine rematch enabled=true`，才算完成。浏览器不用刷新。

## 3. 已经录入的这批人

部署时若已过当天 21:30，这批人要等到次日 21:30 才会自动补。

想马上补：再打开其中一名员工保存一次，或再录一名生效日不晚于这批最早入职日的人。大约 2 分钟后按最早生效日重读。看日志：

```bash
grep 'Deli quarantine rematch' /opt/shenzhouhr/logs/shenzhouhr.log | tail -20
```

`promoted=` 大于 0 表示有卡被补上。

## 4. 不要做的

- 不要覆盖 `start-prod.sh`
- 不要覆盖前端
- 不要回拨得力或 OA 水位
- 不要把 `SHENZHOUHR_DELI_REPLAY_ENABLED` 改成 `true`
- 不要手点整公司整月重算

## 5. 回滚

```bash
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 2
ls /opt/shenzhouhr/backups/shenzhou-hr.jar.before-newhire-rematch-* | tail -1
# 把上面那份拷回
cp -a /opt/shenzhouhr/backups/shenzhou-hr.jar.before-newhire-rematch-时间戳 \
  /opt/shenzhouhr/app/shenzhou-hr.jar
/opt/shenzhouhr/start-prod.sh
```

## 校验和

```
064b569c178de1b5a875ecae88c6bcda502fcb5093f293b3bcdb6d0411c9c4ff
```

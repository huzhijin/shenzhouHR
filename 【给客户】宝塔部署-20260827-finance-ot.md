# 宝塔升级包 20260827-finance-ot

财务加班独立报表、人事改加班小时/取消异常、年假调休按 OA 重算。

发布包：`release-candidates/shenzhouhr-release-20260827-finance-ot.tar.gz`

SHA256：`268e27f5d53a9e5fef13fd0877773c6d8b27602aa82e66293cad61943249265a`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-finance-ot.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260827-finance-ot.tar.gz`
- `release-candidates/upgrade-finance-ot.sh`

## 这次会改什么

查询报表（新增一张，旧表不动）：

- 新增「财务加班」：一人一行，部门 / 工号 / 加班人 / 平时加班 / 周末加班 / 节假日加班，后面按日列小时（日期 + 星期 1–7），导出同布局带总计
- 「加班日报」仍是人×日三列，「加班统计」仍是一单一行

人事调整：

- 考勤日报「改打卡」可填加班小时（0.5 步进），可勾选取消迟到 / 早退 / 缺卡 / 旷工
- 保存后重算 OPEN 月，覆盖进报表

年假 / 调休：

- 年休假、调休额度页「按 OA 重算」：按当年 OA 年假已休、调休已休、加班转调休重写台账，再刷新当月报表

有 Flyway **V63**。启动时会自动迁库。OPEN 月建议再点一次「重新计算」。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

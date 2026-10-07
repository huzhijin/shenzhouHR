# 宝塔升级包 20260825-export-ot-self

合并三段改动：

- 报表导出 500 / 查询页导出 409、员工工作台只看本人异常（session `01a036b4-ff1e-7b23-8ac6-3726b4b6e30d`）
- 加班小时按 OA 单据取整后计算（session 加班单 1.33h / 小时为 0）

发布包：`release-candidates/shenzhouhr-release-20260825-export-ot-self.tar.gz`

SHA256：`bc2c6ec11c278eebdb50656e39ea14727fceb6d4bba6cc359ede8352b4e27f62`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-export-ot-self.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260825-export-ot-self.tar.gz`
- `release-candidates/upgrade-export-ot-self.sh`

## 这次会改什么

导出（部署后立刻可用，不必等重算）：

- 考勤报表中心导出不再因矩阵组装失败整次 500
- 查询报表导出指纹与导出接口对齐，不再 409「绑定过期」
- 补签导出走请假类型，不再误传 `MAKEUP`

员工（部署后立刻可用）：

- `SZST0056` 等工作台 / 我的考勤不再 500
- 工作台标题「我的异常」，只显示登录人自己的异常（默认先看昨天）

加班小时（必须重算 2026-08 才进钉住结果）：

- 小时看 OA 加班单取整后的起止，不再用打卡配对
- `:01–:29` → 整点，`:31–:59` → 半点
- 周六日/节假日盖住 12:00–13:00 才扣 1 小时午休；晚上加班不扣午休
- 夏令晚餐盖住 18:00–18:30 扣 0.5 小时
- 有单无卡也会出时长；虚假加班仍报异常，但不把小时写成 0
- 小时只显示 0 / 0.5 / 1 / 1.5…

没有新的 Flyway 脚本。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 部署后必须重算 2026-08

浏览器 **Ctrl+F5**。

导出和员工工作台部署完马上能验。加班小时要等重算。只重算江苏神州：

```bash
nohup env COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  bash /root/recalculate-open-month.sh >> /root/recalculate-js.log 2>&1 &
tail -f /root/recalculate-js.log
```

全部 OPEN 公司：

```bash
nohup bash /root/recalculate-open-month.sh >> /root/recalculate-open-month.log 2>&1 &
tail -f /root/recalculate-open-month.log
```

成功行类似：`完成 ... 成功=1 失败=0`。看完 `Ctrl+C` 停掉 tail，不要停后台重算。

不要手工 `DELETE FROM attendance_report_exception_fact`。不要自动重开关账月。

## 验收

1. 管理员：考勤报表中心导出月明细 / 加班 / 工时，不再 toast「导出失败」
2. 管理员：查询报表加班统计点导出，不再 409
3. `SZST0056` / `Admin@123123`：工作台打开「我的异常」，只看到自己的异常；我的考勤能打开
4. 重算后加班统计：工作日 `18:00–21:00` 夏令 → 2.5；周六白天 `08:30–17:00` → 7.5；周六晚上 `18:00–21:00` → 2.5；以前「人留下了小时仍是 0」的晚上单应变为单据时长减餐

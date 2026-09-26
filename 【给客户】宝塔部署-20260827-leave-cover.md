# 宝塔升级包 20260827-leave-cover

请假覆盖漏刷、考勤明细本月日期筛选（中文日历）。也带上上一包的财务加班、人事改加班小时、年假调休按 OA 重算。

发布包：`release-candidates/shenzhouhr-release-20260827-leave-cover.tar.gz`

SHA256：`73cba507e13e8901e22d84e5b0b08a9f2a827648abfaae78ce0900267d1ae472`

## 你只需要跑这些命令

两个文件都传到服务器 `/root`：

- `release-candidates/shenzhouhr-release-20260827-leave-cover.tar.gz`
- `release-candidates/upgrade-leave-cover.sh`

宝塔终端：

```bash
bash /root/upgrade-leave-cover.sh
```

等脚本打印「部署完成」后，浏览器 **Ctrl+F5**。

让本月报表用上「请假不再算漏刷」，再跑：

```bash
nohup bash /root/recalculate-open-month.sh >> /root/recalculate-open-month.log 2>&1 &
tail -f /root/recalculate-open-month.log
```

看完日志 `Ctrl+C` 退出 tail，后台重算继续。也可以在页面点「重新计算本月」，二选一即可。

## 这次会改什么

- 同一天已有请假单：异常总览不再出漏刷/缺卡/旷工，考勤明细格子显示假别，日报也不再写漏刷
- 考勤明细日期筛选只允许当前月份；选到别的月会弹窗「暂不支持跨月」
- 日历中文（例如 2026年8月1日）
- 仍包含：查询页「财务加班」、考勤日报改打卡可改加班小时/取消异常、年休假/调休「按 OA 重算」

无新 Flyway。不要改现网路径、不要覆盖 `start-prod.sh`。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

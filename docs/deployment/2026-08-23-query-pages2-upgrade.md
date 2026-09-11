# 查询报表分块筛选与调休额度 20260823-query-pages2

发布包：`release-candidates/shenzhouhr-release-20260823-query-pages2.tar.gz`

SHA256：`14ba824041115732cbdd769a9f718a537a8312bcd82c36f33d8b360bc6c697b8`

把 `shenzhouhr-release-20260823-query-pages2.tar.gz` 和 `upgrade-query-pages2.sh` 都传到 `/root` 后，宝塔终端只跑：

```bash
bash /root/upgrade-query-pages2.sh
```

## 这次会改什么

相对上一包 `20260823-query-pages`：

- 查询报表十页筛选改成分块：范围、期间、本页细筛，操作按钮单独一行
- 新增「调休额度」查询页（年休假仍单独一页；正式 9 页 Excel 报表中心不变）
- 查询只读已钉住核算，GET 不再现场整月核算
- 无打卡/无核算月份提示「该期间暂无打卡或核算数据」，不报 500
- 得力与 OA 定时同步都成功后，延迟约 30 分钟后台自动重算；手工同步不触发
- 考勤工作台、「我的考勤工作台」默认本月，可切当天 / 指定月份
- Flyway **V58**（查询能力、索引、自动重算槽；若上一包已执行则启动时跳过）

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

部署后浏览器 **Ctrl+F5**。侧栏「查询报表」应看到十项（含调休额度）。HR / 系统管理员对已有打卡但还没有 pin 的 OPEN 月点一次 **「重新计算」**，或等当天 00:00/12:00 两边同步成功后再过约 30 分钟自动补上。

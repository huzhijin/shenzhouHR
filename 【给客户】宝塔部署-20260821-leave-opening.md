# 宝塔升级包 20260821-leave-opening

发布包：`release-candidates/shenzhouhr-release-20260821-leave-opening.tar.gz`

传到服务器 `/root/shenzhouhr-release-20260821-leave-opening.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 允许年假/调休账户余额为负（超用）
- 导入 2026-08-01 期初；年假按天×8 小时；调休按小时
- 页面按天展示（8 小时 = 1 天）
- 负余额进入异常报表，工作台当天异常列表也会显示「假期余额为负」
- zhanglana 无工号，未导入

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。

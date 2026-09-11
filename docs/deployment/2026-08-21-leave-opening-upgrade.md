# 年假/调休期初导入（允许负数）

发布包：`release-candidates/shenzhouhr-release-20260821-leave-opening.tar.gz`

传到服务器 `/root/shenzhouhr-release-20260821-leave-opening.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- Flyway `V57`：允许 `time_account.balance_hours` 为负
- 员工详情期初可以录入负数，页面仍按 8 小时 = 1 天展示
- 导入 2026-08-01 年假/调休 OPENING（已含 8 月已审单据滚算，跳过 zhanglana）

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。密钥不要发到 git。

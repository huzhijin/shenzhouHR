# 宝塔升级包 20260821-pin-ot-roster

发布包：`release-candidates/shenzhouhr-release-20260821-pin-ot-roster.tar.gz`

SHA256：`24f0a5c3657aedbd1358a6cdd445a6528d7662b60a09adea48e9031bd83ef05c`

传到服务器 `/root/shenzhouhr-release-20260821-pin-ot-roster.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 报表第一次打开会整月核算并钉住，之后刷新不再乱变；只有 HR / 系统管理员能点「重新计算」
- 过夜加班、周末假别工时、月度工时、半天出勤率
- 花名册部门树、免打卡名单（生效日 2026-01-01）

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。密钥不要发到 git。

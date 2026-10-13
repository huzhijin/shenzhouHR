# 宝塔升级包 20260822-matrix-leave-exceptions

发布包：`release-candidates/shenzhouhr-release-20260822-matrix-leave-exceptions.tar.gz`

SHA256：`163c5c848d3cb2e0c34d88e98c715c2ebe798637559354edb03b2a95514a5b8a`

传到服务器 `/root/shenzhouhr-release-20260822-matrix-leave-exceptions.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 调休显示黄色「调休」，不再白底「请假」
- 婚假、产假、陪产假、丧假、工伤假、护理假、哺乳假、孕检假、计生假各自独立颜色
- 休息日有打卡会显示时间；周六加班单无卡时格子写「加班」
- 异常总览改为日期 / 类型 / 详情（上班缺卡、下班缺卡），不再默认显示证据摘要
- **没有新的 Flyway 脚本**

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

部署后浏览器 **Ctrl+F5**。HR / 系统管理员打开 **2026-08** 报表点 **「重新计算」**，周六加班打卡才会进格子。调休颜色刷新即可。

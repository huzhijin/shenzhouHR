# 宝塔升级包 20260821-deli-daily

发布包：`release-candidates/shenzhouhr-release-20260821-deli-daily.tar.gz`

SHA256：`df4500a6b25cce583c59628c29054e81d783ca6ce4cdd35b93040a60b4b1d27b`

传到服务器 `/root/shenzhouhr-release-20260821-deli-daily.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 得力同步可按天截止（`throughDate`）
- 打卡工号以打卡条为准，避免人员目录把人盖错

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。前端不用换。部署完成后再跑 `/root/reload-deli-august-daily.sh`。

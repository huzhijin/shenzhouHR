# 宝塔升级包 20260825-workbench-board

发布包：`release-candidates/shenzhouhr-release-20260825-workbench-board.tar.gz`

SHA256：`6ee940d43a2574edb08346d3c5c7a46b83589ff3be417114652b3e794c59c58c`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-workbench-board.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260825-workbench-board.tar.gz`
- `release-candidates/upgrade-workbench-board.sh`

## 这次会改什么

- 修复考勤工作台打开后「请求未成功完成 / 考勤工作台数据暂时无法显示」
- 原因：工作台按**昨天**汇总异常，接口却把 `businessDate` 写成今天，前端契约拒收
- 现在接口按昨日看板日期发布；前端也兼容相邻日
- **没有新的 Flyway 脚本**
- **不必重算 OPEN 月**。工作台走 LIVE-WB 打卡汇总，与月报钉住 409 无关

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 部署后

浏览器 **Ctrl+F5**，打开 `/workbench`，应看到「昨日异常人员」，不再是重试空页。

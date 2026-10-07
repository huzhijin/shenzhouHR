# 宝塔升级包 20260824-report-workbench

发布包：`release-candidates/shenzhouhr-release-20260824-report-workbench.tar.gz`

SHA256：`a4959e5bad6d30eec19648e4642ee8b5b33c4375a27013b8db9c8953369c5c32`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-report-workbench.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260824-report-workbench.tar.gz`
- `release-candidates/upgrade-report-workbench.sh`

## 这次会改什么

- 查询报表工时/请假按半小时取整；休息日有加班单且有打卡时，加班小时不再一直是 0
- 异常总览同一人同一日同一类型不重复；全天请假不再出缺卡/迟到/早退/旷工
- 忘打卡按上班/下班侧计次
- 查询页导出能下载 xlsx（查询角色有导出权限即可）
- 月度工时/迟到抽屉去掉英文字段；备注列不再变成第二颗「查看详情」
- 查询报表、工作台组织列与总表一致：完整部门用 `-` 连接，例如 `服务中心-工程二部-RF-B组`
- 考勤工作台：高管角色和「无需打卡」名单不再显示缺卡错误
- **没有新的 Flyway 脚本**

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 部署后

浏览器 **Ctrl+F5**。

OPEN 月要在「考勤报表」点 **「重新计算」**（或等得力/OA 定时同步成功后约 30 分钟自动重算），新口径才会进钉住结果。未重算的旧 pin 不会被新公式读取。

不要手工 `DELETE FROM attendance_report_exception_fact`。不要自动重开关账月。

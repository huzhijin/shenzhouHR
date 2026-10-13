# 宝塔升级包 20260823-query-pages2

发布包：`release-candidates/shenzhouhr-release-20260823-query-pages2.tar.gz`

SHA256：`14ba824041115732cbdd769a9f718a537a8312bcd82c36f33d8b360bc6c697b8`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-query-pages2.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260823-query-pages2.tar.gz`
- `release-candidates/upgrade-query-pages2.sh`

## 这次会改什么

- 「查询报表」十页：异常、请假、加班、工时、迟到、忘打卡、出勤率、年假、**调休额度**、明细
- 筛选分三块：范围 / 期间 / 本页细筛
- 打开查询不再现场算账；缺月提示无数据，不算错
- 得力+OA 定时同步都成功后约 30 分钟自动重算；手动「重新计算」仍可用
- 工作台 / 我的考勤默认本月，可选当天或指定月份
- 含 Flyway V58（上一包已升过则自动跳过）

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

部署后浏览器 **Ctrl+F5**。

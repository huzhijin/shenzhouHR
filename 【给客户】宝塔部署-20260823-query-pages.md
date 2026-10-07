# 宝塔升级包 20260823-query-pages

发布包：`release-candidates/shenzhouhr-release-20260823-query-pages.tar.gz`

SHA256：`e6166a1aaa2f4d4e92f24292aca4b70c6eadf6a98eb4c04851a1560cc1636745`

传到服务器 `/root/shenzhouhr-release-20260823-query-pages.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 新菜单「查询报表」九页，细筛走服务端，已核算时按秒级查询
- 打开报表不再现场算账；缺月提示无数据，不算错
- 得力+OA 定时同步都成功后约 30 分钟自动重算；手动「重新计算」仍可用
- 工作台 / 我的考勤默认本月，可选当天或指定月份
- 含 Flyway V58

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

部署后浏览器 **Ctrl+F5**。

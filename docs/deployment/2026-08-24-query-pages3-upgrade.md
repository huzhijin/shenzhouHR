# 查询报表样式、OA 单据与启动修复 20260824-query-pages3

发布包：`release-candidates/shenzhouhr-release-20260824-query-pages3.tar.gz`

把 `shenzhouhr-release-20260824-query-pages3.tar.gz` 和 `upgrade-query-pages3.sh` 都传到 `/root` 后，宝塔终端只跑：

```bash
bash /root/upgrade-query-pages3.sh
```

## 这次会改什么

- 修复得力定时任务无参构造导致 Java 起不来
- 查询报表公司/部门下拉能列出授权范围
- 日期选择器全中文
- 查询报表、同步作业、OA 单据表头改为致远浅蓝，悬停浅灰
- OA 单据分页；列改为谁、什么单据、说明、时长、时间；支持请假/加班/外出/出差/补签/调休/销假/免打卡
- Flyway **V59**（OA 单据列表索引；V58 已执行则跳过）

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。部署后浏览器 **Ctrl+F5**。

# 宝塔升级包 20260822-dept-path-query

发布包：`release-candidates/shenzhouhr-release-20260822-dept-path-query.tar.gz`

SHA256：`808a5bdc28328c686ebe1f15eca687e8118dff851eacdb9deacfcaf5e62cdd50`

传到服务器 `/root/shenzhouhr-release-20260822-dept-path-query.tar.gz` 后，打开宝塔终端，整段粘贴包内 `部署操作-服务器执行.txt`。

## 这次会改什么

- 考勤报表「部门」列按组织树拼全路径，例如 `服务中心-工程一部-DC组`
- 查询时直接拼，**不必再重新计算**

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。前端不用换。若日更脚本还在跑，等它跑完再部署。

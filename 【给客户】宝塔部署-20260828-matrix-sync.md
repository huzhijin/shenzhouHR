# 宝塔升级包 20260828-matrix-sync

考勤明细改数、异常只藏审批通过 OA、调休日更、纸质加班进报表、
财务加班表头冻结、考勤报表矩阵导出、0/12 双源同步后再整月重算。

发布包：`release-candidates/shenzhouhr-release-20260828-matrix-sync.tar.gz`

SHA256：`44771490c580e71784eadf304337d71a8fb99ad42bda2cf6498d7236de441af7`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-matrix-sync.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260828-matrix-sync.tar.gz`
- `release-candidates/upgrade-matrix-sync.sh`

## 这次会改什么

- 查询报表「考勤明细」：点格子改打卡 / 加班小时 / 取消异常并留痕
- 异常总览：只有审批通过的请假/调休/外出/出差/免打卡覆盖当天，异常才不再出现；审批中不算
- 年休假 / 调休额度：保留「按 OA 重算」；每天 01:00 再自动按 OA 更新一次
- 纸质加班单：按钮在上，去掉主表/明细标题，行可删除；保存后清空并提示「新增成功」，后台重算后进加班报表
- 每日加班查询：日期+星期两行一起冻，1–3 日列宽与后面一致
- 考勤报表页签「每日加班」导出改走与查询页同一张人×日矩阵 Excel
- OA 仍每小时同步；得力仍 8/12/18/0；自动计算只在 0 点和 12 点，且两边都成功后再整月重算

无新 Flyway。OPEN 月建议部署后再点一次「重新计算」。

葛倩荣 / 程兆俊 / 蔡小钰 / 赵建浩 得力工号已改为 SZST0498 / SZST0554 / SZST0654 / SZST0680。部署后请手动同步得力并重算 8 月。不要 `checkin_query_init`。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

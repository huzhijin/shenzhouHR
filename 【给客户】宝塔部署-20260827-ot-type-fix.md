# 宝塔升级包 20260827-ot-type-fix

修复整月重算 `Unknown column oa.overtime_type`。财务加班作为独立查询报表，细筛：平时/周末/节假日、本月哪一天、最低小时。同时带上纸质加班 HTTP 白屏修复。

发布包：`release-candidates/shenzhouhr-release-20260827-ot-type-fix.tar.gz`

SHA256：`530d0dd19a071908174d130e31aba2c971d487f01f98a689ecc0e820d700dba5`

## 命令

两个文件传到 `/root`：

- `release-candidates/shenzhouhr-release-20260827-ot-type-fix.tar.gz`
- `release-candidates/upgrade-ot-type-fix.sh`

```bash
bash /root/upgrade-ot-type-fix.sh
```

出现「部署完成」后浏览器 **Ctrl+F5**。再重算：

```bash
nohup bash /root/recalculate-open-month.sh >> /root/recalculate-open-month.log 2>&1 &
tail -f /root/recalculate-open-month.log
```

也可以只对江苏神州在页面点「重新计算本月」。

## 财务加班在哪

- **查询报表** 左侧菜单「财务加班」：`/attendance/queries/finance-overtime`（独立查询页，可细筛）
- **考勤报表** 第 5 个页签「财务加班」

无新 Flyway。不覆盖 `start-prod.sh`。

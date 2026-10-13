# 纸质加班单、同步窗口与报表局部重算部署说明

部署本变更前先备份数据库和应用配置。Flyway 会执行 `V61__paper_overtime_ocr_and_report_windows.sql`。

## 定时同步 cron（Asia/Shanghai）

在 `application.yml` 或生产 env 中确认：

| 来源 | 环境变量 | 新默认 |
|---|---|---|
| OA 考勤单据 | `SHENZHOUHR_OA_AUTO_SYNC_CRON` | `0 0 * * * ?`（每小时） |
| 得力打卡 | `SHENZHOUHR_DELI_AUTO_SYNC_CRON` | `0 0 0,8,12,18 * * ?` |

两侧仍走现有水位线增量，不要改成按调度时钟全量重拉。

自动重算默认延迟 `SHENZHOUHR_REPORT_AUTO_RECALCULATE_DELAY=PT10M`，任一侧定时成功即排队近 3 天局部重算；关账/冻结月跳过。手工点同步仍不触发自动重算。

## OCR 环境变量占位

纸质加班识别读取百度高精度 OCR（接口标注为 Paddle）：

```
SHENZHOUHR_OCR_BAIDU_API_KEY=
SHENZHOUHR_OCR_BAIDU_SECRET_KEY=
```

只允许写在仓库外的 `/etc/shenzhouhr/shenzhouhr.env` 或密钥管理器。不要把庆峰或其它试用密钥提交进 Git。未配置密钥时识别接口失败，手填明细仍可保存。

## 报表与纸质单

- 人事菜单「纸质加班单」需要 `PAPER_OVERTIME:MANAGE`（默认 `HR_ADMIN` / `SYSTEM_ADMIN`）。
- 保存成功即写入已批准加班证据，不必等核算完成；重叠自然日仍拦保存。
- **OPEN 月必须再点「重新计算近3天 / 近一周 / 本月」或等自动近 3 天任务后，纸质单才会进入 pin 报表。** 查询加班统计在证据入库后即可看到来源为纸质的单据行。
- 关账 / 冻结月不会被局部重算改写。

## 回滚要点

1. 停用纸质加班 API 与 OCR 环境变量。
2. cron 改回 `0 0 0,12 * * ?`。
3. 重算请求不传 `window` 时仍按整月。

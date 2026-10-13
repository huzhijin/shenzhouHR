# 2026-08-27 考勤报表 500 / 纸质加班识别 / 日期筛选

发布包：`release-candidates/shenzhouhr-release-20260827-report-ocr.tar.gz`

- 考勤报表打开 500（页面显示「服务暂时不可用」）：钉住假别码 `ANNUAL_LEAVE` 被当成 Java 枚举名解析
- 纸质加班识别没提示、没出数：大图 OCR 失败被吞掉，空结果当成功，提示框没挂上页面
- 考勤报表去掉「月份」，只留起止日期（同一月内）
- Flyway V64：晟州负期初可写入钉住

无覆盖 `start-prod.sh`。启动会迁 V64。

## 上传

```bash
scp release-candidates/shenzhouhr-release-20260827-report-ocr.tar.gz \
    release-candidates/upgrade-report-ocr.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260827-report-ocr.tar.gz
# 必须是
# 1d41c1c380d2bec05c651e610362c72cfb89d9352cc20dfc14e002370c973b78

bash /root/upgrade-report-ocr.sh
```

升完 Ctrl+F5 打开考勤报表。晟州仍无钉住的话再算：

```bash
nohup env COMPANY_ID=41000000-0000-0000-0000-000000000002 \
  bash /root/recalculate-open-month.sh \
  >> /root/recalculate-failed.log 2>&1 &
```

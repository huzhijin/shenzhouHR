# 夏令时下午班次批量更正（13:00 → 13:30）

只改正库里的班次配置，不改计算引擎、不改大连/成都特殊班次。

## 改什么

| 对象 | 原值 | 新值 |
| --- | --- | --- |
| 夏令时下午上班 | 13:00～18:00 | 13:30～18:00 |
| 对应午休结束 | 13:00 | 13:30 |

覆盖：各分公司默认考勤组、上海复制扬州夏令、以及按扬州夏令建的办事处班次。

## 不改什么

- 大连：07:30～12:00、13:00～16:30
- 成都：09:00～12:00、13:00～18:00
- 冬令时下午：13:00～17:30
- 用餐扣减、迟到、打卡窗口等策略

## 执行

先备份：

```bash
mysqldump -uroot -p --single-transaction shenzhou_hr \
  shift_version > /opt/shenzhouhr/backup/shift_version-before-1330-$(date +%Y%m%d%H%M).sql
```

宝塔 phpMyAdmin：打开 `shenzhou_hr` → SQL，把
`deploy/mysql/summer-afternoon-1330-post-v35.sql`
整份贴进去执行。

命令行：

```bash
mysql -uroot -p shenzhou_hr < deploy/mysql/summer-afternoon-1330-post-v35.sql
```

## 看结果

脚本会输出 4 组结果：

1. **预览**：即将改的班次清单（公司、班次编码、生效日）
2. `updated_rows`：`afternoon_ok`、`break_ok` 应等于 `actual_count`
3. `remaining_yangzhou_summer_1300_1800`：应为 `0`
4. `chengdu_unchanged` / `dalian_unchanged`：成都、大连行数保持原样

页面「规则设置 → 班次」里，夏令版本下午应变为 13:30–18:00。

实时报表下次查询会按新班次计算。若本月已经刷过核算结果，在报表页再刷新一次。

## 回滚

```bash
mysql -uroot -p shenzhou_hr < deploy/mysql/ROLLBACK_summer-afternoon-1330-post-v35.sql
```

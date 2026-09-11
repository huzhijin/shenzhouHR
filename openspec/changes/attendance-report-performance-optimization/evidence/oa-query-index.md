# OA 有效单据查询索引（任务 1.2 / 1.3）

## 拒绝的索引

不要创建：

```sql
CREATE INDEX ix_oa_document_status_type_created
    ON oa_attendance_document (
        source_status,
        document_type,
        created_at,
        oa_attendance_document_id
    );
```

内层窗口是 `WHERE created_at <= dataAsOf` 后按 `(attendance_source_id, source_business_key)` 对**全部状态**做 `ROW_NUMBER()`。`source_status` / `document_type` 在 `version_rank = 1` 之后才过滤，status 前导索引剪不掉这次扫描。

## 采用的索引（V67）

```sql
(attendance_source_id, source_business_key, created_at, knowledge_rank, oa_attendance_document_id)
```

对应分区键、`created_at` 截止、以及 `knowledge_rank DESC, created_at DESC, id DESC` 排序。窗口仍先排全部状态。

## 生产 EXPLAIN（任务 1.1，待有库后补计划）

把 `AttendanceReportCalculationMapper.xml` 里 `findEffectiveOaDocuments` 的 SQL 套上真实 `companyId` / 窗口 / `dataAsOf` 后执行：

```sql
EXPLAIN ANALYZE
-- paste findEffectiveOaDocuments here
;
```

保存 `EXPLAIN` 的 `actual time`、扫描行数、是否使用 `ix_oa_document_source_key_created`。任务 1.1 / 1.5 在有生产级副本之前不能勾完。

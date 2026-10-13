# Business Rules Alignment Migration Rehearsal — 2026-08-17

## Result

PASS. A clean development schema was migrated from V1 through V48 on the
repository's isolated MySQL 8.4.10 instance, followed by `flyway validate` and
the read-only schema verifier.

## Isolated scope

- Server: `127.0.0.1:13306`, MySQL 8.4.10 isolated runtime
- Disposable schema: `shenzhouhr_business_rules_20260817`
- Existing local MySQL 8.0.34 on port 3306: unchanged (guarded by the runtime
  script before and after startup)
- Flyway outcome: version 48 installed with `success = 1`

## Verified contracts

- `attendance_report_daily_fact` has leave type, day-count fields, and all
  four overtime classification totals.
- `punch_correction_request`, `oa_enum_mapping`, and `deli_sync_log` exist.
- `oa_attendance_document_context.overtime_type` exists.
- Day-count, punch-side, overtime-type, and overtime-total constraints exist.
- The three confirmed `formson_0172.field0096` mappings total 117,177
  classified records (the separate 30 NULL records remain quarantined).
- The non-human `SYSTEM` principal exists with no employee binding.

The repeatable verifier is
`deploy/mysql/sql/verify-business-rules-alignment-schema.sql`; every detail row
and `overall_status` must be `PASS`.

## Rehearsal findings fixed before PASS

1. V45 referred to a nonexistent `employment` table. It now preserves the
   canonical `employment_assignment.assignment_id` foreign key used by all
   ingestion writers.
2. V46 inserted columns not present in `auth_principal`. It now follows the
   existing non-human-principal schema (`employee_id = NULL`, `ACTIVE`).

Each correction was followed by a fresh empty-schema migration, so the final
PASS does not rely on repairing a partially migrated database.

## Rollback rehearsal

After the successful migrate/validate/schema-verifier sequence, the V48
rollback script was executed on the disposable schema. Post-rollback checks
returned zero for all three V48 tables, all V48 business columns, and all three
punch-correction capabilities. The disposable schema and short-lived Flyway
user were then removed and the isolated server was stopped.

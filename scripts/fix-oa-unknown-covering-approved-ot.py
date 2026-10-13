#!/usr/bin/env python3
"""诊断并修复吕加军 SZST0687 加班算成 0。

先列出每张加班单最新状态。默认预览。
  python3 /root/fix-oa-unknown-covering-approved-ot.py
  APPLY=1 python3 /root/fix-oa-unknown-covering-approved-ot.py

不回拨 OA 水位。APPLY 后必须整月重算 2026-08。
"""
from __future__ import annotations

import os
import subprocess
import sys
import uuid

HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"
EMPNO = os.environ.get("EMPNO", "SZST0687")


def mysql_bin() -> str:
    path = "/www/server/mysql/bin/mysql"
    return path if os.access(path, os.X_OK) else "mysql"


def run(sql: str, table: bool = True) -> str:
    argv = [
        mysql_bin(), "-h127.0.0.1", "-P3306", "-uroot",
        "--default-character-set=utf8mb4", HR_DB,
    ]
    argv.append("--table" if table else "-N")
    completed = subprocess.run(
        argv, input="SET NAMES utf8mb4;\n" + sql,
        text=True, capture_output=True, env=os.environ.copy(),
    )
    sys.stdout.write(completed.stdout)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")
    return completed.stdout


def scalar(sql: str) -> str:
    argv = [
        mysql_bin(), "-h127.0.0.1", "-P3306", "-uroot",
        "--default-character-set=utf8mb4", "-N", "-B", HR_DB,
    ]
    completed = subprocess.run(
        argv, input="SET NAMES utf8mb4;\n" + sql,
        text=True, capture_output=True, env=os.environ.copy(),
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")
    return completed.stdout.strip()


LATEST = f"""
SELECT latest.source_business_key AS 单号,
       latest.source_status AS 最新状态,
       ctx.activation_decision AS 激活,
       ctx.overtime_type AS 类型,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       CONVERT_TZ(n.interval_start,'+00:00','+08:00') AS 开始上海,
       CONVERT_TZ(n.interval_end,'+00:00','+08:00') AS 结束上海
FROM oa_attendance_document latest
JOIN (
    SELECT oa.attendance_source_id, oa.source_business_key,
           MAX(oa.knowledge_rank) AS max_rank
    FROM oa_attendance_document oa
    JOIN employee_match_decision m
      ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
     AND m.match_status = 'MATCHED'
    JOIN employee_version v
      ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
    WHERE v.employee_number = '{EMPNO}'
      AND oa.document_type = 'OVERTIME'
    GROUP BY oa.attendance_source_id, oa.source_business_key
) mx
  ON mx.attendance_source_id = latest.attendance_source_id
 AND mx.source_business_key = latest.source_business_key
 AND latest.knowledge_rank = mx.max_rank
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = latest.normalized_attendance_record_id
LEFT JOIN oa_attendance_document_context ctx
  ON ctx.oa_attendance_document_id = latest.oa_attendance_document_id
ORDER BY n.interval_start, latest.source_business_key;
"""

AUG_LATEST = f"""
SELECT latest.source_status AS 最新状态,
       ctx.activation_decision AS 激活,
       ctx.overtime_type AS 类型,
       COUNT(*) AS 张数
FROM oa_attendance_document latest
JOIN (
    SELECT oa.attendance_source_id, oa.source_business_key,
           MAX(oa.knowledge_rank) AS max_rank
    FROM oa_attendance_document oa
    JOIN employee_match_decision m
      ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
     AND m.match_status = 'MATCHED'
    JOIN employee_version v
      ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
    WHERE v.employee_number = '{EMPNO}'
      AND oa.document_type = 'OVERTIME'
    GROUP BY oa.attendance_source_id, oa.source_business_key
) mx
  ON mx.attendance_source_id = latest.attendance_source_id
 AND mx.source_business_key = latest.source_business_key
 AND latest.knowledge_rank = mx.max_rank
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = latest.normalized_attendance_record_id
LEFT JOIN oa_attendance_document_context ctx
  ON ctx.oa_attendance_document_id = latest.oa_attendance_document_id
WHERE n.interval_start >= '2026-07-31 16:00:00'
  AND n.interval_start <  '2026-08-31 16:00:00'
GROUP BY latest.source_status, ctx.activation_decision, ctx.overtime_type;
"""

AUG_UNKNOWN_IDS = f"""
SELECT latest.oa_attendance_document_id,
       latest.attendance_source_id,
       latest.source_business_key
FROM oa_attendance_document latest
JOIN (
    SELECT oa.attendance_source_id, oa.source_business_key,
           MAX(oa.knowledge_rank) AS max_rank
    FROM oa_attendance_document oa
    JOIN employee_match_decision m
      ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
     AND m.match_status = 'MATCHED'
    JOIN employee_version v
      ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
    WHERE v.employee_number = '{EMPNO}'
      AND oa.document_type = 'OVERTIME'
    GROUP BY oa.attendance_source_id, oa.source_business_key
) mx
  ON mx.attendance_source_id = latest.attendance_source_id
 AND mx.source_business_key = latest.source_business_key
 AND latest.knowledge_rank = mx.max_rank
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = latest.normalized_attendance_record_id
LEFT JOIN oa_attendance_document_context ctx
  ON ctx.oa_attendance_document_id = latest.oa_attendance_document_id
WHERE n.interval_start >= '2026-07-31 16:00:00'
  AND n.interval_start <  '2026-08-31 16:00:00'
  AND latest.document_type = 'OVERTIME'
  AND latest.source_status IN ('UNKNOWN','APPROVED')
  AND (ctx.oa_attendance_document_id IS NULL
       OR ctx.activation_decision IS NULL
       OR ctx.overtime_type IS NULL);
"""


def main() -> int:
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("先 export MYSQL_PWD")
    print("吕加军 %s 加班诊断。APPLY=%s" % (EMPNO, int(APPLY)))
    print("\n==== 全部加班单（每张单号最新一版）====")
    out = run(LATEST).rstrip()
    print(out if out else "(无行)")
    print("\n==== 仅 2026-08 最新一版汇总 ====")
    out = run(AUG_LATEST).rstrip()
    print(out if out else "(无行)")

    missing = scalar(AUG_UNKNOWN_IDS)
    if not missing:
        print("\n8 月最新版都已激活，不需要抬状态。若报表仍是 0，把上面两张表贴回来。")
        return 0

    print("\n==== 8 月最新版缺激活、核算会计 0 的单 ====")
    run(
        """
SELECT latest.oa_attendance_document_id AS 单据ID,
       latest.source_business_key AS 单号,
       latest.source_status AS 状态,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       CONVERT_TZ(n.interval_start,'+00:00','+08:00') AS 开始上海
FROM oa_attendance_document latest
JOIN (
    SELECT oa.attendance_source_id, oa.source_business_key,
           MAX(oa.knowledge_rank) AS max_rank
    FROM oa_attendance_document oa
    JOIN employee_match_decision m
      ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
     AND m.match_status = 'MATCHED'
    JOIN employee_version v
      ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
    WHERE v.employee_number = '%s'
      AND oa.document_type = 'OVERTIME'
    GROUP BY oa.attendance_source_id, oa.source_business_key
) mx
  ON mx.attendance_source_id = latest.attendance_source_id
 AND mx.source_business_key = latest.source_business_key
 AND latest.knowledge_rank = mx.max_rank
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = latest.normalized_attendance_record_id
LEFT JOIN oa_attendance_document_context ctx
  ON ctx.oa_attendance_document_id = latest.oa_attendance_document_id
WHERE n.interval_start >= '2026-07-31 16:00:00'
  AND n.interval_start <  '2026-08-31 16:00:00'
  AND latest.document_type = 'OVERTIME'
  AND latest.source_status IN ('UNKNOWN','APPROVED')
  AND (ctx.oa_attendance_document_id IS NULL
       OR ctx.activation_decision IS NULL
       OR ctx.overtime_type IS NULL)
ORDER BY n.interval_start;
"""
        % EMPNO
    )

    if not APPLY:
        print("\n确认这些是吕加军 8 月真实加班后：")
        print("APPLY=1 python3 /root/fix-oa-unknown-covering-approved-ot.py")
        return 0

    template = scalar(
        f"""
SELECT CONCAT_WS('\\t',
       ctx.oa_runtime_contract_revision_id,
       ctx.overtime_type)
FROM oa_attendance_document_context ctx
JOIN oa_attendance_document oa
  ON oa.oa_attendance_document_id = ctx.oa_attendance_document_id
JOIN employee_match_decision m
  ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
 AND m.match_status = 'MATCHED'
JOIN employee_version v
  ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
WHERE v.employee_number = '{EMPNO}'
  AND ctx.activation_decision = 'ACTIVATED'
  AND ctx.overtime_type IS NOT NULL
ORDER BY ctx.created_at DESC
LIMIT 1;
"""
    )
    if not template:
        raise SystemExit("没有可复制的已激活加班 context")
    contract_id, overtime_type = template.split("\t", 1)
    rows = scalar(AUG_UNKNOWN_IDS).splitlines()
    print("\n==== APPLY 给缺激活的 8 月单补 PAID 上下文 ====")
    for line in rows:
        parts = line.split("\t")
        if len(parts) < 1 or not parts[0]:
            continue
        doc_id = parts[0]
        ctx_id = str(uuid.uuid4())
        digest = "fix-lu-jiajun-" + ctx_id.replace("-", "")
        run(
            f"""
UPDATE oa_attendance_document
SET source_status = 'APPROVED'
WHERE oa_attendance_document_id = '{doc_id}'
  AND source_status IN ('UNKNOWN','APPROVED');
INSERT INTO oa_attendance_document_context (
    oa_attendance_document_context_id,
    oa_attendance_document_id,
    oa_runtime_contract_revision_id,
    attendance_group_revision_id,
    activation_decision,
    raw_status_value,
    overtime_treatment,
    overtime_type,
    recognized_work_minutes,
    payroll_credit_minutes,
    time_off_credit_minutes,
    authorized_context_json,
    context_digest,
    created_at
)
SELECT
    '{ctx_id}',
    '{doc_id}',
    '{contract_id}',
    NULL,
    'ACTIVATED',
    'APPROVED',
    'UNKNOWN',
    '{overtime_type}',
    0, 0, 0,
    CONCAT('{{\"overtimeType\":\"','{overtime_type}','\",\"sourceStatus\":\"APPROVED\"}}'),
    '{digest}',
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM oa_attendance_document_context c
    WHERE c.oa_attendance_document_id = '{doc_id}'
);
""",
            table=False,
        )
        print("patched", doc_id)
    print("\n==== APPLY 后 8 月最新版 ====")
    run(AUG_LATEST)
    print("接着：COMPANY_ID=41000000-0000-0000-0000-000000000003 bash /root/recalculate-open-month.sh")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

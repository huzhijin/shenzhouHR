#!/usr/bin/env python3
"""
从本地预览数据库导出完整期初数据（使用正确的字段名）
"""

import subprocess
import os
import json
from datetime import datetime

MYSQL_CLIENT = "mysql"
MYSQL_DEFAULTS = os.path.expanduser("~/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/root-client.cnf")
SOURCE_DB = "shenzhou_hr_four_company_rehearsal_20260805"
OUTPUT_DIR = "deploy/mysql/initial_data"

os.makedirs(OUTPUT_DIR, exist_ok=True)

def run_mysql(sql):
    """执行 MySQL 查询并返回结果"""
    cmd = [
        MYSQL_CLIENT,
        f"--defaults-extra-file={MYSQL_DEFAULTS}",
        SOURCE_DB,
        "--batch",
        "--skip-column-names",
        "-e",
        sql
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"❌ SQL 执行失败: {result.stderr}")
        return None
    return result.stdout

print("=" * 80)
print("🚀 从预览数据库导出完整期初数据")
print(f"源数据库: {SOURCE_DB}")
print("=" * 80)
print()

# 1. 导出地点
print("📍 1/9 导出工作地点...")
result = run_mysql("SELECT * FROM location ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V7.1__locations.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 工作地点期初数据\n")
        f.write(f"-- 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write(f"-- 数据来源: {SOURCE_DB}\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO location (\n")
        f.write("  location_id, company_id, location_code, row_version, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            f.write(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', {parts[3]}, '{parts[4]}', '{parts[5]}'){comma}\n")

        f.write("\n-- 验证\n")
        f.write("SELECT COUNT(*) AS location_count FROM location;\n")

    print(f"   ✅ {len(lines)} 个地点")

# 2. 导出地点版本
print("📌 2/9 导出地点版本...")
result = run_mysql("SELECT * FROM location_revision ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V7.2__location_revisions.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 地点版本（详细配置）\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO location_revision (\n")
        f.write("  location_revision_id, location_id, revision_number, location_name,\n")
        f.write("  time_zone, effective_from, supersedes_location_revision_id,\n")
        f.write("  snapshot_digest, change_reason, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            supersedes = f"'{parts[6]}'" if parts[6] != 'NULL' and parts[6] else 'NULL'

            f.write(f"  ('{parts[0]}', '{parts[1]}', {parts[2]}, '{parts[3]}',\n")
            f.write(f"   '{parts[4]}', '{parts[5]}', {supersedes},\n")
            f.write(f"   '{parts[7]}', '{parts[8]}', '{parts[9]}', '{parts[10]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个地点版本")

# 3. 导出工作日历
print("📅 3/9 导出工作日历...")
result = run_mysql("SELECT * FROM work_calendar ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V7.3__work_calendars.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 工作日历\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO work_calendar (\n")
        f.write("  work_calendar_id, company_id, calendar_code, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            f.write(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', '{parts[3]}', '{parts[4]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个工作日历")

# 4. 导出班次模板
print("📋 4/9 导出班次模板...")
result = run_mysql("SELECT * FROM shift_template ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V7.4__shift_templates.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 班次模板\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO shift_template (\n")
        f.write("  shift_template_id, company_id, location_id, template_code,\n")
        f.write("  created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            f.write(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', '{parts[3]}',\n")
            f.write(f"   '{parts[4]}', '{parts[5]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个班次模板")

# 5. 导出班次版本（最重要：包含夏令时/冬令时配置）
print("⏰ 5/9 导出班次版本（夏令时/冬令时配置）...")
result = run_mysql("SELECT * FROM shift_version ORDER BY shift_template_id, effective_from;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V7.5__shift_versions.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 班次版本（夏令时/冬令时具体配置）\n")
        f.write("-- ============================================================================\n\n")
        f.write("-- 重要字段说明：\n")
        f.write("-- effective_from: 生效日期（如 2026-01-01 为冬令时，2026-05-01 为夏令时）\n")
        f.write("-- segments_json: JSON 格式的工作时段配置\n")
        f.write("--   - WORK: 工作时段\n")
        f.write("--   - BREAK: 休息时段\n")
        f.write("--   - effectiveTo: 该配置有效期截止日期\n\n")

        f.write("INSERT INTO shift_version (\n")
        f.write("  shift_version_id, shift_template_id, version_number, effective_from,\n")
        f.write("  time_zone_snapshot, segments_json, supersedes_shift_version_id,\n")
        f.write("  snapshot_digest, change_reason, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            supersedes = f"'{parts[6]}'" if parts[6] != 'NULL' and parts[6] else 'NULL'

            # 转义 JSON 中的单引号
            segments_json = parts[5].replace("'", "''")

            f.write(f"  ('{parts[0]}', '{parts[1]}', {parts[2]}, '{parts[3]}',\n")
            f.write(f"   '{parts[4]}', '{segments_json}', {supersedes},\n")
            f.write(f"   '{parts[7]}', '{parts[8]}', '{parts[9]}', '{parts[10]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个班次版本（包含夏令时/冬令时）")

# 6. 导出考勤组
print("👥 6/9 导出考勤组...")
result = run_mysql("SELECT * FROM attendance_group ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V8.1__attendance_groups.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 考勤组\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO attendance_group (\n")
        f.write("  attendance_group_id, company_id, group_code, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            f.write(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', '{parts[3]}', '{parts[4]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个考勤组")

# 7. 导出考勤组版本
print("📝 7/9 导出考勤组版本（班次、地点、日历关联）...")
result = run_mysql("SELECT * FROM attendance_group_revision ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')
    with open(f"{OUTPUT_DIR}/V8.2__attendance_group_revisions.sql", 'w') as f:
        f.write("-- ============================================================================\n")
        f.write("-- 考勤组版本（关联班次模板、工作地点、工作日历）\n")
        f.write("-- ============================================================================\n\n")
        f.write("INSERT INTO attendance_group_revision (\n")
        f.write("  attendance_group_revision_id, attendance_group_id, revision_number,\n")
        f.write("  group_name, location_revision_id, work_calendar_id, shift_template_id,\n")
        f.write("  effective_from, supersedes_attendance_group_revision_id,\n")
        f.write("  snapshot_digest, change_reason, created_by, created_at\n")
        f.write(") VALUES\n")

        for i, line in enumerate(lines):
            parts = line.split('\t')
            comma = ',' if i < len(lines) - 1 else ';'
            supersedes = f"'{parts[8]}'" if parts[8] != 'NULL' and parts[8] else 'NULL'

            f.write(f"  ('{parts[0]}', '{parts[1]}', {parts[2]},\n")
            f.write(f"   '{parts[3]}', '{parts[4]}', '{parts[5]}', '{parts[6]}',\n")
            f.write(f"   '{parts[7]}', {supersedes},\n")
            f.write(f"   '{parts[9]}', '{parts[10]}', '{parts[11]}', '{parts[12]}'){comma}\n")

    print(f"   ✅ {len(lines)} 个考勤组版本")

# 8. 导出考勤组人员分配（1230 人）
print("🔗 8/9 导出考勤组人员分配（1230 条记录）...")
result = run_mysql("SELECT * FROM attendance_group_assignment ORDER BY created_at;")
if result and result.strip():
    lines = result.strip().split('\n')

    # 分批写入（每 100 条一批）
    batch_size = 100
    total_batches = (len(lines) + batch_size - 1) // batch_size

    for batch_num in range(total_batches):
        start = batch_num * batch_size
        end = min((batch_num + 1) * batch_size, len(lines))
        batch_lines = lines[start:end]

        with open(f"{OUTPUT_DIR}/V8.3__attendance_group_assignments_batch{batch_num+1:02d}.sql", 'w') as f:
            if batch_num == 0:
                f.write("-- ============================================================================\n")
                f.write(f"-- 考勤组人员分配 (批次 {batch_num+1}/{total_batches})\n")
                f.write(f"-- 总记录数: {len(lines)}\n")
                f.write("-- ============================================================================\n\n")
            else:
                f.write(f"-- 考勤组人员分配 (批次 {batch_num+1}/{total_batches})\n\n")

            f.write("INSERT INTO attendance_group_assignment (\n")
            f.write("  attendance_group_assignment_id, employee_id, attendance_group_revision_id,\n")
            f.write("  effective_from, supersedes_assignment_id, snapshot_digest,\n")
            f.write("  change_reason, created_by, created_at\n")
            f.write(") VALUES\n")

            for i, line in enumerate(batch_lines):
                parts = line.split('\t')
                comma = ',' if i < len(batch_lines) - 1 else ';'
                supersedes = f"'{parts[4]}'" if parts[4] != 'NULL' and parts[4] else 'NULL'

                f.write(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}',\n")
                f.write(f"   '{parts[3]}', {supersedes}, '{parts[5]}',\n")
                f.write(f"   '{parts[6]}', '{parts[7]}', '{parts[8]}'){comma}\n")

    print(f"   ✅ {len(lines)} 条人员分配（分 {total_batches} 个批次）")

# 9. 生成合并脚本
print("📦 9/9 生成合并导入脚本...")
with open(f"{OUTPUT_DIR}/import_all.sh", 'w') as f:
    f.write("#!/usr/bin/env bash\n")
    f.write("# 一键导入所有期初数据\n\n")
    f.write("set -euo pipefail\n\n")
    f.write("MYSQL_CLIENT=\"mysql\"\n")
    f.write("DB_NAME=\"shenzhou_hr\"\n\n")
    f.write("echo \"=== 🚀 导入神州HR期初数据 ===\"\n")
    f.write("echo\n\n")

    # 按顺序导入
    sql_files = [
        "V7.1__locations.sql",
        "V7.2__location_revisions.sql",
        "V7.3__work_calendars.sql",
        "V7.4__shift_templates.sql",
        "V7.5__shift_versions.sql",
        "V8.1__attendance_groups.sql",
        "V8.2__attendance_group_revisions.sql",
    ]

    for i, sql_file in enumerate(sql_files, 1):
        f.write(f"echo \"📥 {i}/{len(sql_files) + total_batches} 导入 {sql_file}...\"\n")
        f.write(f"$MYSQL_CLIENT -u root -p \"$DB_NAME\" < \"{sql_file}\"\n\n")

    # 导入所有分批文件
    for batch_num in range(total_batches):
        batch_file = f"V8.3__attendance_group_assignments_batch{batch_num+1:02d}.sql"
        f.write(f"echo \"📥 {len(sql_files) + batch_num + 1}/{len(sql_files) + total_batches} 导入 {batch_file}...\"\n")
        f.write(f"$MYSQL_CLIENT -u root -p \"$DB_NAME\" < \"{batch_file}\"\n\n")

    f.write("echo\n")
    f.write("echo \"=== ✅ 导入完成 ===\"\n")
    f.write("echo\n")
    f.write("echo \"验证数据：\"\n")
    f.write("$MYSQL_CLIENT -u root -p \"$DB_NAME\" << 'SQL'\n")
    f.write("SELECT 'locations' AS table_name, COUNT(*) AS count FROM location\n")
    f.write("UNION ALL SELECT 'shift_templates', COUNT(*) FROM shift_template\n")
    f.write("UNION ALL SELECT 'shift_versions', COUNT(*) FROM shift_version\n")
    f.write("UNION ALL SELECT 'attendance_groups', COUNT(*) FROM attendance_group\n")
    f.write("UNION ALL SELECT 'group_revisions', COUNT(*) FROM attendance_group_revision\n")
    f.write("UNION ALL SELECT 'group_assignments', COUNT(*) FROM attendance_group_assignment;\n")
    f.write("SQL\n")

os.chmod(f"{OUTPUT_DIR}/import_all.sh", 0o755)

print()
print("=" * 80)
print("✅ 完整期初数据导出完成")
print("=" * 80)
print()
print(f"输出目录: {OUTPUT_DIR}/")
subprocess.run(["ls", "-lh", OUTPUT_DIR])
print()
print("📌 导出内容：")
print("  ✅ 地点配置")
print("  ✅ 班次模板 + 版本（夏令时/冬令时）")
print("  ✅ 考勤组 + 版本")
print("  ✅ 1230 条人员分配记录")
print()
print("📥 使用方法：")
print("  1. 复制到服务器: scp -r deploy/mysql/initial_data root@服务器:/root/")
print("  2. 在服务器执行: cd /root/initial_data && ./import_all.sh")

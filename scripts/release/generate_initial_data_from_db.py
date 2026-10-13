#!/usr/bin/env python3
"""
从本地预览数据库生成完整期初数据 SQL 脚本
"""

import subprocess
import os
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
print("🚀 从预览数据库生成期初数据 SQL")
print("=" * 80)
print()

# 1. 导出地点
print("📍 1/6 导出工作地点...")
sql = """
SELECT location_id, company_id, location_code, row_version, created_by, created_at
FROM location
ORDER BY created_at;
"""
result = run_mysql(sql)
if result:
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 工作地点期初数据",
        f"-- 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "-- ============================================================================",
        "",
        "INSERT INTO location (",
        "  location_id, company_id, location_code, row_version,",
        "  created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', {parts[3]},")
        sql_lines.append(f"   '{parts[4]}', '{parts[5]}'){comma}")

    with open(f"{OUTPUT_DIR}/V7.1__locations.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个地点")

# 2. 导出班次模板
print("📋 2/6 导出班次模板...")
sql = """
SELECT shift_template_id, company_id, location_id, template_code, created_by, created_at
FROM shift_template
ORDER BY created_at;
"""
result = run_mysql(sql)
if result:
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 班次模板期初数据",
        "-- ============================================================================",
        "",
        "INSERT INTO shift_template (",
        "  shift_template_id, company_id, location_id, template_code,",
        "  created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}', '{parts[3]}',")
        sql_lines.append(f"   '{parts[4]}', '{parts[5]}'){comma}")

    with open(f"{OUTPUT_DIR}/V7.2__shift_templates.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个班次模板")

# 3. 导出班次版本（真实配置）
print("⏰ 3/6 导出班次版本...")
sql = """
SELECT version_id, shift_template_id, version_number, start_date, end_date,
       start_time, end_time, break_start, break_end, created_by, created_at
FROM shift_version
ORDER BY created_at;
"""
result = run_mysql(sql)
if result and result.strip():
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 班次版本（具体时间配置）",
        "-- ============================================================================",
        "",
        "INSERT INTO shift_version (",
        "  version_id, shift_template_id, version_number, start_date, end_date,",
        "  start_time, end_time, break_start, break_end, created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        end_date = f"'{parts[4]}'" if parts[4] != 'NULL' else 'NULL'
        break_start = f"'{parts[7]}'" if parts[7] != 'NULL' else 'NULL'
        break_end = f"'{parts[8]}'" if parts[8] != 'NULL' else 'NULL'

        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', {parts[2]}, '{parts[3]}', {end_date},")
        sql_lines.append(f"   '{parts[5]}', '{parts[6]}', {break_start}, {break_end}, '{parts[9]}', '{parts[10]}'){comma}")

    with open(f"{OUTPUT_DIR}/V7.3__shift_versions.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个班次版本")
else:
    print("   ⚠️  无班次版本数据")

# 4. 导出考勤组
print("👥 4/6 导出考勤组...")
sql = """
SELECT attendance_group_id, company_id, group_code, created_by, created_at
FROM attendance_group
ORDER BY created_at;
"""
result = run_mysql(sql)
if result:
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 考勤组期初数据",
        "-- ============================================================================",
        "",
        "INSERT INTO attendance_group (",
        "  attendance_group_id, company_id, group_code,",
        "  created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}',")
        sql_lines.append(f"   '{parts[3]}', '{parts[4]}'){comma}")

    with open(f"{OUTPUT_DIR}/V8.1__attendance_groups.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个考勤组")

# 5. 导出考勤组版本
print("📝 5/6 导出考勤组版本...")
sql = """
SELECT revision_id, attendance_group_id, version_number, shift_template_id,
       location_id, effective_from, effective_to, created_by, created_at
FROM attendance_group_revision
ORDER BY created_at;
"""
result = run_mysql(sql)
if result and result.strip():
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 考勤组版本（班次和地点配置）",
        "-- ============================================================================",
        "",
        "INSERT INTO attendance_group_revision (",
        "  revision_id, attendance_group_id, version_number, shift_template_id,",
        "  location_id, effective_from, effective_to, created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        effective_to = f"'{parts[6]}'" if parts[6] != 'NULL' else 'NULL'

        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', {parts[2]}, '{parts[3]}',")
        sql_lines.append(f"   '{parts[4]}', '{parts[5]}', {effective_to}, '{parts[7]}', '{parts[8]}'){comma}")

    with open(f"{OUTPUT_DIR}/V8.2__attendance_group_revisions.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个考勤组版本")
else:
    print("   ⚠️  无考勤组版本数据")

# 6. 导出考勤组人员分配
print("🔗 6/6 导出考勤组人员分配...")
sql = """
SELECT assignment_id, attendance_group_id, employee_id,
       effective_from, effective_to, created_by, created_at
FROM attendance_group_assignment
ORDER BY created_at;
"""
result = run_mysql(sql)
if result and result.strip():
    lines = result.strip().split('\n')
    sql_lines = [
        "-- ============================================================================",
        "-- 考勤组人员分配",
        "-- ============================================================================",
        "",
        "INSERT INTO attendance_group_assignment (",
        "  assignment_id, attendance_group_id, employee_id,",
        "  effective_from, effective_to, created_by, created_at",
        ") VALUES"
    ]

    for i, line in enumerate(lines):
        parts = line.split('\t')
        comma = ',' if i < len(lines) - 1 else ';'
        effective_to = f"'{parts[4]}'" if parts[4] != 'NULL' else 'NULL'

        sql_lines.append(f"  ('{parts[0]}', '{parts[1]}', '{parts[2]}',")
        sql_lines.append(f"   '{parts[3]}', {effective_to}, '{parts[5]}', '{parts[6]}'){comma}")

    with open(f"{OUTPUT_DIR}/V8.3__attendance_group_assignments.sql", 'w') as f:
        f.write('\n'.join(sql_lines))
    print(f"   ✅ {len(lines)} 个人员分配")
else:
    print("   ⚠️  无人员分配数据")

print()
print("=" * 80)
print("✅ 期初数据导出完成")
print("=" * 80)
print()
print(f"输出目录: {OUTPUT_DIR}/")
subprocess.run(["ls", "-lh", OUTPUT_DIR])

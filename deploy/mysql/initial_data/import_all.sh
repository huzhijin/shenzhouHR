#!/usr/bin/env bash
# 一键导入所有期初数据

set -euo pipefail

MYSQL_CLIENT="mysql"
DB_NAME="shenzhou_hr"

echo "=== 🚀 导入神州HR期初数据 ==="
echo

echo "📥 1/20 导入 V7.1__locations.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V7.1__locations.sql"

echo "📥 2/20 导入 V7.2__location_revisions.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V7.2__location_revisions.sql"

echo "📥 3/20 导入 V7.3__work_calendars.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V7.3__work_calendars.sql"

echo "📥 4/20 导入 V7.4__shift_templates.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V7.4__shift_templates.sql"

echo "📥 5/20 导入 V7.5__shift_versions.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V7.5__shift_versions.sql"

echo "📥 6/20 导入 V8.1__attendance_groups.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.1__attendance_groups.sql"

echo "📥 7/20 导入 V8.2__attendance_group_revisions.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.2__attendance_group_revisions.sql"

echo "📥 8/20 导入 V8.3__attendance_group_assignments_batch01.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch01.sql"

echo "📥 9/20 导入 V8.3__attendance_group_assignments_batch02.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch02.sql"

echo "📥 10/20 导入 V8.3__attendance_group_assignments_batch03.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch03.sql"

echo "📥 11/20 导入 V8.3__attendance_group_assignments_batch04.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch04.sql"

echo "📥 12/20 导入 V8.3__attendance_group_assignments_batch05.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch05.sql"

echo "📥 13/20 导入 V8.3__attendance_group_assignments_batch06.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch06.sql"

echo "📥 14/20 导入 V8.3__attendance_group_assignments_batch07.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch07.sql"

echo "📥 15/20 导入 V8.3__attendance_group_assignments_batch08.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch08.sql"

echo "📥 16/20 导入 V8.3__attendance_group_assignments_batch09.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch09.sql"

echo "📥 17/20 导入 V8.3__attendance_group_assignments_batch10.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch10.sql"

echo "📥 18/20 导入 V8.3__attendance_group_assignments_batch11.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch11.sql"

echo "📥 19/20 导入 V8.3__attendance_group_assignments_batch12.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch12.sql"

echo "📥 20/20 导入 V8.3__attendance_group_assignments_batch13.sql..."
$MYSQL_CLIENT -u root -p "$DB_NAME" < "V8.3__attendance_group_assignments_batch13.sql"

echo
echo "=== ✅ 导入完成 ==="
echo
echo "验证数据："
$MYSQL_CLIENT -u root -p "$DB_NAME" << 'SQL'
SELECT 'locations' AS table_name, COUNT(*) AS count FROM location
UNION ALL SELECT 'shift_templates', COUNT(*) FROM shift_template
UNION ALL SELECT 'shift_versions', COUNT(*) FROM shift_version
UNION ALL SELECT 'attendance_groups', COUNT(*) FROM attendance_group
UNION ALL SELECT 'group_revisions', COUNT(*) FROM attendance_group_revision
UNION ALL SELECT 'group_assignments', COUNT(*) FROM attendance_group_assignment;
SQL

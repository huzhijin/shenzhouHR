#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/deploy/mysql/initial_data"

MYSQL_CLIENT="mysql"
MYSQL_DEFAULTS="$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/root-client.cnf"
SOURCE_DB="shenzhou_hr_four_company_rehearsal_20260805"

mkdir -p "$OUTPUT_DIR"

echo "=== 🚀 导出神州HR期初配置数据 ==="
echo "源数据库: $SOURCE_DB"
echo "输出目录: $OUTPUT_DIR"
echo

# 1. 导出班次模板
echo "📋 导出班次模板..."
$MYSQL_CLIENT --defaults-extra-file="$MYSQL_DEFAULTS" "$SOURCE_DB" --batch --skip-column-names << 'SQL' > "$OUTPUT_DIR/V7.1__shift_templates.sql"
SELECT CONCAT(
  '-- 班次模板\n',
  'INSERT INTO shift_template (\n',
  '  shift_template_id, shift_name, shift_code, start_time, end_time,\n',
  '  break_minutes, company_id, is_active, row_version,\n',
  '  created_by, created_at, updated_by, updated_at\n',
  ') VALUES\n',
  GROUP_CONCAT(
    CONCAT(
      '(''', shift_template_id, ''', ''', shift_name, ''', ''', shift_code, ''', ''', start_time, ''', ''', end_time, ''',\n',
      ' ', IFNULL(break_minutes, 0), ', ''', company_id, ''', ', is_active, ', ', row_version, ',\n',
      ' ''', created_by, ''', ''', created_at, ''', ''', updated_by, ''', ''', updated_at, ''')'
    )
    SEPARATOR ',\n'
  ),
  ';\n'
)
FROM shift_template;
SQL

# 2. 导出地点
echo "📍 导出工作地点..."
$MYSQL_CLIENT --defaults-extra-file="$MYSQL_DEFAULTS" "$SOURCE_DB" --batch --skip-column-names << 'SQL' > "$OUTPUT_DIR/V7.2__locations.sql"
SELECT CONCAT(
  '-- 工作地点\n',
  'INSERT INTO location (\n',
  '  location_id, location_code, location_name, company_id,\n',
  '  latitude, longitude, radius_meters, is_active,\n',
  '  row_version, created_by, created_at, updated_by, updated_at\n',
  ') VALUES\n',
  GROUP_CONCAT(
    CONCAT(
      '(''', location_id, ''', ''', location_code, ''', ''', location_name, ''', ''', company_id, ''',\n',
      ' ', IFNULL(latitude, 'NULL'), ', ', IFNULL(longitude, 'NULL'), ', ', IFNULL(radius_meters, 200), ', ', is_active, ',\n',
      ' ', row_version, ', ''', created_by, ''', ''', created_at, ''', ''', updated_by, ''', ''', updated_at, ''')'
    )
    SEPARATOR ',\n'
  ),
  ';\n'
)
FROM location;
SQL

# 3. 导出考勤组
echo "👥 导出考勤组..."
$MYSQL_CLIENT --defaults-extra-file="$MYSQL_DEFAULTS" "$SOURCE_DB" --batch --skip-column-names << 'SQL' > "$OUTPUT_DIR/V8.1__attendance_groups.sql"
SELECT CONCAT(
  '-- 考勤组\n',
  'INSERT INTO attendance_group (\n',
  '  group_id, group_code, group_name, company_id, location_id,\n',
  '  shift_template_id, is_active, row_version,\n',
  '  created_by, created_at, updated_by, updated_at\n',
  ') VALUES\n',
  GROUP_CONCAT(
    CONCAT(
      '(''', group_id, ''', ''', group_code, ''', ''', group_name, ''', ''', company_id, ''', ',
      IFNULL(CONCAT('''', location_id, ''''), 'NULL'), ',\n',
      ' ', IFNULL(CONCAT('''', shift_template_id, ''''), 'NULL'), ', ', is_active, ', ', row_version, ',\n',
      ' ''', created_by, ''', ''', created_at, ''', ''', updated_by, ''', ''', updated_at, ''')'
    )
    SEPARATOR ',\n'
  ),
  ';\n'
)
FROM attendance_group;
SQL

# 4. 导出考勤组分配
echo "🔗 导出考勤组人员分配..."
$MYSQL_CLIENT --defaults-extra-file="$MYSQL_DEFAULTS" "$SOURCE_DB" --batch --skip-column-names << 'SQL' > "$OUTPUT_DIR/V8.2__attendance_group_assignments.sql"
SELECT CONCAT(
  '-- 考勤组人员分配\n',
  'INSERT INTO attendance_group_assignment (\n',
  '  assignment_id, group_id, employee_id, effective_from, effective_to,\n',
  '  row_version, created_by, created_at, updated_by, updated_at\n',
  ') VALUES\n',
  GROUP_CONCAT(
    CONCAT(
      '(''', assignment_id, ''', ''', group_id, ''', ''', employee_id, ''', ''', effective_from, ''', ',
      IFNULL(CONCAT('''', effective_to, ''''), 'NULL'), ',\n',
      ' ', row_version, ', ''', created_by, ''', ''', created_at, ''', ''', updated_by, ''', ''', updated_at, ''')'
    )
    SEPARATOR ',\n'
  ),
  ';\n'
)
FROM attendance_group_assignment;
SQL

# 5. 导出年假余额
echo "🏖️  导出年假余额..."
$MYSQL_CLIENT --defaults-extra-file="$MYSQL_DEFAULTS" "$SOURCE_DB" << 'SQL' > "$OUTPUT_DIR/V9.1__annual_leave_balances.sql"
SELECT CONCAT(
  '-- 年假余额期初数据\n',
  'INSERT INTO annual_leave_entitlement_projection (\n',
  '  projection_id, employee_id, policy_year, total_days, used_days, remaining_days,\n',
  '  last_updated, row_version\n',
  ') VALUES\n',
  GROUP_CONCAT(
    CONCAT(
      '(''', projection_id, ''', ''', employee_id, ''', ', policy_year, ', ',
      total_days, ', ', used_days, ', ', remaining_days, ',\n',
      ' ''', last_updated, ''', ', row_version, ')'
    )
    SEPARATOR ',\n'
  ),
  ';\n'
) AS sql_statement
FROM annual_leave_entitlement_projection;
SQL

echo
echo "=== ✅ 导出完成 ==="
ls -lh "$OUTPUT_DIR"

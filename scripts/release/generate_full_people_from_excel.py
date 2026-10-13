#!/usr/bin/env python3
"""
从 Excel 文件生成完整的人员期初数据 SQL 脚本

输入文件：
- 人员列表_seeyon1.xls (622 人)
- departments_seeyon1.xls (部门列表)

输出：
- V5.1__full_people_initial_import.sql (完整 622 人 SQL)

使用方法：
  python3 generate_full_people_from_excel.py
"""

import xlrd
import hashlib
import re
from datetime import datetime
from typing import Dict, List, Set, Tuple

# ============================================================================
# 配置
# ============================================================================

PEOPLE_EXCEL = '/Users/huzhijin/Downloads/人员列表_seeyon1.xls'
DEPT_EXCEL = '/Users/huzhijin/Downloads/departments_seeyon1.xls'
OUTPUT_SQL = 'deploy/mysql/V5.1__full_people_initial_import.sql'

# 四家公司的固定 company_id
COMPANIES = {
    'SZSZ': ('2a730fe2-c4e9-4e72-8f1d-95e3a8b14d01', '上海昇州半导体科技有限公司'),
    'SZJN': ('3b841fe3-d5fa-4f83-9g2e-a6f4b9c25e12', '上海晟州聚能半导体科技有限公司'),
    'SZSC': ('4c952fe4-e6fb-5g94-ah3f-b7g5cad36f23', '江苏神州半导体科技股份有限公司'),
    'SZXY': ('5da63fe5-f7gc-6ha5-bi4g-c8h6dbe47g34', '江苏芯越半导体科技有限公司'),
}

# 公司简称映射（Excel 里可能出现的变体）
COMPANY_NAME_MAP = {
    '上海昇州': 'SZSZ',
    '昇州': 'SZSZ',
    '上海晟州': 'SZJN',
    '晟州聚能': 'SZJN',
    '晟州': 'SZJN',
    '江苏神州': 'SZSC',
    '神州半导体': 'SZSC',
    '神州': 'SZSC',
    '江苏芯越': 'SZXY',
    '芯越': 'SZXY',
}

# SYSTEM principal (作为创建者)
SYSTEM_PRINCIPAL_ID = '00000000-0000-0000-0000-000000000001'

# ============================================================================
# 工具函数
# ============================================================================

def generate_uuid(seed: str) -> str:
    """根据种子生成确定性 UUID"""
    hash_obj = hashlib.sha256(seed.encode('utf-8'))
    hex_str = hash_obj.hexdigest()[:32]
    return f"{hex_str[0:8]}-{hex_str[8:12]}-{hex_str[12:16]}-{hex_str[16:20]}-{hex_str[20:32]}"

def normalize_name(name: str) -> str:
    """规范化姓名（去空格、特殊字符）"""
    return re.sub(r'\s+', '', name.strip())

def infer_company_code(dept_name: str, company_field: str = '') -> str:
    """从部门名称或公司字段推断公司代码"""
    # 优先从公司字段推断
    if company_field:
        for keyword, code in COMPANY_NAME_MAP.items():
            if keyword in company_field:
                return code

    # 从部门名称推断
    for keyword, code in COMPANY_NAME_MAP.items():
        if keyword in dept_name:
            return code

    # 默认归属到神州（SZSC）
    return 'SZSC'

def escape_sql(value: str) -> str:
    """转义 SQL 字符串"""
    if value is None:
        return 'NULL'
    return value.replace("'", "''").replace("\\", "\\\\")

def format_date(date_str: str) -> str:
    """格式化日期字符串为 MySQL DATE 格式"""
    if not date_str or str(date_str).strip() == '':
        return 'NULL'

    # 尝试多种日期格式
    formats = [
        '%Y-%m-%d',
        '%Y/%m/%d',
        '%Y.%m.%d',
        '%Y%m%d',
    ]

    for fmt in formats:
        try:
            dt = datetime.strptime(str(date_str).strip(), fmt)
            return f"'{dt.strftime('%Y-%m-%d')}'"
        except ValueError:
            continue

    return 'NULL'

# ============================================================================
# 读取 Excel 数据
# ============================================================================

def read_departments() -> Dict[str, Tuple[str, str]]:
    """
    读取部门列表
    返回：{部门名称: (department_id, company_code)}
    """
    print("📖 读取部门列表...")
    wb = xlrd.open_workbook(DEPT_EXCEL)
    sheet = wb.sheet_by_index(0)

    depts = {}
    for row in range(1, sheet.nrows):  # 跳过表头
        dept_name = str(sheet.cell_value(row, 0)).strip()
        if not dept_name:
            continue

        # 根据部门名称推断公司
        company_code = infer_company_code(dept_name)

        # 生成确定性 department_id
        dept_id = generate_uuid(f"dept_{company_code}_{dept_name}")

        depts[dept_name] = (dept_id, company_code)

    print(f"✅ 读取到 {len(depts)} 个部门")
    return depts

def read_people(departments: Dict[str, Tuple[str, str]]) -> List[Dict]:
    """
    读取人员列表
    返回：[{person_id, name, employee_number, ...}, ...]
    """
    print("📖 读取人员列表...")
    wb = xlrd.open_workbook(PEOPLE_EXCEL)
    sheet = wb.sheet_by_index(0)

    # 读取表头（第 3 行，索引 2）
    headers = [str(sheet.cell_value(2, col)).strip() for col in range(sheet.ncols)]
    print(f"表头: {headers}")

    people = []
    seen_emp_nums = set()

    for row in range(3, sheet.nrows):  # 从第 4 行开始（跳过标题、说明、表头）
        # 姓名
        name = normalize_name(str(sheet.cell_value(row, 0)))
        if not name or name == '':
            continue

        # 工号
        emp_num = str(sheet.cell_value(row, 2)).strip()
        if not emp_num:
            emp_num = f"AUTO_{row:04d}"

        # 去重
        if emp_num in seen_emp_nums:
            print(f"⚠️  跳过重复工号: {emp_num} ({name})")
            continue
        seen_emp_nums.add(emp_num)

        # 部门
        dept_name = str(sheet.cell_value(row, 5)).strip()
        if dept_name not in departments:
            # 动态添加缺失部门
            company_code = infer_company_code(dept_name)
            dept_id = generate_uuid(f"dept_{company_code}_{dept_name}")
            departments[dept_name] = (dept_id, company_code)
            print(f"🆕 动态添加部门: {dept_name} → {company_code}")

        dept_id, company_code = departments[dept_name]
        company_id, _ = COMPANIES[company_code]

        # 生成 person_id
        person_id = generate_uuid(f"person_{company_code}_{emp_num}")

        # 其他字段
        person = {
            'person_id': person_id,
            'company_id': company_id,
            'company_code': company_code,
            'employee_number': emp_num,
            'full_name': name,
            'department_id': dept_id,
            'department_name': dept_name,
            'position': str(sheet.cell_value(row, 6)).strip() if sheet.ncols > 6 else '',
            'mobile': str(sheet.cell_value(row, 8)).strip() if sheet.ncols > 8 else '',
            'email': str(sheet.cell_value(row, 9)).strip() if sheet.ncols > 9 else '',
            'hire_date': str(sheet.cell_value(row, 10)).strip() if sheet.ncols > 10 else '',
            'status': '在职',  # 默认在职
        }

        people.append(person)

    print(f"✅ 读取到 {len(people)} 个有效人员")
    return people

# ============================================================================
# 生成 SQL
# ============================================================================

def generate_sql(people: List[Dict], departments: Dict[str, Tuple[str, str]]) -> str:
    """生成完整 SQL 脚本"""
    lines = []

    # 文件头
    lines.append("-- ============================================================================")
    lines.append("-- 神州HR - 完整人员期初数据导入 (622 人)")
    lines.append("-- 生成时间: " + datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
    lines.append("-- 数据来源: 人员列表_seeyon1.xls + departments_seeyon1.xls")
    lines.append("-- ============================================================================")
    lines.append("")
    lines.append("SET @system_principal_id = '" + SYSTEM_PRINCIPAL_ID + "';")
    lines.append("SET @now = CURRENT_TIMESTAMP(6);")
    lines.append("")

    # 1. 插入缺失的部门
    lines.append("-- ============================================================================")
    lines.append("-- 1. 确保所有部门存在")
    lines.append("-- ============================================================================")
    lines.append("")

    for dept_name, (dept_id, company_code) in sorted(departments.items()):
        company_id, company_display = COMPANIES[company_code]
        lines.append(f"-- 部门: {dept_name} ({company_display})")
        lines.append("INSERT IGNORE INTO department (")
        lines.append("  department_id, company_id, department_code, department_name,")
        lines.append("  parent_department_id, level, display_order, is_active,")
        lines.append("  row_version, created_by, created_at, updated_by, updated_at")
        lines.append(") VALUES (")
        lines.append(f"  '{dept_id}', '{company_id}', '{company_code}_DEPT_{dept_name[:10]}', '{escape_sql(dept_name)}',")
        lines.append(f"  NULL, 1, 1, TRUE,")
        lines.append(f"  0, @system_principal_id, @now, @system_principal_id, @now")
        lines.append(");")
        lines.append("")

    # 2. 插入人员
    lines.append("-- ============================================================================")
    lines.append(f"-- 2. 批量插入人员 ({len(people)} 人)")
    lines.append("-- ============================================================================")
    lines.append("")

    # 按公司分组
    people_by_company = {}
    for p in people:
        code = p['company_code']
        if code not in people_by_company:
            people_by_company[code] = []
        people_by_company[code].append(p)

    for company_code in ['SZSZ', 'SZJN', 'SZSC', 'SZXY']:
        if company_code not in people_by_company:
            continue

        company_people = people_by_company[company_code]
        _, company_display = COMPANIES[company_code]

        lines.append(f"-- {company_display} ({len(company_people)} 人)")
        lines.append("")

        # 分批插入（每批 50 人）
        batch_size = 50
        for i in range(0, len(company_people), batch_size):
            batch = company_people[i:i+batch_size]

            lines.append("INSERT INTO person (")
            lines.append("  person_id, company_id, employee_number, full_name,")
            lines.append("  department_id, position, mobile, email, hire_date, status,")
            lines.append("  row_version, created_by, created_at, updated_by, updated_at")
            lines.append(") VALUES")

            for j, p in enumerate(batch):
                mobile = f"'{escape_sql(p['mobile'])}'" if p['mobile'] else 'NULL'
                email = f"'{escape_sql(p['email'])}'" if p['email'] else 'NULL'
                position = f"'{escape_sql(p['position'])}'" if p['position'] else 'NULL'
                hire_date = format_date(p['hire_date'])

                comma = ',' if j < len(batch) - 1 else ';'

                lines.append(f"  ('{p['person_id']}', '{p['company_id']}', '{escape_sql(p['employee_number'])}', '{escape_sql(p['full_name'])}',")
                lines.append(f"   '{p['department_id']}', {position}, {mobile}, {email}, {hire_date}, '{p['status']}',")
                lines.append(f"   0, @system_principal_id, @now, @system_principal_id, @now){comma}")

            lines.append("")

    # 3. 统计信息
    lines.append("-- ============================================================================")
    lines.append("-- 3. 验证统计")
    lines.append("-- ============================================================================")
    lines.append("")
    lines.append("SELECT ")
    lines.append("  c.company_code,")
    lines.append("  c.company_name,")
    lines.append("  COUNT(p.person_id) AS person_count")
    lines.append("FROM company c")
    lines.append("LEFT JOIN person p ON p.company_id = c.company_id")
    lines.append("WHERE c.company_code IN ('SZSZ', 'SZJN', 'SZSC', 'SZXY')")
    lines.append("GROUP BY c.company_code, c.company_name")
    lines.append("ORDER BY c.company_code;")
    lines.append("")
    lines.append(f"-- 预期总人数: {len(people)} 人")

    return '\n'.join(lines)

# ============================================================================
# 主函数
# ============================================================================

def main():
    print("=" * 80)
    print("🚀 生成神州HR完整人员期初数据 SQL 脚本")
    print("=" * 80)
    print()

    # 1. 读取部门
    departments = read_departments()
    print()

    # 2. 读取人员
    people = read_people(departments)
    print()

    # 3. 生成 SQL
    print("📝 生成 SQL 脚本...")
    sql_content = generate_sql(people, departments)

    # 4. 写入文件
    with open(OUTPUT_SQL, 'w', encoding='utf-8') as f:
        f.write(sql_content)

    print(f"✅ SQL 脚本已生成: {OUTPUT_SQL}")
    print()

    # 5. 统计
    print("=" * 80)
    print("📊 统计信息")
    print("=" * 80)
    print(f"总部门数: {len(departments)}")
    print(f"总人数: {len(people)}")
    print()

    # 按公司统计
    people_by_company = {}
    for p in people:
        code = p['company_code']
        people_by_company[code] = people_by_company.get(code, 0) + 1

    for code in ['SZSZ', 'SZJN', 'SZSC', 'SZXY']:
        _, name = COMPANIES[code]
        count = people_by_company.get(code, 0)
        print(f"  {code} {name}: {count} 人")

    print()
    print("✅ 完成！")
    print()
    print("📌 下一步：")
    print("  1. 检查生成的 SQL 文件")
    print(f"  2. 复制到服务器: scp {OUTPUT_SQL} root@服务器:/root/")
    print("  3. 在服务器上执行: mysql -u root -p shenzhou_hr < V5.1__full_people_initial_import.sql")

if __name__ == '__main__':
    main()

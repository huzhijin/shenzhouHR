#!/usr/bin/env bash

if [[ -n "${SHENZHOUHR_COMPANY_CUTOVER_LOADED:-}" ]]; then
  return 0
fi
SHENZHOUHR_COMPANY_CUTOVER_LOADED=1

readonly COMPANY_CUTOVER_V10_TABLE_PATTERN="^(legal_entity|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"
readonly COMPANY_CUTOVER_LATEST_TABLE_PATTERN="^(company|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"

company_cutover_current_version() {
  local database="$1"
  local defaults_file="$2"
  local history_table_count
  local failed_count
  local current_version
  assert_exact_database "$database"
  history_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME = 'flyway_schema_history'
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  case "$history_table_count" in
    0)
      printf '0'
      return 0
      ;;
    1)
      ;;
    *)
      fail "Flyway history table shape is ambiguous at company cutover."
      ;;
  esac
  failed_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM flyway_schema_history
    WHERE success <> 1;
  ")"
  [[ "$failed_count" == "0" ]] \
    || fail "Flyway history contains a failed migration at company cutover."
  current_version="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$current_version" =~ ^[0-9]+$ ]] \
    || fail "Flyway history has a non-numeric SQL migration version."
  printf '%s' "$current_version"
}

company_cutover_boundary_orphan_count() {
  local database="$1"
  local defaults_file="$2"
  local schema_mode="$3"
  local boundary_table
  local boundary_column
  local dependent_table
  local sql_union=""
  case "$schema_mode" in
    v10)
      boundary_table="legal_entity"
      boundary_column="legal_entity_id"
      ;;
    latest)
      boundary_table="company"
      boundary_column="company_id"
      ;;
    *)
      fail "Unknown company cutover orphan schema mode."
      ;;
  esac
  while IFS= read -r dependent_table; do
    [[ -n "$dependent_table" ]] || continue
    if [[ -n "$sql_union" ]]; then
      sql_union+=" UNION ALL "
    fi
    sql_union+="
      SELECT COUNT(*) AS orphan_count
      FROM \`${dependent_table}\` dependent
      LEFT JOIN \`${boundary_table}\` boundary
        ON boundary.\`${boundary_column}\` =
           dependent.\`${boundary_column}\`
      WHERE dependent.\`${boundary_column}\` IS NOT NULL
        AND boundary.\`${boundary_column}\` IS NULL"
  done <<'TABLES'
employee
organization_identity
auth_data_scope
people_import_batch
people_import_publication
location
shift_template
work_calendar
attendance_group
attendance_policy_scope
attendance_source
source_device
device_person_binding
attendance_evidence_subject_lock
raw_attendance_fact
effective_attendance_event
duplicate_review_group
evidence_interval_slice
attendance_recalculation_intent
punch_mapping_profile
punch_import_batch
punch_import_file
attendance_report_projection
attendance_report_daily_fact
attendance_report_oa_fact
attendance_report_exception_fact
attendance_report_time_account_fact
attendance_report_export_job
TABLES
  mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(SUM(orphan_count), 0)
    FROM (${sql_union}) company_boundary_orphans;
  "
}

company_cutover_scope_check_matches() {
  local database="$1"
  local defaults_file="$2"
  local schema_mode="$3"
  local constraint_shape
  local check_clause
  constraint_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(*),
      COALESCE(SUM(CASE WHEN tc.ENFORCED = 'YES' THEN 1 ELSE 0 END), 0)
    )
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = '${database}'
      AND tc.TABLE_NAME = 'auth_data_scope'
      AND tc.CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND tc.CONSTRAINT_TYPE = 'CHECK';
  ")"
  [[ "$constraint_shape" == "1|1" ]] || return 1
  check_clause="$(mysql_scalar "$defaults_file" "" "
    SELECT cc.CHECK_CLAUSE
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = '${database}'
      AND tc.TABLE_NAME = 'auth_data_scope'
      AND tc.CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
      AND tc.ENFORCED = 'YES';
  ")"
  command -v python3 >/dev/null 2>&1 || return 1
  python3 - "$schema_mode" "$check_clause" <<'PY'
import re
import sys

mode, clause = sys.argv[1:]
expected_by_mode = {
    "v10": """
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
    """,
    "latest": """
        (scope_type = 'COMPANY'
          AND company_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND company_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND company_id IS NULL
          AND organization_id IS NULL)
    """,
}
if mode not in expected_by_mode:
    raise SystemExit(1)


def tokens(value):
    value = value.replace("\\'", "'")
    value = value.replace("`", "")
    value = re.sub(
        r"(?<![A-Za-z0-9_'])_[A-Za-z0-9]+(?=')",
        "",
        value,
    )
    pattern = re.compile(
        r"\s*(?:"
        r"(?P<left>\()|(?P<right>\))|(?P<equal>=)|"
        r"(?P<string>'(?:''|[^'])*')|"
        r"(?P<word>[A-Za-z_][A-Za-z0-9_]*)"
        r")"
    )
    result = []
    offset = 0
    while offset < len(value):
        match = pattern.match(value, offset)
        if match is None:
            if value[offset:].strip() == "":
                break
            raise ValueError("unsupported CHECK token")
        result.append(match.group(match.lastgroup))
        offset = match.end()
    return result


class Parser:
    def __init__(self, values):
        self.values = values
        self.offset = 0

    def peek(self):
        if self.offset >= len(self.values):
            return None
        return self.values[self.offset]

    def take(self, expected=None):
        value = self.peek()
        if value is None or (
            expected is not None and value.upper() != expected
        ):
            raise ValueError("unexpected CHECK structure")
        self.offset += 1
        return value

    @staticmethod
    def combine(operator, nodes):
        flattened = []
        for node in nodes:
            if node[0] == operator:
                flattened.extend(node[1])
            else:
                flattened.append(node)
        if len(flattened) == 1:
            return flattened[0]
        return operator, tuple(sorted(flattened, key=repr))

    def parse(self):
        result = self.parse_or()
        if self.peek() is not None:
            raise ValueError("trailing CHECK tokens")
        return result

    def parse_or(self):
        nodes = [self.parse_and()]
        while self.peek() is not None and self.peek().upper() == "OR":
            self.take("OR")
            nodes.append(self.parse_and())
        return self.combine("or", nodes)

    def parse_and(self):
        nodes = [self.parse_factor()]
        while self.peek() is not None and self.peek().upper() == "AND":
            self.take("AND")
            nodes.append(self.parse_factor())
        return self.combine("and", nodes)

    def parse_factor(self):
        if self.peek() == "(":
            self.take("(")
            result = self.parse_or()
            self.take(")")
            return result
        return self.parse_atom()

    def parse_atom(self):
        identifier = self.take().lower()
        if not re.fullmatch(r"[a-z_][a-z0-9_]*", identifier):
            raise ValueError("invalid CHECK identifier")
        operator = self.take()
        if operator == "=":
            literal = self.take()
            if not (literal.startswith("'") and literal.endswith("'")):
                raise ValueError("CHECK equality requires a string literal")
            return "eq", identifier, literal[1:-1]
        if operator.upper() != "IS":
            raise ValueError("unsupported CHECK operator")
        negated = False
        if self.peek() is not None and self.peek().upper() == "NOT":
            self.take("NOT")
            negated = True
        self.take("NULL")
        return ("not_null" if negated else "null"), identifier


try:
    actual = Parser(tokens(clause)).parse()
    expected = Parser(tokens(expected_by_mode[mode])).parse()
except ValueError:
    raise SystemExit(1)
raise SystemExit(0 if actual == expected else 1)
PY
}

company_cutover_auxiliary_check_contract_matches() {
  local database="$1"
  local defaults_file="$2"
  local schema_mode="$3"
  local table_pattern
  local check_shape
  case "$schema_mode" in
    v10)
      table_pattern="$COMPANY_CUTOVER_V10_TABLE_PATTERN"
      ;;
    latest)
      table_pattern="$COMPANY_CUTOVER_LATEST_TABLE_PATTERN"
      ;;
    *)
      return 1
      ;;
  esac
  check_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(*),
      SUM(CASE WHEN ENFORCED = 'YES' THEN 1 ELSE 0 END),
      COUNT(DISTINCT CASE
        WHEN CONCAT(TABLE_NAME, '.', CONSTRAINT_NAME) IN (
          'auth_data_scope.ck_auth_scope_period',
          'people_import_batch.ck_people_import_status',
          'people_import_batch.ck_people_import_template_type',
          'attendance_source.ck_att_source_status',
          'attendance_source.ck_att_source_type',
          'device_person_binding.ck_device_person_period',
          'raw_attendance_fact.ck_raw_fact_identity',
          'raw_attendance_fact.ck_raw_fact_kind',
          'raw_attendance_fact.ck_raw_fact_temporal',
          'effective_attendance_event.ck_effective_event_direction',
          'effective_attendance_event.ck_effective_event_kind',
          'effective_attendance_event.ck_effective_event_temporal',
          'duplicate_review_group.ck_duplicate_group_direction',
          'duplicate_review_group.ck_duplicate_group_status',
          'duplicate_review_group.ck_duplicate_group_window',
          'evidence_interval_slice.ck_evidence_slice_period',
          'evidence_interval_slice.ck_evidence_slice_status',
          'evidence_interval_slice.ck_evidence_slice_winner',
          'punch_import_file.ck_punch_file_scan',
          'punch_import_file.ck_punch_file_size',
          'attendance_report_projection.ck_att_report_projection_period',
          'attendance_report_projection.ck_att_report_projection_publish',
          'attendance_report_projection.ck_att_report_projection_state',
          'attendance_report_projection.ck_att_report_projection_status',
          'attendance_report_daily_fact.ck_att_report_daily_actual_work',
          'attendance_report_daily_fact.ck_att_report_daily_day_type',
          'attendance_report_daily_fact.ck_att_report_daily_late',
          'attendance_report_daily_fact.ck_att_report_daily_punch_order',
          'attendance_report_oa_fact.ck_att_report_oa_status',
          'attendance_report_oa_fact.ck_att_report_oa_temporal_shape',
          'attendance_report_oa_fact.ck_att_report_oa_type',
          'attendance_report_exception_fact.ck_att_report_exception_severity',
          'attendance_report_exception_fact.ck_att_report_exception_state',
          'attendance_report_time_account_fact.ck_att_report_account_type',
          'attendance_report_export_job.ck_att_report_export_delivery',
          'attendance_report_export_job.ck_att_report_export_digests',
          'attendance_report_export_job.ck_att_report_export_extension',
          'attendance_report_export_job.ck_att_report_export_fields',
          'attendance_report_export_job.ck_att_report_export_period',
          'attendance_report_export_job.ck_att_report_export_purpose',
          'attendance_report_export_job.ck_att_report_export_state',
          'attendance_report_export_job.ck_att_report_export_status',
          'attendance_report_export_job.ck_att_report_export_time',
          'attendance_report_export_job.ck_att_report_export_type'
        )
        THEN CONCAT(TABLE_NAME, '.', CONSTRAINT_NAME)
      END)
    )
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND TABLE_NAME REGEXP '${table_pattern}'
      AND NOT (
        TABLE_NAME = 'auth_data_scope'
        AND CONSTRAINT_NAME = 'ck_auth_scope_target'
      );
  ")"
  [[ "$check_shape" == "44|44|44" ]]
}

company_cutover_index_contract_matches() {
  local database="$1"
  local defaults_file="$2"
  local schema_mode="$3"
  local index_shape
  index_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(DISTINCT CASE
        WHEN (TABLE_NAME = 'legal_entity'
                AND INDEX_NAME = 'uq_legal_entity_code')
          OR (TABLE_NAME = 'employee'
                AND INDEX_NAME = 'ix_employee_legal_entity_status')
          OR (TABLE_NAME = 'organization_identity'
                AND INDEX_NAME = 'ix_organization_identity_legal_entity')
          OR (TABLE_NAME = 'attendance_policy_scope'
                AND INDEX_NAME = 'ix_attendance_policy_scope_legal_entity')
          OR (TABLE_NAME = 'attendance_source'
                AND INDEX_NAME = 'uq_att_source_id_entity')
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN INDEX_NAME IN (
          'uq_legal_entity_code',
          'ix_employee_legal_entity_status',
          'ix_organization_identity_legal_entity',
          'ix_attendance_policy_scope_legal_entity',
          'uq_att_source_id_entity'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN (TABLE_NAME = 'company'
                AND INDEX_NAME = 'uq_company_code')
          OR (TABLE_NAME = 'employee'
                AND INDEX_NAME = 'ix_employee_company_status')
          OR (TABLE_NAME = 'organization_identity'
                AND INDEX_NAME = 'ix_organization_identity_company')
          OR (TABLE_NAME = 'attendance_policy_scope'
                AND INDEX_NAME = 'ix_attendance_policy_scope_company')
          OR (TABLE_NAME = 'attendance_source'
                AND INDEX_NAME = 'uq_att_source_id_company')
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN INDEX_NAME IN (
          'uq_company_code',
          'ix_employee_company_status',
          'ix_organization_identity_company',
          'ix_attendance_policy_scope_company',
          'uq_att_source_id_company'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END)
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}';
  ")"
  case "$schema_mode" in
    v10)
      [[ "$index_shape" == "5|5|0|0" ]]
      ;;
    latest)
      [[ "$index_shape" == "0|0|5|5" ]]
      ;;
    *)
      return 1
      ;;
  esac
}

company_cutover_relationship_contract_matches() {
  local database="$1"
  local defaults_file="$2"
  local schema_mode="$3"
  local boundary_table
  local boundary_column
  local table_pattern
  local relationship_shape
  case "$schema_mode" in
    v10)
      boundary_table="legal_entity"
      boundary_column="legal_entity_id"
      table_pattern="$COMPANY_CUTOVER_V10_TABLE_PATTERN"
      ;;
    latest)
      boundary_table="company"
      boundary_column="company_id"
      table_pattern="$COMPANY_CUTOVER_LATEST_TABLE_PATTERN"
      ;;
    *)
      return 1
      ;;
  esac
  relationship_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(DISTINCT TABLE_NAME),
      COUNT(DISTINCT CONCAT(
        TABLE_NAME, CHAR(0), REFERENCED_TABLE_NAME)),
      COUNT(DISTINCT CASE
        WHEN (
          TABLE_NAME IN (
            'employee',
            'organization_identity',
            'auth_data_scope',
            'people_import_batch',
            'people_import_publication',
            'location',
            'shift_template',
            'work_calendar',
            'attendance_group',
            'attendance_policy_scope',
            'attendance_source',
            'attendance_evidence_subject_lock',
            'effective_attendance_event',
            'duplicate_review_group',
            'evidence_interval_slice',
            'attendance_recalculation_intent',
            'punch_mapping_profile',
            'attendance_report_projection',
            'attendance_report_export_job'
          )
          AND REFERENCED_TABLE_NAME = '${boundary_table}'
        )
        OR (
          TABLE_NAME IN (
            'source_device',
            'raw_attendance_fact',
            'punch_import_batch',
            'punch_import_file'
          )
          AND REFERENCED_TABLE_NAME = 'attendance_source'
        )
        OR (
          TABLE_NAME = 'device_person_binding'
          AND REFERENCED_TABLE_NAME IN (
            'source_device',
            'attendance_source'
          )
        )
        OR (
          TABLE_NAME IN (
            'attendance_report_daily_fact',
            'attendance_report_oa_fact',
            'attendance_report_exception_fact',
            'attendance_report_time_account_fact'
          )
          AND REFERENCED_TABLE_NAME = 'attendance_report_projection'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), REFERENCED_TABLE_NAME)
      END),
      COUNT(DISTINCT CONCAT(
        TABLE_NAME, CHAR(0), CONSTRAINT_NAME,
        CHAR(0), REFERENCED_TABLE_NAME))
    )
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${table_pattern}'
      AND TABLE_NAME <> '${boundary_table}'
      AND COLUMN_NAME = '${boundary_column}'
      AND REFERENCED_COLUMN_NAME = '${boundary_column}';
  ")"
  [[ "$relationship_shape" == "28|29|29|29" ]]
}

company_cutover_verify_v10() {
  local database="$1"
  local defaults_file="$2"
  local current_version
  local v11_history_count
  local schema_shape
  local scope_violations
  local orphan_count
  local setup_started
  local ingestion_started
  assert_exact_database "$database"
  current_version="$(company_cutover_current_version \
    "$database" "$defaults_file")"
  [[ "$current_version" == "10" ]] \
    || fail "Company V11 cutover requires the exact V10 checkpoint."
  v11_history_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND version = '11';
  ")"
  [[ "$v11_history_count" == "0" ]] \
    || fail "Company V11 cutover found partial V11 history."
  schema_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME REGEXP '${COMPANY_CUTOVER_V10_TABLE_PATTERN}'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${COMPANY_CUTOVER_V10_TABLE_PATTERN}'
          AND COLUMN_NAME = 'legal_entity_id'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME = 'company'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${COMPANY_CUTOVER_V10_TABLE_PATTERN}'
          AND COLUMN_NAME = 'company_id'
      )
    );
  ")"
  [[ "$schema_shape" == "29|29|0|0" ]] \
    || fail "Company V11 cutover requires the exact 29-table V10 shape."
  company_cutover_relationship_contract_matches \
    "$database" "$defaults_file" v10 \
    || fail "Company V11 cutover requires 28 tables and 29 exact V10 relationship edges."
  scope_violations="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_data_scope
    WHERE scope_type NOT IN ('LEGAL_ENTITY', 'ORGANIZATION', 'SELF')
       OR NOT (
            (scope_type = 'LEGAL_ENTITY'
              AND legal_entity_id IS NOT NULL
              AND organization_id IS NULL)
            OR (scope_type = 'ORGANIZATION'
              AND legal_entity_id IS NULL
              AND organization_id IS NOT NULL)
            OR (scope_type = 'SELF'
              AND legal_entity_id IS NULL
              AND organization_id IS NULL)
       );
  ")"
  [[ "$scope_violations" == "0" ]] \
    || fail "Company V11 cutover found an invalid V10 authorization scope."
  company_cutover_scope_check_matches \
    "$database" "$defaults_file" v10 \
    || fail "Company V11 cutover requires the exact enforced V10 scope CHECK."
  company_cutover_auxiliary_check_contract_matches \
    "$database" "$defaults_file" v10 \
    || fail "Company V11 cutover requires 44 exact enforced auxiliary CHECKs."
  company_cutover_index_contract_matches \
    "$database" "$defaults_file" v10 \
    || fail "Company V11 cutover requires five old and zero new index pairs."
  orphan_count="$(company_cutover_boundary_orphan_count \
    "$database" "$defaults_file" v10)"
  [[ "$orphan_count" == "0" ]] \
    || fail "Company V11 cutover found an orphan V10 company reference."
  setup_started="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM attendance_setup_idempotency
    WHERE state = 'STARTED';
  ")"
  [[ "$setup_started" == "0" ]] \
    || fail "Company V11 cutover is blocked by started setup work."
  ingestion_started="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM attendance_ingestion_idempotency
    WHERE status = 'STARTED';
  ")"
  [[ "$ingestion_started" == "0" ]] \
    || fail "Company V11 cutover is blocked by started ingestion work."
  log "COMPANY_V11_EXACT_PREFLIGHT=PASS database=${database} version=10 tables=29 columns=29 dependent_tables=28 relationships=29 auxiliary_checks=44 enforced=44 old_indexes=5 new_indexes=0 orphans=0 setup_started=0 ingestion_started=0"
}

company_cutover_verify_latest() {
  local database="$1"
  local defaults_file="$2"
  local current_version
  local v11_history_count
  local schema_shape
  local legacy_relationship_count
  local scope_violations
  local orphan_count
  assert_exact_database "$database"
  current_version="$(company_cutover_current_version \
    "$database" "$defaults_file")"
  ((10#$current_version >= 11)) \
    || fail "Latest company contract requires V11 or newer history."
  v11_history_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND version = '11' AND success = 1;
  ")"
  [[ "$v11_history_count" == "1" ]] \
    || fail "Latest company contract requires one successful V11 migration."
  schema_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME REGEXP '${COMPANY_CUTOVER_LATEST_TABLE_PATTERN}'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${COMPANY_CUTOVER_LATEST_TABLE_PATTERN}'
          AND COLUMN_NAME = 'company_id'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME = 'legal_entity'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND COLUMN_NAME = 'legal_entity_id'
      )
    );
  ")"
  [[ "$schema_shape" == "29|29|0|0" ]] \
    || fail "Latest company contract requires the exact 29-table shape."
  company_cutover_relationship_contract_matches \
    "$database" "$defaults_file" latest \
    || fail "Latest company contract requires 28 tables and 29 exact relationship edges."
  legacy_relationship_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND (
        COLUMN_NAME = 'legal_entity_id'
        OR REFERENCED_COLUMN_NAME = 'legal_entity_id'
        OR REFERENCED_TABLE_NAME = 'legal_entity'
      );
  ")"
  [[ "$legacy_relationship_count" == "0" ]] \
    || fail "Latest company contract retains an old boundary relationship."
  scope_violations="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_data_scope
    WHERE scope_type NOT IN ('COMPANY', 'ORGANIZATION', 'SELF')
       OR NOT (
            (scope_type = 'COMPANY'
              AND company_id IS NOT NULL
              AND organization_id IS NULL)
            OR (scope_type = 'ORGANIZATION'
              AND company_id IS NULL
              AND organization_id IS NOT NULL)
            OR (scope_type = 'SELF'
              AND company_id IS NULL
              AND organization_id IS NULL)
       );
  ")"
  [[ "$scope_violations" == "0" ]] \
    || fail "Latest company contract contains an invalid authorization scope."
  company_cutover_scope_check_matches \
    "$database" "$defaults_file" latest \
    || fail "Latest company contract requires the exact enforced scope CHECK."
  company_cutover_auxiliary_check_contract_matches \
    "$database" "$defaults_file" latest \
    || fail "Latest company contract requires 44 exact enforced auxiliary CHECKs."
  company_cutover_index_contract_matches \
    "$database" "$defaults_file" latest \
    || fail "Latest company contract requires zero old and five new index pairs."
  orphan_count="$(company_cutover_boundary_orphan_count \
    "$database" "$defaults_file" latest)"
  [[ "$orphan_count" == "0" ]] \
    || fail "Latest company contract contains an orphan company reference."
  log "COMPANY_DIMENSION_EXACT_LATEST=PASS database=${database} version=${current_version} tables=29 columns=29 dependent_tables=28 relationships=29 auxiliary_checks=44 enforced=44 old_indexes=0 new_indexes=5 orphans=0"
}

company_cutover_migrate_to_v11() {
  local database="$1"
  local defaults_file="$2"
  local current_version
  assert_exact_database "$database"
  current_version="$(company_cutover_current_version \
    "$database" "$defaults_file")"
  if ((10#$current_version < 10)); then
    run_flyway "$database" "-target=10" migrate
    current_version="$(company_cutover_current_version \
      "$database" "$defaults_file")"
    [[ "$current_version" == "10" ]] \
      || fail "Company cutover did not stop at the exact V10 checkpoint."
  fi
  if [[ "$current_version" == "10" ]]; then
    company_cutover_verify_v10 "$database" "$defaults_file"
    run_flyway "$database" "-target=11" migrate
    current_version="$(company_cutover_current_version \
      "$database" "$defaults_file")"
    [[ "$current_version" == "11" ]] \
      || fail "Company cutover did not stop at the exact V11 checkpoint."
  fi
  ((10#$current_version >= 11)) \
    || fail "Company cutover cannot proceed from this Flyway history."
  company_cutover_verify_latest "$database" "$defaults_file"
}

-- Read-only acceptance checks for the four-company finalization.
--
-- This script deliberately contains only one SELECT statement. It does not
-- create procedures, temporary tables, variables, locks, or persistent rows.
-- Every assertion returns PASS or FAIL, and overall_status is FAIL when any
-- assertion fails. Consumers must reject the deployment unless every row and
-- overall_status are PASS.

WITH
expected_company AS (
    SELECT 'SZSZ' AS code, '上海昇州半导体科技有限公司' AS name,
           2 AS organization_count, 1 AS employee_count
    UNION ALL
    SELECT 'SZJN', '上海晟州聚能半导体科技有限公司', 17, 35
    UNION ALL
    SELECT 'SZSC', '江苏神州半导体科技股份有限公司', 130, 571
    UNION ALL
    SELECT 'SZXY', '江苏芯越半导体科技有限公司', 7, 8
),
expected_city AS (
    SELECT 'CHENGDU' AS city_code,'成都' AS city_name UNION ALL
    SELECT 'DALIAN','大连' UNION ALL SELECT 'SHANGHAI','上海' UNION ALL
    SELECT 'HEFEI','合肥' UNION ALL SELECT 'WUHAN','武汉' UNION ALL
    SELECT 'SHENZHEN','深圳'
),
expected_fixed_city_shift AS (
    SELECT 'DALIAN_FIXED' AS template_code,
           '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"07:30:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"16:30:00","endDayOffset":0}]}' AS expected_segments_json
    UNION ALL
    SELECT 'CHENGDU_FIXED',
           '{"effectiveTo":"2027-01-01","segments":[{"segmentType":"WORK","startLocalTime":"09:00:00","startDayOffset":0,"endLocalTime":"12:00:00","endDayOffset":0},{"segmentType":"BREAK","startLocalTime":"12:00:00","startDayOffset":0,"endLocalTime":"13:00:00","endDayOffset":0},{"segmentType":"WORK","startLocalTime":"13:00:00","startDayOffset":0,"endLocalTime":"18:00:00","endDayOffset":0}]}'
),
expected_named_assignment AS (
    SELECT 'SZST0074' employee_number,'路昊' display_name,'SZSC' company_code,
           'DALIAN_ATTENDANCE' group_code UNION ALL
    SELECT 'SZST0121','高攀','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0163','李政','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0207','王小龙','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0302','姜长波','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0388','王杰S','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0414','李春江','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0424','王松','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0445','霍岩','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0645','张泽','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0646','温慧杰','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0669','张清雅','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0708','戢昱','SZSC','DALIAN_ATTENDANCE' UNION ALL
    SELECT 'SZST0511','周文武','SZSC','CHENGDU_ATTENDANCE' UNION ALL
    SELECT 'SZST0512','周彦沛','SZSC','CHENGDU_ATTENDANCE' UNION ALL
    SELECT 'SZST0618','彭帆','SZSC','CHENGDU_ATTENDANCE' UNION ALL
    SELECT 'SZST0537','唐浩','SZSC','CHENGDU_ATTENDANCE' UNION ALL
    SELECT 'SZST0442','张静','SZSC','SHANGHAI_ATTENDANCE' UNION ALL
    SELECT 'SZJN0012','赵俊君','SZJN','DEFAULT_ATTENDANCE' UNION ALL
    SELECT 'SZJN0021','时晨','SZJN','DEFAULT_ATTENDANCE' UNION ALL
    SELECT 'SZJN0030','王颂雅','SZJN','DEFAULT_ATTENDANCE'
),
active_company AS (
    SELECT company.company_id, company.code, company.name
    FROM company
    WHERE company.status = 'ACTIVE'
),
fixed_city_shift_audit AS (
    SELECT expected.template_code,
           COUNT(version.shift_version_id) AS matching_count
    FROM expected_fixed_city_shift expected
    LEFT JOIN shift_template template
      ON template.template_code=expected.template_code
    LEFT JOIN active_company company
      ON company.company_id=template.company_id AND company.code='SZSC'
    LEFT JOIN shift_version version
      ON version.shift_template_id=template.shift_template_id
     AND version.version_number=1
     AND version.effective_from='2026-01-01'
     AND version.time_zone_snapshot='Asia/Shanghai'
     AND version.segments_json=CAST(expected.expected_segments_json AS JSON)
    GROUP BY expected.template_code
),
latest_assignment_timeline AS (
    SELECT timeline.attendance_group_assignment_id,
           timeline.state,
           timeline.business_effective_from
    FROM attendance_assignment_timeline timeline
    WHERE timeline.event_sequence = (
        SELECT MAX(latest.event_sequence)
        FROM attendance_assignment_timeline latest
        WHERE latest.attendance_group_assignment_id =
              timeline.attendance_group_assignment_id
    )
),
current_resolved_assignment AS (
    SELECT assignment.attendance_group_assignment_id,
           assignment.employee_id,
           attendance_group.company_id AS group_company_id,
           attendance_group.group_code
    FROM attendance_group_assignment assignment
    JOIN attendance_group_revision group_revision
      ON group_revision.attendance_group_revision_id =
         assignment.attendance_group_revision_id
    JOIN attendance_group
      ON attendance_group.attendance_group_id =
         group_revision.attendance_group_id
    JOIN latest_assignment_timeline timeline
      ON timeline.attendance_group_assignment_id =
         assignment.attendance_group_assignment_id
     AND timeline.state = 'ACTIVE'
     AND timeline.business_effective_from <= CURRENT_DATE()
    WHERE assignment.effective_from <= CURRENT_DATE()
      AND NOT EXISTS (
          SELECT 1
          FROM attendance_group_assignment successor
          WHERE successor.supersedes_assignment_id =
                assignment.attendance_group_assignment_id
            AND successor.effective_from <= CURRENT_DATE()
      )
),
employee_resolution AS (
    SELECT employee.employee_id,
           employee.company_id,
           COUNT(resolved.attendance_group_assignment_id) AS resolved_count,
           SUM(CASE
                   WHEN resolved.group_company_id = employee.company_id THEN 1
                   ELSE 0
               END) AS matching_company_count
    FROM employee
    JOIN active_company
      ON active_company.company_id = employee.company_id
    LEFT JOIN current_resolved_assignment resolved
      ON resolved.employee_id = employee.employee_id
    GROUP BY employee.employee_id, employee.company_id
),
named_assignment_audit AS (
    SELECT expected.employee_number,expected.display_name,
           expected.company_code,expected.group_code,
           COUNT(resolved.attendance_group_assignment_id) AS matching_count
    FROM expected_named_assignment expected
    LEFT JOIN active_company company ON company.code=expected.company_code
    LEFT JOIN employee
      ON employee.employee_number=expected.employee_number
     AND employee.display_name=expected.display_name
     AND employee.company_id=company.company_id
    LEFT JOIN current_resolved_assignment resolved
      ON resolved.employee_id=employee.employee_id
     AND resolved.group_company_id=company.company_id
     AND resolved.group_code=expected.group_code
    GROUP BY expected.employee_number,expected.display_name,
             expected.company_code,expected.group_code
),
city_location_audit AS (
    SELECT company.code,city.city_code,city.city_name,
           COUNT(timeline.location_timeline_id) AS matching_count
    FROM active_company company
    CROSS JOIN expected_city city
    LEFT JOIN location
      ON location.company_id=company.company_id
     AND location.location_code=city.city_code
    LEFT JOIN location_revision revision
      ON revision.location_id=location.location_id
     AND revision.location_name=city.city_name
     AND revision.time_zone='Asia/Shanghai'
    LEFT JOIN location_timeline timeline
      ON timeline.location_id=location.location_id
     AND timeline.location_revision_id=revision.location_revision_id
     AND timeline.event_sequence=1 AND timeline.state='ACTIVE'
    GROUP BY company.code,city.city_code,city.city_name
),
active_admin AS (
    SELECT principal.principal_id
    FROM local_account account
    JOIN auth_principal principal
      ON principal.principal_id = account.principal_id
    WHERE account.display_name = 'SZSC 初始管理员'
      AND account.status = 'ACTIVE'
      AND principal.status = 'ACTIVE'
),
policy_canonical_parameters AS (
    SELECT version.scoped_version_id,
           CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'{',GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               JSON_QUOTE(parameter_key.parameter_key),':',
               REPLACE(CAST(JSON_EXTRACT(
                   version.parameters_json,
                   CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'$.',parameter_key.parameter_key)
               ) AS CHAR),', ',','))
               ORDER BY CONVERT(parameter_key.parameter_key USING utf8mb4)
                        COLLATE utf8mb4_bin SEPARATOR ','),'}')
               AS canonical_parameters
    FROM attendance_policy_scoped_version version
    JOIN attendance_policy_scope scope ON scope.scope_id = version.scope_id
    JOIN active_company company ON company.company_id = scope.company_id
    JOIN JSON_TABLE(
        JSON_KEYS(version.parameters_json),
        '$[*]' COLUMNS(parameter_key VARCHAR(128) PATH '$')
    ) parameter_key ON TRUE
    GROUP BY version.scoped_version_id
),
policy_snapshot_audit AS (
    SELECT version.scoped_version_id,
           version.parameters_json,
           version.effective_from,
           version.effective_to,
           version.version_number,
           version.snapshot_json,
           version.snapshot_digest,
           scope.scope_id,
           scope.company_id,
           scope.policy_template_id,
           template.template_code,
           canonical.snapshot_json AS canonical_snapshot_json
    FROM attendance_policy_scoped_version version
    JOIN attendance_policy_scope scope ON scope.scope_id = version.scope_id
    JOIN attendance_policy_template template
      ON template.policy_template_id = scope.policy_template_id
    JOIN active_company company ON company.company_id = scope.company_id
    JOIN policy_canonical_parameters parameters
      ON parameters.scoped_version_id = version.scoped_version_id
    CROSS JOIN LATERAL (
        SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
            '{"companyId":',JSON_QUOTE(scope.company_id),
            ',"effectiveFrom":',
                JSON_QUOTE(DATE_FORMAT(version.effective_from,'%Y-%m-%d')),
            ',"effectiveTo":',IF(version.effective_to IS NULL,'null',
                JSON_QUOTE(DATE_FORMAT(version.effective_to,'%Y-%m-%d'))),
            ',"parameters":',parameters.canonical_parameters,
            ',"policyKind":',JSON_QUOTE(template.template_code),
            ',"scopeId":',JSON_QUOTE(scope.scope_id),
            ',"templateId":',JSON_QUOTE(scope.policy_template_id),
            ',"versionNumber":',version.version_number,'}'
        ) AS snapshot_json
    ) canonical
),
policy_snapshot_metrics AS (
    SELECT COUNT(*) AS snapshot_count,
           SUM(CASE WHEN
               COALESCE(JSON_LENGTH(snapshot_json),-1)<>8
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.companyId'))
                       <=> company_id)
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.effectiveFrom'))
                       <=> DATE_FORMAT(effective_from,'%Y-%m-%d'))
               OR (effective_to IS NULL AND
                   COALESCE(JSON_TYPE(JSON_EXTRACT(
                       snapshot_json,'$.effectiveTo')),'MISSING')<>'NULL')
               OR (effective_to IS NOT NULL AND NOT (
                   JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.effectiveTo'))
                   <=> DATE_FORMAT(effective_to,'%Y-%m-%d')))
               OR COALESCE(JSON_CONTAINS(
                      JSON_EXTRACT(snapshot_json,'$.parameters'),parameters_json),0)=0
               OR COALESCE(JSON_CONTAINS(
                      parameters_json,
                      JSON_EXTRACT(snapshot_json,'$.parameters')),0)=0
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.policyKind'))
                       <=> template_code)
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.scopeId'))
                       <=> scope_id)
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.templateId'))
                       <=> policy_template_id)
               OR NOT (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.versionNumber'))
                       <=> CAST(version_number AS CHAR))
               THEN 1 ELSE 0 END) AS field_mismatch_count,
           SUM(CASE WHEN snapshot_digest<>SHA2(canonical_snapshot_json,256)
               THEN 1 ELSE 0 END) AS digest_mismatch_count
    FROM policy_snapshot_audit
),
shift_canonical_segments AS (
    SELECT version.shift_version_id,
           COUNT(segment.ord) AS segment_count,
           COALESCE(GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'S:',OCTET_LENGTH(segment.segment_type),':',segment.segment_type,
               'S:',OCTET_LENGTH(TIME_FORMAT(TIME(segment.start_time),'%H:%i')),
                   ':',TIME_FORMAT(TIME(segment.start_time),'%H:%i'),
               'S:',OCTET_LENGTH(segment.start_day_delta),':',segment.start_day_delta,
               'S:',OCTET_LENGTH(TIME_FORMAT(TIME(segment.end_time),'%H:%i')),
                   ':',TIME_FORMAT(TIME(segment.end_time),'%H:%i'),
               'S:',OCTET_LENGTH(segment.end_day_delta),':',segment.end_day_delta)
               ORDER BY segment.ord SEPARATOR ''),'') AS segment_body
    FROM shift_version version
    JOIN shift_template template
      ON template.shift_template_id=version.shift_template_id
    JOIN active_company company ON company.company_id=template.company_id
    LEFT JOIN JSON_TABLE(version.segments_json,'$.segments[*]' COLUMNS(
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        start_day_delta INT PATH '$.startDayOffset',
        end_time VARCHAR(16) PATH '$.endLocalTime',
        end_day_delta INT PATH '$.endDayOffset'
    )) segment ON TRUE
    GROUP BY version.shift_version_id
),
shift_digest_metrics AS (
    SELECT COUNT(*) AS version_count,
           SUM(CASE WHEN version.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'S:17:shift-snapshot-v1',
               'S:36:',template.shift_template_id,
               'S:36:',version.shift_version_id,
               'S:',OCTET_LENGTH(version.version_number),':',version.version_number,
               'S:10:',version.effective_from,
               'S:10:',JSON_UNQUOTE(JSON_EXTRACT(
                   version.segments_json,'$.effectiveTo')),
               'S:',OCTET_LENGTH(version.time_zone_snapshot),
                   ':',version.time_zone_snapshot,
               'S:',OCTET_LENGTH(segments.segment_count),':',segments.segment_count,
               segments.segment_body),256) THEN 1 ELSE 0 END)
               AS mismatch_count
    FROM shift_version version
    JOIN shift_template template
      ON template.shift_template_id=version.shift_template_id
    JOIN active_company company ON company.company_id=template.company_id
    JOIN shift_canonical_segments segments
      ON segments.shift_version_id=version.shift_version_id
),
calendar_canonical_days AS (
    SELECT version.work_calendar_version_id,
           COUNT(day_record.work_calendar_day_id) AS day_count,
           COALESCE(GROUP_CONCAT(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'S:10:',day_record.business_date,
               'S:',OCTET_LENGTH(day_record.day_type),':',day_record.day_type,
               'N:0:') ORDER BY day_record.business_date,day_record.day_type
               SEPARATOR ''),'') AS day_body
    FROM work_calendar_version version
    JOIN work_calendar calendar
      ON calendar.work_calendar_id=version.work_calendar_id
    JOIN active_company company ON company.company_id=calendar.company_id
    LEFT JOIN work_calendar_day day_record
      ON day_record.work_calendar_version_id=version.work_calendar_version_id
    GROUP BY version.work_calendar_version_id
),
calendar_digest_metrics AS (
    SELECT COUNT(*) AS version_count,
           SUM(CASE WHEN version.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'S:',OCTET_LENGTH('work-calendar-snapshot-v1'),
                   ':work-calendar-snapshot-v1',
               'S:36:',calendar.company_id,'S:36:',calendar.location_id,
               'S:36:',calendar.work_calendar_id,
               'S:36:',version.work_calendar_version_id,
               'S:',OCTET_LENGTH(version.version_number),':',version.version_number,
               'S:',OCTET_LENGTH(calendar.calendar_code),':',calendar.calendar_code,
               'S:',OCTET_LENGTH(version.calendar_name),':',version.calendar_name,
               'S:',OCTET_LENGTH(version.calendar_year),':',version.calendar_year,
               'S:',OCTET_LENGTH(version.time_zone_snapshot),
                   ':',version.time_zone_snapshot,
               'S:10:',version.effective_from,'S:10:',version.effective_to,
               'S:',OCTET_LENGTH(days.day_count),':',days.day_count,days.day_body),256)
               THEN 1 ELSE 0 END) AS mismatch_count
    FROM work_calendar_version version
    JOIN work_calendar calendar
      ON calendar.work_calendar_id=version.work_calendar_id
    JOIN active_company company ON company.company_id=calendar.company_id
    JOIN calendar_canonical_days days
      ON days.work_calendar_version_id=version.work_calendar_version_id
),
resource_digest_metrics AS (
    SELECT
      (SELECT COUNT(*)
       FROM location_revision revision
       JOIN location ON location.location_id=revision.location_id
       JOIN active_company company ON company.company_id=location.company_id
       WHERE revision.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
           location.company_id,'|',location.location_code,'|',revision.location_name,
           '|',revision.time_zone,'|',revision.effective_from,'|',
           COALESCE(revision.supersedes_location_revision_id,'NULL')),256))
          AS location_mismatch_count,
      (SELECT COUNT(*)
       FROM attendance_group_revision revision
       JOIN attendance_group attendance_group
         ON attendance_group.attendance_group_id=revision.attendance_group_id
       JOIN active_company company
         ON company.company_id=attendance_group.company_id
       JOIN location_revision location_revision
         ON location_revision.location_revision_id=revision.location_revision_id
       WHERE revision.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
           attendance_group.company_id,'|',attendance_group.group_code,'|',
           revision.group_name,'|',revision.location_revision_id,'|',
           location_revision.time_zone,'|',revision.work_calendar_id,'|',
           revision.shift_template_id,'|',revision.effective_from,'|',
           COALESCE(revision.supersedes_attendance_group_revision_id,'NULL')),256))
          AS group_mismatch_count,
      (SELECT COUNT(*)
       FROM work_calendar_day day_record
       JOIN work_calendar_version version
         ON version.work_calendar_version_id=day_record.work_calendar_version_id
       JOIN work_calendar calendar
         ON calendar.work_calendar_id=version.work_calendar_id
       JOIN active_company company ON company.company_id=calendar.company_id
       WHERE day_record.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
           day_record.work_calendar_version_id,'|',day_record.business_date,'|',
           day_record.day_type,'|',
           COALESCE(day_record.shift_version_override_id,'NULL')),256))
          AS calendar_day_mismatch_count,
      (SELECT COUNT(*)
       FROM attendance_policy_binding_revision revision
       JOIN attendance_policy_binding_family family
         ON family.binding_family_id=revision.binding_family_id
       JOIN attendance_group attendance_group
         ON attendance_group.attendance_group_id=family.attendance_group_id
       JOIN active_company company
         ON company.company_id=attendance_group.company_id
       JOIN attendance_policy_scoped_version policy_version
         ON policy_version.scoped_version_id=
            revision.attendance_policy_scoped_version_id
       WHERE revision.snapshot_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
           revision.attendance_group_revision_id,'|',
           revision.attendance_policy_scoped_version_id,'|',
           policy_version.snapshot_digest,'|',revision.effective_from,'|',
           COALESCE(revision.supersedes_binding_revision_id,'NULL')),256))
          AS binding_mismatch_count,
      (SELECT COUNT(*)
       FROM attendance_company_default_provisioning provisioning
       JOIN active_company company ON company.company_id=provisioning.company_id
       WHERE provisioning.configuration_digest<>SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
           provisioning.company_id,'|',provisioning.provisioning_year,'|',
           provisioning.location_id,'|',provisioning.shift_template_id,'|',
           provisioning.calendar_id,'|',provisioning.attendance_group_id),256))
          AS provisioning_mismatch_count
),
annual_policy_source_mapping AS (
    SELECT target.annual_leave_policy_version_id
    FROM annual_leave_policy_version target
    JOIN active_company company ON company.company_id=target.company_id
    JOIN annual_leave_policy_version source
      ON source.company_id=(SELECT company_id FROM company
                            WHERE code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')
     AND source.scope_type='COMPANY' AND source.status='PUBLISHED'
     AND target.version_number=source.version_number
     AND target.based_on_version_id<=>source.based_on_version_id
     AND target.effective_from=source.effective_from
     AND target.effective_to<=>source.effective_to
     AND target.qualification_required=source.qualification_required
     AND target.qualification_months=source.qualification_months
     AND target.leap_day_rule=source.leap_day_rule
     AND target.tiers_json=source.tiers_json
     AND target.snapshot_digest=source.snapshot_digest
    WHERE target.scope_type='COMPANY' AND target.status='PUBLISHED'
),
company_metrics AS (
    SELECT expected.code,
           expected.name AS expected_name,
           expected.organization_count AS expected_organizations,
           expected.employee_count AS expected_employees,
           company.company_id,
           company.name AS actual_name,
           (SELECT COUNT(*)
            FROM organization_identity organization
            WHERE organization.company_id = company.company_id)
               AS organization_count,
           (SELECT COUNT(*)
            FROM employee
            WHERE employee.company_id = company.company_id)
               AS employee_count,
           (SELECT COUNT(*)
            FROM organization_identity organization
            JOIN organization_current_projection projection
              ON projection.organization_id = organization.organization_id
            JOIN organization_version version
              ON version.organization_version_id = projection.current_version_id
             AND version.organization_id = organization.organization_id
            WHERE organization.company_id = company.company_id
              AND organization.identity_status = 'ACTIVE'
              AND version.status = 'ACTIVE'
              AND version.parent_organization_id IS NULL
              AND version.org_type = 'COMPANY'
              AND version.name = expected.name)
               AS company_root_count,
           (SELECT COUNT(*)
            FROM location
            WHERE location.company_id = company.company_id
              AND location.location_code = 'DEFAULT_LOCATION'
              AND EXISTS (
                  SELECT 1
                  FROM location_timeline timeline
                  WHERE timeline.location_id = location.location_id
                    AND timeline.state = 'ACTIVE'
                    AND timeline.event_sequence = (
                        SELECT MAX(latest.event_sequence)
                        FROM location_timeline latest
                        WHERE latest.location_id = location.location_id
                    )
              ))
               AS default_location_count,
           (SELECT COUNT(*)
            FROM location_revision revision
            JOIN location
              ON location.location_id = revision.location_id
            WHERE location.company_id = company.company_id
              AND location.location_code = 'DEFAULT_LOCATION')
               AS default_location_revision_count,
           (SELECT COUNT(*)
            FROM location_timeline timeline
            JOIN location
              ON location.location_id = timeline.location_id
            WHERE location.company_id = company.company_id
              AND location.location_code = 'DEFAULT_LOCATION'
              AND timeline.state = 'ACTIVE'
              AND timeline.event_sequence = (
                  SELECT MAX(latest.event_sequence)
                  FROM location_timeline latest
                  WHERE latest.location_id = location.location_id
              )) AS default_location_current_timeline_count,
           (SELECT COUNT(*)
            FROM shift_template
            WHERE shift_template.company_id = company.company_id
              AND shift_template.template_code = 'STANDARD_SEASONAL')
               AS seasonal_shift_count,
           (SELECT COUNT(*)
            FROM shift_version version
            JOIN shift_template template
              ON template.shift_template_id = version.shift_template_id
            JOIN shift_publication_timeline timeline
              ON timeline.shift_template_id = template.shift_template_id
             AND timeline.shift_version_id = version.shift_version_id
             AND timeline.state = 'PUBLISHED'
            WHERE template.company_id = company.company_id
              AND template.template_code = 'STANDARD_SEASONAL')
               AS seasonal_shift_version_count,
           (SELECT COUNT(*)
            FROM shift_seasonal_schedule schedule
            JOIN shift_template template
              ON template.shift_template_id = schedule.shift_template_id
            JOIN shift_version winter_h1
              ON winter_h1.shift_version_id = schedule.winter_h1_version_id
             AND winter_h1.shift_template_id = template.shift_template_id
            JOIN shift_version summer
              ON summer.shift_version_id = schedule.summer_version_id
             AND summer.shift_template_id = template.shift_template_id
            JOIN shift_version winter_h2
              ON winter_h2.shift_version_id = schedule.winter_h2_version_id
             AND winter_h2.shift_template_id = template.shift_template_id
            WHERE template.company_id = company.company_id
              AND template.template_code = 'STANDARD_SEASONAL'
              AND schedule.schedule_year = 2026)
               AS seasonal_schedule_count,
           (SELECT COUNT(*)
            FROM shift_seasonal_schedule_revision schedule_revision
            JOIN shift_seasonal_schedule schedule
              ON schedule.shift_seasonal_schedule_id =
                 schedule_revision.shift_seasonal_schedule_id
            JOIN shift_template template
              ON template.shift_template_id = schedule.shift_template_id
            JOIN shift_version revision_winter_h1
              ON revision_winter_h1.shift_version_id =
                 schedule_revision.winter_h1_version_id
             AND revision_winter_h1.shift_template_id =
                 template.shift_template_id
            JOIN shift_version revision_summer
              ON revision_summer.shift_version_id =
                 schedule_revision.summer_version_id
             AND revision_summer.shift_template_id = template.shift_template_id
            JOIN shift_version revision_winter_h2
              ON revision_winter_h2.shift_version_id =
                 schedule_revision.winter_h2_version_id
             AND revision_winter_h2.shift_template_id =
                 template.shift_template_id
            WHERE template.company_id = company.company_id
              AND template.template_code = 'STANDARD_SEASONAL'
              AND schedule.schedule_year = 2026)
               AS seasonal_schedule_revision_count,
           (SELECT COUNT(*)
            FROM work_calendar
            WHERE work_calendar.company_id = company.company_id
              AND work_calendar.calendar_code = 'STANDARD_2026'
              AND EXISTS (
                  SELECT 1
                  FROM calendar_publication_timeline timeline
                  WHERE timeline.work_calendar_id =
                        work_calendar.work_calendar_id
                    AND timeline.state = 'PUBLISHED'
              ))
               AS calendar_count,
           (SELECT COUNT(*)
            FROM work_calendar_version version
            JOIN work_calendar calendar
              ON calendar.work_calendar_id = version.work_calendar_id
            WHERE calendar.company_id = company.company_id
              AND calendar.calendar_code = 'STANDARD_2026'
              AND version.calendar_year = 2026)
               AS calendar_version_count,
           (SELECT COUNT(*)
            FROM work_calendar_day day_record
            JOIN work_calendar_version version
              ON version.work_calendar_version_id =
                 day_record.work_calendar_version_id
            JOIN work_calendar calendar
              ON calendar.work_calendar_id = version.work_calendar_id
            WHERE calendar.company_id = company.company_id
              AND calendar.calendar_code = 'STANDARD_2026'
              AND version.calendar_year = 2026)
               AS calendar_day_count,
           (SELECT COUNT(*)
            FROM attendance_group
            WHERE attendance_group.company_id = company.company_id
              AND attendance_group.group_code = 'DEFAULT_ATTENDANCE'
              AND EXISTS (
                  SELECT 1
                  FROM attendance_group_timeline timeline
                  WHERE timeline.attendance_group_id =
                        attendance_group.attendance_group_id
                    AND timeline.state = 'ACTIVE'
                    AND timeline.event_sequence = (
                        SELECT MAX(latest.event_sequence)
                        FROM attendance_group_timeline latest
                        WHERE latest.attendance_group_id =
                              attendance_group.attendance_group_id
                    )
              ))
               AS default_group_count,
           (SELECT COUNT(*)
            FROM attendance_group_revision revision
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id =
                 revision.attendance_group_id
            WHERE attendance_group.company_id = company.company_id
              AND attendance_group.group_code = 'DEFAULT_ATTENDANCE')
               AS default_group_revision_count,
           (SELECT COUNT(*)
            FROM attendance_group_timeline timeline
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id =
                 timeline.attendance_group_id
            WHERE attendance_group.company_id = company.company_id
              AND attendance_group.group_code = 'DEFAULT_ATTENDANCE'
              AND timeline.state = 'ACTIVE'
              AND timeline.event_sequence = (
                  SELECT MAX(latest.event_sequence)
                  FROM attendance_group_timeline latest
                  WHERE latest.attendance_group_id =
                        attendance_group.attendance_group_id
              )) AS default_group_current_timeline_count,
           (SELECT COUNT(*)
            FROM employee_resolution resolution
            WHERE resolution.company_id = company.company_id
              AND resolution.resolved_count = 1
              AND resolution.matching_company_count = 1)
               AS resolved_employee_count,
           (SELECT COUNT(DISTINCT scope.policy_template_id)
            FROM attendance_policy_scope scope
            JOIN attendance_policy_template template
              ON template.policy_template_id = scope.policy_template_id
            WHERE scope.company_id = company.company_id
              AND template.template_code IN (
                  'MEAL_DEDUCTION', 'LATE_GRACE',
                  'MONTHLY_LATE_EXEMPTION', 'EARLY_DEPARTURE',
                  'MISSING_PUNCH', 'PUNCH_ARBITRATION',
                  'OVERTIME_RECOGNITION', 'ATTENDANCE_RATE'
              )
              AND EXISTS (
                  SELECT 1
                  FROM attendance_policy_lifecycle_event lifecycle
                  WHERE lifecycle.scope_id = scope.scope_id
                    AND lifecycle.action = 'PUBLISHED'
                    AND lifecycle.event_sequence = (
                        SELECT MAX(latest.event_sequence)
                        FROM attendance_policy_lifecycle_event latest
                        WHERE latest.scope_id = scope.scope_id
                    )
              )) AS attendance_rule_count,
           (SELECT COUNT(DISTINCT family.binding_family_id)
            FROM attendance_policy_binding_family family
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id =
                 family.attendance_group_id
            WHERE attendance_group.company_id = company.company_id
              AND attendance_group.group_code = 'DEFAULT_ATTENDANCE'
              AND family.policy_kind IN (
                  'MEAL_DEDUCTION', 'LATE_GRACE',
                  'MONTHLY_LATE_EXEMPTION'
              )
              AND EXISTS (
                  SELECT 1
                  FROM attendance_policy_binding_revision revision
                  WHERE revision.binding_family_id = family.binding_family_id
              )) AS explicit_policy_binding_count,
           (SELECT COUNT(*)
            FROM annual_leave_policy_version policy
            WHERE policy.scope_type = 'COMPANY'
              AND policy.company_id = company.company_id
              AND policy.status = 'PUBLISHED'
              AND policy.effective_from <= CURRENT_DATE()
              AND (policy.effective_to IS NULL
                   OR policy.effective_to > CURRENT_DATE())
              AND EXISTS (
                  SELECT 1
                  FROM annual_leave_policy_lifecycle_event lifecycle
                  WHERE lifecycle.annual_leave_policy_version_id =
                        policy.annual_leave_policy_version_id
                    AND lifecycle.action = 'PUBLISHED'
                    AND lifecycle.event_sequence = (
                        SELECT MAX(latest.event_sequence)
                        FROM annual_leave_policy_lifecycle_event latest
                        WHERE latest.annual_leave_policy_version_id =
                              policy.annual_leave_policy_version_id
                    )
              ))
               AS annual_leave_policy_count,
           (SELECT COUNT(*)
            FROM attendance_company_default_provisioning provisioning
            WHERE provisioning.company_id = company.company_id
              AND provisioning.provisioning_year = 2026
              AND provisioning.status = 'READY')
               AS default_provisioning_count,
           (SELECT COUNT(*)
            FROM auth_principal_role_assignment assignment
            JOIN auth_role role ON role.role_id = assignment.role_id
            JOIN auth_data_scope scope
              ON scope.scope_id = assignment.data_scope_id
            WHERE assignment.principal_id IN (
                      SELECT principal_id FROM active_admin)
              AND role.role_code = 'HR_ADMIN'
              AND scope.scope_type = 'COMPANY'
              AND scope.company_id = company.company_id
              AND assignment.valid_from <= CURRENT_TIMESTAMP(6)
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to > CURRENT_TIMESTAMP(6))
              AND scope.valid_from <= CURRENT_TIMESTAMP(6)
              AND (scope.valid_to IS NULL
                   OR scope.valid_to > CURRENT_TIMESTAMP(6)))
               AS active_admin_hr_count,
           (SELECT COUNT(*)
            FROM auth_principal_role_assignment assignment
            JOIN auth_role role ON role.role_id = assignment.role_id
            JOIN auth_data_scope scope
              ON scope.scope_id = assignment.data_scope_id
            WHERE assignment.principal_id IN (
                      SELECT principal_id FROM active_admin)
              AND role.role_code = 'SYSTEM_ADMIN'
              AND scope.scope_type = 'COMPANY'
              AND scope.company_id = company.company_id
              AND assignment.valid_from <= CURRENT_TIMESTAMP(6)
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to > CURRENT_TIMESTAMP(6))
              AND scope.valid_from <= CURRENT_TIMESTAMP(6)
              AND (scope.valid_to IS NULL
                   OR scope.valid_to > CURRENT_TIMESTAMP(6)))
               AS active_admin_system_count
    FROM expected_company expected
    LEFT JOIN active_company company ON company.code = expected.code
),
shared_location_chain_metrics AS (
    SELECT shared.shared_location_id,
           shared.row_version,
           COUNT(revision.shared_location_revision_id) AS revision_count,
           COALESCE(MIN(revision.revision_number),0) AS minimum_revision,
           COALESCE(MAX(revision.revision_number),0) AS maximum_revision,
           COUNT(DISTINCT revision.revision_number) AS distinct_revision_count,
           SUM(CASE
               WHEN revision.shared_location_revision_id IS NOT NULL
                AND revision.revision_number = shared.row_version + 1
                   THEN 1 ELSE 0
           END) AS head_count,
           SUM(CASE
               WHEN revision.shared_location_revision_id IS NOT NULL
                AND revision.supersedes_shared_location_revision_id IS NULL
                   THEN 1 ELSE 0
           END) AS root_count,
           SUM(CASE
               WHEN revision.shared_location_revision_id IS NULL THEN 1
               WHEN revision.revision_number = 1
                AND revision.supersedes_shared_location_revision_id IS NOT NULL
                   THEN 1
               WHEN revision.revision_number > 1
                AND (
                    predecessor.shared_location_revision_id IS NULL
                    OR predecessor.shared_location_id <>
                       revision.shared_location_id
                    OR predecessor.revision_number + 1 <>
                       revision.revision_number
                    OR NOT (predecessor.effective_to <=>
                            revision.effective_from)
                ) THEN 1
               ELSE 0
           END) AS chain_error_count
    FROM shared_location shared
    LEFT JOIN shared_location_revision revision
      ON revision.shared_location_id = shared.shared_location_id
    LEFT JOIN shared_location_revision predecessor
      ON predecessor.shared_location_revision_id =
         revision.supersedes_shared_location_revision_id
    GROUP BY shared.shared_location_id, shared.row_version
),
checks AS (
    SELECT 'directory.active_company_count' AS check_name,
           CAST((SELECT COUNT(*) FROM active_company) AS CHAR) AS actual_value,
           '4' AS expected_value,
           (SELECT COUNT(*) FROM active_company) = 4 AS passed
    UNION ALL
    SELECT 'archive.published_import_batch_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_batch batch
                 JOIN company archive_company
                   ON archive_company.company_id=batch.company_id
                 WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
                   AND batch.status='PUBLISHED') AS CHAR),
           '3',
           (SELECT COUNT(*)
            FROM people_import_batch batch
            JOIN company archive_company
              ON archive_company.company_id=batch.company_id
            WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
              AND batch.status='PUBLISHED')=3
    UNION ALL
    SELECT 'archive.import_publication_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_publication publication
                 JOIN people_import_batch batch ON batch.batch_id=publication.batch_id
                 JOIN company archive_company
                   ON archive_company.company_id=batch.company_id
                  AND archive_company.company_id=publication.company_id
                 WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')
                AS CHAR),
           '3',
           (SELECT COUNT(*)
            FROM people_import_publication publication
            JOIN people_import_batch batch ON batch.batch_id=publication.batch_id
            JOIN company archive_company
              ON archive_company.company_id=batch.company_id
             AND archive_company.company_id=publication.company_id
            WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')=3
    UNION ALL
    SELECT 'archive.import_file_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_file import_file
                 JOIN people_import_batch batch ON batch.batch_id=import_file.batch_id
                 JOIN company archive_company
                   ON archive_company.company_id=batch.company_id
                 WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')
                AS CHAR),
           '3',
           (SELECT COUNT(*)
            FROM people_import_file import_file
            JOIN people_import_batch batch ON batch.batch_id=import_file.batch_id
            JOIN company archive_company
              ON archive_company.company_id=batch.company_id
            WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')=3
    UNION ALL
    SELECT 'archive.import_diff_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_diff import_diff
                 JOIN people_import_batch batch ON batch.batch_id=import_diff.batch_id
                 JOIN company archive_company
                   ON archive_company.company_id=batch.company_id
                 WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')
                AS CHAR),
           '1386',
           (SELECT COUNT(*)
            FROM people_import_diff import_diff
            JOIN people_import_batch batch ON batch.batch_id=import_diff.batch_id
            JOIN company archive_company
              ON archive_company.company_id=batch.company_id
            WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')=1386
    UNION ALL
    SELECT 'archive.import_issue_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_issue import_issue
                 JOIN people_import_batch batch ON batch.batch_id=import_issue.batch_id
                 JOIN company archive_company
                   ON archive_company.company_id=batch.company_id
                 WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')
                AS CHAR),
           '33',
           (SELECT COUNT(*)
            FROM people_import_issue import_issue
            JOIN people_import_batch batch ON batch.batch_id=import_issue.batch_id
            JOIN company archive_company
              ON archive_company.company_id=batch.company_id
            WHERE archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE')=33
    UNION ALL
    SELECT 'archive.target_company_import_batch_count',
           CAST((SELECT COUNT(*)
                 FROM people_import_batch batch
                 JOIN active_company company ON company.company_id=batch.company_id)
                AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM people_import_batch batch
            JOIN active_company company ON company.company_id=batch.company_id)=0
    UNION ALL
    SELECT 'security.archive_active_admin_grant_count',
           CAST((SELECT COUNT(*)
                 FROM auth_principal_role_assignment assignment
                 JOIN auth_data_scope scope
                   ON scope.scope_id=assignment.data_scope_id
                 JOIN company archive_company
                   ON archive_company.company_id=scope.company_id
                 WHERE assignment.principal_id IN (
                           SELECT principal_id FROM active_admin)
                   AND archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
                   AND assignment.valid_from<=CURRENT_TIMESTAMP(6)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to>CURRENT_TIMESTAMP(6))
                   AND scope.valid_from<=CURRENT_TIMESTAMP(6)
                   AND (scope.valid_to IS NULL
                        OR scope.valid_to>CURRENT_TIMESTAMP(6))) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM auth_principal_role_assignment assignment
            JOIN auth_data_scope scope ON scope.scope_id=assignment.data_scope_id
            JOIN company archive_company
              ON archive_company.company_id=scope.company_id
            WHERE assignment.principal_id IN (SELECT principal_id FROM active_admin)
              AND archive_company.code='LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
              AND assignment.valid_from<=CURRENT_TIMESTAMP(6)
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to>CURRENT_TIMESTAMP(6))
              AND scope.valid_from<=CURRENT_TIMESTAMP(6)
              AND (scope.valid_to IS NULL
                   OR scope.valid_to>CURRENT_TIMESTAMP(6)))=0
    UNION ALL
    SELECT 'security.four_company_hr_system_grant_count',
           CAST((SELECT COUNT(*)
                 FROM auth_principal_role_assignment assignment
                 JOIN auth_role role ON role.role_id=assignment.role_id
                 JOIN auth_data_scope scope
                   ON scope.scope_id=assignment.data_scope_id
                 JOIN active_company company ON company.company_id=scope.company_id
                 WHERE assignment.principal_id IN (
                           SELECT principal_id FROM active_admin)
                   AND role.role_code IN ('HR_ADMIN','SYSTEM_ADMIN')
                   AND assignment.valid_from<=CURRENT_TIMESTAMP(6)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to>CURRENT_TIMESTAMP(6))
                   AND scope.valid_from<=CURRENT_TIMESTAMP(6)
                   AND (scope.valid_to IS NULL
                        OR scope.valid_to>CURRENT_TIMESTAMP(6))) AS CHAR),
           '8',
           (SELECT COUNT(*)
            FROM auth_principal_role_assignment assignment
            JOIN auth_role role ON role.role_id=assignment.role_id
            JOIN auth_data_scope scope ON scope.scope_id=assignment.data_scope_id
            JOIN active_company company ON company.company_id=scope.company_id
            WHERE assignment.principal_id IN (SELECT principal_id FROM active_admin)
              AND role.role_code IN ('HR_ADMIN','SYSTEM_ADMIN')
              AND assignment.valid_from<=CURRENT_TIMESTAMP(6)
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to>CURRENT_TIMESTAMP(6))
              AND scope.valid_from<=CURRENT_TIMESTAMP(6)
              AND (scope.valid_to IS NULL
                   OR scope.valid_to>CURRENT_TIMESTAMP(6)))=8
    UNION ALL
    SELECT 'integrity.location_digest_mismatch',
           CAST(location_mismatch_count AS CHAR),'0',location_mismatch_count=0
    FROM resource_digest_metrics
    UNION ALL
    SELECT 'integrity.attendance_group_digest_mismatch',
           CAST(group_mismatch_count AS CHAR),'0',group_mismatch_count=0
    FROM resource_digest_metrics
    UNION ALL
    SELECT 'integrity.shift_version_digest_mismatch',
           CAST(mismatch_count AS CHAR),'0',
           version_count=17 AND mismatch_count=0
    FROM shift_digest_metrics
    UNION ALL
    SELECT 'integrity.calendar_version_digest_mismatch',
           CAST(mismatch_count AS CHAR),'0',
           version_count=8 AND mismatch_count=0
    FROM calendar_digest_metrics
    UNION ALL
    SELECT 'integrity.calendar_day_digest_mismatch',
           CAST(calendar_day_mismatch_count AS CHAR),'0',
           calendar_day_mismatch_count=0
    FROM resource_digest_metrics
    UNION ALL
    SELECT 'integrity.policy_binding_digest_mismatch',
           CAST(binding_mismatch_count AS CHAR),'0',binding_mismatch_count=0
    FROM resource_digest_metrics
    UNION ALL
    SELECT 'integrity.default_provisioning_digest_mismatch',
           CAST(provisioning_mismatch_count AS CHAR),'0',
           provisioning_mismatch_count=0
    FROM resource_digest_metrics
    UNION ALL
    SELECT 'integrity.annual_policy_source_mapping_count',
           CAST((SELECT COUNT(*) FROM annual_policy_source_mapping) AS CHAR),
           '4',(SELECT COUNT(*) FROM annual_policy_source_mapping)=4
    UNION ALL
    SELECT 'integrity.attendance_policy_snapshot_count',
           CAST(snapshot_count AS CHAR),
           '32',
           snapshot_count = 32
    FROM policy_snapshot_metrics
    UNION ALL
    SELECT 'integrity.attendance_policy_snapshot_field_mismatch',
           CAST(field_mismatch_count AS CHAR),
           '0',
           field_mismatch_count = 0
    FROM policy_snapshot_metrics
    UNION ALL
    SELECT 'integrity.attendance_policy_snapshot_digest_mismatch',
           CAST(digest_mismatch_count AS CHAR),
           '0',
           digest_mismatch_count = 0
    FROM policy_snapshot_metrics
    UNION ALL
    SELECT 'configuration.compatibility_location_projection_count',
           CAST((SELECT COUNT(*) FROM location location
                 JOIN active_company company
                   ON company.company_id=location.company_id) AS CHAR),
           '28',
           (SELECT COUNT(*) FROM location location
            JOIN active_company company
              ON company.company_id=location.company_id)=28
    UNION ALL
    SELECT 'configuration.shared_physical_location_count',
           CAST((SELECT COUNT(*) FROM shared_location) AS CHAR),
           '7',
           (SELECT COUNT(*) FROM shared_location)=7
    UNION ALL
    SELECT 'configuration.shared_location_head_count',
           CAST((SELECT COUNT(*)
                 FROM shared_location_chain_metrics
                 WHERE head_count=1) AS CHAR),
           '7',
           (SELECT COUNT(*)
            FROM shared_location_chain_metrics
            WHERE head_count=1)=7
    UNION ALL
    SELECT 'integrity.shared_location_revision_chain_mismatch',
           CAST((SELECT COUNT(*)
                 FROM shared_location_chain_metrics
                 WHERE revision_count=0
                    OR minimum_revision<>1
                    OR revision_count<>maximum_revision
                    OR distinct_revision_count<>revision_count
                    OR root_count<>1
                    OR head_count<>1
                    OR maximum_revision<>row_version+1
                    OR chain_error_count<>0) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM shared_location_chain_metrics
            WHERE revision_count=0
               OR minimum_revision<>1
               OR revision_count<>maximum_revision
               OR distinct_revision_count<>revision_count
               OR root_count<>1
               OR head_count<>1
               OR maximum_revision<>row_version+1
               OR chain_error_count<>0)=0
    UNION ALL
    SELECT 'configuration.active_company_location_availability_count',
           CAST((SELECT COUNT(*)
                 FROM company_location_availability availability
                 JOIN active_company company
                   ON company.company_id=availability.company_id
                 WHERE availability.status='ACTIVE') AS CHAR),
           '28',
           (SELECT COUNT(*)
            FROM company_location_availability availability
            JOIN active_company company
              ON company.company_id=availability.company_id
            WHERE availability.status='ACTIVE')=28
    UNION ALL
    SELECT 'configuration.company_location_availability_cardinality',
           CAST((SELECT COUNT(*)
                 FROM (
                     SELECT availability.company_id
                     FROM company_location_availability availability
                     JOIN active_company company
                       ON company.company_id=availability.company_id
                     WHERE availability.status='ACTIVE'
                       AND availability.effective_to IS NULL
                     GROUP BY availability.company_id
                     HAVING COUNT(*)=7
                        AND COUNT(DISTINCT availability.shared_location_id)=7
                        AND COUNT(DISTINCT availability.location_id)=7
                 ) exact_company) AS CHAR),
           '4',
           (SELECT COUNT(*)
            FROM (
                SELECT availability.company_id
                FROM company_location_availability availability
                JOIN active_company company
                  ON company.company_id=availability.company_id
                WHERE availability.status='ACTIVE'
                  AND availability.effective_to IS NULL
                GROUP BY availability.company_id
                HAVING COUNT(*)=7
                   AND COUNT(DISTINCT availability.shared_location_id)=7
                   AND COUNT(DISTINCT availability.location_id)=7
            ) exact_company)=4
    UNION ALL
    SELECT 'configuration.shared_location_code_set',
           CAST((SELECT COUNT(*)
                 FROM shared_location
                 WHERE location_code IN (
                     'DEFAULT_LOCATION','CHENGDU','DALIAN','SHANGHAI',
                     'HEFEI','WUHAN','SHENZHEN')) AS CHAR),
           '7',
           (SELECT COUNT(*)
            FROM shared_location
            WHERE location_code IN (
                'DEFAULT_LOCATION','CHENGDU','DALIAN','SHANGHAI',
                'HEFEI','WUHAN','SHENZHEN'))=7
    UNION ALL
    SELECT 'integrity.shared_location_projection_mismatch',
           CAST((SELECT COUNT(*)
                 FROM company_location_availability availability
                 JOIN active_company company
                   ON company.company_id=availability.company_id
                 JOIN location projection
                   ON projection.location_id=availability.location_id
                 JOIN location_revision projection_revision
                   ON projection_revision.location_id=projection.location_id
                  AND projection_revision.revision_number=(
                      SELECT MAX(latest.revision_number)
                      FROM location_revision latest
                      WHERE latest.location_id=projection.location_id)
                 JOIN location_timeline projection_timeline
                   ON projection_timeline.location_revision_id=
                      projection_revision.location_revision_id
                  AND projection_timeline.event_sequence=(
                      SELECT MIN(origin.event_sequence)
                      FROM location_timeline origin
                      WHERE origin.location_revision_id=
                            projection_revision.location_revision_id)
                 JOIN shared_location shared
                   ON shared.shared_location_id=availability.shared_location_id
                 JOIN shared_location_revision shared_revision
                   ON shared_revision.shared_location_id=shared.shared_location_id
                  AND shared_revision.revision_number=(
                      SELECT MAX(latest.revision_number)
                      FROM shared_location_revision latest
                      WHERE latest.shared_location_id=shared.shared_location_id)
                 WHERE availability.status<>'ACTIVE'
                    OR projection.company_id<>availability.company_id
                    OR projection.location_code<>availability.location_code
                    OR availability.location_code<>shared.location_code
                    OR projection_revision.location_name<>
                       shared_revision.location_name
                    OR projection_revision.time_zone<>shared_revision.time_zone
                    OR projection_timeline.state<>shared_revision.status
                    OR projection_revision.effective_from<>
                       shared_revision.effective_from
                    OR NOT (shared_revision.effective_to <=> (
                        SELECT MIN(boundary.business_effective_from)
                        FROM location_timeline boundary
                        WHERE boundary.location_id=projection.location_id
                          AND boundary.event_sequence>
                              projection_timeline.event_sequence
                          AND boundary.business_effective_from>
                              projection_timeline.business_effective_from
                          AND (boundary.location_revision_id<>
                               projection_timeline.location_revision_id
                               OR boundary.state='INACTIVE')
                    ))) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM company_location_availability availability
            JOIN active_company company
              ON company.company_id=availability.company_id
            JOIN location projection
              ON projection.location_id=availability.location_id
            JOIN location_revision projection_revision
              ON projection_revision.location_id=projection.location_id
             AND projection_revision.revision_number=(
                 SELECT MAX(latest.revision_number)
                 FROM location_revision latest
                 WHERE latest.location_id=projection.location_id)
            JOIN location_timeline projection_timeline
              ON projection_timeline.location_revision_id=
                 projection_revision.location_revision_id
             AND projection_timeline.event_sequence=(
                 SELECT MIN(origin.event_sequence)
                 FROM location_timeline origin
                 WHERE origin.location_revision_id=
                       projection_revision.location_revision_id)
            JOIN shared_location shared
              ON shared.shared_location_id=availability.shared_location_id
            JOIN shared_location_revision shared_revision
              ON shared_revision.shared_location_id=shared.shared_location_id
             AND shared_revision.revision_number=(
                 SELECT MAX(latest.revision_number)
                 FROM shared_location_revision latest
                 WHERE latest.shared_location_id=shared.shared_location_id)
            WHERE availability.status<>'ACTIVE'
               OR projection.company_id<>availability.company_id
               OR projection.location_code<>availability.location_code
               OR availability.location_code<>shared.location_code
               OR projection_revision.location_name<>
                  shared_revision.location_name
               OR projection_revision.time_zone<>shared_revision.time_zone
               OR projection_timeline.state<>shared_revision.status
               OR projection_revision.effective_from<>
                  shared_revision.effective_from
               OR NOT (shared_revision.effective_to <=> (
                   SELECT MIN(boundary.business_effective_from)
                   FROM location_timeline boundary
                   WHERE boundary.location_id=projection.location_id
                     AND boundary.event_sequence>
                         projection_timeline.event_sequence
                     AND boundary.business_effective_from>
                         projection_timeline.business_effective_from
                     AND (boundary.location_revision_id<>
                          projection_timeline.location_revision_id
                          OR boundary.state='INACTIVE')
               )))=0
    UNION ALL
    SELECT 'integrity.archived_company_shared_availability_count',
           CAST((SELECT COUNT(*)
                 FROM company_location_availability availability
                 JOIN company ON company.company_id=availability.company_id
                 WHERE company.status<>'ACTIVE') AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM company_location_availability availability
            JOIN company ON company.company_id=availability.company_id
            WHERE company.status<>'ACTIVE')=0
    UNION ALL
    SELECT 'integrity.shared_location_revision_digest_mismatch',
           CAST((SELECT COUNT(*)
                 FROM shared_location_revision revision
                 JOIN shared_location shared
                   ON shared.shared_location_id=revision.shared_location_id
                 WHERE revision.snapshot_digest<>SHA2(CONCAT(
                     shared.location_code,'|',revision.location_name,'|',
                     revision.time_zone,'|',revision.status,'|',
                     revision.effective_from,'|',
                     COALESCE(CAST(revision.effective_to AS CHAR),'NULL')
                 ),256)) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM shared_location_revision revision
            JOIN shared_location shared
              ON shared.shared_location_id=revision.shared_location_id
            WHERE revision.snapshot_digest<>SHA2(CONCAT(
                shared.location_code,'|',revision.location_name,'|',
                revision.time_zone,'|',revision.status,'|',
                revision.effective_from,'|',
                COALESCE(CAST(revision.effective_to AS CHAR),'NULL')
            ),256))=0
    UNION ALL
    SELECT 'configuration.default_yangzhou_location_count',
           CAST((SELECT COUNT(*)
                 FROM location location
                 JOIN active_company company
                   ON company.company_id=location.company_id
                 JOIN location_revision revision
                   ON revision.location_id=location.location_id
                 JOIN location_timeline timeline
                   ON timeline.location_id=location.location_id
                  AND timeline.location_revision_id=revision.location_revision_id
                 WHERE location.location_code='DEFAULT_LOCATION'
                   AND revision.location_name='扬州'
                   AND revision.time_zone='Asia/Shanghai'
                   AND timeline.event_sequence=1
                   AND timeline.state='ACTIVE') AS CHAR),
           '4',
           (SELECT COUNT(*)
            FROM location location
            JOIN active_company company ON company.company_id=location.company_id
            JOIN location_revision revision ON revision.location_id=location.location_id
            JOIN location_timeline timeline
              ON timeline.location_id=location.location_id
             AND timeline.location_revision_id=revision.location_revision_id
            WHERE location.location_code='DEFAULT_LOCATION'
              AND revision.location_name='扬州'
              AND revision.time_zone='Asia/Shanghai'
              AND timeline.event_sequence=1 AND timeline.state='ACTIVE')=4
    UNION ALL
    SELECT 'configuration.precreated_city_location_count',
           CAST((SELECT COUNT(*) FROM city_location_audit
                 WHERE matching_count=1) AS CHAR),
           '24',
           (SELECT COUNT(*) FROM city_location_audit
            WHERE matching_count=1)=24
    UNION ALL
    SELECT 'configuration.named_employee_assignment_count',
           CAST((SELECT COUNT(*) FROM named_assignment_audit
                 WHERE matching_count=1) AS CHAR),
           '21',
           (SELECT COUNT(*) FROM named_assignment_audit
            WHERE matching_count=1)=21
    UNION ALL
    SELECT 'configuration.default_group_employee_count',
           CAST((SELECT COUNT(*) FROM current_resolved_assignment
                 WHERE group_code='DEFAULT_ATTENDANCE') AS CHAR),
           '597',
           (SELECT COUNT(*) FROM current_resolved_assignment
            WHERE group_code='DEFAULT_ATTENDANCE')=597
    UNION ALL
    SELECT 'configuration.special_group_employee_count',
           CAST((SELECT COUNT(*) FROM current_resolved_assignment
                 WHERE group_code IN ('DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                                      'SHANGHAI_ATTENDANCE')) AS CHAR),
           '18',
           (SELECT COUNT(*) FROM current_resolved_assignment
            WHERE group_code IN ('DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                                 'SHANGHAI_ATTENDANCE'))=18
    UNION ALL
    SELECT 'configuration.special_group_count',
           CAST((SELECT COUNT(*) FROM attendance_group attendance_group
                 JOIN active_company company
                   ON company.company_id=attendance_group.company_id
                 WHERE company.code='SZSC'
                   AND attendance_group.group_code IN (
                       'DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                       'SHANGHAI_ATTENDANCE')) AS CHAR),
           '3',
           (SELECT COUNT(*) FROM attendance_group attendance_group
            JOIN active_company company ON company.company_id=attendance_group.company_id
            WHERE company.code='SZSC'
              AND attendance_group.group_code IN (
                  'DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                  'SHANGHAI_ATTENDANCE'))=3
    UNION ALL
    SELECT 'configuration.special_policy_binding_count',
           CAST((SELECT COUNT(*)
                 FROM attendance_policy_binding_family family
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id=family.attendance_group_id
                 WHERE attendance_group.group_code IN (
                     'DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                     'SHANGHAI_ATTENDANCE')) AS CHAR),
           '9',
           (SELECT COUNT(*)
            FROM attendance_policy_binding_family family
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id=family.attendance_group_id
            WHERE attendance_group.group_code IN (
                'DALIAN_ATTENDANCE','CHENGDU_ATTENDANCE',
                'SHANGHAI_ATTENDANCE'))=9
    UNION ALL
    SELECT 'configuration.special_shift_version_count',
           CAST((SELECT COUNT(*) FROM shift_version version
                 JOIN shift_template template
                   ON template.shift_template_id=version.shift_template_id
                 WHERE template.template_code IN (
                     'DALIAN_FIXED','CHENGDU_FIXED','SHANGHAI_SEASONAL')) AS CHAR),
           '5',
           (SELECT COUNT(*) FROM shift_version version
            JOIN shift_template template
              ON template.shift_template_id=version.shift_template_id
            WHERE template.template_code IN (
                'DALIAN_FIXED','CHENGDU_FIXED','SHANGHAI_SEASONAL'))=5
    UNION ALL
    SELECT 'configuration.unapproved_city_specific_shift_count',
           CAST((SELECT COUNT(*)
                 FROM shift_template shift_template
                 JOIN location location
                   ON location.location_id=shift_template.location_id
                 JOIN company company
                   ON company.company_id=shift_template.company_id
                 WHERE location.location_code IN (
                     'CHENGDU','DALIAN','SHANGHAI','HEFEI','WUHAN','SHENZHEN')
                   AND NOT (company.code='SZSC'
                       AND location.location_code IN ('CHENGDU','DALIAN','SHANGHAI')
                       AND shift_template.template_code IN (
                           'CHENGDU_FIXED','DALIAN_FIXED','SHANGHAI_SEASONAL'))) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM shift_template shift_template
            JOIN location location ON location.location_id=shift_template.location_id
            JOIN company company ON company.company_id=shift_template.company_id
            WHERE location.location_code IN (
                'CHENGDU','DALIAN','SHANGHAI','HEFEI','WUHAN','SHENZHEN')
              AND NOT (company.code='SZSC'
                  AND location.location_code IN ('CHENGDU','DALIAN','SHANGHAI')
                  AND shift_template.template_code IN (
                      'CHENGDU_FIXED','DALIAN_FIXED','SHANGHAI_SEASONAL')))=0
    UNION ALL
    SELECT 'configuration.fixed_city_shift_exact_version_count',
           CAST((SELECT COUNT(*) FROM fixed_city_shift_audit
                 WHERE matching_count=1) AS CHAR),
           '2',
           (SELECT COUNT(*) FROM fixed_city_shift_audit
            WHERE matching_count=1)=2
    UNION ALL
    SELECT 'configuration.shanghai_matches_yangzhou_version_count',
           CAST((SELECT COUNT(*)
                 FROM shift_version shanghai
                 JOIN shift_template shanghai_template
                   ON shanghai_template.shift_template_id=shanghai.shift_template_id
                  AND shanghai_template.template_code='SHANGHAI_SEASONAL'
                 JOIN shift_template yangzhou_template
                   ON yangzhou_template.company_id=shanghai_template.company_id
                  AND yangzhou_template.template_code='STANDARD_SEASONAL'
                 JOIN shift_version yangzhou
                   ON yangzhou.shift_template_id=yangzhou_template.shift_template_id
                  AND yangzhou.version_number=shanghai.version_number
                 WHERE shanghai.effective_from=yangzhou.effective_from
                   AND shanghai.time_zone_snapshot=yangzhou.time_zone_snapshot
                   AND shanghai.segments_json=yangzhou.segments_json) AS CHAR),
           '3',
           (SELECT COUNT(*)
            FROM shift_version shanghai
            JOIN shift_template shanghai_template
              ON shanghai_template.shift_template_id=shanghai.shift_template_id
             AND shanghai_template.template_code='SHANGHAI_SEASONAL'
            JOIN shift_template yangzhou_template
              ON yangzhou_template.company_id=shanghai_template.company_id
             AND yangzhou_template.template_code='STANDARD_SEASONAL'
            JOIN shift_version yangzhou
              ON yangzhou.shift_template_id=yangzhou_template.shift_template_id
             AND yangzhou.version_number=shanghai.version_number
            WHERE shanghai.effective_from=yangzhou.effective_from
              AND shanghai.time_zone_snapshot=yangzhou.time_zone_snapshot
              AND shanghai.segments_json=yangzhou.segments_json)=3
    UNION ALL
    SELECT 'directory.unexpected_active_company_count',
           CAST((SELECT COUNT(*)
                 FROM active_company active
                 WHERE active.code NOT IN (
                     SELECT code FROM expected_company)) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM active_company active
            WHERE active.code NOT IN (
                SELECT code FROM expected_company)) = 0
    UNION ALL
    SELECT 'directory.archive_company_is_inactive',
           CAST((SELECT COUNT(*) FROM company
                 WHERE code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
                   AND name = '历史导入归档（原四公司混合数据）'
                   AND status = 'INACTIVE') AS CHAR),
           '1',
           (SELECT COUNT(*) FROM company
            WHERE code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
              AND name = '历史导入归档（原四公司混合数据）'
              AND status = 'INACTIVE') = 1
    UNION ALL
    SELECT 'directory.archive_active_count',
           CAST((SELECT COUNT(*) FROM company
                 WHERE code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
                   AND status = 'ACTIVE') AS CHAR),
           '0',
           (SELECT COUNT(*) FROM company
            WHERE code = 'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
              AND status = 'ACTIVE') = 0
    UNION ALL
    SELECT 'directory.w3_active_count',
           CAST((SELECT COUNT(*) FROM company
                 WHERE (code = 'W3_BASELINE_LEGAL_ENTITY'
                        OR name = 'W3 verification baseline legal entity')
                   AND status = 'ACTIVE') AS CHAR),
           '0',
           (SELECT COUNT(*) FROM company
            WHERE (code = 'W3_BASELINE_LEGAL_ENTITY'
                   OR name = 'W3 verification baseline legal entity')
              AND status = 'ACTIVE') = 0
    UNION ALL
    SELECT 'security.active_production_admin_count',
           CAST((SELECT COUNT(*) FROM active_admin) AS CHAR),
           '1',
           (SELECT COUNT(*) FROM active_admin) = 1
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.name'),
           COALESCE(actual_name, '<missing>'), expected_name,
           actual_name = expected_name
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.organization_count'),
           CAST(organization_count AS CHAR),
           CAST(expected_organizations AS CHAR),
           organization_count = expected_organizations
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.employee_count'),
           CAST(employee_count AS CHAR), CAST(expected_employees AS CHAR),
           employee_count = expected_employees
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.company_root_count'),
           CAST(company_root_count AS CHAR), '1', company_root_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.default_location_count'),
           CAST(default_location_count AS CHAR), '1',
           default_location_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.default_location_revision_count'),
           CAST(default_location_revision_count AS CHAR), '>=1',
           default_location_revision_count >= 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'company.', code,
               '.default_location_current_timeline_count'),
           CAST(default_location_current_timeline_count AS CHAR), '1',
           default_location_current_timeline_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.seasonal_shift_count'),
           CAST(seasonal_shift_count AS CHAR), '1', seasonal_shift_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.seasonal_shift_version_count'),
           CAST(seasonal_shift_version_count AS CHAR), '3',
           seasonal_shift_version_count = 3
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.seasonal_schedule_count'),
           CAST(seasonal_schedule_count AS CHAR), '1',
           seasonal_schedule_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.seasonal_schedule_revision_count'),
           CAST(seasonal_schedule_revision_count AS CHAR), '1',
           seasonal_schedule_revision_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.calendar_count'),
           CAST(calendar_count AS CHAR), '1', calendar_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.calendar_version_count'),
           CAST(calendar_version_count AS CHAR), '2',
           calendar_version_count = 2
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.calendar_day_count'),
           CAST(calendar_day_count AS CHAR), '365', calendar_day_count = 365
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.default_group_count'),
           CAST(default_group_count AS CHAR), '1', default_group_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.default_group_revision_count'),
           CAST(default_group_revision_count AS CHAR), '>=1',
           default_group_revision_count >= 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
               'company.', code,
               '.default_group_current_timeline_count'),
           CAST(default_group_current_timeline_count AS CHAR), '1',
           default_group_current_timeline_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.resolved_employee_count'),
           CAST(resolved_employee_count AS CHAR),
           CAST(expected_employees AS CHAR),
           resolved_employee_count = expected_employees
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.attendance_rule_count'),
           CAST(attendance_rule_count AS CHAR), '8', attendance_rule_count = 8
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.explicit_policy_binding_count'),
           CAST(explicit_policy_binding_count AS CHAR), '3',
           explicit_policy_binding_count = 3
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.annual_leave_policy_count'),
           CAST(annual_leave_policy_count AS CHAR), '1',
           annual_leave_policy_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.default_provisioning_count'),
           CAST(default_provisioning_count AS CHAR), '1',
           default_provisioning_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.active_admin_hr_count'),
           CAST(active_admin_hr_count AS CHAR), '1', active_admin_hr_count = 1
    FROM company_metrics
    UNION ALL
    SELECT CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,'company.', code, '.active_admin_system_count'),
           CAST(active_admin_system_count AS CHAR), '1',
           active_admin_system_count = 1
    FROM company_metrics
    UNION ALL
    SELECT 'integrity.employee_current_organization_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM employment_assignment employment
                 JOIN employee
                   ON employee.employee_id = employment.employee_id
                 JOIN organization_identity organization
                   ON organization.organization_id = employment.organization_id
                 WHERE employment.version_valid_to IS NULL
                   AND employment.record_status = 'ACTIVE'
                   AND employee.company_id <> organization.company_id)
                AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM employment_assignment employment
            JOIN employee ON employee.employee_id = employment.employee_id
            JOIN organization_identity organization
              ON organization.organization_id = employment.organization_id
            WHERE employment.version_valid_to IS NULL
              AND employment.record_status = 'ACTIVE'
              AND employee.company_id <> organization.company_id) = 0
    UNION ALL
    SELECT 'integrity.organization_parent_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM organization_version child
                 JOIN organization_identity child_identity
                   ON child_identity.organization_id = child.organization_id
                 JOIN organization_identity parent_identity
                   ON parent_identity.organization_id =
                      child.parent_organization_id
                 WHERE child_identity.company_id <>
                       parent_identity.company_id) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM organization_version child
            JOIN organization_identity child_identity
              ON child_identity.organization_id = child.organization_id
            JOIN organization_identity parent_identity
              ON parent_identity.organization_id = child.parent_organization_id
            WHERE child_identity.company_id <>
                  parent_identity.company_id) = 0
    UNION ALL
    SELECT 'integrity.organization_closure_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM organization_current_closure closure
                 JOIN organization_identity ancestor
                   ON ancestor.organization_id =
                      closure.ancestor_organization_id
                 JOIN organization_identity descendant
                   ON descendant.organization_id =
                      closure.descendant_organization_id
                 WHERE ancestor.company_id <> descendant.company_id)
                AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM organization_current_closure closure
            JOIN organization_identity ancestor
              ON ancestor.organization_id = closure.ancestor_organization_id
            JOIN organization_identity descendant
              ON descendant.organization_id =
                 closure.descendant_organization_id
            WHERE ancestor.company_id <> descendant.company_id) = 0
    UNION ALL
    SELECT 'integrity.current_employee_group_resolution_mismatch',
           CAST((SELECT COUNT(*) FROM employee_resolution
                 WHERE resolved_count <> 1
                    OR matching_company_count <> 1) AS CHAR),
           '0',
           (SELECT COUNT(*) FROM employee_resolution
            WHERE resolved_count <> 1
               OR matching_company_count <> 1) = 0
    UNION ALL
    SELECT 'integrity.archive_assignment_predecessor_count',
           CAST((SELECT COUNT(*)
                 FROM attendance_group_assignment assignment
                 JOIN attendance_group_revision revision
                   ON revision.attendance_group_revision_id =
                      assignment.attendance_group_revision_id
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id =
                      revision.attendance_group_id
                 JOIN company archive_company
                   ON archive_company.company_id = attendance_group.company_id
                 WHERE archive_company.code =
                       'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE') AS CHAR),
           '615',
           (SELECT COUNT(*)
            FROM attendance_group_assignment assignment
            JOIN attendance_group_revision revision
              ON revision.attendance_group_revision_id =
                 assignment.attendance_group_revision_id
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id = revision.attendance_group_id
            JOIN company archive_company
              ON archive_company.company_id = attendance_group.company_id
            WHERE archive_company.code =
                  'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE') = 615
    UNION ALL
    SELECT 'integrity.archive_assignment_latest_inactive_count',
           CAST((SELECT COUNT(*)
                 FROM attendance_group_assignment assignment
                 JOIN attendance_group_revision revision
                   ON revision.attendance_group_revision_id =
                      assignment.attendance_group_revision_id
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id =
                      revision.attendance_group_id
                 JOIN company archive_company
                   ON archive_company.company_id = attendance_group.company_id
                 JOIN latest_assignment_timeline timeline
                   ON timeline.attendance_group_assignment_id =
                      assignment.attendance_group_assignment_id
                  AND timeline.state = 'INACTIVE'
                 WHERE archive_company.code =
                       'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE') AS CHAR),
           '615',
           (SELECT COUNT(*)
            FROM attendance_group_assignment assignment
            JOIN attendance_group_revision revision
              ON revision.attendance_group_revision_id =
                 assignment.attendance_group_revision_id
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id = revision.attendance_group_id
            JOIN company archive_company
              ON archive_company.company_id = attendance_group.company_id
            JOIN latest_assignment_timeline timeline
              ON timeline.attendance_group_assignment_id =
                 assignment.attendance_group_assignment_id
             AND timeline.state = 'INACTIVE'
            WHERE archive_company.code =
                  'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE') = 615
    UNION ALL
    SELECT 'integrity.archive_assignment_single_successor_count',
           CAST((SELECT COUNT(*)
                 FROM attendance_group_assignment predecessor
                 JOIN attendance_group_revision revision
                   ON revision.attendance_group_revision_id =
                      predecessor.attendance_group_revision_id
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id =
                      revision.attendance_group_id
                 JOIN company archive_company
                   ON archive_company.company_id = attendance_group.company_id
                 WHERE archive_company.code =
                       'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
                   AND (SELECT COUNT(*)
                        FROM attendance_group_assignment successor
                        WHERE successor.supersedes_assignment_id =
                              predecessor.attendance_group_assignment_id) = 1)
                AS CHAR),
           '615',
           (SELECT COUNT(*)
            FROM attendance_group_assignment predecessor
            JOIN attendance_group_revision revision
              ON revision.attendance_group_revision_id =
                 predecessor.attendance_group_revision_id
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id = revision.attendance_group_id
            JOIN company archive_company
              ON archive_company.company_id = attendance_group.company_id
            WHERE archive_company.code =
                  'LEGACY_FOUR_COMPANY_IMPORT_ARCHIVE'
              AND (SELECT COUNT(*)
                   FROM attendance_group_assignment successor
                   WHERE successor.supersedes_assignment_id =
                         predecessor.attendance_group_assignment_id) = 1) = 615
    UNION ALL
    SELECT 'integrity.group_configuration_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM attendance_group_revision group_revision
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id =
                      group_revision.attendance_group_id
                 JOIN location_revision location_revision
                   ON location_revision.location_revision_id =
                      group_revision.location_revision_id
                 JOIN location
                   ON location.location_id = location_revision.location_id
                 JOIN work_calendar calendar
                   ON calendar.work_calendar_id =
                      group_revision.work_calendar_id
                 JOIN shift_template shift_template
                   ON shift_template.shift_template_id =
                      group_revision.shift_template_id
                 WHERE attendance_group.company_id <> location.company_id
                    OR attendance_group.company_id <> calendar.company_id
                    OR attendance_group.company_id <>
                       shift_template.company_id) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM attendance_group_revision group_revision
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id =
                 group_revision.attendance_group_id
            JOIN location_revision location_revision
              ON location_revision.location_revision_id =
                 group_revision.location_revision_id
            JOIN location ON location.location_id = location_revision.location_id
            JOIN work_calendar calendar
              ON calendar.work_calendar_id = group_revision.work_calendar_id
            JOIN shift_template shift_template
              ON shift_template.shift_template_id =
                 group_revision.shift_template_id
            WHERE attendance_group.company_id <> location.company_id
               OR attendance_group.company_id <> calendar.company_id
               OR attendance_group.company_id <>
                  shift_template.company_id) = 0
    UNION ALL
    SELECT 'integrity.location_scoped_resource_company_mismatch',
           CAST(((SELECT COUNT(*)
                  FROM shift_template shift_template
                  JOIN location
                    ON location.location_id = shift_template.location_id
                  WHERE shift_template.company_id <> location.company_id)
                 +
                 (SELECT COUNT(*)
                  FROM work_calendar calendar
                  JOIN location ON location.location_id = calendar.location_id
                  WHERE calendar.company_id <> location.company_id)) AS CHAR),
           '0',
           ((SELECT COUNT(*)
             FROM shift_template shift_template
             JOIN location ON location.location_id = shift_template.location_id
             WHERE shift_template.company_id <> location.company_id)
            +
            (SELECT COUNT(*)
             FROM work_calendar calendar
             JOIN location ON location.location_id = calendar.location_id
             WHERE calendar.company_id <> location.company_id)) = 0
    UNION ALL
    SELECT 'integrity.policy_binding_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM attendance_policy_binding_revision binding_revision
                 JOIN attendance_policy_binding_family family
                   ON family.binding_family_id =
                      binding_revision.binding_family_id
                 JOIN attendance_group family_group
                   ON family_group.attendance_group_id =
                      family.attendance_group_id
                 JOIN attendance_group_revision bound_group_revision
                   ON bound_group_revision.attendance_group_revision_id =
                      binding_revision.attendance_group_revision_id
                 JOIN attendance_group bound_group
                   ON bound_group.attendance_group_id =
                      bound_group_revision.attendance_group_id
                 JOIN attendance_policy_scoped_version scoped_version
                   ON scoped_version.scoped_version_id =
                      binding_revision.attendance_policy_scoped_version_id
                 JOIN attendance_policy_scope policy_scope
                   ON policy_scope.scope_id = scoped_version.scope_id
                 WHERE family_group.company_id <> bound_group.company_id
                    OR family_group.company_id <>
                       policy_scope.company_id) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM attendance_policy_binding_revision binding_revision
            JOIN attendance_policy_binding_family family
              ON family.binding_family_id = binding_revision.binding_family_id
            JOIN attendance_group family_group
              ON family_group.attendance_group_id = family.attendance_group_id
            JOIN attendance_group_revision bound_group_revision
              ON bound_group_revision.attendance_group_revision_id =
                 binding_revision.attendance_group_revision_id
            JOIN attendance_group bound_group
              ON bound_group.attendance_group_id =
                 bound_group_revision.attendance_group_id
            JOIN attendance_policy_scoped_version scoped_version
              ON scoped_version.scoped_version_id =
                 binding_revision.attendance_policy_scoped_version_id
            JOIN attendance_policy_scope policy_scope
              ON policy_scope.scope_id = scoped_version.scope_id
            WHERE family_group.company_id <> bound_group.company_id
               OR family_group.company_id <> policy_scope.company_id) = 0
    UNION ALL
    SELECT 'integrity.default_provisioning_company_mismatch',
           CAST((SELECT COUNT(*)
                 FROM attendance_company_default_provisioning provisioning
                 JOIN location
                   ON location.location_id = provisioning.location_id
                 JOIN shift_template shift_template
                   ON shift_template.shift_template_id =
                      provisioning.shift_template_id
                 JOIN work_calendar calendar
                   ON calendar.work_calendar_id = provisioning.calendar_id
                 JOIN attendance_group attendance_group
                   ON attendance_group.attendance_group_id =
                      provisioning.attendance_group_id
                 WHERE provisioning.company_id <> location.company_id
                    OR provisioning.company_id <>
                       shift_template.company_id
                    OR provisioning.company_id <> calendar.company_id
                    OR provisioning.company_id <>
                       attendance_group.company_id) AS CHAR),
           '0',
           (SELECT COUNT(*)
            FROM attendance_company_default_provisioning provisioning
            JOIN location ON location.location_id = provisioning.location_id
            JOIN shift_template shift_template
              ON shift_template.shift_template_id =
                 provisioning.shift_template_id
            JOIN work_calendar calendar
              ON calendar.work_calendar_id = provisioning.calendar_id
            JOIN attendance_group attendance_group
              ON attendance_group.attendance_group_id =
                 provisioning.attendance_group_id
            WHERE provisioning.company_id <> location.company_id
               OR provisioning.company_id <> shift_template.company_id
               OR provisioning.company_id <> calendar.company_id
               OR provisioning.company_id <>
                  attendance_group.company_id) = 0
)
SELECT /*+ SET_VAR(group_concat_max_len=4000000) */ check_name,
       actual_value,
       expected_value,
       CASE WHEN passed THEN 'PASS' ELSE 'FAIL' END AS status,
       CASE
           WHEN SUM(CASE WHEN passed THEN 0 ELSE 1 END) OVER () = 0
               THEN 'PASS'
           ELSE 'FAIL'
       END AS overall_status
FROM checks
ORDER BY check_name;

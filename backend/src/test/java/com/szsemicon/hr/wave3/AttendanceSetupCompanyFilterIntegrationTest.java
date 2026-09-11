package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AttendanceSetupCompanyFilterIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String COMPANY_A =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPANY_B =
            "30000000-0000-0000-0000-000000000002";
    private static final String UNKNOWN_COMPANY =
            "30000000-0000-0000-0000-000000000099";
    private static final String DIGEST =
            "abababababababababababababababababababababababababababababababab";
    private static final List<String> LIST_ENDPOINTS = List.of(
            "/api/v1/attendance-setup/locations",
            "/api/v1/attendance-setup/shifts",
            "/api/v1/attendance-setup/calendars",
            "/api/v1/attendance-setup/groups",
            "/api/v1/attendance-setup/policy-bindings");

    @Test
    void availability_foreign_keys_reject_company_and_code_drift() {
        String policyTemplateId = id("availability-fk-policy-template");
        seedPolicyTemplate(policyTemplateId);
        SetupIds setup = seedCompanySetup(
                COMPANY_A, "A", policyTemplateId);

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE company_location_availability
                SET location_code = 'WRONG_CODE'
                WHERE location_id = ?
                """,
                setup.locationId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE company_location_availability
                SET company_id = ?
                WHERE location_id = ?
                """,
                COMPANY_B,
                setup.locationId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForMap(
                """
                SELECT company_id, location_code
                FROM company_location_availability
                WHERE location_id = ?
                """,
                setup.locationId()))
                .containsEntry("COMPANY_ID", COMPANY_A)
                .containsEntry("LOCATION_CODE", "FILTER_LOC_SHARED");
    }

    @Test
    void company_filter_intersects_authorization_and_isolates_authorized_companies()
            throws Exception {
        String policyTemplateId = id("company-filter-policy-template");
        seedPolicyTemplate(policyTemplateId);
        seedCompanySetup(COMPANY_A, "A", policyTemplateId);
        seedCompanySetup(COMPANY_B, "B", policyTemplateId);

        for (String endpoint : LIST_ENDPOINTS) {
            expectCompanyPage(endpoint, null, 1, COMPANY_A);
            expectCompanyPage(endpoint, COMPANY_A, 1, COMPANY_A);
            expectCompanyPage(endpoint, COMPANY_B, 0, null);
        }
        expectCompanyPage(
                LIST_ENDPOINTS.getFirst(), UNKNOWN_COMPANY, 0, null);

        grantCompanyScope(COMPANY_B);

        expectCompanyPage(LIST_ENDPOINTS.getFirst(), null, 1, null);
        mockMvc.perform(get(LIST_ENDPOINTS.getFirst())
                        .with(user(ADMIN_PRINCIPAL))
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name")
                        .value("筛选共享地点"));
        for (String endpoint : LIST_ENDPOINTS.subList(1, LIST_ENDPOINTS.size())) {
            expectCompanyPage(endpoint, null, 2, null);
        }
        for (String endpoint : LIST_ENDPOINTS) {
            expectCompanyPage(endpoint, COMPANY_A, 1, COMPANY_A);
            expectCompanyPage(endpoint, COMPANY_B, 1, COMPANY_B);
        }
    }

    @Test
    void shared_location_update_requires_all_company_scopes_and_syncs_history()
            throws Exception {
        String locationA = seedSharedOnlyLocation(COMPANY_A, "SYNC_A", true);
        String locationB = seedSharedOnlyLocation(COMPANY_B, "SYNC_B", false);
        String sharedLocationId = id("sync-shared-location");

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "shared-location-denied")
                        .header("X-Change-Reason", "共享地点全公司同步验证")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLocationUpdateBody(COMPANY_A)))
                .andExpect(status().isNotFound());
        assertSharedLocationRevisionCounts(1, 1, 1);

        grantCompanyScope(COMPANY_B);

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "shared-location-allowed")
                        .header("X-Change-Reason", "共享地点全公司同步验证")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLocationUpdateBody(COMPANY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("共享地点新名称"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Singapore"))
                .andExpect(jsonPath("$.rowVersion").value(1))
                .andExpect(jsonPath("$.sharedLocationId")
                        .value(sharedLocationId))
                .andExpect(jsonPath("$.sharedManagementAllowed").value(true));

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "shared-location-allowed")
                        .header("X-Change-Reason", "共享地点全公司同步验证")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLocationUpdateBody(COMPANY_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.sharedLocationId")
                        .value(sharedLocationId))
                .andExpect(jsonPath("$.rowVersion").value(1));

        for (String companyId : List.of(COMPANY_A, COMPANY_B)) {
            mockMvc.perform(get("/api/v1/attendance-setup/locations")
                            .with(user(ADMIN_PRINCIPAL))
                            .param("companyId", companyId)
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].name")
                            .value("共享地点新名称"))
                    .andExpect(jsonPath("$.items[0].timeZone")
                            .value("Asia/Singapore"))
                    .andExpect(jsonPath("$.items[0].rowVersion").value(1));
        }

        assertSharedLocationRevisionCounts(2, 2, 2);
        assertLegacyLocationBoundary(locationA);
        assertLegacyLocationBoundary(locationB);
        assertThat(jdbc.queryForObject(
                """
                SELECT effective_to
                FROM shared_location_revision
                WHERE revision_number = 1
                """,
                Date.class)).isEqualTo(Date.valueOf("2026-09-01"));
        String persistedDigest = jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM shared_location_revision
                WHERE shared_location_id = ? AND revision_number = 2
                """,
                String.class,
                sharedLocationId);
        assertThat(persistedDigest).isNotEqualTo(DIGEST);
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedLocationId")
                        .value(sharedLocationId))
                .andExpect(jsonPath("$.snapshotDigest")
                        .value(persistedDigest))
                .andExpect(jsonPath("$.rowVersion").value(1));

        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = ?
                WHERE assignment_id = ?
                """,
                Timestamp.from(Instant.parse("2026-07-21T00:00:00Z")),
                id("company-filter-assignment-" + COMPANY_B));
        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "shared-location-allowed")
                        .header("X-Change-Reason", "共享地点全公司同步验证")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLocationUpdateBody(COMPANY_A)))
                .andExpect(status().isNotFound());
        assertSharedLocationRevisionCounts(2, 2, 2);
    }

    @Test
    void referenced_shared_location_rejects_direct_time_zone_change()
            throws Exception {
        String locationA = seedSharedOnlyLocation(COMPANY_A, "TZ_A", true);
        String locationB = seedSharedOnlyLocation(COMPANY_B, "TZ_B", false);
        String sharedLocationId = id("sync-shared-location");
        grantCompanyScope(COMPANY_B);
        jdbc.update(
                """
                INSERT INTO shift_template (
                    shift_template_id, company_id, location_id,
                    template_code, created_by, created_at
                ) VALUES (?, ?, ?, 'TZ_DEPENDENCY', ?, ?)
                """,
                id("timezone-dependency-shift"), COMPANY_A, locationA,
                ADMIN_PRINCIPAL, timestamp());

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        sharedLocationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "shared-location-timezone-in-use")
                        .header("X-Change-Reason", "共享地点全公司同步验证")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sharedLocationUpdateBody(COMPANY_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("SHARED_LOCATION_TIME_ZONE_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        "该地点已有考勤配置或历史引用，不能直接修改时区；"
                                + "请联系系统管理员评估受控迁移"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shared_location_revision",
                Integer.class)).isEqualTo(1);
        for (String locationId : List.of(locationA, locationB)) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM location_revision WHERE location_id = ?",
                    Integer.class,
                    locationId)).isEqualTo(1);
        }
    }

    private String seedSharedOnlyLocation(
            String companyId, String marker, boolean createMaster) {
        String sharedLocationId = id("sync-shared-location");
        String locationId = id("sync-location-" + marker);
        String revisionId = id("sync-location-revision-" + marker);
        Timestamp recordedAt = timestamp();
        if (createMaster) {
            jdbc.update(
                    """
                    INSERT INTO shared_location (
                        shared_location_id, location_code, row_version,
                        created_by, created_at
                    ) VALUES (?, 'SYNC_SHARED', 0, ?, ?)
                    """,
                    sharedLocationId, ADMIN_PRINCIPAL, recordedAt);
            jdbc.update(
                    """
                    INSERT INTO shared_location_revision (
                        shared_location_revision_id, shared_location_id,
                        revision_number, location_name, time_zone, status,
                        effective_from, effective_to,
                        supersedes_shared_location_revision_id,
                        snapshot_digest, change_reason, created_by, created_at
                    ) VALUES (?, ?, 1, '共享地点旧名称', 'Asia/Shanghai',
                        'ACTIVE', ?, NULL, NULL, ?, '共享地点测试初始化', ?, ?)
                    """,
                    id("sync-shared-revision"), sharedLocationId,
                    Date.valueOf("2026-01-01"), DIGEST,
                    ADMIN_PRINCIPAL, recordedAt);
        }
        jdbc.update(
                """
                INSERT INTO location (
                    location_id, company_id, location_code, row_version,
                    created_by, created_at
                ) VALUES (?, ?, 'SYNC_SHARED', 0, ?, ?)
                """,
                locationId, companyId, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO location_revision (
                    location_revision_id, location_id, revision_number,
                    location_name, time_zone, effective_from,
                    supersedes_location_revision_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (?, ?, 1, '共享地点旧名称', 'Asia/Shanghai', ?,
                    NULL, ?, '共享地点测试初始化', ?, ?)
                """,
                revisionId, locationId, Date.valueOf("2026-01-01"), DIGEST,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO location_timeline (
                    location_timeline_id, location_id, location_revision_id,
                    event_sequence, state, business_effective_from,
                    predecessor_timeline_id, recorded_at, actor_id, request_id
                ) VALUES (?, ?, ?, 1, 'ACTIVE', ?, NULL, ?, ?, ?)
                """,
                id("sync-timeline-" + marker), locationId, revisionId,
                Date.valueOf("2026-01-01"), recordedAt, ADMIN_PRINCIPAL,
                "sync-location-" + marker);
        jdbc.update(
                """
                INSERT INTO company_location_availability (
                    company_location_availability_id, shared_location_id,
                    company_id, location_id, location_code,
                    status, effective_from,
                    effective_to, created_by, created_at
                ) VALUES (?, ?, ?, ?, 'SYNC_SHARED', 'ACTIVE', ?, NULL, ?, ?)
                """,
                id("sync-availability-" + marker), sharedLocationId,
                companyId, locationId, Date.valueOf("2026-01-01"),
                ADMIN_PRINCIPAL, recordedAt);
        return locationId;
    }

    private String sharedLocationUpdateBody(String companyId) {
        return """
                {
                  "companyId":"%s",
                  "code":"SYNC_SHARED",
                  "name":"共享地点新名称",
                  "timeZone":"Asia/Singapore",
                  "effectiveFrom":"2026-09-01",
                  "reason":"共享地点全公司同步验证"
                }
                """.formatted(companyId);
    }

    private void assertSharedLocationRevisionCounts(
            int sharedRevisions, int revisionsA, int revisionsB) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shared_location_revision",
                Integer.class)).isEqualTo(sharedRevisions);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM location_revision WHERE location_id = ?",
                Integer.class,
                id("sync-location-SYNC_A"))).isEqualTo(revisionsA);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM location_revision WHERE location_id = ?",
                Integer.class,
                id("sync-location-SYNC_B"))).isEqualTo(revisionsB);
    }

    private void assertLegacyLocationBoundary(String locationId) {
        assertThat(jdbc.queryForList(
                """
                SELECT revision_number, location_name, time_zone
                FROM location_revision
                WHERE location_id = ?
                ORDER BY revision_number
                """,
                locationId)).satisfies(rows -> {
                    assertThat(rows).hasSize(2);
                    assertThat(rows.get(0).get("LOCATION_NAME"))
                            .isEqualTo("共享地点旧名称");
                    assertThat(rows.get(0).get("TIME_ZONE"))
                            .isEqualTo("Asia/Shanghai");
                    assertThat(rows.get(1).get("LOCATION_NAME"))
                            .isEqualTo("共享地点新名称");
                    assertThat(rows.get(1).get("TIME_ZONE"))
                            .isEqualTo("Asia/Singapore");
                });
    }

    private void expectCompanyPage(
            String endpoint,
            String companyId,
            int total,
            String expectedCompanyId) throws Exception {
        var request = get(endpoint)
                .with(user(ADMIN_PRINCIPAL))
                .param("page", "0")
                .param("size", "20");
        if (companyId != null) {
            request.param("companyId", companyId);
        }
        var result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items.length()").value(total));
        if (expectedCompanyId != null) {
            result.andExpect(
                    jsonPath("$.items[0].companyId").value(expectedCompanyId));
        }
    }

    private void grantCompanyScope(String companyId) {
        String scopeId = id("company-filter-scope-" + companyId);
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)
                """,
                scopeId,
                companyId,
                timestamp());
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?,
                    '考勤设置公司筛选集成测试', 0)
                """,
                id("company-filter-assignment-" + companyId),
                ADMIN_PRINCIPAL,
                ADMIN_ROLE,
                scopeId,
                timestamp(),
                ADMIN_PRINCIPAL);
    }

    private void seedPolicyTemplate(String policyTemplateId) {
        jdbc.update(
                """
                INSERT INTO attendance_policy_template (
                    policy_template_id, template_code, name, description,
                    field_definitions_json, created_by, created_at
                ) VALUES (?, 'MEAL_DEDUCTION', '用餐扣除',
                    '考勤设置公司筛选集成测试', '[]', ?, ?)
                """,
                policyTemplateId,
                ADMIN_PRINCIPAL,
                timestamp());
    }

    private SetupIds seedCompanySetup(
            String companyId, String marker, String policyTemplateId) {
        SetupIds ids = new SetupIds(
                id(marker + "-location"),
                id(marker + "-location-revision"),
                id(marker + "-shift"),
                id(marker + "-calendar"),
                id(marker + "-calendar-version"),
                id(marker + "-group"),
                id(marker + "-group-revision"),
                id(marker + "-policy-scope"),
                id(marker + "-policy-version"),
                id(marker + "-binding-family"),
                id(marker + "-binding-revision"));
        Timestamp recordedAt = timestamp();
        String sharedLocationId = id("company-filter-shared-location");
        String sharedLocationRevisionId =
                id("company-filter-shared-location-revision");

        if ("A".equals(marker)) {
            jdbc.update(
                    """
                    INSERT INTO shared_location (
                        shared_location_id, location_code, row_version,
                        created_by, created_at
                    ) VALUES (?, 'FILTER_LOC_SHARED', 0, ?, ?)
                    """,
                    sharedLocationId, ADMIN_PRINCIPAL, recordedAt);
            jdbc.update(
                    """
                    INSERT INTO shared_location_revision (
                        shared_location_revision_id, shared_location_id,
                        revision_number, location_name, time_zone, status,
                        effective_from, effective_to,
                        supersedes_shared_location_revision_id,
                        snapshot_digest, change_reason, created_by, created_at
                    ) VALUES (?, ?, 1, '筛选共享地点', 'Asia/Shanghai',
                        'ACTIVE', ?, NULL, NULL, ?,
                        '考勤设置共享地点筛选集成测试', ?, ?)
                    """,
                    sharedLocationRevisionId, sharedLocationId,
                    Date.valueOf("2026-01-01"), DIGEST,
                    ADMIN_PRINCIPAL, recordedAt);
        }

        jdbc.update(
                """
                INSERT INTO location (
                    location_id, company_id, location_code, row_version,
                    created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                ids.locationId(), companyId, "FILTER_LOC_SHARED",
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO location_revision (
                    location_revision_id, location_id, revision_number,
                    location_name, time_zone, effective_from,
                    supersedes_location_revision_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (?, ?, 1, ?, 'Asia/Shanghai', ?, NULL, ?,
                    '考勤设置公司筛选集成测试', ?, ?)
                """,
                ids.locationRevisionId(), ids.locationId(),
                "筛选共享地点", Date.valueOf("2026-01-01"), DIGEST,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO location_timeline (
                    location_timeline_id, location_id, location_revision_id,
                    event_sequence, state, business_effective_from,
                    predecessor_timeline_id, recorded_at, actor_id, request_id
                ) VALUES (?, ?, ?, 1, 'ACTIVE', ?, NULL, ?, ?, ?)
                """,
                id(marker + "-location-timeline"), ids.locationId(),
                ids.locationRevisionId(), Date.valueOf("2026-01-01"),
                recordedAt, ADMIN_PRINCIPAL, "company-filter-location-" + marker);
        jdbc.update(
                """
                INSERT INTO company_location_availability (
                    company_location_availability_id, shared_location_id,
                    company_id, location_id, location_code,
                    status, effective_from,
                    effective_to, created_by, created_at
                ) VALUES (?, ?, ?, ?, 'FILTER_LOC_SHARED',
                    'ACTIVE', ?, NULL, ?, ?)
                """,
                id(marker + "-location-availability"), sharedLocationId,
                companyId, ids.locationId(), Date.valueOf("2026-01-01"),
                ADMIN_PRINCIPAL, recordedAt);

        jdbc.update(
                """
                INSERT INTO shift_template (
                    shift_template_id, company_id, location_id, template_code,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                ids.shiftId(), companyId, ids.locationId(),
                "FILTER_SHIFT_" + marker, ADMIN_PRINCIPAL, recordedAt);

        jdbc.update(
                """
                INSERT INTO work_calendar (
                    work_calendar_id, company_id, location_id, calendar_code,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                ids.calendarId(), companyId, ids.locationId(),
                "FILTER_CAL_" + marker, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO work_calendar_version (
                    work_calendar_version_id, work_calendar_id, version_number,
                    calendar_name, calendar_year, time_zone_snapshot,
                    effective_from, effective_to,
                    supersedes_work_calendar_version_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (?, ?, 1, ?, 2026, 'Asia/Shanghai', ?, ?, NULL, ?,
                    '考勤设置公司筛选集成测试', ?, ?)
                """,
                ids.calendarVersionId(), ids.calendarId(), "筛选日历" + marker,
                Date.valueOf("2026-01-01"), Date.valueOf("2027-01-01"),
                DIGEST, ADMIN_PRINCIPAL, recordedAt);

        jdbc.update(
                """
                INSERT INTO attendance_group (
                    attendance_group_id, company_id, group_code,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                ids.groupId(), companyId, "FILTER_GROUP_" + marker,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_group_revision (
                    attendance_group_revision_id, attendance_group_id,
                    revision_number, group_name, location_revision_id,
                    work_calendar_id, shift_template_id, effective_from,
                    supersedes_attendance_group_revision_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (?, ?, 1, ?, ?, ?, ?, ?, NULL, ?,
                    '考勤设置公司筛选集成测试', ?, ?)
                """,
                ids.groupRevisionId(), ids.groupId(), "筛选考勤组" + marker,
                ids.locationRevisionId(), ids.calendarId(), ids.shiftId(),
                Date.valueOf("2026-01-01"), DIGEST, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_group_timeline (
                    attendance_group_timeline_id, attendance_group_id,
                    attendance_group_revision_id, event_sequence, state,
                    business_effective_from, predecessor_timeline_id,
                    recorded_at, actor_id, request_id
                ) VALUES (?, ?, ?, 1, 'ACTIVE', ?, NULL, ?, ?, ?)
                """,
                id(marker + "-group-timeline"), ids.groupId(),
                ids.groupRevisionId(), Date.valueOf("2026-01-01"), recordedAt,
                ADMIN_PRINCIPAL, "company-filter-group-" + marker);

        jdbc.update(
                """
                INSERT INTO attendance_policy_scope (
                    scope_id, policy_template_id, company_id, row_version,
                    created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                ids.policyScopeId(), policyTemplateId, companyId,
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scoped_version (
                    scoped_version_id, scope_id, version_number,
                    parameters_json, effective_from, effective_to,
                    validation_json, snapshot_json, snapshot_digest,
                    rollback_of_scoped_version_id, row_version, change_reason,
                    created_by, created_at
                ) VALUES (?, ?, 1, '{}', ?, NULL, '{"valid":true}', '{}', ?,
                    NULL, 1, '考勤设置公司筛选集成测试', ?, ?)
                """,
                ids.policyVersionId(), ids.policyScopeId(),
                Date.valueOf("2026-01-01"), DIGEST, ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_binding_family (
                    binding_family_id, attendance_group_id, policy_kind,
                    created_by, created_at
                ) VALUES (?, ?, 'MEAL_DEDUCTION', ?, ?)
                """,
                ids.bindingFamilyId(), ids.groupId(),
                ADMIN_PRINCIPAL, recordedAt);
        jdbc.update(
                """
                INSERT INTO attendance_policy_binding_revision (
                    binding_revision_id, binding_family_id,
                    attendance_group_revision_id,
                    attendance_policy_scoped_version_id, revision_number,
                    effective_from, supersedes_binding_revision_id,
                    snapshot_digest, change_reason, created_by, created_at
                ) VALUES (?, ?, ?, ?, 1, ?, NULL, ?,
                    '考勤设置公司筛选集成测试', ?, ?)
                """,
                ids.bindingRevisionId(), ids.bindingFamilyId(),
                ids.groupRevisionId(), ids.policyVersionId(),
                Date.valueOf("2026-01-01"), DIGEST, ADMIN_PRINCIPAL, recordedAt);
        return ids;
    }

    private static Timestamp timestamp() {
        return Timestamp.from(Instant.parse("2026-07-20T00:00:00Z"));
    }

    private static String id(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private record SetupIds(
            String locationId,
            String locationRevisionId,
            String shiftId,
            String calendarId,
            String calendarVersionId,
            String groupId,
            String groupRevisionId,
            String policyScopeId,
            String policyVersionId,
            String bindingFamilyId,
            String bindingRevisionId) {
    }
}

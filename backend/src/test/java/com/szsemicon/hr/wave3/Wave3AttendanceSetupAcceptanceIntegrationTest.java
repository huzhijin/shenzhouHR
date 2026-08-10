package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.szsemicon.hr.attendance.application.AttendanceGroupRepository;
import com.szsemicon.hr.attendance.application.AttendanceMonthlyExemptionUsageProvider;
import com.szsemicon.hr.attendance.application.AttendancePolicyRepository;
import com.szsemicon.hr.attendance.application.CalendarRepository;
import com.szsemicon.hr.attendance.application.ShiftRepository;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Assignment;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.AttendanceGroup;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.Location;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendar;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.CalendarSnapshotDigest;
import com.szsemicon.hr.attendance.domain.ShiftSnapshotDigest;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;

/**
 */
@Import(Wave3AttendanceSetupAcceptanceIntegrationTest.FixedClockConfiguration.class)
class Wave3AttendanceSetupAcceptanceIntegrationTest
        extends Wave1IntegrationTestSupport {

    @Autowired
    private AttendanceGroupRepository attendanceGroupRepository;

    @Autowired
    private AttendancePolicyRepository attendancePolicyRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private CalendarRepository calendarRepository;

    @Autowired
    private MutableClock testClock;

    @Autowired
    private MutableUsageProvider testUsageProvider;

    private static final String COMPANY =
            "30000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE =
            "b0000000-0000-0000-0000-000000000001";
    private static final String SECOND_EMPLOYEE =
            "b0000000-0000-0000-0000-000000000002";
    private static final String OUTSIDE_COMPANY_EMPLOYEE =
            "b0000000-0000-0000-0000-000000000004";
    private static final String MEAL_TEMPLATE =
            "86000000-0000-0000-0000-000000000001";
    private static final String LATE_TEMPLATE =
            "86000000-0000-0000-0000-000000000002";
    private static final String MONTHLY_TEMPLATE =
            "86000000-0000-0000-0000-000000000003";
    private static final String MEAL_VERSION =
            "87000000-0000-0000-0000-000000000001";
    private static final String LATE_VERSION =
            "87000000-0000-0000-0000-000000000002";
    private static final String MONTHLY_VERSION =
            "87000000-0000-0000-0000-000000000003";
    private static final String DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final AtomicLong AUTO_IDEMPOTENCY_SEQUENCE = new AtomicLong();
    private static final Instant FIXTURE_RECORDED_AT =
            Instant.parse("2026-07-20T00:00:00Z");

    @BeforeEach
    void seedControlledAttendancePolicies() {
        testClock.setInstant(Instant.parse("2026-07-27T00:00:00Z"));
        testUsageProvider.reset();
        insertPolicy(
                MEAL_TEMPLATE,
                "MEAL_DEDUCTION",
                "晚餐扣除",
                MEAL_VERSION,
                """
                [
                  {"key":"enabled","value":true},
                  {"key":"mealWindowStart","value":"18:00"},
                  {"key":"mealWindowEnd","value":"20:00"},
                  {"key":"deductionMinutes","value":30},
                  {"key":"triggerMinutes","value":240},
                  {"key":"applicableDayTypes","value":["SPECIAL_WORKDAY","WORKDAY"]}
                ]
                """);
        insertPolicy(
                LATE_TEMPLATE,
                "LATE_GRACE",
                "迟到分钟宽限",
                LATE_VERSION,
                """
                {"enabled":true,"graceMinutes":15}
                """);
        insertPolicy(
                MONTHLY_TEMPLATE,
                "MONTHLY_LATE_EXEMPTION",
                "自然月迟到豁免",
                MONTHLY_VERSION,
                """
                {"enabled":true,"graceMinutes":15,"monthlyUses":1,"resetOnGroupChange":false}
                """);
        insertPolicy(
                "86000000-0000-0000-0000-000000000004",
                "PUNCH_WINDOW",
                "打卡取卡窗口",
                "87000000-0000-0000-0000-000000000004",
                """
                {"arrivalBeforeMinutes":30,"arrivalAfterMinutes":30,
                 "departureBeforeMinutes":30,"departureAfterMinutes":30}
                """);
        insertPolicy(
                "86000000-0000-0000-0000-000000000005",
                "PERIOD_CLOSE",
                "月结封账",
                "87000000-0000-0000-0000-000000000005",
                """
                {"closeDayOfNextMonth":5,"reopenAllowed":false,
                 "reopenRequiresApproval":false,"maxReopenCount":0}
                """);
    }

    @Test
    void complete_setup_resolves_adjacent_group_change_and_real_policy_impact()
            throws Exception {
        Setup setup = createBaseSetup();
        String firstAssignment = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-first",
                "2026-08-14",
                "2026-08-15");
        String secondAssignment = createAssignment(
                setup.secondGroupId(),
                "wave3-assignment-second",
                "2026-08-15",
                null);

        MvcResult replay = create(
                "/api/v1/attendance-setup/groups/%s/assignments"
                        .formatted(setup.secondGroupId()),
                "wave3-assignment-second",
                assignmentBody("2026-08-15", null))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.assignmentId").value(secondAssignment))
                .andReturn();
        assertThat(value(replay, "$.assignmentId")).isEqualTo(secondAssignment);

        MvcResult beforeChange = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.groupId").value(setup.firstGroupId()))
                .andExpect(jsonPath("$.shiftVersion.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.policyBindings.length()").value(3))
                .andReturn();
        MvcResult afterChange = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-15")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.groupId").value(setup.secondGroupId()))
                .andExpect(jsonPath("$.policyBindings[1].policyKind")
                        .value("LATE_GRACE"))
                .andExpect(jsonPath("$.policyBindings[1].priority").doesNotExist())
                .andExpect(jsonPath("$.policyBindings.length()").value(3))
                .andReturn();

        assertThat(value(beforeChange, "$.monthlyContextKey"))
                .isEqualTo(EMPLOYEE + ":2026-08");
        assertThat(value(afterChange, "$.monthlyContextKey"))
                .isEqualTo(EMPLOYEE + ":2026-08");

        // Open assignments validate their first business date at write time.
        // Future configuration is still resolved fail-closed when that date
        // is actually requested.
        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2027-01-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("CALENDAR_VERSION_MISSING"));

        write(
                post("/api/v1/attendance-setup/policy-impact-preview"),
                """
                {
                  "policyKind":"LATE_GRACE",
                  "policyVersionId":"%s",
                  "groupId":"%s",
                  "groupRevisionId":"%s",
                  "effectiveFrom":"2026-08-15",
                  "reason":"WAVE-3 真实影响范围预览"
                }
                """.formatted(
                        LATE_VERSION,
                        setup.secondGroupId(),
                        groupRevisionId(setup.secondGroupId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCount").value(1))
                .andExpect(jsonPath("$.assignmentCount").value(1))
                .andExpect(jsonPath("$.countSource")
                        .value("REAL_ATTENDANCE_ASSIGNMENTS"))
                .andExpect(jsonPath("$.impactToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class))
                .isEqualTo(2);
        assertThat(auditCountForResource(firstAssignment)).isEqualTo(1);
        assertThat(auditCountForResource(secondAssignment)).isEqualTo(1);
    }

    @Test
    void overlap_stale_write_and_reader_permissions_are_enforced() throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-overlap-base",
                "2026-08-14",
                "2026-08-15");

        create(
                "/api/v1/attendance-setup/groups/%s/assignments"
                        .formatted(setup.secondGroupId()),
                "wave3-assignment-overlap-candidate",
                assignmentBody("2026-08-14", "2026-08-20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ATTENDANCE_ASSIGNMENT_OVERLAP"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND result_code = 'FAILURE'
                  AND reason_code = 'ATTENDANCE_ASSIGNMENT_OVERLAP'
                """,
                Long.class)).isEqualTo(1);

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        setup.locationId())
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-stale-location-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 陈旧版本更新")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody("WAVE-3 陈旧版本更新")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/attendance-setup/locations")
                        .with(user(LIMITED_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].locationId")
                        .value(setup.locationId()));

        mockMvc.perform(post("/api/v1/attendance-setup/locations")
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", "wave3-reader-write-denied")
                        .header("X-Change-Reason", "WAVE-3 只读权限拒绝")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(locationBody("WAVE-3 只读权限拒绝")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_DENIED'
                  AND result_code = 'DENIED'
                  AND reason_code = 'ACCESS_DENIED'
                """,
                Long.class)).isEqualTo(1);

        mockMvc.perform(post(
                        "/api/v1/attendance-setup/groups/{groupId}/deactivate",
                        setup.secondGroupId())
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-group-deactivate-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 考勤组停用留痕")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"reason":"WAVE-3 考勤组停用留痕"}
                                 """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}",
                        setup.secondGroupId())
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, include_descendants,
                    valid_from, valid_to
                ) VALUES (
                    '99000000-0000-0000-0000-000000000002',
                    'COMPANY',
                    '30000000-0000-0000-0000-000000000002',
                    TRUE, TIMESTAMP '2020-01-01 00:00:00', NULL
                )
                """);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (
                    'a1000000-0000-0000-0000-000000000099',
                    ?, ?, '99000000-0000-0000-0000-000000000002',
                    TIMESTAMP '2026-01-01 00:00:00', NULL, ?,
                    'WAVE-3 CROSS-SCOPE TEST', 0
                )
                """,
                OUTSIDE_SCOPE_PRINCIPAL, ADMIN_ROLE, ADMIN_PRINCIPAL);
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}",
                        setup.firstGroupId())
                        .with(user(OUTSIDE_SCOPE_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}",
                        "00000000-0000-0000-0000-000000000000")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void committed_replay_rechecks_capability_after_the_actor_is_revoked()
            throws Exception {
        String idempotencyKey = "wave3-replay-after-capability-revocation";
        String body = locationBodyFor(
                "REPLAY_REVOKED",
                "Asia/Shanghai",
                "WAVE-3 成功后撤权重放");
        MvcResult first = create(
                "/api/v1/attendance-setup/locations",
                idempotencyKey,
                body)
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed", "false"))
                .andReturn();
        String locationId = value(first, "$.locationId");

        jdbc.update(
                """
                DELETE FROM auth_principal_role_assignment
                WHERE principal_id = ?
                """,
                ADMIN_PRINCIPAL);

        create(
                "/api/v1/attendance-setup/locations",
                idempotencyKey,
                body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM location WHERE location_id = ?",
                Long.class,
                locationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_setup_idempotency
                WHERE actor_id = ?
                  AND idempotency_key = ?
                  AND state = 'COMPLETED_SUCCESS'
                """,
                Long.class,
                ADMIN_PRINCIPAL,
                idempotencyKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_LOCATION_CREATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                locationId)).isEqualTo(1);
    }

    @Test
    void controlled_policy_simulations_cover_boundaries_without_formal_writes()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.secondGroupId(),
                "wave3-authoritative-simulation-assignment",
                "2026-08-15",
                null);
        testClock.setInstant(Instant.parse("2026-08-15T16:00:00Z"));
        Map<String, Long> formalRowsBefore = formalAttendanceTableCounts();
        assertFormalResultTablesAbsent();
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"2026-08-15T12:00:00+08:00",
                  "punches":[{
                    "direction":"EXIT",
                    "instant":"2026-08-16T04:00:00+08:00",
                    "association":"SCHEDULED_WORK"
                  }]
                }
                """.formatted(EMPLOYEE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNCH_ENTRY_MISSING"));
        testUsageProvider.setEmployeeIdOverride(SECOND_EMPLOYEE);
        simulateLate(1, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USAGE_PROJECTION_INVALID"));
        testUsageProvider.reset();
        testUsageProvider.setKnowledgeTimeOverride(
                Instant.parse("2026-08-15T04:00:01Z"));
        simulateLate(1, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("USAGE_KNOWLEDGE_AFTER_CORRECTION"));
        testUsageProvider.reset();
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                simulationRequest(
                        "2026-08-14T23:59:59+08:00",
                        "2026-08-15T20:00:00+08:00",
                        null,
                        null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                simulationRequest(
                        "2026-08-16T00:00:01Z",
                        "2026-08-15T20:00:00+08:00",
                        null,
                        null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                simulationRequest(
                        "2026-08-15T12:00:00+08:00",
                        "2026-08-15T20:00:00+08:00",
                        "segment-99",
                        "SCHEDULED_WORK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"2026-08-15",
                  "punches":[],
                  "policyUsage":{"naturalMonthLateGraceUses":0}
                }
                """.formatted(EMPLOYEE))
                .andExpect(status().isBadRequest());
        simulateLate(0, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[1].policyKind")
                        .value("LATE_GRACE"))
                .andExpect(jsonPath("$.results[1].status").value("ON_TIME"))
                .andExpect(jsonPath("$.results[2].predictedMonthlyConsumption").value(0));
        simulateLate(1, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[1].status").value("EXEMPTED"))
                .andExpect(jsonPath("$.results[2].status").value("EXEMPTED"))
                .andExpect(jsonPath("$.results[2].predictedMonthlyConsumption").value(1));
        simulateLate(15, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[2].status").value("EXEMPTED"));
        simulateLate(16, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[2].status").value("LATE"))
                .andExpect(jsonPath("$.results[2].predictedMonthlyConsumption").value(0));
        simulateLate(15, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[2].status").value("LATE"))
                .andExpect(jsonPath("$.results[2].predictedMonthlyConsumption").value(0))
                .andExpect(jsonPath("$.results[2].usageProvenance")
                        .value("TEST_AUTHORITATIVE_MONTHLY_USAGE"));

        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"2026-08-15T12:00:00+08:00",
                  "punches":[
                    {
                      "direction":"ENTRY",
                      "instant":"2026-08-15T17:00:00+08:00",
                      "association":"SCHEDULED_WORK"
                    },
                    {
                      "direction":"EXIT",
                      "instant":"2026-08-15T21:00:00+08:00",
                      "association":"SCHEDULED_WORK"
                    }
                  ]
                }
                """.formatted(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.results[0].policyVersionId")
                        .value(MEAL_VERSION))
                .andExpect(jsonPath("$.configurationDigest").isNotEmpty())
                .andExpect(jsonPath("$.results[0].matched").value(true))
                .andExpect(jsonPath("$.results[0].deductionMinutes").value(30))
                .andExpect(jsonPath("$.results[0].matchedMealWindows.length()")
                        .value(1))
                .andExpect(jsonPath("$.results[0].matchedMealWindows[0].windowId")
                        .value("BASE_DINNER"))
                .andExpect(jsonPath("$.results[0].writesFormalResult").value(false));

        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"2026-08-15T12:00:00+08:00",
                  "punches":[{
                    "direction":"ENTRY",
                    "instant":"2026-08-15T20:00:00+08:00",
                    "association":"SCHEDULED_WORK"
                  }]
                }
                """.formatted(EMPLOYEE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[2].status").value("ON_TIME"))
                .andExpect(jsonPath("$.results[2].writesFormalResult").value(false));

        jdbc.update(
                """
                INSERT INTO attendance_group_assignment (
                    attendance_group_assignment_id, employee_id,
                    attendance_group_revision_id, effective_from,
                    supersedes_assignment_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (
                    ?, ?,
                    (
                        SELECT timeline.attendance_group_revision_id
                        FROM attendance_group_timeline timeline
                        WHERE timeline.attendance_group_id = ?
                          AND timeline.state = 'ACTIVE'
                        ORDER BY timeline.event_sequence DESC
                        LIMIT 1
                    ),
                    DATE '2026-08-15', NULL, ?, ?, ?, ?
                )
                """,
                "7f000000-0000-0000-0000-000000000001",
                EMPLOYEE,
                setup.secondGroupId(),
                "7f00000000000000000000000000000000000000000000000000000000000001",
                "未来知识时态测试",
                ADMIN_PRINCIPAL,
                java.sql.Timestamp.from(Instant.parse("2026-08-15T05:00:00Z")));
        jdbc.update(
                """
                INSERT INTO attendance_assignment_timeline (
                    attendance_assignment_timeline_id,
                    attendance_group_assignment_id, employee_id,
                    event_sequence, state, business_effective_from,
                    predecessor_timeline_id, recorded_at, actor_id, request_id
                ) VALUES (
                    '7f000000-0000-0000-0000-000000000002',
                    '7f000000-0000-0000-0000-000000000001', ?,
                    1, 'ACTIVE', DATE '2026-08-15',
                    NULL, ?, ?, 'future-knowledge-assignment'
                )
                """,
                EMPLOYEE,
                java.sql.Timestamp.from(Instant.parse("2026-08-15T05:00:00Z")),
                ADMIN_PRINCIPAL);
        formalRowsBefore = formalAttendanceTableCounts();
        String deterministicRequest = simulationRequest(
                "2026-08-15T12:00:00+08:00",
                "2026-08-15T20:01:00+08:00",
                null,
                "SCHEDULED_WORK");
        MvcResult firstReplay = write(
                post("/api/v1/attendance-setup/policy-simulations"),
                deterministicRequest)
                .andExpect(status().isOk())
                .andReturn();
        MvcResult secondReplay = write(
                post("/api/v1/attendance-setup/policy-simulations"),
                deterministicRequest)
                .andExpect(status().isOk())
                .andReturn();
        assertThat(secondReplay.getResponse().getContentAsString())
                .isEqualTo(firstReplay.getResponse().getContentAsString());
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                simulationRequest(
                        "2026-08-15T14:00:00+08:00",
                        "2026-08-15T20:01:00+08:00",
                        null,
                        "SCHEDULED_WORK"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_AMBIGUOUS"));
        assertThat(formalAttendanceTableCounts()).isEqualTo(formalRowsBefore);
    }

    private String simulationRequest(
            String correctionAsOf,
            String instant,
            String workSegmentId,
            String association) {
        String optionalSegment = workSegmentId == null
                ? "" : ",\"workSegmentId\":\"" + workSegmentId + "\"";
        String optionalAssociation = association == null
                ? "" : ",\"association\":\"" + association + "\"";
        return """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"%s",
                  "punches":[{
                    "direction":"ENTRY",
                    "instant":"%s"%s%s
                  }]
                }
                """.formatted(
                        EMPLOYEE,
                        correctionAsOf,
                        instant,
                        optionalSegment,
                        optionalAssociation);
    }

    @Test
    void resolution_fails_closed_when_any_required_policy_kind_is_missing()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-policy-missing",
                "2026-08-14",
                null);
        deactivatePolicyAt(MONTHLY_VERSION, "2026-08-14");

        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_MISSING"));
    }

    @Test
    void resolution_rejects_inactive_policy_version_and_ambiguous_assignment()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-ambiguous-first",
                "2026-08-14",
                null);
        jdbc.update(
                """
                INSERT INTO attendance_group_assignment (
                    attendance_group_assignment_id, employee_id,
                    attendance_group_revision_id, effective_from,
                    supersedes_assignment_id, snapshot_digest,
                    change_reason, created_by, created_at
                ) VALUES (
                    '99000000-0000-0000-0000-000000000081', ?,
                    (
                        SELECT timeline.attendance_group_revision_id
                        FROM attendance_group_timeline timeline
                        WHERE timeline.attendance_group_id = ?
                          AND timeline.state = 'ACTIVE'
                        ORDER BY timeline.event_sequence DESC
                        LIMIT 1
                    ),
                    DATE '2026-08-14', NULL,
                    '9900000000000000000000000000000000000000000000000000000000000081',
                    'WAVE-3 歧义负例', ?, ?
                )
                """,
                EMPLOYEE,
                setup.secondGroupId(),
                ADMIN_PRINCIPAL,
                Timestamp.from(FIXTURE_RECORDED_AT));
        jdbc.update(
                """
                INSERT INTO attendance_assignment_timeline (
                    attendance_assignment_timeline_id,
                    attendance_group_assignment_id, employee_id,
                    event_sequence, state, business_effective_from,
                    predecessor_timeline_id, recorded_at, actor_id, request_id
                ) VALUES (
                    '99000000-0000-0000-0000-000000000082',
                    '99000000-0000-0000-0000-000000000081', ?,
                    1, 'ACTIVE', DATE '2026-08-14',
                    NULL, ?, ?, 'wave3-direct-ambiguous'
                )
                """,
                EMPLOYEE,
                Timestamp.from(FIXTURE_RECORDED_AT),
                ADMIN_PRINCIPAL);

        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_AMBIGUOUS"));

        jdbc.update(
                """
                DELETE FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id =
                    '99000000-0000-0000-0000-000000000081'
                """);
        jdbc.update(
                """
                DELETE FROM attendance_group_assignment
                WHERE attendance_group_assignment_id =
                    '99000000-0000-0000-0000-000000000081'
                """);
        deactivatePolicyAt(MONTHLY_VERSION, "2026-08-14");

        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_MISSING"));
    }

    @Test
    void resolution_rejects_employee_group_company_mismatch()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-cross-company",
                "2026-08-14",
                null);
        jdbc.update(
                """
                UPDATE attendance_group
                SET company_id = '30000000-0000-0000-0000-000000000002'
                WHERE attendance_group_id = ?
                """,
                setup.firstGroupId());

        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

    }

    @Test
    void group_creation_rejects_shift_location_and_calendar_timezone_mismatch()
            throws Exception {
        MvcResult firstLocation = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-reference-a",
                locationBodyFor(
                        "REFERENCE_A", "Asia/Shanghai", "WAVE-3 关联地点 A"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult secondLocation = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-reference-b",
                locationBodyFor(
                        "REFERENCE_B", "Asia/Shanghai", "WAVE-3 关联地点 B"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstLocationId = value(firstLocation, "$.locationId");
        String secondLocationId = value(secondLocation, "$.locationId");
        String shiftId = createShiftTemplate(
                firstLocationId,
                "REFERENCE_SHIFT",
                "关联校验班次",
                "wave3-reference-shift");
        String versionId = createShiftVersion(
                shiftId,
                "wave3-reference-version",
                "2026-01-01",
                null);
        publishShiftVersion(shiftId, versionId).andExpect(status().isOk());

        MvcResult calendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-reference-calendar",
                """
                {
                  "companyId":"%s",
                  "code":"REFERENCE_2026",
                  "name":"关联校验日历",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-08-15",
                  "reason":"WAVE-3 时区关联负例"
                }
                """.formatted(COMPANY, firstLocationId))
                .andExpect(status().isCreated())
                .andReturn();
        String calendarId = value(calendar, "$.calendarId");
        replaceCalendarDays(
                calendarId,
                0,
                "WAVE-3 关联校验日历日",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"WORKDAY"
                  }
                ]
                """)
                .andExpect(status().isOk());
        publishAndActivateCalendar(calendarId, 1);

        create(
                "/api/v1/attendance-setup/groups",
                "wave3-reference-group-mismatch",
                """
                {
                  "companyId":"%s",
                  "code":"REFERENCE_GROUP",
                  "name":"关联校验考勤组",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-14",
                  "reason":"WAVE-3 关联一致性负例"
                }
                """.formatted(
                        COMPANY,
                        secondLocationId,
                        calendarId,
                        shiftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"));

        assertThat(versionId).isNotBlank();
    }

    @Test
    void meal_simulation_rejects_client_asserted_window_match()
            throws Exception {
        write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "policyKind":"MEAL_DEDUCTION",
                  "policyVersionId":"%s",
                  "syntheticInput":{
                    "attendedMinutes":300,
                    "mealWindowMatched":true,
                    "dayType":"WORKDAY"
                  }
                }
                """.formatted(MEAL_VERSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cross_midnight_shift_is_valid_and_calendar_rejects_duplicate_dates()
            throws Exception {
        Setup setup = createBaseSetup();
        assertThat(jdbc.queryForObject(
                """
                SELECT state
                FROM shift_publication_timeline
                WHERE shift_version_id = ?
                ORDER BY event_sequence DESC
                LIMIT 1
                """,
                String.class,
                setup.shiftVersionId())).isEqualTo("PUBLISHED");
        MvcResult draftCalendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-calendar-validation-draft",
                """
                {
                  "companyId":"%s",
                  "code":"VALIDATION_2026",
                  "name":"日历校验草稿",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-09-02",
                  "reason":"WAVE-3 日历负例草稿"
                }
                """.formatted(COMPANY, setup.locationId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String draftCalendarId = value(draftCalendar, "$.calendarId");

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/calendars/{calendarId}/days",
                        draftCalendarId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-duplicate-calendar-day-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 重复日期检查")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "businessDate":"2026-08-14",
                                    "dayType":"WORKDAY",
                                    "shiftVersionOverrideId":"%s"
                                  },
                                  {
                                    "businessDate":"2026-08-14",
                                    "dayType":"WORKDAY",
                                    "shiftVersionOverrideId":"%s"
                                  }
                                ]
                                """.formatted(
                                setup.shiftVersionId(), setup.shiftVersionId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORK_CALENDAR_DATE_DUPLICATE"));

        replaceCalendarDays(
                draftCalendarId,
                0,
                "WAVE-3 默认班次来自考勤组",
                """
                [
                  {
                    "businessDate":"2026-09-01",
                    "dayType":"WORKDAY"
                  }
                ]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1));
    }

    @Test
    void calendar_day_patch_creates_new_version_and_preserves_old_day_bytes()
            throws Exception {
        Setup setup = createBaseSetup();
        MvcResult draftCalendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-calendar-history-draft",
                """
                {
                  "companyId":"%s",
                  "code":"HISTORY_2026",
                  "name":"日历日历史草稿",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-08-16",
                  "reason":"WAVE-3 日历日历史草稿"
                }
                """.formatted(COMPANY, setup.locationId()))
                .andExpect(status().isCreated())
                .andReturn();
        String draftCalendarId = value(draftCalendar, "$.calendarId");
        replaceCalendarDays(
                draftCalendarId,
                0,
                "WAVE-3 日历日初始版本",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"WORKDAY",
                    "shiftVersionOverrideId":"%s"
                  },
                  {
                    "businessDate":"2026-08-15",
                    "dayType":"SPECIAL_WORKDAY",
                    "shiftVersionOverrideId":"%s"
                  }
                ]
                """.formatted(
                        setup.shiftVersionId(),
                        setup.shiftVersionId()))
                .andExpect(status().isOk());
        String firstDraftVersionId = jdbc.queryForObject(
                """
                SELECT work_calendar_version_id
                FROM work_calendar_version
                WHERE work_calendar_id = ?
                ORDER BY version_number DESC
                LIMIT 1
                """,
                String.class,
                draftCalendarId);
        String originalDayId = jdbc.queryForObject(
                """
                SELECT work_calendar_day_id
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-14'
                """,
                String.class,
                firstDraftVersionId);
        String originalDayDigest = jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-14'
                """,
                String.class,
                firstDraftVersionId);

        replaceCalendarDays(
                draftCalendarId,
                1,
                "WAVE-3 日历日可审计更新",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"PUBLIC_HOLIDAY"
                  }
                ]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));

        String successorVersionId = jdbc.queryForObject(
                """
                SELECT work_calendar_version_id
                FROM work_calendar_version
                WHERE work_calendar_id = ?
                ORDER BY version_number DESC
                LIMIT 1
                """,
                String.class,
                draftCalendarId);
        assertThat(successorVersionId).isNotEqualTo(firstDraftVersionId);

        assertThat(jdbc.queryForObject(
                """
                SELECT work_calendar_day_id
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-14'
                """,
                String.class,
                firstDraftVersionId)).isEqualTo(originalDayId);
        assertThat(jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-14'
                """,
                String.class,
                firstDraftVersionId)).isEqualTo(originalDayDigest);
        assertThat(jdbc.queryForObject(
                """
                SELECT day_type
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-14'
                """,
                String.class,
                successorVersionId)).isEqualTo("PUBLIC_HOLIDAY");
        assertThat(jdbc.queryForObject(
                """
                SELECT day_type
                FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-15'
                """,
                String.class,
                successorVersionId)).isEqualTo("SPECIAL_WORKDAY");
    }

    @Test
    void calendarPublishDigestMatchesResponseGetDatabaseAndExactReplay()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-calendar-digest-location",
                locationBodyFor(
                        "CALENDAR_DIGEST",
                        "Asia/Shanghai",
                        "WAVE-3 日历摘要地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String locationId = value(location, "$.locationId");
        MvcResult created = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-calendar-digest-create",
                """
                {
                  "companyId":"%s",
                  "code":"DIGEST_2026",
                  "name":"摘要一致性日历",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-08-15",
                  "reason":"WAVE-3 创建摘要一致性日历"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotDigest")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[a-f0-9]{64}$")))
                .andReturn();
        String calendarId = value(created, "$.calendarId");
        MvcResult completedDraft = replaceCalendarDays(
                calendarId,
                0,
                "WAVE-3 配置摘要一致性日历日",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"WEEKEND"
                  }
                ]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1))
                .andReturn();
        String versionId = value(completedDraft, "$.calendarVersionId");
        String draftDigest = value(completedDraft, "$.snapshotDigest");

        WorkCalendar persistedDraft =
                calendarRepository.findVersion(versionId).orElseThrow();
        List<WorkCalendarDay> persistedDays = calendarRepository.listDays(
                versionId,
                LocalDate.parse("2026-08-14"),
                LocalDate.parse("2026-08-14"),
                100,
                0);
        assertThat(CalendarSnapshotDigest.digest(
                persistedDraft, persistedDays)).isEqualTo(draftDigest);

        String idempotencyKey = "wave3-calendar-digest-publish-"
                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet();
        String publishBody =
                """
                {"reason":"WAVE-3 发布摘要一致性日历"}
                """;
        MvcResult firstPublish = write(
                post("/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/publish",
                        calendarId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey),
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.snapshotDigest").value(draftDigest))
                .andReturn();
        MvcResult replay = write(
                post("/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/publish",
                        calendarId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey),
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.snapshotDigest").value(draftDigest))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(firstPublish.getResponse().getContentAsString());

        read(
                "/api/v1/attendance-setup/calendars/%s/versions"
                        .formatted(calendarId),
                "page", "0",
                "size", "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].calendarVersionId")
                        .value(versionId))
                .andExpect(jsonPath("$.items[0].snapshotDigest")
                        .value(draftDigest));
        assertThat(calendarRepository.findVersion(versionId))
                .get()
                .extracting(WorkCalendar::snapshotDigest)
                .isEqualTo(draftDigest);
        assertThat(jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM work_calendar_version
                WHERE work_calendar_version_id = ?
                """,
                String.class,
                versionId)).isEqualTo(draftDigest);
    }

    @Test
    void shiftPublishDigestMatchesResponseGetDatabaseAndExactReplay()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-shift-digest-location",
                locationBodyFor(
                        "SHIFT_DIGEST",
                        "Asia/Shanghai",
                        "WAVE-3 班次摘要地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String shiftId = createShiftTemplate(
                value(location, "$.locationId"),
                "DIGEST_SHIFT",
                "摘要一致性班次",
                "wave3-shift-digest-template");
        MvcResult created = create(
                "/api/v1/attendance-setup/shifts/%s/versions"
                        .formatted(shiftId),
                "wave3-shift-digest-create",
                """
                {
                  "effectiveFrom":"2026-10-01",
                  "effectiveTo":"2026-11-01",
                  "segments":[
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"08:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"17:00:00",
                      "endDayOffset":0
                    }
                  ],
                  "reason":"WAVE-3 创建摘要一致性班次"
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotDigest")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "^[a-f0-9]{64}$")))
                .andReturn();
        String versionId = value(created, "$.shiftVersionId");
        String draftDigest = value(created, "$.snapshotDigest");

        var persistedDraft =
                shiftRepository.findVersion(versionId).orElseThrow();
        assertThat(ShiftSnapshotDigest.digest(persistedDraft))
                .isEqualTo(draftDigest);

        String idempotencyKey = "wave3-shift-digest-publish-"
                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet();
        String publishBody =
                """
                {"reason":"WAVE-3 发布摘要一致性班次"}
                """;
        MvcResult firstPublish = write(
                post("/api/v1/attendance-setup/shifts/{shiftId}"
                                + "/versions/{versionId}/publish",
                        shiftId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey),
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.snapshotDigest").value(draftDigest))
                .andReturn();
        MvcResult replay = write(
                post("/api/v1/attendance-setup/shifts/{shiftId}"
                                + "/versions/{versionId}/publish",
                        shiftId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey),
                publishBody)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.snapshotDigest").value(draftDigest))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(firstPublish.getResponse().getContentAsString());

        read(
                "/api/v1/attendance-setup/shifts/%s/versions"
                        .formatted(shiftId),
                "page", "0",
                "size", "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].shiftVersionId")
                        .value(versionId))
                .andExpect(jsonPath("$.items[0].snapshotDigest")
                        .value(draftDigest));
        assertThat(shiftRepository.findVersion(versionId))
                .get()
                .satisfies(version ->
                        assertThat(version.snapshotDigest())
                                .isEqualTo(draftDigest));
        assertThat(jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM shift_version
                WHERE shift_version_id = ?
                """,
                String.class,
                versionId)).isEqualTo(draftDigest);
    }

    @Test
    void shiftFutureDeactivationAtomicallyCarriesAdjacentPublishedSuccessor()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-shift-future-close-location",
                locationBodyFor(
                        "SHIFT_FUTURE_CLOSE",
                        "Asia/Shanghai",
                        "WAVE-3 班次未来停用地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String shiftId = createShiftTemplate(
                value(location, "$.locationId"),
                "FUTURE_CLOSE",
                "未来停用连续班次",
                "wave3-shift-future-close-template");
        String firstVersionId = createShiftVersion(
                shiftId,
                "wave3-shift-future-close-first",
                "2026-08-01",
                "2026-09-01");
        String successorVersionId = createShiftVersion(
                shiftId,
                "wave3-shift-future-close-successor",
                "2026-09-01",
                "2026-10-01");
        publishShiftVersion(shiftId, firstVersionId)
                .andExpect(status().isOk());
        publishShiftVersion(shiftId, successorVersionId)
                .andExpect(status().isOk());

        String idempotencyKey = "wave3-shift-future-close-"
                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet();
        String body =
                """
                {
                  "status":"INACTIVE",
                  "businessEffectiveFrom":"2026-09-01",
                  "reason":"WAVE-3 班次未来边界停用"
                }
                """;
        MvcResult first = write(
                post("/api/v1/attendance-setup/shifts/{shiftId}"
                                + "/versions/{versionId}/status",
                        shiftId,
                        firstVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey),
                body)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn();
        MvcResult replay = write(
                post("/api/v1/attendance-setup/shifts/{shiftId}"
                                + "/versions/{versionId}/status",
                        shiftId,
                        firstVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey),
                body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        Instant knowledgeAsOf = testClock.instant().plusSeconds(1);
        assertThat(shiftRepository.resolvePublishedAt(
                shiftId,
                LocalDate.parse("2026-08-31"),
                knowledgeAsOf))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.shiftVersionId())
                            .isEqualTo(firstVersionId);
                    assertThat(resolved.status().name())
                            .isEqualTo("PUBLISHED");
                });
        assertThat(shiftRepository.resolvePublishedAt(
                shiftId,
                LocalDate.parse("2026-09-01"),
                knowledgeAsOf))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.shiftVersionId())
                            .isEqualTo(successorVersionId);
                    assertThat(resolved.status().name())
                            .isEqualTo("PUBLISHED");
                });
        assertThat(jdbc.queryForList(
                """
                SELECT state
                FROM shift_publication_timeline
                WHERE shift_template_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                shiftId)).containsExactly(
                        "PUBLISHED", "PUBLISHED", "INACTIVE", "PUBLISHED");
    }

    @Test
    void calendarFutureDeactivationAtomicallyCarriesAdjacentPublishedSuccessor()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-calendar-future-close-location",
                locationBodyFor(
                        "CALENDAR_FUTURE_CLOSE",
                        "Asia/Shanghai",
                        "WAVE-3 日历未来停用地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String locationId = value(location, "$.locationId");
        MvcResult created = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-calendar-future-close-family",
                """
                {
                  "companyId":"%s",
                  "code":"FUTURE_CLOSE_2026",
                  "name":"未来停用连续日历一",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":"2026-08-03",
                  "reason":"WAVE-3 创建第一段连续日历"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andReturn();
        String calendarId = value(created, "$.calendarId");
        MvcResult completedFirst = replaceCalendarDays(
                calendarId,
                0,
                "WAVE-3 配置第一段连续日历",
                """
                [
                  {"businessDate":"2026-08-01","dayType":"WEEKEND"},
                  {"businessDate":"2026-08-02","dayType":"WEEKEND"}
                ]
                """)
                .andExpect(status().isOk())
                .andReturn();
        String firstVersionId =
                value(completedFirst, "$.calendarVersionId");
        publishCalendarVersion(calendarId, firstVersionId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));

        MvcResult successorDraft = create(
                "/api/v1/attendance-setup/calendars/%s/versions"
                        .formatted(calendarId),
                "wave3-calendar-future-close-successor",
                """
                {
                  "name":"未来停用连续日历二",
                  "calendarYear":2026,
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-03",
                  "effectiveTo":"2026-08-05",
                  "reason":"WAVE-3 创建承接日历"
                }
                """)
                .andExpect(status().isCreated())
                .andReturn();
        String successorDraftId =
                value(successorDraft, "$.calendarVersionId");
        MvcResult completedSuccessor = upsertCalendarVersionDays(
                calendarId,
                successorDraftId,
                0,
                "WAVE-3 配置承接日历",
                """
                [
                  {"businessDate":"2026-08-03","dayType":"WEEKEND"},
                  {"businessDate":"2026-08-04","dayType":"WEEKEND"}
                ]
                """)
                .andExpect(status().isOk())
                .andReturn();
        String successorVersionId =
                value(completedSuccessor, "$.calendarVersionId");
        publishCalendarVersion(calendarId, successorVersionId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));

        String idempotencyKey = "wave3-calendar-future-close-"
                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet();
        String body =
                """
                {
                  "businessEffectiveFrom":"2026-08-03",
                  "reason":"WAVE-3 日历未来边界停用"
                }
                """;
        MvcResult first = write(
                post("/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/deactivate",
                        calendarId,
                        firstVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", idempotencyKey),
                body)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn();
        MvcResult replay = write(
                post("/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/deactivate",
                        calendarId,
                        firstVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .header("Idempotency-Key", idempotencyKey),
                body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        Instant knowledgeAsOf = testClock.instant().plusSeconds(1);
        assertThat(calendarRepository.resolvePublishedVersions(
                calendarId,
                LocalDate.parse("2026-08-02"),
                knowledgeAsOf))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.calendarVersionId())
                            .isEqualTo(firstVersionId);
                    assertThat(resolved.status().name())
                            .isEqualTo("PUBLISHED");
                });
        assertThat(calendarRepository.resolvePublishedVersions(
                calendarId,
                LocalDate.parse("2026-08-03"),
                knowledgeAsOf))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.calendarVersionId())
                            .isEqualTo(successorVersionId);
                    assertThat(resolved.status().name())
                            .isEqualTo("PUBLISHED");
                });
        assertThat(jdbc.queryForList(
                """
                SELECT state
                FROM calendar_publication_timeline
                WHERE work_calendar_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                calendarId)).containsExactly(
                        "PUBLISHED", "PUBLISHED", "INACTIVE", "PUBLISHED");
    }

    @Test
    void cross_midnight_segments_use_explicit_start_and_end_day_offsets()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-offsets",
                locationBody("WAVE-3 跨午夜偏移地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String shiftId = createShiftTemplate(
                value(location, "$.locationId"),
                "OFFSET_NIGHT",
                "跨午夜三段班次",
                "wave3-shift-offsets");

        create(
                "/api/v1/attendance-setup/shifts/%s/versions".formatted(shiftId),
                "wave3-version-explicit-offsets",
                """
                {
                  "effectiveFrom":"2026-08-01",
                  "segments":[
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"20:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"01:00:00",
                      "endDayOffset":1
                    },
                    {
                      "segmentType":"BREAK",
                      "startLocalTime":"01:00:00",
                      "startDayOffset":1,
                      "endLocalTime":"01:15:00",
                      "endDayOffset":1
                    },
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"01:15:00",
                      "startDayOffset":1,
                      "endLocalTime":"04:00:00",
                      "endDayOffset":1
                    }
                  ],
                  "reason":"WAVE-3 跨午夜显式日偏移"
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segments[0].startDayOffset").value(0))
                .andExpect(jsonPath("$.segments[1].startDayOffset").value(1))
                .andExpect(jsonPath("$.segments[2].endDayOffset").value(1));
    }

    @Test
    void shift_calendar_and_binding_lifecycle_preserves_history_and_immutability()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-lifecycle-location",
                locationBodyFor(
                        "LIFECYCLE", "Asia/Shanghai", "WAVE-3 生命周期地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String locationId = value(location, "$.locationId");
        MvcResult shift = create(
                "/api/v1/attendance-setup/shifts",
                "wave3-lifecycle-shift",
                """
                {
                  "companyId":"%s",
                  "locationId":"%s",
                  "code":"LIFECYCLE_SHIFT",
                  "name":"生命周期班次",
                  "reason":"WAVE-3 生命周期班次建档"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andReturn();
        String shiftId = value(shift, "$.shiftId");

        mockMvc.perform(put("/api/v1/attendance-setup/shifts/{shiftId}", shiftId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-shift-template-update-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 班次模板修订")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "locationId":"%s",
                                  "code":"LIFECYCLE_SHIFT",
                                  "name":"生命周期班次修订",
                                  "reason":"WAVE-3 班次模板修订"
                                }
                                """.formatted(COMPANY, locationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1));
        changeShiftTemplateStatus(shiftId, 1, "INACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));
        changeShiftTemplateStatus(shiftId, 2, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(3));

        String shiftVersionId = createShiftVersion(
                shiftId,
                "wave3-lifecycle-version",
                "2026-01-01",
                null);
        MvcResult revisedShiftVersion = updateShiftVersion(
                shiftId, shiftVersionId, 0, "09:00:00", "18:00:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1))
                .andReturn();
        shiftVersionId = value(revisedShiftVersion, "$.shiftVersionId");
        publishShiftVersion(shiftId, shiftVersionId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));
        updateShiftVersion(shiftId, shiftVersionId, 2, "10:00:00", "19:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_VERSION_IMMUTABLE"));
        changeShiftVersionStatus(shiftId, shiftVersionId, 2, "INACTIVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("SHIFT_VERSION_HISTORY_PROTECTED"));

        MvcResult calendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-lifecycle-calendar",
                """
                {
                  "companyId":"%s",
                  "code":"LIFECYCLE_2026",
                  "name":"生命周期日历",
                  "calendarYear":2026,
                  "locationId":"%s",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-08-15",
                  "reason":"WAVE-3 生命周期日历建档"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String calendarId = value(calendar, "$.calendarId");
        mockMvc.perform(put(
                        "/api/v1/attendance-setup/calendars/{calendarId}",
                        calendarId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-calendar-update-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 日历草稿修订")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "code":"LIFECYCLE_2026",
                                  "name":"生命周期日历修订",
                                  "calendarYear":2026,
                                  "locationId":"%s",
                                  "timeZone":"Asia/Shanghai",
                                  "effectiveFrom":"2026-08-14",
                                  "effectiveTo":"2026-08-15",
                                  "reason":"WAVE-3 日历草稿修订"
                                }
                                """.formatted(COMPANY, locationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(1));
        replaceCalendarDays(
                calendarId,
                1,
                "WAVE-3 生命周期日历日",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"WORKDAY",
                    "shiftVersionOverrideId":"%s"
                  }
                ]
                """.formatted(shiftVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowVersion").value(2));
        publishAndActivateCalendar(calendarId, 2);
        replaceCalendarDays(
                calendarId,
                4,
                "WAVE-3 已发布日历不可变负例",
                """
                [
                  {
                    "businessDate":"2026-08-14",
                    "dayType":"PUBLIC_HOLIDAY"
                  }
                ]
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_CALENDAR_IMMUTABLE"));

        String groupId = createGroup(
                "wave3-lifecycle-group",
                "LIFECYCLE_GROUP",
                "生命周期考勤组",
                locationId,
                calendarId,
                shiftId);
        String groupRevisionId = groupRevisionId(groupId);
        String bindingId = jdbc.queryForObject(
                """
                SELECT binding_family_id
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                  AND policy_kind = 'LATE_GRACE'
                """,
                String.class,
                groupId);
        String firstBindingRevisionId = jdbc.queryForObject(
                """
                SELECT binding_revision_id
                FROM attendance_policy_binding_revision
                WHERE binding_family_id = ?
                """,
                String.class,
                bindingId);
        String bindingReplacementReason = "WAVE-3 策略绑定版本化更新";
        MvcResult impactPreview = write(
                post("/api/v1/attendance-setup/policy-impact-preview"),
                """
                {
                  "policyKind":"LATE_GRACE",
                  "policyVersionId":"%s",
                  "groupId":"%s",
                  "groupRevisionId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "reason":"%s"
                }
                """.formatted(
                        LATE_VERSION,
                        groupId,
                        groupRevisionId,
                        bindingReplacementReason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impactToken").isString())
                .andReturn();
        String impactToken = value(impactPreview, "$.impactToken");
        MvcResult replacement = mockMvc.perform(put(
                        "/api/v1/attendance-setup/policy-bindings/{bindingId}",
                        bindingId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "wave3-lifecycle-binding-update")
                        .header("X-Change-Reason", bindingReplacementReason)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyKind":"LATE_GRACE",
                                  "policyVersionId":"%s",
                                  "groupId":"%s",
                                  "groupRevisionId":"%s",
                                  "effectiveFrom":"2026-08-16",
                                  "reason":"%s",
                                  "impactToken":"%s"
                                }
                                """.formatted(
                                        LATE_VERSION,
                                        groupId,
                                        groupRevisionId,
                                        bindingReplacementReason,
                                        impactToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindingId").value(bindingId))
                .andExpect(jsonPath("$.bindingRevisionId").value(
                        org.hamcrest.Matchers.not(firstBindingRevisionId)))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andReturn();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision
                WHERE binding_family_id = ?
                """,
                Long.class,
                bindingId)).isEqualTo(2);
        write(
                post("/api/v1/attendance-setup/calendars/{calendarId}/status",
                        calendarId)
                        .header(HttpHeaders.IF_MATCH, "\"3\""),
                """
                {
                  "status":"INACTIVE",
                  "businessEffectiveFrom":"2026-08-15",
                  "reason":"WAVE-3 工作日历停用"
                }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORK_CALENDAR_VERSION_GAP"));
    }

    @Test
    void fixed_clock_shift_template_status_uses_monotonic_resource_version()
            throws Exception {
        String locationId = value(
                create(
                        "/api/v1/attendance-setup/locations",
                        "wave3-fixed-clock-shift-location",
                        locationBodyFor(
                                "FIXED_CLOCK_SHIFT",
                                "Asia/Shanghai",
                                "WAVE-3 固定时钟班次地点"))
                        .andReturn(),
                "$.locationId");
        String shiftId = value(
                create(
                        "/api/v1/attendance-setup/shifts",
                        "wave3-fixed-clock-shift",
                        """
                        {
                          "companyId":"%s",
                          "locationId":"%s",
                          "code":"FIXED_CLOCK_SHIFT",
                          "name":"固定时钟班次",
                          "reason":"WAVE-3 固定时钟班次建档"
                        }
                        """.formatted(COMPANY, locationId))
                        .andReturn(),
                "$.shiftId");

        changeShiftTemplateStatus(shiftId, 0, "INACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.rowVersion").value(1));
        changeShiftTemplateStatus(shiftId, 1, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rowVersion").value(2));
        changeShiftTemplateStatus(shiftId, 2, "INACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.rowVersion").value(3));
        changeShiftTemplateStatus(shiftId, 3, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rowVersion").value(4));

        assertThat(shiftRepository.findTemplate(shiftId))
                .isPresent()
                .get()
                .satisfies(template -> {
                    assertThat(template.status().name()).isEqualTo("ACTIVE");
                    assertThat(template.rowVersion()).isEqualTo(4);
                });
        assertThat(jdbc.queryForList(
                """
                SELECT policy_version
                FROM audit_event
                WHERE resource_type = 'SHIFT_TEMPLATE'
                  AND resource_id_ref = ?
                  AND action_code IN (
                      'SHIFT_TEMPLATE_ACTIVE',
                      'SHIFT_TEMPLATE_INACTIVE'
                  )
                ORDER BY CAST(policy_version AS DECIMAL(20, 0))
                """,
                String.class,
                shiftId)).containsExactly("1", "2", "3", "4");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT occurred_at)
                FROM audit_event
                WHERE resource_type = 'SHIFT_TEMPLATE'
                  AND resource_id_ref = ?
                  AND action_code IN (
                      'SHIFT_TEMPLATE_ACTIVE',
                      'SHIFT_TEMPLATE_INACTIVE'
                  )
                """,
                Long.class,
                shiftId)).isEqualTo(1L);
    }

    @Test
    void attendance_policy_facade_uses_attendance_capability_and_full_lifecycle()
            throws Exception {
        jdbc.update(
                """
                DELETE FROM auth_role_capability
                WHERE role_id = '11000000-0000-0000-0000-000000000001'
                  AND capability_id IN (
                      SELECT capability_id
                      FROM auth_capability
                      WHERE capability_code LIKE 'POLICY:%'
                  )
                """);

        mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        LATE_TEMPLATE)
                        .queryParam("companyId", COMPANY)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].scopedVersionId").value(LATE_VERSION));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        LATE_TEMPLATE,
                        LATE_VERSION)
                        .queryParam(
                                "companyId",
                                "30000000-0000-0000-0000-000000000002")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        String draftRequest = """
                {
                  "basedOnVersionId":"%s",
                  "effectiveFrom":"2026-09-01",
                  "reason":"WAVE-3 考勤策略草稿"
                }
                """.formatted(LATE_VERSION);
        MvcResult draft = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        LATE_TEMPLATE)
                        .queryParam("companyId", COMPANY)
                        .header("Idempotency-Key", "wave3-policy-draft-create"),
                draftRequest)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String originalDraftId = value(draft, "$.scopedVersionId");
        MvcResult draftReplay = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        LATE_TEMPLATE)
                        .queryParam("companyId", COMPANY)
                        .header("Idempotency-Key", "wave3-policy-draft-create"),
                draftRequest)
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(draftReplay.getResponse().getContentAsString())
                .isEqualTo(draft.getResponse().getContentAsString());

        MvcResult updated = mockMvc.perform(patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        LATE_TEMPLATE,
                        originalDraftId)
                        .queryParam("companyId", COMPANY)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "wave3-policy-draft-update")
                        .header("X-Change-Reason", "WAVE-3 考勤策略草稿修订")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parameters":[
                                    {"key":"enabled","value":true},
                                    {"key":"graceMinutes","value":15}
                                  ],
                                  "effectiveFrom":"2026-09-01",
                                  "reason":"WAVE-3 考勤策略草稿修订"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long updatedVersion = ((Number) JsonPath.read(
                updated.getResponse().getContentAsString(), "$.rowVersion"))
                .longValue();
        MvcResult updatedReplay = mockMvc.perform(patch(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        LATE_TEMPLATE,
                        originalDraftId)
                        .queryParam("companyId", COMPANY)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "wave3-policy-draft-update")
                        .header("X-Change-Reason", "WAVE-3 考勤策略草稿修订")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parameters":[
                                    {"key":"enabled","value":true},
                                    {"key":"graceMinutes","value":15}
                                  ],
                                  "effectiveFrom":"2026-09-01",
                                  "reason":"WAVE-3 考勤策略草稿修订"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(updatedReplay.getResponse().getContentAsString())
                .isEqualTo(updated.getResponse().getContentAsString());
        String draftId = value(updated, "$.scopedVersionId");

        MvcResult validated = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + updatedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-validate"),
                "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andReturn();
        assertThat(validated.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");
        MvcResult validatedReplay = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + updatedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-validate"),
                "{}")
                .andExpect(status().isOk())
                .andReturn();
        assertThat(validatedReplay.getResponse().getContentAsString())
                .isEqualTo(validated.getResponse().getContentAsString());
        long validationSuccessAudits = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_VALIDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                draftId);
        write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + updatedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-validate-stale"),
                "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_VALIDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                draftId)).isEqualTo(validationSuccessAudits);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND result_code = 'FAILURE'
                  AND reason_code = 'VERSION_CONFLICT'
                  AND resource_type = 'ATTENDANCE_SETUP_REQUEST'
                  AND resource_id_ref LIKE 'sha256:%'
                """,
                Long.class)).isEqualTo(1);
        MvcResult validatedVersionResponse = mockMvc.perform(get(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.validation.issues").isEmpty())
                .andExpect(jsonPath("$.validation.validatedAt").isString())
                .andReturn();
        long validatedVersion = ((Number) JsonPath.read(
                validatedVersionResponse.getResponse().getContentAsString(),
                "$.rowVersion")).longValue();
        assertThat(validatedVersion).isNotEqualTo(updatedVersion);

        MvcResult published = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + validatedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-publish"),
                """
                {"reason":"WAVE-3 考勤策略安全发布"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.snapshotDigest").isString())
                .andReturn();
        long publishedVersion = ((Number) JsonPath.read(
                published.getResponse().getContentAsString(), "$.rowVersion"))
                .longValue();
        MvcResult publishedReplay = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/publish",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + validatedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-publish"),
                """
                {"reason":"WAVE-3 考勤策略安全发布"}
                """)
                .andExpect(status().isOk())
                .andReturn();
        assertThat(publishedReplay.getResponse().getContentAsString())
                .isEqualTo(published.getResponse().getContentAsString());

        MvcResult inactive = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + publishedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-deactivate"),
                """
                {
                  "effectiveFrom":"2026-10-01",
                  "reason":"WAVE-3 考勤策略停用"
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.deactivationEffectiveFrom").value("2026-10-01"))
                .andReturn();
        long inactiveVersion = ((Number) JsonPath.read(
                inactive.getResponse().getContentAsString(), "$.rowVersion"))
                .longValue();
        MvcResult inactiveReplay = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + publishedVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-deactivate"),
                """
                {
                  "effectiveFrom":"2026-10-01",
                  "reason":"WAVE-3 考勤策略停用"
                }
                """)
                .andExpect(status().isOk())
                .andReturn();
        assertThat(inactiveReplay.getResponse().getContentAsString())
                .isEqualTo(inactive.getResponse().getContentAsString());

        MvcResult rollback = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + inactiveVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-rollback-create"),
                """
                {
                  "targetVersionId":"%s",
                  "effectiveFrom":"2026-11-01",
                  "reason":"WAVE-3 考勤策略回滚"
                }
                """.formatted(LATE_VERSION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.rollbackOfScopedVersionId").value(LATE_VERSION))
                .andReturn();
        MvcResult rollbackReplay = write(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/rollback",
                        LATE_TEMPLATE,
                        draftId)
                        .queryParam("companyId", COMPANY)
                        .header(HttpHeaders.IF_MATCH, "\"" + inactiveVersion + "\"")
                        .header("Idempotency-Key", "wave3-policy-rollback-create"),
                """
                {
                  "targetVersionId":"%s",
                  "effectiveFrom":"2026-11-01",
                  "reason":"WAVE-3 考勤策略回滚"
                }
                """.formatted(LATE_VERSION))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(rollbackReplay.getResponse().getContentAsString())
                .isEqualTo(rollback.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_setup_idempotency
                WHERE state = 'COMPLETED_SUCCESS'
                  AND resource_type IN (
                      'ATTENDANCE_POLICY_SCOPE',
                      'ATTENDANCE_POLICY_SCOPED_VERSION'
                  )
                """,
                Long.class)).isEqualTo(6L);
        assertThat(jdbc.queryForList(
                """
                SELECT CAST(response_headers_json AS VARCHAR)
                FROM attendance_setup_idempotency
                WHERE state = 'COMPLETED_SUCCESS'
                  AND resource_type IN (
                      'ATTENDANCE_POLICY_SCOPE',
                      'ATTENDANCE_POLICY_SCOPED_VERSION'
                  )
                ORDER BY operation_code, idempotency_key
                """,
                String.class))
                .hasSize(6)
                .allSatisfy(headers -> assertThat(headers)
                        .contains("\"ETag\"", "\"Idempotency-Key\""));

        long successfulDraftAudits = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'POLICY_DRAFT_CREATED'
                """,
                Long.class);
        long deniedAuditsBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_DENIED'
                  AND resource_type = 'ATTENDANCE_SETUP_REQUEST'
                  AND resource_id_ref LIKE 'sha256:%'
                  AND reason_code = 'ACCESS_DENIED'
                """,
                Long.class);
        mockMvc.perform(post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        LATE_TEMPLATE)
                        .queryParam("companyId", COMPANY)
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", "wave3-policy-denied-create")
                        .header("X-Change-Reason", "WAVE-3 只读角色拒绝")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "basedOnVersionId":"%s",
                                  "effectiveFrom":"2026-10-01",
                                  "reason":"WAVE-3 只读角色拒绝"
                                }
                                """.formatted(LATE_VERSION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'POLICY_DRAFT_CREATED'
                """,
                Long.class)).isEqualTo(successfulDraftAudits);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_DENIED'
                  AND resource_type = 'ATTENDANCE_SETUP_REQUEST'
                  AND resource_id_ref LIKE 'sha256:%'
                  AND reason_code = 'ACCESS_DENIED'
                """,
                Long.class)).isEqualTo(deniedAuditsBefore + 1);
    }

    @Test
    void resolver_selects_the_independent_calendar_on_each_side_of_year_boundary()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-year-boundary",
                "2026-12-31",
                null);
        MvcResult oldConfigurationBeforeFutureVersion = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andReturn();

        String nextShiftVersionId = createShiftVersion(
                setup.shiftId(),
                "wave3-shift-version-2027",
                "2027-01-01",
                null);
        publishShiftVersion(setup.shiftId(), nextShiftVersionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2));

        MvcResult nextCalendar = create(
                "/api/v1/attendance-setup/calendars/%s/versions"
                        .formatted(setup.calendarId()),
                "wave3-calendar-version-2027",
                """
                {
                  "name":"中国区 2027 工作日历",
                  "calendarYear":2027,
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2027-01-01",
                  "effectiveTo":"2028-01-01",
                  "reason":"WAVE-3 次年工作日历建档"
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calendarId").value(setup.calendarId()))
                .andExpect(jsonPath("$.versionNumber").value(3))
                .andReturn();
        String nextCalendarVersionId =
                value(nextCalendar, "$.calendarVersionId");
        MvcResult nextCalendarDays = upsertCalendarVersionDays(
                setup.calendarId(),
                nextCalendarVersionId,
                0,
                "WAVE-3 年初日历设置",
                calendarDaysJson(
                        java.time.LocalDate.parse("2027-01-01"),
                        java.time.LocalDate.parse("2028-01-01")))
                .andExpect(status().isOk())
                .andReturn();
        nextCalendarVersionId =
                value(nextCalendarDays, "$.calendarVersionId");
        publishCalendarVersion(
                setup.calendarId(), nextCalendarVersionId, 1)
                .andExpect(status().isOk());

        MvcResult oldConfigurationAfterFutureVersion = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.calendarDay.calendarId")
                        .value(setup.calendarId()))
                .andReturn();
        assertThat(value(oldConfigurationAfterFutureVersion, "$.configurationDigest"))
                .isEqualTo(value(
                        oldConfigurationBeforeFutureVersion,
                        "$.configurationDigest"));
        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2027-01-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.calendarVersionId")
                        .value(nextCalendarVersionId))
                .andExpect(jsonPath("$.shiftVersion.shiftVersionId")
                        .value(nextShiftVersionId));
    }

    @Test
    void two_groups_resolve_distinct_immutable_shift_and_calendar_versions_on_same_day()
            throws Exception {
        Setup setup = createBaseSetup();
        String alternateShiftId = createShiftTemplate(
                setup.locationId(),
                "DAY_B",
                "日班 B",
                "wave3-distinct-shift-family");
        MvcResult alternateShiftVersion = create(
                "/api/v1/attendance-setup/shifts/%s/versions"
                        .formatted(alternateShiftId),
                "wave3-distinct-shift-version",
                """
                {
                  "effectiveFrom":"2026-08-14",
                  "segments":[{
                    "segmentType":"WORK",
                    "startLocalTime":"06:30:00",
                    "startDayOffset":0,
                    "endLocalTime":"15:30:00",
                    "endDayOffset":0
                  }],
                  "reason":"WAVE-3 不同组独立班次版本"
                }
                """)
                .andExpect(status().isCreated())
                .andReturn();
        String alternateShiftVersionId =
                value(alternateShiftVersion, "$.shiftVersionId");
        publishShiftVersion(alternateShiftId, alternateShiftVersionId)
                .andExpect(status().isOk());

        MvcResult alternateCalendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-distinct-calendar-family",
                """
                {
                  "companyId":"%s",
                  "locationId":"%s",
                  "code":"ALT_2026",
                  "name":"替代工作日历",
                  "calendarYear":2026,
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-08-15",
                  "reason":"WAVE-3 不同组独立日历版本"
                }
                """.formatted(COMPANY, setup.locationId()))
                .andExpect(status().isCreated())
                .andReturn();
        String alternateCalendarId =
                value(alternateCalendar, "$.calendarId");
        String alternateCalendarVersionId =
                value(alternateCalendar, "$.calendarVersionId");
        MvcResult alternateCalendarDays = upsertCalendarVersionDays(
                alternateCalendarId,
                alternateCalendarVersionId,
                0,
                "WAVE-3 不同组独立日历日",
                """
                [{
                  "businessDate":"2026-08-14",
                  "dayType":"SPECIAL_WORKDAY"
                }]
                """)
                .andExpect(status().isOk())
                .andReturn();
        alternateCalendarVersionId =
                value(alternateCalendarDays, "$.calendarVersionId");
        publishCalendarVersion(
                alternateCalendarId, alternateCalendarVersionId, 1)
                .andExpect(status().isOk());

        String alternateGroupId = createGroup(
                "wave3-distinct-group",
                "GROUP_DISTINCT",
                "独立配置组",
                setup.locationId(),
                alternateCalendarId,
                alternateShiftId);
        createAssignment(
                setup.firstGroupId(),
                EMPLOYEE,
                "wave3-distinct-assignment-a",
                "2026-08-14",
                "2026-08-15");
        createAssignment(
                alternateGroupId,
                SECOND_EMPLOYEE,
                "wave3-distinct-assignment-b",
                "2026-08-14",
                "2026-08-15");

        MvcResult first = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(setup.firstGroupId()))
                .andExpect(jsonPath("$.shiftVersion.shiftVersionId")
                        .value(setup.shiftVersionId()))
                .andReturn();
        MvcResult second = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", SECOND_EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(alternateGroupId))
                .andExpect(jsonPath("$.calendarVersionId")
                        .value(alternateCalendarVersionId))
                .andExpect(jsonPath("$.shiftVersion.shiftVersionId")
                        .value(alternateShiftVersionId))
                .andReturn();

        assertThat(value(first, "$.configurationDigest"))
                .isNotEqualTo(value(second, "$.configurationDigest"));
        assertThat(value(first, "$.groupRevisionId")).isNotBlank();
        assertThat(value(second, "$.groupRevisionId")).isNotBlank();
        assertThat(value(first, "$.locationRevisionId"))
                .isEqualTo(value(second, "$.locationRevisionId"));
    }

    @Test
    void assignment_rejects_inactive_group_and_cross_company_employee()
            throws Exception {
        Setup setup = createBaseSetup();
        write(
                post("/api/v1/attendance-setup/groups/{groupId}/deactivate",
                        setup.secondGroupId())
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                """
                {"reason":"WAVE-3 停用组禁止新增分配"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        create(
                "/api/v1/attendance-setup/groups/%s/assignments"
                        .formatted(setup.secondGroupId()),
                "wave3-assignment-inactive-group",
                assignmentBody(
                        SECOND_EMPLOYEE, "2026-08-15", "2026-08-16"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ATTENDANCE_GROUP_INACTIVE"));

        create(
                "/api/v1/attendance-setup/groups/%s/assignments"
                        .formatted(setup.firstGroupId()),
                "wave3-assignment-cross-company-write",
                assignmentBody(
                        OUTSIDE_COMPANY_EMPLOYEE,
                        "2026-08-14",
                        "2026-08-15"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
    }

    @Test
    void assignment_rejects_a_missing_intermediate_configuration_day()
            throws Exception {
        Setup setup = createBaseSetup();
        String calendarVersionId = jdbc.queryForObject(
                """
                SELECT work_calendar_version_id
                FROM work_calendar_version
                WHERE work_calendar_id = ?
                  AND effective_from = DATE '2026-08-14'
                ORDER BY version_number DESC
                LIMIT 1
                """,
                String.class,
                setup.calendarId());
        jdbc.update(
                """
                DELETE FROM work_calendar_day
                WHERE work_calendar_version_id = ?
                  AND business_date = DATE '2026-08-15'
                """,
                calendarVersionId);
        long assignmentsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class);

        create(
                "/api/v1/attendance-setup/groups/%s/assignments"
                        .formatted(setup.firstGroupId()),
                "wave3-assignment-middle-configuration-gap",
                assignmentBody("2026-08-14", "2026-08-17"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ASSIGNMENT_CALENDAR_DAY_MISSING"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class)).isEqualTo(assignmentsBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_setup_idempotency
                WHERE idempotency_key =
                    'wave3-assignment-middle-configuration-gap'
                  AND state = 'COMPLETED_SUCCESS'
                """,
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND result_code = 'FAILURE'
                  AND reason_code = 'ASSIGNMENT_CALENDAR_DAY_MISSING'
                  AND resource_type = 'ATTENDANCE_SETUP_REQUEST'
                """,
                Long.class)).isEqualTo(1L);
    }

    @Test
    void group_period_contraction_checks_assignments_and_default_policy_bindings()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-contraction-open-assignment",
                "2026-08-14",
                null);

        updateGroupPeriod(
                setup.firstGroupId(),
                setup,
                "2026-09-01",
                "2026-12-01",
                "WAVE-3 收缩组期间先检查分配")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("GROUP_PERIOD_ASSIGNMENT_CONFLICT"));

        updateGroupPeriod(
                setup.secondGroupId(),
                setup,
                "2026-09-01",
                "2026-12-01",
                "WAVE-3 收缩组期间检查默认策略")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("GROUP_PERIOD_POLICY_BINDING_CONFLICT"));
    }

    @Test
    void group_rollover_atomically_appends_assignment_and_binding_successors()
            throws Exception {
        Setup setup = createBaseSetup();
        String predecessorAssignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-group-rollover-assignment",
                "2026-08-14",
                null);
        String predecessorGroupRevisionId =
                groupRevisionId(setup.firstGroupId());
        List<String> familyIds = jdbc.queryForList(
                """
                SELECT binding_family_id
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                ORDER BY policy_kind
                """,
                String.class,
                setup.firstGroupId());

        rolloverGroup(
                setup.firstGroupId(),
                setup,
                "2026-09-01",
                "WAVE-3 考勤组联动换版")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.revisionNumber").value(2));

        String successorGroupRevisionId =
                groupRevisionId(setup.firstGroupId());
        assertThat(successorGroupRevisionId)
                .isNotEqualTo(predecessorGroupRevisionId);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_group_assignment
                WHERE supersedes_assignment_id = ?
                  AND attendance_group_revision_id = ?
                  AND effective_from = DATE '2026-09-01'
                """,
                Long.class,
                predecessorAssignmentId,
                successorGroupRevisionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                  AND state = 'INACTIVE'
                  AND business_effective_from = DATE '2026-09-01'
                """,
                Long.class,
                predecessorAssignmentId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                """,
                Long.class,
                setup.firstGroupId())).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision revision
                JOIN attendance_policy_binding_family family
                  ON family.binding_family_id = revision.binding_family_id
                WHERE family.attendance_group_id = ?
                  AND revision.attendance_group_revision_id = ?
                  AND revision.supersedes_binding_revision_id IS NOT NULL
                """,
                Long.class,
                setup.firstGroupId(),
                successorGroupRevisionId)).isEqualTo(3L);
        assertThat(jdbc.queryForList(
                """
                SELECT binding_family_id
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                ORDER BY policy_kind
                """,
                String.class,
                setup.firstGroupId())).containsExactlyElementsOf(familyIds);

        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupRevisionId")
                        .value(predecessorGroupRevisionId));
        read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-09-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupRevisionId")
                        .value(successorGroupRevisionId))
                .andExpect(jsonPath("$.policyBindings.length()").value(3));
    }

    @Test
    void location_rollover_coordinates_the_complete_referencing_group_set()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                EMPLOYEE,
                "wave3-location-rollover-first-assignment",
                "2026-08-14",
                null);
        createAssignment(
                setup.secondGroupId(),
                SECOND_EMPLOYEE,
                "wave3-location-rollover-second-assignment",
                "2026-08-14",
                null);
        String predecessorLocationRevisionId = jdbc.queryForObject(
                """
                SELECT location_revision_id
                FROM location_revision
                WHERE location_id = ?
                ORDER BY revision_number DESC
                LIMIT 1
                """,
                String.class,
                setup.locationId());

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        setup.locationId())
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-location-coordinated-rollover")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 地点完整集合联动换版")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "code":"SHENZHOU_SZ",
                                  "name":"神州半导体苏州厂区二期",
                                  "timeZone":"Asia/Shanghai",
                                  "effectiveFrom":"2026-09-01",
                                  "reason":"WAVE-3 地点完整集合联动换版"
                                }
                                """.formatted(COMPANY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));

        String successorLocationRevisionId = jdbc.queryForObject(
                """
                SELECT location_revision_id
                FROM location_revision
                WHERE location_id = ?
                ORDER BY revision_number DESC
                LIMIT 1
                """,
                String.class,
                setup.locationId());
        assertThat(successorLocationRevisionId)
                .isNotEqualTo(predecessorLocationRevisionId);
        assertThat(jdbc.queryForList(
                """
                SELECT attendance_group_id
                FROM attendance_group_revision
                WHERE location_revision_id = ?
                  AND revision_number = 2
                ORDER BY attendance_group_id
                """,
                String.class,
                successorLocationRevisionId))
                .containsExactlyInAnyOrder(
                        setup.firstGroupId(), setup.secondGroupId());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_group_assignment
                WHERE supersedes_assignment_id IS NOT NULL
                  AND attendance_group_revision_id IN (
                      SELECT attendance_group_revision_id
                      FROM attendance_group_revision
                      WHERE location_revision_id = ?
                  )
                """,
                Long.class,
                successorLocationRevisionId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision binding_revision
                JOIN attendance_group_revision group_revision
                  ON group_revision.attendance_group_revision_id =
                        binding_revision.attendance_group_revision_id
                WHERE group_revision.location_revision_id = ?
                  AND binding_revision.supersedes_binding_revision_id IS NOT NULL
                """,
                Long.class,
                successorLocationRevisionId)).isEqualTo(6L);
    }

    @Test
    void group_create_policy_cardinality_failures_roll_back_every_success_state()
            throws Exception {
        Setup setup = createBaseSetup();
        long groupsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group", Long.class);
        long revisionsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_revision", Long.class);
        long timelinesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_timeline", Long.class);
        long bindingsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_policy_binding_revision",
                Long.class);
        long successAuditsBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_CREATED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class);

        jdbc.update(
                """
                DELETE FROM attendance_policy_lifecycle_event
                WHERE scoped_version_id = ?
                """,
                MONTHLY_VERSION);
        create(
                "/api/v1/attendance-setup/groups",
                "wave3-group-policy-missing-rollback",
                groupBody(
                        "POLICY_MISSING_GROUP",
                        "策略缺失回滚组",
                        setup))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_MISSING"));
        assertGroupCreationFullyRolledBack(
                groupsBefore,
                revisionsBefore,
                timelinesBefore,
                bindingsBefore,
                successAuditsBefore,
                "wave3-group-policy-missing-rollback");

        Timestamp now = Timestamp.from(FIXTURE_RECORDED_AT);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id, request_id, recorded_at
                ) VALUES (?, ?, ?, 1, NULL, 'PUBLISHED', DATE '1970-01-01',
                    'WAVE-3 恢复受控策略发布', ?, ?, ?)
                """,
                lifecycleId(MONTHLY_TEMPLATE),
                scopeId(MONTHLY_TEMPLATE),
                MONTHLY_VERSION,
                ADMIN_PRINCIPAL,
                "fixture-restored-monthly",
                now);
        String ambiguousVersion =
                "87000000-0000-0000-0000-000000000099";
        jdbc.update(
                """
                INSERT INTO attendance_policy_scoped_version (
                    scoped_version_id, scope_id, version_number,
                    parameters_json, effective_from, effective_to,
                    change_reason, validation_json, snapshot_json,
                    snapshot_digest, rollback_of_scoped_version_id, row_version,
                    created_by, created_at
                ) VALUES (?, ?, 2,
                    '{"enabled":true,"graceMinutes":15}',
                    DATE '1970-01-01', NULL,
                    'WAVE-3 歧义策略负例', '{"valid":true,"issues":[]}',
                    '{}', ?, NULL, 1, ?, ?)
                """,
                ambiguousVersion,
                scopeId(LATE_TEMPLATE),
                DIGEST,
                ADMIN_PRINCIPAL,
                now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id, request_id, recorded_at
                ) VALUES (
                    RANDOM_UUID(), ?, ?, 2, ?, 'PUBLISHED',
                    DATE '1970-01-01', 'WAVE-3 歧义策略负例',
                    ?, 'fixture-ambiguous-late', ?
                )
                """,
                scopeId(LATE_TEMPLATE),
                ambiguousVersion,
                lifecycleId(LATE_TEMPLATE),
                ADMIN_PRINCIPAL,
                now);
        create(
                "/api/v1/attendance-setup/groups",
                "wave3-group-policy-ambiguous-rollback",
                groupBody(
                        "POLICY_AMBIGUOUS_GROUP",
                        "策略歧义回滚组",
                        setup))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_AMBIGUOUS"));
        assertGroupCreationFullyRolledBack(
                groupsBefore,
                revisionsBefore,
                timelinesBefore,
                bindingsBefore,
                successAuditsBefore,
                "wave3-group-policy-ambiguous-rollback");
    }

    @Test
    void binding_successor_rejects_source_lifecycle_end_before_cutover()
            throws Exception {
        Setup setup = createBaseSetup();
        String groupRevisionId = groupRevisionId(setup.firstGroupId());
        String bindingId = jdbc.queryForObject(
                """
                SELECT binding_family_id
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                  AND policy_kind = 'LATE_GRACE'
                """,
                String.class,
                setup.firstGroupId());
        String reason = "WAVE-3 策略绑定源版本期间负例";
        MvcResult preview = write(
                post("/api/v1/attendance-setup/policy-impact-preview"),
                """
                {
                  "policyKind":"LATE_GRACE",
                  "policyVersionId":"%s",
                  "groupId":"%s",
                  "groupRevisionId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "reason":"%s"
                }
                """.formatted(
                        LATE_VERSION,
                        setup.firstGroupId(),
                        groupRevisionId,
                        reason))
                .andExpect(status().isOk())
                .andReturn();
        String impactToken = value(preview, "$.impactToken");
        deactivatePolicyAt(LATE_VERSION, "2026-08-15");

        long revisionsBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision
                WHERE binding_family_id = ?
                """,
                Long.class,
                bindingId);
        long successAuditsBefore = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_BINDING_REPLACED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class);
        String idempotencyKey =
                "wave3-binding-source-period-out-of-range";

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/policy-bindings/{bindingId}",
                        bindingId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Change-Reason", reason)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyKind":"LATE_GRACE",
                                  "policyVersionId":"%s",
                                  "groupId":"%s",
                                  "groupRevisionId":"%s",
                                  "effectiveFrom":"2026-08-16",
                                  "reason":"%s",
                                  "impactToken":"%s"
                                }
                                """.formatted(
                                        LATE_VERSION,
                                        setup.firstGroupId(),
                                        groupRevisionId,
                                        reason,
                                        impactToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ATTENDANCE_POLICY_BINDING_SOURCE_PERIOD_INVALID"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision
                WHERE binding_family_id = ?
                """,
                Long.class,
                bindingId)).isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_BINDING_REPLACED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class)).isEqualTo(successAuditsBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_setup_idempotency
                WHERE idempotency_key = ?
                  AND state = 'COMPLETED_SUCCESS'
                """,
                Long.class,
                idempotencyKey)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_PUT_FAILURE'
                  AND result_code = 'FAILURE'
                  AND reason_code =
                      'ATTENDANCE_POLICY_BINDING_SOURCE_PERIOD_INVALID'
                  AND resource_type = 'ATTENDANCE_SETUP_REQUEST'
                """,
                Long.class)).isEqualTo(1L);
    }

    @Test
    void referenced_location_timezone_change_is_rejected_before_group_rollover()
            throws Exception {
        Setup setup = createBaseSetup();
        createAssignment(
                setup.firstGroupId(),
                "wave3-location-history-assignment",
                "2026-08-14",
                "2026-08-15");
        MvcResult before = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        setup.locationId())
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-location-time-zone-update-"
                                        + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                        .header("X-Change-Reason", "WAVE-3 地点时区未来换版")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "code":"SHENZHOU_SZ",
                                  "name":"神州半导体苏州厂区",
                                  "timeZone":"UTC",
                                  "effectiveFrom":"2027-01-01",
                                  "reason":"WAVE-3 地点时区未来换版"
                                }
                                """.formatted(COMPANY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("SHARED_LOCATION_TIME_ZONE_IN_USE"));

        MvcResult after = read(
                "/api/v1/attendance-setup/resolve",
                "employeeId", EMPLOYEE,
                "businessDate", "2026-08-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationRevisionId")
                        .value(value(before, "$.locationRevisionId")))
                .andReturn();
        assertThat(value(after, "$.configurationDigest"))
                .isEqualTo(value(before, "$.configurationDigest"));
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM location_revision
                WHERE location_id = ?
                """,
                Long.class,
                setup.locationId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM attendance_group_revision
                WHERE attendance_group_id IN (?, ?)
                """,
                Long.class,
                setup.firstGroupId(),
                setup.secondGroupId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM shared_location_revision
                WHERE shared_location_id = ?
                """,
                Long.class,
                setup.locationId())).isEqualTo(1L);
    }

    @Test
    void finite_location_and_group_revisions_append_end_facts_and_reload_half_open()
            throws Exception {
        Setup setup = createBaseSetup();

        MvcResult finiteLocationResult = create(
                "/api/v1/attendance-setup/locations",
                "wave3-finite-location",
                """
                {
                  "companyId":"%s",
                  "code":"FINITE_LOCATION",
                  "name":"有限期间地点",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":"2026-09-01",
                  "reason":"WAVE-3 地点半开期间"
                }
                """.formatted(COMPANY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").value("2026-09-01"))
                .andReturn();
        String finiteLocationId = value(
                finiteLocationResult, "$.locationId");
        Location finiteLocation = attendanceGroupRepository
                .findLocation(finiteLocationId)
                .orElseThrow();
        assertThat(finiteLocation.status()).isEqualTo(LifecycleStatus.ACTIVE);
        assertThat(finiteLocation.effectiveTo())
                .isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(finiteLocation.rowVersion()).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM location_timeline
                WHERE location_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                finiteLocationId))
                .containsExactly("ACTIVE:2026-08-01", "INACTIVE:2026-09-01");

        MvcResult finiteGroupResult = create(
                "/api/v1/attendance-setup/groups",
                "wave3-finite-group",
                """
                {
                  "companyId":"%s",
                  "code":"FINITE_GROUP",
                  "name":"有限期间考勤组",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2026-09-01",
                  "reason":"WAVE-3 考勤组半开期间"
                }
                """.formatted(
                        COMPANY,
                        setup.locationId(),
                        setup.calendarId(),
                        setup.shiftId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveTo").value("2026-09-01"))
                .andReturn();
        String finiteGroupId = value(finiteGroupResult, "$.groupId");
        AttendanceGroup finiteGroup = attendanceGroupRepository
                .findGroup(finiteGroupId)
                .orElseThrow();
        assertThat(finiteGroup.status()).isEqualTo(LifecycleStatus.ACTIVE);
        assertThat(finiteGroup.effectiveTo())
                .isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(finiteGroup.rowVersion()).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM attendance_group_timeline
                WHERE attendance_group_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                finiteGroupId))
                .containsExactly("ACTIVE:2026-08-14", "INACTIVE:2026-09-01");
        assertThat(jdbc.queryForObject(
                """
                SELECT revision.snapshot_digest
                FROM attendance_policy_binding_revision revision
                JOIN attendance_policy_binding_family family
                  ON family.binding_family_id = revision.binding_family_id
                WHERE family.attendance_group_id = ?
                  AND family.policy_kind = 'MEAL_DEDUCTION'
                """,
                String.class,
                finiteGroupId))
                .isEqualTo(sha256(String.join(
                        "|",
                        finiteGroup.groupRevisionId(),
                        MEAL_VERSION,
                        DIGEST,
                        "2026-08-14",
                        "2026-09-01")));

        Instant firstKnowledge = testClock.instant();
        String finiteMealBindingId = jdbc.queryForObject(
                """
                SELECT family.binding_family_id
                FROM attendance_policy_binding_family family
                WHERE family.attendance_group_id = ?
                  AND family.policy_kind = 'MEAL_DEDUCTION'
                """,
                String.class,
                finiteGroupId);
        assertThat(attendancePolicyRepository.findBinding(finiteMealBindingId))
                .get()
                .satisfies(binding -> {
                    assertThat(binding.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                    assertThat(binding.changeReason())
                            .isEqualTo("考勤组创建时配置受控默认策略");
                });
        assertThat(attendancePolicyRepository.resolveBindings(
                        finiteGroupId,
                        finiteGroup.groupRevisionId(),
                        LocalDate.parse("2026-08-31"),
                        firstKnowledge))
                .hasSize(3)
                .allSatisfy(binding ->
                        assertThat(binding.effectiveTo())
                                .isEqualTo(LocalDate.parse("2026-09-01")));
        assertThat(attendancePolicyRepository.resolveBindings(
                        finiteGroupId,
                        finiteGroup.groupRevisionId(),
                        LocalDate.parse("2026-09-01"),
                        firstKnowledge))
                .isEmpty();
        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        finiteLocationId,
                        LocalDate.parse("2026-08-31"),
                        firstKnowledge))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.status())
                            .isEqualTo(LifecycleStatus.ACTIVE);
                    assertThat(location.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                });
        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        finiteLocationId,
                        LocalDate.parse("2026-09-01"),
                        firstKnowledge))
                .singleElement()
                .satisfies(location ->
                        assertThat(location.status())
                                .isEqualTo(LifecycleStatus.INACTIVE));
        assertThat(attendanceGroupRepository.resolveGroupRevisions(
                        finiteGroupId,
                        LocalDate.parse("2026-08-31"),
                        firstKnowledge))
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.status()).isEqualTo(LifecycleStatus.ACTIVE);
                    assertThat(group.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                });
        assertThat(attendanceGroupRepository.resolveGroupRevisions(
                        finiteGroupId,
                        LocalDate.parse("2026-09-01"),
                        firstKnowledge))
                .singleElement()
                .satisfies(group ->
                        assertThat(group.status())
                                .isEqualTo(LifecycleStatus.INACTIVE));

        testClock.setInstant(Instant.parse("2026-07-28T00:00:00Z"));
        Instant successorRecordedAt = testClock.instant();
        Location locationSuccessor = new Location(
                finiteLocation.locationId(),
                finiteLocation.sharedLocationId(),
                finiteLocation.companyId(),
                finiteLocation.code(),
                java.util.UUID.randomUUID().toString(),
                2,
                "有限期间地点换版",
                finiteLocation.timeZone(),
                LifecycleStatus.ACTIVE,
                LocalDate.parse("2026-08-20"),
                LocalDate.parse("2026-08-25"),
                sha256(finiteLocation.snapshotDigest() + "|successor"),
                1,
                "WAVE-3 地点 successor 结束边界",
                finiteLocation.createdBy(),
                finiteLocation.createdAt(),
                ADMIN_PRINCIPAL,
                successorRecordedAt);
        attendanceGroupRepository.lockLocation(finiteLocationId);
        assertThat(attendanceGroupRepository.updateLocation(
                locationSuccessor, 0)).isTrue();

        AttendanceGroup groupSuccessor = new AttendanceGroup(
                finiteGroup.groupId(),
                finiteGroup.companyId(),
                finiteGroup.code(),
                java.util.UUID.randomUUID().toString(),
                2,
                "有限期间考勤组换版",
                finiteGroup.locationId(),
                finiteGroup.locationRevisionId(),
                finiteGroup.calendarId(),
                finiteGroup.shiftTemplateId(),
                LifecycleStatus.ACTIVE,
                LocalDate.parse("2026-08-20"),
                LocalDate.parse("2026-08-25"),
                sha256(finiteGroup.snapshotDigest() + "|successor"),
                1,
                "WAVE-3 考勤组 successor 结束边界",
                finiteGroup.createdBy(),
                finiteGroup.createdAt(),
                ADMIN_PRINCIPAL,
                successorRecordedAt);
        attendanceGroupRepository.lockGroup(finiteGroupId);
        assertThat(attendanceGroupRepository.updateGroup(
                groupSuccessor, 0)).isTrue();

        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM location_timeline
                WHERE location_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                finiteLocationId))
                .containsExactly(
                        "ACTIVE:2026-08-01",
                        "INACTIVE:2026-09-01",
                        "ACTIVE:2026-08-20",
                        "INACTIVE:2026-08-25");
        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM attendance_group_timeline
                WHERE attendance_group_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                finiteGroupId))
                .containsExactly(
                        "ACTIVE:2026-08-14",
                        "INACTIVE:2026-09-01",
                        "ACTIVE:2026-08-20",
                        "INACTIVE:2026-08-25");

        assertThat(attendanceGroupRepository.findLocation(finiteLocationId))
                .get()
                .satisfies(location -> {
                    assertThat(location.locationRevisionId())
                            .isEqualTo(locationSuccessor.locationRevisionId());
                    assertThat(location.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-08-25"));
                    assertThat(location.rowVersion()).isEqualTo(1);
                });
        assertThat(attendanceGroupRepository.findGroup(finiteGroupId))
                .get()
                .satisfies(group -> {
                    assertThat(group.groupRevisionId())
                            .isEqualTo(groupSuccessor.groupRevisionId());
                    assertThat(group.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-08-25"));
                    assertThat(group.rowVersion()).isEqualTo(1);
                });

        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        finiteLocationId,
                        LocalDate.parse("2026-08-21"),
                        Instant.parse("2026-07-27T12:00:00Z")))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.locationRevisionId())
                            .isEqualTo(finiteLocation.locationRevisionId());
                    assertThat(location.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                    assertThat(location.rowVersion()).isZero();
                });
        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        finiteLocationId,
                        LocalDate.parse("2026-08-21"),
                        Instant.parse("2026-07-29T00:00:00Z")))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.locationRevisionId())
                            .isEqualTo(locationSuccessor.locationRevisionId());
                    assertThat(location.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-08-25"));
                    assertThat(location.rowVersion()).isEqualTo(1);
                });
        assertThat(attendanceGroupRepository.resolveGroupRevisions(
                        finiteGroupId,
                        LocalDate.parse("2026-08-21"),
                        Instant.parse("2026-07-27T12:00:00Z")))
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.groupRevisionId())
                            .isEqualTo(finiteGroup.groupRevisionId());
                    assertThat(group.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                    assertThat(group.rowVersion()).isZero();
                });
        assertThat(attendanceGroupRepository.resolveGroupRevisions(
                        finiteGroupId,
                        LocalDate.parse("2026-08-21"),
                        Instant.parse("2026-07-29T00:00:00Z")))
                .singleElement()
                .satisfies(group -> {
                    assertThat(group.groupRevisionId())
                            .isEqualTo(groupSuccessor.groupRevisionId());
                    assertThat(group.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-08-25"));
                    assertThat(group.rowVersion()).isEqualTo(1);
                });
    }

    @Test
    void assignment_successors_reject_non_forward_boundaries_and_preserve_knowledge_time()
            throws Exception {
        Setup setup = createBaseSetup();
        String assignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-boundary",
                "2026-08-20",
                null);
        assertThat(attendanceGroupRepository.findAssignment(assignmentId))
                .get()
                .satisfies(assignment ->
                        assertThat(assignment.rowVersion()).isZero());
        long assignmentsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class);
        long timelinesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline",
                Long.class);

        for (String invalidBoundary : List.of("2026-08-19", "2026-08-20")) {
            mockMvc.perform(put(
                            "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}",
                            setup.firstGroupId(),
                            assignmentId)
                            .with(user(ADMIN_PRINCIPAL))
                            .with(csrf())
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .header(
                                    "Idempotency-Key",
                                    "wave3-assignment-invalid-boundary-"
                                            + invalidBoundary)
                            .header(
                                    "X-Change-Reason",
                                    "WAVE-3 人员考勤组分配")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(assignmentBody(
                                    invalidBoundary, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("ATTENDANCE_ASSIGNMENT_BOUNDARY_CONFLICT"));
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class)).isEqualTo(assignmentsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline",
                Long.class)).isEqualTo(timelinesBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_ASSIGNMENT_UPDATED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class)).isZero();

        testClock.setInstant(Instant.parse("2026-07-28T00:00:00Z"));
        MvcResult updated = mockMvc.perform(put(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}",
                        setup.firstGroupId(),
                        assignmentId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-assignment-forward-boundary")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 人员考勤组分配")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(
                                "2026-09-01", "2026-10-01")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        String successorId = value(updated, "$.assignmentId");
        assertThat(successorId).isNotEqualTo(assignmentId);

        assertThat(attendanceGroupRepository.findAssignment(assignmentId))
                .get()
                .satisfies(predecessor -> {
                    assertThat(predecessor.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                    assertThat(predecessor.rowVersion()).isEqualTo(1);
                });
        assertThat(attendanceGroupRepository.findAssignment(successorId))
                .get()
                .satisfies(successor -> {
                    assertThat(successor.effectiveFrom())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                    assertThat(successor.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-10-01"));
                    assertThat(successor.rowVersion()).isZero();
                });
        String predecessorMutationRequestId = jdbc.queryForObject(
                """
                SELECT request_id
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                  AND state = 'INACTIVE'
                  AND business_effective_from = DATE '2026-09-01'
                """,
                String.class,
                assignmentId);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT request_id)
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                """,
                Long.class,
                successorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT MIN(request_id)
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                """,
                String.class,
                successorId)).isEqualTo(predecessorMutationRequestId);

        assertThat(attendanceGroupRepository.resolveAssignments(
                        EMPLOYEE,
                        LocalDate.parse("2026-09-15"),
                        Instant.parse("2026-07-27T12:00:00Z")))
                .singleElement()
                .satisfies(predecessor -> {
                    assertThat(predecessor.assignmentId())
                            .isEqualTo(assignmentId);
                    assertThat(predecessor.effectiveTo()).isNull();
                    assertThat(predecessor.rowVersion()).isZero();
                    assertThat(predecessor.updatedAt())
                            .isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
                });
        assertThat(attendanceGroupRepository.resolveAssignments(
                        EMPLOYEE,
                        LocalDate.parse("2026-09-15"),
                        Instant.parse("2026-07-29T00:00:00Z")))
                .singleElement()
                .satisfies(successor -> {
                    assertThat(successor.assignmentId())
                            .isEqualTo(successorId);
                    assertThat(successor.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-10-01"));
                    assertThat(successor.rowVersion()).isZero();
                    assertThat(successor.updatedAt())
                            .isEqualTo(Instant.parse("2026-07-28T00:00:00Z"));
                });

        long assignmentsAfterUpdate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class);
        long timelinesAfterUpdate = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline",
                Long.class);
        mockMvc.perform(put(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}",
                        setup.firstGroupId(),
                        assignmentId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-assignment-stale-predecessor")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 人员考勤组分配")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentBody(
                                "2026-09-15", "2026-10-15")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment",
                Long.class)).isEqualTo(assignmentsAfterUpdate);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline",
                Long.class)).isEqualTo(timelinesAfterUpdate);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_ASSIGNMENT_UPDATED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class)).isEqualTo(1);
    }

    @Test
    void assignment_transfer_appends_cross_group_successor_and_preserves_history()
            throws Exception {
        Setup setup = createBaseSetup();
        String assignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-transfer-source",
                "2026-08-14",
                null);
        String requestBody = """
                {
                  "targetGroupId":"%s",
                  "effectiveFrom":"2026-08-20",
                  "reason":"WAVE-3 人员跨考勤组调配"
                }
                """.formatted(setup.secondGroupId());

        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments",
                        setup.firstGroupId())
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assignmentId")
                        .value(assignmentId))
                .andExpect(jsonPath("$.items[0].hasSuccessor").value(false))
                .andExpect(jsonPath("$.items[0].transferable").value(true));

        MvcResult transferred = mockMvc.perform(post(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}/transfer",
                        setup.firstGroupId(),
                        assignmentId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-assignment-cross-group-transfer")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 人员跨考勤组调配")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.groupId").value(setup.secondGroupId()))
                .andExpect(jsonPath("$.employeeId").value(EMPLOYEE))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-08-20"))
                .andExpect(jsonPath("$.effectiveTo").doesNotExist())
                .andReturn();
        String successorId = value(transferred, "$.assignmentId");
        assertThat(successorId).isNotEqualTo(assignmentId);

        assertThat(attendanceGroupRepository.findAssignment(assignmentId))
                .get()
                .satisfies(predecessor -> {
                    assertThat(predecessor.groupId())
                            .isEqualTo(setup.firstGroupId());
                    assertThat(predecessor.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-08-20"));
                    assertThat(predecessor.rowVersion()).isEqualTo(1);
                });
        assertThat(attendanceGroupRepository.findAssignment(successorId))
                .get()
                .satisfies(successor -> {
                    assertThat(successor.groupId())
                            .isEqualTo(setup.secondGroupId());
                    assertThat(successor.effectiveFrom())
                            .isEqualTo(LocalDate.parse("2026-08-20"));
                    assertThat(successor.effectiveTo()).isNull();
                    assertThat(successor.rowVersion()).isZero();
                });
        assertThat(jdbc.queryForObject(
                """
                SELECT supersedes_assignment_id
                FROM attendance_group_assignment
                WHERE attendance_group_assignment_id = ?
                """,
                String.class,
                successorId)).isEqualTo(assignmentId);
        assertThat(attendanceGroupRepository.resolveAssignments(
                        EMPLOYEE,
                        LocalDate.parse("2026-08-19"),
                        testClock.instant()))
                .singleElement()
                .extracting(Assignment::assignmentId)
                .isEqualTo(assignmentId);
        assertThat(attendanceGroupRepository.resolveAssignments(
                        EMPLOYEE,
                        LocalDate.parse("2026-08-20"),
                        testClock.instant()))
                .singleElement()
                .satisfies(successor -> {
                    assertThat(successor.assignmentId()).isEqualTo(successorId);
                    assertThat(successor.groupId())
                            .isEqualTo(setup.secondGroupId());
                });
        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                assignmentId)).containsExactly(
                        "ACTIVE:2026-08-14",
                        "INACTIVE:2026-08-20");
        assertThat(jdbc.queryForList(
                """
                SELECT state || ':' || CAST(business_effective_from AS VARCHAR)
                FROM attendance_assignment_timeline
                WHERE attendance_group_assignment_id = ?
                ORDER BY event_sequence
                """,
                String.class,
                successorId)).containsExactly("ACTIVE:2026-08-20");

        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments",
                        setup.firstGroupId())
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assignmentId")
                        .value(assignmentId))
                .andExpect(jsonPath("$.items[0].hasSuccessor").value(true))
                .andExpect(jsonPath("$.items[0].transferable").value(false));
        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments",
                        setup.secondGroupId())
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assignmentId")
                        .value(successorId))
                .andExpect(jsonPath("$.items[0].hasSuccessor").value(false))
                .andExpect(jsonPath("$.items[0].transferable").value(true));

        long assignmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class);
        long timelineCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class);
        mockMvc.perform(post(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}/transfer",
                        setup.firstGroupId(),
                        assignmentId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-assignment-cross-group-transfer")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 人员跨考勤组调配")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.assignmentId").value(successorId));
        mockMvc.perform(post(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}/transfer",
                        setup.secondGroupId(),
                        assignmentId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-assignment-cross-group-transfer")
                        .header(
                                "X-Change-Reason",
                                "WAVE-3 人员跨考勤组调配")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class))
                .isEqualTo(assignmentCount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class))
                .isEqualTo(timelineCount);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_ASSIGNMENT_TRANSFERRED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                assignmentId)).isEqualTo(1L);
    }

    @Test
    void assignment_list_marks_a_leaf_without_a_legal_boundary_non_transferable()
            throws Exception {
        Setup setup = createBaseSetup();
        String assignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-no-transfer-boundary",
                "2026-08-14",
                "2026-08-15");

        mockMvc.perform(get(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments",
                        setup.firstGroupId())
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assignmentId")
                        .value(assignmentId))
                .andExpect(jsonPath("$.items[0].hasSuccessor").value(false))
                .andExpect(jsonPath("$.items[0].transferable").value(false));
    }

    @Test
    void assignment_transfer_rejects_same_group_backfill_and_existing_successor()
            throws Exception {
        Setup setup = createBaseSetup();
        String assignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-transfer-guards",
                "2026-08-14",
                null);
        long assignmentsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class);
        long timelinesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class);

        transferAssignment(
                setup.firstGroupId(),
                assignmentId,
                setup.firstGroupId(),
                "2026-08-20",
                "wave3-transfer-same-group")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ATTENDANCE_ASSIGNMENT_TRANSFER_SAME_GROUP"));

        testClock.setInstant(Instant.parse("2026-08-25T00:00:00Z"));
        transferAssignment(
                setup.firstGroupId(),
                assignmentId,
                setup.secondGroupId(),
                "2026-08-20",
                "wave3-transfer-backfill")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ATTENDANCE_ASSIGNMENT_BACKFILL_FORBIDDEN"));

        testClock.setInstant(Instant.parse("2026-07-27T00:00:00Z"));
        transferAssignment(
                setup.firstGroupId(),
                assignmentId,
                setup.secondGroupId(),
                "2026-08-20",
                "wave3-transfer-first-success")
                .andExpect(status().isOk());
        transferAssignment(
                setup.firstGroupId(),
                assignmentId,
                setup.secondGroupId(),
                "2026-08-21",
                "wave3-transfer-existing-successor",
                1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ATTENDANCE_ASSIGNMENT_SUCCESSOR_EXISTS"));

        assertThat(assignmentsBefore).isEqualTo(1L);
        assertThat(timelinesBefore).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void assignment_transfer_never_crosses_the_company_boundary()
            throws Exception {
        Setup setup = createBaseSetup();
        String assignmentId = createAssignment(
                setup.firstGroupId(),
                "wave3-assignment-transfer-company-guard",
                "2026-08-14",
                null);
        jdbc.update(
                """
                UPDATE attendance_group
                SET company_id = '30000000-0000-0000-0000-000000000002'
                WHERE attendance_group_id = ?
                """,
                setup.secondGroupId());
        long assignmentsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class);
        long timelinesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class);

        transferAssignment(
                setup.firstGroupId(),
                assignmentId,
                setup.secondGroupId(),
                "2026-08-20",
                "wave3-transfer-cross-company")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_assignment", Long.class))
                .isEqualTo(assignmentsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_assignment_timeline", Long.class))
                .isEqualTo(timelinesBefore);
    }

    @Test
    void location_timeline_resolves_business_and_knowledge_time_without_mutating_history()
            throws Exception {
        MvcResult created = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-bitemporal",
                locationBodyFor(
                        "BITEMPORAL", "Asia/Shanghai",
                        "WAVE-3 双时态地点建档"))
                .andExpect(status().isCreated())
                .andReturn();
        String locationId = value(created, "$.locationId");
        String originalRevisionId = value(created, "$.locationRevisionId");
        String originalDigest = jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM location_revision
                WHERE location_revision_id = ?
                """,
                String.class,
                originalRevisionId);

        testClock.setInstant(Instant.parse("2026-07-28T00:00:00Z"));
        mockMvc.perform(put(
                        "/api/v1/attendance-setup/locations/{locationId}",
                        locationId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-location-bitemporal-update")
                        .header("X-Change-Reason", "WAVE-3 地点双时态换版")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "code":"BITEMPORAL",
                                  "name":"双时态地点",
                                  "timeZone":"UTC",
                                  "effectiveFrom":"2026-09-01",
                                  "reason":"WAVE-3 地点双时态换版"
                                }
                                """.formatted(COMPANY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));

        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        locationId,
                        LocalDate.parse("2026-09-02"),
                        Instant.parse("2026-07-27T12:00:00Z")))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.locationRevisionId())
                            .isEqualTo(originalRevisionId);
                    assertThat(location.timeZone()).isEqualTo("Asia/Shanghai");
                });
        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        locationId,
                        LocalDate.parse("2026-09-02"),
                        Instant.parse("2026-07-29T00:00:00Z")))
                .singleElement()
                .satisfies(location ->
                        assertThat(location.timeZone()).isEqualTo("UTC"));
        assertThat(attendanceGroupRepository.resolveLocationRevisions(
                        locationId,
                        LocalDate.parse("2026-08-31"),
                        Instant.parse("2026-07-29T00:00:00Z")))
                .singleElement()
                .satisfies(location ->
                        assertThat(location.locationRevisionId())
                                .isEqualTo(originalRevisionId));
        assertThat(jdbc.queryForObject(
                """
                SELECT snapshot_digest
                FROM location_revision
                WHERE location_revision_id = ?
                """,
                String.class,
                originalRevisionId)).isEqualTo(originalDigest);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM location_timeline
                WHERE location_id = ?
                """,
                Long.class,
                locationId)).isEqualTo(2L);
        assertThat(attendanceGroupRepository.countLocationRevisions(locationId))
                .isEqualTo(2L);
        assertThat(attendanceGroupRepository.listLocationRevisions(
                        locationId, 1, 0))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.revisionNumber()).isEqualTo(2);
                    assertThat(location.rowVersion()).isEqualTo(1);
                    assertThat(location.timeZone()).isEqualTo("UTC");
                });
        assertThat(attendanceGroupRepository.listLocationRevisions(
                        locationId, 1, 1))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.revisionNumber()).isEqualTo(1);
                    assertThat(location.rowVersion()).isZero();
                    assertThat(location.locationRevisionId())
                            .isEqualTo(originalRevisionId);
                    assertThat(location.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                });
    }

    @Test
    void group_revision_history_is_bounded_complete_and_deterministic()
            throws Exception {
        Setup setup = createBaseSetup();
        AttendanceGroup current = attendanceGroupRepository
                .findGroup(setup.firstGroupId())
                .orElseThrow();
        Instant recordedAt = testClock.instant().plusSeconds(1);
        AttendanceGroup successor = new AttendanceGroup(
                current.groupId(),
                current.companyId(),
                current.code(),
                java.util.UUID.randomUUID().toString(),
                current.revisionNumber() + 1,
                current.name() + "换版",
                current.locationId(),
                current.locationRevisionId(),
                current.calendarId(),
                current.shiftTemplateId(),
                current.status(),
                LocalDate.parse("2026-09-01"),
                null,
                sha256(current.snapshotDigest() + "|revision-2"),
                current.rowVersion() + 1,
                "WAVE-3 考勤组历史分页",
                current.createdBy(),
                current.createdAt(),
                ADMIN_PRINCIPAL,
                recordedAt);

        attendanceGroupRepository.lockGroup(current.groupId());
        assertThat(attendanceGroupRepository.updateGroup(
                successor, current.rowVersion())).isTrue();
        assertThat(attendanceGroupRepository.countGroupRevisions(
                current.groupId())).isEqualTo(2L);
        assertThat(attendanceGroupRepository.listGroupRevisions(
                        current.groupId(), 1, 0))
                .singleElement()
                .satisfies(revision -> {
                    assertThat(revision.revisionNumber()).isEqualTo(2);
                    assertThat(revision.groupRevisionId())
                            .isEqualTo(successor.groupRevisionId());
                });
        assertThat(attendanceGroupRepository.listGroupRevisions(
                        current.groupId(), 1, 1))
                .singleElement()
                .satisfies(revision -> {
                    assertThat(revision.revisionNumber()).isEqualTo(1);
                    assertThat(revision.groupRevisionId())
                            .isEqualTo(current.groupRevisionId());
                    assertThat(revision.effectiveTo())
                            .isEqualTo(LocalDate.parse("2026-09-01"));
                });
    }

    @Test
    void assignment_shift_calendar_version_and_day_pages_have_no_drift()
            throws Exception {
        Setup setup = createBaseSetup();
        String firstAssignment = createAssignment(
                setup.firstGroupId(),
                EMPLOYEE,
                "wave3-page-assignment-1",
                "2026-08-14",
                "2026-08-15");
        String secondAssignment = createAssignment(
                setup.firstGroupId(),
                SECOND_EMPLOYEE,
                "wave3-page-assignment-2",
                "2026-08-14",
                "2026-08-15");

        assertThat(attendanceGroupRepository.countAssignments(
                setup.firstGroupId(), null)).isEqualTo(2L);
        var assignmentPage0 = attendanceGroupRepository.listAssignments(
                setup.firstGroupId(), null, 1, 0);
        var assignmentPage1 = attendanceGroupRepository.listAssignments(
                setup.firstGroupId(), null, 1, 1);
        assertThat(assignmentPage0).hasSize(1);
        assertThat(assignmentPage1).hasSize(1);
        assertThat(List.of(
                assignmentPage0.getFirst().assignmentId(),
                assignmentPage1.getFirst().assignmentId()))
                .containsExactlyInAnyOrder(firstAssignment, secondAssignment);

        String secondShiftVersion = createShiftVersion(
                setup.shiftId(),
                "wave3-page-shift-version-2",
                "2027-01-01",
                null);
        assertThat(shiftRepository.countVersions(setup.shiftId())).isEqualTo(2L);
        assertThat(shiftRepository.listVersions(setup.shiftId(), 1, 0))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.versionNumber()).isEqualTo(2);
                    assertThat(version.shiftVersionId())
                            .isEqualTo(secondShiftVersion);
                });
        assertThat(shiftRepository.listVersions(setup.shiftId(), 1, 1))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.versionNumber()).isEqualTo(1);
                    assertThat(version.shiftVersionId())
                            .isEqualTo(setup.shiftVersionId());
                });
        assertThat(shiftRepository.countTemplates(
                ADMIN_PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SETUP_READ,
                testClock.instant())).isEqualTo(1L);
        assertThat(shiftRepository.listTemplates(
                ADMIN_PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SETUP_READ,
                1,
                0,
                testClock.instant())).hasSize(1);

        var currentCalendar = calendarRepository
                .findCalendar(setup.calendarId())
                .orElseThrow();
        assertThat(calendarRepository.countVersions(setup.calendarId()))
                .isEqualTo(2L);
        assertThat(calendarRepository.listVersions(setup.calendarId(), 1, 0))
                .singleElement()
                .satisfies(version -> assertThat(version.versionNumber())
                        .isEqualTo(2));
        assertThat(calendarRepository.listVersions(setup.calendarId(), 1, 1))
                .singleElement()
                .satisfies(version -> assertThat(version.versionNumber())
                        .isEqualTo(1));
        assertThat(calendarRepository.countCalendars(
                ADMIN_PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SETUP_READ,
                2026,
                testClock.instant())).isEqualTo(1L);
        assertThat(calendarRepository.listCalendars(
                ADMIN_PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SETUP_READ,
                2026,
                1,
                0,
                testClock.instant())).hasSize(1);

        LocalDate from = LocalDate.parse("2026-08-14");
        LocalDate to = LocalDate.parse("2026-09-30");
        long dayCount = calendarRepository.countDays(
                currentCalendar.calendarVersionId(), from, to);
        assertThat(dayCount).isEqualTo(from.datesUntil(to.plusDays(1)).count());
        var dayPage0 = calendarRepository.listDays(
                currentCalendar.calendarVersionId(), from, to, 17, 0);
        var dayPage1 = calendarRepository.listDays(
                currentCalendar.calendarVersionId(), from, to, 17, 17);
        assertThat(dayPage0).hasSize(17);
        assertThat(dayPage1).hasSize(17);
        assertThat(dayPage0.getLast().businessDate())
                .isBefore(dayPage1.getFirst().businessDate());
        assertThat(dayPage0.stream().map(day -> day.calendarDayId()).toList())
                .doesNotContainAnyElementsOf(
                        dayPage1.stream()
                                .map(day -> day.calendarDayId())
                                .toList());
    }

    @Test
    void seasonal_shift_versions_require_adjacency_and_reject_overlap()
            throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-seasonal",
                locationBody("WAVE-3 季节班次地点"))
                .andExpect(status().isCreated())
                .andReturn();
        String locationId = value(location, "$.locationId");

        String adjacentShift = createShiftTemplate(
                locationId, "SEASONAL_OK", "季节班次", "wave3-seasonal-ok");
        String winter = createShiftVersion(
                adjacentShift, "wave3-winter-version",
                "2026-01-01", "2026-06-01");
        publishShiftVersion(adjacentShift, winter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        String summer = createShiftVersion(
                adjacentShift, "wave3-summer-version",
                "2026-06-01", null);
        publishShiftVersion(adjacentShift, summer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String overlapping = createShiftVersion(
                adjacentShift, "wave3-overlap-version",
                "2026-05-01", "2026-07-01");
        publishShiftVersion(adjacentShift, overlapping)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_VERSION_OVERLAP"));

        String gappedShift = createShiftTemplate(
                locationId, "SEASONAL_GAP", "间隙班次", "wave3-seasonal-gap");
        String first = createShiftVersion(
                gappedShift, "wave3-gap-first-version",
                "2026-01-01", "2026-06-01");
        publishShiftVersion(gappedShift, first)
                .andExpect(status().isOk());
        String afterGap = createShiftVersion(
                gappedShift, "wave3-gap-second-version",
                "2026-07-01", null);
        publishShiftVersion(gappedShift, afterGap)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_VERSION_GAP"));

        create(
                "/api/v1/attendance-setup/shifts/%s/versions"
                        .formatted(gappedShift),
                "wave3-invalid-overlap-segments",
                """
                {
                  "effectiveFrom":"2027-01-01",
                  "segments":[
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"09:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"18:00:00",
                      "endDayOffset":0
                    },
                    {
                      "segmentType":"MEAL",
                      "startLocalTime":"12:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"13:00:00",
                      "endDayOffset":0
                    }
                  ],
                  "reason":"WAVE-3 重叠工作段验证"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("工作段时间不得重叠"));
    }

    private Setup createBaseSetup() throws Exception {
        MvcResult location = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-create",
                locationBody("WAVE-3 地点建档"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        String locationId = value(location, "$.locationId");
        assertThat(jdbc.queryForObject(
                "SELECT created_by FROM location WHERE location_id = ?",
                String.class,
                locationId)).isEqualTo(ADMIN_PRINCIPAL);
        assertThat(attendanceGroupRepository.findLocationByIdempotency(
                ADMIN_PRINCIPAL, "wave3-location-create")).isPresent();

        MvcResult replay = create(
                "/api/v1/attendance-setup/locations",
                "wave3-location-create",
                locationBody("WAVE-3 地点建档"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(value(replay, "$.locationId")).isEqualTo(locationId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM location", Long.class)).isEqualTo(1);

        MvcResult shift = create(
                "/api/v1/attendance-setup/shifts",
                "wave3-shift-template",
                """
                {
                  "companyId":"%s",
                  "locationId":"%s",
                  "code":"NIGHT_A",
                  "name":"夜班 A",
                  "reason":"WAVE-3 夜班模板建档"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andReturn();
        String shiftId = value(shift, "$.shiftId");

        MvcResult version = create(
                "/api/v1/attendance-setup/shifts/%s/versions".formatted(shiftId),
                "wave3-shift-version",
                """
                {
                  "effectiveFrom":"2026-01-01",
                  "effectiveTo":"2027-01-01",
                  "segments":[
                    {
                      "segmentType":"MEAL",
                      "startLocalTime":"18:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"18:30:00",
                      "endDayOffset":0
                    },
                    {
                      "segmentType":"BREAK",
                      "startLocalTime":"18:30:00",
                      "startDayOffset":0,
                      "endLocalTime":"19:00:00",
                      "endDayOffset":0
                    },
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"20:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"05:00:00",
                      "endDayOffset":1
                    }
                  ],
                  "reason":"WAVE-3 跨午夜班次版本"
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String versionId = value(version, "$.shiftVersionId");

        mockMvc.perform(post(
                        "/api/v1/attendance-setup/shifts/{shiftId}/versions/{versionId}/publish",
                        shiftId,
                        versionId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-shift-version-publish")
                        .header("X-Change-Reason", "WAVE-3 发布跨午夜班次")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"WAVE-3 发布跨午夜班次"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.snapshotDigest").isString());

        MvcResult calendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-calendar-create",
                """
                {
                  "companyId":"%s",
                  "locationId":"%s",
                  "code":"CN_2026",
                  "name":"中国区 2026 工作日历",
                  "calendarYear":2026,
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-14",
                  "effectiveTo":"2027-01-01",
                  "reason":"WAVE-3 工作日历建档"
                }
                """.formatted(COMPANY, locationId))
                .andExpect(status().isCreated())
                .andReturn();
        String calendarId = value(calendar, "$.calendarId");

        mockMvc.perform(put(
                        "/api/v1/attendance-setup/calendars/{calendarId}/days",
                        calendarId)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-calendar-days-replace")
                        .header("X-Change-Reason", "WAVE-3 工作日设置")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calendarDaysJson(
                                java.time.LocalDate.parse("2026-08-14"),
                                java.time.LocalDate.parse("2027-01-01"))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        publishAndActivateCalendar(calendarId, 1);

        String firstGroup = createGroup(
                "wave3-group-first",
                "GROUP_A",
                "封装一组",
                locationId,
                calendarId,
                shiftId);
        String secondGroup = createGroup(
                "wave3-group-second",
                "GROUP_B",
                "封装二组",
                locationId,
                calendarId,
                shiftId);
        return new Setup(
                locationId, shiftId, versionId, calendarId,
                firstGroup, secondGroup);
    }

    private String createGroup(
            String idempotencyKey,
            String code,
            String name,
            String locationId,
            String calendarId,
            String shiftId) throws Exception {
        String body = """
                {
                  "companyId":"%s",
                  "code":"%s",
                  "name":"%s",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-14",
                  "reason":"WAVE-3 考勤组建档"
                }
                """.formatted(
                COMPANY, code, name, locationId, calendarId, shiftId);
        MvcResult result = create(
                "/api/v1/attendance-setup/groups",
                idempotencyKey,
                body)
                .andExpect(status().isCreated())
                .andReturn();
        String groupId = value(result, "$.groupId");
        create(
                "/api/v1/attendance-setup/groups",
                idempotencyKey,
                body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId));
        assertThat(auditCountForResource(groupId)).isEqualTo(1);
        return groupId;
    }

    private String createShiftTemplate(
            String locationId, String code, String name, String idempotencyKey)
            throws Exception {
        MvcResult result = create(
                "/api/v1/attendance-setup/shifts",
                idempotencyKey,
                """
                {
                  "companyId":"%s",
                  "locationId":"%s",
                  "code":"%s",
                  "name":"%s",
                  "reason":"WAVE-3 季节班次模板"
                }
                """.formatted(COMPANY, locationId, code, name))
                .andExpect(status().isCreated())
                .andReturn();
        return value(result, "$.shiftId");
    }

    private String createShiftVersion(
            String shiftId,
            String idempotencyKey,
            String effectiveFrom,
            String effectiveTo) throws Exception {
        String end = effectiveTo == null
                ? ""
                : ",\"effectiveTo\":\"" + effectiveTo + "\"";
        MvcResult result = create(
                "/api/v1/attendance-setup/shifts/%s/versions".formatted(shiftId),
                idempotencyKey,
                """
                {
                  "effectiveFrom":"%s"%s,
                  "segments":[
                    {
                      "segmentType":"WORK",
                      "startLocalTime":"08:00:00",
                      "startDayOffset":0,
                      "endLocalTime":"17:00:00",
                      "endDayOffset":0
                    }
                  ],
                  "reason":"WAVE-3 季节班次版本"
                }
                """.formatted(effectiveFrom, end))
                .andExpect(status().isCreated())
                .andReturn();
        return value(result, "$.shiftVersionId");
    }

    private org.springframework.test.web.servlet.ResultActions publishShiftVersion(
            String shiftId, String versionId) throws Exception {
        return publishShiftVersion(shiftId, versionId, 0);
    }

    private org.springframework.test.web.servlet.ResultActions publishShiftVersion(
            String shiftId, String versionId, long version) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/attendance-setup/shifts/{shiftId}/versions/{versionId}/publish",
                        shiftId,
                        versionId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .header(
                        "Idempotency-Key",
                        "wave3-seasonal-shift-publish-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", "WAVE-3 季节班次发布")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"reason":"WAVE-3 季节班次发布"}
                         """));
    }

    private org.springframework.test.web.servlet.ResultActions updateShiftVersion(
            String shiftId,
            String versionId,
            long version,
            String start,
            String end) throws Exception {
        return mockMvc.perform(put(
                        "/api/v1/attendance-setup/shifts/{shiftId}/versions/{versionId}",
                        shiftId,
                        versionId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .header(
                        "Idempotency-Key",
                        "wave3-shift-version-update-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", "WAVE-3 班次版本修订")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {
                           "effectiveFrom":"2026-01-01",
                           "segments":[{
                             "segmentType":"WORK",
                             "startLocalTime":"%s",
                             "startDayOffset":0,
                             "endLocalTime":"%s",
                             "endDayOffset":0
                           }],
                           "reason":"WAVE-3 班次版本修订"
                         }
                         """.formatted(start, end)));
    }

    private org.springframework.test.web.servlet.ResultActions changeShiftTemplateStatus(
            String shiftId, long version, String target) throws Exception {
        return write(
                post("/api/v1/attendance-setup/shifts/{shiftId}/status", shiftId)
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\""),
                """
                {
                  "status":"%s",
                  "reason":"WAVE-3 班次模板状态变更"
                }
                """.formatted(target));
    }

    private org.springframework.test.web.servlet.ResultActions changeShiftVersionStatus(
            String shiftId, String versionId, long version, String target)
            throws Exception {
        return write(
                post(
                        "/api/v1/attendance-setup/shifts/{shiftId}/versions/{versionId}/status",
                        shiftId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\""),
                """
                {
                  "status":"%s",
                  "businessEffectiveFrom":"2026-07-27",
                  "reason":"WAVE-3 班次版本状态变更"
                }
                """.formatted(target));
    }

    private org.springframework.test.web.servlet.ResultActions updateGroupPeriod(
            String groupId,
            Setup setup,
            String effectiveFrom,
            String effectiveTo,
            String reason) throws Exception {
        return mockMvc.perform(put(
                        "/api/v1/attendance-setup/groups/{groupId}",
                        groupId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .header(
                        "Idempotency-Key",
                        "wave3-group-period-update-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", reason)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "companyId":"%s",
                          "code":"%s",
                          "name":"%s",
                          "locationId":"%s",
                          "calendarId":"%s",
                          "shiftTemplateId":"%s",
                          "effectiveFrom":"%s",
                          "effectiveTo":"%s",
                          "reason":"%s"
                        }
                        """.formatted(
                        COMPANY,
                        groupId.equals(setup.firstGroupId())
                                ? "GROUP_A" : "GROUP_B",
                        groupId.equals(setup.firstGroupId())
                                ? "封装一组" : "封装二组",
                        setup.locationId(),
                        setup.calendarId(),
                        setup.shiftId(),
                        effectiveFrom,
                        effectiveTo,
                        reason)));
    }

    private org.springframework.test.web.servlet.ResultActions rolloverGroup(
            String groupId,
            Setup setup,
            String effectiveFrom,
            String reason) throws Exception {
        String code = groupId.equals(setup.firstGroupId())
                ? "GROUP_A" : "GROUP_B";
        String name = groupId.equals(setup.firstGroupId())
                ? "封装一组换版" : "封装二组换版";
        return mockMvc.perform(put(
                        "/api/v1/attendance-setup/groups/{groupId}",
                        groupId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .header(
                        "Idempotency-Key",
                        "wave3-group-rollover-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", reason)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "companyId":"%s",
                          "code":"%s",
                          "name":"%s",
                          "locationId":"%s",
                          "calendarId":"%s",
                          "shiftTemplateId":"%s",
                          "effectiveFrom":"%s",
                          "reason":"%s"
                        }
                        """.formatted(
                        COMPANY,
                        code,
                        name,
                        setup.locationId(),
                        setup.calendarId(),
                        setup.shiftId(),
                        effectiveFrom,
                        reason)));
    }

    private String groupBody(
            String code, String name, Setup setup) {
        return """
                {
                  "companyId":"%s",
                  "code":"%s",
                  "name":"%s",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-14",
                  "reason":"WAVE-3 策略基数失败全回滚"
                }
                """.formatted(
                COMPANY,
                code,
                name,
                setup.locationId(),
                setup.calendarId(),
                setup.shiftId());
    }

    private void assertGroupCreationFullyRolledBack(
            long groupsBefore,
            long revisionsBefore,
            long timelinesBefore,
            long bindingsBefore,
            long successAuditsBefore,
            String idempotencyKey) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group", Long.class))
                .isEqualTo(groupsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_revision", Long.class))
                .isEqualTo(revisionsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_group_timeline", Long.class))
                .isEqualTo(timelinesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_policy_binding_revision",
                Long.class)).isEqualTo(bindingsBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_CREATED'
                  AND result_code = 'SUCCESS'
                """,
                Long.class)).isEqualTo(successAuditsBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM attendance_setup_idempotency
                WHERE idempotency_key = ?
                  AND state = 'COMPLETED_SUCCESS'
                """,
                Long.class,
                idempotencyKey)).isZero();
    }

    private String createAssignment(
            String groupId,
            String idempotencyKey,
            String effectiveFrom,
            String effectiveTo) throws Exception {
        return createAssignment(
                groupId, EMPLOYEE, idempotencyKey, effectiveFrom, effectiveTo);
    }

    private String createAssignment(
            String groupId,
            String employeeId,
            String idempotencyKey,
            String effectiveFrom,
            String effectiveTo) throws Exception {
        MvcResult result = create(
                "/api/v1/attendance-setup/groups/%s/assignments".formatted(groupId),
                idempotencyKey,
                assignmentBody(employeeId, effectiveFrom, effectiveTo))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed", "false"))
                .andReturn();
        return value(result, "$.assignmentId");
    }

    private org.springframework.test.web.servlet.ResultActions transferAssignment(
            String sourceGroupId,
            String assignmentId,
            String targetGroupId,
            String effectiveFrom,
            String idempotencyKey) throws Exception {
        return transferAssignment(
                sourceGroupId,
                assignmentId,
                targetGroupId,
                effectiveFrom,
                idempotencyKey,
                0);
    }

    private org.springframework.test.web.servlet.ResultActions transferAssignment(
            String sourceGroupId,
            String assignmentId,
            String targetGroupId,
            String effectiveFrom,
            String idempotencyKey,
            long expectedVersion) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/attendance-setup/groups/{groupId}/assignments/{assignmentId}/transfer",
                        sourceGroupId,
                        assignmentId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"" + expectedVersion + "\"")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Change-Reason", "WAVE-3 人员跨考勤组调配")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "targetGroupId":"%s",
                          "effectiveFrom":"%s",
                          "reason":"WAVE-3 人员跨考勤组调配"
                        }
                        """.formatted(targetGroupId, effectiveFrom)));
    }

    private Map<String, Long> formalAttendanceTableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList(
                """
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND (
                    TABLE_NAME LIKE 'ATTENDANCE_%'
                    OR TABLE_NAME LIKE 'SHIFT_%'
                    OR TABLE_NAME LIKE 'WORK_CALENDAR%'
                    OR TABLE_NAME = 'AUDIT_EVENT'
                  )
                ORDER BY TABLE_NAME
                """,
                String.class);
        tables.forEach(table -> counts.put(
                table,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class)));
        return Map.copyOf(counts);
    }

    private void assertFormalResultTablesAbsent() {
        for (String table : List.of(
                "ATTENDANCE_RAW_PUNCH",
                "ATTENDANCE_DAILY_RESULT",
                "ATTENDANCE_MONTHLY_RESULT",
                "ATTENDANCE_MONTHLY_EXEMPTION_USAGE")) {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?
                    """,
                    Integer.class,
                    table);
            assertThat(count)
                    .as(table + " 不得在 W3 引入正式写路径")
                    .isZero();
        }
    }

    private void deactivatePolicyAt(String scopedVersionId, String businessDate) {
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id, request_id, recorded_at
                )
                SELECT RANDOM_UUID(), scope_id, scoped_version_id,
                       (
                           SELECT MAX(existing.event_sequence) + 1
                           FROM attendance_policy_lifecycle_event existing
                           WHERE existing.scope_id = version.scope_id
                       ),
                       (
                           SELECT existing.lifecycle_event_id
                           FROM attendance_policy_lifecycle_event existing
                           WHERE existing.scope_id = version.scope_id
                           ORDER BY existing.event_sequence DESC
                           LIMIT 1
                       ),
                       'DEACTIVATE_SCHEDULED', CAST(? AS DATE),
                       'WAVE-3 生命周期负例', ?, RANDOM_UUID(), ?
                FROM attendance_policy_scoped_version version
                WHERE version.scoped_version_id = ?
                """,
                businessDate,
                ADMIN_PRINCIPAL,
                Timestamp.from(FIXTURE_RECORDED_AT),
                scopedVersionId);
    }

    private org.springframework.test.web.servlet.ResultActions simulateLate(
            int lateMinutes, boolean monthlyAllowanceUsed) throws Exception {
        testUsageProvider.setUsedCount(monthlyAllowanceUsed ? 1 : 0);
        return write(
                post("/api/v1/attendance-setup/policy-simulations"),
                """
                {
                  "employeeId":"%s",
                  "businessDate":"2026-08-15",
                  "correctionAsOf":"2026-08-15T12:00:00+08:00",
                  "punches":[{
                    "direction":"ENTRY",
                    "instant":"2026-08-15T20:%s:00+08:00",
                    "association":"SCHEDULED_WORK"
                  }]
                }
                """.formatted(
                        EMPLOYEE,
                        "%02d".formatted(lateMinutes)));
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        MutableClock wave3MutableClock() {
            return new MutableClock(Instant.parse("2026-07-27T00:00:00Z"));
        }

        @Bean
        @Primary
        MutableUsageProvider wave3MutableUsageProvider() {
            return new MutableUsageProvider();
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class MutableUsageProvider
            implements AttendanceMonthlyExemptionUsageProvider {
        private volatile int usedCount;
        private volatile String employeeIdOverride;
        private volatile Instant knowledgeTimeOverride;

        void reset() {
            usedCount = 0;
            employeeIdOverride = null;
            knowledgeTimeOverride = null;
        }

        void setUsedCount(int usedCount) {
            this.usedCount = usedCount;
        }

        void setEmployeeIdOverride(String employeeIdOverride) {
            this.employeeIdOverride = employeeIdOverride;
        }

        void setKnowledgeTimeOverride(Instant knowledgeTimeOverride) {
            this.knowledgeTimeOverride = knowledgeTimeOverride;
        }

        @Override
        public UsageSnapshot findUsage(
                String employeeId,
                YearMonth naturalMonth,
                Instant correctionAsOf) {
            return new UsageSnapshot(
                    employeeIdOverride == null ? employeeId : employeeIdOverride,
                    naturalMonth,
                    usedCount,
                    "TEST_AUTHORITATIVE_MONTHLY_USAGE",
                    knowledgeTimeOverride == null
                            ? correctionAsOf : knowledgeTimeOverride);
        }
    }

    private org.springframework.test.web.servlet.ResultActions replaceCalendarDays(
            String calendarId, long version, String reason, String body) throws Exception {
        return mockMvc.perform(put(
                        "/api/v1/attendance-setup/calendars/{calendarId}/days",
                        calendarId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .header(
                        "Idempotency-Key",
                        "wave3-auto-calendar-days-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", reason)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions upsertCalendarVersionDays(
            String calendarId,
            String versionId,
            long rowVersion,
            String reason,
            String body) throws Exception {
        return mockMvc.perform(patch(
                        "/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/days",
                        calendarId,
                        versionId)
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"" + rowVersion + "\"")
                .header(
                        "Idempotency-Key",
                        "wave3-auto-calendar-version-days-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet())
                .header("X-Change-Reason", reason)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions publishCalendarVersion(
            String calendarId,
            String versionId,
            long rowVersion) throws Exception {
        return write(
                post("/api/v1/attendance-setup/calendars/{calendarId}"
                                + "/versions/{versionId}/publish",
                        calendarId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"" + rowVersion + "\""),
                """
                {"reason":"WAVE-3 发布工作日历版本"}
                """);
    }

    private void publishAndActivateCalendar(String calendarId, long version)
            throws Exception {
        write(
                post("/api/v1/attendance-setup/calendars/{calendarId}/publish",
                        calendarId)
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .header(
                                "Idempotency-Key",
                                "wave3-calendar-publish-" + calendarId),
                """
                {"reason":"WAVE-3 发布工作日历"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(header().string(
                        HttpHeaders.ETAG, "\"" + (version + 1) + "\""));
    }

    private org.springframework.test.web.servlet.ResultActions create(
            String path, String idempotencyKey, String body) throws Exception {
        return write(post(path).header("Idempotency-Key", idempotencyKey), body);
    }

    private org.springframework.test.web.servlet.ResultActions write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body) throws Exception {
        String changeReason;
        try {
            changeReason = JsonPath.read(body, "$.reason");
        } catch (RuntimeException exception) {
            changeReason = "WAVE-3 试算或校验";
        }
        return mockMvc.perform(request
                .with(user(ADMIN_PRINCIPAL))
                .with(csrf())
                .with(idempotencyIfMissing("wave3-auto-write"))
                .header("X-Change-Reason", changeReason)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
            idempotencyIfMissing(String prefix) {
        return request -> {
            if (request.getHeader("Idempotency-Key") == null) {
                request.addHeader(
                        "Idempotency-Key",
                        prefix + "-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet());
            }
            return request;
        };
    }

    private org.springframework.test.web.servlet.ResultActions read(
            String path, String... queryPairs) throws Exception {
        var request = get(path).with(user(ADMIN_PRINCIPAL));
        for (int index = 0; index < queryPairs.length; index += 2) {
            request.queryParam(queryPairs[index], queryPairs[index + 1]);
        }
        return mockMvc.perform(request);
    }

    private String assignmentBody(String effectiveFrom, String effectiveTo) {
        return assignmentBody(EMPLOYEE, effectiveFrom, effectiveTo);
    }

    private String assignmentBody(
            String employeeId, String effectiveFrom, String effectiveTo) {
        String end = effectiveTo == null
                ? ""
                : ",\"effectiveTo\":\"" + effectiveTo + "\"";
        return """
               {
                 "employeeId":"%s",
                 "effectiveFrom":"%s"%s,
                 "reason":"WAVE-3 人员考勤组分配"
               }
               """.formatted(employeeId, effectiveFrom, end);
    }

    private String locationBody(String reason) {
        return locationBodyFor("SHENZHOU_SZ", "Asia/Shanghai", reason);
    }

    private String locationBodyFor(String code, String timeZone, String reason) {
        return """
               {
                 "companyId":"%s",
                 "code":"%s",
                 "name":"神州半导体苏州厂区",
                 "timeZone":"%s",
                 "effectiveFrom":"2026-01-01",
                 "reason":"%s"
               }
               """.formatted(COMPANY, code, timeZone, reason);
    }

    private String calendarDaysJson(
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo) {
        return effectiveFrom.datesUntil(effectiveTo)
                .map(date -> """
                        {
                          "businessDate":"%s",
                          "dayType":"WORKDAY"
                        }
                        """.formatted(date))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private void insertPolicy(
            String templateId,
            String code,
            String name,
            String versionId,
            String parameters) {
        Timestamp now = Timestamp.from(FIXTURE_RECORDED_AT);
        String fieldDefinitions = switch (code) {
            case "MEAL_DEDUCTION" -> """
                    [
                      {"key":"enabled","label":"是否启用","valueType":"BOOLEAN","required":true,"enumValues":[]},
                      {"key":"mealWindowStart","label":"晚餐窗口开始","valueType":"LOCAL_TIME","required":true,"enumValues":[]},
                      {"key":"mealWindowEnd","label":"晚餐窗口结束","valueType":"LOCAL_TIME","required":true,"enumValues":[]},
                      {"key":"deductionMinutes","label":"扣除分钟","valueType":"INTEGER","required":true,"enumValues":[],"minimum":0,"maximum":240},
                      {"key":"triggerMinutes","label":"触发分钟","valueType":"INTEGER","required":true,"enumValues":[],"minimum":0,"maximum":1440},
                      {"key":"applicableDayTypes","label":"适用日期","valueType":"ENUM_LIST","required":true,"enumValues":["WORKDAY","SPECIAL_WORKDAY","WEEKEND","PUBLIC_HOLIDAY"]}
                    ]
                    """;
            case "LATE_GRACE" -> """
                    [
                      {"key":"enabled","label":"是否启用","valueType":"BOOLEAN","required":true,"enumValues":[]},
                      {"key":"graceMinutes","label":"宽限分钟","valueType":"INTEGER","required":true,"enumValues":[],"minimum":15,"maximum":15}
                    ]
                    """;
            case "MONTHLY_LATE_EXEMPTION" -> """
                    [
                      {"key":"enabled","label":"是否启用","valueType":"BOOLEAN","required":true,"enumValues":[]},
                      {"key":"graceMinutes","label":"宽限分钟","valueType":"INTEGER","required":true,"enumValues":[],"minimum":15,"maximum":15},
                      {"key":"monthlyUses","label":"自然月次数","valueType":"INTEGER","required":true,"enumValues":[],"minimum":1,"maximum":1},
                      {"key":"resetOnGroupChange","label":"换组重置","valueType":"BOOLEAN","required":true,"enumValues":[]}
                    ]
                    """;
            default -> throw new IllegalArgumentException("unsupported policy code");
        };
        jdbc.update(
                """
                INSERT INTO attendance_policy_template (
                    policy_template_id, template_code, name, description,
                    field_definitions_json, created_by, created_at
                ) VALUES (?, ?, ?, 'WAVE-3 受控考勤基础策略', ?,
                    ?, ?)
                """,
                templateId, code, name, fieldDefinitions,
                ADMIN_PRINCIPAL, now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scope (
                    scope_id, policy_template_id, company_id, row_version,
                    created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                scopeId(templateId), templateId, COMPANY,
                ADMIN_PRINCIPAL, now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scoped_version (
                    scoped_version_id, scope_id, version_number,
                    parameters_json, effective_from, effective_to,
                    change_reason, validation_json, snapshot_json,
                    snapshot_digest, rollback_of_scoped_version_id, row_version,
                    created_by, created_at
                ) VALUES (?, ?, 1, ?, ?, NULL,
                    'WAVE-3 受控策略发布', '{"valid":true,"issues":[]}',
                    '{}', ?, NULL, 1, ?, ?)
                """,
                versionId, scopeId(templateId),
                parameters, Date.valueOf("1970-01-01"),
                DIGEST, ADMIN_PRINCIPAL, now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id, request_id, recorded_at
                ) VALUES (?, ?, ?, 1, NULL, 'PUBLISHED', ?,
                    'WAVE-3 受控策略发布', ?, ?, ?)
                """,
                lifecycleId(templateId), scopeId(templateId), versionId,
                Date.valueOf("1970-01-01"), ADMIN_PRINCIPAL,
                "fixture-" + code, now);
    }

    private String scopeId(String templateId) {
        return "88" + templateId.substring(2);
    }

    private String lifecycleId(String templateId) {
        return "89" + templateId.substring(2);
    }

    private String value(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private String groupRevisionId(String groupId) {
        return jdbc.queryForObject(
                """
                SELECT attendance_group_revision_id
                FROM attendance_group_revision
                WHERE attendance_group_id = ?
                ORDER BY revision_number DESC
                LIMIT 1
                """,
                String.class,
                groupId);
    }

    private record Setup(
            String locationId,
            String shiftId,
            String shiftVersionId,
            String calendarId,
            String firstGroupId,
            String secondGroupId) {
    }
}

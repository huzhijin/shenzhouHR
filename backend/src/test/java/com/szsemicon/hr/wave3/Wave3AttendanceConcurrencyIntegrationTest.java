package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Wave3AttendanceConcurrencyIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String LEGAL_ENTITY =
            "30000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE =
            "b0000000-0000-0000-0000-000000000001";
    private static final String SECOND_EMPLOYEE =
            "b0000000-0000-0000-0000-000000000002";
    private static final String DIGEST =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final AtomicLong AUTO_IDEMPOTENCY_SEQUENCE = new AtomicLong();

    @BeforeEach
    void seedAttendancePoliciesForConcurrency() {
        insertPolicy(
                "96000000-0000-0000-0000-000000000001",
                "MEAL_DEDUCTION",
                "97000000-0000-0000-0000-000000000001",
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
                "96000000-0000-0000-0000-000000000002",
                "LATE_GRACE",
                "97000000-0000-0000-0000-000000000002",
                """
                {"enabled":true,"graceMinutes":15}
                """);
        insertPolicy(
                "96000000-0000-0000-0000-000000000003",
                "MONTHLY_LATE_EXEMPTION",
                "97000000-0000-0000-0000-000000000003",
                """
                {"enabled":true,"graceMinutes":15,"monthlyUses":1,"resetOnGroupChange":false}
                """);
    }

    @Test
    void concurrent_shift_publication_and_binding_creation_allow_one_winner()
            throws Exception {
        String locationId = value(create(
                "/api/v1/attendance-setup/locations",
                "wave3-concurrency-location",
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENCY",
                  "name":"并发测试地点",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":"2027-01-01",
                  "reason":"WAVE-3 并发地点建档"
                }
                """.formatted(LEGAL_ENTITY)), "$.locationId");
        String competingShiftId = createShift(
                locationId, "CONCURRENT_SHIFT", "wave3-concurrency-shift");
        String firstVersionId = createVersion(
                competingShiftId, "wave3-concurrency-version-first");
        String secondVersionId = createVersion(
                competingShiftId, "wave3-concurrency-version-second");

        List<MvcResult> publicationResults = concurrent(
                () -> publish(competingShiftId, firstVersionId),
                () -> publish(competingShiftId, secondVersionId));
        assertStatuses(publicationResults, 200, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM shift_publication_timeline
                WHERE shift_template_id = ? AND state = 'PUBLISHED'
                """,
                Long.class,
                competingShiftId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'SHIFT_VERSION_PUBLISHED'
                """,
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND reason_code = 'SHIFT_VERSION_OVERLAP'
                """,
                Long.class)).isEqualTo(1);

        String baseShiftId = createShift(
                locationId, "GROUP_SHIFT", "wave3-concurrency-group-shift");
        String baseVersionId = createVersion(
                baseShiftId, "wave3-concurrency-group-version");
        assertThat(publish(baseShiftId, baseVersionId)
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult calendar = create(
                "/api/v1/attendance-setup/calendars",
                "wave3-concurrency-calendar",
                """
                {
                  "legalEntityId":"%s",
                  "locationId":"%s",
                  "code":"CONCURRENCY_2026",
                  "name":"并发测试日历",
                  "calendarYear":2026,
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-15",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 并发日历建档"
                }
                """.formatted(LEGAL_ENTITY, locationId));
        String calendarId = value(calendar, "$.calendarId");
        String calendarVersionId = value(calendar, "$.calendarVersionId");
        MvcResult calendarDays = perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/attendance-setup/calendars/{calendarId}/versions/{versionId}/days",
                        calendarId,
                        calendarVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("X-Change-Reason", "WAVE-3 并发日历日"),
                """
                [
                  {
                    "businessDate":"2026-08-15",
                    "dayType":"WORKDAY"
                  },
                  {
                    "businessDate":"2026-08-16",
                    "dayType":"WORKDAY"
                  }
                ]
                """);
        calendarVersionId = value(calendarDays, "$.calendarVersionId");
        perform(
                post("/api/v1/attendance-setup/calendars/{calendarId}/versions/{versionId}/publish",
                        calendarId,
                        calendarVersionId)
                        .header(HttpHeaders.IF_MATCH, "\"1\""),
                """
                {"reason":"WAVE-3 并发日历发布"}
                """);
        String groupId = value(create(
                "/api/v1/attendance-setup/groups",
                "wave3-concurrency-group",
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENCY_GROUP",
                  "name":"并发测试考勤组",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-15",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 并发考勤组建档"
                }
                """.formatted(
                        LEGAL_ENTITY, locationId, calendarId, baseShiftId)),
                "$.groupId");

        String bindingFamilyId = jdbc.queryForObject(
                """
                SELECT binding_family_id
                FROM attendance_policy_binding_family
                WHERE attendance_group_id = ?
                  AND policy_kind = 'LATE_GRACE'
                """,
                String.class,
                groupId);
        String groupRevisionId = jdbc.queryForObject(
                """
                SELECT attendance_group_revision_id
                FROM attendance_group_revision
                WHERE attendance_group_id = ?
                ORDER BY revision_number DESC
                LIMIT 1
                """,
                String.class,
                groupId);
        String bindingImpactToken = value(
                perform(
                        post("/api/v1/attendance-setup/policy-impact-preview"),
                        """
                        {
                          "policyKind":"LATE_GRACE",
                          "policyVersionId":"97000000-0000-0000-0000-000000000002",
                          "groupId":"%s",
                          "groupRevisionId":"%s",
                          "effectiveFrom":"2026-08-16",
                          "effectiveTo":"2026-08-17",
                          "reason":"WAVE-3 并发策略绑定替换"
                        }
                        """.formatted(groupId, groupRevisionId)),
                "$.impactToken");
        List<MvcResult> bindingResults = concurrent(
                () -> createBinding(
                        bindingFamilyId, groupId, groupRevisionId,
                        bindingImpactToken,
                        "wave3-concurrent-binding-first"),
                () -> createBinding(
                        bindingFamilyId, groupId, groupRevisionId,
                        bindingImpactToken,
                        "wave3-concurrent-binding-second"));
        assertStatuses(bindingResults, 200, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision
                WHERE binding_family_id = ?
                """,
                Long.class,
                bindingFamilyId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_BINDING_REPLACED'
                """,
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                """,
                Long.class)).isEqualTo(1);

        String secondGroupId = value(create(
                "/api/v1/attendance-setup/groups",
                "wave3-concurrency-group-second",
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENCY_GROUP_SECOND",
                  "name":"第二并发考勤组",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 第二并发考勤组建档"
                }
                """.formatted(
                        LEGAL_ENTITY, locationId, calendarId, baseShiftId)),
                "$.groupId");

        List<MvcResult> sameKeyAssignments = concurrent(
                () -> createAssignment(
                        groupId, EMPLOYEE, "wave3-assignment-same-key"),
                () -> createAssignment(
                        groupId, EMPLOYEE, "wave3-assignment-same-key"));
        assertStatuses(sameKeyAssignments, 201, 201);
        assertThat(sameKeyAssignments.get(0).getResponse().getContentAsString())
                .isEqualTo(sameKeyAssignments.get(1)
                        .getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_group_assignment
                WHERE employee_id = ?
                """,
                Long.class,
                EMPLOYEE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_ASSIGNMENT_CREATED'
                  AND resource_id_ref = (
                      SELECT attendance_group_assignment_id
                      FROM attendance_group_assignment
                      WHERE employee_id = ?
                  )
                """,
                Long.class,
                EMPLOYEE)).isEqualTo(1);

        List<MvcResult> competingAssignments = concurrent(
                () -> createAssignment(
                        groupId, SECOND_EMPLOYEE,
                        "wave3-assignment-different-key-first"),
                () -> createAssignment(
                        secondGroupId, SECOND_EMPLOYEE,
                        "wave3-assignment-different-key-second"));
        assertStatuses(competingAssignments, 201, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_group_assignment
                WHERE employee_id = ?
                """,
                Long.class,
                SECOND_EMPLOYEE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                """,
                Long.class)).isEqualTo(2);

        String rolloverGroupId = value(create(
                "/api/v1/attendance-setup/groups",
                "wave3-concurrency-rollover-group",
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENCY_ROLLOVER_GROUP",
                  "name":"并发换版考勤组",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-15",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 并发换版考勤组建档"
                }
                """.formatted(
                        LEGAL_ENTITY, locationId, calendarId, baseShiftId)),
                "$.groupId");
        List<MvcResult> groupRolloverResults = concurrent(
                () -> rolloverGroup(
                        rolloverGroupId,
                        "并发换版胜者甲",
                        "wave3-concurrent-group-rollover-first"),
                () -> rolloverGroup(
                        rolloverGroupId,
                        "并发换版胜者乙",
                        "wave3-concurrent-group-rollover-second"));
        assertStatuses(groupRolloverResults, 200, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_group_revision
                WHERE attendance_group_id = ?
                """,
                Long.class,
                rolloverGroupId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM attendance_policy_binding_revision revision
                JOIN attendance_policy_binding_family family
                  ON family.binding_family_id = revision.binding_family_id
                WHERE family.attendance_group_id = ?
                """,
                Long.class,
                rolloverGroupId)).isEqualTo(6L);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_GROUP_UPDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                rolloverGroupId)).isEqualTo(1L);
        verifyPolicyValidateAndDeactivateConcurrency();
        verifyConcurrentShiftTemplateStatusUpdates();
    }

    private void verifyConcurrentShiftTemplateStatusUpdates()
            throws Exception {
        String locationId = value(create(
                "/api/v1/attendance-setup/locations",
                "wave3-concurrent-template-status-location",
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENT_TEMPLATE_STATUS",
                  "name":"并发班次模板状态地点",
                  "timeZone":"Asia/Shanghai",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":"2027-01-01",
                  "reason":"WAVE-3 并发班次模板状态地点"
                }
                """.formatted(LEGAL_ENTITY)), "$.locationId");
        String shiftId = createShift(
                locationId,
                "CONCURRENT_TEMPLATE_STATUS",
                "wave3-concurrent-template-status-shift");

        List<MvcResult> results = concurrent(
                () -> changeShiftTemplateStatus(
                        shiftId,
                        "wave3-concurrent-template-status-first"),
                () -> changeShiftTemplateStatus(
                        shiftId,
                        "wave3-concurrent-template-status-second"));

        assertStatuses(results, 200, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'SHIFT_TEMPLATE_INACTIVE'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                  AND policy_version = '1'
                """,
                Long.class,
                shiftId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT action_code
                FROM audit_event
                WHERE resource_type = 'SHIFT_TEMPLATE'
                  AND resource_id_ref = ?
                  AND policy_version IS NOT NULL
                ORDER BY CAST(policy_version AS DECIMAL(20, 0)) DESC
                FETCH FIRST 1 ROW ONLY
                """,
                String.class,
                shiftId)).isEqualTo("SHIFT_TEMPLATE_INACTIVE");
    }

    private void verifyPolicyValidateAndDeactivateConcurrency()
            throws Exception {
        String mealTemplate = "96000000-0000-0000-0000-000000000001";
        String mealVersion = value(
                createPolicyDraft(
                        mealTemplate,
                        "97000000-0000-0000-0000-000000000001"),
                "$.scopedVersionId");
        List<MvcResult> validateReplay = concurrent(
                () -> validatePolicy(
                        mealTemplate, mealVersion,
                        "wave3-concurrent-validate-replay", 0),
                () -> validatePolicy(
                        mealTemplate, mealVersion,
                        "wave3-concurrent-validate-replay", 0));
        assertStatuses(validateReplay, 200, 200);
        assertThat(validateReplay.get(0).getResponse().getContentAsString())
                .isEqualTo(validateReplay.get(1)
                        .getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_VALIDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                mealVersion)).isEqualTo(1);

        long validationSuccesses = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_VALIDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                mealVersion);
        assertThat(validatePolicy(
                mealTemplate, mealVersion,
                "wave3-concurrent-validate-stale", 0)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_VALIDATED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                mealVersion)).isEqualTo(validationSuccesses);

        String lateTemplate = "96000000-0000-0000-0000-000000000002";
        String lateVersion = "97000000-0000-0000-0000-000000000002";
        List<MvcResult> deactivateReplay = concurrent(
                () -> deactivatePolicy(
                        lateTemplate, lateVersion,
                        "wave3-concurrent-deactivate-replay", 0),
                () -> deactivatePolicy(
                        lateTemplate, lateVersion,
                        "wave3-concurrent-deactivate-replay", 0));
        assertStatuses(deactivateReplay, 200, 200);
        assertThat(deactivateReplay.get(0).getResponse().getContentAsString())
                .isEqualTo(deactivateReplay.get(1)
                        .getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_DEACTIVATION_SCHEDULED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                lateVersion)).isEqualTo(1);

        String punchTemplate = "96000000-0000-0000-0000-000000000003";
        String punchVersion = "97000000-0000-0000-0000-000000000003";
        List<MvcResult> competingDeactivate = concurrent(
                () -> deactivatePolicy(
                        punchTemplate, punchVersion,
                        "wave3-concurrent-deactivate-first", 0),
                () -> deactivatePolicy(
                        punchTemplate, punchVersion,
                        "wave3-concurrent-deactivate-second", 0));
        assertStatuses(competingDeactivate, 200, 409);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_POLICY_DEACTIVATION_SCHEDULED'
                  AND resource_id_ref = ?
                  AND result_code = 'SUCCESS'
                """,
                Long.class,
                punchVersion)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND reason_code = 'VERSION_CONFLICT'
                """,
                Long.class)).isGreaterThanOrEqualTo(2);
    }

    private String createShift(
            String locationId, String code, String idempotencyKey) throws Exception {
        return value(create(
                "/api/v1/attendance-setup/shifts",
                idempotencyKey,
                """
                {
                  "legalEntityId":"%s",
                  "locationId":"%s",
                  "code":"%s",
                  "name":"并发测试班次",
                  "reason":"WAVE-3 并发班次建档"
                }
                """.formatted(LEGAL_ENTITY, locationId, code)), "$.shiftId");
    }

    private String createVersion(String shiftId, String idempotencyKey)
            throws Exception {
        return value(create(
                "/api/v1/attendance-setup/shifts/%s/versions".formatted(shiftId),
                idempotencyKey,
                """
                {
                  "effectiveFrom":"2026-08-01",
                  "segments":[{
                    "segmentType":"WORK",
                    "startLocalTime":"08:00:00",
                    "startDayOffset":0,
                    "endLocalTime":"17:00:00",
                    "endDayOffset":0
                  }],
                  "reason":"WAVE-3 并发班次版本"
                }
                """), "$.shiftVersionId");
    }

    private MvcResult publish(String shiftId, String versionId) throws Exception {
        return perform(
                post(
                        "/api/v1/attendance-setup/shifts/{shiftId}/versions/{versionId}/publish",
                        shiftId,
                        versionId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""),
                """
                {"reason":"WAVE-3 并发发布"}
                """);
    }

    private MvcResult createBinding(
            String bindingFamilyId,
            String groupId,
            String groupRevisionId,
            String impactToken,
            String idempotencyKey)
            throws Exception {
        return perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/attendance-setup/policy-bindings/{bindingId}",
                                bindingFamilyId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey),
                """
                {
                  "policyKind":"LATE_GRACE",
                  "policyVersionId":"97000000-0000-0000-0000-000000000002",
                  "groupId":"%s",
                  "groupRevisionId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 并发策略绑定替换",
                  "impactToken":"%s"
                }
                """.formatted(groupId, groupRevisionId, impactToken));
    }

    private MvcResult createAssignment(
            String groupId, String employeeId, String idempotencyKey)
            throws Exception {
        return perform(
                post("/api/v1/attendance-setup/groups/{groupId}/assignments",
                        groupId)
                        .header("Idempotency-Key", idempotencyKey),
                """
                {
                  "employeeId":"%s",
                  "effectiveFrom":"2026-08-15",
                  "effectiveTo":"2026-08-16",
                  "reason":"WAVE-3 并发人员分配"
                }
                """.formatted(employeeId));
    }

    private MvcResult changeShiftTemplateStatus(
            String shiftId, String idempotencyKey) throws Exception {
        return perform(
                post(
                        "/api/v1/attendance-setup/shifts/{shiftId}/status",
                        shiftId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey),
                """
                {
                  "status":"INACTIVE",
                  "reason":"WAVE-3 并发班次模板停用"
                }
                """);
    }

    private MvcResult rolloverGroup(
            String groupId, String name, String idempotencyKey)
            throws Exception {
        return perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/attendance-setup/groups/{groupId}",
                                groupId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", idempotencyKey),
                """
                {
                  "legalEntityId":"%s",
                  "code":"CONCURRENCY_ROLLOVER_GROUP",
                  "name":"%s",
                  "locationId":"%s",
                  "calendarId":"%s",
                  "shiftTemplateId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "effectiveTo":"2026-08-17",
                  "reason":"WAVE-3 不同 key 并发考勤组换版"
                }
                """.formatted(
                        LEGAL_ENTITY,
                        name,
                        jdbc.queryForObject(
                                """
                                SELECT location_revision.location_id
                                FROM attendance_group_revision group_revision
                                JOIN location_revision
                                  ON location_revision.location_revision_id =
                                        group_revision.location_revision_id
                                WHERE group_revision.attendance_group_id = ?
                                ORDER BY group_revision.revision_number
                                FETCH FIRST 1 ROW ONLY
                                """,
                                String.class,
                                groupId),
                        jdbc.queryForObject(
                                """
                                SELECT work_calendar_id
                                FROM attendance_group_revision
                                WHERE attendance_group_id = ?
                                ORDER BY revision_number
                                FETCH FIRST 1 ROW ONLY
                                """,
                                String.class,
                                groupId),
                        jdbc.queryForObject(
                                """
                                SELECT shift_template_id
                                FROM attendance_group_revision
                                WHERE attendance_group_id = ?
                                ORDER BY revision_number
                                FETCH FIRST 1 ROW ONLY
                                """,
                                String.class,
                                groupId)));
    }

    private MvcResult validatePolicy(
            String templateId,
            String versionId,
            String idempotencyKey,
            long expectedVersion) throws Exception {
        return perform(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/validate",
                        templateId,
                        versionId)
                        .queryParam("legalEntityId", LEGAL_ENTITY)
                        .header(HttpHeaders.IF_MATCH,
                                "\"" + expectedVersion + "\"")
                        .header("Idempotency-Key", idempotencyKey),
                """
                {"reason":"WAVE-3 并发校验"}
                """);
    }

    private MvcResult createPolicyDraft(
            String templateId,
            String basedOnVersionId) throws Exception {
        return perform(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions",
                        templateId)
                        .queryParam("legalEntityId", LEGAL_ENTITY)
                        .header("Idempotency-Key",
                                "wave3-concurrent-policy-draft"),
                """
                {
                  "basedOnVersionId":"%s",
                  "effectiveFrom":"2026-08-16",
                  "reason":"WAVE-3 并发校验草稿"
                }
                """.formatted(basedOnVersionId));
    }

    private MvcResult deactivatePolicy(
            String templateId,
            String versionId,
            String idempotencyKey,
            long expectedVersion) throws Exception {
        return perform(
                post(
                        "/api/v1/attendance-setup/policy-lifecycle/{templateId}/versions/{versionId}/deactivate",
                        templateId,
                        versionId)
                        .queryParam("legalEntityId", LEGAL_ENTITY)
                        .header(HttpHeaders.IF_MATCH,
                                "\"" + expectedVersion + "\"")
                        .header("Idempotency-Key", idempotencyKey),
                """
                {
                  "effectiveFrom":"2026-10-01",
                  "reason":"WAVE-3 并发停用"
                }
                """);
    }

    private List<MvcResult> concurrent(
            Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> firstReady = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return first.call();
        };
        Callable<MvcResult> secondReady = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return second.call();
        };
        try {
            Future<MvcResult> firstResult = executor.submit(firstReady);
            Future<MvcResult> secondResult = executor.submit(secondReady);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertStatuses(
            List<MvcResult> results, int firstStatus, int secondStatus) {
        List<Integer> statuses = new ArrayList<>(
                results.stream()
                        .map(result -> result.getResponse().getStatus())
                        .toList());
        Collections.sort(statuses);
        assertThat(statuses)
                .as("HTTP responses: %s", results.stream()
                        .map(result -> result.getResponse()
                                .getContentAsByteArray())
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .toList())
                .containsExactly(firstStatus, secondStatus);
    }

    private MvcResult create(String path, String idempotencyKey, String body)
            throws Exception {
        return perform(post(path).header("Idempotency-Key", idempotencyKey), body);
    }

    private MvcResult perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body) throws Exception {
        String changeReason;
        try {
            changeReason = JsonPath.read(body, "$.reason");
        } catch (RuntimeException exception) {
            changeReason = "WAVE-3 并发测试";
        }
        return mockMvc.perform(request
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .with(idempotencyIfMissing())
                        .header("X-Change-Reason", changeReason)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
            idempotencyIfMissing() {
        return request -> {
            if (request.getHeader("Idempotency-Key") == null) {
                request.addHeader(
                        "Idempotency-Key",
                        "wave3-concurrency-auto-"
                                + AUTO_IDEMPOTENCY_SEQUENCE.incrementAndGet());
            }
            return request;
        };
    }

    private String value(MvcResult result, String path) throws Exception {
        assertThat(result.getResponse().getStatus())
                .withFailMessage(result.getResponse().getContentAsString())
                .isBetween(200, 299);
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private void insertPolicy(
            String templateId,
            String code,
            String versionId,
            String parameters) {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-20T00:00:00Z"));
        jdbc.update(
                """
                INSERT INTO attendance_policy_template (
                    policy_template_id, template_code, name, description,
                    field_definitions_json, created_by, created_at
                ) VALUES (?, ?, ?, 'WAVE-3 并发受控策略', '[]',
                    ?, ?)
                """,
                templateId, code, code, ADMIN_PRINCIPAL, now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_scope (
                    scope_id, policy_template_id, legal_entity_id, row_version,
                    created_by, created_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """,
                scopeId(templateId), templateId, LEGAL_ENTITY,
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
                    'WAVE-3 并发受控策略发布',
                    '{"valid":true,"issues":[]}', '{}', ?, NULL, 1, ?, ?)
                """,
                versionId, scopeId(templateId),
                parameters, Date.valueOf("2026-08-01"),
                DIGEST, ADMIN_PRINCIPAL, now);
        jdbc.update(
                """
                INSERT INTO attendance_policy_lifecycle_event (
                    lifecycle_event_id, scope_id, scoped_version_id,
                    event_sequence, predecessor_event_id, action,
                    business_effective_from, reason, actor_id, request_id, recorded_at
                ) VALUES (?, ?, ?, 1, NULL, 'PUBLISHED', ?,
                    'WAVE-3 并发受控策略发布', ?, ?, ?)
                """,
                lifecycleId(templateId), scopeId(templateId), versionId,
                Date.valueOf("2026-08-01"), ADMIN_PRINCIPAL,
                "fixture-" + code, now);
    }

    private String scopeId(String templateId) {
        return "98" + templateId.substring(2);
    }

    private String lifecycleId(String templateId) {
        return "99" + templateId.substring(2);
    }
}

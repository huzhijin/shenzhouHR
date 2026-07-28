package com.szsemicon.hr.wave3;

import static com.szsemicon.hr.wave3.OpenApiContractDocument.list;
import static com.szsemicon.hr.wave3.OpenApiContractDocument.map;
import static com.szsemicon.hr.wave3.OpenApiContractDocument.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.interfaces.rest.AttendanceGroupController;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyController;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyLifecycleController;
import com.szsemicon.hr.attendance.interfaces.rest.ShiftCalendarController;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchDirection;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

class Wave3OpenApiContractTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete");
    private static final Set<String> WAVE3_CAPABILITIES = Set.of(
            "ATTENDANCE_SETUP:READ",
            "ATTENDANCE_SETUP:MANAGE_GROUP",
            "ATTENDANCE_SETUP:ASSIGN",
            "ATTENDANCE_SETUP:MANAGE_SHIFT",
            "ATTENDANCE_SETUP:MANAGE_CALENDAR",
            "ATTENDANCE_SETUP:MANAGE_POLICY");

    private final OpenApiContractDocument contract =
            OpenApiContractDocument.load("api/openapi.yaml");

    @Test
    void controllerAndOpenApiOperationSetsAreExactlyClosedInBothDirections() {
        Set<Route> controllerRoutes = controllerRoutes(
                AttendanceGroupController.class,
                ShiftCalendarController.class,
                AttendancePolicyController.class,
                AttendancePolicyLifecycleController.class);
        Set<Route> openApiRoutes = new LinkedHashSet<>();
        wave3Operations().forEach(operation -> openApiRoutes.add(
                new Route(operation.method().toUpperCase(), operation.path())));

        assertThat(openApiRoutes)
                .as("OpenAPI operations missing from controllers")
                .containsExactlyInAnyOrderElementsOf(controllerRoutes);
        assertThat(controllerRoutes)
                .as("Controller operations missing from OpenAPI")
                .containsExactlyInAnyOrderElementsOf(openApiRoutes);
    }

    @Test
    void parsesYamlAndResolvesEveryReference() {
        assertThat(contract.root().get("openapi")).isEqualTo("3.1.0");
        assertThat(map(contract.root().get("info")).get("version"))
                .isEqualTo("1.9.0-wave3");
        assertThat(list(contract.root().get("servers")))
                .singleElement()
                .satisfies(server -> assertThat(map(server).get("url")).isEqualTo("/api/v1"));
        assertThat(contract.paths().keySet())
                .noneMatch(path -> path.startsWith("/api/"));
        assertThat(contract.unresolvedReferences()).isEmpty();
    }

    @Test
    void everyAttendanceSetupOperationHasSecurityAuthorizationAndHttpOutcomes() {
        List<Operation> operations = wave3Operations();
        assertThat(operations)
                .as("every current W3 controller operation")
                .hasSize(54);
        assertThat(operations.stream().map(Operation::operationId))
                .doesNotHaveDuplicates();

        for (Operation operation : operations) {
            Map<String, Object> value = operation.value();
            assertThat(value.get("security"))
                    .as(operation.label() + " security")
                    .isEqualTo(List.of(Map.of("sessionCookie", List.of())));
            assertThat(value.get("x-capability"))
                    .as(operation.label() + " capability")
                    .isIn(WAVE3_CAPABILITIES);
            assertThat(value.get("x-data-scope"))
                    .as(operation.label() + " data scope")
                    .isEqualTo("ATTENDANCE_SETUP:LEGAL_ENTITY");

            Map<String, Object> responses = map(value.get("responses"));
            assertThat(responses)
                    .as(operation.label() + " authentication responses")
                    .containsKeys("401", "403");
            Map<String, Object> success = responses.entrySet().stream()
                    .filter(entry -> entry.getKey().matches("2\\d\\d"))
                    .map(Map.Entry::getValue)
                    .map(OpenApiContractDocument::map)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            operation.label() + " lacks a success response"));
            assertThat(map(success.get("headers")))
                    .as(operation.label() + " success headers")
                    .containsKey("X-Correlation-ID");
        }
    }

    @Test
    void writeOperationsExposeConsistentConcurrencyAndChangeHeaders() {
        for (Operation operation : wave3Operations()) {
            if ("get".equals(operation.method()) || readOnlyPost(operation)) {
                continue;
            }
            Set<String> parameterReferences = parameterReferences(operation.value());
            assertThat(parameterReferences)
                    .as(operation.label() + " CSRF/change reason")
                    .contains(
                            "#/components/parameters/CsrfToken",
                            "#/components/parameters/ChangeReason",
                            "#/components/parameters/IdempotencyKey");
            if (mutatesVersionedResource(operation)) {
                assertThat(parameterReferences)
                        .as(operation.label() + " optimistic concurrency")
                        .contains("#/components/parameters/IfMatch");
            }
            Map<String, Object> success = map(map(operation.value().get("responses"))
                    .entrySet().stream()
                    .filter(entry -> entry.getKey().matches("2\\d\\d"))
                    .findFirst()
                    .orElseThrow()
                    .getValue());
            assertThat(map(success.get("headers")))
                    .as(operation.label() + " replay response metadata")
                    .containsEntry(
                            "Idempotency-Replayed",
                            Map.of("$ref",
                                    "#/components/headers/IdempotencyReplayed"));
        }
    }

    @Test
    void shiftAndCalendarDeactivationContractsRequireExplicitFutureBoundary()
            throws Exception {
        Map<String, Object> versionDeactivation = object(
                "businessEffectiveFrom", "2026-08-01",
                "reason", "未来边界停用");
        assertThat(contract.validateSchema(
                "FutureDeactivationRequest", versionDeactivation)).isEmpty();

        Map<String, Object> shiftStatus = object(
                "status", "INACTIVE",
                "businessEffectiveFrom", "2026-08-01",
                "reason", "未来边界停用班次");
        assertThat(contract.validateSchema(
                "ShiftVersionStatusRequest", shiftStatus)).isEmpty();
        Map<String, Object> calendarStatus = object(
                "status", "INACTIVE",
                "businessEffectiveFrom", "2026-08-01",
                "reason", "未来边界停用日历");
        assertThat(contract.validateSchema(
                "WorkCalendarStatusRequest", calendarStatus)).isEmpty();

        versionDeactivation.remove("businessEffectiveFrom");
        shiftStatus.remove("businessEffectiveFrom");
        calendarStatus.remove("businessEffectiveFrom");
        assertThat(contract.validateSchema(
                "FutureDeactivationRequest", versionDeactivation)).isNotEmpty();
        assertThat(contract.validateSchema(
                "ShiftVersionStatusRequest", shiftStatus)).isNotEmpty();
        assertThat(contract.validateSchema(
                "WorkCalendarStatusRequest", calendarStatus)).isNotEmpty();

        shiftStatus.put("businessEffectiveFrom", "2026-08-01");
        calendarStatus.put("businessEffectiveFrom", "2026-08-01");
        for (String prohibited : List.of("DRAFT", "PUBLISHED")) {
            shiftStatus.put("status", prohibited);
            calendarStatus.put("status", prohibited);
            assertThat(contract.validateSchema(
                    "ShiftVersionStatusRequest", shiftStatus)).isNotEmpty();
            assertThat(contract.validateSchema(
                    "WorkCalendarStatusRequest", calendarStatus)).isNotEmpty();
        }

        Class<?> shiftRequestType = Class.forName(
                "com.szsemicon.hr.attendance.interfaces.rest."
                        + "ShiftCalendarDtos$ShiftVersionStatusRequest");
        Class<?> calendarRequestType = Class.forName(
                "com.szsemicon.hr.attendance.interfaces.rest."
                        + "ShiftCalendarDtos$CalendarStatusRequest");
        Class<?> shiftStatusType = Arrays.stream(
                        shiftRequestType.getRecordComponents())
                .filter(component -> component.getName().equals("status"))
                .findFirst()
                .orElseThrow()
                .getType();
        Class<?> calendarStatusType = Arrays.stream(
                        calendarRequestType.getRecordComponents())
                .filter(component -> component.getName().equals("status"))
                .findFirst()
                .orElseThrow()
                .getType();
        assertThat(Arrays.stream(shiftStatusType.getEnumConstants())
                .map(Object::toString)
                .toList()).containsExactly("INACTIVE");
        assertThat(Arrays.stream(calendarStatusType.getEnumConstants())
                .map(Object::toString)
                .toList()).containsExactly("INACTIVE");

        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        String validRequest =
                """
                {
                  "status":"INACTIVE",
                  "businessEffectiveFrom":"2026-08-01",
                  "reason":"未来边界停用"
                }
                """;
        assertThat(mapper.readValue(validRequest, shiftRequestType)).isNotNull();
        assertThat(mapper.readValue(validRequest, calendarRequestType)).isNotNull();
        for (String prohibited : List.of("DRAFT", "PUBLISHED")) {
            String invalidRequest = validRequest.replace("INACTIVE", prohibited);
            assertThatThrownBy(() -> mapper.readValue(
                    invalidRequest, shiftRequestType))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> mapper.readValue(
                    invalidRequest, calendarRequestType))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void viewAndSimulationSchemasAcceptRealDtosAndRejectInvalidInstances() {
        Map<String, Object> segment = object(
                "segmentType", "WORK",
                "startLocalTime", "20:00:00",
                "startDayOffset", 0,
                "endLocalTime", "01:00:00",
                "endDayOffset", 1);
        Map<String, Object> fixtures = object(
                "AttendanceLocationView", object(
                        "locationId", "loc-1", "legalEntityId", "le-1",
                        "code", "SHANGHAI",
                        "locationRevisionId", "location-revision-1",
                        "revisionNumber", 1, "name", "上海园区",
                        "timeZone", "Asia/Shanghai", "status", "ACTIVE",
                        "effectiveFrom", "2026-01-01", "effectiveTo", null,
                        "snapshotDigest", "c".repeat(64),
                        "rowVersion", 2L, "changeReason", "启用地点",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "AttendanceGroupView", object(
                        "groupId", "group-1", "legalEntityId", "le-1",
                        "code", "DEFAULT", "name", "默认考勤组",
                        "groupRevisionId", "group-revision-1", "revisionNumber", 2,
                        "locationId", "loc-1", "locationRevisionId", "location-revision-1",
                        "calendarId", "calendar-1", "shiftTemplateId", "shift-1",
                        "status", "ACTIVE", "effectiveFrom", "2026-01-01",
                        "effectiveTo", null, "snapshotDigest", "d".repeat(64),
                        "rowVersion", 3L,
                        "changeReason", "激活默认组",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "AttendanceGroupAssignmentView", object(
                        "assignmentId", "assignment-1", "groupId", "group-1",
                        "employeeId", "employee-1", "effectiveFrom", "2026-01-01",
                        "effectiveTo", null, "rowVersion", 1L,
                        "monthlyContextKey", "employee-1:2026-07",
                        "changeReason", "分配默认考勤组",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "ShiftTemplateView", object(
                        "shiftId", "shift-1", "legalEntityId", "le-1",
                        "locationId", "loc-1", "code", "NIGHT",
                        "name", "夜班", "status", "ACTIVE",
                        "rowVersion", 1L, "changeReason", "启用夜班",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "ShiftVersionView", object(
                        "shiftVersionId", "shift-version-1", "shiftId", "shift-1",
                        "versionNumber", 1, "status", "PUBLISHED",
                        "effectiveFrom", "2026-01-01", "effectiveTo", null,
                        "timeZone", "Asia/Shanghai",
                        "segments", List.of(segment),
                        "snapshotDigest", "a".repeat(64), "rowVersion", 1L,
                        "changeReason", "发布跨夜班次",
                        "publishedAt", "2026-07-26T10:00:00Z",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "WorkCalendarView", object(
                        "calendarId", "calendar-1", "legalEntityId", "le-1",
                        "locationId", "loc-1", "code", "CN_2026",
                        "calendarVersionId", "calendar-version-1",
                        "versionNumber", 1, "name", "2026 工作日历",
                        "calendarYear", 2026, "timeZone", "Asia/Shanghai",
                        "status", "PUBLISHED",
                        "effectiveFrom", "2026-01-01",
                        "effectiveTo", "2027-01-01",
                        "snapshotDigest", "e".repeat(64), "rowVersion", 4L,
                        "changeReason", "启用日历",
                        "updatedAt", "2026-07-26T10:00:00Z"),
                "WorkCalendarDayView", object(
                        "calendarDayId", "day-1", "calendarId", "calendar-1",
                        "calendarVersionId", "calendar-version-1",
                        "businessDate", "2026-07-26", "dayType", "WORKDAY",
                        "shiftVersionOverrideId", "shift-version-1",
                        "rowVersion", 1L,
                        "changeReason", "设置工作日"),
                "AttendancePolicyBindingView", object(
                        "bindingId", "binding-1",
                        "bindingRevisionId", "binding-revision-1",
                        "revisionNumber", 1, "legalEntityId", "le-1",
                        "policyKind", "LATE_GRACE",
                        "policyVersionId", "policy-version-1", "groupId", "group-1",
                        "groupRevisionId", "group-revision-1",
                        "effectiveFrom", "2026-01-01",
                        "effectiveTo", null, "status", "ACTIVE",
                        "snapshotDigest", "b".repeat(64), "rowVersion", 1L,
                        "changeReason", "绑定迟到宽限",
                        "updatedAt", "2026-07-26T10:00:00Z"));

        fixtures.forEach((schema, instance) -> {
            assertThat(contract.validateSchema(schema, instance))
                    .as(schema + " legal DTO")
                    .isEmpty();
            Map<String, Object> invalid = new java.util.LinkedHashMap<>(map(instance));
            invalid.put("unexpected", true);
            assertThat(contract.validateSchema(schema, invalid))
                    .as(schema + " rejects additional fields")
                    .isNotEmpty();
        });

        Map<String, Object> catalogEntry = object(
                "templateId", "template-1",
                "policyKind", "MEAL_DEDUCTION",
                "name", "晚餐扣除",
                "fields", List.of(
                        object("key", "enabled", "label", "是否启用",
                                "valueType", "BOOLEAN", "required", true),
                        object("key", "mealWindowStart", "label", "晚餐窗口开始",
                                "valueType", "LOCAL_TIME", "required", true),
                        object("key", "mealWindowEnd", "label", "晚餐窗口结束",
                                "valueType", "LOCAL_TIME", "required", true),
                        object("key", "deductionMinutes", "label", "扣除分钟",
                                "valueType", "INTEGER", "required", true),
                        object("key", "triggerMinutes", "label", "触发分钟",
                                "valueType", "INTEGER", "required", true),
                        object("key", "applicableDayTypes", "label", "适用日类型",
                                "valueType", "ENUM_LIST", "required", true)));
        assertThat(contract.validateSchema(
                "AttendancePolicyCatalogEntry", catalogEntry)).isEmpty();
        catalogEntry.remove("templateId");
        assertThat(contract.validateSchema(
                "AttendancePolicyCatalogEntry", catalogEntry)).isNotEmpty();

        Map<String, Object> policyVersion = object(
                "scopedVersionId", "policy-version-1",
                "scopeId", "scope-1",
                "templateId", "template-1",
                "legalEntityId", "legal-entity-1",
                "policyKind", "MEAL_DEDUCTION",
                "versionNumber", 2,
                "status", "VALIDATED",
                "parameters", List.of(
                        object("key", "enabled", "value", true),
                        object("key", "mealWindowStart", "value", "18:00"),
                        object("key", "mealWindowEnd", "value", "20:00"),
                        object("key", "deductionMinutes", "value", 30),
                        object("key", "triggerMinutes", "value", 240),
                        object("key", "applicableDayTypes",
                                "value", List.of("SPECIAL_WORKDAY", "WORKDAY"))),
                "effectiveFrom", "2026-09-01",
                "effectiveTo", null,
                "changeReason", "调整晚餐窗口",
                "validation", object(
                        "valid", true,
                        "issues", List.of(),
                        "validatedAt", "2026-07-26T10:01:00Z"),
                "snapshotDigest", null,
                "rollbackOfScopedVersionId", null,
                "rowVersion", 3L,
                "createdBy", "principal-1",
                "createdAt", "2026-07-26T10:00:00Z",
                "publishedAt", null,
                "updatedBy", "principal-1",
                "updatedAt", "2026-07-26T10:01:00Z",
                "deactivationEffectiveFrom", null);
        assertThat(contract.validateSchema(
                "AttendancePolicyVersionDetail", policyVersion)).isEmpty();
        policyVersion.put("status", "ROLLED_BACK");
        assertThat(contract.validateSchema(
                "AttendancePolicyVersionDetail", policyVersion)).isNotEmpty();
        policyVersion.put("status", "VALIDATED");
        policyVersion.put("unexpected", true);
        assertThat(contract.validateSchema(
                "AttendancePolicyVersionDetail", policyVersion))
                .contains("$ has additional property unexpected");

        Map<String, Object> simulationRequest = object(
                "employeeId", "employee-1",
                "businessDate", "2026-07-26",
                "correctionAsOf", "2026-07-26T12:00:00+08:00",
                "punches", List.of(object(
                        "direction", "ENTRY",
                        "instant", "2026-07-26T20:05:00+08:00",
                        "workSegmentId", "work-0",
                        "association", "SCHEDULED_WORK")));
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationRequest", simulationRequest)).isEmpty();
        Map<String, Object> invalidSimulation = new java.util.LinkedHashMap<>(
                simulationRequest);
        invalidSimulation.put("policyUsage", object(
                "naturalMonthLateGraceUses", 0));
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationRequest", invalidSimulation)).isNotEmpty();
        invalidSimulation = new java.util.LinkedHashMap<>(simulationRequest);
        invalidSimulation.put("correctionAsOf", "2026-07-26");
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationRequest", invalidSimulation)).isNotEmpty();

        Map<String, Object> simulationResponse = object(
                "policyKind", "MONTHLY_LATE_EXEMPTION",
                "status", "EXEMPTED", "policyVersionId", "policy-version-1",
                "configurationDigest", "c".repeat(64), "matched", true,
                "consumesAllowance", true, "rawLateMinutes", 15,
                "predictedMonthlyConsumption", 1,
                "usageProvenance", "OFFICIAL_USAGE_PROJECTION",
                "usageKnowledgeTime", "2026-07-26T04:00:00Z",
                "deductionMinutes", null,
                "matchedMealWindows", List.of(),
                "correctionDeadline", null, "affectedSegment", "WORK:0",
                "explanation", "本自然月首次且迟到 5 分钟",
                "writesFormalResult", false);
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationView", simulationResponse)).isEmpty();

        Map<String, Object> holidayMealResponse = object(
                "policyKind", "MEAL_DEDUCTION",
                "status", "MATCHED",
                "policyVersionId", "policy-version-holiday",
                "configurationDigest", "d".repeat(64),
                "matched", true,
                "consumesAllowance", false,
                "rawLateMinutes", null,
                "predictedMonthlyConsumption", 0,
                "usageProvenance", "OFFICIAL_USAGE_PROJECTION",
                "usageKnowledgeTime", "2026-07-26T04:00:00Z",
                "deductionMinutes", 45,
                "matchedMealWindows", List.of(object(
                        "windowId", "PUBLIC_HOLIDAY_DINNER",
                        "mealType", "DINNER",
                        "source", "PUBLIC_HOLIDAY_OVERRIDE",
                        "windowStart", "18:30:00",
                        "windowEnd", "19:15:00",
                        "deductionMinutes", 45,
                        "triggerMinutes", 180)),
                "correctionDeadline", null,
                "affectedSegment", null,
                "explanation", "法定节假日晚餐独立覆盖",
                "writesFormalResult", false);
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationView", holidayMealResponse)).isEmpty();

        simulationResponse.put("writesFormalResult", true);
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationView", simulationResponse)).isNotEmpty();
    }

    @Test
    void realSimulationRequestDtoRoundTripsAgainstTheStrictSchema() throws Exception {
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        Class<?> punchType = Class.forName(
                "com.szsemicon.hr.attendance.interfaces.rest."
                + "AttendancePolicyDtos$PunchSimulationInput");
        var punchConstructor = punchType.getDeclaredConstructors()[0];
        punchConstructor.setAccessible(true);
        Object punch = punchConstructor.newInstance(
                PunchDirection.ENTRY,
                OffsetDateTime.parse("2026-07-26T20:05:00+08:00"),
                "work-0",
                "SCHEDULED_WORK");
        Class<?> requestType = Class.forName(
                "com.szsemicon.hr.attendance.interfaces.rest."
                + "AttendancePolicyDtos$SimulationRequest");
        var requestConstructor = requestType.getDeclaredConstructors()[0];
        requestConstructor.setAccessible(true);
        Object request = requestConstructor.newInstance(
                "employee-1",
                LocalDate.parse("2026-07-26"),
                OffsetDateTime.parse("2026-07-26T12:00:00+08:00"),
                List.of(punch));

        @SuppressWarnings("unchecked")
        Map<String, Object> serialized = mapper.convertValue(request, Map.class);
        assertThat(serialized.keySet()).containsExactlyInAnyOrder(
                "employeeId", "businessDate", "correctionAsOf", "punches");
        assertThat(map(((List<?>) serialized.get("punches")).getFirst()).keySet())
                .containsExactlyInAnyOrder(
                        "direction", "instant", "workSegmentId", "association");
        assertThat(contract.validateSchema(
                "AttendancePolicySimulationRequest", serialized)).isEmpty();
        assertThatThrownBy(() -> mapper.readValue(
                """
                {"employeeId":"employee-1","businessDate":"2026-07-26",
                 "correctionAsOf":"2026-07-26T12:00:00+08:00",
                 "punches":[],"policyUsage":{"naturalMonthLateGraceUses":0}}
                """,
                requestType)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(
                """
                {"employeeId":"employee-1","businessDate":"2026-07-26",
                 "correctionAsOf":"2026-07-26","punches":[]}
                """,
                requestType)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void policyVersionContextResolvesFromVersionIdWithoutGuessedScopeParameters() {
        Operation operation = wave3Operations().stream()
                .filter(candidate -> candidate.operationId()
                        .equals("getAttendancePolicyVersionContext"))
                .findFirst()
                .orElseThrow();

        assertThat(parameterReferences(operation.value()))
                .containsExactly("#/components/parameters/VersionId");
        assertThat(operation.value())
                .containsEntry("x-capability", "ATTENDANCE_SETUP:READ")
                .containsEntry(
                        "x-data-scope",
                        "ATTENDANCE_SETUP:LEGAL_ENTITY");
        Map<String, Object> success = map(
                map(operation.value().get("responses")).get("200"));
        Map<String, Object> schema = map(map(
                map(success.get("content")).get("application/json"))
                .get("schema"));
        assertThat(schema).containsEntry(
                "$ref",
                "#/components/schemas/AttendancePolicyVersionDetail");
    }

    private List<Operation> wave3Operations() {
        List<Operation> result = new ArrayList<>();
        contract.paths().forEach((path, pathItemValue) -> {
            if (!path.startsWith("/attendance-setup/")) {
                return;
            }
            map(pathItemValue).forEach((method, operationValue) -> {
                if (HTTP_METHODS.contains(method)) {
                    Map<String, Object> operation = map(operationValue);
                    result.add(new Operation(
                            path,
                            method,
                            String.valueOf(operation.get("operationId")),
                            operation));
                }
            });
        });
        return result;
    }

    private static Set<String> parameterReferences(Map<String, Object> operation) {
        Set<String> result = new LinkedHashSet<>();
        Object rawParameters = operation.get("parameters");
        if (rawParameters instanceof List<?> parameters) {
            parameters.stream()
                    .filter(Map.class::isInstance)
                    .map(OpenApiContractDocument::map)
                    .map(parameter -> parameter.get("$ref"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(result::add);
        }
        return result;
    }

    private static boolean createsResource(Operation operation) {
        return "post".equals(operation.method())
                && (operation.path().matches(
                                "/attendance-setup/(locations|groups|shifts|calendars|policy-bindings)")
                        || operation.path().endsWith("/assignments")
                        || operation.path().endsWith("/versions")
                        || operation.path().endsWith("/rollback"));
    }

    private static boolean mutatesVersionedResource(Operation operation) {
        return !"get".equals(operation.method())
                && !operation.path().endsWith("/policy-simulations")
                && !operation.path().endsWith("/policy-impact-preview")
                && !createsResource(operation);
    }

    private static boolean readOnlyPost(Operation operation) {
        return operation.path().endsWith("/policy-simulations")
                || operation.path().endsWith("/policy-impact-preview");
    }

    @SafeVarargs
    private static Set<Route> controllerRoutes(Class<?>... controllerTypes) {
        Set<Route> routes = new LinkedHashSet<>();
        for (Class<?> controllerType : controllerTypes) {
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controllerType, RequestMapping.class);
            String basePath = typeMapping == null || typeMapping.path().length == 0
                    ? ""
                    : stripApiPrefix(typeMapping.path()[0]);
            for (Method method : controllerType.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0
                        ? new String[] {""}
                        : mapping.path();
                for (RequestMethod requestMethod : mapping.method()) {
                    Arrays.stream(paths).forEach(path -> routes.add(new Route(
                            requestMethod.name(), normalizePath(basePath + path))));
                }
            }
        }
        return routes;
    }

    private static final Pattern PATH_VARIABLE_CONSTRAINT =
            Pattern.compile("\\{([^}:]+):[^}]+}");

    private static String normalizePath(String path) {
        return PATH_VARIABLE_CONSTRAINT.matcher(path).replaceAll("{$1}");
    }

    private static String stripApiPrefix(String path) {
        return path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
    }

    private record Route(String method, String path) {
    }

    private record Operation(
            String path,
            String method,
            String operationId,
            Map<String, Object> value) {

        String label() {
            return method.toUpperCase() + " " + path;
        }
    }
}

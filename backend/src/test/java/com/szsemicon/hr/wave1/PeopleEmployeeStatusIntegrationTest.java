package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class PeopleEmployeeStatusIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String ALICE_EMPLOYEE_ID = "b0000000-0000-0000-0000-000000000001";
    private static final LocalDate TERMINATION_DATE = LocalDate.of(2026, 9, 15);

    @Test
    void laterEffectiveFromStatusChangeClosesOldActiveAndCreatesNewTerminated() throws Exception {
        var session = login(ADMIN_USERNAME, adminPassword);

        MvcResult getResult = mockMvc.perform(withSession(
                        get("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session))
                .andExpect(status().isOk())
                .andReturn();

        String etag = getResult.getResponse().getHeader("ETag");
        String responseBody = getResult.getResponse().getContentAsString();
        String employeeNumber = JsonPath.read(responseBody, "$.employeeNumber");
        String displayName = JsonPath.read(responseBody, "$.displayName");
        String currentStatus = JsonPath.read(responseBody, "$.status");
        String effectiveFromStr = JsonPath.read(responseBody, "$.effectiveFrom");

        assertThat(currentStatus).isEqualTo("ACTIVE");

        String updateJson = String.format("""
                {
                    "employeeNumber": "%s",
                    "displayName": "%s",
                    "status": "TERMINATED",
                    "effectiveFrom": "%s",
                    "effectiveTo": null,
                    "reason": "employee terminated"
                }
                """, employeeNumber, displayName, TERMINATION_DATE);

        mockMvc.perform(withSession(
                        patch("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "test-termination-key")
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.effectiveFrom").value(TERMINATION_DATE.toString()));

        List<Map<String, Object>> versions = jdbc.queryForList(
                """
                SELECT employee_version_id, status, effective_from, effective_to, row_version
                FROM employee_version
                WHERE employee_id = ?
                ORDER BY effective_from, row_version
                """,
                ALICE_EMPLOYEE_ID);

        assertThat(versions).hasSizeGreaterThanOrEqualTo(2);

        Map<String, Object> oldActive = versions.get(0);
        assertThat(oldActive.get("status")).isEqualTo("ACTIVE");
        assertThat(((java.sql.Date) oldActive.get("effective_from")).toLocalDate())
                .isEqualTo(LocalDate.parse(effectiveFromStr));
        assertThat(((java.sql.Date) oldActive.get("effective_to")).toLocalDate())
                .isEqualTo(TERMINATION_DATE);

        Map<String, Object> newTerminated = versions.get(versions.size() - 1);
        assertThat(newTerminated.get("status")).isEqualTo("TERMINATED");
        assertThat(((java.sql.Date) newTerminated.get("effective_from")).toLocalDate())
                .isEqualTo(TERMINATION_DATE);
        assertThat(newTerminated.get("effective_to")).isNull();

        Map<String, Object> identity = jdbc.queryForMap(
                """
                SELECT employment_status FROM employee WHERE employee_id = ?
                """,
                ALICE_EMPLOYEE_ID);
        assertThat(identity.get("employment_status")).isEqualTo("TERMINATED");
    }

    @Test
    void sameDayStatusChangeRejectsWithConflict() throws Exception {
        var session = login(ADMIN_USERNAME, adminPassword);

        MvcResult getResult = mockMvc.perform(withSession(
                        get("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session))
                .andExpect(status().isOk())
                .andReturn();

        String etag = getResult.getResponse().getHeader("ETag");
        String responseBody = getResult.getResponse().getContentAsString();
        String employeeNumber = JsonPath.read(responseBody, "$.employeeNumber");
        String displayName = JsonPath.read(responseBody, "$.displayName");
        String effectiveFromStr = JsonPath.read(responseBody, "$.effectiveFrom");

        String updateJson = String.format("""
                {
                    "employeeNumber": "%s",
                    "displayName": "%s",
                    "status": "TERMINATED",
                    "effectiveFrom": "%s",
                    "effectiveTo": null,
                    "reason": "attempt same-day status change"
                }
                """, employeeNumber, displayName, effectiveFromStr);

        mockMvc.perform(withSession(
                        patch("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "test-same-day-conflict-key")
                        .content(updateJson))
                .andExpect(status().isConflict());

        Map<String, Object> identity = jdbc.queryForMap(
                """
                SELECT employment_status FROM employee WHERE employee_id = ?
                """,
                ALICE_EMPLOYEE_ID);
        assertThat(identity.get("employment_status")).isEqualTo("ACTIVE");
    }

    @Test
    void sameDayUnchangedStatusNameCorrectionUpdatesInPlace() throws Exception {
        var session = login(ADMIN_USERNAME, adminPassword);

        MvcResult getResult = mockMvc.perform(withSession(
                        get("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session))
                .andExpect(status().isOk())
                .andReturn();

        String etag = getResult.getResponse().getHeader("ETag");
        String responseBody = getResult.getResponse().getContentAsString();
        String employeeNumber = JsonPath.read(responseBody, "$.employeeNumber");
        String effectiveFromStr = JsonPath.read(responseBody, "$.effectiveFrom");
        String originalVersionId = JsonPath.read(responseBody, "$.employeeVersionId");

        String correctedName = "Alice Corrected";
        String updateJson = String.format("""
                {
                    "employeeNumber": "%s",
                    "displayName": "%s",
                    "status": "ACTIVE",
                    "effectiveFrom": "%s",
                    "effectiveTo": null,
                    "reason": "correct display name"
                }
                """, employeeNumber, correctedName, effectiveFromStr);

        mockMvc.perform(withSession(
                        patch("/api/v1/employees/" + ALICE_EMPLOYEE_ID), session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "test-name-correction-key")
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(correctedName))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        List<Map<String, Object>> versions = jdbc.queryForList(
                """
                SELECT employee_version_id, display_name, status, effective_from, effective_to, row_version
                FROM employee_version
                WHERE employee_id = ?
                """,
                ALICE_EMPLOYEE_ID);

        assertThat(versions).hasSize(1);
        Map<String, Object> corrected = versions.get(0);
        assertThat(corrected.get("employee_version_id")).isEqualTo(originalVersionId);
        assertThat(corrected.get("display_name")).isEqualTo(correctedName);
        assertThat(corrected.get("status")).isEqualTo("ACTIVE");
        assertThat(((java.sql.Date) corrected.get("effective_from")).toLocalDate())
                .isEqualTo(LocalDate.parse(effectiveFromStr));
        assertThat(corrected.get("effective_to")).isNull();

        Map<String, Object> identity = jdbc.queryForMap(
                """
                SELECT display_name, employment_status FROM employee WHERE employee_id = ?
                """,
                ALICE_EMPLOYEE_ID);
        assertThat(identity.get("display_name")).isEqualTo(correctedName);
        assertThat(identity.get("employment_status")).isEqualTo("ACTIVE");
    }
}

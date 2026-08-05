package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles(profiles = "production", inheritProfiles = false)
@TestPropertySource(properties = {
        "spring.datasource.url="
                + "jdbc:h2:mem:attendance-location-production-guard;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql",
        "spring.sql.init.data-locations=classpath:db/test-data.sql",
        "shenzhouhr.security.account-provisioning.pepper="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "shenzhouhr.security.account-provisioning.key-id=production-guard-v1",
        "shenzhouhr.reporting.export-worker-enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttendanceLocationProductionGuardIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String COMPANY =
            "30000000-0000-0000-0000-000000000001";

    @Test
    void production_create_location_is_fixed_catalog_conflict_without_writes()
            throws Exception {
        Map<String, Long> before = guardedBusinessTableCounts();
        long auditCountBefore = countRows("SELECT COUNT(*) FROM audit_event");

        mockMvc.perform(post("/api/v1/attendance-setup/locations")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", "production-location-create-blocked")
                        .header("X-Change-Reason", "验证生产环境共享地点目录不可新增")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId":"%s",
                                  "code":"PRODUCTION_BLOCKED",
                                  "name":"生产环境不应新增的地点",
                                  "timeZone":"Asia/Shanghai",
                                  "effectiveFrom":"2026-08-05",
                                  "effectiveTo":null,
                                  "reason":"验证生产环境共享地点目录不可新增"
                                }
                                """.formatted(COMPANY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("SHARED_LOCATION_CATALOG_FIXED"));

        assertThat(guardedBusinessTableCounts()).isEqualTo(before);
        assertThat(countRows("SELECT COUNT(*) FROM audit_event"))
                .isEqualTo(auditCountBefore + 1);
        assertThat(countRows(
                """
                SELECT COUNT(*)
                FROM audit_event
                WHERE action_code = 'ATTENDANCE_SETUP_POST_FAILURE'
                  AND result_code = 'FAILURE'
                  AND reason_code = 'SHARED_LOCATION_CATALOG_FIXED'
                """))
                .isEqualTo(1);
    }

    private Map<String, Long> guardedBusinessTableCounts() {
        return Map.of(
                "attendance_setup_idempotency",
                countRows("SELECT COUNT(*) FROM attendance_setup_idempotency"),
                "shared_location",
                countRows("SELECT COUNT(*) FROM shared_location"),
                "location",
                countRows("SELECT COUNT(*) FROM location"));
    }

    private long countRows(String query) {
        Long count = jdbc.queryForObject(query, Long.class);
        return count == null ? 0 : count;
    }
}

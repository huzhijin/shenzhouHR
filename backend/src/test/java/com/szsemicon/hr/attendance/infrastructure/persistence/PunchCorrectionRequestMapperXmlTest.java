package com.szsemicon.hr.attendance.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PunchCorrectionRequestMapperXmlTest {

    @Test
    void quotaCountsPendingAndApprovedButNotRejectedRequests()
            throws Exception {
        String xml = Files.readString(Path.of(
                "src/main/resources/mappers/PunchCorrectionRequestMapper.xml"));

        assertThat(xml)
                .contains("FROM punch_correction_request")
                .contains("request_month = #{requestMonth}")
                .contains("status IN ('PENDING', 'APPROVED')")
                .doesNotContain("status IN ('PENDING', 'APPROVED', 'REJECTED')");
    }

    @Test
    void approvalIsAtomicAndEligibilityUsesConfirmedRoleAndCalendarFacts()
            throws Exception {
        String xml = Files.readString(Path.of(
                "src/main/resources/mappers/PunchCorrectionRequestMapper.xml"));

        assertThat(xml)
                .contains("FOR UPDATE")
                .contains("AND status = 'PENDING'")
                .contains("role.role_code = 'EXECUTIVE'")
                .contains("calendar_day.day_type IN ('WORKDAY', 'SPECIAL_WORKDAY')");
    }
}

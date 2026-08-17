package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class PunchCorrectionCalculationMapperContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    @Test
    void calculationReadsOnlyApprovedVisibleCompanyScopedCorrections()
            throws Exception {
        String mapper = normalized(Files.readString(MAPPER));

        assertThat(mapper).contains(
                "correction.status = 'approved'",
                "correction.reviewed_at &lt;= #{dataasof}",
                "correction.business_date &gt;= #{periodstart}",
                "correction.business_date &lt; #{periodendexclusive}",
                "employee.company_id = #{companyid}",
                "correction.punch_side as punchside");
    }

    @Test
    void executivePunchExemptionIsCompanyScopedAndEffectiveDated()
            throws Exception {
        String mapper = normalized(Files.readString(MAPPER));

        assertThat(mapper).contains(
                "role.role_code = 'executive'",
                "employee.company_id = #{companyid}",
                "assignment.valid_from &lt; #{windowendexclusive}",
                "assignment.valid_to &gt; #{windowstart}");
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}

package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ExemptionOutingCalculationMapperContractTest {

    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    @Test
    void productionCalculationReadsApprovedDocumentsButNeverTrips()
            throws Exception {
        String mapper = normalized(Files.readString(CALCULATION_MAPPER));

        assertThat(mapper).contains(
                "where oa.version_rank = 1 and oa.source_status = 'approved'",
                "and oa.document_type &lt;&gt; 'trip'",
                "normalized.interval_start &lt; #{windowendexclusive}",
                "normalized.interval_end &gt; #{windowstart}");
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}

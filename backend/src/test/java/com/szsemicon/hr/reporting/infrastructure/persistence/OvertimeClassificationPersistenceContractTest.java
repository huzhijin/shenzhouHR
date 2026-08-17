package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class OvertimeClassificationPersistenceContractTest {

    private static final Path V48 = Path.of(
            "src/main/resources/db/migration/"
                    + "V48__business_rules_alignment_schema.sql");
    private static final Path WRITE_MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendanceReportProjectionWriteMapper.xml");
    private static final Path READ_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportMapper.xml");
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendanceReportCalculationMapper.xml");
    private static final Path EVIDENCE_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceEvidenceMapper.xml");

    @Test
    void v48_and_projection_mappers_preserve_all_four_overtime_metrics()
            throws Exception {
        String migration = normalized(Files.readString(V48));
        String writer = normalized(Files.readString(WRITE_MAPPER));
        String reader = normalized(Files.readString(READ_MAPPER));

        assertThat(migration)
                .contains(
                        "paid_overtime_minutes bigint unsigned not null default 0",
                        "compensatory_overtime_minutes bigint unsigned not null default 0",
                        "voluntary_overtime_minutes bigint unsigned not null default 0",
                        "total_overtime_minutes bigint unsigned not null default 0",
                        "total_overtime_minutes = paid_overtime_minutes + compensatory_overtime_minutes + voluntary_overtime_minutes");
        assertThat(writer)
                .contains(
                        "paid_overtime_minutes",
                        "compensatory_overtime_minutes",
                        "voluntary_overtime_minutes",
                        "total_overtime_minutes",
                        "#{paidovertimeminutes}",
                        "#{compensatoryovertimeminutes}",
                        "#{voluntaryovertimeminutes}",
                        "#{totalovertimeminutes}");
        assertThat(reader)
                .contains(
                        "fact.paid_overtime_minutes",
                        "fact.compensatory_overtime_minutes",
                        "fact.voluntary_overtime_minutes",
                        "fact.total_overtime_minutes");
    }

    @Test
    void calculation_query_reads_only_approved_classified_overtime()
            throws Exception {
        String mapper = normalized(Files.readString(CALCULATION_MAPPER));

        assertThat(mapper)
                .contains(
                        "left join oa_attendance_document_context context",
                        "context.overtime_type as overtimetype",
                        "row_number() over ( partition by document.attendance_source_id, document.source_business_key order by document.knowledge_rank desc, document.created_at desc, document.oa_attendance_document_id desc ) as version_rank",
                        "where oa.version_rank = 1 and oa.source_status = 'approved'",
                        "oa.source_status = 'approved'",
                        "context.activation_decision = 'activated'",
                        "context.overtime_type is not null");
    }

    @Test
    void ingestion_persists_classification_under_real_published_contract()
            throws Exception {
        String mapper = normalized(Files.readString(EVIDENCE_MAPPER));

        assertThat(mapper).contains(
                "from oa_runtime_contract_revision contract",
                "contract.attendance_source_id = #{sourceid}",
                "contract.contract_status = 'published'",
                "order by contract.revision_number desc",
                "insert into oa_attendance_document_context",
                "oa_runtime_contract_revision_id",
                "activation_decision",
                "raw_status_value",
                "overtime_treatment",
                "overtime_type",
                "authorized_context_json",
                "context_digest",
                "#{overtimetype}");
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}

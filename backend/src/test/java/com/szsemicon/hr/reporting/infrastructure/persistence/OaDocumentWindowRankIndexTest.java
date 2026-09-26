package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OaDocumentWindowRankIndexTest {

    private static final Path V67 = Path.of(
            "src/main/resources/db/migration/"
                    + "V67__oa_document_window_rank_index.sql");

    @Test
    void indexMatchesWindowPartitionAndRejectsStatusLeading()
            throws Exception {
        String sql = Files.readString(V67);
        String ddl = sql.substring(sql.indexOf("ALTER TABLE"));
        assertThat(ddl)
                .contains("ix_oa_document_source_key_created")
                .contains("attendance_source_id")
                .contains("source_business_key")
                .contains("created_at")
                .contains("knowledge_rank")
                .contains("oa_attendance_document_id")
                .contains("ALGORITHM=INPLACE")
                .contains("LOCK=NONE")
                .doesNotContain("source_status")
                .doesNotContain("document_type");
    }
}

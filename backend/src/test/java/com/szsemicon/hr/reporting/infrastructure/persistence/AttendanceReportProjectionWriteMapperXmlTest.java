package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AttendanceReportProjectionWriteMapperXmlTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportProjectionWriteMapper.xml";
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "AttendanceReportProjectionWriteMapper.";

    @Test
    void parsesAndRegistersTheCompleteImmutablePublicationFlow()
            throws Exception {
        Configuration configuration = new Configuration();

        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(
                            input,
                            configuration,
                            RESOURCE,
                            configuration.getSqlFragments())
                    .parse();
        }

        assertThat(configuration.getMappedStatementNames())
                .contains(
                        NAMESPACE + "lockLegalEntity",
                        NAMESPACE + "findByDigest",
                        NAMESPACE + "findLatestPublished",
                        NAMESPACE + "insertDraft",
                        NAMESPACE + "insertDailyFact",
                        NAMESPACE + "insertExceptionFact",
                        NAMESPACE + "insertOaDocumentFact",
                        NAMESPACE + "insertTimeAccountFact",
                        NAMESPACE + "markPublished");
    }
}

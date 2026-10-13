package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AttendanceReportExportMapperXmlTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportExportMapper.xml";
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "AttendanceReportExportMapper.";

    @Test
    void parsesAndRegistersAllExportStateTransitions() throws Exception {
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
                        NAMESPACE + "insertJob",
                        NAMESPACE + "insertArtifact",
                        NAMESPACE + "findOwnedJob",
                        NAMESPACE + "findOwnedReadyArtifact",
                        NAMESPACE + "findNextQueuedForUpdate",
                        NAMESPACE + "markBuilding",
                        NAMESPACE + "markReady",
                        NAMESPACE + "markFailed",
                        NAMESPACE + "purgeExpired");
    }
}

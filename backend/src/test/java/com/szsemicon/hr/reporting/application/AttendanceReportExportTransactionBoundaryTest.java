package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.YearMonth;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AttendanceReportExportTransactionBoundaryTest {

    @Test
    void passwordConfirmedEntryPointsDoNotStartAnOuterTransaction()
            throws Exception {
        assertThat(AttendanceReportExportService.class
                        .getMethod(
                                "create",
                                ReportType.class,
                                YearMonth.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class)
                        .getAnnotation(Transactional.class))
                .isNull();
        assertThat(AttendanceReportExportService.class
                        .getMethod(
                                "download",
                                String.class,
                                String.class)
                        .getAnnotation(Transactional.class))
                .isNull();
    }

    @Test
    void postReauthenticationWorkUsesANewReadCommittedTransaction()
            throws Exception {
        Transactional transactional =
                AttendanceReportExportTransactions.class
                        .getMethod(
                                "readCommitted", Supplier.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.isolation())
                .isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void finalDownloadAuthorizationAndArtifactReadAreSerializable()
            throws Exception {
        Transactional transactional =
                AttendanceReportExportTransactions.class
                        .getMethod("serialized", Supplier.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.isolation())
                .isEqualTo(Isolation.SERIALIZABLE);
    }
}

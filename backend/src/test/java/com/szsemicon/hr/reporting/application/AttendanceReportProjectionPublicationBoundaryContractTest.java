package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

class AttendanceReportProjectionPublicationBoundaryContractTest {

    private static final Path REPORTING_ROOT =
            Path.of("src/main/java/com/szsemicon/hr/reporting");

    @Test
    void publisherIsAnInjectableInternalUseCase() {
        assertThat(AttendanceReportProjectionPublicationUseCase.class
                        .isAssignableFrom(
                                AttendanceReportProjectionPublisher.class))
                .isTrue();
        assertThat(AttendanceReportProjectionPublisher.class
                        .isAnnotationPresent(Service.class))
                .isTrue();
    }

    @Test
    void noReportingRestControllerAcceptsPublicationFacts()
            throws Exception {
        String restSources;
        try (var paths = Files.walk(REPORTING_ROOT.resolve("interfaces/rest"))) {
            restSources = paths.filter(Files::isRegularFile)
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase(Locale.ROOT);
        }

        assertThat(restSources)
                .doesNotContain(
                        "publishcommand",
                        "verifiedprojectionmetadata",
                        "verifiedcalculatedfacts",
                        "verifiedoadocumentfact",
                        "verifiedtimeaccountfact",
                        "attendancereportprojectionpublicationusecase");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

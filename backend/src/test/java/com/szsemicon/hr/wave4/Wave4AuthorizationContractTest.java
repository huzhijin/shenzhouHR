package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Wave4AuthorizationContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Set<String> EXPECTED = Set.of(
            "ATTENDANCE_SOURCE:READ",
            "ATTENDANCE_SOURCE:CONFIGURE",
            "ATTENDANCE_SOURCE:RUN",
            "ATTENDANCE_SOURCE:RETRY",
            "ATTENDANCE_SOURCE:QUARANTINE_READ",
            "ATTENDANCE_PUNCH_IMPORT:READ",
            "ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD",
            "ATTENDANCE_PUNCH_IMPORT:UPLOAD",
            "ATTENDANCE_PUNCH_IMPORT:PRECHECK",
            "ATTENDANCE_PUNCH_IMPORT:PUBLISH",
            "ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH",
            "ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE",
            "ATTENDANCE_PUNCH_IMPORT:RAW_FILE_READ",
            "ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ",
            "ATTENDANCE_PUNCH_IMPORT:ERROR_REPORT_DOWNLOAD",
            "ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW",
            "ATTENDANCE_PUNCH_IMPORT:RECALCULATE");

    @Test
    void exactCatalogIsClosedAcrossSqlJavaOpenApiAndTypeScript() throws Exception {
        String migrations = read("backend/src/main/resources/db/migration/"
                        + "V8__attendance_source_and_evidence.sql")
                + read("backend/src/main/resources/db/migration/"
                        + "V9__attendance_punch_import.sql");
        String javaCatalog = read("backend/src/main/java/com/szsemicon/hr/"
                + "authorization/domain/CapabilityCodes.java");
        String openApi = read("api/openapi.yaml");
        String demoSession = read("frontend/src/features/session/demoSession.ts");

        var matcher = Pattern.compile(
                        "'(ATTENDANCE_(?:SOURCE|PUNCH_IMPORT):[A-Z_]+)'")
                .matcher(migrations);
        Set<String> sqlCatalog = new LinkedHashSet<>();
        while (matcher.find()) {
            sqlCatalog.add(matcher.group(1));
        }

        assertThat(sqlCatalog).containsExactlyInAnyOrderElementsOf(EXPECTED);
        EXPECTED.forEach(code -> {
            assertThat(javaCatalog).as("java " + code).contains("\"" + code + "\"");
            assertThat(openApi).as("openapi " + code).contains(code);
            assertThat(demoSession).as("typescript " + code).contains(code);
        });
    }

    @Test
    void w4MigrationsDoNotGrantCapabilitiesToSystemAdminOrAnyRole()
            throws Exception {
        String migrations = read("backend/src/main/resources/db/migration/"
                        + "V8__attendance_source_and_evidence.sql")
                + read("backend/src/main/resources/db/migration/"
                        + "V9__attendance_punch_import.sql");

        assertThat(migrations)
                .doesNotContain("INSERT INTO auth_role_capability")
                .doesNotContain("SYSTEM_ADMIN");
    }

    private static String read(String relative) {
        try {
            return Files.readString(ROOT.resolve(relative));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

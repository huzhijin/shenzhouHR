package com.szsemicon.hr.identityaccess.infrastructure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialProductionAdminCommandTest {

    private static final String EXPECTED_CATALOG = """
            SZSZ\t上海昇州半导体科技有限公司
            SZJN\t上海晟州聚能半导体科技有限公司
            SZSC\t江苏神州半导体科技股份有限公司
            SZXY\t江苏芯越半导体科技有限公司
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheExactSignedFourCompanyCatalog() throws Exception {
        Path catalog = temporaryDirectory.resolve("initial-companies.tsv");
        Files.writeString(catalog, EXPECTED_CATALOG, StandardCharsets.UTF_8);

        List<InitialProductionAdminCommand.CompanyCatalogEntry> companies =
                InitialProductionAdminCommand.loadCompanyCatalog(catalog);

        assertEquals(List.of("SZSZ", "SZJN", "SZSC", "SZXY"),
                companies.stream()
                        .map(InitialProductionAdminCommand.CompanyCatalogEntry::code)
                        .toList());
        assertEquals(4, companies.size());
    }

    @Test
    void rejectsCatalogDriftInsteadOfPartiallyInitializing() throws Exception {
        Path catalog = temporaryDirectory.resolve("initial-companies.tsv");
        Files.writeString(
                catalog,
                EXPECTED_CATALOG.replace("江苏芯越半导体科技有限公司", "被篡改的公司"),
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> InitialProductionAdminCommand.loadCompanyCatalog(catalog));
    }
}

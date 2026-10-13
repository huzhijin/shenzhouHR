package com.szsemicon.hr.referencedata.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReferenceDataOpenApiContractTest {

    private static final Path OPEN_API =
            Path.of("../api/openapi.yaml").toAbsolutePath().normalize();

    @Test
    void activeCompanyDirectoryIsDocumentedWithManagementReadBoundary()
            throws Exception {
        String contract = Files.readString(OPEN_API);
        String operation = between(
                contract,
                "  /reference-data/companies:",
                "  /access/accounts:");
        String schema = between(
                contract,
                "    CompanyReference:",
                "    MenuItem:");

        assertThat(operation)
                .contains("operationId: listReferenceCompanies")
                .contains("x-capabilities-any:")
                .contains(
                        "ACCOUNT:READ",
                        "ROLE:READ",
                        "MASTER_DATA:READ",
                        "ORGANIZATION:READ",
                        "EMPLOYEE:READ",
                        "ATTENDANCE_SETUP:READ")
                .contains(
                        "$ref: '#/components/schemas/CompanyReference'")
                .contains(
                        "$ref: '#/components/responses/AuthenticationRequired'")
                .contains("$ref: '#/components/responses/AccessDenied'");
        assertThat(schema)
                .contains("required: [companyId, code, name]")
                .contains("companyId:")
                .contains("code:")
                .contains("name:")
                .doesNotContain("status:");
    }

    private static String between(
            String value,
            String start,
            String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return value.substring(startIndex, endIndex);
    }
}

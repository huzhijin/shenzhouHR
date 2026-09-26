package com.szsemicon.hr.wave2;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.infrastructure.excel.PoiPeopleWorkbookGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ExportedPeopleWorkbookContractTest {

    @Test
    void customer_people_export_is_accepted_by_the_production_parser() throws Exception {
        String exportDirectory = System.getProperty("shenzhouhr.people-export-dir");
        Assumptions.assumeTrue(
                exportDirectory != null && !exportDirectory.isBlank(),
                "set -Dshenzhouhr.people-export-dir to validate a generated customer export");

        var gateway = new PoiPeopleWorkbookGateway(50_000, 8);
        assertWorkbook(
                gateway,
                Path.of(exportDirectory, "01-组织期初.xlsx"),
                TemplateType.ORGANIZATION,
                List.of(
                        new MappingEntry("组织编码", "organizationCode"),
                        new MappingEntry("组织名称", "name"),
                        new MappingEntry("上级组织编码", "parentOrganizationCode"),
                        new MappingEntry("组织类型", "organizationType"),
                        new MappingEntry("生效日期", "effectiveFrom")),
                130);
        assertWorkbook(
                gateway,
                Path.of(exportDirectory, "02-员工期初.xlsx"),
                TemplateType.EMPLOYEE,
                List.of(
                        new MappingEntry("员工编号", "employeeNumber"),
                        new MappingEntry("外部精确员工ID", "externalEmployeeId"),
                        new MappingEntry("姓名", "displayName"),
                        new MappingEntry("生效日期", "effectiveFrom")),
                571);
        assertWorkbook(
                gateway,
                Path.of(exportDirectory, "03-任职期初.xlsx"),
                TemplateType.EMPLOYMENT,
                List.of(
                        new MappingEntry("员工编号", "employeeNumber"),
                        new MappingEntry("组织编码", "organizationCode"),
                        new MappingEntry("任职开始日", "startDate"),
                        new MappingEntry("业务离职日", "terminationDate")),
                571);
    }

    private static void assertWorkbook(
            PoiPeopleWorkbookGateway gateway,
            Path workbook,
            TemplateType type,
            List<MappingEntry> mapping,
            int expectedRows) throws Exception {
        assertThat(Files.isRegularFile(workbook)).isTrue();
        var parsed = gateway.parse(Files.readAllBytes(workbook), type, mapping);
        assertThat(parsed.rows()).hasSize(expectedRows);
    }
}

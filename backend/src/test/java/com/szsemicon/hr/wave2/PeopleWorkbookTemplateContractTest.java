package com.szsemicon.hr.wave2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.people.application.PeopleWorkbookException;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.infrastructure.excel.PoiPeopleWorkbookGateway;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PeopleWorkbookTemplateContractTest {

    @Test
    void versioned_template_bytes_and_sha_are_stable_across_gateway_instances()
            throws Exception {
        var firstGateway = new PoiPeopleWorkbookGateway(50_000, 8);
        var first = firstGateway.getTemplate(TemplateType.EMPLOYEE, "1.0.0");

        Thread.sleep(Duration.ofSeconds(1));

        var secondGateway = new PoiPeopleWorkbookGateway(50_000, 8);
        var second = secondGateway.getTemplate(TemplateType.EMPLOYEE, "1.0.0");

        assertThat(second.metadata().sha256()).isEqualTo(first.metadata().sha256());
        assertThat(second.content()).containsExactly(first.content());
    }

    @Test
    void generated_template_is_accepted_by_the_upload_parser() {
        var gateway = new PoiPeopleWorkbookGateway(50_000, 8);
        var template = gateway.getTemplate(TemplateType.EMPLOYEE, "1.0.0");

        assertThat(gateway.parse(template.content(), TemplateType.EMPLOYEE, List.of()).rows())
                .isEmpty();
    }

    @Test
    void workbook_with_an_external_hyperlink_is_rejected() throws Exception {
        var gateway = new PoiPeopleWorkbookGateway(50_000, 8);
        var template = gateway.getTemplate(TemplateType.EMPLOYEE, "1.0.0");
        byte[] workbookWithExternalRelationship;

        try (var workbook = new XSSFWorkbook(
                        new ByteArrayInputStream(template.content()));
                var output = new ByteArrayOutputStream()) {
            var hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.URL);
            hyperlink.setAddress("https://example.invalid/external-data.xlsx");
            workbook.getSheetAt(0).getRow(0).getCell(0).setHyperlink(hyperlink);
            workbook.write(output);
            workbookWithExternalRelationship = output.toByteArray();
        }

        assertThatThrownBy(() -> gateway.parse(
                        workbookWithExternalRelationship,
                        TemplateType.EMPLOYEE,
                        List.of()))
                .isInstanceOf(PeopleWorkbookException.class)
                .hasMessageContaining("外部链接");
    }
}

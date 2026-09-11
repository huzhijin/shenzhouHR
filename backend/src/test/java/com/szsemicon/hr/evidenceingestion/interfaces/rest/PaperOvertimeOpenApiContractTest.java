package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperOvertimeOpenApiContractTest {

    private static final String DOCUMENT = readDocument();

    @Test
    void paperOvertimeRecognizeCandidatesSaveAndListAreExplicit() {
        assertThat(DOCUMENT)
                .contains("operationId: listPaperOvertime")
                .contains("operationId: recognizePaperOvertime")
                .contains("operationId: listPaperOvertimeCandidates")
                .contains("operationId: savePaperOvertime")
                .contains("x-capability: PAPER_OVERTIME:MANAGE")
                .contains("PaperOvertimeRecognizeView")
                .contains("PaperOvertimeSaveRequest")
                .contains("PaperOvertimeSaveView")
                .contains("PaperOvertimeLineList")
                .doesNotContain("forceSave")
                .doesNotContain("SHENZHOUHR_OCR_BAIDU_API_KEY:");
    }

    private static String readDocument() {
        try {
            return Files.readString(
                    Path.of("../api/openapi.yaml")
                            .toAbsolutePath()
                            .normalize());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

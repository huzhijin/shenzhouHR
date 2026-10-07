package com.szsemicon.hr.punchimport.application;

import java.util.List;
import java.util.Map;

public interface PunchWorkbookGateway {

    TemplateWorkbook currentTemplate();

    ParsedWorkbook parse(byte[] content, String filename, String contentType);

    record TemplateWorkbook(
            String templateVersion,
            String fieldContractDigest,
            String fileSha256,
            String filename,
            byte[] content) {

        public TemplateWorkbook {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record ParsedWorkbook(
            String templateVersion,
            String fieldContractDigest,
            String workbookKind,
            List<Map<String, String>> punchRows,
            List<Map<String, String>> deviceMappingRows) {

        public ParsedWorkbook {
            workbookKind = workbookKind == null ? "OFFICIAL_TEMPLATE" : workbookKind;
            punchRows = punchRows.stream().map(Map::copyOf).toList();
            deviceMappingRows = deviceMappingRows.stream().map(Map::copyOf).toList();
        }
    }
}

package com.szsemicon.hr.punchimport.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.punchimport.application.PunchWorkbookGateway;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-punch-imports")
public class PunchTemplateController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PunchWorkbookGateway workbookGateway;

    public PunchTemplateController(PunchWorkbookGateway workbookGateway) {
        this.workbookGateway = workbookGateway;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_TEMPLATE_DOWNLOAD + "')")
    ResponseEntity<byte[]> downloadTemplate() {
        var template = workbookGateway.currentTemplate();
        String filename = java.net.URLEncoder.encode(
                template.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(template.content().length)
                .cacheControl(CacheControl.noStore())
                .eTag(template.fileSha256())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .header("X-Template-Version", template.templateVersion())
                .header("X-Field-Contract-SHA256", template.fieldContractDigest())
                .body(template.content());
    }
}

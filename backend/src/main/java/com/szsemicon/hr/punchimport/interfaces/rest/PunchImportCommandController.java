package com.szsemicon.hr.punchimport.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.punchimport.application.PunchImportCommandService;
import com.szsemicon.hr.punchimport.application.PunchImportReadModels.BatchView;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.StrongEtag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/attendance-punch-imports")
public class PunchImportCommandController {

    private final PunchImportCommandService service;

    public PunchImportCommandController(PunchImportCommandService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_UPLOAD + "')")
    ResponseEntity<BatchView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") Metadata metadata,
            @RequestHeader("X-Change-Reason") String reason,
            @RequestHeader("Idempotency-Key") String requestId) throws Exception {
        BatchView batch = service.upload(
                metadata.companyId(),
                metadata.sourceId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                ChangeReasonHeader.decodeAndValidate(reason),
                requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(batch.rowVersion()))
                .body(batch);
    }

    @PostMapping("/{batchId}/precheck")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PRECHECK + "')")
    ResponseEntity<BatchView> precheck(
            @PathVariable String batchId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Change-Reason") String reason,
            @RequestHeader("Idempotency-Key") String requestId) {
        ChangeReasonHeader.decodeAndValidate(reason);
        BatchView batch = service.precheck(
                batchId,
                StrongEtag.parseVersion(ifMatch),
                requestId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(batch.rowVersion()))
                .body(batch);
    }

    @PostMapping("/{batchId}/publish")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_PUBLISH + "')")
    ResponseEntity<BatchView> publish(
            @PathVariable String batchId,
            @RequestBody PublishRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-Change-Reason") String reason,
            @RequestHeader("Idempotency-Key") String requestId) {
        BatchView batch = service.publish(
                batchId,
                StrongEtag.parseVersion(ifMatch),
                request.precheckToken(),
                request.mode(),
                request.confirmation(),
                ChangeReasonHeader.decodeAndValidate(reason),
                requestId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(batch.rowVersion()))
                .body(batch);
    }

    public record Metadata(String companyId, String sourceId) {
    }

    public record PublishRequest(
            String precheckToken,
            String mode,
            String confirmation) {
    }
}

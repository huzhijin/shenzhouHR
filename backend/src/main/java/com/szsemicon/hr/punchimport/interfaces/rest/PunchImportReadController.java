package com.szsemicon.hr.punchimport.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.punchimport.application.PunchImportReadModels;
import com.szsemicon.hr.punchimport.application.PunchImportReadService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-punch-imports")
public class PunchImportReadController {

    private final PunchImportReadService service;

    public PunchImportReadController(PunchImportReadService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ + "')")
    ResponseEntity<PunchImportReadModels.Page<PunchImportReadModels.BatchView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(service.list(page, size));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ + "')")
    ResponseEntity<PunchImportReadModels.BatchView> find(
            @PathVariable String batchId) {
        return noStore(service.find(batchId));
    }

    @GetMapping("/{batchId}/errors")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ + "')")
    ResponseEntity<PunchImportReadModels.Page<PunchImportReadModels.IssueView>> issues(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(service.issues(batchId, page, size));
    }

    @GetMapping("/{batchId}/rows")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_RAW_ROW_READ + "')")
    ResponseEntity<PunchImportReadModels.Page<PunchImportReadModels.RowView>> rows(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(service.rows(batchId, page, size));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadModels;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AttendanceSourceReadController {

    private final AttendanceSourceReadService service;

    public AttendanceSourceReadController(AttendanceSourceReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/attendance-sources")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<AttendanceSourceReadModels.Page<AttendanceSourceReadModels.SourceView>>
            listSources(
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return noStore(service.listSources(page, size));
    }

    @GetMapping("/api/v1/attendance-source-jobs")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<AttendanceSourceReadModels.Page<AttendanceSourceReadModels.JobView>>
            listJobs(
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return noStore(service.listJobs(page, size));
    }

    @GetMapping("/api/v1/attendance-sources/{sourceId}/documents")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<AttendanceSourceReadModels.Page<AttendanceSourceReadModels.OaDocumentView>>
            listDocuments(
                    @PathVariable String sourceId,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size) {
        return noStore(service.listOaDocuments(sourceId, page, size));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

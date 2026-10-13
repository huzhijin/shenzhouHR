package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.EmployeeCandidate;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.RecognizeResult;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.SaveCommand;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.SaveResult;
import com.szsemicon.hr.evidenceingestion.application.PaperOvertimeApplicationService.SavedLine;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/paper-overtime")
public class PaperOvertimeController {

    private final PaperOvertimeApplicationService service;

    public PaperOvertimeController(PaperOvertimeApplicationService service) {
        this.service = service;
    }

    @PostMapping(path = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + CapabilityCodes.PAPER_OVERTIME_MANAGE + "')")
    ResponseEntity<RecognizeResult> recognize(
            @RequestParam String companyId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.recognize(companyId, files));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.PAPER_OVERTIME_MANAGE + "')")
    ResponseEntity<List<EmployeeCandidate>> candidates(
            @RequestParam String companyId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) LocalDate asOf) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.suggest(companyId, name, department, asOf));
    }

    @PostMapping("/save")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.PAPER_OVERTIME_MANAGE + "')")
    ResponseEntity<SaveResult> save(@RequestBody SaveCommand command) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.save(command));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + CapabilityCodes.PAPER_OVERTIME_MANAGE + "')")
    ResponseEntity<List<SavedLine>> list(
            @RequestParam String companyId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.list(companyId, fromDate, toDate));
    }
}

package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.people.application.RosterImportService;
import com.szsemicon.hr.people.application.RosterImportService.BatchDetail;
import com.szsemicon.hr.people.application.roster.RosterNames;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Diff;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Issue;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Summary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/roster-imports")
public class RosterImportController {

    private final RosterImportService service;

    public RosterImportController(RosterImportService service) {
        this.service = service;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:TEMPLATE_DOWNLOAD')")
    ResponseEntity<byte[]> template() {
        byte[] content = service.template();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + RosterNames.fileName() + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:UPLOAD')")
    ResponseEntity<BatchView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("reason") String reason) throws Exception {
        BatchDetail detail = service.upload(file.getOriginalFilename(), file.getBytes(), reason);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BatchView.from(detail));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:READ')")
    ResponseEntity<BatchView> get(@PathVariable String batchId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BatchView.from(service.get(batchId)));
    }

    @PostMapping("/{batchId}/publish")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:PUBLISH')")
    ResponseEntity<BatchView> publish(
            @PathVariable String batchId,
            @RequestParam("reason") @NotBlank @Size(min = 2, max = 500) String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BatchView.from(service.publish(batchId, reason, idempotencyKey)));
    }

    public record BatchView(
            String batchId,
            String status,
            String reason,
            String originalFileName,
            String fileSha256,
            Summary summary,
            List<DiffView> diffs,
            List<IssueView> issues,
            long rowVersion,
            Instant createdAt,
            Instant publishedAt) {

        static BatchView from(BatchDetail detail) {
            return new BatchView(
                    detail.batchId(),
                    detail.status(),
                    detail.reason(),
                    detail.originalFileName(),
                    detail.fileSha256(),
                    detail.precheck().summary(),
                    detail.precheck().diffs().stream().map(DiffView::from).toList(),
                    detail.precheck().issues().stream().map(IssueView::from).toList(),
                    detail.rowVersion(),
                    detail.createdAt(),
                    detail.publishedAt());
        }
    }

    public record DiffView(
            int rowNumber,
            String category,
            String matchedResourceId,
            Map<String, Object> sourceValues,
            Map<String, Object> currentValues,
            Map<String, Object> proposedValues) {

        static DiffView from(Diff diff) {
            return new DiffView(
                    diff.rowNumber(),
                    diff.category(),
                    diff.matchedResourceId(),
                    diff.sourceValues(),
                    diff.currentValues(),
                    diff.proposedValues());
        }
    }

    public record IssueView(
            int rowNumber,
            String field,
            String code,
            String message,
            String severity) {

        static IssueView from(Issue issue) {
            return new IssueView(
                    issue.rowNumber(),
                    issue.field(),
                    issue.code(),
                    issue.message(),
                    issue.severity());
        }
    }
}

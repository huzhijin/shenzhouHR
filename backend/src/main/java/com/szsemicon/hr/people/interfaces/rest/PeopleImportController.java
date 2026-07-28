package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.people.application.PeopleCommands.CreateImportBatch;
import com.szsemicon.hr.people.application.PeopleCommands.PublishImport;
import com.szsemicon.hr.people.application.PeopleCommands.ReplaceMapping;
import com.szsemicon.hr.people.application.PeopleCommands.RollbackImport;
import com.szsemicon.hr.people.application.PeopleImportService;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.BatchPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.BatchView;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.CreateRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.DiffPage;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.FileView;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.IssuePage;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.MappingRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.PublicationView;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.PublishRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.ReasonRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.RollbackRequest;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.RollbackView;
import com.szsemicon.hr.people.interfaces.rest.PeopleImportDtos.TemplatePage;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/people-imports")
public class PeopleImportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PeopleImportService service;

    public PeopleImportController(PeopleImportService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:TEMPLATE_DOWNLOAD')")
    ResponseEntity<TemplatePage> listTemplates(
            @RequestParam(required = false) TemplateType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleImportDtos.templatePage(service.listTemplates(type, page, size)));
    }

    @GetMapping("/templates/{templateType}/versions/{templateVersion}")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:TEMPLATE_DOWNLOAD')")
    ResponseEntity<byte[]> downloadTemplate(
            @PathVariable TemplateType templateType,
            @PathVariable String templateVersion) {
        var workbook = service.downloadTemplate(templateType, templateVersion);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .eTag(workbook.metadata().sha256())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + workbook.metadata().fileName() + "\"")
                .body(workbook.content());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:READ')")
    ResponseEntity<BatchPage> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return noStore(PeopleImportDtos.batchPage(service.listBatches(page, size, status)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:CREATE')")
    ResponseEntity<BatchView> createBatch(
            @Valid @RequestBody CreateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        ImportBatch batch = service.createBatch(
                new CreateImportBatch(
                        request.legalEntityId(), request.templateType(),
                        request.templateVersion(), request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(batch.rowVersion()))
                .body(PeopleImportDtos.batch(batch, null));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:READ')")
    ResponseEntity<BatchView> getBatch(@PathVariable String batchId) {
        var detail = service.getBatchDetail(batchId);
        ImportBatch batch = detail.batch();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(batch.rowVersion()))
                .body(PeopleImportDtos.batch(batch, detail.publication()));
    }

    @PutMapping(path = "/{batchId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:UPLOAD')")
    ResponseEntity<FileView> upload(
            @PathVariable String batchId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) throws Exception {
        var result = service.upload(
                batchId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.batch().rowVersion()))
                .body(PeopleImportDtos.file(result.file()));
    }

    @PutMapping("/{batchId}/mapping")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:MAP')")
    ResponseEntity<BatchView> replaceMapping(
            @PathVariable String batchId,
            @Valid @RequestBody MappingRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var mapping = request.entries().stream()
                .map(entry -> new MappingEntry(entry.sourceColumn(), entry.targetField()))
                .toList();
        ImportBatch batch = service.replaceMapping(
                batchId,
                new ReplaceMapping(mapping, request.reason()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return versioned(batch);
    }

    @PostMapping("/{batchId}/precheck")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:PRECHECK')")
    ResponseEntity<BatchView> precheck(
            @PathVariable String batchId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        ImportBatch batch = service.precheck(
                batchId, IfMatchVersion.parse(ifMatch), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(batch.rowVersion()))
                .body(PeopleImportDtos.batch(batch, null));
    }

    @GetMapping("/{batchId}/diff")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:READ')")
    ResponseEntity<DiffPage> listDiff(
            @PathVariable String batchId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ImportBatch batch = service.getBatch(batchId);
        return noStore(PeopleImportDtos.diffPage(
                service.listDiffs(batchId, category, page, size), batch));
    }

    @GetMapping("/{batchId}/errors")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:READ')")
    ResponseEntity<IssuePage> listErrors(
            @PathVariable String batchId,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(PeopleImportDtos.issuePage(
                service.listIssues(batchId, severity, page, size)));
    }

    @GetMapping("/{batchId}/error-report")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:ERROR_REPORT_DOWNLOAD')")
    ResponseEntity<byte[]> errorReport(@PathVariable String batchId) {
        byte[] report = service.errorReport(batchId);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .eTag(sha256Etag(report))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"people-import-errors-" + batchId + ".xlsx\"")
                .body(report);
    }

    @PostMapping("/{batchId}/publish")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:PUBLISH')")
    ResponseEntity<PublicationView> publish(
            @PathVariable String batchId,
            @Valid @RequestBody PublishRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.publish(
                batchId,
                new PublishImport(
                        request.reason(), request.confirmedFileSha256(),
                        request.confirmedPrecheckVersion()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.batch().rowVersion()))
                .body(PeopleImportDtos.publication(result.publication()));
    }

    @PostMapping("/{batchId}/void")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:VOID')")
    ResponseEntity<BatchView> voidDraft(
            @PathVariable String batchId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return versioned(service.voidDraft(
                batchId, request.reason(), IfMatchVersion.parse(ifMatch), idempotencyKey));
    }

    @PostMapping("/{batchId}/rollback")
    @PreAuthorize("hasAuthority('PEOPLE_IMPORT:ROLLBACK')")
    ResponseEntity<RollbackView> rollback(
            @PathVariable String batchId,
            @Valid @RequestBody RollbackRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = service.rollback(
                batchId,
                new RollbackImport(request.reason(), request.confirmedPublicationId()),
                IfMatchVersion.parse(ifMatch),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(result.batch().rowVersion()))
                .body(PeopleImportDtos.rollback(result.rollback()));
    }

    private ResponseEntity<BatchView> versioned(ImportBatch batch) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.etag(batch.rowVersion()))
                .body(PeopleImportDtos.batch(batch, null));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String sha256Etag(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}

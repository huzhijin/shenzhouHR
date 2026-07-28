package com.szsemicon.hr.people.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleCommands.CreateImportBatch;
import com.szsemicon.hr.people.application.PeopleCommands.PublishImport;
import com.szsemicon.hr.people.application.PeopleCommands.ReplaceMapping;
import com.szsemicon.hr.people.application.PeopleCommands.RollbackImport;
import com.szsemicon.hr.people.application.PeopleWorkbookGateway.TemplateWorkbook;
import com.szsemicon.hr.people.domain.PeopleModels.BatchStatus;
import com.szsemicon.hr.people.domain.PeopleModels.DiffCategory;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.IdempotencyRecord;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.ImportDiff;
import com.szsemicon.hr.people.domain.PeopleModels.ImportFile;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.IssueSeverity;
import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.domain.PeopleModels.PrecheckSummary;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.Publication;
import com.szsemicon.hr.people.domain.PeopleModels.Rollback;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateField;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateVersion;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PeopleImportService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final PeopleRepository repository;
    private final PeopleWorkbookGateway workbookGateway;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long maxFileBytes;

    public PeopleImportService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            PeopleRepository repository,
            PeopleWorkbookGateway workbookGateway,
            AuditService auditService,
            SecurityTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${shenzhouhr.people-import.max-file-bytes:20971520}") long maxFileBytes) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.workbookGateway = workbookGateway;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxFileBytes = maxFileBytes;
    }

    @Transactional(readOnly = true)
    public PeoplePage<TemplateVersion> listTemplates(TemplateType type, int page, int size) {
        validatePage(page, size);
        capabilityService.require(CapabilityCodes.PEOPLE_IMPORT_TEMPLATE_DOWNLOAD);
        List<TemplateVersion> all = workbookGateway.listTemplates(type);
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PeoplePage<>(all.subList(from, to), all.size(), page, size);
    }

    @Transactional(readOnly = true)
    public TemplateWorkbook downloadTemplate(TemplateType type, String version) {
        capabilityService.require(CapabilityCodes.PEOPLE_IMPORT_TEMPLATE_DOWNLOAD);
        TemplateWorkbook workbook = workbookGateway.getTemplate(type, version);
        if (workbook == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return workbook;
    }

    @Transactional
    public ImportBatch createBatch(
            CreateImportBatch command, long expectedVersion, String idempotencyKey) {
        requireExpected(expectedVersion, 0);
        requireReason(command.reason());
        requireText(command.legalEntityId(), "legalEntityId", 36);
        requireIdempotencyKey(idempotencyKey);
        if (workbookGateway.getTemplate(command.templateType(), command.templateVersion()) == null) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "模板版本不存在");
        }
        requireLegalEntity(CapabilityCodes.PEOPLE_IMPORT_CREATE, command.legalEntityId());
        String actor = principalProvider.currentPrincipalId();
        String digest = digest(command);
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_CREATE", idempotencyKey, digest);
        if (existing != null) {
            return loadBatch(existing.resourceId());
        }
        Instant now = clock.instant();
        String batchId = UUID.randomUUID().toString();
        ImportBatch batch = new ImportBatch(
                batchId,
                command.legalEntityId(),
                command.templateType(),
                command.templateVersion(),
                BatchStatus.DRAFT,
                command.reason().trim(),
                null,
                List.of(),
                emptySummary(),
                null,
                0,
                actor,
                now,
                actor,
                now,
                null,
                null,
                null,
                null);
        repository.createBatch(batch);
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_CREATE",
                idempotencyKey, digest, batchId, null, now);
        auditService.record(
                actor, "PEOPLE_IMPORT_DRAFT_CREATED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", command.reason(), null, digest(batch));
        return batch;
    }

    @Transactional(readOnly = true)
    public PeoplePage<ImportBatch> listBatches(
            int page, int size, String status) {
        validatePage(page, size);
        capabilityService.require(CapabilityCodes.PEOPLE_IMPORT_READ);
        if (status != null) {
            parseBatchStatus(status);
        }
        String actor = principalProvider.currentPrincipalId();
        Instant now = clock.instant();
        return new PeoplePage<>(
                repository.listBatches(
                        actor, CapabilityCodes.PEOPLE_IMPORT_READ, status,
                        size, page * size, now),
                repository.countBatches(
                        actor, CapabilityCodes.PEOPLE_IMPORT_READ, status, now),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public ImportBatch getBatch(String batchId) {
        return requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_READ);
    }

    @Transactional(readOnly = true)
    public BatchDetail getBatchDetail(String batchId) {
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_READ);
        return new BatchDetail(batch, publicationFor(batch));
    }

    @Transactional
    public UploadResult upload(
            String batchId,
            String originalFileName,
            String mediaType,
            byte[] content,
            long expectedVersion,
            String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_UPLOAD);
        validateUpload(originalFileName, mediaType, content);
        String actor = principalProvider.currentPrincipalId();
        String contentHash = digest(content);
        String requestDigest = digest(List.of(batchId, originalFileName, mediaType, contentHash));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_UPLOAD", idempotencyKey, requestDigest);
        if (existing != null) {
            ImportBatch reloaded = loadBatch(batchId);
            return new UploadResult(reloaded.file(), reloaded);
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (batch.status() != BatchStatus.DRAFT || batch.file() != null) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "仅空白草稿可上传一个不可变源文件");
        }
        try {
            workbookGateway.parse(content, batch.templateType(), List.of());
        } catch (PeopleWorkbookException exception) {
            throw problem(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_PEOPLE_IMPORT_FILE",
                    exception.getMessage());
        }
        Instant now = clock.instant();
        ImportFile file = new ImportFile(
                UUID.randomUUID().toString(),
                batchId,
                originalFileName,
                com.szsemicon.hr.people.domain.PeopleModels.XLSX_MEDIA_TYPE,
                content.length,
                contentHash,
                content.clone(),
                actor,
                now);
        repository.saveFile(file, expectedVersion, actor, now);
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_UPLOAD",
                idempotencyKey, requestDigest, file.fileId(), null, now);
        auditService.record(
                actor, "PEOPLE_IMPORT_FILE_UPLOADED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", "SOURCE_FILE_STORED_IMMUTABLY", null, contentHash);
        return new UploadResult(file, loadBatch(batchId));
    }

    @Transactional
    public ImportBatch replaceMapping(
            String batchId,
            ReplaceMapping command,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(command.reason());
        requireIdempotencyKey(idempotencyKey);
        if (command.entries() == null || command.entries().isEmpty()) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "字段映射不能为空");
        }
        validateMapping(command.entries());
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_MAP);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(batchId, command));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_MAP", idempotencyKey, requestDigest);
        if (existing != null) {
            return loadBatch(batchId);
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (!(batch.status() == BatchStatus.DRAFT
                || batch.status() == BatchStatus.VALIDATION_FAILED
                || batch.status() == BatchStatus.AWAITING_CONFIRMATION)) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "当前批次状态不允许修改映射");
        }
        Instant now = clock.instant();
        if (!repository.saveBatchMapping(
                batchId, expectedVersion, writeJson(command.entries()), actor, now)) {
            throw stale();
        }
        repository.replacePrecheck(batchId, List.of(), List.of());
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_MAP",
                idempotencyKey, requestDigest, batchId, null, now);
        ImportBatch updated = loadBatch(batchId);
        auditService.record(
                actor, "PEOPLE_IMPORT_MAPPING_CHANGED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", command.reason(), digest(batch.mapping()), digest(updated.mapping()));
        return updated;
    }

    @Transactional
    public ImportBatch precheck(
            String batchId, long expectedVersion, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_PRECHECK);
        if (batch.fileSha256() == null) {
            throw conflict("PEOPLE_IMPORT_FILE_REQUIRED", "预检前必须上传源文件");
        }
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(
                batchId, batch.fileSha256(), batch.mapping(), expectedVersion));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_PRECHECK", idempotencyKey, requestDigest);
        if (existing != null) {
            return loadBatch(batchId);
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (batch.mapping().isEmpty()) {
            throw conflict("PEOPLE_IMPORT_MAPPING_REQUIRED", "预检前必须保存字段映射");
        }
        if (!(batch.status() == BatchStatus.DRAFT
                || batch.status() == BatchStatus.VALIDATION_FAILED
                || batch.status() == BatchStatus.AWAITING_CONFIRMATION)) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "当前批次状态不允许预检");
        }
        assertRequiredMapping(batch.templateType(), batch.mapping());
        Instant now = clock.instant();
        if (!repository.updateBatchState(
                batchId, expectedVersion, BatchStatus.VALIDATING.name(), actor, now,
                batch.precheckVersion(),
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount(),
                null, null, null)) {
            throw stale();
        }
        List<Map<String, Object>> rows;
        try {
            rows = workbookGateway.parse(
                    batch.file().content(), batch.templateType(), batch.mapping()).rows();
        } catch (PeopleWorkbookException exception) {
            throw problem(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_PEOPLE_IMPORT_FILE",
                    exception.getMessage());
        }
        PrecheckResult result = precheckRows(batch, rows);
        repository.replacePrecheck(batchId, result.diffs(), result.issues());
        long precheckVersion = batch.precheckVersion() == null
                ? 1
                : Math.addExact(batch.precheckVersion(), 1);
        BatchStatus finalStatus = result.summary().blockingIssueCount() > 0
                ? BatchStatus.VALIDATION_FAILED
                : BatchStatus.AWAITING_CONFIRMATION;
        if (!repository.updateBatchState(
                batchId, expectedVersion + 1, finalStatus.name(), actor, now,
                precheckVersion,
                result.summary().added(), result.summary().updated(),
                result.summary().unchanged(), result.summary().conflict(),
                result.summary().error(), result.summary().blockingIssueCount(),
                null, null, null)) {
            throw stale();
        }
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_PRECHECK",
                idempotencyKey, requestDigest, batchId, null, now);
        ImportBatch updated = loadBatch(batchId);
        auditService.record(
                actor, "PEOPLE_IMPORT_PRECHECKED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", "PRECHECK_VERSION_" + precheckVersion,
                digest(batch), digest(updated));
        return updated;
    }

    @Transactional(readOnly = true)
    public PeoplePage<ImportDiff> listDiffs(
            String batchId, String category, int page, int size) {
        validatePage(page, size);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_READ);
        if (batch.precheckVersion() == null) {
            throw conflict("PEOPLE_IMPORT_PRECHECK_REQUIRED", "尚无可查询的预检差异");
        }
        if (category != null) {
            DiffCategory.valueOf(category);
        }
        return new PeoplePage<>(
                repository.listDiffs(batchId, category, size, page * size),
                repository.countDiffs(batchId, category),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public PeoplePage<ImportIssue> listIssues(
            String batchId, String severity, int page, int size) {
        validatePage(page, size);
        requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_READ);
        if (severity != null) {
            IssueSeverity.valueOf(severity);
        }
        return new PeoplePage<>(
                repository.listIssues(batchId, severity, size, page * size),
                repository.countIssues(batchId, severity),
                page,
                size);
    }

    @Transactional
    public byte[] errorReport(String batchId) {
        ImportBatch batch = requireBatch(
                batchId, CapabilityCodes.PEOPLE_IMPORT_ERROR_REPORT_DOWNLOAD);
        if (batch.precheckVersion() == null) {
            throw conflict("PEOPLE_IMPORT_PRECHECK_REQUIRED", "预检后才可下载错误报告");
        }
        byte[] report = workbookGateway.createErrorReport(
                repository.listIssues(batchId, null, Integer.MAX_VALUE, 0));
        auditService.record(
                principalProvider.currentPrincipalId(),
                "PEOPLE_IMPORT_ERROR_REPORT_DOWNLOADED",
                "PEOPLE_IMPORT",
                batchId,
                "SUCCESS",
                "PRECHECK_VERSION_" + batch.precheckVersion(),
                null,
                digest(report));
        return report;
    }

    @Transactional
    public PublishResult publish(
            String batchId,
            PublishImport command,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(command.reason());
        requireIdempotencyKey(idempotencyKey);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_PUBLISH);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(batchId, command));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_PUBLISH", idempotencyKey, requestDigest);
        if (existing != null) {
            Publication publication = repository.findPublicationByBatch(batchId)
                    .or(() -> repository.findPublicationByFileHash(
                            batch.legalEntityId(),
                            batch.templateType().name(),
                            batch.templateVersion(),
                            batch.fileSha256()))
                    .orElseThrow(() -> conflict(
                            "PEOPLE_IMPORT_INVALID_STATE",
                            "幂等发布结果不可用"));
            return new PublishResult(
                    deduplicated(publication, publication.publicationId()),
                    loadBatch(batchId));
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (batch.precheckVersion() == null
                || batch.status() == BatchStatus.DRAFT
                || batch.status() == BatchStatus.VALIDATING
                || batch.status() == BatchStatus.VALIDATION_FAILED) {
            throw conflict("PEOPLE_IMPORT_PRECHECK_REQUIRED", "发布前必须完成无阻断预检");
        }
        if (batch.status() == BatchStatus.PUBLISHED) {
            Publication existingPublication = repository.findPublicationByBatch(batchId)
                    .orElseThrow(() -> conflict(
                            "PEOPLE_IMPORT_ALREADY_PUBLISHED", "批次已经发布"));
            return new PublishResult(
                    deduplicated(existingPublication, existingPublication.publicationId()),
                    batch);
        }
        if (batch.status() != BatchStatus.AWAITING_CONFIRMATION
                && batch.status() != BatchStatus.PUBLISH_FAILED) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "当前批次状态不允许发布");
        }
        if (!Objects.equals(batch.fileSha256(), command.confirmedFileSha256())
                || batch.precheckVersion() != command.confirmedPrecheckVersion()) {
            throw conflict("PEOPLE_IMPORT_PRECHECK_STALE", "文件哈希或预检版本已变化");
        }
        if (batch.summary().blockingIssueCount() > 0) {
            throw conflict("PEOPLE_IMPORT_BLOCKING_ERRORS", "存在阻断错误，禁止发布");
        }
        Instant now = clock.instant();
        repository.lockLegalEntity(batch.legalEntityId());
        Publication hashDuplicate = repository
                .findPublicationByFileHash(
                        batch.legalEntityId(),
                        batch.templateType().name(),
                        batch.templateVersion(),
                        command.confirmedFileSha256())
                .orElse(null);
        if (hashDuplicate != null) {
            if (!repository.updateBatchState(
                    batchId, expectedVersion, BatchStatus.PUBLISHED.name(), actor, now,
                    batch.precheckVersion(),
                    batch.summary().added(), batch.summary().updated(),
                    batch.summary().unchanged(), batch.summary().conflict(),
                    batch.summary().error(), batch.summary().blockingIssueCount(),
                    now, null, hashDuplicate.publicationId())) {
                throw stale();
            }
            repository.saveIdempotency(
                    UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_PUBLISH",
                    idempotencyKey, requestDigest, hashDuplicate.publicationId(), null, now);
            auditService.record(
                    actor, "PEOPLE_IMPORT_PUBLISHED", "PEOPLE_IMPORT", batchId,
                    "DEDUPLICATED", command.reason(), null, hashDuplicate.snapshotDigest());
            return new PublishResult(
                    deduplicated(hashDuplicate, hashDuplicate.publicationId()),
                    loadBatch(batchId));
        }
        if (!repository.updateBatchState(
                batchId, expectedVersion, BatchStatus.PUBLISHING.name(), actor, now,
                batch.precheckVersion(),
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount(),
                null, null, null)) {
            throw stale();
        }
        List<ImportDiff> diffs = repository.listDiffs(batchId, null, Integer.MAX_VALUE, 0);
        assertPrecheckStillCurrent(batch, diffs);
        List<ImportDiff> publicationDiffs = orderDiffsForPublication(diffs);
        String snapshotJson = writeJson(Map.of(
                "batchId", batchId,
                "fileSha256", batch.fileSha256(),
                "precheckVersion", batch.precheckVersion(),
                "items", publicationDiffs));
        String snapshotDigest = digest(snapshotJson);
        List<String> localVersionIds = applyDiffs(batch, publicationDiffs, actor, now);
        if (!repository.updateBatchState(
                batchId, expectedVersion + 1, BatchStatus.PUBLISHED.name(), actor, now,
                batch.precheckVersion(),
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount(),
                now, null, null)) {
            throw stale();
        }
        Publication publication = new Publication(
                UUID.randomUUID().toString(),
                batchId,
                batch.legalEntityId(),
                batch.templateType(),
                batch.templateVersion(),
                batch.fileSha256(),
                snapshotDigest,
                localVersionIds,
                false,
                null,
                actor,
                now,
                snapshotJson);
        repository.savePublication(publication, idempotencyKey, writeJson(localVersionIds));
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_PUBLISH",
                idempotencyKey, requestDigest, publication.publicationId(), null, now);
        auditService.record(
                actor, "PEOPLE_IMPORT_PUBLISHED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", command.reason(), null, snapshotDigest);
        return new PublishResult(publication, loadBatch(batchId));
    }

    @Transactional
    public ImportBatch voidDraft(
            String batchId,
            String reason,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(reason);
        requireIdempotencyKey(idempotencyKey);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_VOID);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(batchId, reason));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_VOID", idempotencyKey, requestDigest);
        if (existing != null) {
            return loadBatch(batchId);
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (!(batch.status() == BatchStatus.DRAFT
                || batch.status() == BatchStatus.VALIDATION_FAILED
                || batch.status() == BatchStatus.AWAITING_CONFIRMATION)) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "仅未发布草稿可作废");
        }
        Instant now = clock.instant();
        if (!repository.updateBatchState(
                batchId, expectedVersion, BatchStatus.VOIDED.name(), actor, now,
                batch.precheckVersion(),
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount(),
                null, now, null)) {
            throw stale();
        }
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_VOID",
                idempotencyKey, requestDigest, batchId, null, now);
        auditService.record(
                actor, "PEOPLE_IMPORT_DRAFT_VOIDED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", reason, digest(batch), digest("VOIDED"));
        return loadBatch(batchId);
    }

    @Transactional
    public RollbackResult rollback(
            String batchId,
            RollbackImport command,
            long expectedVersion,
            String idempotencyKey) {
        requireReason(command.reason());
        requireIdempotencyKey(idempotencyKey);
        ImportBatch batch = requireBatch(batchId, CapabilityCodes.PEOPLE_IMPORT_ROLLBACK);
        String actor = principalProvider.currentPrincipalId();
        String requestDigest = digest(List.of(batchId, command));
        IdempotencyRecord existing = existingIdempotency(
                actor, "PEOPLE_IMPORT_ROLLBACK", idempotencyKey, requestDigest);
        Publication publication = repository.findPublicationByBatch(batchId)
                .orElseThrow(() -> conflict(
                        "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                        "发布快照不可用"));
        if (existing != null) {
            Rollback replay = repository.findRollbackByPublication(publication.publicationId())
                    .orElseThrow(() -> conflict(
                            "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                            "撤销结果不可用"));
            return new RollbackResult(replay, loadBatch(batchId));
        }
        requireVersion(batch.rowVersion(), expectedVersion);
        if (batch.status() != BatchStatus.PUBLISHED) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "仅已发布批次可受控撤销");
        }
        if (!publication.publicationId().equals(command.confirmedPublicationId())) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                    "确认的发布快照与当前批次不一致");
        }
        if (repository.findRollbackByPublication(publication.publicationId()).isPresent()) {
            throw conflict("PEOPLE_IMPORT_INVALID_STATE", "发布批次已经撤销");
        }
        repository.lockLegalEntity(batch.legalEntityId());
        lockPublicationEmployees(publication);
        Set<String> producedIds = new HashSet<>(publication.localVersionIds());
        List<String> unownedReferences = repository.findDownstreamReferenceIds(batchId).stream()
                .filter(reference -> !producedIds.contains(reference))
                .toList();
        if (!unownedReferences.isEmpty()) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES",
                    "已发布数据存在下游引用，只能前向更正");
        }
        if (repository.hasLaterVersions(batchId, publication.publishedAt())) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_NOT_LATEST",
                    "存在后续本地版本，只能前向更正");
        }
        Instant now = clock.instant();
        List<String> restoredVersionIds = restoreSnapshot(
                publication, batch, actor, command.reason(), now);
        if (restoredVersionIds.isEmpty()) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                    "发布快照中没有可恢复的本地版本");
        }
        Rollback rollback = new Rollback(
                UUID.randomUUID().toString(),
                batchId,
                publication.publicationId(),
                publication.snapshotDigest(),
                restoredVersionIds,
                actor,
                now);
        if (!repository.updateBatchState(
                batchId, expectedVersion, BatchStatus.PUBLISHED.name(), actor, now,
                batch.precheckVersion(),
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount(),
                batch.publishedAt(), null, batch.duplicateOfPublicationId())) {
            throw stale();
        }
        repository.saveRollback(
                rollback, idempotencyKey, command.reason(), writeJson(restoredVersionIds));
        repository.saveIdempotency(
                UUID.randomUUID().toString(), actor, "PEOPLE_IMPORT_ROLLBACK",
                idempotencyKey, requestDigest, rollback.rollbackId(), null, now);
        auditService.record(
                actor, "PEOPLE_IMPORT_ROLLBACK_REQUESTED", "PEOPLE_IMPORT", batchId,
                "SUCCESS", command.reason(), publication.snapshotDigest(),
                digest(restoredVersionIds));
        return new RollbackResult(rollback, loadBatch(batchId));
    }

    private void lockPublicationEmployees(Publication publication) {
        Set<String> employeeIds = new java.util.TreeSet<>();
        for (String versionId : publication.localVersionIds()) {
            repository.findEmployeeVersion(versionId)
                    .map(EmployeeVersion::employeeId)
                    .ifPresent(employeeIds::add);
            repository.findEmploymentVersion(versionId)
                    .map(EmploymentPeriod::employeeId)
                    .ifPresent(employeeIds::add);
            repository.findPriorServiceRecord(versionId)
                    .map(PriorServiceRecord::employeeId)
                    .ifPresent(employeeIds::add);
        }
        employeeIds.forEach(repository::lockEmployee);
    }

    private PrecheckResult precheckRows(
            ImportBatch batch, List<Map<String, Object>> rows) {
        List<ImportDiff> diffs = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        BatchValidationContext validationContext =
                buildBatchValidationContext(batch, rows);
        if (rows.isEmpty()) {
            ImportIssue emptyIssue = issue(
                    batch.batchId(), 1, null, "REQUIRED_FIELD_MISSING",
                    "数据工作表没有可导入的数据行", IssueSeverity.BLOCKING, List.of());
            issues.add(emptyIssue);
            diffs.add(new ImportDiff(
                    UUID.randomUUID().toString(),
                    batch.batchId(),
                    1,
                    batch.templateType(),
                    DiffCategory.ERROR,
                    null,
                    Map.of(),
                    null,
                    Map.of()));
        }
        for (Map<String, Object> row : rows) {
            int rowNumber = Integer.parseInt(row.get("_rowNumber").toString());
            List<ImportIssue> rowIssues = validateRequiredFields(
                    batch.batchId(), batch.templateType(), rowNumber, row);
            rowIssues.addAll(validationContext.issuesByRow()
                    .getOrDefault(rowNumber, List.of()));
            PrecheckMatch match = rowIssues.isEmpty()
                    ? matchRow(batch, rowNumber, row, validationContext)
                    : new PrecheckMatch(DiffCategory.ERROR, null, null, stripMetadata(row), List.of());
            rowIssues.addAll(match.issues());
            issues.addAll(rowIssues);
            DiffCategory category = rowIssues.stream()
                    .anyMatch(issue -> issue.severity() == IssueSeverity.BLOCKING)
                    ? rowIssues.stream().anyMatch(issue ->
                            "EMPLOYEE_MATCH_AMBIGUOUS".equals(issue.code())
                                    || "EMPLOYMENT_PERIOD_OVERLAP".equals(issue.code()))
                            ? DiffCategory.CONFLICT
                            : DiffCategory.ERROR
                    : match.category();
            diffs.add(new ImportDiff(
                    UUID.randomUUID().toString(),
                    batch.batchId(),
                    rowNumber,
                    batch.templateType(),
                    category,
                    match.resourceId(),
                    stripMetadata(row),
                    match.current(),
                    match.proposed()));
        }
        PrecheckSummary summary = new PrecheckSummary(
                count(diffs, DiffCategory.ADDED),
                count(diffs, DiffCategory.UPDATED),
                count(diffs, DiffCategory.UNCHANGED),
                count(diffs, DiffCategory.CONFLICT),
                count(diffs, DiffCategory.ERROR),
                (int) issues.stream()
                        .filter(issue -> issue.severity() == IssueSeverity.BLOCKING)
                        .count());
        return new PrecheckResult(List.copyOf(diffs), List.copyOf(issues), summary);
    }

    private PrecheckMatch matchRow(
            ImportBatch batch,
            int rowNumber,
            Map<String, Object> row,
            BatchValidationContext validationContext) {
        Map<String, Object> proposed = stripMetadata(row);
        return switch (batch.templateType()) {
            case ORGANIZATION -> {
                String code = value(row, "organizationCode");
                OrganizationVersion current = repository
                        .findOrganizationByCode(batch.legalEntityId(), code)
                        .orElse(null);
                String parentCode = value(row, "parentOrganizationCode");
                List<ImportIssue> issues = new ArrayList<>();
                if (!parentCode.isBlank()
                        && !validationContext.organizationCodes().contains(parentCode)
                        && repository.findOrganizationByCode(
                                batch.legalEntityId(), parentCode).isEmpty()) {
                    issues.add(issue(
                            batch.batchId(), rowNumber, "parentOrganizationCode",
                            "ORGANIZATION_PARENT_MISSING",
                            "上级组织编码不存在", IssueSeverity.BLOCKING, List.of()));
                }
                Map<String, Object> currentMap =
                        current == null ? null : organizationMap(current);
                if (current != null
                        && !equivalent(currentMap, proposed)
                        && invalidNextVersionDate(
                                current.effectiveFrom(),
                                current.effectiveTo(),
                                date(row, "effectiveFrom"))) {
                    issues.add(issue(
                            batch.batchId(), rowNumber, "effectiveFrom",
                            "VERSION_EFFECTIVE_DATE_INVALID",
                            "新组织版本生效日期必须晚于当前版本",
                            IssueSeverity.BLOCKING, List.of()));
                }
                if (current != null && !parentCode.isBlank()) {
                    OrganizationVersion parent = repository.findOrganizationByCode(
                            batch.legalEntityId(), parentCode).orElse(null);
                    if (parent != null
                            && repository.organizationWouldCycle(
                                    current.organizationId(), parent.organizationId())) {
                        issues.add(issue(
                                batch.batchId(), rowNumber, "parentOrganizationCode",
                                "ORGANIZATION_PARENT_CYCLE",
                                "上级组织不能是当前组织或其后代",
                                IssueSeverity.BLOCKING, List.of(parent.organizationId())));
                    }
                }
                yield new PrecheckMatch(
                        current == null
                                ? DiffCategory.ADDED
                                : equivalent(currentMap, proposed)
                                        ? DiffCategory.UNCHANGED
                                        : DiffCategory.UPDATED,
                        current == null ? null : current.organizationId(),
                        currentMap,
                        proposed,
                        issues);
            }
            case EMPLOYEE -> matchEmployee(batch, rowNumber, row, proposed);
            case EMPLOYMENT -> {
                String employeeNumber = value(row, "employeeNumber");
                String organizationCode = value(row, "organizationCode");
                EmployeeVersion employee = repository.findEmployeeByNumber(
                        batch.legalEntityId(), employeeNumber).orElse(null);
                OrganizationVersion organization = repository.findOrganizationByCode(
                        batch.legalEntityId(), organizationCode).orElse(null);
                List<ImportIssue> issues = new ArrayList<>();
                if (employee == null) {
                    issues.add(issue(
                            batch.batchId(), rowNumber, "employeeNumber",
                            "INVALID_VALUE", "员工编号不存在",
                            IssueSeverity.BLOCKING, List.of()));
                }
                if (organization == null) {
                    issues.add(issue(
                            batch.batchId(), rowNumber, "organizationCode",
                            "ORGANIZATION_PARENT_MISSING", "组织编码不存在",
                            IssueSeverity.BLOCKING, List.of()));
                } else if (!"ACTIVE".equals(organization.status())) {
                    issues.add(issue(
                            batch.batchId(), rowNumber, "organizationCode",
                            "ORGANIZATION_INACTIVE", "任职组织不是有效组织",
                            IssueSeverity.BLOCKING, List.of(organization.organizationId())));
                }
                if (employee != null && organization != null) {
                    LocalDate start = date(row, "startDate");
                    LocalDate termination = nullableDate(row, "terminationDate");
                    LocalDate end = termination == null ? null : termination.plusDays(1);
                    if (repository.hasEmploymentOverlap(
                            employee.employeeId(), start, end, null)) {
                        issues.add(issue(
                                batch.batchId(), rowNumber, "startDate",
                                "EMPLOYMENT_PERIOD_OVERLAP", "任职周期与现有周期重叠",
                                IssueSeverity.BLOCKING, List.of(employee.employeeId())));
                    }
                }
                yield new PrecheckMatch(
                        issues.isEmpty() ? DiffCategory.ADDED : DiffCategory.CONFLICT,
                        employee == null ? null : employee.employeeId(),
                        null,
                        proposed,
                        issues);
            }
            case PRIOR_SERVICE -> {
                String employeeNumber = value(row, "employeeNumber");
                EmployeeVersion employee = repository.findEmployeeByNumber(
                        batch.legalEntityId(), employeeNumber).orElse(null);
                List<ImportIssue> issues = employee == null
                        ? List.of(issue(
                                batch.batchId(), rowNumber, "employeeNumber",
                                "INVALID_VALUE", "员工编号不存在",
                                IssueSeverity.BLOCKING, List.of()))
                        : List.of();
                yield new PrecheckMatch(
                        employee == null ? DiffCategory.ERROR : DiffCategory.ADDED,
                        employee == null ? null : employee.employeeId(),
                        null,
                        proposed,
                        issues);
            }
        };
    }

    private PrecheckMatch matchEmployee(
            ImportBatch batch,
            int rowNumber,
            Map<String, Object> row,
            Map<String, Object> proposed) {
        String number = value(row, "employeeNumber");
        String externalId = value(row, "externalEmployeeId");
        if (number.isBlank() && externalId.isBlank()) {
            return new PrecheckMatch(
                    DiffCategory.ERROR, null, null, proposed,
                    List.of(issue(
                            batch.batchId(), rowNumber, "employeeNumber",
                            "EMPLOYEE_MATCH_KEY_REQUIRED",
                            "员工编号或外部精确员工 ID 至少填写一项",
                            IssueSeverity.BLOCKING, List.of())));
        }
        EmployeeVersion byNumber = number.isBlank()
                ? null
                : repository.findEmployeeByNumber(batch.legalEntityId(), number).orElse(null);
        List<EmployeeVersion> byExternal = externalId.isBlank()
                ? List.of()
                : repository.findEmployeesByExternalId(batch.legalEntityId(), externalId);
        Set<String> candidateIds = new HashSet<>();
        if (byNumber != null) {
            candidateIds.add(byNumber.employeeId());
        }
        byExternal.forEach(employee -> candidateIds.add(employee.employeeId()));
        if (candidateIds.size() > 1) {
            List<String> candidates = candidateIds.stream().sorted().toList();
            return new PrecheckMatch(
                    DiffCategory.CONFLICT, null, null, proposed,
                    List.of(issue(
                            batch.batchId(), rowNumber, "employeeNumber",
                            "EMPLOYEE_MATCH_AMBIGUOUS",
                            "精确匹配键指向多个员工，禁止按姓名或部门猜测",
                            IssueSeverity.BLOCKING, candidates)));
        }
        EmployeeVersion current = byNumber != null
                ? byNumber
                : byExternal.stream().findFirst().orElse(null);
        Map<String, Object> currentMap = current == null ? null : employeeMap(current);
        List<ImportIssue> issues = new ArrayList<>();
        if (current != null
                && !equivalent(currentMap, proposed)
                && invalidNextVersionDate(
                        current.effectiveFrom(),
                        current.effectiveTo(),
                        date(row, "effectiveFrom"))) {
            issues.add(issue(
                    batch.batchId(), rowNumber, "effectiveFrom",
                    "VERSION_EFFECTIVE_DATE_INVALID",
                    "新员工版本生效日期必须晚于当前版本",
                    IssueSeverity.BLOCKING, List.of(current.employeeId())));
        }
        return new PrecheckMatch(
                current == null
                        ? DiffCategory.ADDED
                        : equivalent(currentMap, proposed)
                                ? DiffCategory.UNCHANGED
                                : DiffCategory.UPDATED,
                current == null ? null : current.employeeId(),
                currentMap,
                proposed,
                List.copyOf(issues));
    }

    private List<String> applyDiffs(
            ImportBatch batch,
            List<ImportDiff> diffs,
            String actor,
            Instant now) {
        List<String> versionIds = new ArrayList<>();
        boolean organizationChanged = false;
        for (ImportDiff diff : orderDiffsForPublication(diffs)) {
            if (diff.category() == DiffCategory.UNCHANGED) {
                continue;
            }
            if (diff.category() == DiffCategory.CONFLICT
                    || diff.category() == DiffCategory.ERROR) {
                throw conflict(
                        "PEOPLE_IMPORT_BLOCKING_ERRORS",
                        "预检差异包含冲突或错误，禁止发布");
            }
            Map<String, Object> values = diff.proposedValues();
            switch (diff.entityType()) {
                case ORGANIZATION -> {
                    OrganizationVersion version = publishOrganization(
                            batch, diff, values, actor, now);
                    versionIds.add(version.organizationVersionId());
                    organizationChanged = true;
                }
                case EMPLOYEE -> versionIds.add(
                        publishEmployee(batch, diff, values, actor, now)
                                .employeeVersionId());
                case EMPLOYMENT -> versionIds.add(
                        publishEmployment(batch, values, actor, now)
                                .assignmentVersionId());
                case PRIOR_SERVICE -> versionIds.add(
                        publishPriorService(batch, values, actor, now)
                                .priorServiceRecordId());
            }
        }
        if (organizationChanged) {
            repository.rebuildOrganizationClosure(UUID.randomUUID().toString());
        }
        return List.copyOf(versionIds);
    }

    private void assertPrecheckStillCurrent(
            ImportBatch batch, List<ImportDiff> diffs) {
        for (ImportDiff diff : diffs) {
            if (diff.category() == DiffCategory.ADDED) {
                boolean nowExists = switch (diff.entityType()) {
                    case ORGANIZATION -> repository.findOrganizationByCode(
                            batch.legalEntityId(),
                            string(diff.proposedValues(), "organizationCode")).isPresent();
                    case EMPLOYEE -> {
                        String legalEntityId = batch.legalEntityId();
                        String employeeNumber = string(
                                diff.proposedValues(), "employeeNumber");
                        String externalId = string(
                                diff.proposedValues(), "externalEmployeeId");
                        yield repository.findEmployeeByNumber(
                                        legalEntityId, employeeNumber).isPresent()
                                || !externalId.isBlank()
                                && !repository.findEmployeesByExternalId(
                                        legalEntityId, externalId).isEmpty();
                    }
                    case EMPLOYMENT, PRIOR_SERVICE -> false;
                };
                if (nowExists) {
                    throw conflict(
                            "PEOPLE_IMPORT_PRECHECK_STALE",
                            "预检后精确业务键已被占用，请重新预检");
                }
                continue;
            }
            if (diff.category() != DiffCategory.UPDATED || diff.currentValues() == null) {
                continue;
            }
            long expected = Long.parseLong(string(diff.currentValues(), "rowVersion"));
            long actual = switch (diff.entityType()) {
                case ORGANIZATION -> repository
                        .findCurrentOrganization(diff.matchedResourceId())
                        .map(OrganizationVersion::rowVersion)
                        .orElse(-1L);
                case EMPLOYEE -> repository
                        .findCurrentEmployee(diff.matchedResourceId())
                        .map(EmployeeVersion::rowVersion)
                        .orElse(-1L);
                case EMPLOYMENT, PRIOR_SERVICE -> expected;
            };
            if (actual != expected) {
                throw conflict(
                        "PEOPLE_IMPORT_PRECHECK_STALE",
                        "预检后源数据版本已变化，请重新预检");
            }
        }
    }

    private static List<ImportDiff> orderDiffsForPublication(List<ImportDiff> diffs) {
        if (diffs.stream().noneMatch(
                diff -> diff.entityType() == TemplateType.ORGANIZATION)) {
            return diffs;
        }
        List<ImportDiff> pending = new ArrayList<>(diffs);
        List<ImportDiff> ordered = new ArrayList<>(diffs.size());
        Set<String> pendingCodes = pending.stream()
                .filter(diff -> diff.entityType() == TemplateType.ORGANIZATION)
                .map(diff -> string(diff.proposedValues(), "organizationCode"))
                .collect(java.util.stream.Collectors.toSet());
        while (!pending.isEmpty()) {
            int before = pending.size();
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                ImportDiff diff = iterator.next();
                if (diff.entityType() != TemplateType.ORGANIZATION) {
                    ordered.add(diff);
                    iterator.remove();
                    continue;
                }
                String parentCode = string(
                        diff.proposedValues(), "parentOrganizationCode");
                if (parentCode.isBlank() || !pendingCodes.contains(parentCode)) {
                    ordered.add(diff);
                    pendingCodes.remove(string(
                            diff.proposedValues(), "organizationCode"));
                    iterator.remove();
                }
            }
            if (pending.size() == before) {
                throw conflict(
                        "ORGANIZATION_PARENT_CYCLE",
                        "组织层级无法按父子顺序发布");
            }
        }
        return List.copyOf(ordered);
    }

    private OrganizationVersion publishOrganization(
            ImportBatch batch,
            ImportDiff diff,
            Map<String, Object> values,
            String actor,
            Instant now) {
        String code = string(values, "organizationCode");
        String parentCode = string(values, "parentOrganizationCode");
        String parentId = parentCode.isBlank()
                ? null
                : repository.findOrganizationByCode(batch.legalEntityId(), parentCode)
                        .orElseThrow(() -> conflict(
                                "ORGANIZATION_PARENT_CYCLE",
                                "发布时上级组织不可用"))
                        .organizationId();
        String organizationId = diff.matchedResourceId();
        long rowVersion = 0;
        if (organizationId == null) {
            organizationId = UUID.randomUUID().toString();
            repository.createOrganizationIdentity(
                    organizationId, batch.legalEntityId(), "ACTIVE", now);
        } else {
            if (parentId != null
                    && repository.organizationWouldCycle(organizationId, parentId)) {
                throw conflict(
                        "ORGANIZATION_PARENT_CYCLE",
                        "发布时上级组织不能是当前组织或其后代");
            }
            OrganizationVersion current = repository.findCurrentOrganization(organizationId)
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            if (invalidNextVersionDate(
                    current.effectiveFrom(),
                    current.effectiveTo(),
                    date(values, "effectiveFrom"))) {
                throw conflict(
                        "PEOPLE_IMPORT_PRECHECK_STALE",
                        "组织版本生效日期不再满足前向发布约束");
            }
            rowVersion = current.rowVersion() + 1;
            repository.closeCurrentOrganizationVersion(
                    organizationId,
                    date(values, "effectiveFrom")
                            .atStartOfDay(ZoneOffset.UTC).toInstant(),
                    current.rowVersion());
        }
        OrganizationVersion version = new OrganizationVersion(
                UUID.randomUUID().toString(),
                organizationId,
                batch.legalEntityId(),
                parentId,
                code,
                string(values, "name"),
                string(values, "organizationType"),
                "ACTIVE",
                date(values, "effectiveFrom"),
                null,
                "INITIAL_EXCEL",
                batch.batchId(),
                rowVersion,
                batch.reason(),
                actor,
                now,
                0);
        repository.saveOrganizationVersion(version, UUID.randomUUID().toString());
        auditService.record(
                actor, "ORGANIZATION_VERSION_CREATED", "ORGANIZATION",
                organizationId, "SUCCESS", batch.reason(),
                digest(diff.currentValues()), digest(values));
        return version;
    }

    private EmployeeVersion publishEmployee(
            ImportBatch batch,
            ImportDiff diff,
            Map<String, Object> values,
            String actor,
            Instant now) {
        String employeeId = diff.matchedResourceId();
        long rowVersion = 0;
        if (employeeId == null) {
            employeeId = UUID.randomUUID().toString();
            repository.createEmployeeIdentity(
                    employeeId,
                    batch.legalEntityId(),
                    string(values, "employeeNumber"),
                    string(values, "displayName"),
                    "ACTIVE",
                    date(values, "effectiveFrom"),
                    now);
        } else {
            EmployeeVersion current = repository.findCurrentEmployee(employeeId)
                    .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
            if (invalidNextVersionDate(
                    current.effectiveFrom(),
                    current.effectiveTo(),
                    date(values, "effectiveFrom"))) {
                throw conflict(
                        "PEOPLE_IMPORT_PRECHECK_STALE",
                        "员工版本生效日期不再满足前向发布约束");
            }
            rowVersion = current.rowVersion() + 1;
            repository.closeCurrentEmployeeVersion(
                    employeeId, date(values, "effectiveFrom"), current.rowVersion());
            repository.updateEmployeeIdentity(
                    employeeId,
                    string(values, "employeeNumber"),
                    string(values, "displayName"),
                    "ACTIVE",
                    current.rowVersion(),
                    now);
        }
        EmployeeVersion version = new EmployeeVersion(
                UUID.randomUUID().toString(),
                employeeId,
                batch.legalEntityId(),
                string(values, "employeeNumber"),
                string(values, "displayName"),
                "ACTIVE",
                emptyToNull(string(values, "externalEmployeeId")),
                date(values, "effectiveFrom"),
                null,
                "INITIAL_EXCEL",
                batch.batchId(),
                rowVersion,
                batch.reason(),
                actor,
                now);
        repository.saveEmployeeVersion(version);
        auditService.record(
                actor, "EMPLOYEE_VERSION_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", batch.reason(), digest(diff.currentValues()), digest(values));
        return version;
    }

    private EmploymentPeriod publishEmployment(
            ImportBatch batch,
            Map<String, Object> values,
            String actor,
            Instant now) {
        EmployeeVersion employee = repository.findEmployeeByNumber(
                batch.legalEntityId(), string(values, "employeeNumber"))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        repository.lockEmployee(employee.employeeId());
        OrganizationVersion organization = repository.findOrganizationByCode(
                batch.legalEntityId(), string(values, "organizationCode"))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!"ACTIVE".equals(organization.status())) {
            throw conflict(
                    "PEOPLE_IMPORT_PRECHECK_STALE",
                    "任职组织已不再有效，请重新预检");
        }
        LocalDate termination = nullableDate(values, "terminationDate");
        EmploymentPeriod period = new EmploymentPeriod(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                employee.employeeId(),
                organization.organizationId(),
                null,
                date(values, "startDate"),
                termination,
                termination == null ? null : termination.plusDays(1),
                "ACTIVE",
                batch.batchId(),
                0,
                batch.reason(),
                actor,
                now);
        long aggregateVersion =
                repository.findEmployeeAggregateVersion(employee.employeeId());
        if (repository.hasEmploymentOverlap(
                employee.employeeId(), period.startDate(), period.endExclusive(), null)) {
            throw conflict("EMPLOYMENT_PERIOD_OVERLAP", "任职周期发生重叠");
        }
        repository.saveEmploymentPeriodVersion(period, true);
        repository.touchEmployee(employee.employeeId(), aggregateVersion, now);
        auditService.record(
                actor, "EMPLOYMENT_PERIOD_CREATED", "EMPLOYMENT_PERIOD",
                period.employmentPeriodId(), "SUCCESS", batch.reason(), null, digest(period));
        return period;
    }

    private PriorServiceRecord publishPriorService(
            ImportBatch batch,
            Map<String, Object> values,
            String actor,
            Instant now) {
        EmployeeVersion employee = repository.findEmployeeByNumber(
                batch.legalEntityId(), string(values, "employeeNumber"))
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        repository.lockEmployee(employee.employeeId());
        long aggregateVersion =
                repository.findEmployeeAggregateVersion(employee.employeeId());
        List<PriorServiceRecord> existing =
                repository.listAllPriorServiceRecords(employee.employeeId());
        int currentTotal = existing.isEmpty()
                ? 0
                : existing.get(existing.size() - 1).resultingTotalDays();
        int amount = integer(values, "amountDays");
        LocalDate businessDate = date(values, "businessDate");
        if (!existing.isEmpty()
                && businessDate.isBefore(existing.get(existing.size() - 1).businessDate())) {
            throw conflict(
                    "PEOPLE_IMPORT_PRECHECK_STALE",
                    "累计工龄业务日期已被后续调整推进，请重新预检");
        }
        long resultingTotal = Math.addExact((long) currentTotal, (long) amount);
        if (resultingTotal < 0 || resultingTotal > Integer.MAX_VALUE) {
            throw conflict(
                    "PEOPLE_IMPORT_PRECHECK_STALE",
                    "累计工龄结果超出允许范围，请重新预检");
        }
        PriorServiceRecord record = new PriorServiceRecord(
                UUID.randomUUID().toString(),
                employee.employeeId(),
                "OPENING_IMPORT",
                amount,
                string(values, "reason"),
                businessDate,
                batch.batchId(),
                null,
                (int) resultingTotal,
                actor,
                now,
                auditService.currentCorrelationId(),
                existing.size());
        repository.savePriorServiceRecord(record);
        repository.touchEmployee(employee.employeeId(), aggregateVersion, now);
        auditService.record(
                actor, "PRIOR_SERVICE_ADJUSTED", "EMPLOYEE", employee.employeeId(),
                "SUCCESS", record.reason(), digest(currentTotal), digest(record.resultingTotalDays()));
        return record;
    }

    private List<String> restoreSnapshot(
            Publication publication,
            ImportBatch batch,
            String actor,
            String reason,
            Instant now) {
        List<String> restoredIds = new ArrayList<>();
        LocalDate businessDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<ImportDiff> appliedDiffs = readPublicationSnapshot(publication).items().stream()
                .filter(diff -> diff.category() == DiffCategory.ADDED
                        || diff.category() == DiffCategory.UPDATED)
                .toList();
        if (appliedDiffs.size() != publication.localVersionIds().size()) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                    "发布快照与本地版本清单不一致");
        }
        for (int index = 0; index < publication.localVersionIds().size(); index++) {
            String versionId = publication.localVersionIds().get(index);
            ImportDiff sourceDiff = appliedDiffs.get(index);
            OrganizationVersion importedOrganization =
                    repository.findOrganizationVersion(versionId).orElse(null);
            if (importedOrganization != null) {
                OrganizationVersion current = repository
                        .findCurrentOrganization(importedOrganization.organizationId())
                        .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
                LocalDate effectiveFrom = nextForwardDate(
                        businessDate, current.effectiveFrom());
                repository.closeCurrentOrganizationVersion(
                        current.organizationId(),
                        effectiveFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        current.rowVersion());
                Map<String, Object> previous = sourceDiff.currentValues();
                boolean createdByImport =
                        sourceDiff.category() == DiffCategory.ADDED || previous == null;
                String restoredCode = createdByImport
                        ? current.code()
                        : string(previous, "organizationCode");
                if (repository.organizationCodeExists(
                        current.legalEntityId(), restoredCode, current.organizationId())) {
                    throw conflict(
                            "PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES",
                            "上一快照的组织编码已被占用，只能前向更正");
                }
                OrganizationVersion restored = new OrganizationVersion(
                        UUID.randomUUID().toString(),
                        current.organizationId(),
                        current.legalEntityId(),
                        createdByImport
                                ? current.parentOrganizationId()
                                : emptyToNull(string(previous, "parentOrganizationId")),
                        restoredCode,
                        createdByImport ? current.name() : string(previous, "name"),
                        createdByImport
                                ? current.organizationType()
                                : string(previous, "organizationType"),
                        createdByImport ? "INACTIVE" : string(previous, "status"),
                        effectiveFrom,
                        null,
                        "LOCAL",
                        null,
                        current.rowVersion() + 1,
                        reason,
                        actor,
                        now,
                        current.childCount());
                repository.saveOrganizationVersion(restored, UUID.randomUUID().toString());
                restoredIds.add(restored.organizationVersionId());
                auditService.record(
                        actor, "ORGANIZATION_VERSION_CREATED", "ORGANIZATION",
                        current.organizationId(), "SUCCESS", reason,
                        digest(current), digest(restored));
                continue;
            }
            EmployeeVersion importedEmployee =
                    repository.findEmployeeVersion(versionId).orElse(null);
            if (importedEmployee != null) {
                EmployeeVersion current = repository
                        .findCurrentEmployee(importedEmployee.employeeId())
                        .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
                LocalDate effectiveFrom = nextForwardDate(
                        businessDate, current.effectiveFrom());
                repository.closeCurrentEmployeeVersion(
                        current.employeeId(), effectiveFrom, current.rowVersion());
                Map<String, Object> previous = sourceDiff.currentValues();
                boolean createdByImport =
                        sourceDiff.category() == DiffCategory.ADDED || previous == null;
                String restoredNumber = createdByImport
                        ? current.employeeNumber()
                        : string(previous, "employeeNumber");
                String restoredName = createdByImport
                        ? current.displayName()
                        : string(previous, "displayName");
                String restoredStatus = createdByImport
                        ? "TERMINATED"
                        : string(previous, "status");
                String restoredExternalId = createdByImport
                        ? current.externalEmployeeId()
                        : emptyToNull(string(previous, "externalEmployeeId"));
                if (repository.employeeNumberExists(
                        current.legalEntityId(), restoredNumber, current.employeeId())) {
                    throw conflict(
                            "PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES",
                            "上一快照的员工编号已被占用，只能前向更正");
                }
                repository.updateEmployeeIdentity(
                        current.employeeId(), restoredNumber,
                        restoredName, restoredStatus, current.rowVersion(), now);
                EmployeeVersion restored = new EmployeeVersion(
                        UUID.randomUUID().toString(),
                        current.employeeId(),
                        current.legalEntityId(),
                        restoredNumber,
                        restoredName,
                        restoredStatus,
                        restoredExternalId,
                        effectiveFrom,
                        null,
                        "LOCAL",
                        null,
                        current.rowVersion() + 1,
                        reason,
                        actor,
                        now);
                repository.saveEmployeeVersion(restored);
                restoredIds.add(restored.employeeVersionId());
                auditService.record(
                        actor, "EMPLOYEE_VERSION_CREATED", "EMPLOYEE",
                        current.employeeId(), "SUCCESS", reason,
                        digest(current), digest(restored));
                continue;
            }
            EmploymentPeriod importedEmployment =
                    repository.findEmploymentVersion(versionId).orElse(null);
            if (importedEmployment != null) {
                EmploymentPeriod current = repository
                        .findCurrentEmploymentPeriodVersion(
                                importedEmployment.employmentPeriodId())
                        .orElse(null);
                if (current != null) {
                    repository.lockEmployee(current.employeeId());
                    long aggregateVersion =
                            repository.findEmployeeAggregateVersion(current.employeeId());
                    repository.closeEmploymentPeriodVersion(
                            current.employmentPeriodId(), current.rowVersion());
                    EmploymentPeriod retracted = new EmploymentPeriod(
                            current.employmentPeriodId(),
                            UUID.randomUUID().toString(),
                            current.employeeId(),
                            current.organizationId(),
                            current.positionId(),
                            current.startDate(),
                            current.terminationDate(),
                            current.endExclusive(),
                            "RETRACTED",
                            null,
                            current.rowVersion() + 1,
                            reason,
                            actor,
                            now);
                    repository.saveEmploymentPeriodVersion(retracted, false);
                    repository.touchEmployee(current.employeeId(), aggregateVersion, now);
                    restoredIds.add(retracted.assignmentVersionId());
                    auditService.record(
                            actor, "EMPLOYMENT_PERIOD_VERSION_CREATED", "EMPLOYEE",
                            current.employeeId(), "SUCCESS", reason,
                            digest(current), digest(retracted));
                }
                continue;
            }
            PriorServiceRecord importedPriorService =
                    repository.findPriorServiceRecord(versionId).orElse(null);
            if (importedPriorService != null) {
                repository.lockEmployee(importedPriorService.employeeId());
                long aggregateVersion = repository.findEmployeeAggregateVersion(
                        importedPriorService.employeeId());
                List<PriorServiceRecord> records = repository.listAllPriorServiceRecords(
                        importedPriorService.employeeId());
                int currentTotal = records.isEmpty()
                        ? 0
                        : records.get(records.size() - 1).resultingTotalDays();
                int restoredTotal = currentTotal - importedPriorService.amountDays();
                if (restoredTotal < 0) {
                    throw conflict(
                            "PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES",
                            "累计工龄已有后续依赖，只能前向更正");
                }
                PriorServiceRecord reversal = new PriorServiceRecord(
                        UUID.randomUUID().toString(),
                        importedPriorService.employeeId(),
                        "REVERSAL",
                        -importedPriorService.amountDays(),
                        reason,
                        businessDate,
                        null,
                        importedPriorService.priorServiceRecordId(),
                        restoredTotal,
                        actor,
                        now,
                        auditService.currentCorrelationId(),
                        records.size());
                repository.savePriorServiceRecord(reversal);
                repository.touchEmployee(
                        importedPriorService.employeeId(), aggregateVersion, now);
                restoredIds.add(reversal.priorServiceRecordId());
                auditService.record(
                        actor, "PRIOR_SERVICE_ADJUSTED", "EMPLOYEE",
                        reversal.employeeId(), "SUCCESS", reason,
                        digest(currentTotal), digest(restoredTotal));
            }
        }
        if (publication.localVersionIds().stream()
                .anyMatch(id -> repository.findOrganizationVersion(id).isPresent())) {
            repository.rebuildOrganizationClosure(UUID.randomUUID().toString());
        }
        return List.copyOf(restoredIds);
    }

    private PublicationSnapshot readPublicationSnapshot(Publication publication) {
        try {
            PublicationSnapshot snapshot = objectMapper.readValue(
                    publication.snapshotJson(), PublicationSnapshot.class);
            if (snapshot == null || snapshot.items() == null) {
                throw new IllegalArgumentException("items missing");
            }
            return snapshot;
        } catch (Exception exception) {
            throw conflict(
                    "PEOPLE_IMPORT_ROLLBACK_SNAPSHOT_UNAVAILABLE",
                    "发布快照不可解析");
        }
    }

    private static LocalDate nextForwardDate(LocalDate requested, LocalDate currentFrom) {
        return requested.isAfter(currentFrom) ? requested : currentFrom.plusDays(1);
    }

    private ImportBatch requireBatch(String batchId, String capability) {
        capabilityService.require(capability);
        ImportBatch batch = repository.findBatch(batchId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
        if (!repository.canAccessLegalEntity(
                principalProvider.currentPrincipalId(),
                capability,
                batch.legalEntityId(),
                clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return batch;
    }

    private ImportBatch loadBatch(String batchId) {
        return repository.findBatch(batchId)
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
    }

    private Publication publicationFor(ImportBatch batch) {
        Publication own = repository.findPublicationByBatch(batch.batchId()).orElse(null);
        if (own != null) {
            return own;
        }
        if (batch.duplicateOfPublicationId() == null) {
            return null;
        }
        Publication original = repository.findPublicationById(
                        batch.duplicateOfPublicationId())
                .orElse(null);
        if (original == null
                || !original.legalEntityId().equals(batch.legalEntityId())
                || original.templateType() != batch.templateType()
                || !original.templateVersion().equals(batch.templateVersion())
                || !original.fileSha256().equals(batch.fileSha256())) {
            return null;
        }
        return deduplicated(original, original.publicationId());
    }

    private void requireLegalEntity(String capability, String legalEntityId) {
        capabilityService.require(capability);
        if (!repository.legalEntityExists(legalEntityId)
                || !repository.canAccessLegalEntity(
                        principalProvider.currentPrincipalId(),
                        capability,
                        legalEntityId,
                        clock.instant())) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    private IdempotencyRecord existingIdempotency(
            String actor, String action, String key, String requestDigest) {
        IdempotencyRecord existing = repository.findIdempotency(actor, action, key).orElse(null);
        if (existing != null && !existing.requestDigest().equals(requestDigest)) {
            throw conflict(
                    "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "幂等键已用于不同请求");
        }
        return existing;
    }

    private BatchValidationContext buildBatchValidationContext(
            ImportBatch batch, List<Map<String, Object>> rows) {
        Map<Integer, List<ImportIssue>> issuesByRow = new HashMap<>();
        Set<String> organizationCodes = new HashSet<>();
        Map<String, Integer> keyCounts = new HashMap<>();
        Map<String, String> organizationParents = new HashMap<>();
        Map<String, List<BatchInterval>> employmentByEmployee = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int rowNumber = Integer.parseInt(row.get("_rowNumber").toString());
            switch (batch.templateType()) {
                case ORGANIZATION -> {
                    String code = value(row, "organizationCode");
                    if (!code.isBlank()) {
                        organizationCodes.add(code);
                        keyCounts.merge("ORG:" + code, 1, Integer::sum);
                        organizationParents.put(code, value(row, "parentOrganizationCode"));
                    }
                }
                case EMPLOYEE -> {
                    String number = value(row, "employeeNumber");
                    String externalId = value(row, "externalEmployeeId");
                    if (!number.isBlank()) {
                        keyCounts.merge("EMP_NO:" + number, 1, Integer::sum);
                    }
                    if (!externalId.isBlank()) {
                        keyCounts.merge("EMP_EXT:" + externalId, 1, Integer::sum);
                    }
                }
                case EMPLOYMENT -> {
                    String number = value(row, "employeeNumber");
                    try {
                        LocalDate start = LocalDate.parse(value(row, "startDate"));
                        String terminationValue = value(row, "terminationDate");
                        LocalDate endExclusive = terminationValue.isBlank()
                                ? null
                                : LocalDate.parse(terminationValue).plusDays(1);
                        employmentByEmployee.computeIfAbsent(number, ignored -> new ArrayList<>())
                                .add(new BatchInterval(rowNumber, start, endExclusive));
                    } catch (Exception ignored) {
                        // Row-level date validation owns malformed values.
                    }
                }
                case PRIOR_SERVICE -> {
                    // No batch-unique business key beyond immutable append ordering.
                }
            }
        }
        for (Map<String, Object> row : rows) {
            int rowNumber = Integer.parseInt(row.get("_rowNumber").toString());
            if (batch.templateType() == TemplateType.ORGANIZATION) {
                String code = value(row, "organizationCode");
                if (!code.isBlank() && keyCounts.getOrDefault("ORG:" + code, 0) > 1) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "organizationCode",
                                    "DUPLICATE_BUSINESS_KEY",
                                    "同一工作簿内组织编码重复",
                                    IssueSeverity.BLOCKING, List.of()));
                }
                if (!code.isBlank() && organizationCycle(code, organizationParents)) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "parentOrganizationCode",
                                    "ORGANIZATION_PARENT_CYCLE",
                                    "同一工作簿内组织层级形成环",
                                    IssueSeverity.BLOCKING, List.of()));
                }
            } else if (batch.templateType() == TemplateType.EMPLOYEE) {
                String number = value(row, "employeeNumber");
                String externalId = value(row, "externalEmployeeId");
                if (!number.isBlank()
                        && keyCounts.getOrDefault("EMP_NO:" + number, 0) > 1) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "employeeNumber",
                                    "DUPLICATE_BUSINESS_KEY",
                                    "同一工作簿内员工编号重复",
                                    IssueSeverity.BLOCKING, List.of()));
                }
                if (!externalId.isBlank()
                        && keyCounts.getOrDefault("EMP_EXT:" + externalId, 0) > 1) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "externalEmployeeId",
                                    "DUPLICATE_BUSINESS_KEY",
                                    "同一工作簿内外部精确员工 ID 重复",
                                    IssueSeverity.BLOCKING, List.of()));
                }
            }
        }
        for (List<BatchInterval> intervals : employmentByEmployee.values()) {
            intervals.sort(java.util.Comparator.comparing(BatchInterval::start));
            for (int left = 0; left < intervals.size(); left++) {
                for (int right = left + 1; right < intervals.size(); right++) {
                    BatchInterval first = intervals.get(left);
                    BatchInterval second = intervals.get(right);
                    if (first.endExclusive() != null
                            && !second.start().isBefore(first.endExclusive())) {
                        break;
                    }
                    ImportIssue overlap = issue(
                            batch.batchId(), second.rowNumber(), "startDate",
                            "EMPLOYMENT_PERIOD_OVERLAP",
                            "同一工作簿内任职周期重叠",
                            IssueSeverity.BLOCKING, List.of());
                    addBatchIssue(issuesByRow, overlap);
                }
            }
        }
        if (batch.templateType() == TemplateType.PRIOR_SERVICE) {
            validatePriorServiceBatch(batch, rows, issuesByRow);
        }
        return new BatchValidationContext(
                Set.copyOf(organizationCodes),
                issuesByRow.entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> List.copyOf(entry.getValue()))));
    }

    private void validatePriorServiceBatch(
            ImportBatch batch,
            List<Map<String, Object>> rows,
            Map<Integer, List<ImportIssue>> issuesByRow) {
        Map<String, List<Map<String, Object>>> byEmployeeNumber = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> value(row, "employeeNumber"),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (Map.Entry<String, List<Map<String, Object>>> entry
                : byEmployeeNumber.entrySet()) {
            EmployeeVersion employee = repository.findEmployeeByNumber(
                            batch.legalEntityId(), entry.getKey())
                    .orElse(null);
            if (employee == null) {
                continue;
            }
            List<PriorServiceRecord> current =
                    repository.listAllPriorServiceRecords(employee.employeeId());
            int runningTotal = current.isEmpty()
                    ? 0
                    : current.get(current.size() - 1).resultingTotalDays();
            LocalDate latestBusinessDate = current.isEmpty()
                    ? null
                    : current.get(current.size() - 1).businessDate();
            for (Map<String, Object> row : entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(item ->
                            Integer.parseInt(item.get("_rowNumber").toString())))
                    .toList()) {
                int rowNumber = Integer.parseInt(row.get("_rowNumber").toString());
                int amount;
                LocalDate businessDate;
                try {
                    amount = Integer.parseInt(value(row, "amountDays"));
                    businessDate = LocalDate.parse(value(row, "businessDate"));
                } catch (Exception ignored) {
                    continue;
                }
                boolean valid = true;
                if (latestBusinessDate != null && businessDate.isBefore(latestBusinessDate)) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "businessDate",
                                    "PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER",
                                    "累计工龄业务日期不得早于最新发生额",
                                    IssueSeverity.BLOCKING, List.of(employee.employeeId())));
                    valid = false;
                }
                if ((long) runningTotal + amount < 0) {
                    addBatchIssue(
                            issuesByRow,
                            issue(batch.batchId(), rowNumber, "amountDays",
                                    "PRIOR_SERVICE_TOTAL_NEGATIVE",
                                    "累计工龄重放结果不得小于零",
                                    IssueSeverity.BLOCKING, List.of(employee.employeeId())));
                    valid = false;
                }
                if (valid) {
                    runningTotal = Math.addExact(runningTotal, amount);
                    latestBusinessDate = businessDate;
                }
            }
        }
    }

    private static boolean organizationCycle(
            String start, Map<String, String> parents) {
        Set<String> visited = new HashSet<>();
        String current = start;
        while (current != null && !current.isBlank() && parents.containsKey(current)) {
            if (!visited.add(current)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private static void addBatchIssue(
            Map<Integer, List<ImportIssue>> issuesByRow, ImportIssue issue) {
        issuesByRow.computeIfAbsent(issue.rowNumber(), ignored -> new ArrayList<>())
                .add(issue);
    }

    private List<ImportIssue> validateRequiredFields(
            String batchId,
            TemplateType type,
            int rowNumber,
            Map<String, Object> row) {
        List<ImportIssue> issues = new ArrayList<>();
        for (TemplateField field : workbookGateway.listTemplates(type).getFirst().fields()) {
            if (field.required() && value(row, field.key()).isBlank()) {
                issues.add(issue(
                        batchId, rowNumber, field.key(), "REQUIRED_FIELD_MISSING",
                        "必填字段为空: " + field.label(), IssueSeverity.BLOCKING, List.of()));
            }
        }
        for (String dateField : dateFields(type)) {
            String value = value(row, dateField);
            if (!value.isBlank()) {
                try {
                    LocalDate.parse(value);
                } catch (Exception exception) {
                    issues.add(issue(
                            batchId, rowNumber, dateField, "INVALID_VALUE",
                            "日期必须为 YYYY-MM-DD", IssueSeverity.BLOCKING, List.of()));
                }
            }
        }
        if (type == TemplateType.PRIOR_SERVICE && !value(row, "amountDays").isBlank()) {
            try {
                int amount = Integer.parseInt(value(row, "amountDays"));
                if (amount < -36500 || amount > 36500) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                issues.add(issue(
                        batchId, rowNumber, "amountDays", "INVALID_VALUE",
                        "发生天数必须为 -36500 至 36500 的整数",
                        IssueSeverity.BLOCKING, List.of()));
            }
        }
        switch (type) {
            case ORGANIZATION -> {
                validateLength(
                        issues, batchId, rowNumber, row,
                        "organizationCode", 128, "组织编码");
                validateLength(issues, batchId, rowNumber, row, "name", 200, "组织名称");
                validateLength(
                        issues, batchId, rowNumber, row,
                        "parentOrganizationCode", 128, "上级组织编码");
                String organizationType = value(row, "organizationType");
                if (!organizationType.isBlank()
                        && !List.of("COMPANY", "DEPARTMENT", "TEAM")
                                .contains(organizationType)) {
                    issues.add(issue(
                            batchId, rowNumber, "organizationType", "INVALID_VALUE",
                            "组织类型仅允许 COMPANY、DEPARTMENT 或 TEAM",
                            IssueSeverity.BLOCKING, List.of()));
                }
            }
            case EMPLOYEE -> {
                validateLength(
                        issues, batchId, rowNumber, row,
                        "employeeNumber", 128, "员工编号");
                validateLength(
                        issues, batchId, rowNumber, row,
                        "externalEmployeeId", 128, "外部精确员工 ID");
                validateLength(issues, batchId, rowNumber, row, "displayName", 100, "姓名");
                String externalId = value(row, "externalEmployeeId");
                if (!externalId.isBlank()) {
                    try {
                        new com.szsemicon.hr.shared.domain.ExternalPreciseId(externalId);
                    } catch (Exception exception) {
                        issues.add(issue(
                                batchId, rowNumber, "externalEmployeeId", "INVALID_VALUE",
                                "外部精确员工 ID 格式无效",
                                IssueSeverity.BLOCKING, List.of()));
                    }
                }
            }
            case EMPLOYMENT -> {
                validateLength(
                        issues, batchId, rowNumber, row,
                        "employeeNumber", 128, "员工编号");
                validateLength(
                        issues, batchId, rowNumber, row,
                        "organizationCode", 128, "组织编码");
                try {
                    LocalDate start = LocalDate.parse(value(row, "startDate"));
                    String terminationValue = value(row, "terminationDate");
                    if (!terminationValue.isBlank()
                            && LocalDate.parse(terminationValue).isBefore(start)) {
                        issues.add(issue(
                                batchId, rowNumber, "terminationDate", "INVALID_VALUE",
                                "业务离职日不得早于任职开始日",
                                IssueSeverity.BLOCKING, List.of()));
                    }
                } catch (Exception ignored) {
                    // The general date validator has already emitted the blocking issue.
                }
            }
            case PRIOR_SERVICE -> {
                validateLength(
                        issues, batchId, rowNumber, row,
                        "employeeNumber", 128, "员工编号");
                String reason = value(row, "reason");
                if (!reason.isBlank() && (reason.length() < 2 || reason.length() > 500)) {
                    issues.add(issue(
                            batchId, rowNumber, "reason", "INVALID_VALUE",
                            "原因长度必须为 2 至 500",
                            IssueSeverity.BLOCKING, List.of()));
                }
            }
        }
        return issues;
    }

    private static void validateLength(
            List<ImportIssue> issues,
            String batchId,
            int rowNumber,
            Map<String, Object> row,
            String field,
            int maxLength,
            String label) {
        String fieldValue = value(row, field);
        if (fieldValue.length() > maxLength) {
            issues.add(issue(
                    batchId, rowNumber, field, "INVALID_VALUE",
                    label + "长度不能超过 " + maxLength,
                    IssueSeverity.BLOCKING, List.of()));
        }
    }

    private static List<String> dateFields(TemplateType type) {
        return switch (type) {
            case ORGANIZATION, EMPLOYEE -> List.of("effectiveFrom");
            case EMPLOYMENT -> List.of("startDate", "terminationDate");
            case PRIOR_SERVICE -> List.of("businessDate");
        };
    }

    private void assertRequiredMapping(TemplateType type, List<MappingEntry> mapping) {
        Set<String> mapped = mapping.stream()
                .map(MappingEntry::targetField)
                .collect(java.util.stream.Collectors.toSet());
        List<String> missing = workbookGateway.listTemplates(type).getFirst().fields().stream()
                .filter(TemplateField::required)
                .map(TemplateField::key)
                .filter(field -> !mapped.contains(field))
                .toList();
        if (!missing.isEmpty()) {
            throw conflict(
                    "PEOPLE_IMPORT_MAPPING_REQUIRED",
                    "缺少必填字段映射: " + String.join(",", missing));
        }
    }

    private static void validateMapping(List<MappingEntry> mapping) {
        Set<String> sources = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (MappingEntry entry : mapping) {
            requireText(entry.sourceColumn(), "sourceColumn", 100);
            requireText(entry.targetField(), "targetField", 100);
            if (!sources.add(entry.sourceColumn()) || !targets.add(entry.targetField())) {
                throw problem(
                        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "字段映射的源列和目标字段必须唯一");
            }
        }
    }

    private void validateUpload(
            String originalFileName, String mediaType, byte[] content) {
        if (content == null || content.length == 0) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "上传文件不能为空");
        }
        if (content.length > maxFileBytes) {
            throw problem(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PEOPLE_IMPORT_FILE_TOO_LARGE",
                    "上传文件超过 20MB 限制");
        }
        if (originalFileName == null
                || originalFileName.contains("/")
                || originalFileName.contains("\\")
                || !originalFileName.toLowerCase().endsWith(".xlsx")) {
            throw problem(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_PEOPLE_IMPORT_FILE",
                    "文件名必须是不含路径的 .xlsx");
        }
        if (!com.szsemicon.hr.people.domain.PeopleModels.XLSX_MEDIA_TYPE.equals(mediaType)) {
            throw problem(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_PEOPLE_IMPORT_FILE",
                    "Content-Type 必须为标准 xlsx 媒体类型");
        }
    }

    private static ImportIssue issue(
            String batchId, int rowNumber, String field, String code,
            String message, IssueSeverity severity, List<String> candidates) {
        return new ImportIssue(
                UUID.randomUUID().toString(), batchId, rowNumber, field,
                code, message, severity, candidates);
    }

    private static int count(List<ImportDiff> diffs, DiffCategory category) {
        return (int) diffs.stream().filter(diff -> diff.category() == category).count();
    }

    private static boolean invalidNextVersionDate(
            LocalDate currentFrom, LocalDate currentTo, LocalDate nextFrom) {
        return !nextFrom.isAfter(currentFrom)
                || currentTo != null && nextFrom.isBefore(currentTo);
    }

    private static Map<String, Object> stripMetadata(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("_rowNumber");
        return Map.copyOf(result);
    }

    private Map<String, Object> organizationMap(OrganizationVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationCode", version.code());
        result.put("name", version.name());
        result.put("parentOrganizationId", nullToEmpty(version.parentOrganizationId()));
        result.put(
                "parentOrganizationCode",
                version.parentOrganizationId() == null
                        ? ""
                        : repository.findCurrentOrganization(version.parentOrganizationId())
                                .map(OrganizationVersion::code)
                                .orElse(""));
        result.put("organizationType", version.organizationType());
        result.put("status", version.status());
        result.put("rowVersion", version.rowVersion());
        result.put("effectiveFrom", version.effectiveFrom().toString());
        return result;
    }

    private static Map<String, Object> employeeMap(EmployeeVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeNumber", version.employeeNumber());
        result.put("externalEmployeeId", nullToEmpty(version.externalEmployeeId()));
        result.put("displayName", version.displayName());
        result.put("status", version.status());
        result.put("rowVersion", version.rowVersion());
        result.put("effectiveFrom", version.effectiveFrom().toString());
        return result;
    }

    private static boolean equivalent(
            Map<String, Object> current, Map<String, Object> proposed) {
        if (current == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : proposed.entrySet()) {
            if (!Objects.equals(
                    nullToEmpty(String.valueOf(current.get(entry.getKey()))),
                    nullToEmpty(String.valueOf(entry.getValue())))) {
                return false;
            }
        }
        return true;
    }

    private static String value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String string(Map<String, Object> row, String key) {
        return value(row, key);
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        return LocalDate.parse(value(row, key));
    }

    private static LocalDate nullableDate(Map<String, Object> row, String key) {
        String value = value(row, key);
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static int integer(Map<String, Object> row, String key) {
        return Integer.parseInt(value(row, key));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    private static PrecheckSummary emptySummary() {
        return new PrecheckSummary(0, 0, 0, 0, 0, 0);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw problem(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "page 必须非负且 size 必须为 1 至 100");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2 || reason.length() > 500) {
            throw problem(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "reason 长度必须为 2 至 500");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw problem(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    field + " 不能为空且长度不能超过 " + maxLength);
        }
    }

    private static void requireIdempotencyKey(String key) {
        if (!IdempotencyKeyPolicy.isValid(key)) {
            throw problem(
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency-Key 必须为 16 至 128 位字母、数字或 ._:-");
        }
    }

    private static void requireExpected(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static BatchStatus parseBatchStatus(String value) {
        try {
            return BatchStatus.valueOf(value);
        } catch (Exception exception) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "无效的批次状态");
        }
    }

    private String digest(Object value) {
        if (value instanceof byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception exception) {
                throw new IllegalStateException("SHA-256 must be available", exception);
            }
        }
        return tokenService.digest(writeJson(value));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("people value must be JSON serializable", exception);
        }
    }

    private static Publication deduplicated(Publication source, String duplicateOf) {
        return new Publication(
                source.publicationId(),
                source.batchId(),
                source.legalEntityId(),
                source.templateType(),
                source.templateVersion(),
                source.fileSha256(),
                source.snapshotDigest(),
                source.localVersionIds(),
                true,
                duplicateOf,
                source.publishedBy(),
                source.publishedAt(),
                source.snapshotJson());
    }

    private static ApiProblemException stale() {
        return conflict("STALE_VERSION", "If-Match 版本已过期");
    }

    private static ApiProblemException conflict(String code, String message) {
        return problem(HttpStatus.CONFLICT, code, message);
    }

    private static ApiProblemException problem(
            HttpStatus status, String code, String message) {
        return new ApiProblemException(status, code, message);
    }

    private record PrecheckResult(
            List<ImportDiff> diffs,
            List<ImportIssue> issues,
            PrecheckSummary summary) {
    }

    private record PublicationSnapshot(
            String batchId,
            String fileSha256,
            Long precheckVersion,
            List<ImportDiff> items) {
    }

    private record BatchValidationContext(
            Set<String> organizationCodes,
            Map<Integer, List<ImportIssue>> issuesByRow) {
    }

    private record BatchInterval(
            int rowNumber,
            LocalDate start,
            LocalDate endExclusive) {
    }

    public record BatchDetail(ImportBatch batch, Publication publication) {
    }

    public record UploadResult(ImportFile file, ImportBatch batch) {
    }

    public record PublishResult(Publication publication, ImportBatch batch) {
    }

    public record RollbackResult(Rollback rollback, ImportBatch batch) {
    }

    private record PrecheckMatch(
            DiffCategory category,
            String resourceId,
            Map<String, Object> current,
            Map<String, Object> proposed,
            List<ImportIssue> issues) {
    }
}

package com.szsemicon.hr.people.application;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleCommands.RollbackImport;
import com.szsemicon.hr.people.domain.PeopleModels.*;
import com.szsemicon.hr.people.domain.PriorServiceLedger;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class PriorServiceRollbackServiceTest {
    private PeopleImportService service;
    private PeopleRepository repository;
    private CurrentCapabilityService capabilityService;
    private CurrentPrincipalProvider principalProvider;
    private AuditService auditService;
    private PeopleWorkbookGateway workbookGateway;
    private ObjectMapper objectMapper;
    private Clock clock;
    @BeforeEach
    void setUp() {
        repository = mock(PeopleRepository.class, RETURNS_DEEP_STUBS);
        capabilityService = mock(CurrentCapabilityService.class);
        principalProvider = mock(CurrentPrincipalProvider.class);
        auditService = mock(AuditService.class);
        workbookGateway = mock(PeopleWorkbookGateway.class);
        // Use real SecurityTokenService instance
        var tokenService = new com.szsemicon.hr.shared.security.SecurityTokenService();
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneOffset.UTC);
        service = new PeopleImportService(
                capabilityService,
                principalProvider,
                repository,
                workbookGateway,
                auditService,
                tokenService,
                objectMapper,
                clock,
                20971520L,
                null);
        when(principalProvider.currentPrincipalId()).thenReturn("test-actor");
        when(auditService.currentCorrelationId()).thenReturn("test-correlation");
    }
    @Test
    void rollback_excel_360_9_years_stored_3240_produces_reversal_with_correct_metadata() {
        // Excel导入9年：stored 3240天(amountDays=3240)，normalized 3285天
        // rollback应生成: recordType=REVERSAL, version=1, reversalOfRecordId=original
        // restoredTotal=0（归零）
        String employeeId = "emp-1";
        String batchId = "batch-1";
        String publicationId = "pub-1";
        String originalRecordId = "rec-excel-1";
        // 模拟Excel 360导入记录
        PriorServiceRecord excelRecord = new PriorServiceRecord(
                originalRecordId,
                employeeId,
                "OPENING_IMPORT",
                3240,  // stored 3240天
                "Excel import 9 years",
                LocalDate.parse("2026-01-01"),
                batchId,
                null,
                3240,  // resultingTotalDays = stored
                "import-actor",
                Instant.parse("2026-01-01T00:00:00Z"),
                "req-1",
                0L,
                null,  // amountYears=NULL for Excel
                "DAYS_PER_YEAR_360",
                1);
        ImportBatch batch = new ImportBatch(
                batchId, "company-1", TemplateType.PRIOR_SERVICE, "1.0",
                BatchStatus.PUBLISHED, "test", "file-sha256", List.of(),
                new PrecheckSummary(1, 0, 0, 0, 0, 0),
                1L, 1L, "test-actor", Instant.parse("2026-01-02T00:00:00Z"),
                "test-actor", Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), null, null, null);
        Publication publication = new Publication(
                publicationId, batchId, "company-1", TemplateType.PRIOR_SERVICE,
                "1.0", "file-sha256", "digest-1", List.of(originalRecordId),
                false, null, "test-actor", Instant.parse("2026-01-02T00:00:00Z"),
                "{\"batchId\":\"" + batchId + "\",\"fileSha256\":\"file-sha256\",\"precheckVersion\":1,\"items\":[{\"diffId\":\"diff-1\",\"batchId\":\"" + batchId + "\",\"rowNumber\":1,\"entityType\":\"PRIOR_SERVICE\",\"category\":\"ADDED\",\"matchedResourceId\":\"" + employeeId + "\",\"sourceValues\":{},\"currentValues\":null,\"proposedValues\":{}}],\"localVersionIds\":[\"" + originalRecordId + "\"]}");
        when(repository.findBatch(batchId)).thenReturn(Optional.of(batch));
        when(repository.canAccessCompany(anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(repository.findPublicationByBatch(batchId)).thenReturn(Optional.of(publication));
        when(repository.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findRollbackByPublication(publicationId)).thenReturn(Optional.empty());
        when(repository.findDownstreamReferenceIds(batchId)).thenReturn(List.of());
        when(repository.hasLaterVersions(batchId, publication.publishedAt())).thenReturn(false);
        when(repository.findPriorServiceRecord(originalRecordId))
                .thenReturn(Optional.of(excelRecord));
        when(repository.listAllPriorServiceRecords(employeeId))
                .thenReturn(List.of(excelRecord));
        when(repository.findEmployeeAggregateVersion(employeeId)).thenReturn(5L);
        when(repository.updateBatchState(anyString(), anyLong(), anyString(), anyString(),
                any(Instant.class), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                any(Instant.class), isNull(), isNull())).thenReturn(true);
        when(repository.findOrganizationVersion(anyString())).thenReturn(Optional.empty());
        when(repository.findEmployeeVersion(anyString())).thenReturn(Optional.empty());
        when(repository.findEmploymentVersion(anyString())).thenReturn(Optional.empty());
        RollbackImport command = new RollbackImport("Rollback test", publicationId);
        ArgumentCaptor<PriorServiceRecord> recordCaptor = ArgumentCaptor.forClass(PriorServiceRecord.class);
        service.rollback(batchId, command, 1L, "rollback-key-0001");
        verify(repository).savePriorServiceRecord(recordCaptor.capture());
        PriorServiceRecord reversal = recordCaptor.getValue();
        assertThat(reversal.recordType()).isEqualTo("REVERSAL");
        assertThat(reversal.reversalOfRecordId()).isEqualTo(originalRecordId);
        assertThat(reversal.conversionVersion()).isEqualTo(1);
        assertThat(reversal.conversionBasis()).isEqualTo("DAYS_PER_YEAR_360");
        assertThat(reversal.amountDays()).isEqualTo(-3240);
        assertThat(reversal.amountYears()).isNull();
        assertThat(reversal.resultingTotalDays()).isEqualTo(0);
        // 验证ledger能正确replay
        List<PriorServiceRecord> allRecords = List.of(excelRecord, reversal);
        PriorServiceLedger.ReplayResult replay = PriorServiceLedger.replay(allRecords);
        assertThat(replay.totalDays()).isEqualTo(0);
        assertThat(replay.cumulativeYears()).isEqualTo(new BigDecimal("0.00"));
        assertThat(replay.cumulativeUnits()).isEqualTo(0L);
    }
    @Test
    void rollback_365_0_01_years_then_excel_9_years_produces_correct_cumulative() {
        // 场景：先添加0.01年(1314 units)，再Excel SET 9年(1182600 units)
        // rollback Excel后应剩余0.01年
        String employeeId = "emp-2";
        String batchId = "batch-2";
        String publicationId = "pub-2";
        String record365Id = "rec-365-1";
        String recordExcelId = "rec-excel-2";
        PriorServiceRecord record365 = new PriorServiceRecord(
                record365Id,
                employeeId,
                "ADJUST",
                4,  // 0.01年 ≈ 4天 normalized
                "Add 0.01 years",
                LocalDate.parse("2026-01-01"),
                null,
                null,
                4,
                "actor-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                "req-365-1",
                0L,
                new BigDecimal("0.01"),
                "DAYS_PER_YEAR_365",
                1);
        // Excel SET 9年：stored=3240，应使cumulative=1182600 units
        PriorServiceRecord recordExcel = new PriorServiceRecord(
                recordExcelId,
                employeeId,
                "OPENING_IMPORT",
                3236,  // Excel amountDays = stored - previousStored = 3240 - 4 = 3236
                "Excel SET 9 years",
                LocalDate.parse("2026-02-01"),
                batchId,
                null,
                3240,  // resultingTotalDays = Excel stored target
                "import-actor",
                Instant.parse("2026-02-01T00:00:00Z"),
                "req-excel-2",
                1L,
                null,
                "DAYS_PER_YEAR_360",
                1);
        ImportBatch batch = new ImportBatch(
                batchId, "company-1", TemplateType.PRIOR_SERVICE, "1.0",
                BatchStatus.PUBLISHED, "test", "file-sha256", List.of(),
                new PrecheckSummary(0, 1, 0, 0, 0, 0),
                1L, 1L, "test-actor", Instant.parse("2026-02-02T00:00:00Z"),
                "test-actor", Instant.parse("2026-02-02T00:00:00Z"),
                Instant.parse("2026-02-02T00:00:00Z"), null, null, null);
        Publication publication = new Publication(
                publicationId, batchId, "company-1", TemplateType.PRIOR_SERVICE,
                "1.0", "file-sha256", "digest-2", List.of(recordExcelId),
                false, null, "test-actor", Instant.parse("2026-02-02T00:00:00Z"),
                "{\"batchId\":\"" + batchId + "\",\"fileSha256\":\"file-sha256\",\"precheckVersion\":1,\"items\":[{\"diffId\":\"diff-2\",\"batchId\":\"" + batchId + "\",\"rowNumber\":1,\"entityType\":\"PRIOR_SERVICE\",\"category\":\"UPDATED\",\"matchedResourceId\":\"" + employeeId + "\",\"sourceValues\":{},\"currentValues\":{},\"proposedValues\":{}}],\"localVersionIds\":[\"" + recordExcelId + "\"]}");
        when(repository.findBatch(batchId)).thenReturn(Optional.of(batch));
        when(repository.canAccessCompany(anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(repository.findPublicationByBatch(batchId)).thenReturn(Optional.of(publication));
        when(repository.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findRollbackByPublication(publicationId)).thenReturn(Optional.empty());
        when(repository.findDownstreamReferenceIds(batchId)).thenReturn(List.of());
        when(repository.hasLaterVersions(batchId, publication.publishedAt())).thenReturn(false);
        when(repository.findPriorServiceRecord(recordExcelId))
                .thenReturn(Optional.of(recordExcel));
        when(repository.listAllPriorServiceRecords(employeeId))
                .thenReturn(List.of(record365, recordExcel));
        when(repository.findEmployeeAggregateVersion(employeeId)).thenReturn(5L);
        when(repository.updateBatchState(anyString(), anyLong(), anyString(), anyString(),
                any(Instant.class), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                any(Instant.class), isNull(), isNull())).thenReturn(true);
        when(repository.findOrganizationVersion(anyString())).thenReturn(Optional.empty());
        when(repository.findEmployeeVersion(anyString())).thenReturn(Optional.empty());
        when(repository.findEmploymentVersion(anyString())).thenReturn(Optional.empty());
        RollbackImport command = new RollbackImport("Rollback Excel", publicationId);
        ArgumentCaptor<PriorServiceRecord> recordCaptor = ArgumentCaptor.forClass(PriorServiceRecord.class);
        service.rollback(batchId, command, 1L, "rollback-key-0002");
        verify(repository).savePriorServiceRecord(recordCaptor.capture());
        PriorServiceRecord reversal = recordCaptor.getValue();
        assertThat(reversal.recordType()).isEqualTo("REVERSAL");
        assertThat(reversal.reversalOfRecordId()).isEqualTo(recordExcelId);
        assertThat(reversal.conversionVersion()).isEqualTo(1);
        // 验证ledger replay：0.01年 + Excel 9年 - Excel reversal = 0.01年
        List<PriorServiceRecord> allRecords = List.of(record365, recordExcel, reversal);
        PriorServiceLedger.ReplayResult replay = PriorServiceLedger.replay(allRecords);
        assertThat(replay.cumulativeUnits()).isEqualTo(1314L);  // 0.01年 = 1314 units
        assertThat(replay.totalDays()).isEqualTo(4);
        assertThat(replay.cumulativeYears()).isEqualTo(new BigDecimal("0.01"));
    }
}

package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.EncodedExport;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.ExportContext;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService.RequestedExportBinding;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttendanceReportExportServiceSecurityTest {

    private static final String PRINCIPAL = "principal-report";
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DIGEST = "a".repeat(64);

    @Test
    void createPersistsNormalizedPurposeAndBindsTheCurrentSnapshot() {
        Fixture fixture = new Fixture();
        YearMonth period = YearMonth.of(2026, 7);
        ReportFilter filter =
                new ReportFilter(
                        period, "legal-1", null, null, null);
        ReportSourceSnapshot currentSnapshot = snapshot(filter);
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                currentSnapshot,
                true,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        byte[] content = new byte[] {1, 2, 3};
        when(fixture.encoder.encode(
                any(), any(ExportContext.class)))
                .thenReturn(new EncodedExport(
                        "application/octet-stream", "xlsx", content));
        ArgumentCaptor<ExportContext> context =
                ArgumentCaptor.forClass(ExportContext.class);
        ArgumentCaptor<ExportJob> job =
                ArgumentCaptor.forClass(ExportJob.class);
        ArgumentCaptor<byte[]> storedContent =
                ArgumentCaptor.forClass(byte[].class);

        var result = fixture.service.create(
                ReportType.ATTENDANCE_DETAIL,
                filter,
                binding(currentSnapshot),
                "  月度考勤核对  ");

        verify(fixture.store).insert(
                job.capture(), storedContent.capture());
        verify(fixture.encoder).encode(any(), context.capture());
        assertThat(job.getValue().purpose())
                .isEqualTo("月度考勤核对");
        assertThat(job.getValue().filter().companyId())
                .isEqualTo("legal-1");
        assertThat(result.companyId()).isEqualTo("legal-1");
        assertThat(storedContent.getValue()).isEqualTo(content);
        assertThat(job.getValue().visibleContentDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(job.getValue().queryFingerprint());
        assertThat(result.purpose()).isEqualTo("月度考勤核对");
        assertThat(context.getValue().exportId())
                .isEqualTo(job.getValue().exportId());
        assertThat(context.getValue().principalId())
                .isEqualTo(PRINCIPAL);
        assertThat(context.getValue().purpose())
                .isEqualTo("月度考勤核对");
        assertThat(context.getValue().filter()).isEqualTo(filter);
        assertThat(context.getValue().authorizationScope())
                .isEqualTo(currentSnapshot.scope());
        assertThat(context.getValue().projectionVersion())
                .isEqualTo(currentSnapshot.projectionVersion());
        assertThat(context.getValue().sourceVersions())
                .containsExactlyElementsOf(currentSnapshot.sourceVersions());
        assertThat(context.getValue().dataAsOf())
                .isEqualTo(currentSnapshot.dataAsOf());
        assertThat(context.getValue().periodState())
                .isEqualTo(currentSnapshot.periodState());
        assertThat(context.getValue().createdAt()).isEqualTo(NOW);
        assertThat(context.getValue().generatedAt()).isEqualTo(NOW);
        assertThat(context.getValue().selectedFields())
                .containsExactlyElementsOf(job.getValue().exportFields());
        var order = inOrder(
                fixture.transactions,
                fixture.capabilities,
                fixture.source);
        order.verify(fixture.transactions).readCommitted(any());
        order.verify(fixture.capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE);
        order.verify(fixture.capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_READ);
        order.verify(fixture.source).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW);
    }

    @Test
    void createRejectsStaleProjectionFingerprintScopeAndUnknownField() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot currentSnapshot = snapshot(filter);
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        RequestedExportBinding current = binding(currentSnapshot);
        List<RequestedExportBinding> staleBindings = List.of(
                new RequestedExportBinding(
                        "projection-stale",
                        current.queryFingerprint(),
                        current.scopeReference(),
                        current.filterScopeReference(),
                        current.selectedFieldKeys()),
                new RequestedExportBinding(
                        current.projectionVersion(),
                        "b".repeat(64),
                        current.scopeReference(),
                        current.filterScopeReference(),
                        current.selectedFieldKeys()),
                new RequestedExportBinding(
                        current.projectionVersion(),
                        current.queryFingerprint(),
                        "authorized-scope-set:" + "b".repeat(64),
                        current.filterScopeReference(),
                        current.selectedFieldKeys()),
                new RequestedExportBinding(
                        current.projectionVersion(),
                        current.queryFingerprint(),
                        current.scopeReference(),
                        current.filterScopeReference(),
                        List.of("unknown-safe-field")));

        for (RequestedExportBinding stale : staleBindings) {
            assertThatThrownBy(() -> fixture.service.create(
                    ReportType.ATTENDANCE_DETAIL,
                    filter,
                    stale,
                    "月度考勤核对"))
                    .isInstanceOf(ApiProblemException.class)
                    .extracting("status", "code")
                    .containsExactly(
                            org.springframework.http.HttpStatus.CONFLICT,
                            "ATTENDANCE_REPORT_EXPORT_BINDING_STALE");
        }

        verify(fixture.store, never()).insert(any(), any());
        verifyNoInteractions(fixture.encoder);
    }

    @Test
    void createRejectsReadScopeNotFullyCoveredByCreateScope() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot currentSnapshot = snapshot(
                filter, fact("fact-a", "employee-a", "0001"));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                currentSnapshot,
                false,
                NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.create(
                ReportType.ATTENDANCE_DETAIL,
                filter,
                binding(currentSnapshot),
                "月度考勤核对"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never()).insert(any(), any());
        verifyNoInteractions(fixture.encoder);
    }

    @Test
    void createExportsOnlyTheRequestedAllowlistedFieldsInRequestOrder() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot currentSnapshot = snapshot(
                filter, fact("fact-a", "employee-a", "0001"));
        List<ReportField> selected = List.of(
                ReportField.EMPLOYEE_NAME,
                ReportField.EMPLOYEE_NUMBER);
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                currentSnapshot,
                false,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.encoder.encode(
                any(), any(ExportContext.class)))
                .thenReturn(new EncodedExport(
                        "application/octet-stream",
                        "xlsx",
                        new byte[] {1, 2, 3}));
        ArgumentCaptor<ReportDataSet> encodedDataSet =
                ArgumentCaptor.forClass(ReportDataSet.class);
        ArgumentCaptor<ExportContext> context =
                ArgumentCaptor.forClass(ExportContext.class);
        ArgumentCaptor<ExportJob> job =
                ArgumentCaptor.forClass(ExportJob.class);

        fixture.service.create(
                ReportType.ATTENDANCE_DETAIL,
                filter,
                binding(currentSnapshot, selected),
                "月度考勤核对");

        verify(fixture.encoder).encode(
                encodedDataSet.capture(), context.capture());
        verify(fixture.store).insert(job.capture(), any(byte[].class));
        assertThat(encodedDataSet.getValue().exportAllowlist())
                .containsExactlyElementsOf(selected);
        assertThat(job.getValue().exportFields())
                .containsExactlyElementsOf(selected);
        assertThat(context.getValue().selectedFields())
                .containsExactlyElementsOf(selected);
    }

    @Test
    void asyncBuildEncodesThePersistedJobAndRevalidatedSnapshotContext() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                "org-1",
                "employee-a",
                null);
        ReportSourceSnapshot sourceSnapshot = snapshot(
                filter, fact("fact-a", "employee-a", "0001"));
        ExportJob building = jobForSnapshot(
                sourceSnapshot, ExportStatus.BUILDING);
        when(fixture.store.claimNextQueued(NOW))
                .thenReturn(Optional.of(building));
        when(fixture.capabilities.activeCapabilities(PRINCIPAL, NOW))
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(sourceSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                sourceSnapshot,
                false,
                NOW)).thenReturn(Optional.of(sourceSnapshot));
        byte[] content = new byte[] {4, 5, 6};
        when(fixture.encoder.encode(
                any(), any(ExportContext.class)))
                .thenReturn(new EncodedExport(
                        "application/octet-stream", "xlsx", content));
        ArgumentCaptor<ReportDataSet> encodedDataSet =
                ArgumentCaptor.forClass(ReportDataSet.class);
        ArgumentCaptor<ExportContext> context =
                ArgumentCaptor.forClass(ExportContext.class);

        assertThat(fixture.service.processNextQueued()).isTrue();

        verify(fixture.encoder).encode(
                encodedDataSet.capture(), context.capture());
        assertThat(encodedDataSet.getValue().exportAllowlist())
                .containsExactlyElementsOf(building.exportFields());
        assertThat(context.getValue().exportId())
                .isEqualTo(building.exportId());
        assertThat(context.getValue().principalId())
                .isEqualTo(building.principalId());
        assertThat(context.getValue().purpose())
                .isEqualTo(building.purpose());
        assertThat(context.getValue().filter())
                .isEqualTo(building.filter());
        assertThat(context.getValue().authorizationScope())
                .isEqualTo(sourceSnapshot.scope());
        assertThat(context.getValue().projectionVersion())
                .isEqualTo(building.projectionVersion());
        assertThat(context.getValue().formulaVersion())
                .isEqualTo(building.formulaVersion());
        assertThat(context.getValue().sourceVersions())
                .containsExactlyElementsOf(sourceSnapshot.sourceVersions());
        assertThat(context.getValue().dataAsOf())
                .isEqualTo(sourceSnapshot.dataAsOf());
        assertThat(context.getValue().periodState())
                .isEqualTo(sourceSnapshot.periodState());
        assertThat(context.getValue().queryFingerprint())
                .isEqualTo(building.queryFingerprint());
        assertThat(context.getValue().visibleContentDigest())
                .isEqualTo(building.visibleContentDigest());
        assertThat(context.getValue().createdAt())
                .isEqualTo(building.createdAt());
        assertThat(context.getValue().generatedAt()).isEqualTo(NOW);
        assertThat(context.getValue().selectedFields())
                .containsExactlyElementsOf(building.exportFields());
        verify(fixture.store).markReady(
                eq("export-1"),
                eq(content),
                eq("application/octet-stream"),
                eq("xlsx"),
                any(String.class),
                eq(building.visibleContentDigest()),
                eq(NOW));
    }

    @Test
    void asyncBuildRechecksCreateAndReadCapabilitiesBeforeSourceAccess() {
        Fixture fixture = new Fixture();
        ExportJob building = job(ExportStatus.BUILDING);
        when(fixture.store.claimNextQueued(NOW))
                .thenReturn(Optional.of(building));
        when(fixture.capabilities.activeCapabilities(PRINCIPAL, NOW))
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_READ));

        assertThat(fixture.service.processNextQueued()).isTrue();

        verify(fixture.store).markFailed(
                "export-1",
                "AUTHORIZATION_OR_SOURCE_CHANGED",
                NOW);
        verifyNoInteractions(fixture.source, fixture.encoder);
    }

    @Test
    void asyncBuildFailsWhenCreateScopeNoLongerCoversReadScope() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot currentSnapshot = snapshot(
                filter, fact("fact-a", "employee-a", "0001"));
        ExportJob building = jobForSnapshot(
                currentSnapshot, ExportStatus.BUILDING);
        when(fixture.store.claimNextQueued(NOW))
                .thenReturn(Optional.of(building));
        when(fixture.capabilities.activeCapabilities(PRINCIPAL, NOW))
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                currentSnapshot,
                false,
                NOW)).thenReturn(Optional.empty());

        assertThat(fixture.service.processNextQueued()).isTrue();

        verify(fixture.store).markFailed(
                "export-1",
                "AUTHORIZATION_OR_SOURCE_CHANGED",
                NOW);
        verifyNoInteractions(fixture.encoder);
    }

    @Test
    void downloadDoesNotLoadSensitiveBlobBeforeScopeRevalidation() {
        Fixture fixture = new Fixture();
        ExportJob ready = job(ExportStatus.READY);
        when(fixture.store.findOwnedJob("export-1", PRINCIPAL))
                .thenReturn(Optional.of(ready));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                ready.filter(),
                NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                fixture.service.download("export-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never())
                .findOwnedReady(
                        any(), any(), any(), any(), any());
        var order = inOrder(
                fixture.transactions,
                fixture.capabilities,
                fixture.store,
                fixture.source);
        order.verify(fixture.transactions).serialized(any());
        order.verify(fixture.capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD);
        order.verify(fixture.capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_READ);
        order.verify(fixture.store).findOwnedJob(
                "export-1", PRINCIPAL);
        order.verify(fixture.source).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                ready.filter(),
                NOW);
    }

    @Test
    void statusDoesNotRevealMetadataAfterScopeRevocation() {
        Fixture fixture = new Fixture();
        ExportJob ready = job(ExportStatus.READY);
        when(fixture.store.findOwnedJob("export-1", PRINCIPAL))
                .thenReturn(Optional.of(ready));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                ready.filter(),
                NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.status("export-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never()).findOwnedReady(
                any(), any(), any(), any(), any());
        verify(fixture.audit).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_EXPORT_STATUS_DENIED",
                "ATTENDANCE_REPORT_EXPORT",
                "export-1",
                "DENIED",
                "AUTHORIZATION_OR_SOURCE_CHANGED");
    }

    @Test
    void statusRequiresCreateScopeToStillCoverThePersistedReadScope() {
        Fixture fixture = new Fixture();
        ReportSourceSnapshot currentSnapshot = snapshot(
                job(ExportStatus.READY).filter(),
                fact("fact-a", "employee-a", "0001"));
        ExportJob ready = jobForSnapshot(
                currentSnapshot, ExportStatus.READY);
        when(fixture.store.findOwnedJob("export-1", PRINCIPAL))
                .thenReturn(Optional.of(ready));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                ready.filter(),
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                currentSnapshot,
                false,
                NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.status("export-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.audit).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_EXPORT_STATUS_DENIED",
                "ATTENDANCE_REPORT_EXPORT",
                "export-1",
                "DENIED",
                "AUTHORIZATION_OR_SOURCE_CHANGED");
    }

    @Test
    void downloadRequiresDownloadScopeBeforeLoadingTheBlob() {
        Fixture fixture = new Fixture();
        ReportSourceSnapshot currentSnapshot = snapshot(
                job(ExportStatus.READY).filter(),
                fact("fact-a", "employee-a", "0001"));
        ExportJob ready = jobForSnapshot(
                currentSnapshot, ExportStatus.READY);
        when(fixture.store.findOwnedJob("export-1", PRINCIPAL))
                .thenReturn(Optional.of(ready));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                ready.filter(),
                NOW)).thenReturn(Optional.of(currentSnapshot));
        when(fixture.source.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD,
                currentSnapshot,
                false,
                NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                fixture.service.download("export-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never()).findOwnedReady(
                any(), any(), any(), any(), any());
        verify(fixture.audit).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_EXPORT_DOWNLOAD_DENIED",
                "ATTENDANCE_REPORT_EXPORT",
                "export-1",
                "DENIED",
                "AUTHORIZATION_OR_SOURCE_CHANGED");
    }

    @Test
    void asyncCompletionRejectsEqualCountEmployeeReplacement() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot original =
                snapshot(filter, fact("fact-a", "employee-a", "0001"));
        ReportSourceSnapshot replacement =
                snapshot(filter, fact("fact-b", "employee-b", "0002"));
        ExportJob building = jobForSnapshot(
                original, ExportStatus.BUILDING);
        when(fixture.store.claimNextQueued(NOW))
                .thenReturn(Optional.of(building));
        when(fixture.capabilities.activeCapabilities(PRINCIPAL, NOW))
                .thenReturn(Set.of(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(replacement));

        assertThat(fixture.service.processNextQueued()).isTrue();

        verify(fixture.store).markFailed(
                "export-1",
                "AUTHORIZATION_OR_SOURCE_CHANGED",
                NOW);
        verifyNoInteractions(fixture.encoder);
    }

    @Test
    void downloadRejectsEqualCountEmployeeReplacementBeforeBlobRead() {
        Fixture fixture = new Fixture();
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        ReportSourceSnapshot original =
                snapshot(filter, fact("fact-a", "employee-a", "0001"));
        ReportSourceSnapshot replacement =
                snapshot(filter, fact("fact-b", "employee-b", "0002"));
        ExportJob ready = jobForSnapshot(
                original, ExportStatus.READY);
        when(fixture.store.findOwnedJob("export-1", PRINCIPAL))
                .thenReturn(Optional.of(ready));
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(replacement));

        assertThatThrownBy(() ->
                fixture.service.download("export-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never()).findOwnedReady(
                any(), any(), any(), any(), any());
    }

    private static ExportJob job(ExportStatus status) {
        boolean ready = status == ExportStatus.READY;
        return new ExportJob(
                "export-1",
                PRINCIPAL,
                ReportType.ATTENDANCE_DETAIL,
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        "legal-1",
                        null,
                        null,
                        null),
                "月度考勤核对",
                "projection-1",
                DIGEST,
                DIGEST,
                DIGEST,
                "formula-1",
                List.of(ReportField.EMPLOYEE_NAME),
                1,
                DeliveryMode.ASYNC,
                status,
                ready ? "application/octet-stream" : null,
                ready ? "xlsx" : null,
                ready ? DIGEST : null,
                ready ? 10 : 0,
                null,
                NOW.plusSeconds(3_600),
                NOW.minusSeconds(60),
                ready ? NOW : null);
    }

    private static ExportJob jobForSnapshot(
            ReportSourceSnapshot snapshot, ExportStatus status) {
        var calculator = new AttendanceReportCalculator();
        var dataSet = calculator.calculate(
                ReportType.ATTENDANCE_DETAIL, snapshot);
        String fingerprint = AttendanceReportQueryService.fingerprint(
                ReportType.ATTENDANCE_DETAIL,
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                dataSet.calculationFormulaVersion());
        String visibilityDigest =
                AttendanceReportVisibilityDigest.calculate(
                        ReportType.ATTENDANCE_DETAIL,
                        snapshot,
                        dataSet);
        boolean ready = status == ExportStatus.READY;
        return new ExportJob(
                "export-1",
                PRINCIPAL,
                ReportType.ATTENDANCE_DETAIL,
                snapshot.filter(),
                "月度考勤核对",
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                fingerprint,
                visibilityDigest,
                dataSet.calculationFormulaVersion(),
                dataSet.exportAllowlist(),
                dataSet.rows().size(),
                DeliveryMode.ASYNC,
                status,
                ready ? "application/octet-stream" : null,
                ready ? "xlsx" : null,
                ready ? DIGEST : null,
                ready ? 10 : 0,
                null,
                NOW.plusSeconds(3_600),
                NOW.minusSeconds(60),
                ready ? NOW : null);
    }

    private static RequestedExportBinding binding(
            ReportSourceSnapshot snapshot) {
        var dataSet = new AttendanceReportCalculator().calculate(
                ReportType.ATTENDANCE_DETAIL, snapshot);
        return binding(snapshot, dataSet.exportAllowlist());
    }

    private static RequestedExportBinding binding(
            ReportSourceSnapshot snapshot,
            List<ReportField> selectedFields) {
        var dataSet = new AttendanceReportCalculator().calculate(
                ReportType.ATTENDANCE_DETAIL, snapshot);
        String fingerprint = AttendanceReportQueryService.fingerprint(
                ReportType.ATTENDANCE_DETAIL,
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                dataSet.calculationFormulaVersion());
        return new RequestedExportBinding(
                snapshot.projectionVersion(),
                fingerprint,
                snapshot.scope().reference(),
                snapshot.scope().reference(),
                selectedFields.stream().map(ReportField::key).toList());
    }

    private static ReportSourceSnapshot snapshot(ReportFilter filter) {
        return snapshot(filter, new DailyFact[0]);
    }

    private static ReportSourceSnapshot snapshot(
            ReportFilter filter, DailyFact... facts) {
        ReportFilter resolvedFilter = filter.companyId() == null
                ? new ReportFilter(
                        filter.period(),
                        "legal-1",
                        filter.organizationId(),
                        filter.employeeId(),
                        filter.status())
                : filter;
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "authorized-scope-set:" + DIGEST,
                        "公司授权范围",
                        DIGEST),
                resolvedFilter,
                "projection-1",
                "OPEN",
                NOW,
                List.of("source-1"),
                List.of(facts),
                List.of(),
                List.of(),
                List.of());
    }

    private static DailyFact fact(
            String factId,
            String employeeId,
            String employeeNumber) {
        return new DailyFact(
                factId,
                "legal-1",
                employeeId,
                employeeNumber,
                "员工-" + employeeNumber,
                "org-1",
                "org-version-1",
                "制造中心",
                LocalDate.of(2026, 7, 1),
                DayType.WEEKDAY,
                "标准班",
                480,
                480,
                0,
                0,
                0,
                480,
                1,  // scheduledAttendanceDays
                1,  // actualAttendanceDays
                0,
                0,
                0,
                0,
                null,
                null,
                "calculation-1",
                "result-" + factId);
    }

    private static final class Fixture {

        private final CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        private final AttendanceReportSourceRepository source =
                mock(AttendanceReportSourceRepository.class);
        private final AttendanceReportExportStore store =
                mock(AttendanceReportExportStore.class);
        private final AttendanceReportExportEncoder encoder =
                mock(AttendanceReportExportEncoder.class);
        private final AttendanceReportExportTransactions transactions =
                spy(new AttendanceReportExportTransactions());
        private final AuditService audit = mock(AuditService.class);
        private final CurrentPrincipalProvider principal =
                () -> PRINCIPAL;
        private final AttendanceReportExportService service =
                new AttendanceReportExportService(
                        capabilities,
                        principal,
                        source,
                        store,
                        encoder,
                        new AttendanceReportCalculator(),
                        transactions,
                        audit,
                        CLOCK,
                        Duration.ofHours(24));
    }
}

package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.identityaccess.application.AuthenticationService;
import com.szsemicon.hr.reporting.application.AttendanceReportExportEncoder.EncodedExport;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
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
    private static final String PASSWORD = "Current#Password123";

    @Test
    void createPersistsPurposeButNeverPlacesPasswordInTheJob() {
        Fixture fixture = new Fixture();
        YearMonth period = YearMonth.of(2026, 7);
        ReportFilter filter =
                new ReportFilter(
                        period, null, null, null, null);
        when(fixture.source.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(snapshot(filter)));
        byte[] content = new byte[] {1, 2, 3};
        when(fixture.encoder.encode(any(), eq(period)))
                .thenReturn(new EncodedExport(
                        "application/octet-stream", "xlsx", content));
        ArgumentCaptor<ExportJob> job =
                ArgumentCaptor.forClass(ExportJob.class);
        ArgumentCaptor<byte[]> storedContent =
                ArgumentCaptor.forClass(byte[].class);

        var result = fixture.service.create(
                ReportType.ATTENDANCE_DETAIL,
                period,
                null,
                null,
                null,
                null,
                "  月度薪资核对  ",
                PASSWORD);

        verify(fixture.authentication)
                .reauthenticateCurrentAccount(
                        PASSWORD,
                        "ATTENDANCE_REPORT_EXPORT_CREATE");
        verify(fixture.store).insert(
                job.capture(), storedContent.capture());
        assertThat(job.getValue().purpose())
                .isEqualTo("月度薪资核对");
        assertThat(job.getValue().filter().legalEntityId())
                .isEqualTo("legal-1");
        assertThat(result.legalEntityId()).isEqualTo("legal-1");
        assertThat(storedContent.getValue()).isEqualTo(content);
        assertThat(job.getValue().toString())
                .doesNotContain(PASSWORD);
        assertThat(job.getValue().visibleContentDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(job.getValue().queryFingerprint());
        assertThat(result.purpose()).isEqualTo("月度薪资核对");
        assertThat(mockingDetails(fixture.audit).getInvocations())
                .allSatisfy(invocation ->
                        assertThat(invocation.toString())
                                .doesNotContain(PASSWORD));
        var order = inOrder(
                fixture.authentication,
                fixture.transactions,
                fixture.capabilities,
                fixture.source);
        order.verify(fixture.authentication)
                .reauthenticateCurrentAccount(
                        PASSWORD,
                        "ATTENDANCE_REPORT_EXPORT_CREATE");
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
                fixture.service.download("export-1", PASSWORD))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_AVAILABLE");

        verify(fixture.store, never())
                .findOwnedReady(
                        any(), any(), any(), any(), any());
        var order = inOrder(
                fixture.authentication,
                fixture.transactions,
                fixture.capabilities,
                fixture.store,
                fixture.source);
        order.verify(fixture.authentication)
                .reauthenticateCurrentAccount(
                        PASSWORD,
                        "ATTENDANCE_REPORT_EXPORT_DOWNLOAD");
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
                fixture.service.download("export-1", PASSWORD))
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
                "月度薪资核对",
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
                "月度薪资核对",
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

    private static ReportSourceSnapshot snapshot(ReportFilter filter) {
        return snapshot(filter, new DailyFact[0]);
    }

    private static ReportSourceSnapshot snapshot(
            ReportFilter filter, DailyFact... facts) {
        ReportFilter resolvedFilter = filter.legalEntityId() == null
                ? new ReportFilter(
                        filter.period(),
                        "legal-1",
                        filter.organizationId(),
                        filter.employeeId(),
                        filter.status())
                : filter;
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.LEGAL_ENTITY,
                        "authorized-scope-set:" + DIGEST,
                        "法人授权范围",
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
        private final AuthenticationService authentication =
                mock(AuthenticationService.class);
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
                        authentication,
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

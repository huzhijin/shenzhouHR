package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.*;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.*;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real Spring transaction proxies and H2 commits, without booting the application. */
class OaReportIsolationTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T13:00:00Z");
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AttendanceSourceSyncMapper mapper;
    private AttendanceSourceSyncRepository repository;
    private AttendanceEvidenceRepository evidence;
    private OaDocumentPageTransaction pages;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:isolation-tx-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean AttendanceSourceSyncMapper mapper() { return mock(AttendanceSourceSyncMapper.class); }
        @Bean AttendanceEvidenceRepository evidence() { return mock(AttendanceEvidenceRepository.class); }
        @Bean EmployeeEmploymentResolverPort resolver() { return mock(EmployeeEmploymentResolverPort.class); }
        @Bean MyBatisAttendanceSourceSyncRepository repository(AttendanceSourceSyncMapper mapper) {
            return new MyBatisAttendanceSourceSyncRepository(mapper);
        }
        @Bean OaDocumentPageTransaction pages(AttendanceSourceSyncRepository repository,
                AttendanceEvidenceRepository evidence, EmployeeEmploymentResolverPort resolver) {
            return new OaDocumentPageTransaction(repository, evidence, resolver,
                    (org.springframework.beans.factory.ObjectProvider<OaOrgMemberDirectoryPort>) null, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("CREATE TABLE job (id VARCHAR(64) PRIMARY KEY, state VARCHAR(40), accepted INT DEFAULT 0, quarantined INT DEFAULT 0, watermark INT DEFAULT 0, error VARCHAR(80))");
        jdbc.execute("CREATE TABLE raw_write (id VARCHAR(64) PRIMARY KEY)");
        mapper = context.getBean(AttendanceSourceSyncMapper.class);
        repository = context.getBean(AttendanceSourceSyncRepository.class);
        evidence = context.getBean(AttendanceEvidenceRepository.class);
        pages = context.getBean(OaDocumentPageTransaction.class);
        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(AopUtils.isAopProxy(pages)).isTrue();
        when(mapper.lockSystemOaPageForCommit(anyString(), eq("source"))).thenAnswer(call -> pageState(call.getArgument(0)));
        when(mapper.lockOaPageForCommit(anyString(), eq("source"), anyString(), anyString(), any()))
                .thenAnswer(call -> pageState(call.getArgument(0)));
        when(mapper.incrementJobCounters(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return increment(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        });
        when(mapper.advanceWatermark(eq("source"), anyLong(), any(), any(), any())).thenAnswer(call ->
                jdbc.update("UPDATE job SET watermark=watermark+1"));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var row = call.<EvidenceRows.RawFactRow>getArgument(0);
            jdbc.update("INSERT INTO raw_write VALUES (?)", row.rawAttendanceFactId());
            return null;
        }).when(evidence).insertRawFact(any());
        when(context.getBean(EmployeeEmploymentResolverPort.class).resolveByEmployeeNumber(eq("company"), eq("E1"), any()))
                .thenReturn(List.of(new EmployeeEmploymentResolverPort.Resolution("employee", "assignment", "d".repeat(64))));
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void mandatoryCounterStillRejectsCallsOutsidePageTransaction() {
        assertThatThrownBy(() -> repository.incrementJobCounters("job", 0, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        verify(mapper, never()).incrementJobCounters(anyString(), anyInt(), anyInt());
    }

    @Test void acceptedQuarantinedAndReportIsolatedRowsAreCountedExactlyOnceWithinOneCommit() {
        createJob("job");
        var result = pages.rematchRecords(job(), "SYSTEM", "request", CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                List.of(valid(), candidate()), 2);
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isEqualTo(3);
        assertThat(counter("accepted")).isEqualTo(1);
        assertThat(counter("quarantined")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_write", Integer.class)).isEqualTo(1);
        verify(mapper, times(1)).incrementJobCounters("job", 1, 3);
    }

    @Test void legacyFiveArgumentManualEntryStillStartsItsOwnTransactionWithNoIsolationCount() {
        createJob("job");
        var result = pages.rematchRecords(job(), "principal", "request", CapabilityCodes.ATTENDANCE_SOURCE_RUN, List.of(valid()));
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isZero();
        assertThat(counter("quarantined")).isZero();
        verify(mapper, times(1)).incrementJobCounters("job", 1, 0);
    }

    @Test void counterFailureRollsBackEvidenceWatermarkAndIsolationCountsTogether() {
        createJob("job");
        doAnswer(call -> {
            increment(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new IllegalStateException("synthetic counter failure");
        }).when(mapper).incrementJobCounters(anyString(), anyInt(), anyInt());
        assertThatThrownBy(() -> pages.rematchRecords(job(), "SYSTEM", "request", CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                List.of(valid()), 1)).isInstanceOf(IllegalStateException.class);
        assertThat(counter("accepted")).isZero();
        assertThat(counter("quarantined")).isZero();
        assertThat(counter("watermark")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_write", Integer.class)).isZero();
    }

    @Test void actualReportServiceCompletesWithIsolationUsingRealMandatoryRepositoryProxy() {
        when(mapper.findAllActiveOaSourceIds()).thenReturn(List.of("source"));
        when(mapper.lockSystemOaSource(eq("source"), any())).thenReturn(
                new AttendanceSourceSyncMapper.AuthorizedOaSourceRow("source", "company", "OA", "Asia/Shanghai", "cursor"));
        doAnswer(call -> { createJob(call.getArgument(0)); return null; })
                .when(mapper).insertJob(anyString(), eq("source"), any(), any(), eq("SYSTEM"), any());
        when(mapper.markRunning(anyString(), any())).thenAnswer(call ->
                jdbc.update("UPDATE job SET state='RUNNING' WHERE id=?", call.<String>getArgument(0)));
        when(mapper.markFinished(anyString(), anyString(), any())).thenAnswer(call ->
                jdbc.update("UPDATE job SET state=? WHERE id=?", call.<String>getArgument(1), call.<String>getArgument(0)));
        when(mapper.markFailed(anyString(), anyString(), any())).thenAnswer(call ->
                jdbc.update("UPDATE job SET state='FAILED',error=? WHERE id=?", call.<String>getArgument(1), call.<String>getArgument(0)));
        when(mapper.findCreatedJob(anyString(), eq("SYSTEM"))).thenAnswer(call -> jdbc.queryForObject(
                "SELECT * FROM job WHERE id=?", (rs, n) -> new AttendanceSourceSyncModels.JobStatus(rs.getString("id"),
                        "source", "OA", "OA_ATTENDANCE", rs.getString("state"), 0, rs.getLong("accepted"),
                        rs.getLong("quarantined"), rs.getString("error"), NOW, NOW, 1), call.<String>getArgument(0)));
        var source = mock(OaAttendanceDocumentSourcePort.class);
        var draft = candidate();
        when(source.fetchOverlapping(any(), any(), any())).thenReturn(List.of(valid(), draft));
        when(source.hasNoLeaveRevocationsForSerial("source", "L1")).thenReturn(true);
        when(evidence.findUnparseableLeaveScopeRows("source", "company", List.of(draft.sourceBusinessKey())))
                .thenReturn(List.of(history()));
        when(evidence.findCompanyEmployeeNumbers("company")).thenReturn(List.of("SZJN0001"));
        var service = new OaDocumentSyncApplicationService(mock(CurrentCapabilityService.class),
                mock(CurrentPrincipalProvider.class), repository, pages, mock(AuditService.class),
                List.of(source), Clock.fixed(NOW, ZoneOffset.UTC), evidence);
        assertThat(service.refreshMonthForReport("company", YearMonth.of(2026, 9), NOW)).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT state FROM job", String.class)).isEqualTo("PARTIALLY_QUARANTINED");
        assertThat(counter("accepted")).isEqualTo(1);
        assertThat(counter("quarantined")).isEqualTo(1);
        verify(mapper, times(1)).incrementJobCounters(anyString(), eq(1), eq(1));
        verify(mapper, never()).markFailed(anyString(), anyString(), any());
    }

    private static OaDocumentRecord candidate() {
        Instant start = Instant.parse("2026-08-31T00:30:00Z");
        return new OaDocumentRecord("LEAVE:1", "2026-08-31T22:04:49:state=0:Asia/Shanghai", null, null,
                DocumentType.LEAVE, SourceStatus.PENDING, start, null, "Asia/Shanghai", start, null,
                null, null, null, true, null, null, "L1", null);
    }
    private static EvidenceRows.UnparseableLeaveScopeRow history() {
        return new EvidenceRows.UnparseableLeaveScopeRow("LEAVE:1", "raw", "OA_DOCUMENT", "norm", "VALID", "OA_INTERVAL",
                "doc", "LEAVE", true, "MATCHED", "foreign-employee", "foreign-company", "SZST0001", "SZST0001", false,
                "2026-08-31T22:04:49:state=0:Asia/Shanghai", "UNKNOWN", "L1", false);
    }

    private int increment(String jobId, int accepted, int quarantined) {
        return jdbc.update("UPDATE job SET accepted=accepted+?, quarantined=quarantined+? WHERE id=?", accepted, quarantined, jobId);
    }
    private void createJob(String id) { jdbc.update("INSERT INTO job(id,state) VALUES (?,'RUNNING')", id); }
    private int counter(String column) { return jdbc.queryForObject("SELECT " + column + " FROM job", Integer.class); }
    private AttendanceSourceSyncModels.PageState pageState(String id) {
        return new AttendanceSourceSyncModels.PageState(id, "source", "company", "RUNNING", 0, "cursor", 7L);
    }
    private AttendanceSourceSyncModels.SourceJobStart job() {
        return new AttendanceSourceSyncModels.SourceJobStart("job", "source", "company", "OA", null, "Asia/Shanghai", 200, 0, 0, "cursor");
    }
    private OaDocumentRecord valid() {
        return new OaDocumentRecord("OUTING:1", "version", "member", "E1", DocumentType.OUTING,
                SourceStatus.APPROVED, NOW.minusSeconds(3600), NOW, "Asia/Shanghai", NOW, NOW, null, null, null, true);
    }
}

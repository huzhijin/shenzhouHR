package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.AttendanceReportDecisionVersionProvider;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Full application bean discovery and real transaction proxies; only mapper I/O is stubbed. */
@SpringBootTest(properties = {
        "spring.aop.proxy-target-class=true",
        "shenzhouhr.deli.auto-sync-enabled=false",
        "shenzhouhr.deli.replay-enabled=false",
        "shenzhouhr.deli.quarantine-rematch-enabled=false",
        "shenzhouhr.oa.auto-sync-enabled=false",
        "shenzhouhr.leave.oa-recalculate-enabled=false",
        "shenzhouhr.reporting.export-worker-enabled=false"
})
@ActiveProfiles("test")
class AttendanceReportDecisionVersionsSpringStartupTest {
    private static final String COMPANY = "spring-startup-company";
    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final Instant CUTOFF = Instant.parse("2026-10-06T10:00:00Z");

    @Autowired ConfigurableApplicationContext context;
    @Autowired AttendanceReportDecisionVersionProvider decisions;
    @MockitoBean AttendanceReportCalculationMapper mapper;

    @Test
    void startsFullApplicationAndInjectsRealClassBasedReportingProxies() {
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBeansOfType(AttendanceReportDecisionVersionProvider.class))
                .containsOnlyKeys("attendanceReportDecisionVersions");
        assertThat(AopUtils.isCglibProxy(decisions)).isTrue();
        assertThat(AopUtils.getTargetClass(decisions)).isEqualTo(AttendanceReportDecisionVersions.class);
        assertThat(context.getBean(AttendanceReportDecisionVersions.class)).isSameAs(decisions);
        assertThat(AopUtils.isCglibProxy(context.getBean(FullCalculationEngineOrchestrator.class))).isTrue();
        assertThat(context.getBean(RealtimeAttendanceReportSnapshotService.class)).isNotNull();
        // The other new recognition type is a static helper, not a bean requiring a proxy.
        assertThat(context.getBeanNamesForType(OaOvertimeRecognition.class)).isEmpty();
        assertNoTransactionLeaked();
    }

    @Test
    void decisionVersionLookupIsActuallyReadOnlyTransactionalAndPreservesCutoff() {
        when(mapper.findOaOvertimeRecognitions(COMPANY, PERIOD.atDay(1),
                PERIOD.plusMonths(1).atDay(1), CUTOFF)).thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    return List.of();
                });
        when(mapper.findOaEvidenceExclusions(COMPANY,
                PERIOD.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                PERIOD.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                CUTOFF)).thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    return List.of();
                });
        assertNoTransactionLeaked();
        assertThat(decisions.versions(COMPANY, PERIOD, CUTOFF)).isEmpty();
        verify(mapper).findOaOvertimeRecognitions(COMPANY, PERIOD.atDay(1),
                PERIOD.plusMonths(1).atDay(1), CUTOFF);
        verify(mapper).findOaEvidenceExclusions(COMPANY,
                PERIOD.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                PERIOD.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                CUTOFF);
        assertNoTransactionLeaked();
    }

    @Test
    void failingLookupUnwindsTheRealTransactionAndNextCallStillWorks() {
        when(mapper.findOaOvertimeRecognitions(COMPANY, PERIOD.atDay(1),
                PERIOD.plusMonths(1).atDay(1), CUTOFF)).thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    throw new IllegalStateException("recognition read failed");
                });
        assertThatThrownBy(() -> decisions.versions(COMPANY, PERIOD, CUTOFF))
                .isInstanceOf(IllegalStateException.class).hasMessage("recognition read failed");
        assertNoTransactionLeaked();
        when(mapper.findOaOvertimeRecognitions(COMPANY, PERIOD.atDay(1),
                PERIOD.plusMonths(1).atDay(1), CUTOFF.plusSeconds(1))).thenReturn(List.of());
        assertThat(decisions.versions(COMPANY, PERIOD, CUTOFF.plusSeconds(1))).isEmpty();
        assertNoTransactionLeaked();
    }

    private static void assertNoTransactionLeaked() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    }
}

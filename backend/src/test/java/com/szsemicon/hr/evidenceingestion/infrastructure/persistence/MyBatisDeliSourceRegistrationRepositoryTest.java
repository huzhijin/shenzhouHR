package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationRepository.RegistrationState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MyBatisDeliSourceRegistrationRepositoryTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");

    private final AttendanceSourceSyncMapper mapper =
            mock(AttendanceSourceSyncMapper.class);
    private final MyBatisDeliSourceRegistrationRepository repository =
            new MyBatisDeliSourceRegistrationRepository(mapper);

    @Test
    void createsSourceConfigurationWatermarkAndCompletedIdempotencyAtomically() {
        var command = command();
        when(mapper.lockAuthorizedCompany(
                        "legal-1", "principal-1", "ATTENDANCE_SOURCE:CONFIGURE", NOW))
                .thenReturn("legal-1");
        when(mapper.findDeliSourceByCode("legal-1", "DELI_MAIN"))
                .thenReturn(null)
                .thenReturn(source());
        when(mapper.completeRegistrationIdempotency(
                        "idem-row-1", "source-1", NOW))
                .thenReturn(1);

        var result = repository.register(
                command,
                "principal-1",
                "ATTENDANCE_SOURCE:CONFIGURE",
                "idem-key-0001",
                "a".repeat(64),
                "source-1",
                "config-1",
                "idem-row-1",
                "b".repeat(64),
                NOW);

        assertThat(result.state()).isEqualTo(RegistrationState.CREATED);
        verify(mapper).insertRegistrationIdempotency(
                "idem-row-1",
                "principal-1",
                "DELI_MAIN",
                "idem-key-0001",
                "a".repeat(64),
                NOW);
        verify(mapper).insertDeliSource(
                "source-1", command, "principal-1", NOW);
        verify(mapper).insertDeliSourceConfiguration(
                "config-1",
                "source-1",
                command,
                "b".repeat(64),
                "principal-1",
                NOW);
        verify(mapper).insertWatermarkIfAbsent("source-1");
        verify(mapper).completeRegistrationIdempotency(
                "idem-row-1", "source-1", NOW);
    }

    @Test
    void exactCompletedIdempotencyReplaysWithoutAnyInsert() {
        when(mapper.lockAuthorizedCompany(
                        "legal-1", "principal-1", "ATTENDANCE_SOURCE:CONFIGURE", NOW))
                .thenReturn("legal-1");
        when(mapper.findRegistrationIdempotency(
                        "principal-1", "DELI_MAIN", "idem-key-0001"))
                .thenReturn(new AttendanceSourceSyncMapper
                        .RegistrationIdempotencyRow(
                                "idem-row-1",
                                "a".repeat(64),
                                "COMPLETED_SUCCESS"));
        when(mapper.findDeliSourceByCode("legal-1", "DELI_MAIN"))
                .thenReturn(source());

        var result = repository.register(
                command(),
                "principal-1",
                "ATTENDANCE_SOURCE:CONFIGURE",
                "idem-key-0001",
                "a".repeat(64),
                "unused-source",
                "unused-config",
                "unused-idem",
                "b".repeat(64),
                NOW);

        assertThat(result.state()).isEqualTo(RegistrationState.REPLAYED);
        verify(mapper, never()).insertDeliSource(
                any(), any(), any(), any());
        verify(mapper, never()).insertDeliSourceConfiguration(
                any(), any(), any(), any(), any(), any());
    }

    private static DeliSourceRegistrationModels.Command command() {
        return new DeliSourceRegistrationModels.Command(
                "legal-1",
                "DELI_MAIN",
                "正式得力 E+",
                "Asia/Shanghai",
                500,
                60,
                5,
                "DELI_EPLUS_APP_CREDENTIALS",
                "初始化正式来源");
    }

    private static DeliSourceRegistrationModels.SourceView source() {
        return new DeliSourceRegistrationModels.SourceView(
                "source-1",
                "legal-1",
                "DELI_MAIN",
                "正式得力 E+",
                "DELI_CLOUD",
                "ACTIVE",
                "DELI_EPLUS_CHECKIN_QUERY",
                "Asia/Shanghai",
                500,
                60,
                5,
                "DELI_EPLUS_APP_CREDENTIALS",
                1,
                NOW,
                0,
                false);
    }
}

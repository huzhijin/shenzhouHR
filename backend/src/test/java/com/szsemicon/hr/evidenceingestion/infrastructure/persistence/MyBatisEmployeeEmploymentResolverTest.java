package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import org.junit.jupiter.api.Test;

class MyBatisEmployeeEmploymentResolverTest {

    private static final Instant PUNCH_AT =
            Instant.parse("2026-07-28T16:30:00Z");

    private final EvidenceEmployeeResolverMapper mapper =
            mock(EvidenceEmployeeResolverMapper.class);
    private final MyBatisEmployeeEmploymentResolver resolver =
            new MyBatisEmployeeEmploymentResolver(mapper);

    @Test
    void preservesExactEmployeeNumberAndUsesShanghaiBusinessDate() {
        String employeeNumber = " 000A-Ab ";
        when(mapper.resolveByEmployeeNumber(
                        "company-1",
                        employeeNumber,
                        LocalDate.parse("2026-07-29")))
                .thenReturn(List.of(row(
                        "employee-1",
                        "employment-1",
                        "assignment-1",
                        "employee-version-1",
                        "organization-1",
                        null)));

        var matches = resolver.resolveByEmployeeNumber(
                "company-1", employeeNumber, PUNCH_AT);

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.employeeId()).isEqualTo("employee-1");
            assertThat(match.employmentPeriodId())
                    .isEqualTo("employment-1");
            assertThat(match.resolverSnapshotDigest())
                    .matches("[0-9a-f]{64}");
        });
        verify(mapper).resolveByEmployeeNumber(
                "company-1",
                employeeNumber,
                LocalDate.parse("2026-07-29"));
    }

    @Test
    void retainsMultipleEmploymentMatchesForFailClosedAmbiguity() {
        when(mapper.resolveByEmployeeNumber(
                        "company-1",
                        "E001",
                        LocalDate.parse("2026-07-29")))
                .thenReturn(List.of(
                        row(
                                "employee-2",
                                "employment-2",
                                "assignment-2",
                                "employee-version-2",
                                "organization-2",
                                null),
                        row(
                                "employee-1",
                                "employment-1",
                                "assignment-1",
                                "employee-version-1",
                                "organization-1",
                                null)));

        assertThat(resolver.resolveByEmployeeNumber(
                        "company-1", "E001", PUNCH_AT))
                .extracting(value ->
                        value.employeeId() + ":" + value.employmentPeriodId())
                .containsExactly(
                        "employee-1:employment-1",
                        "employee-2:employment-2");
    }

    @Test
    void confirmedBindingLookupUsesSourceLocalTimeAndIncludesBindingInDigest() {
        var withoutBinding = row(
                "employee-1",
                "employment-1",
                "assignment-1",
                "employee-version-1",
                "organization-1",
                null);
        var withBinding = row(
                "employee-1",
                "employment-1",
                "assignment-1",
                "employee-version-1",
                "organization-1",
                "binding-1");
        when(mapper.resolveByEmployeeNumber(
                        "company-1",
                        "E001",
                        LocalDate.parse("2026-07-29")))
                .thenReturn(List.of(withoutBinding));
        when(mapper.resolveByConfirmedDeliBinding(
                        "source-1",
                        "company-1",
                        "DELI_EXT_ID",
                        "deli-user-1",
                        LocalDateTime.parse("2026-07-29T00:30:00"),
                        LocalDate.parse("2026-07-29")))
                .thenReturn(List.of(withBinding));

        String numberDigest = resolver.resolveByEmployeeNumber(
                        "company-1", "E001", PUNCH_AT)
                .getFirst()
                .resolverSnapshotDigest();
        var bindingMatch = resolver.resolveByConfirmedBinding(
                "source-1",
                "company-1",
                null,
                "terminal-1",
                ConfirmedBindingKind.DELI_EXT_ID,
                "deli-user-1",
                        PUNCH_AT)
                .getFirst();

        assertThat(bindingMatch.resolverSnapshotDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(numberDigest);
        verify(mapper).resolveByConfirmedDeliBinding(
                "source-1",
                "company-1",
                "DELI_EXT_ID",
                "deli-user-1",
                LocalDateTime.parse("2026-07-29T00:30:00"),
                LocalDate.parse("2026-07-29"));
    }

    @Test
    void rejectsMalformedInputsAndNullPersistenceResults() {
        assertThatThrownBy(() -> resolver.resolveByEmployeeNumber(
                        "company-1", "E001\nOTHER", PUNCH_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolveByConfirmedBinding(
                        "source-1",
                        "company-1",
                        null,
                        null,
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "",
                        PUNCH_AT))
                .isInstanceOf(IllegalArgumentException.class);

        when(mapper.resolveByEmployeeNumber(
                        "company-1",
                        "E404",
                        LocalDate.parse("2026-07-29")))
                .thenReturn(null);
        assertThatThrownBy(() -> resolver.resolveByEmployeeNumber(
                        "company-1", "E404", PUNCH_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null");
    }

    private static EvidenceEmployeeResolverRow row(
            String employeeId,
            String employmentPeriodId,
            String assignmentVersionId,
            String employeeVersionId,
            String organizationId,
            String bindingId) {
        return new EvidenceEmployeeResolverRow(
                employeeId,
                employmentPeriodId,
                assignmentVersionId,
                employeeVersionId,
                organizationId,
                bindingId,
                3,
                4,
                5);
    }
}

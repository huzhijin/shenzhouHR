package com.szsemicon.hr.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.payroll.domain.PayrollReservationModels.EmployeePayrollProfileRef;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.FrozenAttendanceSnapshotRef;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollCalculationResultRef;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollCalculationStatus;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollItemDefinition;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollItemStatus;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriod;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriodStatus;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollRunReservation;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PayrollReservationModelsTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 1);
    private static final String DIGEST = "a".repeat(64);

    @Test
    void constructsReferenceOnlyReservationWithMatchingFrozenSnapshot() {
        PayrollPeriod period = period();
        FrozenAttendanceSnapshotRef snapshot = snapshot();
        PayrollCalculationResultRef result = new PayrollCalculationResultRef(
                id("result-1"),
                period.payrollPeriodId(),
                snapshot.attendanceSnapshotId(),
                PayrollCalculationStatus.RESERVED,
                1,
                "b".repeat(64));

        PayrollRunReservation reservation = new PayrollRunReservation(
                period,
                profile(),
                List.of(item("BASE_REFERENCE"), item("ATTENDANCE_REFERENCE")),
                snapshot,
                result);

        assertThat(reservation.items())
                .extracting(PayrollItemDefinition::itemCode)
                .containsExactly("BASE_REFERENCE", "ATTENDANCE_REFERENCE");
        assertThat(reservation.attendanceSnapshot()).isEqualTo(snapshot);
        assertThat(reservation.calculationResult()).isEqualTo(result);
    }

    @Test
    void rejectsInvalidPeriodsCodesVersionsDigestsAndDuplicateItems() {
        assertThatThrownBy(() -> new PayrollPeriod(
                id("period-1"),
                "period 2026-07",
                END,
                START,
                PayrollPeriodStatus.RESERVED,
                1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayrollPeriod(
                id("period-1"),
                "P202607",
                START,
                END,
                PayrollPeriodStatus.RESERVED,
                0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrozenAttendanceSnapshotRef(
                id("snapshot-1"), START, END, 1, Instant.EPOCH, "ABC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayrollRunReservation(
                period(),
                profile(),
                List.of(item("DUPLICATE"), item("DUPLICATE")),
                snapshot(),
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> id(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedSnapshotAndResultReferences() {
        FrozenAttendanceSnapshotRef wrongPeriodSnapshot =
                new FrozenAttendanceSnapshotRef(
                        id("snapshot-1"),
                        START.plusDays(1),
                        END,
                        1,
                        Instant.EPOCH,
                        DIGEST);
        assertThatThrownBy(() -> new PayrollRunReservation(
                period(),
                profile(),
                List.of(item("BASE_REFERENCE")),
                wrongPeriodSnapshot,
                null))
                .isInstanceOf(IllegalArgumentException.class);

        PayrollCalculationResultRef wrongResult = new PayrollCalculationResultRef(
                id("result-1"),
                id("another-period"),
                snapshot().attendanceSnapshotId(),
                PayrollCalculationStatus.RESERVED,
                1,
                DIGEST);
        assertThatThrownBy(() -> new PayrollRunReservation(
                period(),
                profile(),
                List.of(item("BASE_REFERENCE")),
                snapshot(),
                wrongResult))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservationRecordsHaveNoSensitiveOrCalculationFields() {
        Set<Class<?>> records = Set.of(
                PayrollPeriod.class,
                PayrollItemDefinition.class,
                EmployeePayrollProfileRef.class,
                FrozenAttendanceSnapshotRef.class,
                PayrollCalculationResultRef.class,
                PayrollRunReservation.class);
        Set<String> componentNames = records.stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(componentNames).noneMatch(name -> name.matches(
                ".*(amount|salary|tax|insurance|fund|bank|payment|slip|formula).*"));
    }

    private static PayrollPeriod period() {
        return new PayrollPeriod(
                id("period-1"),
                "P202607",
                START,
                END,
                PayrollPeriodStatus.RESERVED,
                1);
    }

    private static EmployeePayrollProfileRef profile() {
        return new EmployeePayrollProfileRef(
                id("profile-1"),
                id("employee-1"),
                id("company-1"),
                id("group-1"),
                1);
    }

    private static PayrollItemDefinition item(String code) {
        return new PayrollItemDefinition(
                id("item-" + code),
                code,
                PayrollItemStatus.ACTIVE,
                1);
    }

    private static FrozenAttendanceSnapshotRef snapshot() {
        return new FrozenAttendanceSnapshotRef(
                id("snapshot-1"),
                START,
                END,
                1,
                Instant.parse("2026-08-02T00:00:00Z"),
                DIGEST);
    }

    private static ExternalPreciseId id(String value) {
        return new ExternalPreciseId(value);
    }
}

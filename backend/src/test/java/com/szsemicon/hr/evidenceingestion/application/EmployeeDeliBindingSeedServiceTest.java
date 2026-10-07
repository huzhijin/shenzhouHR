package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.EmployeeRosterRow;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort.EmployeeDirectoryPerson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmployeeDeliBindingSeedServiceTest {

    @Test
    void emptyDirectoryDoesNotBlockJulyVerifiedBindings() {
        var pengWei = DeliConflictIdentityCatalog.knownBindings().stream()
                .filter(entry -> "SZST0335".equals(entry.targetEmployeeNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(EmployeeDeliBindingSeedService.directoryAccepts(
                Map.of(), pengWei)).isTrue();
    }

    @Test
    void directoryMayStillListTheDeviceEmpno() {
        var luYulei = DeliConflictIdentityCatalog.knownBindings().stream()
                .filter(entry -> "SZST0284".equals(entry.targetEmployeeNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(EmployeeDeliBindingSeedService.directoryAccepts(
                Map.of("932303205680513024", "SZST0285"), luYulei))
                .isTrue();
        assertThat(EmployeeDeliBindingSeedService.directoryAccepts(
                Map.of("932303205680513024", "SZST0284"), luYulei))
                .isTrue();
        assertThat(EmployeeDeliBindingSeedService.directoryAccepts(
                Map.of("932303205680513024", "SZST9999"), luYulei))
                .isFalse();
    }

    @Test
    void missingDirectorySnowflakeIsHeldForManualReview() {
        var liYang = DeliConflictIdentityCatalog.knownBindings().stream()
                .filter(entry -> "SZST0291".equals(entry.targetEmployeeNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(EmployeeDeliBindingSeedService.directoryAccepts(
                Map.of("other-id", "SZST0001"), liYang))
                .isFalse();
    }

    @Test
    void collidingDeviceEmpnoBindsTheNamedPersonNotTheEmpnoOwner() {
        var pengWei = new EmployeeRosterRow("id-peng", "SZST0335", "彭伟");
        var zhao = new EmployeeRosterRow("id-zhao", "SZST0289", "赵艺娴");
        var byNumber = Map.of(
                "SZST0335", List.of(pengWei),
                "SZST0289", List.of(zhao));
        var byName = Map.of(
                "彭伟", List.of(pengWei),
                "赵艺娴", List.of(zhao));

        var decision = EmployeeDeliBindingSeedService.decide(
                new EmployeeDirectoryPerson(
                        "939805188834107393", "SZST0289", "彭伟"),
                byNumber,
                byName);

        assertThat(decision.employeeNumber()).isEqualTo("SZST0335");
        assertThat(decision.reason()).isEqualTo("NAME_OVER_DEVICE_EMPNO");
    }

    @Test
    void uniqueEmpnoBindsAnyoneInTheDirectoryNotJustTheJulyExamples() {
        var other = new EmployeeRosterRow("id-other", "SZST0800", "示例员");
        var decision = EmployeeDeliBindingSeedService.decide(
                new EmployeeDirectoryPerson(
                        "1280000000000000001", "SZST0800", "示例员"),
                Map.of("SZST0800", List.of(other)),
                Map.of("示例员", List.of(other)));

        assertThat(decision.employeeNumber()).isEqualTo("SZST0800");
        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
    }

    @Test
    void duplicateNamesAreNotAutoBound() {
        var a = new EmployeeRosterRow("id-a", "SZST0801", "同名");
        var b = new EmployeeRosterRow("id-b", "SZST0802", "同名");
        var decision = EmployeeDeliBindingSeedService.decide(
                new EmployeeDirectoryPerson(
                        "1280000000000000002", "SZST9998", "同名"),
                Map.of(),
                Map.of("同名", List.of(a, b)));
        assertThat(decision).isNull();
    }

    @Test
    void unlistedInternEmpnoBindsUniqueRosterName() {
        var zhang = new EmployeeRosterRow("id-zgq", "SZST0677", "张国庆");
        var decision = EmployeeDeliBindingSeedService.decide(
                new EmployeeDirectoryPerson(
                        "1240000000000000001", "SZSTSX61", "张国庆"),
                Map.of(),
                Map.of("张国庆", List.of(zhang)));
        assertThat(decision.reason()).isEqualTo("DISPLAY_NAME");
        assertThat(decision.employeeNumber()).isEqualTo("SZST0677");
    }

    @Test
    void existingEmpnoKeepsHrPersonWhenNamesMatch() {
        var zhangChen = new EmployeeRosterRow("id-zcy", "SZST0663", "张晨阳");
        var decision = EmployeeDeliBindingSeedService.decide(
                new EmployeeDirectoryPerson(
                        "1250000000000000001", "SZST0663", "张晨阳"),
                Map.of("SZST0663", List.of(zhangChen)),
                Map.of("张晨阳", List.of(zhangChen)));
        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
        assertThat(decision.employeeNumber()).isEqualTo("SZST0663");
    }

    @Test
    void shortDirectoryIdIsNotASnowflake() {
        assertThat(EmployeeDeliBindingSeedService.isSnowflakePersonId("387"))
                .isFalse();
        assertThat(EmployeeDeliBindingSeedService.isSnowflakePersonId(
                "939805188834107393")).isTrue();
    }

    @Test
    void catalogContainsJulyConflictSnowflakeBindings() {
        var catalog = DeliConflictIdentityCatalog.knownBindings();
        assertThat(catalog).anyMatch(entry ->
                "SZST0284".equals(entry.targetEmployeeNumber())
                        && "932303205680513024".equals(entry.deliUserId()));
        assertThat(catalog).anyMatch(entry ->
                "SZST0335".equals(entry.targetEmployeeNumber())
                        && "939805188834107393".equals(entry.deliUserId()));
        assertThat(catalog).anyMatch(entry ->
                "SZST0291".equals(entry.targetEmployeeNumber())
                        && "947095089162633216".equals(entry.deliUserId()));
        assertThat(catalog).anyMatch(entry ->
                "SZST0694".equals(entry.targetEmployeeNumber())
                        && "1248197527100440576".equals(entry.deliUserId()));
    }

    @Test
    void bindingEffectiveFromCoversAugustReplayWindow() {
        assertThat(EmployeeDeliBindingSeedService.BINDING_EFFECTIVE_FROM)
                .isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00+08:00"));
    }
}
